package io.dm7codex.plugin.connection;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

public final class DmDriverLoader {
    private static final String CLEANER_CLASS = "io.dm7codex.plugin.connection.ChildDriverRegistryCleaner";
    private final Path stagingDirectory;

    public DmDriverLoader(Path stagingDirectory) {
        this.stagingDirectory = Objects.requireNonNull(stagingDirectory, "stagingDirectory")
                .toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.stagingDirectory);
            CredentialVault.secure(this.stagingDirectory, true);
        } catch (IOException e) {
            throw new IllegalStateException("Secure JDBC driver staging is unavailable");
        }
    }

    public DriverHandle load(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Path source = profile.driverJar();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Configured JDBC driver is missing or is not a regular file");
        }
        Path staged = null;
        URLClassLoader classLoader = null;
        Method cleanup = null;
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
            Method count = cleanerClass.getDeclaredMethod("registeredChildDriverCount");
            cleanup.setAccessible(true);
            count.setAccessible(true);

            Class<?> type = Class.forName(profile.driverClass(), true, classLoader);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalArgumentException("Configured driver class does not implement java.sql.Driver");
            }
            invokeCleanup(cleanup);
            return new DriverHandle(classLoader, driver, staged, cleanup, count);
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException e) {
            cleanupAfterFailedLoad(cleanup, classLoader, staged);
            if (e instanceof SecurityException security) throw security;
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Configured JDBC driver could not be loaded");
        }
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

    private static void cleanupAfterFailedLoad(Method cleanup, URLClassLoader classLoader, Path staged) {
        if (cleanup != null) {
            try {
                invokeCleanup(cleanup);
            } catch (RuntimeException ignored) {
                // Continue all cleanup steps; the public load failure stays safe and generic.
            }
        }
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // Continue to remove the staged copy.
            }
        }
        if (staged != null) {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
                // The original load error remains authoritative.
            }
        }
    }

    private static void invokeCleanup(Method method) {
        try {
            method.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("JDBC driver registration cleanup failed");
        }
    }

    private static int invokeCount(Method method) {
        try {
            return (Integer) method.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("JDBC driver registration inspection failed");
        }
    }

    public static final class DriverHandle implements AutoCloseable {
        private final URLClassLoader classLoader;
        private final Driver driver;
        private final Path stagedJar;
        private final Method cleanup;
        private final Method count;
        private boolean closed;

        private DriverHandle(URLClassLoader classLoader, Driver driver, Path stagedJar, Method cleanup, Method count) {
            this.classLoader = classLoader;
            this.driver = driver;
            this.stagedJar = stagedJar;
            this.cleanup = cleanup;
            this.count = count;
        }

        public synchronized Connection connect(String jdbcUrl, Properties properties) throws SQLException {
            if (closed) throw new SQLException("JDBC driver handle is closed");
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) throw new SQLException("JDBC driver did not accept the configured URL");
            return connection;
        }

        int registeredDriverCount() {
            return invokeCount(count);
        }

        ClassLoader driverClassLoader() {
            return classLoader;
        }

        @Override public synchronized void close() throws Exception {
            if (closed) return;
            closed = true;
            Exception primary = null;
            try {
                invokeCleanup(cleanup);
            } catch (RuntimeException e) {
                primary = e;
            }
            try {
                classLoader.close();
            } catch (Exception e) {
                if (primary == null) primary = e; else primary.addSuppressed(e);
            }
            try {
                Files.deleteIfExists(stagedJar);
            } catch (Exception e) {
                if (primary == null) primary = e; else primary.addSuppressed(e);
            }
            if (primary != null) throw primary;
        }
    }
}

/** Must be loaded by the child loader so DriverManager's caller filtering exposes child registrations. */
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

    public static void deregisterChildDrivers() throws SQLException {
        ClassLoader child = ChildDriverRegistryCleaner.class.getClassLoader();
        List<Driver> registered = new ArrayList<>();
        Enumeration<Driver> drivers = DriverManager.getDrivers();
        while (drivers.hasMoreElements()) {
            Driver driver = drivers.nextElement();
            if (driver.getClass().getClassLoader() == child) registered.add(driver);
        }
        SQLException failure = null;
        for (Driver driver : registered) {
            try {
                DriverManager.deregisterDriver(driver);
            } catch (SQLException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }
}
