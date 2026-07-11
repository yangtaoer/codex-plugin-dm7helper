# Development

`DmDriverLoader` deliberately sets the dedicated MCP JVM's `jar:` URL default cache to `false` once at class initialization. Do not change this to a per-load toggle: toggling a JVM-global default around concurrent loads creates a race, while old DM7 drivers can otherwise pin staged JAR files on Windows. The child-JVM driver-loader probe covers concurrent loads, ordinary JAR resource reads, staged-file cleanup, and isolation from non-JAR URL protocols.

Use JDK 21, Maven 3.9+, Node.js, pnpm, Python 3, Playwright, and a separate exact Java 17 runtime configured through `DM7_CODEX_JAVA17_HOME`. Production bytecode targets Java 17 and packaging runs the extracted MCP under that exact runtime. The project never downloads or packages a proprietary driver: DM7 integration testing is opt-in BYO-driver through environment variables, and ordinary tests use isolated fixtures.

Run `scripts/test.ps1` for Python layout/script/web-asset tests, the official plugin validator, Maven tests, TypeScript/Vitest, Playwright E2E, and the MCP STDIO smoke. Run `scripts/build.ps1` for a clean frontend and server build. Both scripts resolve paths from their own location and support repository paths containing spaces or Chinese characters.

Runtime design is local-first: MCP uses STDIO, the console binds loopback, stored JSON/SQL uses UTF-8, credentials use a user-scoped vault, and release logs are session-scoped. Add tests before changing SQL classification, purpose exclusion (`TEST`, `MOCK`, `SEED`, `SAMPLE`), Chinese 测试SQL behavior, export truncation, or connection URL handling (`dbname=` and `schema=`). Regenerate and audit `licenses/dependencies.json` with `scripts/generate-license-inventory.py` whenever runtime dependencies change.
