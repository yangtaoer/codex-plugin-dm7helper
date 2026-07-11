package io.dm7codex.plugin.execution;
public final class UntrackableMutationException extends IllegalArgumentException {
    public UntrackableMutationException() { super("Tracked execution cannot contain anonymous or dynamic SQL"); }
}
