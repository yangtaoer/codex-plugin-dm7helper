package io.dm7codex.plugin.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.StateDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionInitializerTest {
    @TempDir
    Path tempDir;

    @Test
    void firstCallCreatesIndependentV001ActiveSql() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));

            var a = initializer.initialize(new SessionIdentity("thread-a", "codex_thread", "verified"));
            var b = initializer.initialize(new SessionIdentity("thread-b", "codex_thread", "verified"));

            assertEquals(1, a.version());
            assertEquals(1, b.version());
            assertEquals("unbound", a.databaseFingerprint());
            assertNotEquals(a.sessionId(), b.sessionId());
            assertNotEquals(a.activeSql(), b.activeSql());
            assertHeaderOnlyV001(a.activeSql());
            assertHeaderOnlyV001(b.activeSql());
        }
    }

    @Test
    void repeatedInitializationReturnsSameVersionWithoutTruncating() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));
            var identity = new SessionIdentity("thread-repeat", "codex_thread", "verified");
            var first = initializer.initialize(identity);
            Files.writeString(first.activeSql(), "-- sentinel\n", UTF_8, StandardOpenOption.APPEND);
            var expectedBytes = Files.readAllBytes(first.activeSql());

            var repeated = initializer.initialize(identity);

            assertEquals(first, repeated);
            assertArrayEquals(expectedBytes, Files.readAllBytes(repeated.activeSql()));
        }
    }

    @Test
    void unsafeThreadIdIsHashedAndNeverUsedAsPathText() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        var unsafeId = "../../outside\\..\\thread:?\nsecret";
        var expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(unsafeId.getBytes(UTF_8)));

        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));
            var session = initializer.initialize(
                    new SessionIdentity(unsafeId, "codex_thread", "verified"));

            assertEquals(expectedHash, session.externalIdHash());
            assertEquals(expectedHash, session.activeSql().getParent().getFileName().toString());
            assertTrue(session.activeSql().normalize().startsWith(paths.sessionsDirectory()));
            assertFalse(session.activeSql().toString().contains(unsafeId));
        }
    }

    @Test
    void concurrentInitializationCreatesOneSessionAndOneHeader() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));
            var identity = new SessionIdentity("thread-concurrent", "codex_thread", "verified");
            var executor = Executors.newFixedThreadPool(8);
            try {
                var futures = executor.invokeAll(IntStream.range(0, 16)
                        .<java.util.concurrent.Callable<SessionState>>mapToObj(
                                ignored -> () -> initializer.initialize(identity))
                        .toList());
                var states = new java.util.HashSet<SessionState>();
                for (var future : futures) {
                    states.add(future.get());
                }

                assertEquals(1, states.size());
                assertHeaderOnlyV001(states.iterator().next().activeSql());
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static void assertHeaderOnlyV001(Path activeSql) throws Exception {
        var bytes = Files.readAllBytes(activeSql);
        assertTrue(bytes.length >= 2);
        assertArrayEquals(new byte[] {'-', '-'}, Arrays.copyOf(bytes, 2));
        assertFalse(bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF);

        var header = Files.readString(activeSql, UTF_8);
        assertTrue(header.contains("version: v001"));
        assertTrue(header.contains("database-fingerprint: unbound"));
        assertTrue(header.lines().allMatch(line -> line.startsWith("--")), header);
    }
}
