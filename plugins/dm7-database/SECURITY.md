# Security

The plugin runs in a dedicated MCP JVM. During `DmDriverLoader` class initialization it disables the JVM-wide default cache for the `jar:` URL protocol. Legacy DM7 JDBC drivers otherwise leave a process-global `JarFile` handle open on Windows after their isolated classloader closes, preventing secure staged-driver deletion. This process-lifetime invariant trades a small amount of repeated JAR resource lookup performance for deterministic driver cleanup; it does not change non-`jar:` protocol cache defaults. A fresh-child-JVM regression verifies ordinary JAR resources remain readable before and after initialization, concurrent driver loads leave no staged files, and the HTTP cache default is unchanged.

The plugin listens only on loopback and opens the console through a reusable local token with the browser-compatible maximum lifetime. Credentials are encrypted in the user-scoped plugin data directory and are never accepted as MCP arguments. Session identifiers are SHA-256 hashed before a private, atomically replaced context file is written.

Use least-privilege database accounts. Review every `dm7_execute` mutation and every release export. Exported SQL can contain business data and should be handled as sensitive. The logger rejects secret-bearing statements and excludes purposes `TEST`, `MOCK`, `SEED`, and `SAMPLE`, including Chinese 测试SQL. A comment or filename never changes classification; the caller must select the truthful purpose.

BYO-driver means users must obtain the DM7 JDBC JAR under its vendor license. Do not commit, embed, or redistribute any JDBC driver. Report suspected vulnerabilities privately to the repository owner and include reproduction steps without passwords, URLs, or production data.

## Threat model

Protected assets are database credentials, the BYO driver, execution metadata, release SQL, exports, and the SQLite state database. Trust boundaries are Codex chat versus the local console, STDIO MCP versus the loopback HTTP server, the user filesystem versus the database, and the plugin versus the user-selected JDBC driver. Controls include no credential MCP fields, loopback binding, encrypted user-scoped secrets, bounded SQL/results, purpose-gated mutation logging, immutable export hashes, private session-context ACLs, and recursive package secret scanning. Console links are intentionally reusable and browser sessions use the maximum supported persistent lifetime for local workstation use.

Out of scope are a compromised local administrator, a malicious or replaced JDBC driver explicitly selected by the user, a compromised database server, and disclosure after a user copies an export elsewhere. Treat `PLUGIN_DATA`, backups, downloads, and driver files as sensitive and verify their access controls and hashes.
