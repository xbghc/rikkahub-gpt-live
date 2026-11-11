#!/usr/bin/env python3
"""Remote compiler: no publication credentials, original signing key, or device tests."""

import json
import os
import re
import selectors
import shutil
import signal
import subprocess
import sys
import tarfile
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

from artifacts import TEST_TASKS, collect, digest, require
from cache_store import compatibility, restore, snapshot

ROOT = Path("/content/rikkahub-benchmark")
REPO = ROOT / "repo"
TOOLS = ROOT / "toolchains"
CONFIG = json.loads((ROOT / "job.json").read_text())
SHA = CONFIG["sourceCommit"]
NODE_VERSION = "24.21.0"
PNPM_VERSION = "11.28.11"
os.umask(0o077)
TOOLS.mkdir(exist_ok=True)
OUT = ROOT / "logs"
OUT.mkdir(exist_ok=False)
TOKEN = ROOT / ".private/drive-access.json"
os.environ["COLAB_CACHE_FOLDER_ID"] = CONFIG["cacheFolderId"]
ENV = os.environ.copy()
ENV.update(
    {
        "CI": "true",
        # AGP parses jlink stdout as a version; retain container detection, silence its diagnostics.
        "JAVA_TOOL_OPTIONS": "-Xlog:os+container=off",
        "GRADLE_USER_HOME": str(ROOT / "gradle-home"),
        "ANDROID_HOME": str(TOOLS / "android-sdk"),
        "ANDROID_SDK_ROOT": str(TOOLS / "android-sdk"),
        "RIKKAHUB_DEBUG_KEYSTORE": str(ROOT / "temporary-test.keystore"),
    }
)
RESULT = {
    "jobId": CONFIG["jobId"],
    "sourceCommit": SHA,
    "timingsSeconds": {},
    "releaseArtifact": False,
}
CURRENT = {"step": "starting"}
STOP = threading.Event()
STARTED = time.monotonic()


def heartbeat(final=False):
    value = {
        "jobId": CONFIG["jobId"],
        "step": CURRENT["step"],
        "elapsedSeconds": round(time.monotonic() - STARTED, 2),
        "finished": final,
    }
    path = ROOT / "job-status.json"
    temporary = path.with_suffix(".pending.json")
    temporary.write_text(json.dumps(value) + "\n")
    temporary.replace(path)


def monitor():
    while not STOP.wait(5):
        heartbeat()


