# Task 6 Report: DM7 Execution, History, and Metadata

## Delivered

- Added the locked `execution` package: validated command/result/event models, query and mutation execution, metadata reads, bounded event replay, bounded execution queue, cancellation registry, and safety exceptions.
- Query accepts exactly one `QUERY` or `EXPLAIN` before opening a connection. Effective row, byte, and timeout limits are the minimum of command, profile, and hard limits. JDBC receives timeout, max rows plus one, and bounded fetch size.
- Result mapping preserves Java Unicode, deterministically suffixes duplicate labels, counts UTF-8 bytes without splitting surrogate pairs, streams CLOB/NCLOB/BLOB values within the byte budget, and marks truncation. Binary values use an explicit `base64:` representation.
- Mutation validation rejects query/explain/transaction statements, secret-bearing SQL, tracked anonymous/dynamic blocks, and every non-DML atomic script before opening a connection. Anonymous blocks are limited by the `SqlPurpose` enum to explicit non-release purposes and expose an exclusion reason.
- Atomic DML holds one release reservation across execution, commit/rollback, and release logging. It logs only after commit and logs nothing after rollback. Non-atomic successes are logged immediately; DDL is reported as `database_managed`, so a later failure does not erase it.
- Release operation IDs are derived from execution UUID plus parsed statement index. This is stable for a retry of the same execution/statement, collision-free within a script, and distinct across executions of identical SQL.
- Release fingerprint reservation occurs after connection fingerprinting and before the first statement. Cross-database tracked execution is rejected before statement execution.
- Events are session-isolated, bounded, and monotonically sequenced. Applicable mutation phases use `QUEUED -> CONNECTING -> PARSING -> EXECUTING -> COMMITTING -> LOGGING -> terminal`; rollback omits inapplicable commit/log phases. Queue rejection is explicit and visible.
- Cancellation is race-safe before and after resource attachment. It calls `Statement.cancel()` immediately, force-closes the statement and connection after a two-second grace period, and is idempotent while registered.
- Execution history supports started/progress/statement aggregate/terminal writes and parameterized filtered paging without reusing release `statement_event` rows. Secret SQL is rejected before any history write. External errors retain only safe message, SQLState, vendor code, correlation, phase, and restart-required state.
- Metadata uses JDBC `DatabaseMetaData` with parameterized `ALL_TABLES` and `ALL_TAB_COLUMNS` fallback, validated patterns, offset, and maximum page size 200. JDBC and fallback resources are closed on all paths.

## Explicit durability boundary

No business-database outbox was added. A process crash between database commit and local release logging can leave execution history at `LOGGING`; the execution/correlation identifiers support manual reconciliation. The implementation does not claim to eliminate this cross-resource crash window.

## TDD evidence

The five required test classes contain 29 tests covering validation before connection, transaction rollback and independent DDL commit behavior, duplicate SQL executions, cross-database preflight, Unicode and large-value limits, profile clamps, event order and isolation, bounded queue rejection, cancellation races/grace close, close-time restart propagation and redaction, sanitized history, secret non-persistence, and metadata fallback.

Verification commands and final results are recorded in the task handoff after the final clean run.
