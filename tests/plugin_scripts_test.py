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
from concurrent.futures import ThreadPoolExecutor


ROOT = Path(__file__).resolve().parents[1]
PLUGIN = ROOT / "plugins" / "dm7-database"
POWERSHELL = shutil.which("powershell.exe")


@unittest.skipUnless(POWERSHELL, "Windows PowerShell 5.1 is required")
class PluginScriptsTest(unittest.TestCase):
    def clean_environment(self) -> dict[str, str]:
        environment = os.environ.copy()
        for name in (
            "CODEX_SESSION_ID",
            "CODEX_THREAD_ID",
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
            self.assertEqual({"sessionHash", "timestamp", "processThreadHash"}, set(context))
            self.assertNotIn("thread-with-中文", files[0].read_text("utf-8"))
            self.assertFalse((data / "release").exists())
            acl_command = f"[IO.Directory]::GetAccessControl('{(data / 'session-context')}').AreAccessRulesProtected"
            acl_environment = self.clean_environment()
            acl_environment.pop("PSModulePath", None)
            acl = subprocess.run([POWERSHELL, "-NoProfile", "-Command", acl_command], env=acl_environment,
                                 capture_output=True, text=True)
            self.assertEqual("True", acl.stdout.strip(), acl.stderr)

    def test_session_hook_is_atomic_under_concurrency_and_missing_env_is_noop(self):
        with tempfile.TemporaryDirectory() as temporary:
            data = Path(temporary) / "plugin data"
            environment = self.clean_environment()
            environment["PLUGIN_DATA"] = str(data)
            environment["CODEX_SESSION_ID"] = "session-private-value"
            environment["CODEX_THREAD_ID"] = "thread-private-value"
            hook = PLUGIN / "hooks" / "session-context.ps1"
            with ThreadPoolExecutor(max_workers=8) as pool:
                results = list(pool.map(lambda _: self.run_powershell(hook, cwd=Path(temporary), environment=environment), range(12)))
            self.assertTrue(all(result.returncode == 0 for result in results), [result.stderr for result in results])
            files = list((data / "session-context").glob("*.json"))
            self.assertEqual(1, len(files))
            raw = files[0].read_text("utf-8")
            json.loads(raw)
            self.assertNotIn("session-private-value", raw)
            self.assertNotIn("thread-private-value", raw)
            self.assertEqual([], list((data / "session-context").glob("*.tmp")))

            missing = self.clean_environment()
            missing.pop("CODEX_SESSION_ID", None)
            missing.pop("CODEX_THREAD_ID", None)
            missing["PLUGIN_DATA"] = str(Path(temporary) / "missing")
            result = self.run_powershell(hook, cwd=Path(temporary), environment=missing)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertFalse((Path(temporary) / "missing").exists())

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

    def test_mcp_launcher_selects_java_17_and_preserves_protocol_stdout(self):
        java = Path(r"C:\tool\jdk21\bin\java.exe")
        if not java.is_file():
            self.skipTest("JDK 21 test runtime is unavailable")
        with tempfile.TemporaryDirectory(prefix="dm7 launcher ") as temporary:
            codex_home = Path(temporary) / "codex home"
            plugin_version = json.loads((PLUGIN / ".codex-plugin" / "plugin.json").read_text("utf-8"))["version"]
            root = codex_home / "plugins" / "cache" / "test market" / "dm7-database" / plugin_version
            (root / "scripts").mkdir(parents=True)
            (root / "lib").mkdir()
            (root / ".codex-plugin").mkdir()
            shutil.copy2(PLUGIN / "scripts" / "launch-mcp.ps1", root / "scripts")
            shutil.copy2(PLUGIN / "lib" / "dm7-codex-plugin.jar", root / "lib")
            shutil.copy2(PLUGIN / ".codex-plugin" / "plugin.json", root / ".codex-plugin")
            self.assertEqual(
                hashlib.sha256((PLUGIN / "scripts" / "launch-mcp.ps1").read_bytes()).hexdigest(),
                hashlib.sha256((root / "scripts" / "launch-mcp.ps1").read_bytes()).hexdigest(),
            )
            self.assertEqual(
                hashlib.sha256((PLUGIN / "lib" / "dm7-codex-plugin.jar").read_bytes()).hexdigest(),
                hashlib.sha256((root / "lib" / "dm7-codex-plugin.jar").read_bytes()).hexdigest(),
            )
            decoy_root = codex_home / "plugins" / "cache" / "decoy market" / "dm7-database" / plugin_version
            decoy = decoy_root / "scripts"
            decoy.mkdir(parents=True)
            (decoy_root / "lib").mkdir()
            (decoy_root / ".codex-plugin").mkdir()
            marker = Path(temporary) / "decoy-executed"
            (decoy / "launch-mcp.ps1").write_text(
                f"[IO.File]::WriteAllText('{marker}', 'unsafe')\n", encoding="utf-8")
            shutil.copy2(PLUGIN / "lib" / "dm7-codex-plugin.jar", decoy_root / "lib")
            shutil.copy2(PLUGIN / ".codex-plugin" / "plugin.json", decoy_root / ".codex-plugin")
            mcp = json.loads((PLUGIN / ".mcp.json").read_text("utf-8"))["mcpServers"]["dm7"]
            arguments = list(mcp["args"])
            environment = self.clean_environment()
            environment.pop("DM7_CODEX_JAVA", None)
            environment["DM7_CODEX_JAVA_SEARCH_ROOTS"] = str(java.parents[2])
            environment["CODEX_HOME"] = str(codex_home)
            environment["JAVA_HOME"] = str(Path(temporary) / "invalid-old-java")
            environment.pop("PLUGIN_DATA", None)
            environment["CODEX_THREAD_ID"] = "launcher-thread"
            environment.pop("PLUGIN_ROOT", None)
            diagnostic = Path(temporary) / "launcher-status.log"
            environment["DM7_MCP_DIAGNOSTIC_FILE"] = str(diagnostic)
            initialize = json.dumps({
                "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                    "protocolVersion": "2025-06-18", "capabilities": {},
                    "clientInfo": {"name": "launcher-test", "version": "1"},
                },
            }) + "\n"
            initialized = json.dumps({
                "jsonrpc": "2.0", "method": "notifications/initialized", "params": {},
            }) + "\n"
            list_connections = json.dumps({
                "jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
                    "name": "dm7_list_connections", "arguments": {},
                },
            }) + "\n"

            process = subprocess.Popen(
                [mcp["command"], *arguments], cwd=root, env=environment,
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                text=True, encoding="utf-8", errors="replace",
            )
            try:
                process.stdin.write(initialize); process.stdin.flush()
                first = process.stdout.readline()
                process.stdin.write(initialized + list_connections); process.stdin.flush()
                second = process.stdout.readline()
                process.stdin.close()
                returncode = process.wait(timeout=60)
                stdout = first + second + process.stdout.read()
                stderr = process.stderr.read()
            finally:
                if process.poll() is None:
                    process.kill()
                process.stdout.close()
                process.stderr.close()

            self.assertEqual(0, returncode, stderr)
            frames = [json.loads(line) for line in stdout.splitlines()]
            self.assertEqual([1, 2], [frame.get("id") for frame in frames])
            self.assertNotIn("java version", stderr.lower())
            self.assertEqual("JAVA_EXIT_0", diagnostic.read_text("utf-8").strip())
            self.assertFalse(marker.exists(), "bootstrap executed a higher-version decoy cache")
            durable_data = codex_home / "plugins" / "data" / "dm7-database-dm7-database-local"
            self.assertTrue(durable_data.is_dir())

    def make_fake_command(self, directory: Path, name: str) -> None:
        (directory / f"{name}.cmd").write_text(
            "@echo off\r\n"
            "if /I \"%TEST_FAIL_COMMAND%\"==\"%~n0\" exit /b 23\r\n"
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

    def test_build_and_test_propagate_every_external_gate_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary); fake_bin = base / "bin"; fake_bin.mkdir()
            for command in ("python", "mvn", "pnpm"):
                self.make_fake_command(fake_bin, command)
            environment = self.clean_environment()
            environment["PATH"] = str(fake_bin) + os.pathsep + environment["PATH"]
            environment["TEST_COMMAND_LOG"] = str(base / "commands.log")
            environment["SOURCE_DATE_EPOCH"] = "1783612800"
            for script, failures in (("build.ps1", ("pnpm", "mvn")), ("test.ps1", ("python", "pnpm", "mvn"))):
                for failure in failures:
                    with self.subTest(script=script, failure=failure):
                        environment["TEST_FAIL_COMMAND"] = failure
                        result = self.run_powershell(PLUGIN / "scripts" / script, cwd=base, environment=environment)
                        self.assertNotEqual(0, result.returncode)

    def test_package_propagates_test_and_build_gate_failures(self):
        for gate in ("test.ps1", "build.ps1"):
            with self.subTest(gate=gate), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary); _, plugin = self.make_package_fixture(base)
                (plugin / "scripts" / gate).write_text("throw 'fixture gate failure'\n", encoding="utf-8")
                result = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("fixture gate failure", result.stderr)

    def make_package_fixture(self, base: Path) -> tuple[Path, Path]:
        repo = base / "repo with spaces"
        plugin = repo / "plugins" / "dm7-database"
        scripts = plugin / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy2(PLUGIN / "scripts" / "package.ps1", scripts)
        shutil.copy2(PLUGIN / "scripts" / "verify-package-security.py", scripts)
        shutil.copy2(PLUGIN / "scripts" / "verify-license-inventory.py", scripts)
        for name in ("test.ps1", "build.ps1"):
            (scripts / name).write_text("$ErrorActionPreference = 'Stop'\n", encoding="utf-8")
        (scripts / "verify-extracted.ps1").write_text("$ErrorActionPreference = 'Stop'\n", encoding="utf-8")
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
            "licenses/dependencies.json": '{"schemaVersion":1,"licenseRefs":{},"components":[{"id":"fixture:one","license":"MIT","licenseFile":"licenses/components/fixture.txt","provenance":"fixture"}]}',
            "licenses/components/fixture.txt": "fixture license",
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
            self.assertTrue(any(name.endswith("/licenses/dependencies.json") for name in names), names)
            self.assertTrue(any(name.endswith("/licenses/components/fixture.txt") for name in names), names)
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

    def test_package_rejects_an_additional_renamed_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            _, plugin = self.make_package_fixture(temporary_path)
            (plugin / "lib" / "driver.jar").write_bytes(b"renamed driver")

            result = self.run_powershell(
                plugin / "scripts" / "package.ps1",
                cwd=temporary_path,
            )

            self.assertNotEqual(0, result.returncode)
            self.assertIn("Package contains forbidden files", result.stderr)

    def test_package_rejects_rogue_unregistered_license_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary); _, plugin = self.make_package_fixture(base)
            (plugin / "licenses" / "components" / "rogue.txt").write_text("rogue", encoding="utf-8")
            result = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Dependency license inventory validation failed", result.stderr)

    def test_package_rejects_env_files_outside_dot_prefix(self):
        with tempfile.TemporaryDirectory() as temporary:
            temporary_path = Path(temporary)
            _, plugin = self.make_package_fixture(temporary_path)
            (plugin / "assets" / "prod.env").write_text("fixture", encoding="utf-8")

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

    def test_package_rejects_secret_inside_deflated_runtime_jar(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            _, plugin = self.make_package_fixture(base)
            secret = "jar-only-secret-Q7x9"
            runtime_jar = plugin / "lib" / "dm7-codex-plugin.jar"
            with zipfile.ZipFile(runtime_jar, "w", zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("random.bin", os.urandom(65536))
                archive.writestr("secret.txt", (secret + "\n") * 10)
            self.assertNotIn(secret.encode(), runtime_jar.read_bytes(), "fixture secret must exist only after decompression")
            environment = self.clean_environment(); environment["DM7_IT_PASSWORD"] = secret
            result = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base, environment=environment)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("integration environment value", result.stderr)
            self.assertNotIn(secret, result.stdout + result.stderr)

    def test_package_propagates_fresh_extraction_gate_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            _, plugin = self.make_package_fixture(base)
            (plugin / "scripts" / "verify-extracted.ps1").write_text("throw 'fixture verify failure'\n", encoding="utf-8")
            result = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("fixture verify failure", result.stderr)

    def test_package_is_byte_reproducible_and_rejects_unexpected_archives(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            repo, plugin = self.make_package_fixture(base)
            environment = self.clean_environment()
            environment["SOURCE_DATE_EPOCH"] = "1783612800"
            first = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base, environment=environment)
            self.assertEqual(0, first.returncode, first.stderr)
            archive = repo / "dist" / "dm7-database-0.1.0.zip"
            first_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
            second = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base, environment=environment)
            self.assertEqual(0, second.returncode, second.stderr)
            self.assertEqual(first_hash, hashlib.sha256(archive.read_bytes()).hexdigest())

            unexpected = plugin / "assets" / "nested.zip"
            unexpected.write_bytes(b"PK\x03\x04unexpected")
            rejected = self.run_powershell(plugin / "scripts" / "package.ps1", cwd=base, environment=environment)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("Package contains forbidden files", rejected.stderr)

    def test_scripts_and_docs_declare_complete_runtime_contracts(self):
        build = (PLUGIN / "scripts" / "build.ps1").read_text("utf-8")
        tests = (PLUGIN / "scripts" / "test.ps1").read_text("utf-8")
        package = (PLUGIN / "scripts" / "package.ps1").read_text("utf-8")
        extracted = (PLUGIN / "scripts" / "verify-extracted.ps1").read_text("utf-8")
        self.assertIn("JAVA_HOME", build)
        self.assertIn("clean package", build)
        self.assertIn("mcp_stdio_smoke.py", tests)
        self.assertIn("web_assets_test.py", tests)
        self.assertIn(" e2e", tests)
        self.assertIn("FirstJarHash", package)
        self.assertIn("SecondJarHash", package)
        self.assertIn("SOURCE_DATE_EPOCH", package)
        self.assertIn("315532800", build + tests + package)
        self.assertIn("verify-package-security.py", package)
        self.assertIn("DM7_CODEX_JAVA17_HOME", package + extracted)
        self.assertIn("DM7_SMOKE_PLUGIN_ROOT", package + extracted)
        self.assertIn("validate_plugin.py", package + extracted)

        docs = "\n".join(path.read_text("utf-8") for path in
                         [ROOT / "README.md", PLUGIN / "README.md", PLUGIN / "SECURITY.md", *sorted((PLUGIN / "docs").glob("*.md"))])
        for required in (
            "Java 17", "BYO-driver", "dbname=", "schema=", "UTF-8",
            "dm7_open_console", "dm7_list_connections", "dm7_test_connection", "dm7_query", "dm7_execute",
            "dm7_describe_schema", "dm7_get_execution", "dm7_cancel_execution", "dm7_get_release_log", "dm7_release_export",
            "TEST", "MOCK", "SEED", "SAMPLE", "测试SQL", "PLUGIN_DATA", "backup", "restore", "recovery",
            "cachebuster", "new task", "codex plugin marketplace add",
        ):
            self.assertIn(required, docs)


if __name__ == "__main__":
    unittest.main()
