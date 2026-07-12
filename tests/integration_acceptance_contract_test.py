from __future__ import annotations

import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

import jsonschema


ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"
SCHEMA = ROOT / "artifacts" / "acceptance" / "dm7-integration-summary.schema.json"
RUNNER = PLUGIN / "scripts" / "run-dm7-integration.ps1"
WORKTREE_SCANNER = PLUGIN / "scripts" / "verify-worktree-security.py"
REPORT_SANITIZER = PLUGIN / "scripts" / "sanitize-test-reports.py"
PACKAGE_SCANNER = PLUGIN / "scripts" / "verify-package-security.py"
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
                         "Remove-Item \"Env:$_\"", "git -C", " diff ", "target", "artifacts"):
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
        self.assertLess(runner.index("sanitize-test-reports.py"), runner.index("verify-worktree-security.py"))
        self.assertIn("$securityFailure = $false", runner)
        self.assertIn("--per-archive", runner)
        self.assertGreater(runner.index("if ($securityFailure)"), runner.index("checkout-index --all"))
        self.assertLess(runner.index("$mavenExitCode ="), runner.index("verify-worktree-security.py"))
        self.assertGreater(runner.index("if ($mavenExitCode -ne 0)"), runner.index("checkout-index --all"))
        self.assertIn("git -C $repoRoot checkout-index --all", runner)
        self.assertIn("'^javac 21(?:\\.|$)'", runner)
        pattern = re.compile(r"^javac 21(?:\.|$)")
        self.assertFalse(pattern.search("javac 20.0.2"))
        self.assertTrue(pattern.search("javac 21.0.7"))
        self.assertFalse(pattern.search("javac 22.0.1"))

    def test_worktree_scanner_rejects_ignored_untracked_and_ignored_archive_values_without_echo(self):
        secret = "adversarial-worktree-secret-中文-X92"
        for case in ("ignored-env", "untracked", "ignored-archive"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                subprocess.run(["git", "init", "-q", str(root)], check=True)
                (root / ".gitignore").write_text(".env\n*.zip\n", "utf-8")
                if case == "ignored-env":
                    (root / ".env").write_text(secret, "utf-8")
                elif case == "untracked":
                    (root / "leak.txt").write_text(secret, "utf-16-le")
                else:
                    with zipfile.ZipFile(root / "ignored.zip", "w", zipfile.ZIP_DEFLATED) as archive:
                        archive.writestr("nested/value.txt", secret.encode())
                environment = os.environ.copy(); environment["DM7_IT_PASSWORD"] = secret
                result = subprocess.run(["python", str(WORKTREE_SCANNER), str(root)], env=environment,
                                        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("integration environment value", result.stderr)
                self.assertNotIn(secret, result.stdout + result.stderr)

    def test_worktree_scanner_has_per_archive_not_global_volume_budget(self):
        scanner = WORKTREE_SCANNER.read_text("utf-8")
        self.assertIn('"ls-files","--cached","--others","--ignored","--exclude-standard","-z"', scanner)
        self.assertIn('"ls-files","--cached","--others","--exclude-standard","-z"', scanner)
        self.assertIn("scan_archive(path.read_bytes(),secrets,ArchiveBudget(),1)", scanner)
        self.assertNotIn("MAX_WORKTREE", scanner)

    def test_maven_reports_disable_environment_properties(self):
        pom = (PLUGIN / "server" / "pom.xml").read_text("utf-8")
        self.assertEqual(2, pom.count("<enablePropertiesElement>false</enablePropertiesElement>"))
        for directory in (PLUGIN / "server" / "target" / "surefire-reports",
                          PLUGIN / "server" / "target" / "failsafe-reports"):
            for report in directory.glob("TEST-*.xml") if directory.exists() else ():
                self.assertNotIn("<properties>", report.read_text("utf-8", errors="replace"), report.name)

    def test_failure_report_sanitizer_removes_properties_and_preserves_failure(self):
        secret = "failure-report-secret-中文"
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary) / "failsafe-reports"; reports.mkdir()
            report = reports / "TEST-failure.xml"
            report.write_text('<?xml version="1.0" encoding="UTF-8"?>\n'
                              '<testsuite><properties><property name="password" value="' + secret +
                              '"/></properties><testcase><failure message="safe">safe failure</failure>'
                              '</testcase></testsuite>', "utf-8")
            result = subprocess.run(["python", str(REPORT_SANITIZER), temporary], capture_output=True,
                                    text=True, encoding="utf-8", errors="replace", timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            sanitized = report.read_text("utf-8")
            self.assertNotIn("<properties", sanitized)
            self.assertNotIn(secret, sanitized)
            self.assertIn("safe failure", sanitized)

    def test_malformed_failure_report_still_reaches_secret_scanner_without_echo(self):
        secret = "malformed-report-secret-中文"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); subprocess.run(["git", "init", "-q", str(root)], check=True)
            reports = root / "target" / "failsafe-reports"; reports.mkdir(parents=True)
            (reports / "TEST-broken.xml").write_text("<testsuite><properties>" + secret, "utf-8")
            environment = os.environ.copy(); environment["DM7_IT_PASSWORD"] = secret
            sanitize = subprocess.run(["python", str(REPORT_SANITIZER), str(root / "target")],
                                      env=environment, capture_output=True, text=True, encoding="utf-8",
                                      errors="replace", timeout=30)
            scan = subprocess.run(["python", str(WORKTREE_SCANNER), str(root)], env=environment,
                                  capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            self.assertNotEqual(0, sanitize.returncode)
            self.assertNotEqual(0, scan.returncode)
            self.assertNotIn(secret, sanitize.stdout + sanitize.stderr + scan.stdout + scan.stderr)

    def test_per_archive_index_mode_does_not_share_budget_between_valid_archives(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for number in range(2):
                with zipfile.ZipFile(root / f"valid-{number}.zip", "w", zipfile.ZIP_DEFLATED) as archive:
                    for index in range(6_000): archive.writestr(f"e/{index}.txt", b"")
            result = subprocess.run(["python", str(PACKAGE_SCANNER), str(root), "--per-archive"],
                                    capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stderr)
            with zipfile.ZipFile(root / "bomb.zip", "w", zipfile.ZIP_DEFLATED) as archive:
                for index in range(10_001): archive.writestr(f"b/{index}.txt", b"")
            rejected = subprocess.run(["python", str(PACKAGE_SCANNER), str(root), "--per-archive"],
                                      capture_output=True, text=True, timeout=60)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("safety limit exceeded", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
