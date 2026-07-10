package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DmLiteralRendererTest {
    private final DmLiteralRenderer renderer = new DmLiteralRenderer();

    @ParameterizedTest(name = "{0} as JDBC {1}")
    @MethodSource("renderableValues")
    void rendersSupportedJdbcValues(Object value, int jdbcType, String expected) {
        assertEquals(expected, renderer.render(value, jdbcType));
    }

    static Stream<Arguments> renderableValues() {
        return Stream.of(
                Arguments.of(null, Types.VARCHAR, "NULL"),
                Arguments.of(true, Types.BOOLEAN, "TRUE"),
                Arguments.of(false, Types.BIT, "FALSE"),
                Arguments.of((byte) -1, Types.TINYINT, "-1"),
                Arguments.of((short) 2, Types.SMALLINT, "2"),
                Arguments.of(3, Types.INTEGER, "3"),
                Arguments.of(4L, Types.BIGINT, "4"),
                Arguments.of(new BigDecimal("123.4500"), Types.DECIMAL, "123.4500"),
                Arguments.of(1.25f, Types.REAL, "1.25"),
                Arguments.of(2.5d, Types.DOUBLE, "2.5"),
                Arguments.of("O'Brien 中文", Types.VARCHAR, "'O''Brien 中文'"),
                Arguments.of("中文", Types.NVARCHAR, "N'中文'"),
                Arguments.of(Date.valueOf("2026-07-11"), Types.DATE, "DATE '2026-07-11'"),
                Arguments.of(LocalDate.of(2026, 7, 11), Types.DATE, "DATE '2026-07-11'"),
                Arguments.of(Time.valueOf("09:08:07"), Types.TIME, "TIME '09:08:07'"),
                Arguments.of(LocalTime.of(9, 8, 7, 123_000_000), Types.TIME, "TIME '09:08:07.123'"),
                Arguments.of(Timestamp.valueOf("2026-07-11 09:08:07.123456"), Types.TIMESTAMP,
                        "TIMESTAMP '2026-07-11 09:08:07.123456'"),
                Arguments.of(LocalDateTime.of(2026, 7, 11, 9, 8, 7), Types.TIMESTAMP,
                        "TIMESTAMP '2026-07-11 09:08:07'"),
                Arguments.of(Instant.parse("2026-07-11T01:08:07Z"), Types.TIMESTAMP_WITH_TIMEZONE,
                        "TIMESTAMP '2026-07-11 01:08:07Z'"),
                Arguments.of(new byte[] {0x00, 0x0a, 0x1b, (byte) 0xff}, Types.VARBINARY,
                        "HEXTORAW('000A1BFF')"));
    }

    @ParameterizedTest
    @MethodSource("unrenderableValues")
    void rejectsUnsupportedOrNonFiniteValues(Object value, int jdbcType) {
        assertThrows(UnrenderableParameterException.class, () -> renderer.render(value, jdbcType));
    }

    static Stream<Arguments> unrenderableValues() {
        return Stream.of(
                Arguments.of(Double.NaN, Types.DOUBLE),
                Arguments.of(Double.POSITIVE_INFINITY, Types.DOUBLE),
                Arguments.of(Float.NEGATIVE_INFINITY, Types.REAL),
                Arguments.of("text", Types.OTHER),
                Arguments.of(new Object(), Types.JAVA_OBJECT),
                Arguments.of("1", Types.INTEGER),
                Arguments.of(1, Types.VARCHAR));
    }
}
