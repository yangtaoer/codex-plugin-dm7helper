package io.dm7codex.plugin.release;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.sql.ParsedStatement;
import io.dm7codex.plugin.sql.SqlPurpose;
import io.dm7codex.plugin.sql.SqlSecurityPolicy;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.SessionRepository.FingerprintMismatchException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

public final class ReleaseLogService {
    private final RuntimePaths paths;
    private final SessionRepository sessions;
    private final Duration lockTimeout;
    private final SqlSecurityPolicy securityPolicy;

    public ReleaseLogService(
            RuntimePaths paths, SessionRepository sessions, Duration lockTimeout) {
        this(paths, sessions, lockTimeout, new SqlSecurityPolicy());
    }

    public ReleaseLogService(
            RuntimePaths paths,
            SessionRepository sessions,
            Duration lockTimeout,
            SqlSecurityPolicy securityPolicy) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        this.securityPolicy = Objects.requireNonNull(securityPolicy, "securityPolicy");
    }

    public void recordCommitted(
            SessionState session,
            String databaseFingerprint,
            SqlPurpose purpose,
            ParsedStatement statement,
            String renderedSql)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(renderedSql, "renderedSql");
        if (!purpose.isReleaseEligible() || !statement.releaseEligibleKind()) return;

        securityPolicy.assertNoEmbeddedCredentials(statement);
        var activeAtEntry = requireActive(session);
        try (var ignored = SessionFileLock.acquire(paths, activeAtEntry, lockTimeout)) {
            var current = activeAfterLock(activeAtEntry);
            try {
                sessions.bindOrAssertFingerprint(current, databaseFingerprint);
            } catch (FingerprintMismatchException mismatch) {
                throw new ReleaseLogConnectionMismatch();
            }
            var sql = normalizeStatement(renderedSql);
            var bindingLine = "-- database-fingerprint: " + databaseFingerprint + "\n";
            var binding = Files.readString(current.activeSql(), UTF_8).contains(bindingLine)
                    ? "" : bindingLine;
            appendAndForce(current.activeSql(), binding + sql);
            sessions.recordReleaseStatement(current, statement, renderedSql);
        } catch (ReleaseExportLockTimeout timeout) {
            throw timeout;
        } catch (IOException failure) {
            throw new IOException("Release log could not be updated");
        }
    }

    public void assertWritable(
            SessionState session, String databaseFingerprint, SqlPurpose purpose)
            throws IOException, SQLException, ReleaseLogConnectionMismatch {
        Objects.requireNonNull(purpose, "purpose");
        if (!purpose.isReleaseEligible()) return;
        var activeAtEntry = requireActive(session);
        try (var ignored = SessionFileLock.acquire(paths, activeAtEntry, lockTimeout)) {
            var current = activeAfterLock(activeAtEntry);
            try {
                sessions.assertCompatible(current, databaseFingerprint);
            } catch (FingerprintMismatchException mismatch) {
                throw new ReleaseLogConnectionMismatch();
            }
        } catch (ReleaseExportLockTimeout timeout) {
            throw timeout;
        } catch (IOException failure) {
            throw new IOException("Release log could not be checked");
        }
    }

    public ReleaseSnapshot inspect(SessionState session) throws IOException, SQLException {
        SessionFileLock.trustedSessionDirectory(paths, session);
        var activeAtEntry = requireActive(session);
        try (var ignored = SessionFileLock.acquire(paths, activeAtEntry, lockTimeout)) {
            var current = activeAfterLock(activeAtEntry);
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

    private SessionState activeAfterLock(SessionState activeAtEntry) throws SQLException {
        var current = sessions.findActive(activeAtEntry.sessionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Session release state is stale or invalid"));
        if (!current.externalIdHash().equals(activeAtEntry.externalIdHash())
                || !current.activeSql().equals(activeAtEntry.activeSql())
                || !current.createdAt().equals(activeAtEntry.createdAt())
                || current.version() < activeAtEntry.version()) {
            throw new IllegalStateException("Session release state is stale or invalid");
        }
        return current;
    }

    private SessionState requireActive(SessionState session) throws SQLException {
        try {
            return sessions.requireActive(session);
        } catch (SQLException stale) {
            if (stale.getMessage() != null
                    && (stale.getMessage().contains("Session state")
                            || stale.getMessage().contains("trusted session path"))) {
                throw new IllegalStateException("Session release state is stale or invalid");
            }
            throw stale;
        }
    }

    private static void appendAndForce(java.nio.file.Path path, String text) throws IOException {
        var bytes = UTF_8.encode(text);
        try (var channel = FileChannel.open(path,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    static String normalizeStatement(String renderedSql) {
        var normalized = renderedSql.replace("\r\n", "\n").replace('\r', '\n');
        int end = normalized.length();
        while (end > 0 && Character.isWhitespace(normalized.charAt(end - 1))) end--;
        if (end > 0 && normalized.charAt(end - 1) == ';'
                && isTopLevelDelimiter(normalized, end - 1)) {
            end--;
        }
        while (end > 0 && (normalized.charAt(end - 1) == '\n'
                || normalized.charAt(end - 1) == '\r')) end--;
        return normalized.substring(0, end) + ";\n";
    }

    private static boolean isTopLevelDelimiter(String sql, int delimiter) {
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        int blockComment = 0;
        int parentheses = 0;
        for (int i = 0; i <= delimiter; i++) {
            char value = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (value == '\n') lineComment = false;
                continue;
            }
            if (blockComment > 0) {
                if (value == '/' && next == '*') {
                    blockComment++;
                    i++;
                } else if (value == '*' && next == '/') {
                    blockComment--;
                    i++;
                }
                continue;
            }
            if (single) {
                if (value == '\'' && next == '\'') i++;
                else if (value == '\'') single = false;
                continue;
            }
            if (quotedIdentifier) {
                if (value == '"' && next == '"') i++;
                else if (value == '"') quotedIdentifier = false;
                continue;
            }
            if (value == '\'') single = true;
            else if (value == '"') quotedIdentifier = true;
            else if (value == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (value == '/' && next == '*') {
                blockComment = 1;
                i++;
            } else if (value == '(') parentheses++;
            else if (value == ')' && parentheses > 0) parentheses--;
            else if (i == delimiter) return value == ';' && parentheses == 0;
        }
        return false;
    }

    static String versionText(int version) {
        return "v%03d".formatted(version);
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
