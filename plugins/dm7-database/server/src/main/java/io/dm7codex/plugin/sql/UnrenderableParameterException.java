package io.dm7codex.plugin.sql;

public final class UnrenderableParameterException extends IllegalArgumentException {
    private final int jdbcType;

    public UnrenderableParameterException(int jdbcType) {
        super("JDBC parameter type cannot be rendered safely");
        this.jdbcType = jdbcType;
    }

    public int jdbcType() {
        return jdbcType;
    }
}
