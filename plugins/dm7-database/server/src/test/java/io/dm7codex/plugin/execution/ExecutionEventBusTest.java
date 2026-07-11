package io.dm7codex.plugin.execution;

import static io.dm7codex.plugin.execution.ExecutionModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.sql.SQLException;
import io.dm7codex.plugin.connection.DmConnectionFactory;
import io.dm7codex.plugin.sql.*;

class ExecutionEventBusTest {
    @Test void sequencesAreMonotonicAndSessionIsolated() {
        var bus = new ExecutionEventBus(2);
        UUID id = UUID.randomUUID();
        bus.publish("a", id, ExecutionStatus.QUEUED, Instant.now(), null);
        bus.publish("b", id, ExecutionStatus.QUEUED, Instant.now(), null);
        bus.publish("a", id, ExecutionStatus.CONNECTING, Instant.now(), null);
        assertEquals(2, bus.events("a", 0).size());
        assertTrue(bus.events("a", 0).get(1).sequence() > bus.events("a", 0).get(0).sequence());
        assertEquals(1, bus.events("b", 0).size());
    }

    @Test void queryPublishesExactApplicableOrder() {
        var opener = new TestJdbc.Opener().queryRows(List.of(List.of("一")), List.of("V"));
        try (var service = TestJdbc.service(opener)) {
            service.query(TestJdbc.session(), new QueryCommand(UUID.randomUUID(),
                    "SELECT V FROM T", 1, 100, 30));
            assertEquals(List.of(ExecutionStatus.QUEUED, ExecutionStatus.CONNECTING,
                    ExecutionStatus.PARSING, ExecutionStatus.EXECUTING, ExecutionStatus.COMPLETED),
                    service.events("session", 0).stream().map(ExecutionEvent::status).toList());
        }
    }

    @Test void boundedExecutorRejectsWhenWorkerAndQueueAreFull() throws Exception {
        var gate = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var opener = new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID id) throws SQLException {
                entered.countDown();
                try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new TestJdbc.Opener().open(id);
            }
        };
        try (var service = new ExecutionService(opener, new DmSqlParser(), new SqlSecurityPolicy(),
                null, null, new ExecutionEventBus(20), new ExecutionRegistry(), 1, 1)) {
            var command = new QueryCommand(UUID.randomUUID(), "SELECT 1", 1, 100, 30);
            var first = CompletableFuture.supplyAsync(() -> service.query(TestJdbc.session(), command));
            entered.await();
            var second = CompletableFuture.supplyAsync(() -> service.query(TestJdbc.session(), command));
            Thread.sleep(50);
            assertThrows(ExecutionQueueFullException.class,
                    () -> service.query(TestJdbc.session(), command));
            gate.countDown();
            first.join(); second.join();
        }
    }

    @Test void atomicMutationPublishesExactApplicableOrder() {
        var opener = new TestJdbc.Opener().failOnStatement(Integer.MAX_VALUE);
        try (var service = TestJdbc.service(opener)) {
            service.execute(TestJdbc.session(), new ExecuteCommand(UUID.randomUUID(),
                    "UPDATE T SET C=1", SqlPurpose.TEST, true, false, 30));
            assertEquals(List.of(ExecutionStatus.QUEUED, ExecutionStatus.CONNECTING,
                    ExecutionStatus.PARSING, ExecutionStatus.EXECUTING,
                    ExecutionStatus.COMMITTING, ExecutionStatus.LOGGING,
                    ExecutionStatus.COMPLETED), service.events("session", 0).stream()
                    .map(ExecutionEvent::status).toList());
        }
    }

    @Test void atomicRollbackEndsAtFailedWithoutLogging() {
        var opener = new TestJdbc.Opener().failOnStatement(1);
        try (var service = TestJdbc.service(opener)) {
            service.execute(TestJdbc.session(), new ExecuteCommand(UUID.randomUUID(),
                    "UPDATE T SET C=1", SqlPurpose.TEST, true, false, 30));
            assertEquals(List.of(ExecutionStatus.QUEUED, ExecutionStatus.CONNECTING,
                    ExecutionStatus.PARSING, ExecutionStatus.EXECUTING, ExecutionStatus.FAILED),
                    service.events("session", 0).stream().map(ExecutionEvent::status).toList());
        }
    }
}
