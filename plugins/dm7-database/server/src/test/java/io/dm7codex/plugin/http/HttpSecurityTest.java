package io.dm7codex.plugin.http;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpSecurityTest {
    @Test void acceptsExactLoopbackAuthorityAndIntentionalOriginRules() {
        var security = new HttpSecurity(URI.create("http://127.0.0.1:43123"));
        assertTrue(security.validateAuthority("127.0.0.1:43123", null, "GET").isEmpty());
        assertTrue(security.validateAuthority("127.0.0.1:43123", "http://127.0.0.1:43123", "POST").isEmpty());
        assertEquals("INVALID_HOST", security.validateAuthority("evil.test:43123", null, "GET").orElseThrow());
        assertEquals("INVALID_HOST", security.validateAuthority("127.0.0.1:43123.evil", null, "GET").orElseThrow());
        assertEquals("INVALID_ORIGIN", security.validateAuthority("127.0.0.1:43123", null, "POST").orElseThrow());
        assertEquals("INVALID_ORIGIN", security.validateAuthority("127.0.0.1:43123", "http://evil.test", "POST").orElseThrow());
    }

    @Test void rejectsTraversalAndDoubleDecodeForms() {
        for (String path : new String[]{"/app/../secret", "/app/%2e%2e/secret", "/app/%252e%252e/secret",
                "/app/%2fsecret", "/app/%255csecret", "/app/\\secret", "/app/a%00b"}) {
            assertFalse(HttpSecurity.safePath(path), path);
        }
        assertTrue(HttpSecurity.safePath("/app/assets/main.js"));
        assertTrue(HttpSecurity.safePath("/app/sql"));
    }

    @Test void sessionsAreBoundScopedExpiringAndBounded() {
        var clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        var sessions = new HttpSecurity.BrowserSessions(clock, Duration.ofMinutes(15), 2, "process-a");
        String cookie = sessions.create("thread-a", "http://127.0.0.1:1");
        assertEquals("thread-a", sessions.authenticate(cookie, "http://127.0.0.1:1").orElseThrow());
        assertTrue(sessions.authenticate(cookie, "http://127.0.0.1:2").isEmpty());
        assertFalse(cookie.contains("thread-a"));
    }

    @Test void defaultSessionLifetimeUsesPersistentBrowserMaximum() {
        assertEquals(Integer.MAX_VALUE, HttpSecurity.BrowserSessions.DEFAULT_TTL.toSeconds());
    }

    @Test void securityHeadersAreStrictAndNoStore() {
        Map<String,String> headers = HttpSecurity.responseHeaders(true);
        assertEquals("no-referrer", headers.get("Referrer-Policy"));
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertEquals("no-store", headers.get("Cache-Control"));
        assertTrue(headers.get("Content-Security-Policy").contains("frame-ancestors 'none'"));
    }
}
