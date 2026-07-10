from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"
POWERSHELL = shutil.which("powershell.exe")


@unittest.skipUnless(POWERSHELL, "Windows PowerShell 5.1 is required")
class PluginScriptsTest(unittest.TestCase):
    def clean_environment(self) -> dict[str, str]:
        environment = os.environ.copy()
        for name in (
            "DM7_IT_JDBC_URL",
            "DM7_IT_USERNAME",
            "DM7_IT_PASSWORD",
            "DM7_IT_DRIVER_JAR",
        ):
            environment.pop(name, None)
        return environment

    def run_powershell(
        self,
        script: Path,
        *,
        cwd: Path,
        environment: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                POWERSHELL,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(script),
            ],
            cwd=cwd,
            env=environment or self.clean_environment(),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
        )

    def test_session_hook_runs_under_windows_powershell_51(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary) / "plugin data"
            environment = self.clean_environment()
            environment["PLUGIN_DATA"] = str(data)
            environment["CODEX_THREAD_ID"] = "thread-with-中文"

            result = self.run_powershell(
                PLUGIN / "hooks" / "session-context.ps1",
                cwd=Path(temporary),
                environment=environment,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            files = list((data / "session-context").glob("*.json"))
            self.assertEqual(1, len(files))
            context = json.loads(files[0].read_text("utf-8"))
            expected = hashlib.sha256("thread-with-中文".encode()).hexdigest()
            self.assertEqual(expected, context["sessionHash"])
            self.assertEqual(expected, context["processThreadHash"])

    def test_hook_command_quotes_a_plugin_root_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix="dm7 plugin root ") as temporary:
            plugin_root = Path(temporary) / "plugin with spaces"
            hooks = plugin_root / "hooks"
            hooks.mkdir(parents=True)
            shutil.copy2(PLUGIN / "hooks" / "session-context.ps1", hooks)
            command = json.loads((PLUGIN / "hooks" / "hooks.json").read_text("utf-8"))[
                "hooks"
            ]["SessionStart"][0]["hooks"][0]["command"]
            rendered = command.replace("${PLUGIN_ROOT}", plugin_root.as_posix())
            environment = self.clean_environment()
            environment["PLUGIN_DATA"] = str(Path(temporary) / "plugin data")
            environment["CODEX_THREAD_ID"] = "space-path-thread"

            result = subprocess.run(
                [POWERSHELL, "-NoProfile", "-Command", rendered],
                cwd=temporary,
                env=environment,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn('-File "${PLUGIN_ROOT}/hooks/session-context.ps1"', command)

    def make_fake_command(self, directory: Path, name: str) -> None:
        (directory / f"{name}.cmd").write_text(
            "@echo off\r\n"
            "echo %CD%^|%~nx0 %*>>\"%TEST_COMMAND_LOG%\"\r\n"
            "exit /b 0\r\n",
            encoding="ascii",
        )

    def test_build_and_test_scripts_work_outside_the_repository(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            fake_bin = temporary_path / "fake-bin"
            outside = temporary_path / "outside"
            fake_bin.mkdir()
            outside.mkdir()
            for command in ("python", "mvn", "pnpm"):
                self.make_fake_command(fake_bin, command)
            log = temporary_path / "commands.log"
            environment = self.clean_environment()
            environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
            environment["TEST_COMMAND_LOG"] = str(log)
            environment["SOURCE_DATE_EPOCH"] = "1783612800"

            test_result = self.run_powershell(
                PLUGIN / "scripts" / "test.ps1",
                cwd=outside,
                environment=environment,
            )
            build_result = self.run_powershell(
                PLUGIN / "scripts" / "build.ps1",
                cwd=outside,
                environment=environment,
            )

            self.assertEqual(0, test_result.returncode, test_result.stderr)
            self.assertEqual(0, build_result.returncode, build_result.stderr)
            calls = log.read_text("utf-8").splitlines()
            self.assertGreaterEqual(len(calls), 5)
            self.assertTrue(all(line.lower().startswith(str(ROOT).lower()) for line in calls), calls)
            pnpm_calls = [line for line in calls if "pnpm.cmd" in line.lower()]
            self.assertTrue(any("install --frozen-lockfile" in line for line in pnpm_calls), pnpm_calls)
            self.assertTrue(any(line.rstrip().endswith(" build") for line in pnpm_calls), pnpm_calls)

    def make_package_fixture(self, base: Path) -> tuple[Path, Path]:
        repo = base / "repo with spaces"
        plugin = repo / "plugins" / "dm7-database"
        scripts = plugin / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(PLUGIN / "scripts" / "package.ps1", scripts)
        for name in ("test.ps1", "build.ps1"):
            (scripts / name).write_text("$ErrorActionPreference = 'Stop'\n", encoding="utf-8")
        runtime_files = {
            ".codex-plugin/plugin.json": "{}",
            ".mcp.json": "{}",
            "assets/icon.svg": "<svg/>",
            "hooks/hooks.json": "{}",
            "hooks/session-context.ps1": "exit 0",
            "lib/dm7-codex-plugin.jar": "runtime",
            "skills/dm7-database/SKILL.md": "---\nname: dm7-database\ndescription: fixture\n---",
            "README.md": "runtime docs",
            "LICENSE": "license",
            "THIRD_PARTY_NOTICES.md": "notices",
            "server/target/local.txt": "must not ship",
            "server/src/main/java/Secret.java": "must not ship",
            "web/node_modules/local.txt": "must not ship",
            "web/src/main.tsx": "must not ship",
        }
        for relative, content in runtime_files.items():
            path = plugin / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        return repo, plugin

    def test_package_uses_a_fresh_runtime_only_staging_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            repo, plugin = self.make_package_fixture(temporary_path)
            outside = temporary_path / "outside"
            outside.mkdir()

            result = self.run_powershell(
                plugin / "scripts" / "package.ps1",
                cwd=outside,
            )

            self.assertEqual(0, result.returncode, result.stderr)
            archive = repo / "dist" / "dm7-database-0.1.0.zip"
            self.assertTrue(archive.is_file())
            with zipfile.ZipFile(archive) as package:
                names = {name.replace("\\", "/") for name in package.namelist()}
            self.assertTrue(any(name.endswith("/.codex-plugin/plugin.json") for name in names), names)
            for forbidden_part in ("/server/", "/web/", "/scripts/", "node_modules", "target/"):
                self.assertFalse(any(forbidden_part in f"/{name}" for name in names), forbidden_part)

    def test_package_rejects_forbidden_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            _, plugin = self.make_package_fixture(temporary_path)
            forbidden = plugin / "assets" / "Dm7JdbcDriver-7.0.jar"
            forbidden.write_bytes(b"driver")

            result = self.run_powershell(
                plugin / "scripts" / "package.ps1",
                cwd=temporary_path,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Package contains forbidden files", result.stderr)

    def test_package_rejects_environment_values_without_echoing_them(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            _, plugin = self.make_package_fixture(temporary_path)
            secret = "never-print-this-dm7-secret"
            (plugin / "assets" / "leak.txt").write_text(secret, encoding="utf-8")
            environment = self.clean_environment()
            environment["DM7_IT_PASSWORD"] = secret

            result = self.run_powershell(
                plugin / "scripts" / "package.ps1",
                cwd=temporary_path,
                environment=environment,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Package contains an integration environment value", result.stderr)
            self.assertNotIn(secret, result.stdout)
            self.assertNotIn(secret, result.stderr)


if __name__ == "__main__":
    unittest.main()
