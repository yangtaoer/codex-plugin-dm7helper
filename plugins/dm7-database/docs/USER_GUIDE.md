# User guide

## MCP tools

- `dm7_open_console` opens the authenticated loopback console.
- `dm7_list_connections` lists safe connection metadata.
- `dm7_test_connection` verifies a saved profile.
- `dm7_query` runs read-only SQL with bounded rows and time on the persisted default connection unless another connection ID or name is supplied.
- `dm7_execute` directly runs DDL, DML, DCL, session commands, calls, anonymous blocks, and transaction-control scripts. Omitted purpose defaults to `TEST`, and the SQL console does not show a mutation confirmation dialog.
- `dm7_save_connection`, `dm7_set_default_connection`, and `dm7_delete_connection` manage connection metadata from conversation. New credentials are entered once in the local console and then reused by every Codex conversation.
- `dm7_describe_schema` lists tables, columns, indexes, and constraints.
- `dm7_get_execution` returns status and results for an execution ID.
- `dm7_cancel_execution` requests cancellation.
- `dm7_get_release_log` previews the current session version.
- `dm7_release_export` confirms, exports UTF-8 SQL, and truncates that session's active log into the next version.

The management console has Connections, SQL Console, Activity, and Release pages. Manual SQL and AI tool calls share execution history, live events, cancellation, and downloadable results. Chinese identifiers and values remain UTF-8 end to end.

Mutations default to `TEST`, which is excluded from release SQL. Only provide `PRODUCTION_CHANGE` or `MIGRATION` when release logging is explicitly wanted. Queries are never logged. Export operates only on the current Codex session, does not claim idempotency, and requires preview plus confirmation.

If `dbname=` or `schema=` is wrong, fix the connection rather than qualifying around an unknown default. Keep result limits low for exploratory work.

Query limits default to 1,000 rows, 10 MiB, and 60 seconds. Allowed ranges are 1–10,000 rows, 1 KiB–50 MiB in the console, and 1–3,600 seconds. History pages contain at most 200 records. Result CSV/JSON and release SQL downloads are snapshots; verify the displayed SHA-256 for release artifacts and protect downloaded business data.
