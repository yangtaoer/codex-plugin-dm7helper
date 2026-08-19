# Changelog

## Unreleased

- Rename the user-facing plugin title to `DM7 Database`.
- Make local console redemption URLs reusable and extend both link and browser-session lifetimes to the browser-compatible maximum so ordinary workstation use no longer expires after 60 seconds or 8 hours.
- Fix Windows Codex launches without an injected `PLUGIN_DATA` value by deriving the stable per-plugin data directory from `CODEX_HOME`, so saved connections remain visible after upgrades and in new tasks.
- Add conversational connection metadata tools and allow connection names anywhere an ID was previously required.
- Reuse the persisted default connection automatically across Codex conversations.
- Default `dm7_execute` to `TEST`, make purpose optional, and directly accept DDL, DML, DCL, session commands, calls, anonymous blocks, and transaction control.
- Remove the SQL console mutation acknowledgement dialog and execute changes immediately in test mode.
- Keep passwords in the local vault and retain only technical process limits and credential-bearing SQL protection.
- Allow connection updates to clear an optional Schema without triggering `INVALID_FIELD_TYPE`.
- Keep the SQL workbench and its latest result mounted while navigating between sidebar pages.
- Align result headers and virtualized rows on one resizable grid, including wide multi-column results.
- Refine real-time execution filters with consistent, keyboard-accessible select controls.
- Document GitHub marketplace installation and upgrades while keeping connection state local.

## 0.1.0

- Initial Codex plugin with ten DM7 MCP tools, local management console, UTF-8 results, execution history, session release logging, and deterministic packaging.
