package io.dm7codex.plugin.execution;
public final class AtomicDdlNotSupported extends IllegalArgumentException {
    public AtomicDdlNotSupported() { super("Atomic execution supports pure DML only"); }
}
