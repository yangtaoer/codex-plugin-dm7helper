# DM7 Console QA Report — Task 13

Date: 2026-07-11

Branch: `codex/dm7-database-plugin`

Classifier: APP UI

## Coverage

- Six History-API routes: overview, SQL, activity, release, connections, settings.
- Viewports: 390×844, 768×900, 1280×800, 1440×900.
- Light and dark themes; reduced motion; keyboard-only skip link/dialog flows.
- Stateful synthetic same-origin API covering connection, query, mutation, history, cancellation, release rotation and downloads.
- Chromium console/network audit: no unexpected console errors, broken requests, external requests, or CSP resource failures.

## Findings

| ID | Severity | Finding | Resolution | Commit |
|---|---|---|---|---|
| FINDING-001 | Medium | Light connection action text was 4.32:1, below AA. | Raised muted action contrast; Axe light scan clean. | `1285919` |
| FINDING-002 | Medium | Connection card action targets were 36px high. | Restored 44px minimum height and asserted rendered geometry. | `42cd7ee` |
| FINDING-003 | High | Dark success badges inherited the light success color and rendered at 3.42:1. | Added dark success token; Axe dark scan clean. | `6f11e84` |
| FINDING-004 | Medium | At 1440×900 the SQL result and release artifact evidence fell below the fold. | Added a compact-height desktop rhythm; viewport assertions prove Chinese results and artifact facts remain visible. | `ea17895` |

No Critical, High, or Medium findings remain open.

## Health

- Baseline QA health: 86/100.
- Final QA health: 97/100.
- E2E: 14 deterministic Chromium journeys passed in two consecutive full runs.
- Automated WCAG A/AA: zero violations on representative light and dark connection views.
- Document overflow: zero failures across 24 route/viewport combinations.

## Environment note

The Codex in-app browser backend was not present in this host session. Bootstrap troubleshooting was followed and browser discovery returned an empty list. Real Chromium rendering, screenshots, keyboard dogfooding, and accessibility inspection were completed through the repository's pinned Playwright runtime.
