package io.dm7codex.plugin.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SqlSummaryRedactorTest {
    @Test void redactsDmLiteralAndCommentFormsWithoutLosingIdentifiers() {
        var sql = "/* hidden-a */ UPDATE \"中文表\" SET A='hidden-b', B=N'hidden-c', "
                + "C=X'4142', D=0xCAFE, E=q'[hidden-d]' -- hidden-e\n WHERE ID=42";
        var value = new SqlSummaryRedactor(240, 512).summarize(sql);
        assertTrue(value.contains("UPDATE \"中文表\" SET"));
        assertTrue(value.contains("WHERE ID=42"));
        for (var secret : new String[]{"hidden-a","hidden-b","hidden-c","4142","CAFE","hidden-d","hidden-e"})
            assertFalse(value.contains(secret), value);
    }

    @Test void malformedInputNeverLeaksLiteralBodiesAndIsUnicodeBounded() {
        var marker = "VERY_SECRET_MARKER";
        var value = new SqlSummaryRedactor(24, 48).summarize(
                "INSERT INTO T VALUES ('" + marker + "🚀🚀🚀); /* unterminated");
        assertFalse(value.contains(marker));
        assertTrue(value.codePointCount(0, value.length()) <= 24);
        assertTrue(value.getBytes(StandardCharsets.UTF_8).length <= 48);
        assertFalse(value.endsWith("\uD83D"));
    }

    @Test void nullControlsAndAdversarialQuotesAreSafe() {
        assertDoesNotThrow(() -> new SqlSummaryRedactor(80, 160).summarize("SELECT '\u0000secret''still-secret"));
        var value = new SqlSummaryRedactor(80, 160).summarize("SELECT '\u0000secret''still-secret");
        assertFalse(value.contains("secret"));
        assertFalse(value.contains("\u0000"));
    }

    @Test void quotedIdentifiersHonorEscapesAndUnterminatedIdentifiersFailClosed() {
        var redactor=new SqlSummaryRedactor(120,240);
        assertEquals("SELECT \"A\"\"B\" FROM T",redactor.summarize("SELECT \"A\"\"B\" FROM T"));
        assertEquals("?",redactor.summarize("SELECT \"NEVER_LEAK_IDENTIFIER"));
    }

    @Test void markerFuzzNeverLeaksMalformedQuotedContent() {
        var redactor=new SqlSummaryRedactor(80,160);
        for(int i=0;i<200;i++){
            String marker="MARKER_"+i+"_SECRET";
            for(String sql:new String[]{"SELECT \""+marker,"SELECT '"+marker,"SELECT q'["+marker,
                    "UPDATE T SET C=N'"+marker+" /* 控\u0000制 -- "+marker}){
                String summary=redactor.summarize(sql);
                assertFalse(summary.contains(marker),summary);
                assertFalse(summary.contains("\u0000"),summary);
            }
        }
    }
}
