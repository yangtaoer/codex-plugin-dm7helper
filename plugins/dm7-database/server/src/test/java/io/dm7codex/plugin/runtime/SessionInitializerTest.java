package io.dm7codex.plugin.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.StateDatabase;
import java.io.IOException;
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

    @Test
    void creatorFailureAfterCreatingFileIsCleanedAndCanRetry() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        var identity = new SessionIdentity("thread-creator-failure", "codex_thread", "verified");
        var externalIdHash = sha256(identity.externalId());
        var activeSql = paths.sessionsDirectory().resolve(externalIdHash).resolve("active.sql");

        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var repository = new SessionRepository(database);
            org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> repository.initialize(
                    identity,
                    externalIdHash,
                    activeSql,
                    file -> {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, "-- partial\n", UTF_8, StandardOpenOption.CREATE_NEW);
                        throw new IOException("simulated creator failure");
                    }));

            assertFalse(Files.exists(activeSql));
            var recovered = new SessionInitializer(paths, repository).initialize(identity);
            assertHeaderOnlyV001(recovered.activeSql());
        }
    }

    @Test
    void orphanFromInterruptedInitializationIsRecoveredByNewRepository() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        var identity = new SessionIdentity("thread-orphan", "codex_thread", "verified");
        var orphan = paths.sessionsDirectory()
                .resolve(sha256(identity.externalId()))
                .resolve("active.sql");
        try (var initialDatabase = StateDatabase.open(paths.stateDatabase())) {
            Files.createDirectories(orphan.getParent());
            Files.writeString(orphan, "-- interrupted before commit\n", UTF_8, StandardOpenOption.CREATE_NEW);
        }

        try (var restartedDatabase = StateDatabase.open(paths.stateDatabase())) {
            var recovered = new SessionInitializer(
                            paths, new SessionRepository(restartedDatabase))
                    .initialize(identity);

            assertEquals(orphan, recovered.activeSql());
            assertHeaderOnlyV001(orphan);
            assertFalse(Files.readString(orphan, UTF_8).contains("interrupted"));
        }
    }

    @Test
    void existingSessionRejectsDatabasePathThatDiffersFromComputedPath() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        var identity = new SessionIdentity("thread-path-tamper", "codex_thread", "verified");
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));
            var session = initializer.initialize(identity);
            Files.writeString(session.activeSql(), "-- preserved\n", UTF_8, StandardOpenOption.APPEND);
            var expectedBytes = Files.readAllBytes(session.activeSql());
            var untrustedPath = tempDir.resolveSibling("untrusted-active.sql").toAbsolutePath();
            try (var connection = database.openConnection();
                    var update = connection.prepareStatement(
                            "UPDATE release_version SET active_sql = ? WHERE session_id = ?")) {
                update.setString(1, untrustedPath.toString());
                update.setString(2, session.sessionId());
                update.executeUpdate();
            }

            org.junit.jupiter.api.Assertions.assertThrows(
                    java.sql.SQLException.class, () -> initializer.initialize(identity));
            assertArrayEquals(expectedBytes, Files.readAllBytes(session.activeSql()));
            assertFalse(Files.exists(untrustedPath));
        }
    }

    @Test
    void orphanRecoveryDoesNotDeletePathReferencedWithEquivalentRelativeText() throws Exception {
        var paths = RuntimePaths.forTest(tempDir);
        var identity = new SessionIdentity("thread-equivalent-path", "codex_thread", "verified");
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(paths, new SessionRepository(database));
            var session = initializer.initialize(identity);
            Files.writeString(session.activeSql(), "-- must survive\n", UTF_8, StandardOpenOption.APPEND);
            var expectedBytes = Files.readAllBytes(session.activeSql());
            var relativeEquivalent = Path.of("")
                    .toAbsolutePath()
                    .normalize()
                    .relativize(session.activeSql());
            try (var connection = database.openConnection();
                    var detachIdentity = connection.prepareStatement(
                            "UPDATE logical_session SET external_id_hash = ? WHERE session_id = ?");
                    var rewritePath = connection.prepareStatement(
                            "UPDATE release_version SET active_sql = ? WHERE session_id = ?")) {
                detachIdentity.setString(1, "detached-external-id-hash");
                detachIdentity.setString(2, session.sessionId());
                detachIdentity.executeUpdate();
                rewritePath.setString(1, relativeEquivalent.toString());
                rewritePath.setString(2, session.sessionId());
                rewritePath.executeUpdate();
            }

            org.junit.jupiter.api.Assertions.assertThrows(
                    java.sql.SQLException.class, () -> initializer.initialize(identity));
            assertArrayEquals(expectedBytes, Files.readAllBytes(session.activeSql()));
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

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)));
    }
}
