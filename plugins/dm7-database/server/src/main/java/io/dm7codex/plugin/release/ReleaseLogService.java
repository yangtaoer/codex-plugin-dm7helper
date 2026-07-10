package io.dm7codex.plugin.release;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.ParsedStatement;
import io.dm7codex.plugin.sql.DmSqlParser;
import io.dm7codex.plugin.sql.SqlKind;
import io.dm7codex.plugin.sql.SqlPurpose;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.SessionRepository.FingerprintMismatchException;
import io.dm7codex.plugin.state.SessionRepository.PendingReleaseOperation;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public final class ReleaseLogService {
    private static final String BEGIN_MARKER = "-- dm7-operation-begin:%s:%s\n";
    private static final String END_MARKER = "-- dm7-operation-end:%s:%s\n";

    private final RuntimePaths paths;
    private final SessionRepository sessions;
    private final Duration lockTimeout;
    private final SqlSecurityPolicy securityPolicy;
    private final RecordFaultInjector faultInjector;

    public ReleaseLogService(RuntimePaths paths, SessionRepository sessions, Duration lockTimeout) {
        this(paths, sessions, lockTimeout, new SqlSecurityPolicy(), ignored -> {});
    }

    public ReleaseLogService(
            RuntimePaths paths,
            SessionRepository sessions,
            Duration lockTimeout,
            SqlSecurityPolicy securityPolicy) {
        this(paths, sessions, lockTimeout, securityPolicy, ignored -> {});
    }

    public ReleaseLogService(
            RuntimePaths paths,
            SessionRepository sessions,
            Duration lockTimeout,
            SqlSecurityPolicy securityPolicy,
            RecordFaultInjector faultInjector) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        this.securityPolicy = Objects.requireNonNull(securityPolicy, "securityPolicy");
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    }

    public ReleaseWriteReservation reserveWritable(
            SessionState supplied, String databaseFingerprint, SqlPurpose purpose)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        Objects.requireNonNull(purpose, "purpose");
        if (!purpose.isReleaseEligible()) {
            var current = requireCurrentAtLeast(supplied);
            return new ReleaseWriteReservation(
                    this, current, databaseFingerprint, purpose, null);
        }
        var beforeLock = requireCurrentAtLeast(supplied);
        SessionFileLock lock = null;
        try {
            lock = SessionFileLock.acquire(paths, beforeLock, lockTimeout);
            var current = requireCurrentAtLeast(supplied);
            validateActiveHeader(current);
            reconcilePending(paths, sessions, current);
            current = requireCurrentAtLeast(supplied);
            validateActiveHeader(current);
            try {
                sessions.assertCompatible(current, databaseFingerprint);
            } catch (FingerprintMismatchException mismatch) {
                throw new ReleaseLogConnectionMismatch();
            }
            return new ReleaseWriteReservation(
                    this, current, databaseFingerprint, purpose, lock);
        } catch (ReleaseExportLockTimeout timeout) {
            if (lock != null) lock.close();
            throw timeout;
        } catch (IOException failure) {
            if (lock != null) {
                try { lock.close(); } catch (IOException ignored) { }
            }
            throw new IOException("Release write reservation could not be acquired");
        } catch (SQLException | RuntimeException | ReleaseLogConnectionMismatch failure) {
            if (lock != null) {
                try {
                    lock.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    public void recordCommitted(
            ReleaseWriteReservation reservation,
            String operationId,
            ParsedStatement statement,
            String renderedSql)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        try {
            recordCommittedInternal(reservation, operationId, statement, renderedSql);
        } catch (IOException failure) {
            throw new IOException("Committed release operation could not be journaled");
        }
    }

    private void recordCommittedInternal(
            ReleaseWriteReservation reservation,
            String operationId,
            ParsedStatement statement,
            String renderedSql)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        Objects.requireNonNull(reservation, "reservation");
        reservation.requireOwner(this);
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(renderedSql, "renderedSql");
        if (!reservation.purpose().isReleaseEligible() || !statement.releaseEligibleKind()) return;
        securityPolicy.assertNoEmbeddedCredentials(statement);

        var existing = sessions.findOperation(operationId);
        if (existing.isPresent()) {
            assertSameOperation(existing.get(), reservation, statement, renderedSql);
            if (existing.get().recorded()) return;
            reconcilePending(paths, sessions, reservation.session());
            return;
        }

        var current = reservation.session();
        var binding = "unbound".equals(current.databaseFingerprint());
        var normalized = normalizeStatement(renderedSql);
        var payload = (binding
                ? "-- database-fingerprint: " + reservation.fingerprint() + "\n"
                : "") + normalized;
        var payloadSha = sha256(payload.getBytes(UTF_8));
        var operationHash = sha256(operationId.getBytes(UTF_8));
        var block = operationBlock(operationHash, payloadSha, payload);
        var offset = Files.size(current.activeSql());

        faultInjector.after(RecordStage.BEFORE_PENDING);
        var pending = sessions.beginPending(
                current, operationId, reservation.fingerprint(), statement, renderedSql,
                offset, payloadSha, binding);
        if (pending.recorded()) return;
        faultInjector.after(RecordStage.AFTER_PENDING);
        appendPendingBlock(current.activeSql(), pending, block);
        faultInjector.after(RecordStage.BEFORE_FINALIZE);
        try {
            sessions.finalizePending(pending);
        } catch (FingerprintMismatchException mismatch) {
            throw new ReleaseLogConnectionMismatch();
        }
        faultInjector.after(RecordStage.AFTER_FINALIZE);
    }

    public void recordCommitted(
            SessionState session,
            String databaseFingerprint,
            SqlPurpose purpose,
            String operationId,
            ParsedStatement statement,
            String renderedSql)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        if (!purpose.isReleaseEligible() || !statement.releaseEligibleKind()) return;
        try (var reservation = reserveWritable(session, databaseFingerprint, purpose)) {
            recordCommitted(reservation, operationId, statement, renderedSql);
        }
    }

    public void recordCommitted(
            SessionState session,
            String databaseFingerprint,
            SqlPurpose purpose,
            ParsedStatement statement,
            String renderedSql)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        recordCommitted(session, databaseFingerprint, purpose,
                "legacy-" + UUID.randomUUID(), statement, renderedSql);
    }

    /** Compatibility probe only. Database mutations must use {@link #reserveWritable}. */
    public void assertWritable(
            SessionState session, String databaseFingerprint, SqlPurpose purpose)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        try (var ignored = reserveWritable(session, databaseFingerprint, purpose)) {
            // Reservation is deliberately released because this method cannot span execution.
        }
    }

    public ReleaseSnapshot inspect(SessionState supplied) throws IOException, SQLException {
        var beforeLock = requireCurrentAtLeast(supplied);
        try (var ignored = SessionFileLock.acquire(paths, beforeLock, lockTimeout)) {
            var current = requireCurrentAtLeast(supplied);
            validateActiveHeader(current);
            reconcilePending(paths, sessions, current);
            current = requireCurrentAtLeast(supplied);
            validateActiveHeader(current);
            var release = sessions.findVersion(current.sessionId(), current.version());
            var preview = Files.readString(current.activeSql(), UTF_8);
            return new ReleaseSnapshot(
                    versionText(current.version()), current.databaseFingerprint(),
                    release.statementCount(), 0, 0, preview,
                    release.firstSequence(), release.lastSequence());
        } catch (ReleaseExportLockTimeout timeout) {
            throw timeout;
        } catch (IOException failure) {
            throw new IOException("Release log could not be inspected");
        }
    }

    static void reconcilePending(
            RuntimePaths paths, SessionRepository sessions, SessionState current)
            throws IOException, SQLException {
        SessionFileLock.trustedSessionDirectory(paths, current);
        for (var pending : sessions.findPending(current.sessionId(), current.version())) {
            var payload = (pending.bindingComment()
                    ? "-- database-fingerprint: " + pending.fingerprint() + "\n"
                    : "") + normalizeStatement(pending.replayableSql());
            if (!sha256(payload.getBytes(UTF_8)).equals(pending.blockSha256())) {
                throw new IOException("Pending release operation failed integrity verification");
            }
            var operationHash = sha256(pending.operationId().getBytes(UTF_8));
            var block = operationBlock(operationHash, pending.blockSha256(), payload);
            rewriteMissingOrPartial(current.activeSql(), pending.fileOffset(), block);
            sessions.finalizePending(pending);
        }
    }

    private void appendPendingBlock(Path activeSql, PendingReleaseOperation pending, byte[] block)
            throws IOException {
        requireRegularFile(activeSql);
        try (var channel = FileChannel.open(activeSql,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != pending.fileOffset()) {
                throw new IOException("Active release log changed during journal append");
            }
            channel.position(pending.fileOffset());
            int half = Math.max(1, block.length / 2);
            writeFully(channel, ByteBuffer.wrap(block, 0, half));
            faultInjector.after(RecordStage.AFTER_PARTIAL_APPEND);
            writeFully(channel, ByteBuffer.wrap(block, half, block.length - half));
            channel.force(true);
            faultInjector.after(RecordStage.AFTER_APPEND_FORCE);
        }
    }

    private static void rewriteMissingOrPartial(Path activeSql, long offset, byte[] expected)
            throws IOException {
        requireRegularFile(activeSql);
        try (var channel = FileChannel.open(activeSql,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            var size = channel.size();
            if (size < offset || size - offset > expected.length) {
                throw new IOException("Active release log has unexpected journal bytes");
            }
            var suffixLength = Math.toIntExact(size - offset);
            var suffix = new byte[suffixLength];
            channel.position(offset);
            var buffer = ByteBuffer.wrap(suffix);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            if (!Arrays.equals(suffix, Arrays.copyOf(expected, suffixLength))) {
                throw new IOException("Active release log has unexpected journal bytes");
            }
            if (suffixLength < expected.length) {
                channel.truncate(offset);
                channel.position(offset);
                writeFully(channel, ByteBuffer.wrap(expected));
                channel.force(true);
            }
        }
    }

    private static void assertSameOperation(
            PendingReleaseOperation existing,
            ReleaseWriteReservation reservation,
            ParsedStatement statement,
            String renderedSql) throws IOException {
        if (!existing.sessionId().equals(reservation.session().sessionId())
                || !existing.fingerprint().equals(reservation.fingerprint())
                || !existing.statementKind().equals(statement.kind().name())
                || !existing.replayableSql().equals(renderedSql)) {
            throw new IOException("Release operation identifier was reused with different content");
        }
    }

    private SessionState requireCurrentAtLeast(SessionState session) throws SQLException {
        try {
            return sessions.requireCurrentAtLeast(session);
        } catch (SQLException stale) {
            if (stale.getMessage() != null && (stale.getMessage().contains("Session state")
                    || stale.getMessage().contains("trusted session path"))) {
                throw new IllegalStateException("Session release state is stale or invalid");
            }
            throw stale;
        }
    }

    private static void validateActiveHeader(SessionState current) throws IOException {
        requireRegularFile(current.activeSql());
        var header = Files.readString(current.activeSql(), UTF_8);
        if (!header.startsWith("-- DM7 Codex release log\n-- version: "
                + versionText(current.version()) + "\n")) {
            throw new IOException("Active release header does not match database state");
        }
    }

    private static byte[] operationBlock(String operationHash, String payloadSha, String payload) {
        return (BEGIN_MARKER.formatted(operationHash, payloadSha)
                + payload
                + END_MARKER.formatted(operationHash, payloadSha)).getBytes(UTF_8);
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Release log path is not a regular file");
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    static String normalizeStatement(String renderedSql) {
        return DmSqlParser.ensureSingleTopLevelTerminator(renderedSql);
    }

    static String versionText(int version) {
        return "v%03d".formatted(version);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    public interface RecordFaultInjector {
        void after(RecordStage stage) throws IOException;
    }

    public enum RecordStage {
        BEFORE_PENDING,
        AFTER_PENDING,
        AFTER_PARTIAL_APPEND,
        AFTER_APPEND_FORCE,
        BEFORE_FINALIZE,
        AFTER_FINALIZE
    }

    public record ReleaseSnapshot(
            String currentVersion,
            String databaseFingerprint,
            int statementCount,
            int excludedCount,
            int failedCount,
            String sqlPreview,
            Long firstSequence,
            Long lastSequence) {}
}
