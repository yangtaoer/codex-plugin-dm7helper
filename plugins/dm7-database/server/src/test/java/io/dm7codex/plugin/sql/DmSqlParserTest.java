package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class DmSqlParserTest {
    private final DmSqlParser parser = new DmSqlParser();

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("classificationCases")
    void classifiesTopLevelOperation(String sql, SqlKind expected) {
        assertEquals(expected, parser.parse(sql).get(0).kind());
    }

    static Stream<Arguments> classificationCases() {
        return Stream.of(
                Arguments.of("SELECT 1", SqlKind.QUERY),
                Arguments.of("/*+ INDEX(T IDX_T) */ SELECT * FROM T", SqlKind.QUERY),
                Arguments.of("WITH q AS (SELECT 1) SELECT * FROM q", SqlKind.QUERY),
                Arguments.of("WITH RECURSIVE q(n) AS (SELECT 1), r AS (SELECT n FROM q) SELECT * FROM r", SqlKind.QUERY),
                Arguments.of("WITH \"update\"(id) AS (SELECT 1) UPDATE T SET C=1", SqlKind.DML),
                Arguments.of("WITH q AS (SELECT 1) DELETE FROM T WHERE ID IN (SELECT * FROM q)", SqlKind.DML),
                Arguments.of("INSERT INTO T VALUES (1)", SqlKind.DML),
                Arguments.of("MERGE INTO T USING S ON (T.ID=S.ID) WHEN MATCHED THEN UPDATE SET T.C=S.C", SqlKind.DML),
                Arguments.of("CREATE TABLE T(ID INT)", SqlKind.DDL),
                Arguments.of("ALTER TABLE T ADD C INT", SqlKind.DDL),
                Arguments.of("DROP TABLE T", SqlKind.DDL),
                Arguments.of("TRUNCATE TABLE T", SqlKind.DDL),
                Arguments.of("RENAME T TO T2", SqlKind.DDL),
                Arguments.of("COMMENT ON TABLE T IS '说明'", SqlKind.DDL),
                Arguments.of("GRANT SELECT ON T TO U", SqlKind.DCL),
                Arguments.of("REVOKE SELECT ON T FROM U", SqlKind.DCL),
                Arguments.of("COMMIT", SqlKind.TRANSACTION),
                Arguments.of("ROLLBACK", SqlKind.TRANSACTION),
                Arguments.of("SAVEPOINT S1", SqlKind.TRANSACTION),
                Arguments.of("SET TRANSACTION READ ONLY", SqlKind.TRANSACTION),
                Arguments.of("SET SCHEMA APP", SqlKind.SESSION),
                Arguments.of("CALL P(1)", SqlKind.CALL),
                Arguments.of("EXPLAIN SELECT 1", SqlKind.EXPLAIN),
                Arguments.of("BEGIN EXECUTE IMMEDIATE 'DROP TABLE T'; END", SqlKind.ANONYMOUS_BLOCK),
                Arguments.of("DECLARE X INT; BEGIN X := 1; END", SqlKind.ANONYMOUS_BLOCK),
                Arguments.of("VACUUM", SqlKind.UNKNOWN));
    }

    @Test
    void splitsOnlyAtTopLevelDelimitersAndSkipsEmptyStatements() {
        String script = ";SELECT '甲;乙' AS V /* block;comment */ FROM T;\n"
                + "-- line;comment\nUPDATE T SET C = \"分;号\";;";

        List<ParsedStatement> statements = parser.parse(script);

        assertEquals(2, statements.size());
        assertEquals("SELECT '甲;乙' AS V /* block;comment */ FROM T", statements.get(0).originalSql());
        assertEquals("\n-- line;comment\nUPDATE T SET C = \"分;号\"", statements.get(1).originalSql());
        assertEquals(0, statements.get(0).index());
        assertEquals(1, statements.get(1).index());
    }

    @Test
    void keepsNestedBlockCommentsInsideOneStatement() {
        String sql = "SELECT 1 /* outer; /* inner; */ still outer; */ FROM DUAL; SELECT 2";

        List<ParsedStatement> statements = parser.parse(sql);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1 /* outer; /* inner; */ still outer; */ FROM DUAL", statements.get(0).originalSql());
    }

    @Test
    void procedureAndTriggerBodiesRemainSingleStatements() throws IOException {
        String script = fixture("procedure-and-trigger.sql");

        List<ParsedStatement> statements = parser.parse(script);

        assertEquals(4, statements.size());
        assertEquals(SqlKind.DDL, statements.get(0).kind());
        assertTrue(statements.get(0).originalSql().contains("'过程;值'"));
        assertTrue(statements.get(0).originalSql().contains("END IF;"));
        assertTrue(statements.get(0).originalSql().contains("CASE WHEN 1 = 1"));
        assertTrue(statements.get(0).originalSql().contains("'CASE 后仍在过程体'"));
        assertEquals(SqlKind.DDL, statements.get(1).kind());
        assertTrue(statements.get(1).originalSql().contains("INSERT INTO 审计日志"));
        assertEquals(SqlKind.DDL, statements.get(2).kind());
        assertTrue(statements.get(2).originalSql().contains("RETURN 42;"));
        assertEquals(SqlKind.QUERY, statements.get(3).kind());
    }

    @Test
    void optimizerHintAndChineseTextSurviveAndCrLfIsTheOnlyNormalization() throws IOException {
        String script = fixture("unicode-crlf.sql").replace("\n", "\r\n");
        String expected = "/*+ INDEX(订单 IDX_订单_编号) */\nSELECT \"名称\", '中文值不应归一化' FROM \"订单\"";

        ParsedStatement statement = parser.parse(script).get(0);

        assertEquals(expected, statement.originalSql());
        assertEquals(sha256(expected), statement.sha256());
        assertFalse(statement.originalSql().contains("\r"));
    }

    @Test
    void sha256CoversTheExactNormalizedOriginalStatementInUtf8() {
        String original = "  SELECT '中文';";

        ParsedStatement statement = parser.parse(original).get(0);

        assertEquals("  SELECT '中文'", statement.originalSql());
        assertEquals(sha256(statement.originalSql()), statement.sha256());
    }

    @Test
    void statementAndPurposeReleaseEligibilityAreExact() {
        assertTrue(parser.parse("CREATE TABLE T(ID INT)").get(0).releaseEligibleKind());
        assertTrue(parser.parse("UPDATE T SET C=1").get(0).releaseEligibleKind());
        assertFalse(parser.parse("SELECT 1").get(0).releaseEligibleKind());
        assertTrue(SqlPurpose.PRODUCTION_CHANGE.isReleaseEligible());
        assertTrue(SqlPurpose.MIGRATION.isReleaseEligible());
        assertFalse(SqlPurpose.TEST.isReleaseEligible());
        assertFalse(SqlPurpose.MOCK.isReleaseEligible());
        assertFalse(SqlPurpose.SEED.isReleaseEligible());
        assertFalse(SqlPurpose.SAMPLE.isReleaseEligible());
    }

    @Test
    void quoteAndCommentEdgeTableDoesNotCreatePhantomStatements() {
        List<String> scripts = List.of(
                "SELECT ''';''' FROM DUAL;SELECT 2",
                "SELECT \"a\"\";b\" FROM DUAL;SELECT 2",
                "SELECT 1 -- ; ignored\n;SELECT 2",
                "SELECT 1 /* ; ignored */;SELECT 2",
                ";;; SELECT 1 ; ; SELECT 2 ;;;");

        for (String script : scripts) {
            assertEquals(2, parser.parse(script).size(), script);
        }
    }

    @Test
    void nestedBeginInsideCaseCannotHideFollowingCredentialDdl() {
        String script = """
                CREATE OR REPLACE PROCEDURE REVIEW_CASE AS
                BEGIN
                  CASE
                    WHEN 1 = 1 THEN
                      BEGIN
                        NULL;
                      END;
                  END CASE;
                END;
                CREATE USER REVIEW_USER IDENTIFIED BY fixture_review_value;
                """;

        List<ParsedStatement> statements = parser.parse(script);

        assertEquals(2, statements.size());
        assertEquals(SqlKind.DDL, statements.get(0).kind());
        assertEquals(SqlKind.DDL, statements.get(1).kind());
        assertThrows(SecretBearingSqlException.class,
                () -> new SqlSecurityPolicy().assertNoEmbeddedCredentials(statements.get(1)));
    }

    @Test
    void dmBeginDelimitedIfCannotHideFollowingCredentialDdl() {
        String script = """
                CREATE OR REPLACE PROCEDURE REVIEW_IF_STYLE AS
                IF 1 = 1
                BEGIN
                  INSERT INTO T(V) VALUES (1);
                END
                ELSEIF 2 = 2
                BEGIN
                  INSERT INTO T(V) VALUES (2);
                END
                ELSE
                BEGIN
                  INSERT INTO T(V) VALUES (3);
                END;
                CREATE USER REVIEW_IF_USER IDENTIFIED BY fixture_if_review_value;
                """;

        List<ParsedStatement> statements = parser.parse(script);

        assertEquals(2, statements.size());
        assertEquals(SqlKind.DDL, statements.get(1).kind());
        assertThrows(SecretBearingSqlException.class,
                () -> new SqlSecurityPolicy().assertNoEmbeddedCredentials(statements.get(1)));
    }

    @ParameterizedTest
    @MethodSource("proceduralStackCases")
    void tracksNestedProceduralBlocksUntilTheRoutineTerminator(String routine, String bodyMarker) {
        List<ParsedStatement> statements = parser.parse(routine + "\nSELECT 1;");

        assertEquals(2, statements.size(), bodyMarker);
        assertEquals(SqlKind.DDL, statements.get(0).kind());
        assertTrue(statements.get(0).originalSql().contains(bodyMarker));
        assertEquals(SqlKind.QUERY, statements.get(1).kind());
    }

    static Stream<Arguments> proceduralStackCases() {
        return Stream.of(
                Arguments.of("""
                        CREATE OR REPLACE PROCEDURE STACKED_IF AS
                        BEGIN
                          IF 1 = 1 THEN
                            BEGIN
                              CASE WHEN 1 = 1 THEN NULL; ELSE NULL; END CASE;
                            END;
                          ELSIF 2 = 2 THEN
                            NULL;
                          END IF;
                        END STACKED_IF;
                        """, "ELSIF 2 = 2"),
                Arguments.of("""
                        CREATE OR REPLACE TRIGGER STACKED_LOOPS AFTER INSERT ON T
                        BEGIN
                          FOR I IN 1..2 LOOP
                            WHILE I > 0 LOOP
                              BEGIN
                                NULL;
                              END;
                            END LOOP;
                          END LOOP;
                        END STACKED_LOOPS;
                        """, "WHILE I > 0 LOOP"),
                Arguments.of("""
                        CREATE OR REPLACE PROCEDURE NESTED_CASE AS
                        BEGIN
                          INSERT INTO T(V) SELECT CASE WHEN 1 = 1
                            THEN CASE WHEN 2 = 2 THEN 2 ELSE 3 END
                            ELSE 4 END FROM DUAL;
                        END;
                        """, "THEN CASE WHEN 2 = 2"),
                Arguments.of("""
                        CREATE OR REPLACE PROCEDURE BEGIN_STYLE_NESTED AS
                        IF CASE WHEN 1 = 1 THEN 1 ELSE 0 END = 1
                        BEGIN
                          LOOP
                            CASE WHEN 2 = 2 THEN NULL; ELSE NULL; END CASE;
                          END LOOP;
                        END
                        ELSE
                        BEGIN
                          NULL;
                        END;
                        """, "END LOOP"),
                Arguments.of("""
                        CREATE OR REPLACE PROCEDURE BEGIN_STYLE_LOOKAHEAD AS
                        IF CHECK_TEXT('THEN', (SELECT 'BEGIN')) = 1 /* THEN is not syntax */
                        BEGIN
                          NULL;
                        END
                        ELSE
                        BEGIN
                          NULL;
                        END;
                        """, "THEN is not syntax"));
    }

    private static String fixture(String name) throws IOException {
        try (var stream = DmSqlParserTest.class.getResourceAsStream("/fixtures/sql/" + name)) {
            if (stream == null) throw new IOException("missing fixture " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
