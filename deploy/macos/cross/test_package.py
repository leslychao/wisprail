"""Exercise cross-built Mach-O signatures and real archive formats on Linux."""

import importlib.util
import io
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import urllib.error


def load_script(name, path):
    specification = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(specification)
    specification.loader.exec_module(module)
    return module


DIRECTORY = Path(__file__).parent
SIGNATURES = load_script("signatures", DIRECTORY / "verify-signatures.py")
PACKAGING = load_script("packaging", DIRECTORY / "package.py")
ENGINE = load_script("engine", DIRECTORY.parents[1] / "fetch-engine.py")


class PackageTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workspace = tempfile.TemporaryDirectory(prefix="wisprail-cross-test-")
        cls.root = Path(cls.workspace.name)
        source = cls.root / "main.c"
        source.write_text("int main(void) { return 0; }\n")
        for architecture in ("arm64", "x86_64"):
            binary = cls.root / architecture
            subprocess.run([f"{architecture}-apple-darwin23.5-clang", str(source),
                            "-o", str(binary)], check=True)
            subprocess.run(["rcodesign", "sign", str(binary)], check=True)

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=self.root)
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def test_signatures_for_both_architectures_and_universal(self):
        for architecture in ("arm64", "x86_64"):
            self.assertEqual(len(SIGNATURES.verify_binary(self.root / architecture)), 1)
        universal = self.directory / "universal"
        subprocess.run(["llvm-lipo", "-create", str(self.root / "arm64"),
                        str(self.root / "x86_64"), "-output", str(universal)], check=True)
        self.assertEqual(len(SIGNATURES.verify_binary(universal)), 2)

    def test_changed_code_page_is_rejected(self):
        binary = self.directory / "changed"
        data = bytearray((self.root / "arm64").read_bytes())
        data[1024] ^= 1
        binary.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "Code page hash mismatch"):
            SIGNATURES.verify_binary(binary)

    def test_truncated_signature_is_rejected(self):
        binary = self.directory / "truncated"
        binary.write_bytes((self.root / "arm64").read_bytes()[:1024])
        with self.assertRaises(ValueError):
            SIGNATURES.verify_binary(binary)

    def make_bundle(self):
        app = self.directory / "distribution/Wisprail/Wisprail.app"
        contents = app / "Contents"
        (contents / "MacOS").mkdir(parents=True)
        (contents / "Resources").mkdir()
        shutil.copy2(self.root / "arm64", contents / "MacOS/Test")
        (contents / "Resources/note.txt").write_text("Original resource\n")
        (contents / "Resources/link").symlink_to("note.txt")
        (contents / "Info.plist").write_bytes(plistlib.dumps(dict(
            CFBundleExecutable="Test", CFBundleIdentifier="app.wisprail.test",
            CFBundlePackageType="APPL", CFBundleVersion="1.0.0")))
        subprocess.run(["rcodesign", "sign", str(app)], check=True)
        SIGNATURES.verify_bundle(app)
        return app

    def test_changed_bundle_metadata_is_rejected(self):
        app = self.make_bundle()
        info = app / "Contents/Info.plist"
        info.write_bytes(info.read_bytes().replace(b"1.0.0", b"2.0.0"))
        with self.assertRaisesRegex(ValueError, "Special slot hash mismatch"):
            SIGNATURES.verify_bundle(app)

    def test_changed_resource_is_rejected(self):
        app = self.make_bundle()
        (app / "Contents/Resources/note.txt").write_text("Changed\n")
        with self.assertRaisesRegex(ValueError, "Resource seal changed"):
            SIGNATURES.verify_bundle(app)

    def test_changed_symlink_is_rejected(self):
        app = self.make_bundle()
        link = app / "Contents/Resources/link"
        link.unlink()
        link.symlink_to("missing.txt")
        with self.assertRaisesRegex(ValueError, "Sealed symlink changed"):
            SIGNATURES.verify_bundle(app)

    def test_pkg_and_zip_preserve_payload_scripts_and_permissions(self):
        app = self.make_bundle()
        installer = PACKAGING.build_pkg(DIRECTORY.parents[2], app, self.directory, "0.1.0-SNAPSHOT")
        PACKAGING.verify_pkg(installer, app, self.directory)
        archive = self.directory / "wisprail.zip"
        PACKAGING.zip_distribution(app.parent, archive)
        unpacked = self.directory / "zip-roundtrip"
        unpacked.mkdir()
        subprocess.run(["bsdtar", "-xf", str(archive), "-C", str(unpacked)], check=True)
        self.assertEqual(PACKAGING.inventory(app.parent), PACKAGING.inventory(unpacked / "Wisprail"))


class DownloadTest(unittest.TestCase):
    def test_transient_timeout_retries_the_same_download(self):
        with patch.object(ENGINE.urllib.request, "urlopen",
                          side_effect=[TimeoutError(), io.BytesIO(b"archive")]) as request:
            with patch.object(ENGINE.time, "sleep"):
                self.assertEqual(ENGINE.download("https://example.test/pinned"), b"archive")
            self.assertEqual(request.call_count, 2)
            self.assertEqual(request.call_args_list[0], request.call_args_list[1])

    def test_permanent_error_is_not_retried(self):
        error = urllib.error.HTTPError("https://example.test/pinned", 404, "Missing", {}, None)
        with patch.object(ENGINE.urllib.request, "urlopen", side_effect=error) as request:
            with self.assertRaises(urllib.error.HTTPError):
                ENGINE.download("https://example.test/pinned")
            self.assertEqual(request.call_count, 1)


if __name__ == "__main__":
    unittest.main()
