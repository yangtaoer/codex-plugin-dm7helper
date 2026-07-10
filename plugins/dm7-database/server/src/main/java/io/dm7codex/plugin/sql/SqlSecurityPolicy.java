package io.dm7codex.plugin.sql;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SqlSecurityPolicy {
    public static final String EMBEDDED_CREDENTIALS_POLICY = "EMBEDDED_CREDENTIALS";
    private static final Set<String> CREDENTIAL_PRINCIPALS = Set.of("USER", "ROLE");

    public void assertNoEmbeddedCredentials(ParsedStatement statement) {
        Objects.requireNonNull(statement, "statement");
        if (statement.kind() != SqlKind.DDL) return;
        List<String> tokens = DmSqlParser.lexicalTokens(statement.originalSql(), true);
        if (tokens.isEmpty()) return;

        boolean createsOrAlters = "CREATE".equals(tokens.get(0)) || "ALTER".equals(tokens.get(0));
        if (!createsOrAlters) return;

        if (hasPrincipalCredentialClause(tokens) || hasDatabaseLinkCredentialClause(tokens)) {
            throw new SecretBearingSqlException(EMBEDDED_CREDENTIALS_POLICY);
        }
    }

    private static boolean hasPrincipalCredentialClause(List<String> tokens) {
        if (tokens.size() < 3 || !CREDENTIAL_PRINCIPALS.contains(tokens.get(1))) return false;
        return containsSequence(tokens, 2, "IDENTIFIED", "BY")
                || containsSequence(tokens, 2, "PASSWORD")
                || containsSequence(tokens, 2, "AUTHENTICATED", "BY");
    }

    private static boolean hasDatabaseLinkCredentialClause(List<String> tokens) {
        int cursor = 1;
        while (cursor < tokens.size()
                && ("PUBLIC".equals(tokens.get(cursor)) || "SHARED".equals(tokens.get(cursor)))) {
            cursor++;
        }
        if (cursor + 1 >= tokens.size()
                || !"DATABASE".equals(tokens.get(cursor)) || !"LINK".equals(tokens.get(cursor + 1))) {
            return false;
        }
        int connect = indexOfSequence(tokens, cursor + 2, "CONNECT", "TO");
        if (connect < 0) return false;
        return containsSequence(tokens, connect + 2, "IDENTIFIED", "BY")
                || containsSequence(tokens, connect + 2, "PASSWORD")
                || containsSequence(tokens, connect + 2, "AUTHENTICATED", "BY");
    }

    private static boolean containsSequence(List<String> tokens, int from, String... sequence) {
        return indexOfSequence(tokens, from, sequence) >= 0;
    }

    private static int indexOfSequence(List<String> tokens, int from, String... sequence) {
        outer:
        for (int i = from; i + sequence.length <= tokens.size(); i++) {
            for (int j = 0; j < sequence.length; j++) {
                if (!sequence[j].equals(tokens.get(i + j))) continue outer;
            }
            return i;
        }
        return -1;
    }
}
