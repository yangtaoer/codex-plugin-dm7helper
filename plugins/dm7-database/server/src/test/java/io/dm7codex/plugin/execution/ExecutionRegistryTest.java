package io.dm7codex.plugin.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
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
        assertTrue(statement.isClosed());
        assertTrue(connection.isClosed());
        assertTrue(registry.cancel(id));
    }

    @Test void attachedCancelForcesCloseAfterGracePeriod() throws Exception {
        var statement = TestJdbc.statement();
        var connection = TestJdbc.connection(statement);
        try (var registry = new ExecutionRegistry()) {
            UUID id = UUID.randomUUID();
            registry.register(id);
            registry.attach(id, connection, statement);
            assertTrue(registry.cancel(id));
            assertFalse(connection.isClosed());
            Thread.sleep(2_200);
            assertTrue(statement.isClosed());
            assertTrue(connection.isClosed());
        }
    }
}
