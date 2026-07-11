from __future__ import annotations

import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import unittest

import jsonschema


ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"
SCHEMA = ROOT / "artifacts" / "acceptance" / "dm7-integration-summary.schema.json"
RUNNER = PLUGIN / "scripts" / "run-dm7-integration.ps1"
POWERSHELL = shutil.which("powershell.exe")


class IntegrationAcceptanceContractTest(unittest.TestCase):
    def sample(self) -> dict:
        return {
            "passed": True,
            "driverSha256": "A" * 64,
            "driverVersion": "DM JDBC 7.6",
            "serverVersion": "DM DBMS 7.6",
            "targetFingerprint": "b" * 64,
            "cases": [{"name": "connection", "passed": True, "durationMs": 1},
                      {"name": "cancellation", "passed": True, "durationMs": 0, "supported": False}],
            "cleanupConfirmed": True,
        }

    def test_summary_schema_is_strict_and_accepts_only_sanitized_shape(self):
        schema = json.loads(SCHEMA.read_text("utf-8"))
        jsonschema.validate(self.sample(), schema)
        for key, value in (("driverSha256", "a" * 64), ("targetFingerprint", "B" * 64),
                           ("jdbcUrl", "forbidden")):
            invalid = self.sample(); invalid[key] = value
            with self.subTest(key=key), self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate(invalid, schema)

    @unittest.skipUnless(POWERSHELL, "Windows PowerShell is required")
    def test_runner_fails_closed_with_missing_environment_without_values(self):
        environment = os.environ.copy()
        for name in ("DM7_IT_JDBC_URL", "DM7_IT_USERNAME", "DM7_IT_PASSWORD", "DM7_IT_DRIVER_JAR"):
            environment.pop(name, None)
        result = subprocess.run([POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(RUNNER)],
                                cwd=ROOT, env=environment, capture_output=True, text=True, timeout=30)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exactly four integration environment variables", result.stderr)
        self.assertNotIn("jdbc:", result.stdout + result.stderr)

    def test_profile_runner_and_integration_class_declare_fail_closed_contracts(self):
        pom = (PLUGIN / "server" / "pom.xml").read_text("utf-8")
        runner = RUNNER.read_text("utf-8")
        integration = (PLUGIN / "server" / "src" / "test" / "java" / "io" / "dm7codex" / "plugin" /
                       "integration" / "Dm7IntegrationTest.java").read_text("utf-8")
        self.assertIn("<id>integration</id>", pom)
        self.assertIn("maven-enforcer-plugin", pom)
        self.assertIn("Dm7IntegrationTest", pom)
        for name in ("DM7_IT_JDBC_URL", "DM7_IT_USERNAME", "DM7_IT_PASSWORD", "DM7_IT_DRIVER_JAR"):
            self.assertEqual(1, runner.count(f"'{name}'"), name)
            self.assertIn(name, integration)
        for required in ("verify-package-security.py", "dm7-integration-summary.schema.json",
                         "Remove-Item \"Env:$_\"", "git -C", " diff ", "target", "artifacts", "dist"):
            self.assertIn(required, runner)
        for required in ("CODEX_DM7_IT_", "中文验证：达梦数据库", "中文列名", '"TEST"', '"MOCK"',
                         '"SEED"', '"SAMPLE"', "cleanupConfirmed", "statementCount"):
            self.assertIn(required, integration)

    def test_cleanup_proof_preserves_primary_failure_and_uses_exact_current_run_names(self):
        integration = (PLUGIN / "server" / "src" / "test" / "java" / "io" / "dm7codex" / "plugin" /
                       "integration" / "Dm7IntegrationTest.java").read_text("utf-8")
        runner = RUNNER.read_text("utf-8")
        self.assertIn("addSuppressed", integration)
        self.assertIn("cleanup identifiers unavailable", integration)
        self.assertIn("dm7.integration.cleanup-manifest", integration)
        self.assertIn("Cleanup manifest remained after independent verification", runner)
        self.assertNotIn('"dm.jdbc.driver.DmDriver"', integration)
        self.assertIn("ConnectionProfile.DEFAULT_DRIVER_CLASS", integration)

    def test_runner_scans_failure_outputs_staged_blobs_and_requires_exact_jdk21(self):
        runner = RUNNER.read_text("utf-8")
        self.assertLess(runner.index("$mavenExitCode ="), runner.index("$scanRoots ="))
        self.assertGreater(runner.index("if ($mavenExitCode -ne 0)"), runner.index("checkout-index --all"))
        self.assertIn(".superpowers\\sdd", runner)
        self.assertIn("'^javac 21(?:\\.|$)'", runner)
        pattern = re.compile(r"^javac 21(?:\.|$)")
        self.assertFalse(pattern.search("javac 20.0.2"))
        self.assertTrue(pattern.search("javac 21.0.7"))
        self.assertFalse(pattern.search("javac 22.0.1"))


if __name__ == "__main__":
    unittest.main()
