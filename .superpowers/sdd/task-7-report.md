# Task 7 Report: MCP Server and Ten Tool Contracts

## Delivered

- Added `AppMain` with a single `--stdio` protocol mode, SDK 2.0.0 `StdioServerTransportProvider`, `McpServer.sync`, server info `dm7-database/0.1.0`, tool capabilities, EOF-aware graceful shutdown, stderr-only diagnostics, and non-zero safe startup failures.
- Added exact definitions and handlers for all ten required DM7 tools. Input schemas declare JSON Schema 2020-12, reject additional properties, document required/default/min/max/enum constraints, and carry behavior-accurate safety annotations.
- Every handler resolves the process-trusted `SessionIdentity` and calls `SessionInitializer.initialize(identity)` before argument validation or backend work. Tool arguments cannot override session identity.
- Added the real service adapter wiring runtime paths, SQLite state, session initialization, encrypted vault/config, driver factory, parser/security, execution/metadata/history/registry, and release inspect/export services.
- Expected validation, configuration, connection, SQL, lookup, cancellation, console, and export failures return safe `CallToolResult` values with `isError=true`, structured error code/correlation ID, and concise Chinese UTF-8 text.
- `dm7_open_console` returns `CONSOLE_NOT_AVAILABLE` through an injected launcher contract until Task 8 supplies the backend.
- Connection listing exposes only ID, name, default, optional schema, and redacted URL summary. Execution history is session-scoped and omits stored SQL text. Cancellation is also session-scoped.
- Release export requires explicit `confirm=true`; successful output includes the design-required absolute artifact path only after the release service has constrained it beneath `PLUGIN_DATA/exports`.
- Added a black-box newline-framed STDIO client covering initialization, notification, tool discovery, release-log and business-error calls, protocol-error behavior, clean stdout frames, session file creation, BOM-free UTF-8, EOF exit, and startup failure behavior.

## TDD Evidence

1. `Dm7McpServerTest` initially failed compilation because the MCP server did not exist.
2. The first implementation exposed an unordered copied map; the exact tool-order assertion failed and drove the immutable `LinkedHashMap` correction.
3. `Dm7ServicesBackendTest` initially failed compilation because the real service adapter did not exist.
4. Real release inspection then failed because `Map.copyOf` rejected legitimate null sequence fields; the test drove a null-tolerant immutable structured response.
5. Real release export failed with `INVALID_ARGUMENT` because generic Jackson conversion could not safely convert `Path`/`Instant`; the test drove an explicit safe export DTO.
6. Initial STDIO startup failed because `AppMain` was absent. After wiring, the first client closed stdin too early and proved the SDK closes in-flight work on EOF; the client now reads all expected responses before testing EOF shutdown.

## Verification Evidence

- Java runtime: Maven 3.9.16 on Oracle Java 21.0.11; compiler release is 17.
- Fresh targeted test after `clean`: 15 tests, 0 failures (13 MCP contract cases, including ten independent initializer-first cases; 2 real backend/session cases).
- Full Maven package: 253 tests, 0 failures; shaded `plugins/dm7-database/lib/dm7-codex-plugin.jar` generated.
- Black-box: `python tests/mcp_stdio_smoke.py` passed.
- Plugin layout: `python tests/plugin_layout_test.py` passed.
- Bytecode: `AppMain` major version 61 (Java 17).
- `git diff --check` passed.

The MCP SDK reports a compile-time deprecation note for legacy annotation fields represented by the protocol's `ToolAnnotations` record; the required SDK 2.0.0 API and emitted MCP annotation fields remain correct.

## Independent Review Hardening

The five Important review findings were remediated with additional RED/GREEN cycles:

1. A real `not-json` black-box frame reproduced the permanent STDIO hang within a five-second timeout. A UTF-8 line protocol guard now emits JSON-RPC `-32700 Parse error`, continues to EOF, and permits prompt graceful exit without polluting valid SDK traffic.
2. Query and execute failures no longer collapse into generic adapter exceptions. Their structured results retain execution ID, success/status, the execution correlation ID, phase, safe message, SQLState, database error code, restart flag, and every statement result while MCP correctly sets `isError=true`.
3. All ten handlers now call the SDK 2.0 JSON Schema validator after session initialization and before business work. Ten independent unit cases and ten packaged STDIO calls cover additional properties/forged session IDs, missing fields, wrong types, enum, numeric range, and array shape.
4. MCP parameters are typed `{jdbcType,value}` entries with strict item schemas and supported-type parsing. `ExecutionService` uses `PreparedStatement` binding. Tracked DML independently renders replayable SQL through `DmLiteralRenderer`; tests cover Chinese text, NULL, integral/decimal/boolean, date/time/timestamp, binary, quoted/comment question marks, count mismatch, unsupported types, and safe error boundaries.
5. Query and execute accept an optional caller-generated execution UUID. The service registers and publishes that known ID before JDBC work, allowing another concurrent call to cancel it while the original synchronous call is running. A race test cancels a blocked atomic mutation by the caller-known ID and verifies `CANCELLED` plus rollback. `dm7_get_execution` now includes filtered phase-event history.

The compatibility regression where legacy command objects reused a generated ID was caught by the full mutation suite. Only explicit caller IDs remain stable; legacy constructors retain per-invocation IDs.

Fresh post-review verification used Maven 3.9.16 with `JAVA_HOME=C:\tool\jdk21`: `clean package` ran 272 tests with zero failures, the enhanced packaged STDIO smoke passed, plugin layout passed, bytecode remained Java 17 (major 61), and `git diff --check` passed.

Per the approved implementation-plan packaging boundary, the generated `lib/dm7-codex-plugin.jar` is exercised by this task's package and smoke verification but is not committed here. The final packaging task remains responsible for committing the distribution artifact, preventing a stale intermediate binary from entering source control.
