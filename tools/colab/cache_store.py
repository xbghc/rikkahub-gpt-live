"""Private Drive cache snapshots with authenticated checksums and restricted extraction."""

import hashlib
import json
import os
import re
import shutil
import subprocess
import tarfile
import time
from pathlib import Path, PurePosixPath
from urllib.parse import urlsplit

import requests

API = "https://www.googleapis.com/drive/v3/files"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
SCOPE = "https://www.googleapis.com/auth/drive.file"
FIELDS = "id,name,size,md5Checksum,sha256Checksum,webViewLink,appProperties,shared,trashed,parents"
CHUNK = 16 * 1024**2


def digest(path, algorithm="sha256"):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, algorithm).hexdigest()


def excluded(parts):
    return any(
        part
        in {
            ".git",
            ".aws",
            ".config",
            "configuration-cache",
            "daemon",
            "workers",
            "sessions.json",
            "adc.json",
            "drive-access.json",
            "test-results",
            "reports",
            "prebuilt",
            "colab",
        }
        or part.endswith((".lock", ".keystore", ".jks", ".apk", ".hprof"))
        or part.startswith(("signing_config", "validate_signing"))
        for part in parts
    )


def allowed(name, roots):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or excluded(path.parts):
        return False
    return any(
        path == PurePosixPath(root) or PurePosixPath(root) in path.parents
        for root in roots
    )


def extract_stream(contents, target, roots):
    count = 0
    total = 0
    for member in contents:
        parts = PurePosixPath(member.name).parts
        if any(p in {"test-results", "reports"} for p in parts):
            continue
        if not allowed(member.name, roots) or not (member.isfile() or member.isdir()):
            raise ValueError("Unexpected member in the build cache archive")
        total += member.size
        if total > 20 * 1024**3 or count > 500000:
            raise ValueError("Cache exceeds extraction limits")
        contents.extract(member, target, filter="data")
        count += member.isfile()
    return count


