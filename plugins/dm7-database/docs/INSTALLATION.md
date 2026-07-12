# Installation

## Requirements

- Codex Desktop on Windows
- Java 17 or newer on `PATH`, in `JAVA_HOME`, or selected with `DM7_CODEX_JAVA`
- A vendor-supplied DM7 JDBC driver JAR (BYO-driver)

Extract the release ZIP without changing its top-level `dm7-database` directory, then add that directory to a personal Codex marketplace. Do not copy source folders, `node_modules`, or Maven output into the installed plugin.

For this repository marketplace, run `codex plugin marketplace add <repo-root>`, then `codex plugin add dm7-database@dm7-database-local`. For a non-default marketplace, `<repo-root>/.agents/plugins/marketplace.json` must contain the plugin entry and its top-level marketplace name. Do not run `codex plugin marketplace add` for Codex's default personal marketplace; it is discovered automatically.

Open the console with `dm7_open_console`, create a connection, and choose the driver using the local file picker. Use a URL such as `jdbc:dm7://db.example.invalid:5236?dbname=<database>&schema=<schema>`; `dbname=` chooses the database and `schema=` chooses the initial schema. Enter `<username>` and the password only in the console. Test, save, and optionally mark the connection as default.

The driver path and encrypted credential state are local to the current user and are never included when the plugin is shared.

The bundled Windows MCP launcher validates the Java major version before starting the server. If multiple Java installations exist, set `DM7_CODEX_JAVA` to the full `java.exe` path (or `DM7_CODEX_JAVA_HOME` to its JDK/JRE directory); an incompatible legacy `JAVA_HOME` is skipped when a Java 17+ executable is available later on `PATH`.

## Runtime storage

When Codex provides `PLUGIN_DATA`, all writable state is confined to that directory. Current Windows Codex builds expose plugin MCP processes through a restricted sandbox without injecting that variable; in that case the plugin uses the current user's sandbox-writable temporary directory under `dm7-codex-plugin-data`. The same private directory is reused across plugin process restarts, and the plugin applies user-only access controls to sensitive state. Operating-system temporary-directory cleanup can remove this fallback data, so copy required release exports to durable storage after downloading them from the console.

## Update lifecycle

During source development, run the plugin-creator `update_plugin_cachebuster.py <plugin-path>` helper; do not hand-edit `marketplace.json`. Re-run `codex plugin add dm7-database@dm7-database-local`, then start a **new task** so Codex picks up the new cachebuster, skill, and MCP process. Existing tasks can retain the previous plugin process and must not be used as pickup evidence.
