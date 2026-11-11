#!/usr/bin/env python3
"""Build on a disposable Colab runtime; only short-lived Drive tokens leave Actions."""

import argparse
import base64
import datetime as dt
import hashlib
import json
import logging
import os
import re
import signal
import sys
import time
import uuid
import zipfile
from pathlib import Path
from urllib.parse import quote, urlsplit

import requests
from artifacts import APK_PATHS, require, verify_bundle, version
from google.auth.transport.requests import AuthorizedSession, Request
from google.oauth2.credentials import Credentials
from jupyter_kernel_client import JupyterSubprotocol, KernelClient

API = "https://colaboratory.googleapis.com/"
DRIVE = "https://www.googleapis.com/drive/v3/files"
REMOTE = "/content/rikkahub-benchmark"  # Stable paths retain Gradle/Kotlin cache relocatability.
SPEC = {"variant": "VARIANT_TPU", "accelerator": "V5E1", "shape": "SHAPE_STANDARD"}
SCOPE = "https://www.googleapis.com/auth/drive.file"


class Controller:
    def __init__(self, args):
        self.args = args
        raw = os.environ.get("COLAB_ADC_JSON")
        self.adc = json.loads(raw) if raw else json.loads(args.adc.read_text())
        credentials = Credentials.from_authorized_user_info(
            self.adc
        ).with_quota_project(args.project)
        self.http = AuthorizedSession(credentials)
        self.state = (
            json.loads(args.state.read_text())
            if args.cleanup and args.state.exists()
            else {}
        )

    def save(self):
        self.args.state.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.args.state.with_suffix(".pending.json")
        temporary.write_text(json.dumps(self.state, indent=2) + "\n")
        temporary.replace(self.args.state)

    def api(self, method, path, **kwargs):
        require(
            path.startswith(("v1beta/", "v1/operations/")), "Invalid Colab API route"
        )
        for attempt in range(4):
            try:
                response = self.http.request(
                    method, API + path, timeout=(15, 40), **kwargs
                )
            except requests.RequestException:
                if attempt == 3:
                    raise RuntimeError("Colab API connection failed") from None
                time.sleep(2**attempt)
                continue
            if response.status_code in (429, 500, 502, 503, 504) and attempt < 3:
                response.close()
                time.sleep(2**attempt)
                continue
            require(response.ok, f"Colab API HTTP {response.status_code}")
            return response.json()

    def drive_token(self):
        # Deliberately do not transfer the ADC refresh token, Colab scope, or GitHub token.
        credentials = Credentials.from_authorized_user_info(self.adc, scopes=[SCOPE])
        credentials.refresh(Request())
        response = requests.get(
            "https://oauth2.googleapis.com/tokeninfo",
            params={"access_token": credentials.token},
            timeout=30,
        )
        require(
            response.ok and set(response.json().get("scope", "").split()) == {SCOPE},
            "Could not verify a Drive-only short-lived token",
        )
        return json.dumps(
            {
                "access_token": credentials.token,
                "scope": SCOPE,
                "expires_at": credentials.expiry.isoformat(),
                "quota_project_id": self.args.project,
            }
        ).encode()

    def cache_manifest(self):
        folder = self.args.folder
        response = self.http.get(
            DRIVE + "/" + folder,
            params={"fields": "mimeType,shared,trashed"},
            timeout=30,
        )
        require(response.ok, "Drive cache folder is inaccessible")
        metadata = response.json()
        require(
            metadata.get("mimeType") == "application/vnd.google-apps.folder"
            and not metadata.get("shared")
            and not metadata.get("trashed"),
            "Drive cache folder must be private",
        )
        # Immutable manifests are committed only after a successful full cache upload.
        query = (
            f"'{folder}' in parents and trashed=false and mimeType='application/json' "
            "and appProperties has { key='rikkahubCache' and value='v2' }"
        )
        response = self.http.get(
            DRIVE,
            params={
                "q": query,
                "orderBy": "createdTime desc",
                "pageSize": 20,
                "fields": "files(id,size,sha256Checksum,shared,appProperties)",
            },
            timeout=30,
        )
        require(response.ok, "Drive cache listing failed")
        for entry in response.json().get("files", []):
            require(
                not entry.get("shared") and int(entry["size"]) < 65536,
                "Invalid cache manifest metadata",
            )
            content = self.http.get(
                DRIVE + "/" + entry["id"], params={"alt": "media"}, timeout=30
            )
            require(
                content.ok
                and hashlib.sha256(content.content).hexdigest()
                == entry["sha256Checksum"],
                "Drive cache manifest digest mismatch",
            )
            manifest = content.json()
            if manifest.get("schema") != 2:
                continue
            compatible = manifest.get("compatibility", {})
            # Compare source-controlled dependency inputs before allocating a machine.
            if all(
                hashlib.sha256((self.args.repo / name).read_bytes()).hexdigest()
                == compatible.get(name)
                for name in (
                    "gradle/wrapper/gradle-wrapper.properties",
                    "gradle/libs.versions.toml",
                    "web-ui/pnpm-lock.yaml",
                )
            ):
                require(manifest.get("folderId") == folder, "Cache folder mismatch")
                self.state["cacheManifestId"] = entry["id"]
                return manifest
        return None

    def allocate(self):
        runtime_id = "rikkahub-ci-" + uuid.uuid4().hex[:24]
        self.state.update(
            runtimeName="runtimes/" + runtime_id,
            requestId=str(uuid.uuid4()),
            released=False,
        )
        self.save()  # Record ownership before sending even the first create request.
        operation = self.api(
            "POST",
            "v1beta/runtimes",
            params={"runtimeId": runtime_id, "requestId": self.state["requestId"]},
            json={"runtimeSpec": SPEC},
        )
        self.state["operationName"] = operation.get("name")
        self.save()
        deadline = time.monotonic() + 300
        while not operation.get("done"):
            require(time.monotonic() < deadline, "Colab allocation timed out")
            time.sleep(3)
            operation = self.api("GET", "v1/" + operation["name"])
        require(
            not operation.get("error"),
            "Colab allocation refused; use the Actions backend if capacity is unavailable",
        )
        return Runtime(self)

    def cleanup(self):
        if not self.state.get("runtimeName") or self.state.get("released"):
            return
        require(
            re.fullmatch(
                r"runtimes/rikkahub-ci-[0-9a-f]{24}", self.state["runtimeName"]
            ),
            "Refusing to delete an unrecognized runtime",
        )
        operation = self.state.get("operationName")
        if operation:
            deadline = time.monotonic() + 180
            while not self.api("GET", "v1/" + operation).get("done"):
                require(
                    time.monotonic() < deadline,
                    "Creation still pending; runtime cleanup must be retried",
                )
                time.sleep(5)
        self.api(
            "DELETE",
            "v1beta/" + self.state["runtimeName"],
            params={"allowMissing": "true"},
        )
        self.state["released"] = True
        self.save()
        print("Colab runtime released", flush=True)

    def build(self):
        require(
            re.fullmatch(r"[0-9a-f]{40}", self.args.source),
            "A full source SHA is required",
        )
        require(re.fullmatch(r"[\w-]+", self.args.folder), "Invalid Drive folder ID")
        self.state = {
            "sourceCommit": self.args.source,
            "jobId": uuid.uuid4().hex,
            "runtimeSpec": SPEC,
        }
        cache = self.cache_manifest()
        self.save()
        try:
            runtime = self.allocate()
            runtime.execute(
                "from pathlib import Path\nimport os\nos.umask(0o077)\n"
                f"Path({REMOTE!r}).mkdir(parents=True, exist_ok=True)\n"
                f"Path({(REMOTE + '/.private')!r}).mkdir(exist_ok=True)\n"
            )
            for name in ("remote_build.py", "cache_store.py", "artifacts.py"):
                runtime.put(
                    REMOTE + "/" + name, (Path(__file__).parent / name).read_bytes()
                )
            runtime.put(REMOTE + "/.private/drive-access.json", self.drive_token())
            config = {
                "sourceCommit": self.args.source,
                "jobId": self.state["jobId"],
                "cacheFolderId": self.args.folder,
                "cacheManifest": cache,
            }
            runtime.put(REMOTE + "/job.json", json.dumps(config).encode())
            code = (
                "import os, subprocess\nfrom pathlib import Path\n"
                f"claim = Path({(REMOTE + '/job.claim')!r})\n"
                "if not claim.exists():\n"
                " claim.touch(exist_ok=False)\n"
                f" with open({(REMOTE + '/remote.log')!r}, 'wb') as log:\n"
                f"  subprocess.Popen(['python3', {REMOTE + '/remote_build.py'!r}], "
                "stdout=log, stderr=subprocess.STDOUT, start_new_session=True)\n"
            )
            try:
                runtime.execute(code)
            except Exception:  # noqa: BLE001 - recover transport failures without leaking proxy URLs.
                # An interrupted launch acknowledgement is not a failed or new build.
                print(
                    "Launch connection interrupted; checking the existing job",
                    flush=True,
                )
            started = time.monotonic()
            last_print = 0
            previous = None
            seen = False
            try:
                while time.monotonic() - started < 2400:
                    try:
                        raw = runtime.get(REMOTE + "/job-status.json", missing=True)
                    except (RuntimeError, requests.RequestException):
                        time.sleep(5)
                        continue
                    if raw:
                        status = json.loads(raw)
                        require(
                            status.get("jobId") == self.state["jobId"],
                            "Unexpected remote job ID",
                        )
                        seen = True
                        if (
                            status["step"] != previous
                            or time.monotonic() - last_print > 30
                        ):
                            print(
                                f"Colab: {status['step']} ({status['elapsedSeconds']}s)",
                                flush=True,
                            )
                            last_print = time.monotonic()
                            previous = status["step"]
                        if status.get("finished"):
                            result = json.loads(runtime.get(REMOTE + "/result.json"))
                            require(
                                result.get("jobId") == self.state["jobId"]
                                and result.get("sourceCommit") == self.args.source,
                                "Remote result identity mismatch",
                            )
                            self.state["result"] = result
                            self.save()
                            require(
                                result.get("success"),
                                "Colab build failed; see the build log",
                            )
                            break
                    require(
                        seen or time.monotonic() - started < 150,
                        "Remote worker did not start",
                    )
                    time.sleep(8)
                else:
                    raise RuntimeError("Colab build exceeded 40 minutes")
            finally:
                try:
                    log = runtime.get(REMOTE + "/remote.log", missing=True)
                    if log:
                        self.args.state.with_suffix(".log").write_bytes(log)
                except Exception:  # noqa: BLE001 - recover transport failures without leaking proxy URLs.
                    print("Remote log unavailable", flush=True)
            output = self.args.output
            output.mkdir(parents=True, exist_ok=False)
            bundle = output.parent / "prebuilt.zip"
            runtime.download(REMOTE + "/prebuilt.zip", bundle, result["bundle"])
            with zipfile.ZipFile(bundle) as archive:
                entries = archive.infolist()
                require(
                    len(entries) <= 1000
                    and sum(e.file_size for e in entries) < 250 * 1024**2,
                    "Oversized artifact bundle",
                )
                require(
                    len({e.filename for e in entries}) == len(entries),
                    "Duplicate artifact ZIP entries",
                )
                for entry in entries:
                    name = entry.filename
                    require(
                        name in {*APK_PATHS, "manifest.json"}
                        or re.fullmatch(
                            r"test-results/(app|speech)/TEST-[\w.$-]+\.xml", name
                        ),
                        "Unexpected artifact ZIP path",
                    )
                    target = output / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(entry) as incoming, target.open("wb") as outgoing:
                        import shutil

                        shutil.copyfileobj(incoming, outgoing)
            manifest = verify_bundle(output, self.args.source, version(self.args.repo))
            require(
                manifest.get("jobId") == self.state["jobId"],
                "Artifact job identity mismatch",
            )
            bundle.unlink()
            self.state["success"] = True
            self.save()
            print(
                "Colab APKs and fresh test reports downloaded and verified", flush=True
            )
        finally:
            self.cleanup()


