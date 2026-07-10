package io.dm7codex.plugin.release;

import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionState;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class SessionFileLock implements AutoCloseable {
    private static final long RETRY_MILLIS = 10;
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final FileChannel channel;
    private final FileLock lock;

    private SessionFileLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SessionFileLock acquire(
            RuntimePaths paths, SessionState session, Duration timeout) throws IOException {
        Objects.requireNonNull(paths, "paths");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Lock timeout must be positive");
        }
        var sessionDirectory = trustedSessionDirectory(paths, session);
        secureDirectory(paths.sessionsDirectory());
        secureDirectory(sessionDirectory);
        var lockPath = sessionDirectory.resolve("active.lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IllegalStateException("Session release lock path is not trusted");
        }
        final FileChannel channel;
        try {
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException failure) {
            throw new IOException("Session release lock could not be opened");
        }
        try {
            secureFile(lockPath);
            var deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                FileLock lock = null;
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busyInProcess) {
                    // Same semantics as a lock held by another process.
                }
                if (lock != null) return new SessionFileLock(channel, lock);
                if (System.nanoTime() >= deadline) {
                    throw new ReleaseExportLockTimeout("Timed out waiting for the session release lock");
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ReleaseExportLockTimeout("Interrupted while waiting for the session release lock");
                }
            }
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    static Path trustedSessionDirectory(RuntimePaths paths, SessionState session) {
        var sessionsRoot = paths.sessionsDirectory().toAbsolutePath().normalize();
        var expectedDirectory = sessionsRoot.resolve(session.externalIdHash()).normalize();
        var expectedActive = expectedDirectory.resolve("active.sql").normalize();
        var suppliedActive = session.activeSql().toAbsolutePath().normalize();
        if (!expectedActive.equals(suppliedActive)
                || !expectedDirectory.startsWith(sessionsRoot)
                || expectedDirectory.getParent() == null
                || !expectedDirectory.getParent().equals(sessionsRoot)
                || Files.isSymbolicLink(expectedDirectory)
                || Files.isSymbolicLink(expectedActive)) {
            throw new IllegalStateException("Session release path is not trusted");
        }
        if (Files.exists(expectedDirectory)) {
            try {
                var realPluginData = paths.pluginData().toRealPath();
                var realSessionsRoot = sessionsRoot.toRealPath();
                var realSessionDirectory = expectedDirectory.toRealPath();
                if (!realSessionsRoot.startsWith(realPluginData)
                        || !realSessionDirectory.startsWith(realSessionsRoot)) {
                    throw new IllegalStateException("Session release path is not trusted");
                }
            } catch (IOException invalidRealPath) {
                throw new IllegalStateException("Session release path is not trusted");
            }
        }
        return expectedDirectory;
    }

    static void secureDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        secure(directory, true);
    }

    static void secureFile(Path file) throws IOException {
        secure(file, false);
    }

    private static void secure(Path path, boolean directory) throws IOException {
        var posix = Files.getFileAttributeView(
                path, java.nio.file.attribute.PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path,
                    directory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .equals(directory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS)) {
                throw new IOException("Owner-only path permissions could not be verified");
            }
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal processPrincipal;
            try {
                processPrincipal = path.getFileSystem().getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
            } catch (java.nio.file.attribute.UserPrincipalNotFoundException missing) {
                throw new IOException("Current process principal could not be resolved");
            }
            var permissions = directory
                    ? EnumSet.of(
                            AclEntryPermission.LIST_DIRECTORY,
                            AclEntryPermission.ADD_FILE,
                            AclEntryPermission.ADD_SUBDIRECTORY,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.WRITE_NAMED_ATTRS,
                            AclEntryPermission.DELETE_CHILD,
                            AclEntryPermission.EXECUTE,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.WRITE_ATTRIBUTES,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.WRITE_ACL,
                            AclEntryPermission.DELETE,
                            AclEntryPermission.SYNCHRONIZE)
                    : EnumSet.of(
                            AclEntryPermission.READ_DATA,
                            AclEntryPermission.WRITE_DATA,
                            AclEntryPermission.APPEND_DATA,
                            AclEntryPermission.READ_NAMED_ATTRS,
                            AclEntryPermission.WRITE_NAMED_ATTRS,
                            AclEntryPermission.DELETE,
                            AclEntryPermission.READ_ATTRIBUTES,
                            AclEntryPermission.WRITE_ATTRIBUTES,
                            AclEntryPermission.READ_ACL,
                            AclEntryPermission.WRITE_ACL,
                            AclEntryPermission.SYNCHRONIZE);
            var expected = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(processPrincipal)
                    .setPermissions(permissions)
                    .build();
            acl.setAcl(java.util.List.of(expected));
            if (!acl.getAcl().equals(java.util.List.of(expected))) {
                throw new IOException("Owner-only ACL could not be verified");
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            if (lock.isValid()) lock.release();
        } catch (IOException releaseFailure) {
            failure = releaseFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
