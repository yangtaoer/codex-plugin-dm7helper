# Administration

`PLUGIN_DATA` is mandatory and is the only runtime-state root. Codex normally injects it; administrators may override it before process launch with `PLUGIN_DATA=<absolute-user-private-path>`. Never point it inside the plugin installation or a shared repository.

Exact children are `${PLUGIN_DATA}\config`, `${PLUGIN_DATA}\secrets`, `${PLUGIN_DATA}\cache\jdbc-drivers`, `${PLUGIN_DATA}\state\plugin.db`, `${PLUGIN_DATA}\session-context`, `${PLUGIN_DATA}\sessions`, `${PLUGIN_DATA}\exports`, and `${PLUGIN_DATA}\logs`. Protect the whole root as sensitive: `secrets` contains encrypted material, `state` contains operational metadata, and exports/logs can contain business SQL.

## Backup and restore

Stop all plugin MCP/console processes before backup. Copy the entire `PLUGIN_DATA` tree atomically to encrypted storage and record its filesystem ACL and hashes; partial live copies are unsupported. To restore, stop the plugin, preserve the damaged tree for investigation, restore the complete tree to the same user identity and ACL, then start Codex and test connections without executing mutations. Never merge individual vault or SQLite files.

Release artifacts move through `SEALED`, `RECOVERY_REQUIRED`, and `COMPLETE`. Supported recovery actions are deliberately narrow: `RECOVERABLE` permits the console **恢复导出** action; verify the resulting SHA-256 before download. `MISSING`, `TAMPERED`, and `UNAVAILABLE` require a trusted full backup or incident investigation and must not be force-recovered. Credential states `RECOVERY_REQUIRED` and `UNCERTAIN` require console-guided credential replacement and connection testing.

Downloads are immutable snapshots, not backups. Store exported SQL and result files under your organization's data-handling policy. Rotate logs/backups outside active `PLUGIN_DATA`; do not delete or edit active session/version files to save space.
