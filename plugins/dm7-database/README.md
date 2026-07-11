# DM7 Database for Codex

`dm7-database` is a shareable, local-first Codex plugin for Dameng 7. It exposes ten MCP tools plus a polished management console for connection settings, live execution, manual SQL, results, session logs, and release export. JSON, browser assets, JDBC text, result downloads, and exported SQL use UTF-8 so Chinese content remains intact.

## Capabilities

- Configure named connections without placing passwords in chat or configuration files.
- Run bounded queries and confirmed mutations, monitor progress, cancel work, and inspect schema metadata.
- Record only eligible DDL/DML in a session-scoped release log. SQL marked with purpose `mock`, `seed`, or `sample` is excluded.
- Export and truncate the current session version under an explicit confirmation flow. No idempotency rewriting is attempted.

See [Installation](docs/INSTALLATION.md), [User guide](docs/USER_GUIDE.md), [Troubleshooting](docs/TROUBLESHOOTING.md), [Security](SECURITY.md), and [Development](docs/DEVELOPMENT.md).

This is a BYO-driver distribution. The Dameng JDBC driver and all credentials are deliberately absent.
