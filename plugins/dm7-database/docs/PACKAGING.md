# Packaging

Set `SOURCE_DATE_EPOCH` and `DM7_CODEX_JAVA17_HOME` to stable, verified values and run `scripts/package.ps1`. The script runs the full test gate, performs two clean builds, compares runtime JAR SHA-256 values, stages an exact runtime allowlist and license bundle, recursively scans decompressed archives for integration values with zip-bomb limits, and writes an ordered fixed-timestamp ZIP. It then extracts to a Chinese-and-space path and runs the official validator, MCP initialize/tool smoke under exact Java 17, Hook/ACL check, and class-version check. Missing Java 17 is a release failure, not a warning.

The package excludes source, tests, caches, source maps, environment files, credentials, runtime state, nested archives, and every JAR except `lib/dm7-codex-plugin.jar`. The DM7 driver is always BYO-driver. Verify a release from a newly extracted directory whose path contains spaces and non-ASCII characters.

Every gate is fail-closed: a nonzero Python, Maven, pnpm, Playwright, validator, scanner, Java 17 smoke, Hook, ACL, or reproducibility result aborts packaging. CI must provide `DM7_CODEX_JAVA17_HOME`; it must never substitute JDK 21 or infer compatibility from class version alone.