def run(label, args, cwd=ROOT, timeout=1800, input_text=None):
    started = time.monotonic()
    CURRENT["step"] = label
    print(f"BUILD_START {label}", flush=True)
    log_path = OUT / f"{label}.log"
    with log_path.open("wb") as log:
        process = subprocess.Popen(
            args,
            cwd=cwd,
            env=ENV,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.PIPE if input_text else subprocess.DEVNULL,
            start_new_session=True,
        )
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        if input_text:
            process.stdin.write(input_text.encode())
            process.stdin.close()
        try:
            while selector.get_map():
                if time.monotonic() - started > timeout:
                    raise TimeoutError(f"{label} exceeded {timeout} seconds")
                for key, _ in selector.select(timeout=1):
                    data = os.read(key.fileobj.fileno(), 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    log.write(data)
                    log.flush()
                    sys.stdout.write(data.decode(errors="replace"))
                    sys.stdout.flush()
            code = process.wait(timeout=10)
            if code:
                raise RuntimeError(f"{label} failed with exit code {code}")
        finally:
            selector.close()
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()
            RESULT["timingsSeconds"][label] = round(time.monotonic() - started, 3)
    print(f"BUILD_DONE {label} {RESULT['timingsSeconds'][label]}s", flush=True)


def fetch(url, path):
    if path.exists():
        return
    print(f"Downloading {path.name}", flush=True)
    temporary = path.with_suffix(path.suffix + ".partial")
    try:
        with (
            urllib.request.urlopen(url, timeout=120) as response,
            temporary.open("wb") as handle,
        ):
            shutil.copyfileobj(response, handle)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def setup():
    started = time.monotonic()
    if REPO.exists():
        raise RuntimeError("Build requires a fresh checkout")
    prefix = [] if os.getuid() == 0 else ["sudo", "-n"]
    run("apt-update", prefix + ["apt-get", "update"], timeout=600)
    run(
        "apt-install",
        prefix
        + [
            "apt-get",
            "install",
            "-y",
            "openjdk-21-jdk-headless",
            "git",
            "unzip",
            "zstd",
        ],
        timeout=900,
    )
    run("git-init", ["git", "init", str(REPO)])
    run(
        "git-remote",
        [
            "git",
            "remote",
            "add",
            "origin",
            "https://github.com/xbghc/rikkahub-gpt-live.git",
        ],
        REPO,
    )
    run("git-fetch", ["git", "fetch", "--depth=5", "origin", SHA], REPO, timeout=600)
    run("git-checkout", ["git", "checkout", "--detach", SHA], REPO)
    baseline = re.search(
        r"^rikkahub.upstream.ref=(.+)$",
        (REPO / "gradle.properties").read_text(),
        re.MULTILINE,
    ).group(1)
    require(
        re.fullmatch(r"[0-9a-f]{40}", baseline),
        "Full upstream baseline SHA required",
    )
    run("baseline-check", ["git", "cat-file", "-e", baseline + "^{commit}"], REPO)
    require(
        subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO, text=True
        ).strip()
        == SHA,
        "Source checkout mismatch",
    )
    run(
        "git-submodules",
        ["git", "submodule", "update", "--init", "--recursive"],
        REPO,
        timeout=600,
    )
    java = next(
        (p for p in Path("/usr/lib/jvm").glob("*21*") if (p / "bin/java").exists()),
        None,
    )
    if java is None:
        raise RuntimeError("JDK 21 was not found")
    ENV["JAVA_HOME"] = str(java)
    jlink = subprocess.run(
        [str(java / "bin/jlink"), "--version"],
        env=ENV,
        text=True,
        capture_output=True,
        timeout=30,
        check=True,
    )
    if not jlink.stdout.strip().startswith("21.") or "\n" in jlink.stdout.strip():
        raise RuntimeError(
            "JDK preflight failed: jlink stdout must be a clean Java 21 version"
        )
    RESULT["jlinkVersion"] = jlink.stdout.strip()
    node_dir = TOOLS / f"node-v{NODE_VERSION}-linux-x64"
    if not node_dir.exists():
        archive = TOOLS / f"{node_dir.name}.tar.xz"
        fetch(f"https://nodejs.org/dist/v{NODE_VERSION}/{archive.name}", archive)
        with tarfile.open(archive) as handle:
            handle.extractall(TOOLS, filter="data")
    ENV["PATH"] = os.pathsep.join(
        [
            str(java / "bin"),
            str(node_dir / "bin"),
            str(TOOLS / "node-tools/node_modules/.bin"),
            ENV["PATH"],
        ]
    )
    sdk = Path(ENV["ANDROID_HOME"])
    sdkmanager = sdk / "cmdline-tools/latest/bin/sdkmanager"
    if not sdkmanager.exists():
        archive = TOOLS / "commandlinetools-linux-15859902_latest.zip"
        fetch("https://dl.google.com/android/repository/" + archive.name, archive)
        unpack = TOOLS / "android-cmdline-unpack"
        unpack.mkdir(exist_ok=True)
        run("unzip-sdk", ["unzip", "-q", str(archive), "-d", str(unpack)])
        sdkmanager.parent.parent.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(unpack / "cmdline-tools"), str(sdk / "cmdline-tools/latest"))
    run(
        "sdk-licenses",
        [str(sdkmanager), "--sdk_root=" + str(sdk), "--licenses"],
        input_text="y\n" * 100,
        timeout=300,
    )
    run(
        "sdk-packages",
        [
            str(sdkmanager),
            "--sdk_root=" + str(sdk),
            "platform-tools",
            "platforms;android-37.0",
            "platforms;android-37.2",
            "build-tools;36.0.0",
            "build-tools;37.0.0",
            "ndk;28.2.13676358",
            "cmake;3.22.1",
        ],
        input_text="y\n" * 100,
        timeout=1200,
    )
    run(
        "pnpm-tool",
        [
            "npm",
            "install",
            "--prefix",
            str(TOOLS / "node-tools"),
            "--ignore-scripts",
            "--no-audit",
            "--no-fund",
            "--package-lock=false",
            f"pnpm@{PNPM_VERSION}",
        ],
        timeout=300,
    )
    run(
        "web-dependencies",
        ["pnpm", "install", "--frozen-lockfile"],
        REPO / "web-ui",
        timeout=600,
    )
    run(
        "test-key",
        [
            "keytool",
            "-genkeypair",
            "-noprompt",
            "-keystore",
            ENV["RIKKAHUB_DEBUG_KEYSTORE"],
            "-storepass",
            "android",
            "-keypass",
            "android",
            "-alias",
            "androiddebugkey",
            "-dname",
            "CN=Colab Build Only, O=RikkaHub, C=US",
            "-keyalg",
            "RSA",
            "-keysize",
            "2048",
            "-validity",
            "2",
        ],
        timeout=60,
    )
    (REPO / "local.properties").write_text(f"sdk.dir={sdk}\n")
    RESULT["timingsSeconds"]["setup-total"] = round(time.monotonic() - started, 3)
    RESULT["toolchain"] = {}
    for name, command in [
        ("java", ["java", "-version"]),
        ("node", ["node", "--version"]),
        ("pnpm", ["pnpm", "--version"]),
    ]:
        RESULT["toolchain"][name] = subprocess.check_output(
            command, env=ENV, text=True, stderr=subprocess.STDOUT
        ).strip()


