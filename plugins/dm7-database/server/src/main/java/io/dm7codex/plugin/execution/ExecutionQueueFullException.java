package io.dm7codex.plugin.execution;
public final class ExecutionQueueFullException extends IllegalStateException {
    public ExecutionQueueFullException() { super("Execution queue is full"); }
}
