# DM7 Database for Codex

`dm7-database` is a shareable, local-first Codex plugin for Dameng 7. It exposes thirteen MCP tools plus a polished management console for persistent connection settings, direct SQL, live execution, results, session logs, and optional release export. JSON, browser assets, JDBC text, result downloads, and exported SQL use UTF-8 so Chinese content remains intact.

## Capabilities

- Persist named connection metadata from conversation and reuse the default connection in every Codex conversation. Passwords remain in the local vault and are entered once through the console.
- Reopen the same local console URL without a short redemption window; console links and browser sessions use the browser-compatible maximum lifetime while the owning MCP process is running.
- Run queries and requested test-environment mutations directly without a purpose or acknowledgement dialog, monitor progress, cancel work, and inspect schema metadata.
- Execute DDL, DML, DCL, session commands, calls, anonymous blocks, and transaction-control scripts. Omitted mutation purpose defaults to `TEST`.
- Record only eligible DDL/DML in a session-scoped release log. Purposes `TEST`, `MOCK`, `SEED`, and `SAMPLE`—including Chinese 测试SQL—are excluded; only `PRODUCTION_CHANGE` and `MIGRATION` are release-eligible.
- Export and truncate the current session version under an explicit confirmation flow. No idempotency rewriting is attempted.

See [Installation](docs/INSTALLATION.md), [Administration](docs/ADMINISTRATION.md), [User guide](docs/USER_GUIDE.md), [Troubleshooting](docs/TROUBLESHOOTING.md), [Security](SECURITY.md), [Licensing](docs/LICENSING.md), and [Development](docs/DEVELOPMENT.md).

This is a BYO-driver distribution. The Dameng JDBC driver and all credentials are deliberately absent.
