from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SCANNER = ROOT / "plugins" / "dm7-database" / "scripts" / "verify-package-security.py"


class PackageSecurityTest(unittest.TestCase):
    def run_scanner(self, root: Path, secret: str) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["DM7_IT_PASSWORD"] = secret
        return subprocess.run(
            ["python", str(SCANNER), str(root)], env=environment,
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
        )

    def test_rejects_high_entropy_deflated_jar_secret_without_echo(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            secret = "high-entropy-secret-7Bf92xQ"
            payload = os.urandom(65536) + secret.encode() + os.urandom(65536)
            with zipfile.ZipFile(root / "runtime.jar", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("classes/random.bin", payload)
            result = self.run_scanner(root, secret)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("integration environment value", result.stderr)
            self.assertNotIn(secret, result.stdout + result.stderr)

    def test_rejects_utf16_and_binary_values_inside_nested_archives(self):
        for encoding in ("utf-16-le", "utf-16-be", "utf-8"):
            with self.subTest(encoding=encoding), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                secret = "nested-secret-中文-9Zx"
                nested = root / "nested.zip"
                with zipfile.ZipFile(nested, "w", zipfile.ZIP_DEFLATED) as inner:
                    inner.writestr("payload.bin", os.urandom(32) + secret.encode(encoding) + os.urandom(32))
                with zipfile.ZipFile(root / "runtime.jar", "w", zipfile.ZIP_DEFLATED) as outer:
                    outer.writestr("lib/nested.zip", nested.read_bytes())
                nested.unlink()
                result = self.run_scanner(root, secret)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("integration environment value", result.stderr)
                self.assertNotIn(secret, result.stdout + result.stderr)

    def test_rejects_archive_bombs_and_excessive_nesting(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with zipfile.ZipFile(root / "runtime.jar", "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("bomb.txt", b"A" * 2_000_000)
            result = self.run_scanner(root, "absent-secret")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("archive safety limit", result.stderr)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = b"safe"
            for depth in range(8):
                archive_path = root / f"level-{depth}.zip"
                with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as archive:
                    archive.writestr(f"level-{depth + 1}.zip", data)
                data = archive_path.read_bytes()
                archive_path.unlink()
            (root / "runtime.jar").write_bytes(data)
            result = self.run_scanner(root, "absent-secret")
            self.assertNotEqual(0, result.returncode)
            self.assertIn("archive safety limit", result.stderr)


if __name__ == "__main__":
    unittest.main()