class Runtime:
    def __init__(self, controller):
        self.controller = controller
        self.http = requests.Session()
        self.refresh_at = 0
        self.refresh()

    def refresh(self):
        if time.monotonic() < self.refresh_at:
            return
        info = self.controller.api(
            "GET", "v1beta/" + self.controller.state["runtimeName"]
        )
        connection = info["connectionInfo"]
        self.url = connection["url"].rstrip("/")
        require(urlsplit(self.url).scheme == "https", "Runtime must use HTTPS")
        self.token = connection["token"]
        self.http.headers.update(
            {
                "X-Colab-Runtime-Proxy-Token": self.token,
                "X-Colab-Client-Agent": "colab-cli",
            }
        )
        expires = dt.datetime.fromisoformat(connection["expireTime"])
        self.refresh_at = time.monotonic() + max(
            10, (expires - dt.datetime.now(dt.UTC)).total_seconds() - 120
        )

    def request(self, method, path, missing=False, **kwargs):
        for attempt in range(4):
            self.refresh()
            try:
                response = self.http.request(
                    method, self.url + "/" + path, timeout=(15, 50), **kwargs
                )
            except requests.RequestException:
                if attempt == 3:
                    raise RuntimeError("Colab runtime connection interrupted") from None
                time.sleep(2**attempt)
                continue
            if response.status_code in (401, 403):
                self.refresh_at = 0
            if response.status_code == 404 and missing:
                return None
            if (
                response.status_code in (401, 403, 429, 500, 502, 503, 504)
                and attempt < 3
            ):
                response.close()
                time.sleep(2**attempt)
                continue
            require(response.ok, f"Runtime HTTP {response.status_code}")
            return response

    def put(self, path, content):
        self.request(
            "PUT",
            "api/contents/" + quote(path.strip("/"), safe="/"),
            params={"authuser": "0"},
            json={
                "type": "file",
                "format": "base64",
                "content": base64.b64encode(content).decode(),
            },
        )

    def get(self, path, missing=False):
        response = self.request(
            "GET",
            "api/contents/" + quote(path.strip("/"), safe="/"),
            missing=missing,
            params={"content": "1", "authuser": "0"},
        )
        if response is None:
            return None
        content = response.json()
        return (
            base64.b64decode(content["content"])
            if content["format"] == "base64"
            else content["content"].encode()
        )

    def execute(self, code):
        self.refresh()
        client = KernelClient(
            server_url=self.url,
            token=self.token,
            headers={
                "X-Colab-Client-Agent": "colab-cli",
                "X-Colab-Runtime-Proxy-Token": self.token,
            },
            client_kwargs={
                "subprotocol": JupyterSubprotocol.DEFAULT,
                "extra_params": {"colab-runtime-proxy-token": self.token},
            },
        )
        client._own_kernel = (
            False  # Closing the short client must not terminate the background build.
        )
        try:
            client.start()
            result = client.execute(code, timeout=120)
            require(result and result.get("status") == "ok", "Remote bootstrap failed")
        finally:
            client.stop()

    def download(self, path, target, expected):
        require(0 < expected["size"] < 250 * 1024**2, "Unexpected artifact size")
        for attempt in range(3):
            try:
                response = self.request(
                    "GET",
                    "files/" + quote(path.strip("/"), safe="/"),
                    stream=True,
                    params={"authuser": "0"},
                )
                size = 0
                checksum = hashlib.sha256()
                with response, target.open("wb") as handle:
                    for block in response.iter_content(1024**2):
                        size += len(block)
                        require(
                            size <= expected["size"],
                            "Artifact download exceeded declared size",
                        )
                        handle.write(block)
                        checksum.update(block)
                require(
                    size == expected["size"]
                    and checksum.hexdigest() == expected["sha256"],
                    "Artifact download digest mismatch",
                )
                return
            except requests.RequestException:
                if attempt == 2:
                    raise RuntimeError("Artifact transfer failed") from None


