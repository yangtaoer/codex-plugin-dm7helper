# Security

The plugin listens only on loopback and opens the console through a short-lived local token. Credentials are encrypted in the user-scoped plugin data directory and are never accepted as MCP arguments. Session identifiers are SHA-256 hashed before a private, atomically replaced context file is written.

Use least-privilege database accounts. Review every `dm7_execute` mutation and every release export. Exported SQL can contain business data and should be handled as sensitive. The logger rejects secret-bearing statements and excludes purposes `mock`, `seed`, and `sample`; comments are not a security boundary.

BYO-driver means users must obtain the DM7 JDBC JAR under its vendor license. Do not commit, embed, or redistribute any JDBC driver. Report suspected vulnerabilities privately to the repository owner and include reproduction steps without passwords, URLs, or production data.
