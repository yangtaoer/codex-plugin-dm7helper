package io.dm7codex.plugin.http;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConsoleTokenServiceTest {
    @Test void tokenIsOpaqueSingleUseAndExpires() {
        var clock = new MutableClock();
        var tokens = new ConsoleTokenService(clock, Duration.ofSeconds(30), 8);
        String token = tokens.issue("thread-中文");
        assertTrue(token.matches("[A-Za-z0-9_-]{40,}"));
        assertEquals("thread-中文", tokens.consume(token).orElseThrow());
        assertTrue(tokens.consume(token).isEmpty());
        String expired = tokens.issue("expired");
        clock.advance(Duration.ofSeconds(31));
        assertTrue(tokens.consume(expired).isEmpty());
        assertTrue(tokens.consume("bad token").isEmpty());
    }

    @Test void concurrentConsumptionHasExactlyOneWinner() throws Exception {
        var tokens = new ConsoleTokenService();
        String token = tokens.issue("thread-a");
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(20);
        try {
            var calls = java.util.stream.IntStream.range(0, 20).mapToObj(i -> pool.submit(() -> {
                gate.await(); return tokens.consume(token).isPresent();
            })).toList();
            gate.countDown();
            assertEquals(1, calls.stream().filter(f -> {
                try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); }
            }).count());
        } finally { pool.shutdownNow(); }
    }

    @Test void boundedMapEvictsOldestIssuedToken() {
        var tokens = new ConsoleTokenService(new MutableClock(), Duration.ofMinutes(1), 2);
        String first = tokens.issue("a");
        tokens.issue("b"); tokens.issue("c");
        assertTrue(tokens.consume(first).isEmpty());
        assertEquals(2, tokens.pendingCount());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
