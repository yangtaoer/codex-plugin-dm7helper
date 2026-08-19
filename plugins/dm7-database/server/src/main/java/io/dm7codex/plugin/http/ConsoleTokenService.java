package io.dm7codex.plugin.http;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Issues bounded, effectively non-expiring, reusable bearer tokens for console redemption. */
public final class ConsoleTokenService {
    static final Duration DEFAULT_TTL = Duration.ofSeconds(Integer.MAX_VALUE);
    private final Clock clock;
    private final Duration ttl;
    private final int capacity;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<>();

    public ConsoleTokenService() { this(Clock.systemUTC(), DEFAULT_TTL, 10_000); }
    ConsoleTokenService(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(DEFAULT_TTL) > 0)
            throw new IllegalArgumentException("invalid token lifetime");
        if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("invalid token capacity");
        this.ttl = ttl; this.capacity = capacity;
    }

    public synchronized String issue(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 512)
            throw new IllegalArgumentException("invalid session id");
        cleanup();
        byte[] value = new byte[32];
        String token;
        do { random.nextBytes(value); token = Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
        while (pending.containsKey(token));
        pending.put(token, new Entry(sessionId, clock.instant().plus(ttl)));
        while (pending.size() > capacity) pending.remove(pending.keySet().iterator().next());
        return token;
    }

    public synchronized Optional<String> consume(String token) {
        cleanup();
        if (token == null || !token.matches("[A-Za-z0-9_-]{40,128}")) return Optional.empty();
        Entry entry = pending.get(token);
        return entry == null || !entry.expiresAt().isAfter(clock.instant())
                ? Optional.empty() : Optional.of(entry.sessionId());
    }

    synchronized int pendingCount() { cleanup(); return pending.size(); }
    private void cleanup() { pending.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant())); }
    private record Entry(String sessionId, Instant expiresAt) {}
}
