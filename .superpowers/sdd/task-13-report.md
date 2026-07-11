# Task 13 Report: Browser QA, Accessibility, Responsive Polish, and Assets

## Outcome

Delivered a deterministic Playwright browser harness for the complete local DM7 console, fixed every rendered High/Medium issue found, verified responsive light/dark behavior, and produced the two marketplace screenshots referenced by the plugin manifest. No production fixture endpoint, unsafe mode, real credential, driver path, or DM7 dependency was introduced.

## Browser fixture and journeys

The fixture serves the production Vite build and intercepts same-origin `/api/**` with an explicit state machine. Safe connection responses are constructed field-by-field; request-only `password`, `clearPassword`, and `driverJar` are never spread into state or responses. Every JSON response is recursively checked for forbidden secret keys. Every journey also fails on unexpected page errors, console errors, failed requests, or external network traffic.

The final Chromium suite contains 28 journeys across the five acceptance specs plus `design-evidence.spec.ts`. The complete mapping is recorded in `.gstack/qa-reports/task-13/qa-report.md`; it includes six routes/four viewports, long/selected SQL, 250-row results, explicit queued/executing/completed/cancelled named-event transitions, cancellation race, full connection CRUD/password/default/error states, history field semantics plus UUID validation and pagination/deduplication, exact release bytes/SHA/recovery failures, keyboard/a11y, and visual evidence.

## Rendered audit

All six routes were rendered with their route-specific heading and active navigation state at 390×844, 768×900, 1280×800, and 1440×900. The 24 route/viewport checks found no document-level horizontal overflow. Light and dark representative connection views pass Axe WCAG 2 A/AA and 2.1 A/AA with zero violations. No unexpected browser error or external request remained.

The Codex in-app browser backend was unavailable in this desktop host. The mandatory runtime setup was attempted; after selection failed, `bootstrap-troubleshooting` was read and `browsers.list()` returned `[]`. The audit therefore used the pinned local Playwright Chromium for real rendering, keyboard inspection, screenshots, and automated accessibility evidence.

The Vite fixture does not verify packaged Java CSP or single-use token redemption. Those claims are supported only by the Java `ConsoleHttpServerTest`, `ConsoleTokenServiceTest`, `HttpSecurityTest`, and packaged smoke, and are kept separate from browser-fixture evidence.

## Issue log

| ID | Severity | Before | After | Commit |
|---|---|---|---|---|
| FINDING-001 | Medium | Light connection action contrast 4.32:1 | AA clean with `--muted: #59635d` | `1285919` |
| FINDING-002 | Medium | Connection action target 36px | 44px, rendered geometry asserted | `42cd7ee` |
| FINDING-003 | High | Dark success badge contrast 3.42:1 | AA clean with dark `--success: #43b894` | `6f11e84` |
| FINDING-004 | Medium | 900px marketplace viewport hid result/artifact evidence below the fold | Compact-height layout keeps the Chinese row and immutable artifact in view | `ea17895` |

Rubric-calculated scores: QA health 80→98, design 82→97, AI-slop blacklist count 1→0. No Critical/High/Medium issue is deferred. Before/after PNGs for all four findings are stored under `.gstack/design-audits/task-13/screenshots/`; FINDING-004 uses 1440×900 in both images.

## Assets

- `assets/screenshot-console.png`: 1440×900, 67,916 bytes, SHA-256 `A66AB5EB1262061B67B23365392142E3473A015E1CAAA26D4D210FDA5EDB53F9`.
- `assets/screenshot-release.png`: 1440×900, 85,625 bytes, SHA-256 `53933184B5BE5871A8CAABB33117CD37F1D5AA298DB649617641D074472F8922`.

Both are deterministic demonstration-state PNGs without browser chrome, debug overlays, secrets, absolute paths, or external content. `interface.screenshots` references both files. `web_assets_test.py` validates the references, PNG signature, exact dimensions, and size bound.

## Verification

- Frontend check: TypeScript clean; 11 Vitest files, 103/103 tests.
- Browser: standard `pnpm e2e` owns `tsc -b && vite build`; from a deleted `dist` it rebuilt production assets and passed 28/28, followed by another clean 28/28 run. An intentional TypeScript probe proved build failure stops E2E before Playwright starts.
- Backend: JDK 21 clean package targeting Java 17, 342/342 tests.
- Python: web assets 4/4, plugin layout 1/1; MCP STDIO smoke passed under JDK 21.
- Plugin validator and `git diff --check`: passed.
- Boolean-only exact secret/machine-target scan and proprietary-driver scan: passed; generated runtime JAR and browser test-results were removed.

Expected/known output was not hidden: the first clean-dist RED failed because preview had no `dist`; the intentional TypeScript probe also failed as designed and proved build gating. Maven completed successfully with its existing shade warnings for overlapping license/manifest/module-info resources and the existing deprecated MCP schema API notice. The Windows console rendered the Chinese workspace path with mojibake in Maven/Vitest logs, but application/browser Chinese assertions and screenshots remained correct UTF-8.
