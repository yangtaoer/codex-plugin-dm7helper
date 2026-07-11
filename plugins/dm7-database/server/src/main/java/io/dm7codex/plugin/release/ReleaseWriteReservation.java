package io.dm7codex.plugin.release;

import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.SqlPurpose;
import java.io.IOException;
import java.util.Objects;

public final class ReleaseWriteReservation implements AutoCloseable {
    private final ReleaseLogService owner;
    private final SessionState session;
    private final String fingerprint;
    private final SqlPurpose purpose;
    private final SessionFileLock lock;
    private boolean closed;

    ReleaseWriteReservation(
            ReleaseLogService owner,
            SessionState session,
            String fingerprint,
            SqlPurpose purpose,
            SessionFileLock lock) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.session = Objects.requireNonNull(session, "session");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.lock = lock;
    }

    SessionState session() {
        ensureOpen();
        return session;
    }

    public int releaseVersion() { ensureOpen(); return session.version(); }

    String fingerprint() {
        ensureOpen();
        return fingerprint;
    }

    SqlPurpose purpose() {
        ensureOpen();
        return purpose;
    }

    void requireOwner(ReleaseLogService expected) {
        ensureOpen();
        if (owner != expected) {
            throw new IllegalArgumentException("Release reservation belongs to another service");
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Release reservation is closed");
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (lock != null) lock.close();
    }
}
