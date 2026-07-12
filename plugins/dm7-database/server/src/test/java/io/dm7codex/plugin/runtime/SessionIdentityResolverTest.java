package io.dm7codex.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionIdentityResolverTest {
    @Test
    void codexThreadIdIsPreservedAsVerifiedIdentity() {
        var identity = SessionIdentityResolver.resolve(Map.of("CODEX_THREAD_ID", "thread-123"));

        assertEquals("thread-123", identity.externalId());
        assertEquals("codex_thread", identity.source());
        assertEquals("verified", identity.isolation());
    }

    @Test
    void absentThreadIdUsesStableProcessFallbackIsolation() {
        var first = SessionIdentityResolver.resolve(Map.of());
        var second = SessionIdentityResolver.resolve(Map.of("CODEX_THREAD_ID", "  "));

        assertFalse(first.externalId().isBlank());
        assertEquals(first.externalId(), second.externalId());
        assertEquals("process_uuid", first.source());
        assertEquals("process_fallback", first.isolation());
    }

    @Test
    void nestedMcpMetadataProvidesVerifiedThreadIdentity() {
        var identity = SessionIdentityResolver.resolve(Map.of(), Map.of(
                "openai", Map.of("thread_id", "019f5486-cd45-77b2-ba7d-9df2619fb30b")));

        assertEquals("019f5486-cd45-77b2-ba7d-9df2619fb30b", identity.externalId());
        assertEquals("mcp_request_meta", identity.source());
        assertEquals("verified", identity.isolation());
    }

    @Test
    void unrelatedMetadataCannotOverrideProcessFallback() {
        var identity = SessionIdentityResolver.resolve(Map.of(), Map.of(
                "progressToken", "019f5486-cd45-77b2-ba7d-9df2619fb30b",
                "thread_id", "019f5486-cd45-77b2-ba7d-9df2619fb30b",
                "client", Map.of("sessionId", "019f5486-cd45-77b2-ba7d-9df2619fb30b")));

        assertEquals("process_uuid", identity.source());
        assertEquals("process_fallback", identity.isolation());
    }

    @Test
    void malformedOrAmbiguousOpenAiMetadataIsNotVerified() {
        var malformed = SessionIdentityResolver.resolve(Map.of(), Map.of(
                "openai", Map.of("thread_id", "not-a-codex-thread")));
        var ambiguous = SessionIdentityResolver.resolve(Map.of(), Map.of(
                "openai", Map.of("thread_id", "019f5486-cd45-77b2-ba7d-9df2619fb30b"),
                "OpenAI", Map.of("thread_id", "019f5486-cd45-77b2-ba7d-9df2619fb30c")));

        assertEquals("process_fallback", malformed.isolation());
        assertEquals("process_fallback", ambiguous.isolation());
    }

    @Test
    void explicitCodexEnvironmentIdentityTakesPrecedenceOverRequestMetadata() {
        var identity = SessionIdentityResolver.resolve(
                Map.of("CODEX_THREAD_ID", "environment-thread"),
                Map.of("openai", Map.of("thread_id", "019f5486-cd45-77b2-ba7d-9df2619fb30b")));

        assertEquals("environment-thread", identity.externalId());
        assertEquals("codex_thread", identity.source());
        assertEquals("verified", identity.isolation());
    }
}
