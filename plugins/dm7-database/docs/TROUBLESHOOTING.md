# Troubleshooting

- **Java not found:** install Java 17+ and set `JAVA_HOME`; restart Codex.
- **Driver not loaded:** select the locally licensed DM7 JDBC JAR. Do not place it inside the plugin directory.
- **Cannot connect:** verify host, port, `dbname=`, `schema=`, account permissions, and network access. Error output redacts credentials.
- **Chinese text is garbled:** keep SQL/export files in UTF-8 and do not pass output through a legacy ANSI terminal.
- **Mutation is absent from release log:** queries are excluded; `TEST`, `MOCK`, `SEED`, and `SAMPLE` intentionally suppress all test data and Chinese 测试SQL.
- **Console process stopped:** call `dm7_open_console` again. Links are reusable and effectively non-expiring while their local MCP process remains alive, but a stopped or upgraded process must issue a new loopback URL.
- **Export is locked:** allow the active execution/export to finish, then retry. Do not edit state files manually.
- **Recovery is required:** for a `RECOVERABLE` `SEALED`/`RECOVERY_REQUIRED` artifact, use **恢复导出** and re-check SHA-256. `MISSING`, `TAMPERED`, or `UNAVAILABLE` artifacts are not recoverable; restore a trusted stopped-state backup or retain them for investigation. Credential state `RECOVERY_REQUIRED` or `UNCERTAIN` requires retesting/re-entering the password in the console—never edit vault files.