class Drive:
    def __init__(self, access):
        if access.get("scope") != SCOPE:
            raise ValueError("A Drive-only short-lived token is required")
        self.http = requests.Session()
        self.http.headers.update(
            {
                "Authorization": "Bearer " + access["access_token"],
                "x-goog-user-project": access["quota_project_id"],
            }
        )

    def request(self, method, url, allowed_status=(200, 201, 204, 308), **kwargs):
        parsed = urlsplit(url)
        if parsed.scheme != "https" or parsed.hostname != "www.googleapis.com":
            raise ValueError("Unexpected Drive endpoint")
        attempts = 4 if method == "GET" else 1
        for attempt in range(attempts):
            try:
                r = self.http.request(method, url, timeout=(15, 120), **kwargs)
            except requests.RequestException:
                if attempt + 1 == attempts:
                    raise RuntimeError("Drive network request failed") from None
                time.sleep(2**attempt)
                continue
            if r.status_code in (429, 500, 502, 503, 504) and attempt + 1 < attempts:
                r.close()
                time.sleep(2**attempt)
                continue
            if r.status_code not in allowed_status:
                raise RuntimeError("Drive API HTTP " + str(r.status_code))
            return r

    def generate_id(self):
        return self.request(
            "GET",
            API + "/generateIds",
            params={"count": 1, "space": "drive", "type": "files"},
        ).json()["ids"][0]

    def folder(self):
        folder = os.environ.get("COLAB_CACHE_FOLDER_ID")
        if folder:
            metadata = self.request(
                "GET",
                API + "/" + folder,
                params={"fields": "id,mimeType,shared,trashed"},
            ).json()
            if (
                metadata.get("shared")
                or metadata.get("trashed")
                or metadata.get("mimeType") != "application/vnd.google-apps.folder"
            ):
                raise ValueError("Cache folder must be private and active")
            return folder
        raise ValueError(
            "COLAB_CACHE_FOLDER_ID is required; no implicit Drive folders are created"
        )

    def upload(
        self, path, folder, manifest, name_override=None, mime_type="application/zstd"
    ):
        sha = manifest["sha256"]
        size = manifest["size"]
        key = manifest["compatibilityKey"]
        name = (
            name_override
            or f"rikkahub-cache-v2-{manifest['sourceCommit'][:8]}-{sha[:16]}.tar.zst"
        )
        q = f"'{folder}' in parents and name='{name}' and trashed=false"
        files = (
            self.request(
                "GET", API, params={"q": q, "fields": f"files({FIELDS})", "pageSize": 2}
            )
            .json()
            .get("files", [])
        )
        if len(files) > 1:
            raise RuntimeError("Duplicate immutable cache files")
        if files:
            metadata = files[0]
            if (
                metadata.get("shared")
                or int(metadata["size"]) != size
                or metadata.get("sha256Checksum") != sha
            ):
                raise ValueError(
                    "Existing immutable cache differs from its expected digest"
                )
            return metadata
        body = {
            "id": self.generate_id(),
            "name": name,
            "parents": [folder],
            "mimeType": mime_type,
            "appProperties": {
                "rikkahubCache": "v2",
                "cacheKey": key,
                "sha256": sha,
                "sourceCommit": manifest["sourceCommit"],
            },
        }
        response = self.request(
            "POST",
            UPLOAD,
            params={"uploadType": "resumable", "fields": FIELDS},
            json=body,
            headers={
                "X-Upload-Content-Type": mime_type,
                "X-Upload-Content-Length": str(size),
            },
        )
        location = response.headers["Location"]
        offset = 0
        last = -1
        failures = 0
        with Path(path).open("rb") as f:
            while offset < size:
                f.seek(offset)
                data = f.read(CHUNK)
                try:
                    response = self.request(
                        "PUT",
                        location,
                        data=data,
                        headers={
                            "Content-Length": str(len(data)),
                            "Content-Range": f"bytes {offset}-{offset + len(data) - 1}/{size}",
                        },
                    )
                    failures = 0
                except RuntimeError:
                    failures += 1
                    if failures > 4:
                        raise
                    time.sleep(2**failures)
                    response = self.request(
                        "PUT",
                        location,
                        data=b"",
                        headers={
                            "Content-Length": "0",
                            "Content-Range": f"bytes */{size}",
                        },
                    )
                if response.status_code in (200, 201):
                    return response.json()
                reported = response.headers.get("Range", "")
                following = int(reported.rsplit("-", 1)[1]) + 1 if reported else 0
                if not 0 <= following <= size:
                    raise RuntimeError("Invalid resumable upload offset")
                if following == offset and not failures:
                    raise RuntimeError("Resumable upload did not advance")
                offset = following
                progress = int(10 * offset / size)
                if progress > last:
                    print(f"CACHE_UPLOAD {progress * 10}%", flush=True)
                    last = progress
        return self.request(
            "GET", API + "/" + body["id"], params={"fields": FIELDS}
        ).json()

    def download(self, file_id, path):
        response = self.request(
            "GET", API + "/" + file_id, params={"alt": "media"}, stream=True
        )
        with response, Path(path).open("wb") as out:
            for block in response.iter_content(2 * 1024**2):
                if block:
                    out.write(block)


def compatibility(repo, jdk):
    files = [
        "gradle/wrapper/gradle-wrapper.properties",
        "gradle/libs.versions.toml",
        "web-ui/pnpm-lock.yaml",
    ]
    values = {name: digest(repo / name) for name in files}
    values.update(
        schema=2, architecture="x86_64", java=jdk, node="24.21.0", pnpm="11.28.11"
    )
    return values, hashlib.sha256(
        json.dumps(values, sort_keys=True).encode()
    ).hexdigest()


