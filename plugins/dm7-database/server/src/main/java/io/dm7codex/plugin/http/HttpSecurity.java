package io.dm7codex.plugin.http;

import java.net.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

public final class HttpSecurity {
    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'";
    private final String authority;
    private final String origin;

    public HttpSecurity(URI baseUri) {
        Objects.requireNonNull(baseUri);
        InetAddress address;
        try { address = InetAddress.getByName(baseUri.getHost()); }
        catch (Exception e) { throw new IllegalArgumentException("invalid bind address"); }
        if (!address.isLoopbackAddress() || baseUri.getPort() < 1) throw new IllegalArgumentException("loopback required");
        this.authority = baseUri.getHost() + ":" + baseUri.getPort();
        this.origin = "http://" + authority;
    }

    public Optional<String> validateAuthority(String host, String suppliedOrigin, String method) {
        if (!authority.equals(host)) return Optional.of("INVALID_HOST");
        boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (suppliedOrigin == null || suppliedOrigin.isBlank()) return safe ? Optional.empty() : Optional.of("INVALID_ORIGIN");
        return origin.equals(suppliedOrigin) ? Optional.empty() : Optional.of("INVALID_ORIGIN");
    }

    public String origin() { return origin; }

    public static boolean safePath(String rawPath) {
        if (rawPath == null || rawPath.indexOf('\0') >= 0 || rawPath.indexOf('\\') >= 0) return false;
        String current = rawPath;
        for (int i = 0; i < 2; i++) {
            String lower = current.toLowerCase(Locale.ROOT);
            if (lower.contains("%00") || lower.contains("%2f") || lower.contains("%5c")) return false;
            try { current = URLDecoder.decode(current, java.nio.charset.StandardCharsets.UTF_8); }
            catch (IllegalArgumentException invalid) { return false; }
            if (current.indexOf('\\') >= 0 || current.indexOf('\0') >= 0) return false;
        }
        for (String segment : current.split("/", -1)) if (segment.equals(".") || segment.equals("..")) return false;
        return current.startsWith("/");
    }

    public static Map<String,String> responseHeaders(boolean noStore) {
        var headers = new LinkedHashMap<String,String>();
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Content-Security-Policy", CSP);
        headers.put("Cache-Control", noStore ? "no-store" : "no-cache");
        return Collections.unmodifiableMap(headers);
    }

    public static final class BrowserSessions {
        private final Clock clock; private final Duration ttl; private final int capacity; private final String processId;
        private final SecureRandom random = new SecureRandom();
        private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>(16, .75f, true);
        public BrowserSessions() { this(Clock.systemUTC(), Duration.ofHours(8), 128,
                Long.toUnsignedString(ProcessHandle.current().pid(), 36)); }
        BrowserSessions(Clock clock, Duration ttl, int capacity, String processId) {
            this.clock=clock; this.ttl=ttl; this.capacity=capacity; this.processId=processId;
        }
        public synchronized String create(String sessionId, String origin) {
            cleanup(); byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            sessions.put(id, new Session(sessionId, origin, processId, clock.instant().plus(ttl)));
            while (sessions.size() > capacity) sessions.remove(sessions.keySet().iterator().next());
            return id;
        }
        public synchronized Optional<String> authenticate(String id, String origin) {
            cleanup(); Session value = sessions.get(id);
            return value != null && value.origin().equals(origin) && value.processId().equals(processId)
                    ? Optional.of(value.sessionId()) : Optional.empty();
        }
        private void cleanup() { sessions.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant())); }
        private record Session(String sessionId, String origin, String processId, Instant expiresAt) {}
    }
}
