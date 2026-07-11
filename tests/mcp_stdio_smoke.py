#!/usr/bin/env python3
"""Black-box smoke test for the packaged DM7 MCP STDIO server."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "plugins" / "dm7-database" / "lib" / "dm7-codex-plugin.jar"
TOOLS = [
    "dm7_open_console", "dm7_list_connections", "dm7_test_connection", "dm7_query",
    "dm7_execute", "dm7_describe_schema", "dm7_get_execution", "dm7_cancel_execution",
    "dm7_get_release_log", "dm7_release_export",
]


def launch(data: Path) -> subprocess.Popen[str]:
    env = os.environ.copy()
    env.update({"PLUGIN_DATA": str(data), "CODEX_THREAD_ID": "smoke-thread-中文"})
    return subprocess.Popen(
        [str(Path(os.environ.get("JAVA_HOME", r"C:\tool\jdk21")) / "bin" / "java.exe"),
         "-Dfile.encoding=UTF-8", "-jar", str(JAR), "--stdio"],
        cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")


def exchange(process: subprocess.Popen[str], requests: list[dict]) -> tuple[list[dict], str]:
    payload = "".join(json.dumps(item, ensure_ascii=False, separators=(",", ":")) + "\n" for item in requests)
    assert process.stdin and process.stdout and process.stderr
    process.stdin.write(payload)
    process.stdin.flush()
    expected_responses = sum(1 for request in requests if "method" in request and "id" in request)
    lines = [process.stdout.readline() for _ in range(expected_responses)]
    assert all(lines), lines
    process.stdin.close()
    process.wait(timeout=30)
    stdout = "".join(lines) + process.stdout.read()
    stderr = process.stderr.read()
    assert process.returncode == 0, (process.returncode, stderr)
    frames = []
    for line in stdout.splitlines():
        assert line.strip(), "stdout contains a blank/non-protocol frame"
        frames.append(json.loads(line))
    return frames, stderr


def main() -> None:
    assert JAR.is_file(), f"package first: {JAR}"
    with tempfile.TemporaryDirectory(prefix="dm7-mcp-中文-") as raw:
        data = Path(raw)
        process = launch(data)
        invalid_calls = [
            (10, "dm7_open_console", {"sessionId": "forged"}),
            (11, "dm7_list_connections", {"unexpected": True}),
            (12, "dm7_test_connection", {"connectionId": 7}),
            (13, "dm7_query", {"parameters": {}}),
            (14, "dm7_execute", {"sql": "update t set c=1", "purpose": "invalid"}),
            (15, "dm7_describe_schema", {"limit": 201}),
            (16, "dm7_get_execution", {}),
            (17, "dm7_cancel_execution", {"executionId": True}),
            (18, "dm7_get_release_log", {"sessionId": "forged"}),
            (19, "dm7_release_export", {"confirm": "true"}),
        ]
        requests = [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                "protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "dm7-smoke", "version": "1.0.0"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
            {"jsonrpc": "2.0", "id": "client-response", "result": {"accepted": True}},
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
            {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {
                "name": "dm7_get_release_log", "arguments": {}}},
            {"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {
                "name": "dm7_query", "arguments": {"sql": "select '中文'"}}},
        ] + [{"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": {
            "name": name, "arguments": arguments}} for request_id, name, arguments in invalid_calls]
        frames, stderr = exchange(process, requests)
        by_id = {frame.get("id"): frame for frame in frames if "id" in frame}
        assert {1, 2, 3, 4}.issubset(by_id), frames
        assert by_id[1]["result"]["serverInfo"] == {"name": "dm7-database", "version": "0.1.0"}
        assert [tool["name"] for tool in by_id[2]["result"]["tools"]] == TOOLS
        release = by_id[3]["result"]
        assert release["isError"] is False and release["structuredContent"]["currentVersion"] == "v001"
        assert by_id[4]["result"]["isError"] is True
        for request_id, _, _ in invalid_calls:
            result = by_id[request_id]["result"]
            assert result["isError"] is True, (request_id, result)
            assert result["structuredContent"]["code"] == "INVALID_ARGUMENT", (request_id, result)
        active = list((data / "sessions").glob("*/active.sql"))
        assert len(active) == 1 and not active[0].read_bytes().startswith(b"\xef\xbb\xbf")
        assert "version: v001" in active[0].read_text(encoding="utf-8")
        lowered = stderr.lower()
        for secret in ("password", "master.key", "jdbc:dm7://"):
            assert secret not in lowered, (secret, stderr)

        malformed_data = data / "malformed"
        malformed_data.mkdir()
        malformed = launch(malformed_data)
        try:
            stdout, malformed_stderr = malformed.communicate("not-json\n", timeout=5)
        except subprocess.TimeoutExpired:
            malformed.kill()
            malformed.communicate(timeout=5)
            raise AssertionError("malformed JSON left the STDIO server hanging")
        assert malformed.returncode == 0, malformed_stderr
        malformed_frames = [json.loads(line) for line in stdout.splitlines()]
        assert malformed_frames and malformed_frames[0]["error"]["code"] == -32700

        marker = "password=NEVER_ECHO SQL_SECRET_MARKER"
        invalid_protocol = [
            [], None, 7, marker, {},
            {"method": "tools/list", "id": 21},
            {"jsonrpc": "1.0", "method": "tools/list", "id": 22},
            {"jsonrpc": "2.0", "id": 23},
            {"jsonrpc": "2.0", "method": "tools/list", "id": True},
            {"jsonrpc": "2.0", "method": "tools/list", "id": 24, "result": marker},
            {"jsonrpc": "2.0", "method": "notifications/initialized", "result": marker},
        ]
        invalid = launch(data / "invalid-protocol")
        invalid_payload = "".join(json.dumps(value, ensure_ascii=False) + "\n" for value in invalid_protocol)
        try:
            invalid_stdout, invalid_stderr = invalid.communicate(invalid_payload, timeout=5)
        except subprocess.TimeoutExpired:
            invalid.kill()
            invalid.communicate(timeout=5)
            raise AssertionError("invalid JSON-RPC left the STDIO server hanging")
        assert invalid.returncode == 0, invalid_stderr
        invalid_frames = [json.loads(line) for line in invalid_stdout.splitlines()]
        assert len(invalid_frames) == len(invalid_protocol), invalid_frames
        assert all(frame["error"]["code"] == -32600 for frame in invalid_frames)
        assert marker not in invalid_stdout and marker not in invalid_stderr

        failed_env = os.environ.copy()
        failed_env.pop("PLUGIN_DATA", None)
        failed = subprocess.run(
            [str(Path(os.environ.get("JAVA_HOME", r"C:\tool\jdk21")) / "bin" / "java.exe"),
             "-Dfile.encoding=UTF-8", "-jar", str(JAR), "--stdio"],
            cwd=ROOT, env=failed_env, input="", capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=30)
        assert failed.returncode != 0 and failed.stdout == ""

    print("MCP STDIO smoke passed")


if __name__ == "__main__":
    main()
