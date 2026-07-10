package io.dm7codex.plugin.sql;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public final class DmLiteralRenderer {
    private static final long MAX_PLAIN_LITERAL_LENGTH = 100_000L;

    public String render(Object value, int jdbcType) {
        if (value == null) return "NULL";
        if (isBooleanType(jdbcType) && value instanceof Boolean booleanValue) {
            return booleanValue ? "TRUE" : "FALSE";
        }
        if (isIntegralType(jdbcType) && isIntegral(value)) return value.toString();
        if (isDecimalType(jdbcType) && value instanceof BigDecimal decimal) {
            return renderPlainDecimal(decimal, jdbcType);
        }
        if (isDecimalType(jdbcType) && isIntegral(value)) return value.toString();
        if (isFloatingType(jdbcType) && value instanceof Float floating) {
            if (!Float.isFinite(floating)) throw unsupported(jdbcType);
            return Float.toString(floating);
        }
        if (isFloatingType(jdbcType) && value instanceof Double floating) {
            if (!Double.isFinite(floating)) throw unsupported(jdbcType);
            return Double.toString(floating);
        }
        if (isCharacterType(jdbcType) && value instanceof String string) {
            return quote(string, false);
        }
        if (isNationalCharacterType(jdbcType) && value instanceof String string) {
            return quote(string, true);
        }
        if (jdbcType == Types.DATE) {
            if (value instanceof java.sql.Date date) return "DATE '" + date.toLocalDate() + "'";
            if (value instanceof LocalDate date) return "DATE '" + date + "'";
        }
        if (jdbcType == Types.TIME) {
            if (value instanceof java.sql.Time time) return "TIME '" + time.toLocalTime() + "'";
            if (value instanceof LocalTime time) return "TIME '" + time + "'";
        }
        if (jdbcType == Types.TIME_WITH_TIMEZONE && value instanceof OffsetTime time) {
            return "TIME '" + DateTimeFormatter.ISO_OFFSET_TIME.format(time) + "'";
        }
        if (jdbcType == Types.TIMESTAMP) {
            if (value instanceof java.sql.Timestamp timestamp) {
                return "TIMESTAMP '" + timestamp.toLocalDateTime().toString().replace('T', ' ') + "'";
            }
            if (value instanceof LocalDateTime timestamp) {
                return "TIMESTAMP '" + timestamp.toString().replace('T', ' ') + "'";
            }
        }
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE && value instanceof OffsetDateTime timestamp) {
            return "TIMESTAMP '" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp).replace('T', ' ') + "'";
        }
        if (jdbcType == Types.TIMESTAMP_WITH_TIMEZONE && value instanceof Instant timestamp) {
            return "TIMESTAMP '" + DateTimeFormatter.ISO_INSTANT.format(timestamp).replace('T', ' ') + "'";
        }
        if (isBinaryType(jdbcType) && value instanceof byte[] bytes) {
            return "HEXTORAW('" + HexFormat.of().withUpperCase().formatHex(bytes) + "')";
        }
        throw unsupported(jdbcType);
    }

    private static String quote(String value, boolean national) {
        return (national ? "N'" : "'") + value.replace("'", "''") + "'";
    }

    private static String renderPlainDecimal(BigDecimal decimal, int jdbcType) {
        long precision = decimal.precision();
        long scale = decimal.scale();
        long unsignedLength;
        if (decimal.signum() == 0 && scale <= 0) {
            unsignedLength = 1L;
        } else if (scale <= 0) {
            unsignedLength = precision - scale;
        } else if (scale >= precision) {
            unsignedLength = scale + 2L;
        } else {
            unsignedLength = precision + 1L;
        }
        long renderedLength = unsignedLength + (decimal.signum() < 0 ? 1L : 0L);
        if (renderedLength > MAX_PLAIN_LITERAL_LENGTH) throw unsupported(jdbcType);
        try {
            return decimal.toPlainString();
        } catch (ArithmeticException unsafeLayout) {
            throw unsupported(jdbcType);
        }
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger;
    }

    private static boolean isBooleanType(int type) {
        return type == Types.BOOLEAN || type == Types.BIT;
    }

    private static boolean isIntegralType(int type) {
        return type == Types.TINYINT || type == Types.SMALLINT || type == Types.INTEGER || type == Types.BIGINT;
    }

    private static boolean isDecimalType(int type) {
        return type == Types.DECIMAL || type == Types.NUMERIC;
    }

    private static boolean isFloatingType(int type) {
        return type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE;
    }

    private static boolean isCharacterType(int type) {
        return type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR || type == Types.CLOB;
    }

    private static boolean isNationalCharacterType(int type) {
        return type == Types.NCHAR || type == Types.NVARCHAR || type == Types.LONGNVARCHAR || type == Types.NCLOB;
    }

    private static boolean isBinaryType(int type) {
        return type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB;
    }

    private static UnrenderableParameterException unsupported(int jdbcType) {
        return new UnrenderableParameterException(jdbcType);
    }
}
