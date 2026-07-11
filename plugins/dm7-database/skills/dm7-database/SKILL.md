---
name: dm7-database
description: Use Dameng 7 database tools for safe queries, schema inspection, controlled mutations, local console access, and session-scoped release SQL export.
---

# Dameng 7 Database

- Prefer `dm7_query` for read-only SQL and `dm7_describe_schema` for metadata.
- Open the local management console with `dm7_open_console` for connection setup and manual SQL work.
- Never request or accept database passwords in chat; credentials belong only in the local console.
- Require an explicit mutation purpose before calling `dm7_execute`.
- Use `TEST`, `MOCK`, `SEED`, or `SAMPLE` for all test data and Chinese 测试SQL; these purposes are excluded from release logs. Never disguise test SQL as `PRODUCTION_CHANGE` or `MIGRATION`.
- Explain the active release-log version and ask for confirmation before calling `dm7_release_export`.
- Treat exported release SQL as sensitive and keep it scoped to the current Codex session.
