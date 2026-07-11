package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.*;
import io.dm7codex.plugin.execution.SqlParameter;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SqlParameterBindingsTest {
    @Test
    void rendersSupportedValuesForReplayableTrackedSql() {
        var bindings = new SqlParameterBindings(new DmLiteralRenderer());
        String sql = "insert into t(a,b,c,d,e,f,g) values (?,?,?,?,?,?,?)";
        var parameters = List.of(
                new SqlParameter("中文'值", Types.NVARCHAR),
                new SqlParameter(null, Types.VARCHAR),
                new SqlParameter(42, Types.INTEGER),
                new SqlParameter(true, Types.BOOLEAN),
                new SqlParameter(LocalDate.of(2026, 7, 11), Types.DATE),
                new SqlParameter(LocalDateTime.of(2026, 7, 11, 12, 30), Types.TIMESTAMP),
                new SqlParameter(new byte[]{0x01, (byte) 0xff}, Types.VARBINARY));

        assertEquals("insert into t(a,b,c,d,e,f,g) values (N'中文''值',NULL,42,TRUE,"
                + "DATE '2026-07-11',TIMESTAMP '2026-07-11 12:30',HEXTORAW('01FF'))",
                bindings.render(sql, parameters));
    }

    @Test
    void ignoresQuestionMarksInsideStringsIdentifiersAndComments() {
        var bindings = new SqlParameterBindings(new DmLiteralRenderer());
        String sql = "update t set c=? where note='?' and \"?\"=\"?\" /* ? */ -- ?\n";
        assertEquals("update t set c=7 where note='?' and \"?\"=\"?\" /* ? */ -- ?\n",
                bindings.render(sql, List.of(new SqlParameter(7, Types.INTEGER))));
    }

    @Test
    void rejectsCountMismatchAndUnrenderableValues() {
        var bindings = new SqlParameterBindings(new DmLiteralRenderer());
        assertThrows(IllegalArgumentException.class, () -> bindings.render("select ?", List.of()));
        assertThrows(IllegalArgumentException.class, () -> bindings.render("select 1", List.of(
                new SqlParameter(1, Types.INTEGER))));
        assertThrows(UnrenderableParameterException.class, () -> bindings.render("select ?", List.of(
                new SqlParameter(new Object(), Types.JAVA_OBJECT))));
    }
}