def main():
    os.umask(0o077)
    logging.disable(
        logging.CRITICAL
    )  # Transport exceptions may contain proxy credentials.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, default=Path("build/prebuilt"))
    parser.add_argument("--state", type=Path, default=Path("build/colab-state.json"))
    parser.add_argument(
        "--adc", type=Path, default=Path.home() / ".config/colab-cli/adc.json"
    )
    parser.add_argument("--project", default=os.environ.get("COLAB_PROJECT_ID"))
    parser.add_argument("--folder", default=os.environ.get("COLAB_CACHE_FOLDER_ID"))
    parser.add_argument("--cleanup", action="store_true")
    args = parser.parse_args()
    if args.cleanup and not args.state.exists():
        return
    if os.environ.get("GITHUB_ACTIONS") == "true":
        require(
            os.environ.get("GITHUB_EVENT_NAME") in ("push", "workflow_dispatch")
            and os.environ.get("GITHUB_REPOSITORY") == "xbghc/rikkahub-gpt-live"
            and os.environ.get("GITHUB_REF") == "refs/heads/gpt-live",
            "Colab is restricted to trusted branch builds",
        )
    require(
        args.project and args.folder,
        "Colab project and private Drive cache folder are required",
    )

    def interrupted(signum, frame):
        raise InterruptedError("Build interrupted; releasing the owned runtime")

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    controller = Controller(args)
    try:
        controller.cleanup() if args.cleanup else controller.build()
    except BaseException as error:  # noqa: BLE001 - redact credentials from third-party exceptions.
        # Only our ValueError/RuntimeError messages are safe; never print HTTP exception URLs.
        detail = (
            str(error)
            if type(error) in (ValueError, RuntimeError, InterruptedError)
            else type(error).__name__
        )
        print("Colab build stopped: " + detail, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
