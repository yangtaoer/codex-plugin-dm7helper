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
}
