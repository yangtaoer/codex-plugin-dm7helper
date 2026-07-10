package io.dm7codex.plugin.sql;

public enum SqlKind {
    QUERY,
    EXPLAIN,
    DDL,
    DML,
    DCL,
    TRANSACTION,
    SESSION,
    CALL,
    ANONYMOUS_BLOCK,
    UNKNOWN
}