def snapshot(root, repo, source_commit, jdk, token_path):
    started = time.monotonic()
    roots = ["gradle-home/caches", "gradle-home/wrapper"]
    candidates = [
        repo / ".gradle",
        repo / ".kotlin",
        repo / "build",
        repo / "web-ui/build",
        repo / "web/src/main/resources/static",
    ]
    candidates += [
        p for p in repo.glob("*/build") if (p.parent / "build.gradle.kts").is_file()
    ]
    roots += [p.relative_to(root).as_posix() for p in candidates if p.is_dir()]
    roots = sorted(set(roots))
    values, key = compatibility(repo, jdk)
    archive = root / "build-cache.tar.zst"
    compressor = subprocess.Popen(
        ["zstd", "-T8", "-3", "-q", "-o", str(archive)], stdin=subprocess.PIPE
    )
    try:
        with tarfile.open(
            fileobj=compressor.stdin, mode="w|", format=tarfile.PAX_FORMAT
        ) as tar:
            for item in roots:

                def keep(member):
                    if not allowed(member.name, roots):
                        return None
                    if not (member.isfile() or member.isdir()):
                        return None
                    return member

                tar.add(root / item, arcname=item, filter=keep)
        compressor.stdin.close()
        if compressor.wait() != 0:
            raise RuntimeError("Cache compression failed")
    finally:
        if compressor.poll() is None:
            compressor.kill()
            compressor.wait()
    manifest = {
        "schema": 2,
        "sourceCommit": source_commit,
        "compatibility": values,
        "compatibilityKey": key,
        "roots": roots,
        "size": archive.stat().st_size,
        "sha256": digest(archive),
        "md5": digest(archive, "md5"),
        "packSeconds": round(time.monotonic() - started, 3),
        "releaseArtifact": False,
    }
    drive = Drive(json.loads(token_path.read_text()))
    folder = drive.folder()
    started = time.monotonic()
    metadata = drive.upload(archive, folder, manifest)
    if (
        int(metadata["size"]) != manifest["size"]
        or metadata.get("md5Checksum") != manifest["md5"]
    ):
        raise RuntimeError("Uploaded cache failed checksum validation")
    if (
        metadata.get("sha256Checksum")
        and metadata["sha256Checksum"] != manifest["sha256"]
    ):
        raise RuntimeError("Drive SHA-256 mismatch")
    manifest.update(
        uploadSeconds=round(time.monotonic() - started, 3),
        file=metadata,
        folderId=folder,
        folderUrl="https://drive.google.com/drive/folders/" + folder,
    )
    manifest_path = root / "cache-manifest-upload.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    upload_meta = {
        "sha256": digest(manifest_path),
        "size": manifest_path.stat().st_size,
        "compatibilityKey": key,
        "sourceCommit": source_commit,
    }
    manifest_file = drive.upload(
        manifest_path,
        folder,
        upload_meta,
        name_override=metadata["name"].removesuffix(".tar.zst") + ".json",
        mime_type="application/json",
    )
    if manifest_file.get("md5Checksum") != digest(manifest_path, "md5"):
        raise RuntimeError("Manifest upload checksum mismatch")
    manifest["manifestFile"] = manifest_file
    return manifest


def restore(root, repo, source_commit, jdk, token_path, manifest):
    _values, key = compatibility(repo, jdk)
    if manifest.get("schema") != 2 or manifest.get("compatibilityKey") != key:
        raise ValueError("Cache does not match the expected toolchain")
    roots = manifest["roots"]
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("sourceCommit", "")):
        raise ValueError("Invalid cache source SHA")
    permitted = {
        "gradle-home/caches",
        "gradle-home/wrapper",
        "repo/.gradle",
        "repo/.kotlin",
        "repo/build",
        "repo/web-ui/build",
        "repo/web/src/main/resources/static",
    }
    permitted.update(
        p.parent.relative_to(root).as_posix() + "/build"
        for p in repo.glob("*/build.gradle.kts")
    )
    if not set(roots).issubset(permitted):
        raise ValueError("Cache root allowlist mismatch")
    drive = Drive(json.loads(token_path.read_text()))
    archive = root / "restored-cache.tar.zst"
    started = time.monotonic()
    metadata = drive.request(
        "GET", API + "/" + manifest["file"]["id"], params={"fields": FIELDS}
    ).json()
    if (
        metadata.get("shared")
        or metadata.get("trashed")
        or drive.folder() not in metadata.get("parents", [])
    ):
        raise ValueError("Cache archive must be in the private cache folder")
    if (
        int(metadata["size"]) != manifest["size"]
        or metadata.get("sha256Checksum") != manifest["sha256"]
    ):
        raise ValueError("Cache metadata checksum mismatch")
    drive.download(manifest["file"]["id"], archive)
    if (
        archive.stat().st_size != manifest["size"]
        or digest(archive) != manifest["sha256"]
    ):
        raise ValueError("Downloaded cache SHA-256 mismatch")
    download_seconds = time.monotonic() - started
    started = time.monotonic()
    target = root / "cache-staging"
    target.mkdir(exist_ok=False)
    process = subprocess.Popen(["zstd", "-dc", str(archive)], stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode="r|") as contents:
            count = extract_stream(contents, target, roots)
        if process.wait() != 0:
            raise RuntimeError("Cache decompression failed")
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
    for item in roots:
        source = target / item
        destination = root / item
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            # The only pre-existing output allowed here is the public web resource directory.
            if item != "repo/web/src/main/resources/static":
                raise RuntimeError("Refusing to overwrite existing build state")
            shutil.copytree(source, destination, dirs_exist_ok=True)
        else:
            source.replace(destination)
    return {
        "sha256Verified": True,
        "restoredFiles": count,
        "downloadSeconds": round(download_seconds, 3),
        "extractSeconds": round(time.monotonic() - started, 3),
        "fileId": manifest["file"]["id"],
    }
