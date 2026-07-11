package io.dm7codex.plugin.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class ExecutionRegistryTest {
    @Test void cancelBeforeAttachCancelsAndClosesAttachedResources() throws Exception {
        var statement = TestJdbc.statement();
        var connection = TestJdbc.connection(statement);
        var registry = new ExecutionRegistry();
        UUID id = UUID.randomUUID();
        assertTrue(registry.register(id));
        assertTrue(registry.cancel(id));
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
