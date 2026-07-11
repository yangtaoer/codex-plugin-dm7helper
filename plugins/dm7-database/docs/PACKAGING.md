# Packaging

Set `SOURCE_DATE_EPOCH` to a stable Unix timestamp and run `scripts/package.ps1`. The script runs the full test gate, performs two clean builds, compares the runtime JAR SHA-256 values, stages an exact runtime allowlist, scans for integration values and forbidden files, and writes an ordered fixed-timestamp ZIP.

The package excludes source, tests, caches, source maps, environment files, credentials, runtime state, nested archives, and every JAR except `lib/dm7-codex-plugin.jar`. The DM7 driver is always BYO-driver. Verify a release from a newly extracted directory whose path contains spaces and non-ASCII characters.
