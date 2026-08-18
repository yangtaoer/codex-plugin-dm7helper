# Installation

## Requirements

- Codex Desktop on Windows
- Java 17 or newer on `PATH`, in `JAVA_HOME`, or selected with `DM7_CODEX_JAVA`
- A vendor-supplied DM7 JDBC driver JAR (BYO-driver)

The canonical distribution source is `yangtaoer/codex-plugin-dm7helper` on GitHub. Install it with:

```powershell
codex plugin marketplace add yangtaoer/codex-plugin-dm7helper
codex plugin add dm7-database@dm7-database-local
```

Start a new Codex task after installation. To update an installed copy after a new version is published, run:

```powershell
codex plugin marketplace upgrade dm7-database-local
codex plugin add dm7-database@dm7-database-local
```

Then start another new task so Codex loads the refreshed skill and MCP process.

For offline installation, extract the release ZIP without changing its top-level `dm7-database` directory, then add that directory to a personal Codex marketplace. Do not copy source folders, `node_modules`, or Maven output into the installed plugin.

For this repository marketplace, run `codex plugin marketplace add <repo-root>`, then `codex plugin add dm7-database@dm7-database-local`. For a non-default marketplace, `<repo-root>/.agents/plugins/marketplace.json` must contain the plugin entry and its top-level marketplace name. Do not run `codex plugin marketplace add` for Codex's default personal marketplace; it is discovered automatically.

Open the console with `dm7_open_console`, create a connection, and choose the driver using the local file picker. Use a URL such as `jdbc:dm7://db.example.invalid:5236?dbname=<database>&schema=<schema>`; `dbname=` chooses the database and `schema=` chooses the initial schema. Enter `<username>` and the password only in the console. Test, save, and optionally mark the connection as default.

The driver path, connection profiles, encrypted credential state, runtime state, and release exports are local to the current user and are never included when the plugin is shared or updated from GitHub.

The bundled Windows MCP launcher validates the Java major version before starting the server. It checks explicit overrides, `JAVA_HOME`, `PATH`, common JDK installation roots, and the Windows Java registry. If multiple Java installations exist, set `DM7_CODEX_JAVA` to the full `java.exe` path (or `DM7_CODEX_JAVA_HOME` to its JDK/JRE directory); an incompatible legacy Java is skipped when a Java 17+ runtime is available.

## Runtime storage

When Codex provides `PLUGIN_DATA`, all writable state is confined to that directory. If current Windows Codex builds do not inject it, the bundled launcher uses the durable canonical-marketplace directory `${CODEX_HOME}\plugins\data\dm7-database-dm7-database-local` (or `%USERPROFILE%\.codex` when `CODEX_HOME` is absent) before Java starts. Connections, encrypted credentials, runtime state, and exports therefore survive MCP restarts, plugin upgrades, temporary-directory cleanup, and new Codex tasks. Direct JAR launches that bypass the bundled launcher retain the temporary `dm7-codex-plugin-data` fallback unless the administrator supplies `PLUGIN_DATA` explicitly.

## Update lifecycle

During source development, run the plugin-creator `update_plugin_cachebuster.py <plugin-path>` helper; do not hand-edit `marketplace.json`. Re-run `codex plugin add dm7-database@dm7-database-local`, then start a **new task** so Codex picks up the new cachebuster, skill, and MCP process. Existing tasks can retain the previous plugin process and must not be used as pickup evidence.