def build():
    setup()
    cpu = len(os.sched_getaffinity(0))
    mem = int(
        next(
            line.split()[1]
            for line in Path("/proc/meminfo").read_text().splitlines()
            if line.startswith("MemTotal:")
        )
    )
    workers = min(24, cpu)
    heap = 16 if mem >= 40 * 1024**2 else 8
    forks = min(8, max(2, cpu // 2))
    RESULT["machine"] = {
        "cpuCount": cpu,
        "memoryKiB": mem,
        "workers": workers,
        "heapGiB": heap,
    }
    cache = CONFIG.get("cacheManifest")
    if (
        cache
        and cache.get("compatibilityKey")
        == compatibility(REPO, RESULT["jlinkVersion"])[1]
    ):
        CURRENT["step"] = "restore-drive-cache"
        RESULT["cacheRestore"] = restore(
            ROOT, REPO, SHA, RESULT["jlinkVersion"], TOKEN, cache
        )
    else:
        RESULT["cacheRestore"] = {"miss": True}
    # Never accept cached test results, including XML restored by an older cache format.
    for module, task in TEST_TASKS.items():
        shutil.rmtree(REPO / module / "build/test-results" / task, ignore_errors=True)
    init = ROOT / "fresh-tests.init.gradle"
    init.write_text(
        "import org.gradle.api.tasks.testing.Test\nallprojects {\n"
        "    tasks.withType(Test).configureEach {\n"
        f"        maxParallelForks = {forks}\n"
        "        maxHeapSize = '2g'\n"
        "        outputs.upToDateWhen { false }\n"
        "        outputs.cacheIf { false }\n    }\n}\n"
    )
    flags = [
        "-Prikkahub.firebase.enabled=false",
        "--daemon",
        "--parallel",
        f"--max-workers={workers}",
        f"-Dorg.gradle.jvmargs=-Xmx{heap}g -Dfile.encoding=UTF-8",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "--console=plain",
        "--build-cache",
        "--init-script",
        str(init),
    ]
    build_started = time.monotonic()
    run(
        "format-check",
        ["./gradlew", "spotlessCheck", *flags, "--no-configuration-cache"],
        REPO,
        timeout=1200,
    )
    test_started = time.time()
    run(
        "test-and-build",
        [
            "./gradlew",
            ":speech:testDebugUnitTest",
            ":app:testGithubDebugUnitTest",
            ":app:assembleGithubDebug",
            ":app:assembleGithubDebugAndroidTest",
            *flags,
            "--configuration-cache",
        ],
        REPO,
        timeout=1800,
    )
    log = (OUT / "test-and-build.log").read_text(errors="replace")
    for task in (":speech:testDebugUnitTest", ":app:testGithubDebugUnitTest"):
        require(
            re.search(r"^> Task " + re.escape(task) + r"\s*$", log, re.MULTILINE),
            "Unit tests must execute afresh",
        )
        require(
            not re.search(
                r"^> Task "
                + re.escape(task)
                + r" (FROM-CACHE|UP-TO-DATE|SKIPPED|NO-SOURCE)",
                log,
                re.MULTILINE,
            ),
            "Unit tests cannot be reused or skipped",
        )
    manifest = collect(
        REPO, ROOT / "prebuilt", SHA, CONFIG["jobId"], test_started, "colab"
    )
    RESULT["unitTests"] = manifest["unitTests"]
    RESULT["buildAndVerificationSeconds"] = round(time.monotonic() - build_started, 3)
    bundle = ROOT / "prebuilt.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_STORED) as archive:
        for path in sorted((ROOT / "prebuilt").rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(ROOT / "prebuilt"))
    RESULT["bundle"] = {"size": bundle.stat().st_size, "sha256": digest(bundle)}
    # A cache is an optimization, not a release prerequisite. No failure can skip build/test gates.
    run("stop-gradle", ["./gradlew", "--stop"], REPO, timeout=60)
    CURRENT["step"] = "save-drive-cache"
    try:
        RESULT["cacheManifest"] = snapshot(
            ROOT, REPO, SHA, RESULT["jlinkVersion"], TOKEN
        )
    except Exception as error:  # noqa: BLE001 - an optional cache save never changes test results.
        RESULT["cacheSaveError"] = type(error).__name__
        print("Cache save failed; verified APKs remain available", flush=True)
    RESULT["success"] = True


if __name__ == "__main__":
    thread = threading.Thread(target=monitor, daemon=True)
    heartbeat()
    thread.start()
    try:
        build()
    except BaseException as error:
        RESULT["success"] = False
        RESULT["error"] = type(error).__name__
        raise
    finally:
        TOKEN.unlink(missing_ok=True)
        STOP.set()
        thread.join(timeout=2)
        RESULT["totalSeconds"] = round(time.monotonic() - STARTED, 3)
        path = ROOT / "result.json"
        temporary = path.with_suffix(".pending.json")
        temporary.write_text(json.dumps(RESULT, indent=2) + "\n")
        temporary.replace(path)
        CURRENT["step"] = "finished"
        heartbeat(final=True)
