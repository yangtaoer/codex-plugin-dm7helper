# User guide

## MCP tools

- `dm7_open_console` opens the authenticated loopback console.
- `dm7_list_connections` lists safe connection metadata.
- `dm7_test_connection` verifies a saved profile.
- `dm7_query` runs read-only SQL with bounded rows and time.
- `dm7_execute` runs DDL/DML after a clear purpose and user confirmation.
- `dm7_describe_schema` lists tables, columns, indexes, and constraints.
- `dm7_get_execution` returns status and results for an execution ID.
- `dm7_cancel_execution` requests cancellation.
- `dm7_get_release_log` previews the current session version.
- `dm7_release_export` confirms, exports UTF-8 SQL, and truncates that session's active log into the next version.

The management console has Connections, SQL Console, Activity, and Release pages. Manual SQL and AI tool calls share execution history, live events, cancellation, and downloadable results. Chinese identifiers and values remain UTF-8 end to end.

For mutations, provide a truthful purpose. Purposes `mock`, `seed`, and `sample` identify test data and are excluded from release SQL. Eligible DDL/DML is logged in execution order; queries are never logged. Export operates only on the current Codex session, does not claim idempotency, and requires preview plus confirmation.

If `dbname=` or `schema=` is wrong, fix the connection rather than qualifying around an unknown default. Keep result limits low for exploratory work.
