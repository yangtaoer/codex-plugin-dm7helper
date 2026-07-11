# Installation

## Requirements

- Codex Desktop on Windows
- Java 17 or newer on `PATH` or in `JAVA_HOME`
- A vendor-supplied DM7 JDBC driver JAR (BYO-driver)

Extract the release ZIP without changing its top-level `dm7-database` directory, then add that directory to a personal Codex marketplace. Do not copy source folders, `node_modules`, or Maven output into the installed plugin.

Open the console with `dm7_open_console`, create a connection, and choose the driver using the local file picker. Use a URL such as `jdbc:dm7://db.example.invalid:5236?dbname=<database>&schema=<schema>`; `dbname=` chooses the database and `schema=` chooses the initial schema. Enter `<username>` and the password only in the console. Test, save, and optionally mark the connection as default.

The driver path and encrypted credential state are local to the current user and are never included when the plugin is shared.
