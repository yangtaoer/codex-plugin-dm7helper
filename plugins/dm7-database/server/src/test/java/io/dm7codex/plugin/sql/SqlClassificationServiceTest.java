package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SqlClassificationServiceTest {
    @Test void classifiesQueriesAndMutationEligibilityWithoutReturningSql() {
        var classifier = new SqlClassificationService(new DmSqlParser(), new SqlSecurityPolicy(), 128);
        var query = classifier.classify("select 1");
        assertEquals(1, query.statementCount());
        assertEquals(List.of(SqlKind.QUERY), query.kinds());
        assertTrue(query.queryOnly());
        assertFalse(query.requiresPurpose());
        assertFalse(query.atomicAllowed());
        assertFalse(query.toString().contains("select"));

        var mutation = classifier.classify("update t set n=1; create table x(id int)");
        assertEquals(List.of(SqlKind.DML, SqlKind.DDL), mutation.kinds());
        assertFalse(mutation.queryOnly());
        assertTrue(mutation.requiresPurpose());
        assertFalse(mutation.atomicAllowed());
        assertTrue(classifier.classify("delete from t").atomicAllowed());
    }

    @Test void rejectsBlankMixedUnsupportedTransactionsSecretsAndOversizedInput() {
        var classifier = new SqlClassificationService(new DmSqlParser(), new SqlSecurityPolicy(), 48);
        for (String sql : List.of(" ", "select 1; values 2", "select 1; update t set n=1", "commit", "grant select on t to u", "call p()")) {
            assertThrows(SqlClassificationService.ClassificationRejected.class, () -> classifier.classify(sql));
        }
        assertThrows(SecretBearingSqlException.class,
                () -> classifier.classify("create user demo identified by supersecret"));
        assertThrows(SqlClassificationService.ClassificationRejected.class,
                () -> classifier.classify("select '中文' from t where name='" + "x".repeat(100) + "'"));
    }
}
