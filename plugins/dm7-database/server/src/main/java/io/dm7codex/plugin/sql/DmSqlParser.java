package io.dm7codex.plugin.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class DmSqlParser {
    private static final Set<String> DDL = Set.of(
            "CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "COMMENT");
    private static final Set<String> DML = Set.of("MERGE", "INSERT", "UPDATE", "DELETE");
    private static final Set<String> DCL = Set.of("GRANT", "REVOKE");
    private static final Set<String> TRANSACTION = Set.of("COMMIT", "ROLLBACK", "SAVEPOINT");
    private static final Set<String> STRUCTURED_END_SUFFIXES = Set.of("IF", "LOOP", "CASE");

    public List<ParsedStatement> parse(String script) {
        Objects.requireNonNull(script, "script");
        String normalized = script.replace("\r\n", "\n");
        List<String> split = new Lexer(normalized).splitTopLevelStatements();
        List<ParsedStatement> parsed = new ArrayList<>(split.size());
        for (String sql : split) {
            parsed.add(new ParsedStatement(parsed.size(), sql, classify(sql), sha256(sql)));
        }
        return List.copyOf(parsed);
    }

    static List<String> lexicalTokens(String sql, boolean includeStringMarkers) {
        List<String> tokens = new ArrayList<>();
        LexState state = LexState.NORMAL;
        int blockDepth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            switch (state) {
                case NORMAL, PROCEDURAL_BLOCK -> {
                    if (current == '\'') {
                        if (includeStringMarkers) tokens.add("<STRING>");
                        state = LexState.SINGLE_QUOTE;
                    } else if (current == '"') {
                        tokens.add("<IDENTIFIER>");
                        state = LexState.DOUBLE_QUOTE;
                    } else if (current == '-' && hasNext(sql, i, '-')) {
                        state = LexState.LINE_COMMENT;
                        i++;
                    } else if (current == '/' && hasNext(sql, i, '*')) {
                        state = LexState.BLOCK_COMMENT;
                        blockDepth = 1;
                        i++;
                    } else if (isWordStart(current)) {
                        int end = i + 1;
                        while (end < sql.length() && isWordPart(sql.charAt(end))) end++;
                        tokens.add(sql.substring(i, end).toUpperCase(Locale.ROOT));
                        i = end - 1;
                    } else if (current == '(' || current == ')' || current == ',') {
                        tokens.add(String.valueOf(current));
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && hasNext(sql, i, '\'')) {
                        i++;
                    } else if (current == '\'') {
                        state = LexState.NORMAL;
                    }
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && hasNext(sql, i, '"')) {
                        i++;
                    } else if (current == '"') {
                        state = LexState.NORMAL;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n' || current == '\r') state = LexState.NORMAL;
                }
                case BLOCK_COMMENT -> {
                    if (current == '/' && hasNext(sql, i, '*')) {
                        blockDepth++;
                        i++;
                    } else if (current == '*' && hasNext(sql, i, '/')) {
                        blockDepth--;
                        i++;
                        if (blockDepth == 0) state = LexState.NORMAL;
                    }
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static SqlKind classify(String sql) {
        List<String> tokens = lexicalTokens(sql, false);
        if (tokens.isEmpty()) return SqlKind.UNKNOWN;
        String verb = tokens.get(0);
        if ("WITH".equals(verb)) {
            verb = finalWithVerb(tokens);
            if (verb == null) return SqlKind.UNKNOWN;
        }
        if ("ALTER".equals(verb) && tokens.size() > 1
                && ("SESSION".equals(tokens.get(1)) || "SYSTEM".equals(tokens.get(1)))) {
            return SqlKind.SESSION;
        }
        if ("SET".equals(verb)) {
            return tokens.size() > 1 && "TRANSACTION".equals(tokens.get(1))
                    ? SqlKind.TRANSACTION : SqlKind.SESSION;
        }
        if ("START".equals(verb) && tokens.size() > 1 && "TRANSACTION".equals(tokens.get(1))) {
            return SqlKind.TRANSACTION;
        }
        if ("SELECT".equals(verb) || "VALUES".equals(verb)) return SqlKind.QUERY;
        if ("EXPLAIN".equals(verb)) return SqlKind.EXPLAIN;
        if (DDL.contains(verb)) return SqlKind.DDL;
        if (DML.contains(verb)) return SqlKind.DML;
        if (DCL.contains(verb)) return SqlKind.DCL;
        if (TRANSACTION.contains(verb)) return SqlKind.TRANSACTION;
        if ("CALL".equals(verb) || "EXEC".equals(verb) || "EXECUTE".equals(verb)) return SqlKind.CALL;
        if ("BEGIN".equals(verb) || "DECLARE".equals(verb)) return SqlKind.ANONYMOUS_BLOCK;
        if ("USE".equals(verb)) return SqlKind.SESSION;
        return SqlKind.UNKNOWN;
    }

    private static String finalWithVerb(List<String> tokens) {
        int cursor = 1;
        if (cursor < tokens.size() && "RECURSIVE".equals(tokens.get(cursor))) cursor++;
        while (cursor < tokens.size()) {
            if (isPunctuation(tokens.get(cursor))) return null;
            cursor++;
            if (cursor < tokens.size() && "(".equals(tokens.get(cursor))) {
                cursor = afterBalancedParentheses(tokens, cursor);
                if (cursor < 0) return null;
            }
            if (cursor >= tokens.size() || !"AS".equals(tokens.get(cursor))) return null;
            cursor++;
            if (cursor < tokens.size() && "NOT".equals(tokens.get(cursor))) cursor++;
            if (cursor < tokens.size() && "MATERIALIZED".equals(tokens.get(cursor))) cursor++;
            if (cursor >= tokens.size() || !"(".equals(tokens.get(cursor))) return null;
            cursor = afterBalancedParentheses(tokens, cursor);
            if (cursor < 0 || cursor >= tokens.size()) return null;
            if (",".equals(tokens.get(cursor))) {
                cursor++;
                continue;
            }
            return tokens.get(cursor);
        }
        return null;
    }

    private static int afterBalancedParentheses(List<String> tokens, int opening) {
        int depth = 0;
        for (int i = opening; i < tokens.size(); i++) {
            if ("(".equals(tokens.get(i))) depth++;
            else if (")".equals(tokens.get(i)) && --depth == 0) return i + 1;
        }
        return -1;
    }

    private static boolean isPunctuation(String token) {
        return "(".equals(token) || ")".equals(token) || ",".equals(token);
    }

    private static String sha256(String sql) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean hasNext(String text, int position, char expected) {
        return position + 1 < text.length() && text.charAt(position + 1) == expected;
    }

    private static boolean isWordStart(char value) {
        return Character.isUnicodeIdentifierStart(value) || value == '_' || value == '$' || value == '#';
    }

    private static boolean isWordPart(char value) {
        return Character.isUnicodeIdentifierPart(value) || value == '$' || value == '#';
    }

    private enum LexState {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        PROCEDURAL_BLOCK
    }

    private static final class Lexer {
        private final String script;
        private final List<String> statements = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private final List<String> headerWords = new ArrayList<>();
        private LexState state = LexState.NORMAL;
        private LexState quotedReturnState = LexState.NORMAL;
        private int parenthesesDepth;
        private int commentDepth;
        private int beginDepth;
        private int caseDepth;
        private boolean sawBegin;
        private boolean proceduralComplete;
        private boolean skipClosingCaseKeyword;

        private Lexer(String script) {
            this.script = script;
        }

        private List<String> splitTopLevelStatements() {
            for (int i = 0; i < script.length(); i++) {
                char value = script.charAt(i);
                switch (state) {
                    case NORMAL, PROCEDURAL_BLOCK -> {
                        boolean procedural = state == LexState.PROCEDURAL_BLOCK;
                        if (value == '\'') {
                            current.append(value);
                            quotedReturnState = state;
                            state = LexState.SINGLE_QUOTE;
                        } else if (value == '"') {
                            current.append(value);
                            quotedReturnState = state;
                            state = LexState.DOUBLE_QUOTE;
                        } else if (value == '-' && hasNext(script, i, '-')) {
                            current.append("--");
                            quotedReturnState = state;
                            state = LexState.LINE_COMMENT;
                            i++;
                        } else if (value == '/' && hasNext(script, i, '*')) {
                            current.append("/*");
                            quotedReturnState = state;
                            state = LexState.BLOCK_COMMENT;
                            commentDepth = 1;
                            i++;
                        } else if (isWordStart(value)) {
                            int end = i + 1;
                            while (end < script.length() && isWordPart(script.charAt(end))) end++;
                            String originalWord = script.substring(i, end);
                            current.append(originalWord);
                            consumeWord(originalWord.toUpperCase(Locale.ROOT), procedural, end);
                            i = end - 1;
                        } else if (!procedural && value == '(') {
                            parenthesesDepth++;
                            current.append(value);
                        } else if (!procedural && value == ')') {
                            if (parenthesesDepth > 0) parenthesesDepth--;
                            current.append(value);
                        } else if (value == ';' && isDelimiter(procedural)) {
                            finishStatement();
                        } else {
                            current.append(value);
                        }
                    }
                    case SINGLE_QUOTE -> {
                        current.append(value);
                        if (value == '\'' && hasNext(script, i, '\'')) {
                            current.append('\'');
                            i++;
                        } else if (value == '\'') {
                            state = quotedReturnState;
                        }
                    }
                    case DOUBLE_QUOTE -> {
                        current.append(value);
                        if (value == '"' && hasNext(script, i, '"')) {
                            current.append('"');
                            i++;
                        } else if (value == '"') {
                            state = quotedReturnState;
                        }
                    }
                    case LINE_COMMENT -> {
                        current.append(value);
                        if (value == '\n' || value == '\r') state = quotedReturnState;
                    }
                    case BLOCK_COMMENT -> {
                        current.append(value);
                        if (value == '/' && hasNext(script, i, '*')) {
                            current.append('*');
                            commentDepth++;
                            i++;
                        } else if (value == '*' && hasNext(script, i, '/')) {
                            current.append('/');
                            commentDepth--;
                            i++;
                            if (commentDepth == 0) state = quotedReturnState;
                        }
                    }
                }
            }
            finishStatement();
            return List.copyOf(statements);
        }

        private void consumeWord(String word, boolean procedural, int wordEnd) {
            if (!procedural) {
                headerWords.add(word);
                if (isAnonymousHeader(headerWords) || isCreateRoutineHeader(headerWords)) {
                    state = LexState.PROCEDURAL_BLOCK;
                    if ("BEGIN".equals(word)) {
                        beginDepth = 1;
                        sawBegin = true;
                    }
                }
                return;
            }
            if ("BEGIN".equals(word)) {
                beginDepth++;
                sawBegin = true;
                proceduralComplete = false;
            } else if ("CASE".equals(word)) {
                if (skipClosingCaseKeyword) {
                    skipClosingCaseKeyword = false;
                } else {
                    caseDepth++;
                }
            } else if ("END".equals(word) && sawBegin) {
                String suffix = nextSignificantWord(script, wordEnd);
                if ("CASE".equals(suffix)) {
                    if (caseDepth > 0) caseDepth--;
                    skipClosingCaseKeyword = true;
                } else if (caseDepth > 0 && !STRUCTURED_END_SUFFIXES.contains(suffix)) {
                    caseDepth--;
                } else if (!STRUCTURED_END_SUFFIXES.contains(suffix)) {
                    if (beginDepth > 0) beginDepth--;
                    proceduralComplete = beginDepth == 0;
                }
            }
        }

        private boolean isDelimiter(boolean procedural) {
            return procedural ? proceduralComplete : parenthesesDepth == 0;
        }

        private void finishStatement() {
            String candidate = current.toString();
            if (!lexicalTokens(candidate, true).isEmpty()) statements.add(candidate);
            current.setLength(0);
            headerWords.clear();
            state = LexState.NORMAL;
            quotedReturnState = LexState.NORMAL;
            parenthesesDepth = 0;
            commentDepth = 0;
            beginDepth = 0;
            caseDepth = 0;
            sawBegin = false;
            proceduralComplete = false;
            skipClosingCaseKeyword = false;
        }

        private static boolean isAnonymousHeader(List<String> words) {
            return words.size() == 1 && ("BEGIN".equals(words.get(0)) || "DECLARE".equals(words.get(0)));
        }

        private static boolean isCreateRoutineHeader(List<String> words) {
            if (words.isEmpty() || !"CREATE".equals(words.get(0))) return false;
            int cursor = 1;
            if (words.size() > cursor && "OR".equals(words.get(cursor))) cursor++;
            if (words.size() > cursor && "REPLACE".equals(words.get(cursor))) cursor++;
            return words.size() > cursor && Set.of("PROCEDURE", "FUNCTION", "TRIGGER").contains(words.get(cursor));
        }
    }

    private static String nextSignificantWord(String text, int start) {
        int cursor = start;
        while (cursor < text.length()) {
            char value = text.charAt(cursor);
            if (Character.isWhitespace(value)) {
                cursor++;
            } else if (value == '-' && hasNext(text, cursor, '-')) {
                cursor += 2;
                while (cursor < text.length() && text.charAt(cursor) != '\n' && text.charAt(cursor) != '\r') cursor++;
            } else if (value == '/' && hasNext(text, cursor, '*')) {
                cursor += 2;
                int depth = 1;
                while (cursor < text.length() && depth > 0) {
                    if (text.charAt(cursor) == '/' && hasNext(text, cursor, '*')) {
                        depth++;
                        cursor += 2;
                    } else if (text.charAt(cursor) == '*' && hasNext(text, cursor, '/')) {
                        depth--;
                        cursor += 2;
                    } else {
                        cursor++;
                    }
                }
            } else if (isWordStart(value)) {
                int end = cursor + 1;
                while (end < text.length() && isWordPart(text.charAt(end))) end++;
                return text.substring(cursor, end).toUpperCase(Locale.ROOT);
            } else {
                return "";
            }
        }
        return "";
    }
}
