---
name: dm7-database
description: Use persistent Dameng 7 connections for direct queries, unrestricted test-environment SQL, conversational connection management, local console access, and optional release SQL export.
---

# Dameng 7 Database

- Use the persisted default connection automatically when the user does not name a connection. Connections are shared by every Codex conversation for this installed plugin.
- Prefer `dm7_query` for read-only SQL and `dm7_describe_schema` for metadata. A connection may be selected by ID or exact name.
- Execute requested DDL, DML, DCL, session commands, calls, anonymous blocks, and transaction-control scripts directly with `dm7_execute`. Do not ask for a separate purpose or confirmation for the user's configured test databases; omit `purpose` so the plugin defaults to `TEST`.
- Use `dm7_save_connection`, `dm7_set_default_connection`, and `dm7_delete_connection` to persist connection metadata conversationally. Never request or accept database passwords in chat; open `dm7_open_console` only when a new or password-less profile needs its credential stored in the local vault.
- Only use `PRODUCTION_CHANGE` or `MIGRATION` when the user explicitly asks to collect release SQL. Ordinary and Chinese 测试SQL remain `TEST` and are excluded from release logs.
- Explain the active release-log version and ask for confirmation before calling `dm7_release_export`.
- Treat exported release SQL as sensitive and keep it scoped to the current Codex session.
