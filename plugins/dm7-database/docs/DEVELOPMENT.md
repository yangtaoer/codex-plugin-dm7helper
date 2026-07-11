# Development

Use JDK 21, Maven 3.9+, Node.js, pnpm, Python 3, and Playwright. Production bytecode targets Java 17. The project never downloads or packages a proprietary driver: DM7 integration testing is opt-in BYO-driver through environment variables, and ordinary tests use isolated fixtures.

Run `scripts/test.ps1` for Python layout/script/web-asset tests, the official plugin validator, Maven tests, TypeScript/Vitest, Playwright E2E, and the MCP STDIO smoke. Run `scripts/build.ps1` for a clean frontend and server build. Both scripts resolve paths from their own location and support repository paths containing spaces or Chinese characters.

Runtime design is local-first: MCP uses STDIO, the console binds loopback, stored JSON/SQL uses UTF-8, credentials use a user-scoped vault, and release logs are session-scoped. Add tests before changing SQL classification, purpose exclusion (`mock`, `seed`, `sample`), export truncation, or connection URL handling (`dbname=` and `schema=`).
