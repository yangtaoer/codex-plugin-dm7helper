package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlSecurityPolicyTest {
    private final DmSqlParser parser = new DmSqlParser();
    private final SqlSecurityPolicy policy = new SqlSecurityPolicy();

    @ParameterizedTest
    @MethodSource("credentialBearingSql")
    void rejectsCredentialBearingDdlWithoutEchoingSqlOrCredential(String sql, String marker) {
        ParsedStatement statement = parser.parse(sql).get(0);

        SecretBearingSqlException failure = assertThrows(
                SecretBearingSqlException.class,
                () -> policy.assertNoEmbeddedCredentials(statement));

        assertFalse(failure.getMessage().contains(marker));
        assertFalse(failure.getMessage().contains(sql));
    }

    static Stream<Arguments> credentialBearingSql() throws IOException {
        return Stream.of(
                Arguments.of("CREATE USER APP IDENTIFIED BY fixture_token_7x9", "fixture_token_7x9"),
                Arguments.of("ALTER USER APP IDENTIFIED BY \"fixture_token_8y2\"", "fixture_token_8y2"),
                Arguments.of("CREATE USER APP PASSWORD 'fixture_token_3p4'", "fixture_token_3p4"),
                Arguments.of("CREATE ROLE APP_ROLE IDENTIFIED BY fixture_token_1r2", "fixture_token_1r2"),
                Arguments.of(fixture("database-link.sql"), "fixture_token_5q6"));
    }

    @ParameterizedTest
    @MethodSource("safeSql")
    void ignoresCredentialWordsInsideCommentsAndOrdinaryStringValues(String sql) {
        ParsedStatement statement = parser.parse(sql).get(0);
        assertDoesNotThrow(() -> policy.assertNoEmbeddedCredentials(statement));
    }

    static Stream<String> safeSql() {
        return Stream.of(
                "SELECT 'CREATE USER U IDENTIFIED BY anything' FROM DUAL",
                "/* CREATE USER U IDENTIFIED BY anything */ CREATE TABLE T(ID INT)",
                "COMMENT ON TABLE T IS 'IDENTIFIED BY is documentation'",
                "CREATE TABLE PASSWORD_AUDIT(PASSWORD_LABEL VARCHAR(20))",
                "CREATE TABLE KEYWORDS(USER INT, IDENTIFIED INT, BY INT)",
                "CREATE TABLE LINK_WORDS(DATABASE INT, LINK INT, CONNECT INT, TO INT, IDENTIFIED INT, BY INT)",
                "CREATE TABLE TOKEN_COINCIDENCE AS SELECT USER IDENTIFIED BY FROM SOURCE_ROWS",
                "CREATE DATABASE LINK CURRENT_USER_LINK USING 'service_name'");
    }

    private static String fixture(String name) throws IOException {
        try (var stream = SqlSecurityPolicyTest.class.getResourceAsStream("/fixtures/sql/" + name)) {
            if (stream == null) throw new IOException("missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
