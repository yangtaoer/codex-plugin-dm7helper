# Task 13 Report: Browser QA, Accessibility, Responsive Polish, and Assets

## Outcome

Delivered a deterministic Playwright browser harness for the complete local DM7 console, fixed every rendered High/Medium issue found, verified responsive light/dark behavior, and produced the two marketplace screenshots referenced by the plugin manifest. No production fixture endpoint, unsafe mode, real credential, driver path, or DM7 dependency was introduced.

## Browser fixture and journeys

The fixture serves the production Vite build and intercepts same-origin `/api/**` with mutable, schema-shaped state. It covers runtime/session/default connection, connection CRUD/test/diagnostics/password non-echo, SQL classification/query/mutation/known execution UUID/cancellation, history/detail, release preview/export rotation/artifact download, and named EventSource-compatible startup. It is an E2E-only fixture and does not branch production code.

The final Chromium suite contains 14 journeys across `console.spec.ts`, `connections.spec.ts`, `activity-release.spec.ts`, `accessibility.spec.ts`, and `visual-assets.spec.ts`. It proves History navigation/reload, all six destinations, Chinese query rendering, purpose-gated mutation, keyboard execution, CSV UTF-8 BOM and JSON no-BOM bytes, no-default/session-expiry handling, connection password non-echo, URL warning without rewrite, activity detail and cancellation reconciliation, authoritative v001→v002 export/download, recovery/conflict states, light/dark WCAG scans, skip link, dialog focus return, visible focus, reduced motion, 44px connection actions, and screenshot production.

## Rendered audit

All six routes were rendered at 390×844, 768×900, 1280×800, and 1440×900. The 24 route/viewport checks found no document-level horizontal overflow. Light and dark representative connection views pass Axe WCAG 2 A/AA and 2.1 A/AA with zero violations. No console error, blocked application resource, broken link, or external application request remained.

The Codex in-app browser backend was unavailable in this desktop host. The mandatory runtime setup was attempted; after selection failed, `bootstrap-troubleshooting` was read and `browsers.list()` returned `[]`. The audit therefore used the pinned local Playwright Chromium for real rendering, keyboard inspection, screenshots, and automated accessibility evidence.

## Issue log

| ID | Severity | Before | After | Commit |
|---|---|---|---|---|
| FINDING-001 | Medium | Light connection action contrast 4.32:1 | AA clean with `--muted: #59635d` | `1285919` |
| FINDING-002 | Medium | Connection action target 36px | 44px, rendered geometry asserted | `42cd7ee` |
| FINDING-003 | High | Dark success badge contrast 3.42:1 | AA clean with dark `--success: #43b894` | `6f11e84` |
| FINDING-004 | Medium | 900px marketplace viewport hid result/artifact evidence below the fold | Compact-height layout keeps the Chinese row and immutable artifact in view | `ea17895` |

Baseline/final scores: QA health 86→97, design 82→95, AI slop 1→0. No Critical/High/Medium issue is deferred.

## Assets

- `assets/screenshot-console.png`: 1440×900, 67,916 bytes, SHA-256 `A66AB5EB1262061B67B23365392142E3473A015E1CAAA26D4D210FDA5EDB53F9`.
- `assets/screenshot-release.png`: 1440×900, 82,594 bytes, SHA-256 `C2577C2292E0592DA6E78B81BF94E64498CE231FA9895DC22F9D9CD7623A44D3`.

Both are deterministic demonstration-state PNGs without browser chrome, debug overlays, secrets, absolute paths, or external content. `interface.screenshots` references both files. `web_assets_test.py` validates the references, PNG signature, exact dimensions, and size bound.

## Verification

- Frontend check: TypeScript clean; 11 Vitest files, 103/103 tests.
- Browser: two consecutive full runs, 14/14 and 14/14; screenshot-only run repeated with byte-identical hashes.
- Backend: JDK 21 clean package targeting Java 17, 342/342 tests.
- Python: web assets 4/4, plugin layout 1/1; MCP STDIO smoke passed under JDK 21.
- Plugin validator and `git diff --check`: passed.
- Boolean-only exact secret/machine-target scan and proprietary-driver scan: passed; generated runtime JAR and browser test-results were removed.
