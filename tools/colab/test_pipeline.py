"""Exercise trust boundaries and failure cases without cloud resources or emulators."""

import importlib.util
import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import artifacts
import cache_store

from build import Controller

SPEC = importlib.util.spec_from_file_location(
    "voice_validation",
    Path(__file__).resolve().parents[2] / ".github/scripts/verify-prebuilt-voice-ui.py",
)
VOICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VOICE)
SOURCE = "a" * 40
VERSION = {"versionCode": 204, "versionName": "3.1.2-gpt-live.1"}


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.folder = Path(self.temporary.name)
        for name in artifacts.APK_PATHS:
            (self.folder / name).write_bytes(b"sample APK")
        for module in artifacts.TEST_TASKS:
            folder = self.folder / "test-results" / module
            folder.mkdir(parents=True)
            (folder / "TEST-Example.xml").write_text(
                '<testsuite tests="1" failures="0" errors="0" skipped="0">'
                '<testcase classname="Example" name="works"/></testsuite>'
            )
        self.manifest = {
            "schema": 1,
            "sourceCommit": SOURCE,
            "applicationId": artifacts.PACKAGE,
            **VERSION,
            "formatChecked": True,
            "freshUnitTests": True,
            "releaseArtifact": False,
            "unitTests": {"speech": 1, "app": 1},
        }
        self.save()

    def save(self):
        self.manifest["files"] = {
            p.relative_to(self.folder).as_posix(): {
                "size": p.stat().st_size,
                "sha256": artifacts.digest(p),
            }
            for p in self.folder.rglob("*")
            if p.is_file() and p.name != "manifest.json"
        }
        (self.folder / "manifest.json").write_text(json.dumps(self.manifest))

    def verify(self):
        return artifacts.verify_bundle(self.folder, SOURCE, VERSION)

    def test_valid_bundle(self):
        self.assertEqual(self.verify()["unitTests"], {"app": 1, "speech": 1})

    def test_rejects_different_commit_or_version(self):
        for field, value in (
            ("sourceCommit", "b" * 40),
            ("versionCode", 203),
            ("freshUnitTests", False),
        ):
            with self.subTest(field=field):
                original = self.manifest[field]
                self.manifest[field] = value
                self.save()
                with self.assertRaises(ValueError):
                    self.verify()
                self.manifest[field] = original

    def test_rejects_modified_or_missing_apk(self):
        apk = self.folder / next(iter(artifacts.APK_PATHS))
        apk.write_bytes(b"changed APK")
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.verify()
        apk.unlink()
        with self.assertRaises(ValueError):
            self.verify()

    def test_rejects_extra_executable_and_escape_paths(self):
        (self.folder / "run.sh").write_text("echo unsafe")
        self.save()
        with self.assertRaisesRegex(ValueError, "Unexpected artifact path"):
            self.verify()

    def test_rejects_parent_symlink(self):
        reports = self.folder / "test-results"
        external = self.folder.parent / (self.folder.name + "-outside")
        reports.rename(external)
        self.addCleanup(lambda: __import__("shutil").rmtree(external))
        reports.symlink_to(external, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.verify()

    def test_rejects_failure_hidden_by_zero_counters(self):
        path = self.folder / "test-results/app/TEST-Example.xml"
        path.write_text(
            '<testsuite tests="1" failures="0" errors="0" skipped="0">'
            '<testcase classname="Example" name="works"><failure>bad</failure></testcase></testsuite>'
        )
        self.save()
        with self.assertRaisesRegex(ValueError, "hidden"):
            self.verify()

    def test_rejects_missing_and_skipped_tests(self):
        path = self.folder / "test-results/app/TEST-Example.xml"
        for xml in (
            '<testsuite tests="0" failures="0" errors="0" skipped="0"/>',
            (
                '<testsuite tests="1" failures="0" errors="0" skipped="1">'
                '<testcase classname="X" name="y"><skipped/></testcase></testsuite>'
            ),
        ):
            path.write_text(xml)
            self.save()
            with self.assertRaises(ValueError):
                self.verify()

    def test_signing_payload_check_detects_changed_code(self):
        paths = [
            self.folder / name
            for name in ("before.zip", "resigned.zip", "tampered.zip")
        ]
        for index, path in enumerate(paths):
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(
                    "classes.dex", b"same code" if index < 2 else b"different code"
                )
                archive.writestr("META-INF/CERT.RSA", bytes([index]))
                archive.writestr(
                    "AndroidManifest.xml", b"unchanged version and package"
                )
        self.assertEqual(
            artifacts.apk_payload(paths[0]), artifacts.apk_payload(paths[1])
        )
        self.assertNotEqual(
            artifacts.apk_payload(paths[0]), artifacts.apk_payload(paths[2])
        )

    def test_check_apk_handles_empty_instrumentation_version_code(self):
        test_badging = (
            f"package: name='{artifacts.PACKAGE}.test' versionCode='' versionName=''\n"
        )
        test_xmltree = (
            'A: http://schemas.android.com/apk/res/android:targetPackage(0x01010021)="'
            + artifacts.PACKAGE
            + '"\n'
        )
        with patch(
            "artifacts.subprocess.check_output",
            side_effect=[test_badging, test_xmltree],
        ):
            artifacts.check_apk(
                Path("app-github-debug-androidTest.apk"),
                Path("/sdk"),
                VERSION,
                test=True,
            )
        with patch(
            "artifacts.subprocess.check_output",
            return_value=f"package: name='{artifacts.PACKAGE}' versionCode='' versionName='{VERSION['versionName']}'\n",
        ):
            with self.assertRaisesRegex(ValueError, "version"):
                artifacts.check_apk(
                    Path("app-github-arm64-v8a-debug.apk"),
                    Path("/sdk"),
                    VERSION,
                    test=False,
                )


class CacheTests(unittest.TestCase):
    def extract(self, member, data=b"cache"):
        contents = io.BytesIO()
        with tarfile.open(fileobj=contents, mode="w") as archive:
            if member.isfile():
                member.size = len(data)
                archive.addfile(member, io.BytesIO(data))
            else:
                archive.addfile(member)
        contents.seek(0)
        with (
            tempfile.TemporaryDirectory() as folder,
            tarfile.open(fileobj=contents, mode="r|") as archive,
        ):
            return cache_store.extract_stream(archive, Path(folder), ["repo/app/build"])

    def test_regular_cache_extracts(self):
        self.assertEqual(
            self.extract(tarfile.TarInfo("repo/app/build/classes/Foo.class")), 1
        )

    def test_paths_and_secrets_are_rejected(self):
        for name in (
            "../outside",
            "/tmp/absolute",
            "repo/app/build/../../source.kt",
            "repo/app/build/test.keystore",
            "repo/app/build/.config/adc.json",
            "repo/app/build.gradle.kts",
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.extract(tarfile.TarInfo(name))

    def test_links_cannot_escape_cache(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
            member = tarfile.TarInfo("repo/app/build/link")
            member.type = kind
            member.linkname = "/tmp/outside"
            with self.assertRaises(ValueError):
                self.extract(member)

    def test_old_test_results_are_discarded(self):
        self.assertEqual(
            self.extract(tarfile.TarInfo("repo/app/build/test-results/TEST-stale.xml")),
            0,
        )


def instrumentation(names, codes=None):
    result = []
    codes = codes or [0] * len(names)
    for name, code in zip(names, codes):
        for status in (1, code):
            result += [
                f"INSTRUMENTATION_STATUS: class={VOICE.TEST_CLASS}",
                f"INSTRUMENTATION_STATUS: test={name}",
                f"INSTRUMENTATION_STATUS_CODE: {status}",
            ]
    result += [f"OK ({len(names)} tests)", "INSTRUMENTATION_CODE: -1"]
    return "\n".join(result) + "\n"


class InstrumentationTests(unittest.TestCase):
    def test_complete_success(self):
        self.assertEqual(
            set(
                VOICE.parse_instrumentation(
                    instrumentation(["one", "two"]), ["one", "two"]
                )
            ),
            {"one", "two"},
        )

    def test_exit_zero_or_ok_text_cannot_hide_failure(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                VOICE.parse_instrumentation(instrumentation(["one"], [code]), ["one"])

    def test_crash_missing_or_duplicate_test_fails(self):
        for output in (
            instrumentation(["one"]).replace("INSTRUMENTATION_CODE: -1", ""),
            instrumentation(["one", "one"]),
            instrumentation(["one"]),
        ):
            with self.assertRaises(ValueError):
                VOICE.parse_instrumentation(output, ["one", "two"])


class RuntimeCleanupTests(unittest.TestCase):
    def controller(self, folder):
        controller = Controller.__new__(Controller)
        controller.args = SimpleNamespace(
            state=Path(folder) / "state.json", source=SOURCE, folder="private_folder"
        )
        controller.state = {}
        return controller

    def test_failed_allocation_still_releases_owned_resource(self):
        with tempfile.TemporaryDirectory() as folder:
            controller = self.controller(folder)
            controller.cache_manifest = Mock(return_value=None)
            controller.api = Mock(
                side_effect=[RuntimeError("Lost create acknowledgement"), {}]
            )
            with self.assertRaisesRegex(RuntimeError, "Lost create"):
                controller.build()
            self.assertTrue(controller.state["released"])
            self.assertEqual(controller.api.call_args.args[0], "DELETE")
            self.assertEqual(
                controller.api.call_args.args[1],
                "v1beta/" + controller.state["runtimeName"],
            )

    def test_cleanup_is_idempotent_and_never_deletes_unrelated_instances(self):
        with tempfile.TemporaryDirectory() as folder:
            controller = self.controller(folder)
            controller.api = Mock()
            controller.cleanup()
            controller.api.assert_not_called()
            controller.state = {"runtimeName": "runtimes/someone-elses-runtime"}
            with self.assertRaises(ValueError):
                controller.cleanup()
            controller.api.assert_not_called()
            controller.state = {
                "runtimeName": "runtimes/rikkahub-ci-" + "f" * 24,
                "released": True,
            }
            controller.cleanup()
            controller.api.assert_not_called()

    def test_pending_creation_is_reconciled_before_delete(self):
        with tempfile.TemporaryDirectory() as folder:
            controller = self.controller(folder)
            controller.state = {
                "runtimeName": "runtimes/rikkahub-ci-" + "a" * 24,
                "operationName": "operations/x",
            }
            controller.api = Mock(side_effect=[{"done": False}, {"done": True}, {}])
            with patch("build.time.sleep"):
                controller.cleanup()
            self.assertEqual(
                [call.args[0] for call in controller.api.call_args_list],
                ["GET", "GET", "DELETE"],
            )


if __name__ == "__main__":
    unittest.main()
