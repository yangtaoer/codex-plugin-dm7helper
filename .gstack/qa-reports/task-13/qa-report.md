# DM7 Console QA Report — Task 13

Date: 2026-07-11

Branch: `codex/dm7-database-plugin`

Classifier: APP UI

## Coverage

- Six History-API routes: overview, SQL, activity, release, connections, settings.
- Viewports: 390×844, 768×900, 1280×800, 1440×900.
- Light and dark themes; reduced motion; keyboard-only skip link/dialog flows.
- Stateful synthetic same-origin API covering connection, query, mutation, history, cancellation, release rotation and downloads.
- Chromium guard: every journey fails on unexpected `pageerror`, console error, failed request, or non-loopback/data/blob request. Expected 401/404/409/422/503 responses are allowlisted per test and remain asserted in the UI.

## Findings

| ID | Severity | Finding | Resolution | Commit |
|---|---|---|---|---|
| FINDING-001 | Medium | Light connection action text was 4.32:1, below AA. | Raised muted action contrast; Axe light scan clean. | `1285919` |
| FINDING-002 | Medium | Connection card action targets were 36px high. | Restored 44px minimum height and asserted rendered geometry. | `42cd7ee` |
| FINDING-003 | High | Dark success badges inherited the light success color and rendered at 3.42:1. | Added dark success token; Axe dark scan clean. | `6f11e84` |
| FINDING-004 | Medium | At 1440×900 the SQL result and release artifact evidence fell below the fold. | Added a compact-height desktop rhythm; viewport assertions prove Chinese results and artifact facts remain visible. | `ea17895` |

No Critical, High, or Medium findings remain open.

## Coverage to test mapping

| Evidence area | Playwright evidence |
|---|---|
| Shell, six routes, back/forward/reload, 4 viewports | `console.spec.ts` — route journey and 24 route/viewport assertions |
| Query, mutation, selection, long SQL/result, CSV/JSON, queued/executing/completed/cancelled events, cancel race | `console.spec.ts` — SQL journeys |
| Connection CRUD/default/delete, password three-state, diagnostics, 409/422 | `connections.spec.ts` |
| History status/purpose/recorded/success/kind/correlation/date semantics, 51-row pagination/dedupe, detail, cancellation | `activity-release.spec.ts` |
| Release counts/entries, exact bytes/SHA, rotation, recovery/conflict/tampered/missing | `activity-release.spec.ts` |
| Keyboard, tabs, focus loop/return, reduced motion, light/dark Axe | `accessibility.spec.ts` |
| Marketplace assets and before/after finding evidence | `visual-assets.spec.ts`, `design-evidence.spec.ts` |

## Health rubric

| Category | Weight | Baseline | Final |
|---|---:|---:|---:|
| Functional journey depth | 30 | 23 | 30 |
| Responsive/content integrity | 20 | 18 | 20 |
| Accessibility/keyboard | 20 | 14 | 20 |
| Browser hygiene and fixture security | 20 | 16 | 18 |
| Assets and reproducible evidence | 10 | 9 | 10 |
| **Total** | **100** | **80** | **98** |

Two browser-hygiene points remain unavailable because this host exposed no in-app browser; the pinned Playwright Chromium is authoritative for repository E2E but is not represented as in-app-browser evidence.

- E2E: 28 deterministic Chromium journeys, including evidence capture, must pass in two consecutive full runs.
- Automated WCAG A/AA: zero violations on representative light and dark connection views.
- Document overflow: zero failures across 24 route/viewport combinations.

## Environment note

The Codex in-app browser backend was not present in this host session. Bootstrap troubleshooting was followed and browser discovery returned an empty list. Real Chromium rendering, screenshots, keyboard dogfooding, and accessibility inspection were completed through the repository's pinned Playwright runtime.

The Vite fixture does not claim to verify packaged Java token redemption or CSP headers. Those are covered separately by `ConsoleTokenServiceTest`, `ConsoleHttpServerTest`, `HttpSecurityTest`, and the packaged MCP/HTTP smoke evidence.
