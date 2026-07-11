from __future__ import annotations

import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "plugins" / "dm7-database" / "scripts" / "verify-license-inventory.py"


class LicenseInventoryValidationTest(unittest.TestCase):
    def fixture(self, base: Path, license_file: str = "licenses/components/one.txt") -> Path:
        plugin = base / "plugin"; component = plugin / "licenses" / "components" / "one.txt"
        component.parent.mkdir(parents=True); component.write_text("license", encoding="utf-8")
        inventory = {"schemaVersion": 1, "licenseRefs": {}, "components": [
            {"id": "npm:one@1", "license": "MIT", "licenseFile": license_file, "provenance": "fixture"}
        ]}
        (plugin / "licenses" / "dependencies.json").write_text(json.dumps(inventory), encoding="utf-8")
        return plugin

    def verify(self, plugin: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(["python", str(VERIFIER), str(plugin)], capture_output=True, text=True, timeout=30)

    def test_accepts_exact_inventory_and_rejects_extra_or_missing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            plugin = self.fixture(Path(temporary)); self.assertEqual(0, self.verify(plugin).returncode)
            rogue = plugin / "licenses" / "components" / "rogue.txt"; rogue.write_text("rogue", encoding="utf-8")
            self.assertNotEqual(0, self.verify(plugin).returncode); rogue.unlink()
            (plugin / "licenses" / "components" / "one.txt").unlink()
            self.assertNotEqual(0, self.verify(plugin).returncode)

    def test_rejects_traversal_noncanonical_and_duplicate_records(self):
        for value in ("../outside.txt", "licenses\\components\\one.txt", "licenses/components/../one.txt"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as temporary:
                plugin = self.fixture(Path(temporary), value)
                self.assertNotEqual(0, self.verify(plugin).returncode)
        with tempfile.TemporaryDirectory() as temporary:
            plugin = self.fixture(Path(temporary)); path = plugin / "licenses" / "dependencies.json"
            value = json.loads(path.read_text("utf-8")); value["components"].append(value["components"][0])
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertNotEqual(0, self.verify(plugin).returncode)

    def test_requires_definition_for_custom_license_ref(self):
        with tempfile.TemporaryDirectory() as temporary:
            plugin = self.fixture(Path(temporary)); path = plugin / "licenses" / "dependencies.json"
            value = json.loads(path.read_text("utf-8")); value["components"][0]["license"] = "LicenseRef-Custom"
            path.write_text(json.dumps(value), encoding="utf-8")
            self.assertNotEqual(0, self.verify(plugin).returncode)


if __name__ == "__main__":
    unittest.main()
