from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"


class LicenseInventoryTest(unittest.TestCase):
    def test_plugin_license_matches_full_apache_2_text(self):
        manifest = json.loads((PLUGIN / ".codex-plugin" / "plugin.json").read_text("utf-8"))
        license_text = (PLUGIN / "LICENSE").read_text("utf-8")
        self.assertEqual("Apache-2.0", manifest["license"])
        self.assertIn("Apache License", license_text)
        self.assertIn("Version 2.0, January 2004", license_text)
        self.assertIn("END OF TERMS AND CONDITIONS", license_text)

    def test_inventory_covers_maven_runtime_and_pnpm_production_dependencies(self):
        inventory_path = PLUGIN / "licenses" / "dependencies.json"
        inventory = json.loads(inventory_path.read_text("utf-8"))
        covered = {item["id"] for item in inventory["components"]}
        self.assertIn("embedded:sqlite-engine@3.53.2", covered)
        for item in inventory["components"]:
            self.assertTrue(item["license"])
            self.assertTrue((PLUGIN / item["licenseFile"]).is_file(), item)
            self.assertTrue(item["provenance"])

        pnpm = shutil.which("pnpm.cmd") or shutil.which("pnpm")
        if not pnpm:
            candidates = sorted((Path.home() / ".cache" / "codex-runtimes").glob("*/dependencies/bin/fallback/pnpm.cmd"))
            pnpm = str(candidates[-1]) if candidates else None
        self.assertIsNotNone(pnpm, "pnpm is required for license coverage verification")
        node_candidates = sorted((Path.home() / ".cache" / "codex-runtimes").glob("*/dependencies/node/bin/node.exe"))
        environment = os.environ.copy()
        if node_candidates:
            environment["PATH"] = str(node_candidates[-1].parent) + os.pathsep + environment["PATH"]
        result = subprocess.run([pnpm, "--dir", str(PLUGIN / "web"), "licenses", "list", "--prod", "--json"],
                                env=environment, capture_output=True, text=True, encoding="utf-8", timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        pnpm_ids = {f"npm:{entry['name']}@{version}" for entries in json.loads(result.stdout).values()
                    for entry in entries for version in entry["versions"]}

        java_home = os.environ.get("DM7_CODEX_JAVA_HOME") or "C:\\tool\\jdk21"
        maven = shutil.which("mvn.cmd") or str(Path("C:/tool/apache-maven-3.9.16/bin/mvn.cmd"))
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "dependencies.txt"
            env = os.environ.copy(); env["JAVA_HOME"] = java_home
            result = subprocess.run([maven, "-q", "-f", str(PLUGIN / "server" / "pom.xml"), "dependency:list",
                                     "-DincludeScope=runtime", f"-DoutputFile={output}"], env=env,
                                    capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
            self.assertEqual(0, result.returncode, result.stderr)
            coordinates = set()
            for line in output.read_text("utf-8").splitlines():
                match = re.search(r"\s*([^: ]+):([^: ]+):jar:([^: ]+):", line)
                if match:
                    coordinates.add(f"maven:{match.group(1)}:{match.group(2)}:{match.group(3)}")

        self.assertEqual(set(), (pnpm_ids | coordinates) - covered)

        jackson = [item for item in inventory["components"] if item["id"].startswith("maven:com.fasterxml.jackson")]
        notice_hashes = set()
        for item in jackson:
            _, group, artifact, version = item["id"].split(":", 3)
            jar = Path.home() / ".m2" / "repository" / Path(*group.split(".")) / artifact / version / f"{artifact}-{version}.jar"
            with zipfile.ZipFile(jar) as archive:
                notice = archive.read("META-INF/NOTICE").decode("utf-8", "replace").strip()
            notice_hashes.add(hashlib.sha256(notice.encode()).hexdigest())
            self.assertIn(notice, (PLUGIN / item["licenseFile"]).read_text("utf-8"))
        self.assertEqual(3, len(notice_hashes))


if __name__ == "__main__":
    unittest.main()
