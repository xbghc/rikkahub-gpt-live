"""Collect and validate prebuilt APKs without running Gradle on the signing runner."""

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

PACKAGE = "me.rerere.rikkahub.debug"
PUBLISHED_CERT = "6c2d6dfdac13e1901cbec04feba5f10271747230ce21010f5d4e4608658b9c07"
APK_PATHS = {
    "app-github-arm64-v8a-debug.apk": "app/build/outputs/apk/github/debug",
    "app-github-x86_64-debug.apk": "app/build/outputs/apk/github/debug",
    "app-github-debug-androidTest.apk": "app/build/outputs/apk/androidTest/github/debug",
}
TEST_TASKS = {"speech": "testDebugUnitTest", "app": "testGithubDebugUnitTest"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def version(repo):
    source = (repo / "app/build.gradle.kts").read_text()
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", source, re.MULTILINE)
    names = re.findall(
        r'^\s*versionName\s*=\s*"([a-zA-Z0-9._+-]+)"\s*$', source, re.MULTILINE
    )
    require(len(codes) == len(names) == 1, "Unrecognized source version metadata")
    return {"versionCode": int(codes[0]), "versionName": names[0]}


def test_counts(folder):
    summary = {}
    for module in TEST_TASKS:
        reports = sorted((folder / "test-results" / module).glob("TEST-*.xml"))
        require(bool(reports), f"Missing {module} unit test reports")
        tests = 0
        names = set()
        for path in reports:
            root = ET.parse(path).getroot()
            require(root.tag == "testsuite", "Unexpected JUnit report format")
            cases = root.findall("testcase")
            require(
                int(root.get("tests", "-1")) == len(cases), "JUnit test count mismatch"
            )
            require(
                all(
                    int(root.get(k, "-1")) == 0
                    for k in ("failures", "errors", "skipped")
                ),
                "Unit tests failed or were skipped",
            )
            for case in cases:
                identity = (case.get("classname"), case.get("name"))
                require(
                    all(identity) and identity not in names,
                    "Duplicate or unnamed unit test",
                )
                names.add(identity)
                require(
                    not any(
                        case.find(k) is not None
                        for k in ("failure", "error", "skipped")
                    ),
                    "Unit test failure hidden by report counters",
                )
            tests += len(cases)
        require(tests > 0, f"No {module} unit tests ran")
        summary[module] = tests
    return summary


def collect(repo, output, source, job_id, build_started, backend):
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Full source SHA required")
    require(
        subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo, text=True
        ).strip()
        == source,
        "Checkout does not match requested source",
    )
    subprocess.run(
        ["git", "diff", "--exit-code", "HEAD"],
        cwd=repo,
        check=True,
        stdout=subprocess.DEVNULL,
    )
    output.mkdir(parents=True, exist_ok=False)
    for name, relative in APK_PATHS.items():
        shutil.copy2(repo / relative / name, output / name)
    for module, task in TEST_TASKS.items():
        target = output / "test-results" / module
        target.mkdir(parents=True)
        for path in (repo / module / "build/test-results" / task).glob("TEST-*.xml"):
            require(path.stat().st_mtime >= build_started - 2, "Stale unit test report")
            shutil.copy2(path, target / path.name)
    manifest = {
        "schema": 1,
        "sourceCommit": source,
        "jobId": job_id,
        "backend": backend,
        "applicationId": PACKAGE,
        **version(repo),
        "unitTests": test_counts(output),
        "formatChecked": True,
        "freshUnitTests": True,
        "releaseArtifact": False,
        "files": {},
    }
    # Only an explicit set of APKs and XML reports crosses the build/sign boundary.
    manifest["files"] = {
        path.relative_to(output).as_posix(): {
            "sha256": digest(path),
            "size": path.stat().st_size,
        }
        for path in sorted(output.rglob("*"))
        if path.is_file()
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def verify_bundle(folder, source, expected_version):
    manifest = json.loads((folder / "manifest.json").read_text())
    require(
        manifest.get("schema") == 1 and manifest.get("sourceCommit") == source,
        "Prebuilt APK source SHA mismatch",
    )
    require(
        manifest.get("applicationId") == PACKAGE
        and manifest.get("releaseArtifact") is False,
        "Unexpected build artifact",
    )
    require(
        all(manifest.get(k) == v for k, v in expected_version.items()),
        "Build version mismatch",
    )
    require(
        manifest.get("formatChecked") is True
        and manifest.get("freshUnitTests") is True,
        "Formatting and fresh unit tests are required",
    )
    files = manifest.get("files", {})
    actual = {
        p.relative_to(folder).as_posix() for p in folder.rglob("*") if p.is_file()
    }
    require(
        actual == set(files) | {"manifest.json"}, "Unlisted or missing artifact files"
    )
    require(set(APK_PATHS).issubset(files), "Missing prebuilt APK")
    for name, info in files.items():
        require(
            name in APK_PATHS
            or re.fullmatch(r"test-results/(app|speech)/TEST-[\w.$-]+\.xml", name),
            "Unexpected artifact path",
        )
        path = folder / name
        require(
            not path.is_symlink() and path.resolve().is_relative_to(folder.resolve()),
            "Artifact path escape",
        )
        require(
            path.stat().st_size == info["size"] and digest(path) == info["sha256"],
            "Artifact checksum mismatch",
        )
    require(test_counts(folder) == manifest["unitTests"], "Unit test totals mismatch")
    return manifest


def apk_payload(path):
    """Signing may change signatures, never code/resources or manifest bytes."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate APK ZIP entries")
        return {
            name: hashlib.sha256(archive.read(name)).hexdigest()
            for name in names
            if not re.fullmatch(
                r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", name, re.IGNORECASE
            )
        }


def check_apk(path, sdk, expected_version, test=False):
    badging = subprocess.check_output(
        [str(sdk / "aapt2"), "dump", "badging", str(path)], text=True
    )
    match = re.search(
        r"^package: name='([^']+)' versionCode='(\d*)' versionName='([^']*)'",
        badging,
        re.MULTILINE,
    )
    require(
        match is not None and match[1] == PACKAGE + (".test" if test else ""),
        "APK package mismatch",
    )
    if test:
        tree = subprocess.check_output(
            [
                str(sdk / "aapt2"),
                "dump",
                "xmltree",
                str(path),
                "--file",
                "AndroidManifest.xml",
            ],
            text=True,
        )
        require(
            re.search(
                r'android:targetPackage[^\n]*="' + re.escape(PACKAGE) + r'"', tree
            ),
            "Instrumentation target package mismatch",
        )
    else:
        require(
            match[2].isdigit()
            and int(match[2]) == expected_version["versionCode"]
            and match[3] == expected_version["versionName"],
            "APK embedded version mismatch",
        )
        abi = "arm64-v8a" if "arm64-v8a" in path.name else "x86_64"
        require(
            re.search(r"^native-code: '" + abi + r"'\s*$", badging, re.MULTILINE),
            "APK ABI mismatch",
        )


def resign(folder, repo, source):
    expected = version(repo)
    manifest = verify_bundle(folder, source, expected)
    sdk = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    key = Path(os.environ["RIKKAHUB_DEBUG_KEYSTORE"])
    certificate = os.environ["DEBUG_SIGNING_CERT_SHA256"]
    cert = subprocess.check_output(
        [
            "keytool",
            "-exportcert",
            "-keystore",
            str(key),
            "-storepass",
            "android",
            "-alias",
            "androiddebugkey",
        ],
        stderr=subprocess.DEVNULL,
    )
    require(
        hashlib.sha256(cert).hexdigest() == certificate,
        "Signing key certificate mismatch",
    )
    for name, relative in APK_PATHS.items():
        original = folder / name
        check_apk(original, sdk, expected, test="androidTest" in name)
        subprocess.run([str(sdk / "apksigner"), "verify", str(original)], check=True)
        target = repo / relative / name
        target.parent.mkdir(parents=True, exist_ok=True)
        aligned = target.with_suffix(".aligned.apk")
        subprocess.run(
            [str(sdk / "zipalign"), "-f", "-P", "16", "4", str(original), str(aligned)],
            check=True,
        )
        subprocess.run(
            [
                str(sdk / "apksigner"),
                "sign",
                "--ks",
                str(key),
                "--ks-key-alias",
                "androiddebugkey",
                "--ks-pass",
                "pass:android",
                "--key-pass",
                "pass:android",
                "--v4-signing-enabled",
                "false",
                "--out",
                str(target),
                str(aligned),
            ],
            check=True,
        )
        aligned.unlink()
        verification = subprocess.check_output(
            [
                str(sdk / "apksigner"),
                "verify",
                "--verbose",
                "--print-certs",
                str(target),
            ],
            text=True,
        )
        found = set(
            re.findall(r"certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", verification)
        )
        require(found == {certificate}, "Re-signed APK certificate mismatch")
        require(
            apk_payload(original) == apk_payload(target),
            "APK content changed during signing",
        )
        subprocess.run(
            [str(sdk / "zipalign"), "-c", "-P", "16", "4", str(target)], check=True
        )
    metadata = repo / "app/build/outputs/apk/github/debug/output-metadata.json"
    metadata.write_text(
        json.dumps({"applicationId": PACKAGE, "elements": [expected]}) + "\n"
    )
    print(
        f"Verified {source}: {sum(manifest['unitTests'].values())} unit tests; signed APK payloads unchanged"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["collect", "verify", "sign"])
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--output", type=Path, default=Path("build/prebuilt"))
    parser.add_argument("--source", default=os.environ.get("GITHUB_SHA"))
    parser.add_argument("--job-id", default=os.environ.get("GITHUB_RUN_ID"))
    parser.add_argument("--build-started", type=float)
    args = parser.parse_args()
    if args.command == "collect":
        collect(
            args.repo,
            args.output,
            args.source,
            args.job_id,
            args.build_started,
            "actions",
        )
    elif args.command == "sign":
        resign(args.output, args.repo, args.source)
    else:
        verify_bundle(args.output, args.source, version(args.repo))
