# Licensing

The plugin manifest and root `LICENSE` are Apache-2.0. `THIRD_PARTY_NOTICES.md` points to the deterministic `licenses/dependencies.json` inventory and per-component texts under `licenses/components`. That bundle is generated from Maven POM/JAR/source metadata and installed pnpm production-package license files; it includes all distinct Jackson NOTICE resources, sqlite-jdbc's BSD-2-Clause Zentus terms, Lucide's ISC and Feather MIT terms, and SQLite's documented `LicenseRef-SQLite-Public-Domain` with official provenance.

Run `scripts/generate-license-inventory.py` after every runtime dependency change, then run the license coverage tests. Do not edit generated component files to guess or normalize a license. Review new or changed terms before release.

The proprietary DM7 JDBC driver is BYO-driver, is never packaged, and remains governed solely by the user's vendor agreement.
