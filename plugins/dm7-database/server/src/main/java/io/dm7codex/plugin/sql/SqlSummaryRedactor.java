package io.dm7codex.plugin.sql;

import java.nio.charset.StandardCharsets;

/** Produces a non-replayable, bounded SQL label for operational screens. */
public final class SqlSummaryRedactor {
    private static final String REDACTED = "?";
    private final int maxCodePoints;
    private final int maxUtf8Bytes;

    public SqlSummaryRedactor() { this(240, 512); }

    public SqlSummaryRedactor(int maxCodePoints, int maxUtf8Bytes) {
        if (maxCodePoints < 8 || maxUtf8Bytes < 16) throw new IllegalArgumentException("invalid summary bounds");
        this.maxCodePoints = maxCodePoints;
        this.maxUtf8Bytes = maxUtf8Bytes;
    }

    public String summarize(String sql) {
        if (sql == null || sql.isEmpty()) return "";
        var out = new StringBuilder(Math.min(sql.length(), maxCodePoints));
        try {
            scan(sql, out);
        } catch (RuntimeException malformed) {
            return REDACTED;
        }
        return bound(collapse(out));
    }

    private static void scan(String sql, StringBuilder out) {
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                appendSpace(out); out.append(REDACTED); i += 2;
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                appendSpace(out); out.append(REDACTED); i += 2;
                while (i + 1 < sql.length() && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                if (i + 1 < sql.length()) i += 2; else i = sql.length();
                continue;
            }
            int quote = literalQuoteIndex(sql, i);
            if (quote >= 0) {
                while (out.length() > 0 && Character.isLetter(out.charAt(out.length() - 1)) && quote > i) out.deleteCharAt(out.length() - 1);
                out.append(REDACTED); i = skipSingleQuoted(sql, quote); continue;
            }
            if ((c == 'q' || c == 'Q') && i + 2 < sql.length() && sql.charAt(i + 1) == '\'') {
                out.append(REDACTED); i = skipQQuote(sql, i + 2); continue;
            }
            if (c == '0' && i + 2 < sql.length() && (sql.charAt(i + 1) == 'x' || sql.charAt(i + 1) == 'X')
                    && Character.digit(sql.charAt(i + 2), 16) >= 0) {
                out.append(REDACTED); i += 2;
                while (i < sql.length() && Character.digit(sql.charAt(i), 16) >= 0) i++;
                continue;
            }
            if (c == '"') {
                out.append(c);i++;boolean closed=false;
                while(i<sql.length()){
                    char current=sql.charAt(i++);out.append(Character.isISOControl(current)?' ':current);
                    if(current=='"'){
                        if(i<sql.length()&&sql.charAt(i)=='"'){out.append('"');i++;continue;}
                        closed=true;break;
                    }
                }
                if(!closed)throw new IllegalArgumentException("unterminated quoted identifier");
                continue;
            }
            int cp = sql.codePointAt(i);
            out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp);
            i += Character.charCount(cp);
        }
    }

    private static int literalQuoteIndex(String sql, int i) {
        if (sql.charAt(i) == '\'') return i;
        if ((sql.charAt(i) == 'N' || sql.charAt(i) == 'n' || sql.charAt(i) == 'X' || sql.charAt(i) == 'x'
                || sql.charAt(i) == 'B' || sql.charAt(i) == 'b') && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') return i + 1;
        return -1;
    }

    private static int skipSingleQuoted(String sql, int quote) {
        int i = quote + 1;
        while (i < sql.length()) {
            if (sql.charAt(i++) == '\'') {
                if (i < sql.length() && sql.charAt(i) == '\'') { i++; continue; }
                return i;
            }
        }
        return sql.length();
    }

    private static int skipQQuote(String sql, int delimiterIndex) {
        char open = sql.charAt(delimiterIndex);
        char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
        int i = delimiterIndex + 1;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == close && sql.charAt(i + 1) == '\'') return i + 2;
            i++;
        }
        return sql.length();
    }

    private static void appendSpace(StringBuilder value) {
        if (value.length() > 0 && !Character.isWhitespace(value.charAt(value.length() - 1))) value.append(' ');
    }

    private static String collapse(StringBuilder source) {
        var value = new StringBuilder(source.length()); boolean space = true;
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i); i += Character.charCount(cp);
            if (Character.isWhitespace(cp)) { space = true; continue; }
            if (space && value.length() > 0) value.append(' ');
            value.appendCodePoint(cp); space = false;
        }
        return value.toString();
    }

    private String bound(String source) {
        var out = new StringBuilder(); int points = 0, bytes = 0;
        for (int i = 0; i < source.length() && points < maxCodePoints;) {
            int cp = source.codePointAt(i); String unit = new String(Character.toChars(cp));
            int size = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxUtf8Bytes) break;
            out.append(unit); bytes += size; points++; i += Character.charCount(cp);
        }
        return out.toString();
    }
}
