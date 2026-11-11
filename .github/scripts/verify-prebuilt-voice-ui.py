#!/usr/bin/env python3
"""Install prebuilt APKs and require a complete passing instrumentation run."""

import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "me.rerere.rikkahub.debug"
TEST_CLASS = "me.rerere.rikkahub.ui.voice.VoiceEntryTest"


def parse_instrumentation(output, expected):
    # adb can exit 0 even when AndroidJUnitRunner crashes or a test fails.
    completed = {}
    fields = {}
    for line in output.replace("\r", "").splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(":", 1)[1])
            if code != 1:
                name = fields.get("test")
                if (
                    code != 0
                    or fields.get("class") != TEST_CLASS
                    or not name
                    or name in completed
                ):
                    raise ValueError(
                        "Instrumentation test failed, skipped, duplicated, or was incomplete"
                    )
                completed[name] = code
            fields = {}
    summary = re.findall(r"^OK \((\d+) tests?\)\s*$", output, re.MULTILINE)
    endings = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", output, re.MULTILINE)
    if (
        set(completed) != set(expected)
        or summary != [str(len(expected))]
        or endings != ["-1"]
        or "INSTRUMENTATION_FAILED" in output
        or "INSTRUMENTATION_ABORTED" in output
    ):
        raise ValueError("Incomplete or unsuccessful voice UI instrumentation run")
    return completed


def main():
    adb = ["adb"]
    subprocess.run(
        adb + ["shell", "am", "force-stop", "com.google.android.apps.nexuslauncher"],
        check=True,
    )
    for apk in (
        "app/build/outputs/apk/github/debug/app-github-x86_64-debug.apk",
        "app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk",
    ):
        subprocess.run(adb + ["install", "-r", "-t", apk], check=True, timeout=180)
    source = Path(
        "app/src/androidTest/java/me/rerere/rikkahub/ui/voice/VoiceEntryTest.kt"
    ).read_text()
    expected = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", source)
    if not expected or len(set(expected)) != len(expected):
        raise ValueError("Could not identify expected voice UI tests")
    result = subprocess.run(
        adb
        + [
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            TEST_CLASS,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
        ],
        check=False,
        capture_output=True,
        text=True,
        timeout=600,
    )
    output = result.stdout + result.stderr
    report = Path("app/build/outputs/androidTest-results/connected")
    report.mkdir(parents=True, exist_ok=True)
    (report / "instrumentation.txt").write_text(output)
    print(output, flush=True)
    screenshots = Path("build/voice-ui-screenshots")
    screenshots.mkdir(parents=True, exist_ok=True)
    missing = []
    for name in (
        "entry",
        "setup-missing-key",
        "setup-ready",
        "setup-busy",
        "setup-error",
        "google-setup-missing-account",
        "google-setup-ready",
    ):
        destination = screenshots / f"voice-ui-{name}.png"
        copy = subprocess.run(
            adb
            + [
                "pull",
                f"/sdcard/Android/data/{PACKAGE}/files/voice-ui-{name}.png",
                str(destination),
            ],
            check=False,
            capture_output=True,
            timeout=30,
        )
        if (
            copy.returncode
            or not destination.is_file()
            or not destination.stat().st_size
        ):
            missing.append(name)
    result.check_returncode()
    tests = parse_instrumentation(output, expected)
    if missing:
        raise ValueError("Missing voice UI screenshots: " + ", ".join(missing))
    root = ET.Element(
        "testsuite",
        name=TEST_CLASS,
        tests=str(len(tests)),
        failures="0",
        errors="0",
        skipped="0",
    )
    for name in tests:
        ET.SubElement(root, "testcase", classname=TEST_CLASS, name=name)
    ET.ElementTree(root).write(
        report / "TEST-VoiceEntryTest.xml", encoding="utf-8", xml_declaration=True
    )
    print(
        f"Verified {len(tests)} voice UI tests using prebuilt APKs; no Gradle compilation"
    )


if __name__ == "__main__":
    main()
