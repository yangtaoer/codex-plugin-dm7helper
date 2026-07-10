package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JdbcUrlDiagnosticsTest {
    @Test void oldPathSegmentProducesActionableWarningWithoutMutation() {
        String url = "jdbc:dm7://203.0.113.10:5236/SYSTEM?ignoreCase=true";
        JdbcUrlDiagnostics.UrlDiagnostic diagnostic = JdbcUrlDiagnostics.inspect(url);
        assertEquals(url, diagnostic.original());
        assertTrue(diagnostic.warnings().stream().anyMatch(value -> value.contains("dbname=SYSTEM")));
        assertTrue(diagnostic.warnings().stream().anyMatch(value -> value.contains("schema=SYSTEM")));
    }

    @Test void warningsAndErrorsNeverExposePasswordLikeQueryValues() {
        String token = "do-not-disclose";
        String url = "jdbc:dm7://host:5236/SYSTEM?password=" + token + "&token=" + token;
        JdbcUrlDiagnostics.UrlDiagnostic diagnostic = JdbcUrlDiagnostics.inspect(url);
        assertEquals(url, diagnostic.original());
        assertTrue(diagnostic.warnings().stream().noneMatch(value -> value.contains(token)));
        assertFalse(JdbcUrlDiagnostics.redact(url).contains(token));
        assertFalse(JdbcUrlDiagnostics.redact("jdbc:dm7://host:5236?dbPassword=" + token).contains(token));
        assertFalse(JdbcUrlDiagnostics.redact("jdbc:dm7://host:5236?access_token=" + token).contains(token));
        assertFalse(JdbcUrlDiagnostics.redact("jdbc:dm7://host:5236?sslKeystorePass=" + token).contains(token));
        assertFalse(JdbcUrlDiagnostics.redact("jdbc:dm7://host:5236?uKeyPin=" + token).contains(token));
        assertFalse(JdbcUrlDiagnostics.redact("jdbc:dm7://host:5236?userNewPwd=" + token).contains(token));
    }
}
