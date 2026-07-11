package io.dm7codex.plugin.execution;

public final class ExecutionCleanupTimeoutException extends IllegalStateException {
    public ExecutionCleanupTimeoutException() { super("JDBC cleanup timed out; process restart is required"); }
    public boolean restartRequired() { return true; }
}
