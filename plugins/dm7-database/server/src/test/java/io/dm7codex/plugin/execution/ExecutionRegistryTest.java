package io.dm7codex.plugin.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import io.dm7codex.plugin.execution.ExecutionModels.ExecutionStatus;
import org.junit.jupiter.api.Test;

class ExecutionRegistryTest {
    @Test void repeatedForceCloseUsesOneIndependentTaskPerResourceAndContainsCloseFailures() throws Exception {
        var released = new CountDownLatch(1);
        var statementCloses = new AtomicInteger(); var connectionCloses = new AtomicInteger();
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "close" -> { statementCloses.incrementAndGet(); released.await(); yield null; }
                    default -> null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                    case "close" -> {
                        connectionCloses.incrementAndGet(); released.countDown();
                        throw new java.sql.SQLException("expected close failure");
                    }
                    default -> null;
                });
        var registry = new ExecutionRegistry(); UUID id = UUID.randomUUID();
        registry.register(id); registry.attach(id, connection, statement);
        for (int attempt = 0; attempt < 20; attempt++) registry.forceClose(id);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while ((statementCloses.get() == 0 || connectionCloses.get() == 0) && System.nanoTime() < deadline)
            Thread.onSpinWait();
        registry.complete(id); registry.close();
        assertEquals(1, statementCloses.get()); assertEquals(1, connectionCloses.get());
        assertEquals(0, registry.activeCount());
    }

    @Test void cancelBeforeAttachCancelsAndClosesAttachedResources() throws Exception {
        var statement = TestJdbc.statement();
        var connection = TestJdbc.connection(statement);
        var registry = new ExecutionRegistry();
        UUID id = UUID.randomUUID();
        assertTrue(registry.register(id));
        assertTrue(registry.cancel(id));
        registry.forceClose(id); // no resources yet; must not consume the later close
        registry.attach(id, connection, statement);
        long cancelDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (TestJdbc.cancelCount(statement) == 0 && System.nanoTime() < cancelDeadline) Thread.onSpinWait();
        assertEquals(1, TestJdbc.cancelCount(statement));
        assertFalse(statement.isClosed());
        assertFalse(connection.isClosed());
        assertTrue(registry.cancel(id));
        assertEquals(1, TestJdbc.cancelCount(statement));
        Thread.sleep(2_200);
        assertTrue(statement.isClosed());
        assertTrue(connection.isClosed());
    }

    @Test void terminalClaimAtomicallyResolvesCancellationRace() {
        try (var registry = new ExecutionRegistry()) {
            UUID completed = UUID.randomUUID(); registry.register(completed);
            assertTrue(registry.claimTerminal(completed));
            assertFalse(registry.cancel(completed));
            UUID cancelled = UUID.randomUUID(); registry.register(cancelled);
            assertTrue(registry.cancel(cancelled));
            assertFalse(registry.claimTerminal(cancelled));
        }
    }

    @Test void terminalClaimAtomicallySnapshotsFirstCancellationReason() {
        try (var registry = new ExecutionRegistry()) {
            UUID timeout = UUID.randomUUID(); registry.register(timeout); registry.timeout(timeout);
            var timeoutClaim = registry.claimTerminalSnapshot(timeout, ExecutionStatus.COMPLETED);
            assertEquals(ExecutionStatus.FAILED, timeoutClaim.status()); assertTrue(timeoutClaim.timedOut());
            assertTrue(timeoutClaim.firstClaim());
            assertFalse(registry.cancel(timeout));
            var repeatedTimeoutClaim = registry.claimTerminalSnapshot(timeout, ExecutionStatus.COMPLETED);
            assertEquals(timeoutClaim.status(), repeatedTimeoutClaim.status());
            assertEquals(timeoutClaim.timedOut(), repeatedTimeoutClaim.timedOut());
            assertFalse(repeatedTimeoutClaim.firstClaim());

            UUID user = UUID.randomUUID(); registry.register(user); registry.cancel(user); registry.timeout(user);
            var userClaim = registry.claimTerminalSnapshot(user, ExecutionStatus.FAILED);
            assertEquals(ExecutionStatus.CANCELLED, userClaim.status()); assertFalse(userClaim.timedOut());
            assertTrue(userClaim.firstClaim());
        }
    }

    @Test void completedEntryRejectsLateWorkerTerminalClaimWithoutTombstone() {
        try (var registry = new ExecutionRegistry()) {
            UUID id = UUID.randomUUID(); registry.register(id); registry.timeout(id);
            var finalizer = registry.claimTerminalSnapshot(id, ExecutionStatus.FAILED);
            assertTrue(finalizer.firstClaim()); assertTrue(finalizer.timedOut());
            registry.complete(id);
            var lateWorker = registry.claimTerminalSnapshot(id, ExecutionStatus.COMPLETED);
            assertFalse(lateWorker.firstClaim());
            assertEquals(0, registry.activeCount());
        }
    }

    @Test void blockingCancelCannotStarveForceCloseExecutor() throws Exception {
        var releaseCancel = new CountDownLatch(1);
        var closeCalled = new CountDownLatch(1);
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{Statement.class}, (p, m, a) -> switch (m.getName()) {
                    case "cancel" -> { try { releaseCancel.await(); } catch (InterruptedException ignored) { } yield null; }
                    case "close" -> { closeCalled.countDown(); yield null; }
                    default -> null;
                });
        Connection connection = TestJdbc.connection(statement);
        var registry = new ExecutionRegistry();
        try {
            UUID id = UUID.randomUUID(); registry.register(id); registry.attach(id, connection, statement);
            registry.cancel(id);
            assertTrue(closeCalled.await(3, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            releaseCancel.countDown();
            registry.close();
        }
    }

    @Test void attachedCancelForcesCloseAfterGracePeriod() throws Exception {
        var statement = TestJdbc.statement();
        var connection = TestJdbc.connection(statement);
        try (var registry = new ExecutionRegistry()) {
            UUID id = UUID.randomUUID();
            registry.register(id);
            registry.attach(id, connection, statement);
            assertTrue(registry.cancel(id));
            assertTrue(registry.cancel(id));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            while (TestJdbc.cancelCount(statement) == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, TestJdbc.cancelCount(statement));
            assertFalse(connection.isClosed());
            Thread.sleep(2_200);
            assertTrue(statement.isClosed());
            assertTrue(connection.isClosed());
        }
    }
}
