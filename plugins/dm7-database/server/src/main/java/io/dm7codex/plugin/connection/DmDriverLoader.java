package io.dm7codex.plugin.connection;

import io.dm7codex.plugin.runtime.RuntimePaths;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Arrays;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class DmDriverLoader {
    static { URLConnection.setDefaultUseCaches("jar",false); }
    private static final String CLEANER_CLASS = "io.dm7codex.plugin.connection.ChildDriverRegistryCleaner";
    private static final String IDENTITY_FILE = ".driver-cache-identity";
    private static final Map<Path, Object> IDENTITY_LOCKS = new ConcurrentHashMap<>();
    private static final AtomicReference<DriverIsolationException> FATAL_ISOLATION = new AtomicReference<>();

    private final Path stagingDirectory;
    private final List<DirectoryIdentity> trustChain;
    private final LoaderFileOps fileOps;
    private final LoadObserver loadObserver;

    public DmDriverLoader(RuntimePaths paths) {
        this(paths, LoaderFileOps.DEFAULT, LoadObserver.NONE);
    }

    DmDriverLoader(RuntimePaths paths, LoaderFileOps fileOps) {
        this(paths, fileOps, LoadObserver.NONE);
    }

    DmDriverLoader(RuntimePaths paths, LoaderFileOps fileOps, LoadObserver loadObserver) {
        Objects.requireNonNull(paths, "paths");
        this.fileOps = Objects.requireNonNull(fileOps, "fileOps");
        this.loadObserver = Objects.requireNonNull(loadObserver, "loadObserver");
        try {
            Path pluginData = paths.pluginData().toAbsolutePath().normalize();
            Path cacheParent = pluginData.resolve("cache");
            Path configuredCache = paths.driverCacheDirectory().toAbsolutePath().normalize();
            if (!configuredCache.equals(cacheParent.resolve("jdbc-drivers"))) {
                throw new DriverIsolationException("JDBC driver cache escaped PLUGIN_DATA", true);
            }
            DirectoryIdentity pluginDataIdentity = createAndCaptureDirectory(pluginData);
            DirectoryIdentity cacheParentIdentity = createAndCaptureDirectory(cacheParent);
            DirectoryIdentity stagingIdentity = createAndCaptureDirectory(configuredCache);
            if (!cacheParentIdentity.realPath().getParent().equals(pluginDataIdentity.realPath())
                    || !stagingIdentity.realPath().getParent().equals(cacheParentIdentity.realPath())) {
                throw new DriverIsolationException("JDBC driver cache escaped PLUGIN_DATA", true);
            }
            this.stagingDirectory = stagingIdentity.realPath();
            this.trustChain = List.of(pluginDataIdentity, cacheParentIdentity, stagingIdentity);
        } catch (DriverIsolationException e) {
            throw e;
        } catch (IOException e) {
            throw new DriverIsolationException("Secure JDBC driver cache is unavailable", true);
        }
    }

    public DriverHandle load(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        throwIfFatalIsolation();
        verifyTrustChain();
        Path source = profile.driverJar();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Configured JDBC driver is missing or is not a regular file");
        }

        Path staged = null;
        URLClassLoader classLoader = null;
        Method cleanup = null;
        Method count = null;
        try {
            verifyTrustChain();
            staged = Files.createTempFile(stagingDirectory, "dm-driver-", ".jar");
            CredentialVault.secure(staged, false);
            verifyTrustChain();
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            CredentialVault.secure(staged, false);
            verifySha256(staged, profile.driverSha256());
            StagedIdentity stagedIdentity = captureStagedIdentity(staged, profile.driverSha256());
            loadObserver.afterStagedHash(staged);
            verifyTrustChain();
            verifyStagedIdentity(stagedIdentity);

            URL codeSource = DmDriverLoader.class.getProtectionDomain().getCodeSource().getLocation();
            classLoader = new URLClassLoader(new URL[]{codeSource, staged.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            verifyTrustChain();
            verifyStagedIdentity(stagedIdentity);
            Class<?> cleanerClass = Class.forName(CLEANER_CLASS, true, classLoader);
            cleanup = cleanerClass.getDeclaredMethod("deregisterChildDrivers");
            count = cleanerClass.getDeclaredMethod("registeredChildDriverCount");
            cleanup.setAccessible(true);
            count.setAccessible(true);

            Class<?> type = Class.forName(profile.driverClass(), true, classLoader);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalArgumentException("Configured driver class does not implement java.sql.Driver");
            }
            DriverIsolationException registrationFailure = cleanupRegistrations(cleanup, count);
            if (registrationFailure != null) throw registrationFailure;
            return new DriverHandle(classLoader, driver, staged, cleanup, count, fileOps);
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException failure) {
            RuntimeException publicFailure = publicLoadFailure(failure);
            DriverIsolationException registrationFailure = cleanupRegistrations(cleanup, count);
            RuntimeException primary = registrationFailure == null ? publicFailure : registrationFailure;
            if (registrationFailure != null && failure != registrationFailure) primary.addSuppressed(publicFailure);
            cleanupResources(primary, classLoader, staged, fileOps);
            throw primary;
        }
    }

    private void verifyTrustChain() {
        try {
            for (DirectoryIdentity identity : trustChain) {
                verifyDirectoryIdentity(identity);
            }
            if (!trustChain.get(1).realPath().getParent().equals(trustChain.get(0).realPath())
                    || !trustChain.get(2).realPath().getParent().equals(trustChain.get(1).realPath())) {
                throw new DriverIsolationException("JDBC driver trust chain changed", true);
            }
        } catch (DriverIsolationException e) {
            throw e;
        } catch (IOException e) {
            throw new DriverIsolationException("JDBC driver cache verification failed", true);
        }
    }

    private static DirectoryIdentity createAndCaptureDirectory(Path directory) throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(directory)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new DriverIsolationException("JDBC driver trust chain contains a link", true);
            }
        } else {
            Path parent = directory.getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Trusted directory parent is unavailable");
            }
            Files.createDirectory(directory);
        }
        Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(directory.toAbsolutePath().normalize())) {
            throw new DriverIsolationException("JDBC driver trust chain contains a link", true);
        }
        CredentialVault.secure(real, true);
        CredentialVault.verifySecureDirectory(real);
        Object fileKey = optionalFileKey(real);
        byte[] fallback = fileKey == null ? loadOrCreateFallbackIdentity(real) : null;
        return new DirectoryIdentity(real, fileKey, fallback);
    }

    private static void verifyDirectoryIdentity(DirectoryIdentity identity) throws IOException {
        Path real = identity.realPath();
        if (Files.isSymbolicLink(real) || !real.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(real)) {
            throw new DriverIsolationException("JDBC driver trust chain changed", true);
        }
        CredentialVault.verifySecureDirectory(real);
        Object currentKey = optionalFileKey(real);
        if (identity.fileKey() != null) {
            if (!identity.fileKey().equals(currentKey)) {
                throw new DriverIsolationException("JDBC driver trust chain changed", true);
            }
            return;
        }
        Path sentinel = real.resolve(IDENTITY_FILE);
        if (!Files.isRegularFile(sentinel, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sentinel)
                || !sentinel.toRealPath(LinkOption.NOFOLLOW_LINKS).getParent().equals(real)) {
            throw new DriverIsolationException("JDBC driver trust chain changed", true);
        }
        CredentialVault.verifySecureFile(sentinel);
        byte[] current = Files.readAllBytes(sentinel);
        boolean matches = MessageDigest.isEqual(identity.fallbackIdentity(), current);
        Arrays.fill(current, (byte) 0);
        if (!matches) throw new DriverIsolationException("JDBC driver trust chain changed", true);
    }

    private static StagedIdentity captureStagedIdentity(Path staged, String sha256) throws IOException {
        if (Files.isSymbolicLink(staged)) throw new DriverIsolationException("Staged JDBC driver changed", true);
        Path real = staged.toRealPath(LinkOption.NOFOLLOW_LINKS);
        CredentialVault.verifySecureFile(real);
        return new StagedIdentity(real, optionalFileKey(real), sha256);
    }

    private void verifyStagedIdentity(StagedIdentity identity) throws IOException {
        Path real = identity.realPath();
        if (Files.isSymbolicLink(real) || !real.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(real)
                || !real.getParent().equals(stagingDirectory)) {
            throw new DriverIsolationException("Staged JDBC driver changed", true);
        }
        CredentialVault.verifySecureFile(real);
        Object currentKey = optionalFileKey(real);
        if (identity.fileKey() != null && !identity.fileKey().equals(currentKey)) {
            throw new DriverIsolationException("Staged JDBC driver changed", true);
        }
        try {
            verifySha256(real, identity.sha256());
        } catch (SecurityException e) {
            throw new DriverIsolationException("Staged JDBC driver changed", true);
        }
    }

    private static Object optionalFileKey(Path directory) throws IOException {
        return Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    }

    private static byte[] loadOrCreateFallbackIdentity(Path directory) throws IOException {
        Path identity = directory.resolve(IDENTITY_FILE);
        Object lock = IDENTITY_LOCKS.computeIfAbsent(identity, ignored -> new Object());
        synchronized (lock) {
            if (!Files.exists(identity)) {
                byte[] token = new byte[32];
                new SecureRandom().nextBytes(token);
                try {
                    CredentialVault.atomicReplace(identity, token, true);
                } finally {
                    Arrays.fill(token, (byte) 0);
                }
            }
            CredentialVault.verifySecureFile(identity);
            byte[] token = Files.readAllBytes(identity);
            if (token.length != 32) {
                Arrays.fill(token, (byte) 0);
                throw new IOException("Driver cache identity is invalid");
            }
            return token;
        }
    }

    private static RuntimeException publicLoadFailure(Throwable failure) {
        if (failure instanceof DriverIsolationException isolation) return isolation;
        if (failure instanceof SecurityException security) return security;
        if (failure instanceof IllegalArgumentException illegal) return illegal;
        return new IllegalArgumentException("Configured JDBC driver could not be loaded");
    }

    private static void cleanupResources(Throwable primary, URLClassLoader classLoader, Path staged,
                                         LoaderFileOps fileOps) {
        if (classLoader != null) {
            try {
                fileOps.close(classLoader);
            } catch (Exception | LinkageError e) {
                primary.addSuppressed(e);
            }
        }
        if (staged != null) {
            try {
                fileOps.delete(staged);
            } catch (Exception | LinkageError e) {
                primary.addSuppressed(e);
            }
        }
    }

    private static DriverIsolationException cleanupRegistrations(Method cleanup, Method count) {
        if (cleanup == null && count == null) return null;
        List<Throwable> failures = new ArrayList<>();
        if (cleanup != null) {
            try {
                Throwable[] childFailures = (Throwable[]) cleanup.invoke(null);
                if (childFailures != null) failures.addAll(List.of(childFailures));
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                failures.add(unwrapInvocation(e));
            }
        }
        int remaining = -1;
        if (count != null) {
            try {
                remaining = (Integer) count.invoke(null);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                failures.add(unwrapInvocation(e));
            }
        }
        if (failures.isEmpty() && remaining == 0) return null;
        DriverIsolationException isolation = new DriverIsolationException(
                remaining > 0 ? "JDBC driver registrations remain; process restart is required"
                        : "JDBC driver registration cleanup was not fully reliable; process restart is required",
                true);
        failures.forEach(isolation::addSuppressed);
        tripFatalIsolation();
        return isolation;
    }

    private static void tripFatalIsolation() {
        FATAL_ISOLATION.compareAndSet(null, new DriverIsolationException(
                "JDBC driver isolation failed; process restart is required", true));
    }

    private static void throwIfFatalIsolation() {
        if (FATAL_ISOLATION.get() != null) {
            throw new DriverIsolationException("JDBC driver isolation failed; process restart is required", true);
        }
    }

    private static Throwable unwrapInvocation(Throwable failure) {
        return failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause() : failure;
    }

    private static void verifySha256(Path staged, String expected) {
        String actual = sha256(staged);
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expected), HexFormat.of().parseHex(actual))) {
            throw new SecurityException("Configured JDBC driver failed SHA-256 verification");
        }
    }

    private static String sha256(Path jar) {
        try (InputStream input = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Configured JDBC driver could not be verified");
        }
    }

    interface LoaderFileOps {
        LoaderFileOps DEFAULT = new LoaderFileOps() {
            @Override public void close(URLClassLoader loader) throws IOException { loader.close(); }
            @Override public void delete(Path stagedJar) throws IOException { Files.deleteIfExists(stagedJar); }
        };

        void close(URLClassLoader loader) throws Exception;
        void delete(Path stagedJar) throws Exception;
    }

    @FunctionalInterface
    interface LoadObserver {
        LoadObserver NONE = staged -> {};
        void afterStagedHash(Path staged) throws IOException;
    }

    public static final class DriverIsolationException extends IllegalStateException {
        private final boolean restartRequired;

        DriverIsolationException(String message, boolean restartRequired) {
            super(message);
            this.restartRequired = restartRequired;
        }

        public boolean restartRequired() {
            return restartRequired;
        }
    }

    public static final class DriverHandle implements AutoCloseable {
        private final URLClassLoader classLoader;
        private final Driver driver;
        private final Path stagedJar;
        private final Method cleanup;
        private final Method count;
        private final LoaderFileOps fileOps;
        private boolean closed;

        private DriverHandle(URLClassLoader classLoader, Driver driver, Path stagedJar, Method cleanup, Method count,
                             LoaderFileOps fileOps) {
            this.classLoader = classLoader;
            this.driver = driver;
            this.stagedJar = stagedJar;
            this.cleanup = cleanup;
            this.count = count;
            this.fileOps = fileOps;
        }

        public synchronized Connection connect(String jdbcUrl, Properties properties) throws SQLException {
            throwIfFatalIsolation();
            if (closed) throw new SQLException("JDBC driver handle is closed");
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) throw new SQLException("JDBC driver did not accept the configured URL");
            return connection;
        }

        int registeredDriverCount() {
            try {
                return (Integer) count.invoke(null);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new DriverIsolationException("JDBC driver registration inspection failed", true);
            }
        }

        ClassLoader driverClassLoader() {
            return classLoader;
        }

        @Override public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            DriverIsolationException registrationFailure = cleanupRegistrations(cleanup, count);
            Throwable primary = registrationFailure;
            if (primary == null) primary = new CleanupCompleteMarker();
            cleanupResources(primary, classLoader, stagedJar, fileOps);
            if (primary instanceof CleanupCompleteMarker marker) {
                if (marker.getSuppressed().length == 0) return;
                Exception failure = new Exception("JDBC driver resource cleanup failed");
                for (Throwable suppressed : marker.getSuppressed()) failure.addSuppressed(suppressed);
                throw failure;
            }
            if (primary instanceof Exception exception) throw exception;
            throw new Exception(primary);
        }
    }

    private static final class CleanupCompleteMarker extends Throwable {}

    private record DirectoryIdentity(Path realPath, Object fileKey, byte[] fallbackIdentity) {}
    private record StagedIdentity(Path realPath, Object fileKey, String sha256) {}
}

/** Loaded by the child loader so DriverManager caller filtering exposes only that child's registrations. */
final class ChildDriverRegistryCleaner {
    private ChildDriverRegistryCleaner() {}

    public static int registeredChildDriverCount() {
        ClassLoader child = ChildDriverRegistryCleaner.class.getClassLoader();
        int count = 0;
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            if (drivers.nextElement().getClass().getClassLoader() == child) count++;
        }
        return count;
    }

    public static Throwable[] deregisterChildDrivers() {
        ClassLoader child = ChildDriverRegistryCleaner.class.getClassLoader();
        List<Driver> registered = new ArrayList<>();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == child) registered.add(driver);
        }
        List<Throwable> failures = new ArrayList<>();
        for (Driver driver : registered) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException | RuntimeException e) {
                failures.add(e);
            }
        }
        return failures.toArray(Throwable[]::new);
    }
}
