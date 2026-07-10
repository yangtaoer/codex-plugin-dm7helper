package io.dm7codex.plugin.connection;

import io.dm7codex.plugin.runtime.RuntimePaths;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
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

public final class DmDriverLoader {
    private static final String CLEANER_CLASS = "io.dm7codex.plugin.connection.ChildDriverRegistryCleaner";
    private static final String IDENTITY_FILE = ".driver-cache-identity";
    private static final Map<Path, Object> IDENTITY_LOCKS = new ConcurrentHashMap<>();

    private final Path pluginDataReal;
    private final Path stagingDirectory;
    private final Object stagingFileKey;
    private final byte[] fallbackIdentity;
    private final LoaderFileOps fileOps;

    public DmDriverLoader(RuntimePaths paths) {
        this(paths, LoaderFileOps.DEFAULT);
    }

    DmDriverLoader(RuntimePaths paths, LoaderFileOps fileOps) {
        Objects.requireNonNull(paths, "paths");
        this.fileOps = Objects.requireNonNull(fileOps, "fileOps");
        try {
            Files.createDirectories(paths.pluginData());
            this.pluginDataReal = paths.pluginData().toRealPath();
            Path configuredCache = paths.driverCacheDirectory().toAbsolutePath().normalize();
            if (!configuredCache.startsWith(paths.pluginData())) {
                throw new DriverIsolationException("JDBC driver cache escaped PLUGIN_DATA", true);
            }
            Files.createDirectories(configuredCache);
            this.stagingDirectory = configuredCache.toRealPath();
            if (!stagingDirectory.startsWith(pluginDataReal)) {
                throw new DriverIsolationException("JDBC driver cache escaped PLUGIN_DATA", true);
            }
            CredentialVault.secure(stagingDirectory, true);
            CredentialVault.verifySecureDirectory(stagingDirectory);
            this.stagingFileKey = optionalFileKey(stagingDirectory);
            this.fallbackIdentity = stagingFileKey == null ? loadOrCreateFallbackIdentity(stagingDirectory) : null;
        } catch (DriverIsolationException e) {
            throw e;
        } catch (IOException e) {
            throw new DriverIsolationException("Secure JDBC driver cache is unavailable", true);
        }
    }

    public DriverHandle load(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        verifyCacheIdentity();
        Path source = profile.driverJar();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Configured JDBC driver is missing or is not a regular file");
        }

        Path staged = null;
        URLClassLoader classLoader = null;
        Method cleanup = null;
        Method count = null;
        try {
            staged = Files.createTempFile(stagingDirectory, "dm-driver-", ".jar");
            CredentialVault.secure(staged, false);
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING);
            CredentialVault.secure(staged, false);
            verifySha256(staged, profile.driverSha256());

            URL codeSource = DmDriverLoader.class.getProtectionDomain().getCodeSource().getLocation();
            classLoader = new URLClassLoader(new URL[]{codeSource, staged.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
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

    private void verifyCacheIdentity() {
        try {
            Path currentReal = stagingDirectory.toRealPath();
            if (!currentReal.equals(stagingDirectory) || !currentReal.startsWith(pluginDataReal)) {
                throw new DriverIsolationException("JDBC driver cache identity changed", true);
            }
            Object currentKey = optionalFileKey(currentReal);
            if (stagingFileKey != null) {
                if (!stagingFileKey.equals(currentKey)) {
                    throw new DriverIsolationException("JDBC driver cache identity changed", true);
                }
            } else {
                Path identity = currentReal.resolve(IDENTITY_FILE);
                if (!Files.isRegularFile(identity) || !identity.toRealPath().getParent().equals(currentReal)) {
                    throw new DriverIsolationException("JDBC driver cache identity changed", true);
                }
                CredentialVault.verifySecureFile(identity);
                byte[] currentIdentity = Files.readAllBytes(identity);
                boolean matches = MessageDigest.isEqual(fallbackIdentity, currentIdentity);
                Arrays.fill(currentIdentity, (byte) 0);
                if (!matches) throw new DriverIsolationException("JDBC driver cache identity changed", true);
            }
            CredentialVault.verifySecureDirectory(currentReal);
        } catch (DriverIsolationException e) {
            throw e;
        } catch (IOException e) {
            throw new DriverIsolationException("JDBC driver cache verification failed", true);
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
        return isolation;
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
