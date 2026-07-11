package io.dm7codex.plugin.sql;

import io.dm7codex.plugin.execution.SqlParameter;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class SqlParameterBindings {
    private final DmLiteralRenderer renderer;

    public SqlParameterBindings(DmLiteralRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer);
    }

    public String render(String sql, List<SqlParameter> parameters) {
        Objects.requireNonNull(sql); parameters = List.copyOf(parameters);
        var output = new StringBuilder(sql.length());
        int parameter = 0;
        State state = State.NORMAL;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                if (current == '\'' ) state = State.SINGLE;
                else if (current == '"') state = State.DOUBLE;
                else if (current == '-' && next == '-') state = State.LINE_COMMENT;
                else if (current == '/' && next == '*') state = State.BLOCK_COMMENT;
                else if (current == '?') {
                    if (parameter >= parameters.size()) throw mismatch();
                    var value = parameters.get(parameter++);
                    output.append(renderer.render(value.value(), value.jdbcType()));
                    continue;
                }
            } else if (state == State.SINGLE && current == '\'') {
                if (next == '\'') { output.append(current).append(next); index++; continue; }
                state = State.NORMAL;
            } else if (state == State.DOUBLE && current == '"') {
                if (next == '"') { output.append(current).append(next); index++; continue; }
                state = State.NORMAL;
            } else if (state == State.LINE_COMMENT && (current == '\n' || current == '\r')) {
                state = State.NORMAL;
            } else if (state == State.BLOCK_COMMENT && current == '*' && next == '/') {
                output.append(current).append(next); index++; state = State.NORMAL; continue;
            }
            output.append(current);
        }
        if (parameter != parameters.size()) throw mismatch();
        return output.toString();
    }

    public int placeholderCount(String sql) {
        Objects.requireNonNull(sql);
        int count = 0;
        State state = State.NORMAL;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                if (current == '\'') state = State.SINGLE;
                else if (current == '"') state = State.DOUBLE;
                else if (current == '-' && next == '-') state = State.LINE_COMMENT;
                else if (current == '/' && next == '*') state = State.BLOCK_COMMENT;
                else if (current == '?') count++;
            } else if (state == State.SINGLE && current == '\'') {
                if (next == '\'') index++; else state = State.NORMAL;
            } else if (state == State.DOUBLE && current == '"') {
                if (next == '"') index++; else state = State.NORMAL;
            } else if (state == State.LINE_COMMENT && (current == '\n' || current == '\r')) state = State.NORMAL;
            else if (state == State.BLOCK_COMMENT && current == '*' && next == '/') { index++; state = State.NORMAL; }
        }
        return count;
    }

    public static void bind(PreparedStatement statement, List<SqlParameter> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            var parameter = parameters.get(index);
            if (parameter.value() == null) statement.setNull(index + 1, parameter.jdbcType());
            else if (parameter.value() instanceof byte[] bytes) statement.setBytes(index + 1, bytes);
            else statement.setObject(index + 1, parameter.value(), parameter.jdbcType());
        }
    }

    private static IllegalArgumentException mismatch() {
        return new IllegalArgumentException("SQL parameter count does not match placeholders");
    }

    private enum State { NORMAL, SINGLE, DOUBLE, LINE_COMMENT, BLOCK_COMMENT }
}
