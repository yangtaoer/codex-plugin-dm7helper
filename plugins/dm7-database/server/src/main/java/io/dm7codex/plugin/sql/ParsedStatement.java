package io.dm7codex.plugin.sql;

import java.util.Objects;

public record ParsedStatement(int index, String originalSql, SqlKind kind, String sha256) {
    public ParsedStatement {
        if (index < 0) throw new IllegalArgumentException("Statement index must not be negative");
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
    }

    public boolean releaseEligibleKind() {
        return kind == SqlKind.DDL || kind == SqlKind.DML;
    }
}
