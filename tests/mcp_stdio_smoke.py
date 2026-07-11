#!/usr/bin/env python3
"""Black-box smoke test for the packaged DM7 MCP STDIO server."""
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "plugins" / "dm7-database" / "lib" / "dm7-codex-plugin.jar"
TOOLS = [
    "dm7_open_console", "dm7_list_connections", "dm7_test_connection", "dm7_query",
    "dm7_execute", "dm7_describe_schema", "dm7_get_execution", "dm7_cancel_execution",
    "dm7_get_release_log", "dm7_release_export",
]


def resolve_java() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        for executable in ("java.exe", "java"):
            candidate = Path(java_home) / "bin" / executable
            if candidate.is_file():
                return str(candidate)
    java = shutil.which("java")
    if java:
        return java
    raise RuntimeError("Java was not found; set JAVA_HOME to a JDK 17+ installation or add java to PATH")


JAVA = resolve_java()


def launch(data: Path) -> subprocess.Popen[str]:
    env = os.environ.copy()
    env.update({"PLUGIN_DATA": str(data), "CODEX_THREAD_ID": "smoke-thread-中文"})
    return subprocess.Popen(
        [JAVA, "-Dfile.encoding=UTF-8", "-jar", str(JAR), "--stdio"],
        cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")


def exchange(process: subprocess.Popen[str], requests: list[dict]) -> tuple[list[dict], str]:
    lines = [(json.dumps(item, ensure_ascii=False, separators=(",", ":")),
              "method" in item and "id" in item) for item in requests]
    return exchange_lines(process, lines)


def exchange_lines(process: subprocess.Popen[str], requests: list[tuple[str, bool]]) -> tuple[list[dict], str]:
    assert process.stdin and process.stdout and process.stderr
    responses: list[str] = []
    with ThreadPoolExecutor(max_workers=1) as executor:
        for payload, expects_response in requests:
            process.stdin.write(payload + "\n")
            process.stdin.flush()
            if not expects_response:
                continue
            pending = executor.submit(process.stdout.readline)
            try:
                response = pending.result(timeout=30)
            except FutureTimeout:
                process.kill()
                pending.result(timeout=5)
                raise AssertionError(f"timed out awaiting MCP responses: {responses!r}")
            assert response, responses
            responses.append(response)
    process.stdin.close()
    process.wait(timeout=30)
    stdout = "".join(responses) + process.stdout.read()
    stderr = process.stderr.read()
    assert process.returncode == 0, (process.returncode, stderr)
    frames = []
    for line in stdout.splitlines():
        assert line.strip(), "stdout contains a blank/non-protocol frame"
        frames.append(json.loads(line))
    assert len(frames) == sum(expected for _, expected in requests), frames
    return frames, stderr


def exchange_pipeline(process: subprocess.Popen[str], initialize: dict,
                      calls: list[dict]) -> tuple[list[dict], str]:
    assert process.stdin and process.stdout and process.stderr
    process.stdin.write(json.dumps(initialize, separators=(",", ":")) + "\n")
    process.stdin.flush()
    first = process.stdout.readline()
    assert first, "pipeline initialize response missing"
    process.stdin.write('{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}\n')
    process.stdin.write("".join(json.dumps(call, separators=(",", ":")) + "\n" for call in calls))
    process.stdin.flush()
    responses: list[str] = []

    def read_pipeline() -> None:
        for _ in calls:
            responses.append(process.stdout.readline())

    with ThreadPoolExecutor(max_workers=1) as executor:
        pending = executor.submit(read_pipeline)
        try:
            pending.result(timeout=30)
        except FutureTimeout:
            process.kill()
            pending.result(timeout=5)
            raise AssertionError(f"pipelined MCP responses timed out: {responses!r}")
    assert all(responses), responses
    process.stdin.close()
    process.wait(timeout=30)
    stderr = process.stderr.read()
    assert process.returncode == 0, (process.returncode, stderr)
    return [json.loads(line) for line in [first, *responses]], stderr


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

        response_process = launch(data / "valid-response")
        response_frames, response_stderr = exchange(response_process, [
            {"jsonrpc": "2.0", "id": 20, "method": "initialize", "params": {
                "protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "response-smoke", "version": "1.0"}}},
            {"jsonrpc": "2.0", "id": "client-response", "result": {"accepted": True}},
        ])
        assert len(response_frames) == 1 and response_frames[0]["id"] == 20
        assert '"code":-32600' not in response_stderr

        pipeline = launch(data / "pipeline")
        pipeline_calls = [{"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                           "params": {"name": "dm7_get_release_log", "arguments": {}}}
                          for request_id in range(31, 35)]
        pipeline_frames, pipeline_stderr = exchange_pipeline(pipeline, {
            "jsonrpc": "2.0", "id": 30, "method": "initialize", "params": {
                "protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "pipeline-smoke", "version": "1.0"}}}, pipeline_calls)
        pipeline_by_id = {frame.get("id"): frame for frame in pipeline_frames}
        assert set(range(30, 35)) == set(pipeline_by_id), pipeline_frames
        assert all(pipeline_by_id[i]["result"]["isError"] is False for i in range(31, 35))
        assert "exception" not in pipeline_stderr.lower(), pipeline_stderr

        malformed_data = data / "malformed"
        malformed_data.mkdir()
        malformed = launch(malformed_data)
        try:
            stdout, malformed_stderr = malformed.communicate("\n   \t  \nnot-json\n", timeout=5)
        except subprocess.TimeoutExpired:
            malformed.kill()
            malformed.communicate(timeout=5)
            raise AssertionError("malformed JSON left the STDIO server hanging")
        assert malformed.returncode == 0, malformed_stderr
        malformed_frames = [json.loads(line) for line in stdout.splitlines()]
        assert len(malformed_frames) == 3
        assert all(frame["error"]["code"] == -32700 for frame in malformed_frames)

        marker = "password=NEVER_ECHO SQL_SECRET_MARKER"
        invalid_protocol = [
            [], None, 7, marker, {},
            {"method": "tools/list", "id": 21},
            {"jsonrpc": "1.0", "method": "tools/list", "id": 22},
            {"jsonrpc": "2.0", "id": 23},
            {"jsonrpc": "2.0", "method": "tools/list", "id": True},
            {"jsonrpc": "2.0", "method": "tools/list", "id": 24, "result": marker},
            {"jsonrpc": "2.0", "method": "notifications/initialized", "result": marker},
            {"jsonrpc": "2.0", "id": "response-null", "result": None},
            {"jsonrpc": "2.0", "id": "response-array", "result": []},
            {"jsonrpc": "2.0", "id": "response-error", "error": {
                "code": 2147483648, "message": marker}},
        ]
        invalid = launch(data / "invalid-protocol")
        invalid_payload = "".join(json.dumps(value, ensure_ascii=False) + "\n"
                                  for value in invalid_protocol)
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

        trailing = launch(data / "trailing-json")
        trailing_values = ('{"jsonrpc":"2.0","method":"first"}'
                           '{"jsonrpc":"2.0","method":"second"}\n')
        trailing_stdout, trailing_stderr = trailing.communicate(trailing_values, timeout=5)
        assert trailing.returncode == 0, trailing_stderr
        trailing_frames = [json.loads(line) for line in trailing_stdout.splitlines()]
        assert len(trailing_frames) == 1
        assert trailing_frames[0]["error"]["code"] == -32700

        invalid_utf8_env = os.environ.copy()
        invalid_utf8_env.update({"PLUGIN_DATA": str(data / "invalid-utf8"),
                                 "CODEX_THREAD_ID": "invalid-utf8"})
        invalid_utf8 = subprocess.Popen(
            [JAVA, "-Dfile.encoding=UTF-8", "-jar", str(JAR), "--stdio"],
            cwd=ROOT, env=invalid_utf8_env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE)
        utf8_stdout, utf8_stderr = invalid_utf8.communicate(
            b'{"jsonrpc":"2.0","id":31,"method":"bad-\xff-password"}\n', timeout=5)
        assert invalid_utf8.returncode == 0, utf8_stderr
        utf8_frames = [json.loads(line) for line in utf8_stdout.decode("utf-8").splitlines()]
        assert len(utf8_frames) == 1 and utf8_frames[0]["error"]["code"] == -32700
        assert b"password" not in utf8_stdout and b"password" not in utf8_stderr

        numeric = launch(data / "wire-numeric")
        numeric_requests = [
            {"jsonrpc": "2.0", "id": 40, "method": "initialize", "params": {
                "protocolVersion": "2025-06-18", "capabilities": {},
                "clientInfo": {"name": "numeric-smoke", "version": "1.0"}}},
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        ]
        numeric_lines = [(json.dumps(numeric_requests[0], separators=(",", ":")), True),
                         (json.dumps(numeric_requests[1], separators=(",", ":")), False),
                         ('{"jsonrpc":"2.0","id":41,"method":"tools/call",'
                          '"params":{"name":"dm7_query","arguments":{"sql":"select ?",'
                          '"parameters":[{"jdbcType":8,"value":1e-9999}]}}}', True)]
        numeric_frames, numeric_stderr = exchange_lines(numeric, numeric_lines)
        numeric_by_id = {frame.get("id"): frame for frame in numeric_frames if "id" in frame}
        numeric_error = numeric_by_id[41]["result"]
        assert numeric_error["isError"] is True
        assert numeric_error["structuredContent"]["code"] == "INVALID_ARGUMENT"
        assert numeric_error["structuredContent"]["reason"] == "UNSAFE_NUMERIC_INPUT"
        assert "1e-9999" not in numeric_stderr.lower()
        numeric_active = list((data / "wire-numeric" / "sessions").glob("*/active.sql"))
        assert len(numeric_active) == 1
        assert not numeric_active[0].read_bytes().startswith(b"\xef\xbb\xbf")
        assert "version: v001" in numeric_active[0].read_text(encoding="utf-8")

        failed_env = os.environ.copy()
        failed_env.pop("PLUGIN_DATA", None)
        failed = subprocess.run(
            [JAVA, "-Dfile.encoding=UTF-8", "-jar", str(JAR), "--stdio"],
            cwd=ROOT, env=failed_env, input="", capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=30)
        assert failed.returncode != 0 and failed.stdout == ""

    print("MCP STDIO smoke passed")


if __name__ == "__main__":
    main()
