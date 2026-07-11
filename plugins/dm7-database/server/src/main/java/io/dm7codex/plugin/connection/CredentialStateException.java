package io.dm7codex.plugin.connection;

/** Signals that credential persistence needs explicit user recovery or cannot be proven consistent. */
public final class CredentialStateException extends IllegalStateException {
    public enum State { RECOVERY_REQUIRED, UNCERTAIN }
    private final State state;

    CredentialStateException(State state, String message, Throwable cause) {
        super(message, cause);
        this.state = state;
    }

    public State state() { return state; }
}
