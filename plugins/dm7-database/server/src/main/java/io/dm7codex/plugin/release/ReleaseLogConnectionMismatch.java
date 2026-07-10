package io.dm7codex.plugin.release;

public final class ReleaseLogConnectionMismatch extends Exception {
    public ReleaseLogConnectionMismatch() {
        super("Active release version is bound to another database");
    }
}
