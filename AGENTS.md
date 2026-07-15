# DM7 plugin maintenance

- Treat `https://github.com/yangtaoer/codex-plugin-dm7helper` as the canonical distribution repository.
- Keep the repository marketplace name `dm7-database-local` stable so existing installations can update without changing plugin coordinates.
- Never commit database credentials, connection profiles, runtime state, release exports, local JDBC driver JARs, or integration-test environment values.
- The Dameng JDBC driver remains bring-your-own-driver and must not be added to the plugin package.
- For plugin changes, update `plugins/dm7-database/CHANGELOG.md`, run the frontend and Java test gates, run `plugins/dm7-database/scripts/build.ps1` to produce the final clean runtime, refresh the Codex cachebuster with the `plugin-creator` helper, run `plugins/dm7-database/scripts/refresh-mcp-integrity.ps1`, then run the plugin validator and packaging security checks.
- Publish completed, verified plugin updates to the canonical GitHub repository so `codex plugin marketplace upgrade dm7-database-local` can discover them.
