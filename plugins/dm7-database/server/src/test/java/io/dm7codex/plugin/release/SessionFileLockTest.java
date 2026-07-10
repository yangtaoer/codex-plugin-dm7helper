package io.dm7codex.plugin.release;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionInitializer;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.StateDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SessionFileLockTest {
    @TempDir
    Path tempDir;

    @Test
    void timesOutOnOverlappingLockWithoutLeakingSensitiveDetails() throws Exception {
        var fixture = fixture("overlap");
        try (fixture.database;
                var held = SessionFileLock.acquire(fixture.paths, fixture.session, Duration.ofSeconds(1))) {
            var thrown = assertThrows(ReleaseExportLockTimeout.class,
                    () -> SessionFileLock.acquire(fixture.paths, fixture.session,
                            Duration.ofMillis(80)));
            assertFalse(thrown.getMessage().contains(fixture.session.sessionId()));
            assertFalse(thrown.getMessage().contains(fixture.session.activeSql().toString()));
        }
    }

    @Test
    void rejectsAnExternalOrTamperedActivePathBeforeCreatingLock() throws Exception {
        var fixture = fixture("path");
        try (fixture.database) {
            var outside = tempDir.resolve("outside.sql").toAbsolutePath();
            var tampered = new SessionState(fixture.session.sessionId(), fixture.session.externalIdHash(),
                    fixture.session.version(), "unbound", outside, fixture.session.createdAt());
            assertThrows(IllegalStateException.class,
                    () -> SessionFileLock.acquire(fixture.paths, tampered, Duration.ofMillis(50)));
            assertFalse(Files.exists(outside.resolveSibling("active.lock")));
        }
    }

    @Test
    void independentJvmCannotBypassLockAndCanAcquireAfterHolderCloses() throws Exception {
        var fixture = fixture("process");
        try (fixture.database;
                var held = SessionFileLock.acquire(fixture.paths, fixture.session, Duration.ofSeconds(1))) {
            var blocked = startProbe(fixture, 100);
            assertTrue(blocked.waitFor(10, TimeUnit.SECONDS));
            assertTrue(blocked.exitValue() != 0, processOutput(blocked));
        }
        var acquired = startProbe(fixture, 2_000);
        assertTrue(acquired.waitFor(10, TimeUnit.SECONDS));
        assertTrue(acquired.exitValue() == 0, processOutput(acquired));
    }

    @Test
    void interruptedWaitRestoresInterruptFlag() throws Exception {
        var fixture = fixture("interrupt");
        try (fixture.database;
                var held = SessionFileLock.acquire(fixture.paths, fixture.session, Duration.ofSeconds(1))) {
            var result = new boolean[1];
            var waiter = new Thread(() -> {
                try {
                    SessionFileLock.acquire(fixture.paths, fixture.session, Duration.ofSeconds(5));
                } catch (Exception expected) {
                    result[0] = Thread.currentThread().isInterrupted();
                }
            });
            waiter.start();
            Thread.sleep(50);
            waiter.interrupt();
            waiter.join(2_000);
            assertFalse(waiter.isAlive());
            assertTrue(result[0]);
        }
    }

    @Test
    void sameRootSessionJunctionCannotRedirectLockOrActiveContentToAnotherSession()
            throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve("same-root-junction"));
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var initializer = new SessionInitializer(
                    paths, new SessionRepository(database, paths.sessionsDirectory()));
            var a = initializer.initialize(
                    new SessionIdentity("junction-a", "test_override", "verified"));
            var b = initializer.initialize(
                    new SessionIdentity("junction-b", "test_override", "verified"));
            var bBytes = Files.readAllBytes(b.activeSql());
            var bLock = b.activeSql().resolveSibling("active.lock");
            Files.delete(a.activeSql());
            Files.delete(a.activeSql().getParent());
            createDirectoryReparsePoint(a.activeSql().getParent(), b.activeSql().getParent());

            assertThrows(IllegalStateException.class,
                    () -> SessionFileLock.acquire(paths, a, Duration.ofMillis(100)));

            assertArrayEquals(bBytes, Files.readAllBytes(b.activeSql()));
            assertFalse(Files.exists(bLock));
        }
    }

    @Test
    void sessionsRootReparsePointIsRejectedWithoutTouchingSessionFiles() throws Exception {
        var paths = RuntimePaths.forTest(tempDir.resolve("root-junction"));
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var session = new SessionInitializer(
                            paths, new SessionRepository(database, paths.sessionsDirectory()))
                    .initialize(new SessionIdentity(
                            "root-junction-session", "test_override", "verified"));
            var bytes = Files.readAllBytes(session.activeSql());
            var actualSessions = paths.pluginData().resolve("actual-sessions");
            Files.move(paths.sessionsDirectory(), actualSessions);
            createDirectoryReparsePoint(paths.sessionsDirectory(), actualSessions);

            assertThrows(IllegalStateException.class,
                    () -> SessionFileLock.acquire(paths, session, Duration.ofMillis(100)));
            assertArrayEquals(bytes, Files.readAllBytes(
                    actualSessions.resolve(session.externalIdHash()).resolve("active.sql")));
        }
    }

    @Test
    void missingPosixAndAclViewsFailClosedWithoutChangingFile() throws Exception {
        var file = tempDir.resolve("permission-sentinel.txt");
        Files.writeString(file, "must survive");
        var bytes = Files.readAllBytes(file);

        var thrown = assertThrows(java.io.IOException.class,
                () -> SessionFileLock.applyOwnerOnly(file, false, null, null));

        assertEquals("Owner-only permissions are unavailable", thrown.getMessage());
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    public static void main(String[] args) throws Exception {
        var pluginData = Path.of(args[0]);
        var paths = RuntimePaths.forTest(pluginData);
        try (var database = StateDatabase.open(paths.stateDatabase())) {
            var sessions = new SessionRepository(database, paths.sessionsDirectory());
            var session = new SessionInitializer(paths, sessions)
                    .initialize(new SessionIdentity(args[1], "test_override", "verified"));
            try (var ignored = SessionFileLock.acquire(
                    paths, session, Duration.ofMillis(Long.parseLong(args[2])))) {
                // Acquiring the lock is the process contract.
            }
        }
    }

    private Fixture fixture(String name) throws Exception {
        var pluginData = tempDir.resolve(name).toAbsolutePath();
        var paths = RuntimePaths.forTest(pluginData);
        var database = StateDatabase.open(paths.stateDatabase());
        var externalId = "thread-" + name;
        var session = new SessionInitializer(paths,
                        new SessionRepository(database, paths.sessionsDirectory()))
                .initialize(new SessionIdentity(externalId, "test_override", "verified"));
        return new Fixture(pluginData, paths, database, session, externalId);
    }

    private static Process startProbe(Fixture fixture, long timeoutMillis) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        return new ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"),
                        SessionFileLockTest.class.getName(), fixture.pluginData.toString(),
                        fixture.externalId, Long.toString(timeoutMillis))
                .redirectErrorStream(true)
                .start();
    }

    private static String processOutput(Process process) throws Exception {
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void createDirectoryReparsePoint(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("windows")) {
            var process = new ProcessBuilder(
                    "cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "mklink did not finish");
            assertEquals(0, process.exitValue(), processOutput(process));
            assertTrue(Files.exists(link), "junction was not created");
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private record Fixture(Path pluginData, RuntimePaths paths, StateDatabase database,
                           SessionState session, String externalId) {}
}
