package io.dm7codex.plugin.execution;

/** A typed JDBC positional parameter. The value is never included in diagnostic errors. */
public record SqlParameter(Object value, int jdbcType) {
    @Override public Object value() {
        return value instanceof byte[] bytes ? bytes.clone() : value;
    }
    public SqlParameter {
        if (value instanceof byte[] bytes) value = bytes.clone();
    }
}
