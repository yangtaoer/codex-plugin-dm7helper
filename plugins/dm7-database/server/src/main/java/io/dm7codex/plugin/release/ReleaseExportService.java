package io.dm7codex.plugin.release;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionState;
import io.dm7codex.plugin.state.ExportRepository;
import io.dm7codex.plugin.state.ExportRepository.ExportArtifactRecord;
import io.dm7codex.plugin.state.ExportRepository.SealedRelease;
import io.dm7codex.plugin.state.SessionRepository;
import io.dm7codex.plugin.state.SessionRepository.ReleaseVersion;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class ReleaseExportService {
    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final RuntimePaths paths;
    private final SessionRepository sessions;
    private final ExportRepository exports;
    private final Duration lockTimeout;
    private final Clock clock;
    private final ExportFaultInjector faultInjector;

    public ReleaseExportService(
            RuntimePaths paths,
            SessionRepository sessions,
            ExportRepository exports,
            Duration lockTimeout,
            Clock clock,
            ExportFaultInjector faultInjector) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.exports = Objects.requireNonNull(exports, "exports");
        this.lockTimeout = Objects.requireNonNull(lockTimeout, "lockTimeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.faultInjector = Objects.requireNonNull(faultInjector, "faultInjector");
    }

    public ReleaseExportService(
            RuntimePaths paths, SessionRepository sessions, ExportRepository exports) {
        this(paths, sessions, exports, Duration.ofSeconds(5), Clock.systemUTC(), ignored -> {});
    }

    public ExportArtifact export(SessionState session) throws IOException, SQLException {
        Objects.requireNonNull(session, "session");
        SessionFileLock.trustedSessionDirectory(paths, session);
        var releaseAtEntry = sessions.findVersion(session.sessionId(), session.version());
        validateVersionPath(session, releaseAtEntry);
        if ("active".equals(releaseAtEntry.status())) sessions.requireActive(session);
        try (var ignored = SessionFileLock.acquire(paths, session, lockTimeout)) {
            return exportLocked(session);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Atomic release rotation is unavailable");
        } catch (ReleaseExportLockTimeout timeout) {
            throw timeout;
        } catch (IOException failure) {
            throw new IOException("Release export could not be completed");
        }
    }

    private ExportArtifact exportLocked(SessionState session) throws IOException, SQLException {
        var release = sessions.findVersion(session.sessionId(), session.version());
        validateVersionPath(session, release);
        var sessionDirectory = SessionFileLock.trustedSessionDirectory(paths, session);
        var sealedDirectory = sessionDirectory.resolve("sealed");
        var exportDirectory = trustedExportDirectory(session);
        SessionFileLock.secureDirectory(sealedDirectory);
        SessionFileLock.secureDirectory(exportDirectory);
        var sealedPath = sealedDirectory.resolve(
                ReleaseLogService.versionText(release.version()) + ".sql");

        SealedRelease sealed;
        if ("active".equals(release.status())) {
            sealed = sealActive(session, release, sealedPath);
            release = sessions.findVersion(session.sessionId(), session.version());
        } else if ("sealed".equals(release.status())) {
            sealed = exports.findSealed(session.sessionId(), session.version())
                    .orElseThrow(() -> new SQLException("Sealed release metadata is incomplete"));
            validateSealedPath(sealed, sealedPath);
        } else {
            throw new SQLException("Release version has an unsupported export state");
        }

        var artifactRecord = exports.findArtifact(session.sessionId(), session.version());
        var finalPath = artifactRecord.map(ExportArtifactRecord::artifactPath)
                .orElseGet(() -> exportDirectory.resolve(filename(session, sealed.sealedAt())));
        validateArtifactPath(finalPath, exportDirectory, session, sealed.sealedAt());
        var artifact = completeArtifact(session, release, sealed, artifactRecord, finalPath);
        ensureNextActive(session, release.version() + 1);
        return artifact;
    }

    private SealedRelease sealActive(
            SessionState session, ReleaseVersion release, Path sealedPath)
            throws IOException, SQLException {
        sessions.requireActive(session);
        var activePath = release.activeSql();
        if (!Files.exists(sealedPath)) {
            if (!Files.exists(activePath)) {
                throw new IOException("Active release source is unavailable");
            }
            forceAndClose(activePath);
            atomicMove(activePath, sealedPath);
            forceDirectory(sealedPath.getParent());
            faultInjector.after(ExportStage.AFTER_SEAL_MOVE);
        } else if (Files.exists(activePath)) {
            throw new IOException("Release recovery found conflicting active and sealed sources");
        }
        SessionFileLock.secureFile(sealedPath);
        var sealed = new SealedRelease(
                release.sessionId(), release.version(), sealedPath, sha256(sealedPath),
                release.firstSequence(), release.lastSequence(), release.statementCount(),
                clock.instant());
        exports.recordSealed(sealed);
        faultInjector.after(ExportStage.AFTER_SEALED_RECORDED);
        var initialArtifact = new ExportArtifactRecord(
                exportId(session.sessionId(), release.version()), session.sessionId(),
                release.version(), "SEALED", null, null, release.firstSequence(),
                release.lastSequence(), release.statementCount(), sealed.sealedAt(), null, null);
        exports.saveArtifact(initialArtifact);
        faultInjector.after(ExportStage.AFTER_ARTIFACT_STATE_RECORDED);
        return sealed;
    }

    private ExportArtifact completeArtifact(
            SessionState session,
            ReleaseVersion release,
            SealedRelease sealed,
            Optional<ExportArtifactRecord> existing,
            Path finalPath)
            throws IOException, SQLException {
        validateSealedPath(sealed, sealed.sealedSourcePath());
        if (!Files.exists(sealed.sealedSourcePath())) {
            throw new IOException("Sealed release source is unavailable");
        }
        if (!sha256(sealed.sealedSourcePath()).equals(sealed.sealedSourceSha256())) {
            throw new IOException("Sealed release source failed integrity verification");
        }

        var expectedBytes = artifactBytes(session, release, sealed);
        if (!Files.exists(finalPath)) {
            var temporary = finalPath.resolveSibling("." + finalPath.getFileName() + ".tmp");
            writeAndForce(temporary, expectedBytes);
            faultInjector.after(ExportStage.AFTER_EXPORT_TEMP_FORCED);
            atomicMove(temporary, finalPath);
            forceDirectory(finalPath.getParent());
            SessionFileLock.secureFile(finalPath);
            faultInjector.after(ExportStage.AFTER_ARTIFACT_MOVE);
        } else if (!java.util.Arrays.equals(expectedBytes, Files.readAllBytes(finalPath))) {
            throw new IOException("Existing release artifact failed integrity verification");
        }

        var artifactSha = sha256(finalPath);
        if (existing.isPresent()
                && "COMPLETE".equals(existing.get().state())
                && (!artifactSha.equals(existing.get().artifactSha256())
                        || !finalPath.equals(existing.get().artifactPath()))) {
            throw new IOException("Completed release artifact failed integrity verification");
        }
        var createdAt = existing.map(ExportArtifactRecord::createdAt).orElse(sealed.sealedAt());
        var completedAt = existing.map(ExportArtifactRecord::completedAt)
                .filter(Objects::nonNull).orElse(clock.instant());
        var complete = new ExportArtifactRecord(
                existing.map(ExportArtifactRecord::exportId)
                        .orElseGet(() -> exportId(session.sessionId(), release.version())),
                session.sessionId(), release.version(), "COMPLETE", finalPath, artifactSha,
                release.firstSequence(), release.lastSequence(), release.statementCount(),
                createdAt, completedAt, null);
        exports.saveArtifact(complete);
        faultInjector.after(ExportStage.AFTER_ARTIFACT_RECORDED);
        return new ExportArtifact(
                complete.exportId(), ReleaseLogService.versionText(release.version()),
                ReleaseLogService.versionText(release.version() + 1), finalPath,
                finalPath.getFileName().toString(), Files.size(finalPath), artifactSha,
                sealed.sealedSourceSha256(), release.statementCount(),
                release.firstSequence(), release.lastSequence(), completedAt);
    }

    private void ensureNextActive(SessionState session, int nextVersion)
            throws IOException, SQLException {
        var existingActive = sessions.findActive(session.sessionId());
        if (existingActive.isPresent()) {
            if (existingActive.get().version() != nextVersion
                    || !existingActive.get().activeSql().equals(session.activeSql())) {
                throw new SQLException("Unexpected active release version during recovery");
            }
            return;
        }

        var header = activeHeader(nextVersion);
        if (!Files.exists(session.activeSql())) {
            var temporary = session.activeSql().resolveSibling(
                    ".active-" + ReleaseLogService.versionText(nextVersion) + ".tmp");
            writeAndForce(temporary, header.getBytes(UTF_8));
            atomicMove(temporary, session.activeSql());
            forceDirectory(session.activeSql().getParent());
            SessionFileLock.secureFile(session.activeSql());
            faultInjector.after(ExportStage.AFTER_NEXT_ACTIVE_CREATED);
        } else if (!Files.readString(session.activeSql(), UTF_8).equals(header)) {
            throw new IOException("Recovered active release header is invalid");
        }
        sessions.createNextActiveVersion(
                session.sessionId(), session.externalIdHash(), nextVersion,
                session.activeSql(), clock.instant());
        faultInjector.after(ExportStage.AFTER_NEXT_VERSION_RECORDED);
    }

    private Path trustedExportDirectory(SessionState session) {
        var root = paths.exportsDirectory().toAbsolutePath().normalize();
        var directory = root.resolve(session.externalIdHash()).normalize();
        if (!directory.startsWith(root) || directory.getParent() == null
                || !directory.getParent().equals(root) || Files.isSymbolicLink(directory)) {
            throw new IllegalStateException("Release export path is not trusted");
        }
        try {
            SessionFileLock.secureDirectory(root);
        } catch (IOException failure) {
            throw new IllegalStateException("Release export directory cannot be secured", failure);
        }
        return directory;
    }

    private static void validateVersionPath(SessionState session, ReleaseVersion release) {
        if (!release.sessionId().equals(session.sessionId())
                || release.version() != session.version()
                || !release.activeSql().equals(session.activeSql().toAbsolutePath().normalize())) {
            throw new IllegalStateException("Session release state is stale or invalid");
        }
    }

    private static void validateSealedPath(SealedRelease sealed, Path expected) {
        if (!sealed.sealedSourcePath().equals(expected.toAbsolutePath().normalize())) {
            throw new IllegalStateException("Sealed release path is not trusted");
        }
    }

    private static void validateArtifactPath(
            Path artifactPath, Path exportDirectory, SessionState session, Instant sealedAt) {
        if (artifactPath == null
                || !artifactPath.toAbsolutePath().normalize()
                        .equals(exportDirectory.resolve(filename(session, sealedAt)))) {
            throw new IllegalStateException("Release artifact path is not trusted");
        }
    }

    private static byte[] artifactBytes(
            SessionState session, ReleaseVersion release, SealedRelease sealed) throws IOException {
        var header = """
                -- DM7 Codex release export
                -- version: %s
                -- session: %s
                -- database-fingerprint: %s
                -- generated-at: %s
                -- statement-count: %d
                -- sealed-source-sha256: %s
                """.formatted(
                ReleaseLogService.versionText(release.version()), shortId(session.sessionId()),
                release.databaseFingerprint(), sealed.sealedAt(), release.statementCount(),
                sealed.sealedSourceSha256());
        var body = Files.readAllBytes(sealed.sealedSourcePath());
        var prefix = header.getBytes(UTF_8);
        var result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    private static String activeHeader(int version) {
        return """
                -- DM7 Codex release log
                -- version: %s
                -- database-fingerprint: unbound
                """.formatted(ReleaseLogService.versionText(version));
    }

    private static String filename(SessionState session, Instant sealedAt) {
        return "dm7-%s-%s-%s.sql".formatted(
                shortId(session.sessionId()), ReleaseLogService.versionText(session.version()),
                FILE_TIME.format(sealedAt));
    }

    private static String shortId(String sessionId) {
        var ascii = sessionId.replaceAll("[^A-Za-z0-9]", "");
        if (ascii.isEmpty()) return "session";
        return ascii.substring(0, Math.min(12, ascii.length()));
    }

    private static String exportId(String sessionId, int version) {
        return sha256((sessionId + ":" + version).getBytes(UTF_8));
    }

    private static void forceAndClose(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void writeAndForce(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        SessionFileLock.secureFile(path);
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void forceDirectory(Path directory) {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException bestEffortOnly) {
            // Some Windows and network file systems cannot open directories as channels.
        }
    }

    private static String sha256(Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
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
    public interface ExportFaultInjector {
        void after(ExportStage stage) throws IOException;
    }

    public enum ExportStage {
        AFTER_SEAL_MOVE,
        AFTER_SEALED_RECORDED,
        AFTER_ARTIFACT_STATE_RECORDED,
        AFTER_EXPORT_TEMP_FORCED,
        AFTER_ARTIFACT_MOVE,
        AFTER_ARTIFACT_RECORDED,
        AFTER_NEXT_ACTIVE_CREATED,
        AFTER_NEXT_VERSION_RECORDED
    }

    public record ExportArtifact(
            String id,
            String version,
            String newActiveVersion,
            Path path,
            String filename,
            long byteLength,
            String sha256,
            String sealedSourceSha256,
            int statementCount,
            Long firstSequence,
            Long lastSequence,
            Instant createdAt) {}
}
