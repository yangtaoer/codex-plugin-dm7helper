# Troubleshooting

- **Java not found:** install Java 17+ and set `JAVA_HOME`; restart Codex.
- **Driver not loaded:** select the locally licensed DM7 JDBC JAR. Do not place it inside the plugin directory.
- **Cannot connect:** verify host, port, `dbname=`, `schema=`, account permissions, and network access. Error output redacts credentials.
- **Chinese text is garbled:** keep SQL/export files in UTF-8 and do not pass output through a legacy ANSI terminal.
- **Mutation is absent from release log:** queries are excluded, and purposes `mock`, `seed`, or `sample` intentionally suppress test SQL.
- **Console link expired:** call `dm7_open_console` again; links are short-lived and loopback-only.
- **Export is locked:** allow the active execution/export to finish, then retry. Do not edit state files manually.
