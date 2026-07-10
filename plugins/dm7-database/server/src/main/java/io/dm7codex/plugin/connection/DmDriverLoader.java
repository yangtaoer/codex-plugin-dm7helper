package io.dm7codex.plugin.connection;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;

public final class DmDriverLoader {
    public DriverHandle load(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Path jar = profile.driverJar().toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalArgumentException("Configured JDBC driver is missing or is not a regular file");
        }
        String actual = sha256(jar);
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(profile.driverSha256()), HexFormat.of().parseHex(actual))) {
            throw new SecurityException("Configured JDBC driver failed SHA-256 verification");
        }
        URLClassLoader classLoader;
        try {
            classLoader = new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        } catch (IOException e) {
            throw new IllegalStateException("JDBC driver could not be opened");
        }
        try {
            Class<?> type = Class.forName(profile.driverClass(), true, classLoader);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalArgumentException("Configured driver class does not implement java.sql.Driver");
            }
            return new DriverHandle(classLoader, driver);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            try {
                classLoader.close();
            } catch (IOException ignored) {
                // The primary safe error is more useful than a secondary classloader close failure.
            }
            if (e instanceof IllegalArgumentException illegal) throw illegal;
            throw new IllegalArgumentException("Configured JDBC driver class could not be loaded");
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

    public static final class DriverHandle implements AutoCloseable {
        private final URLClassLoader classLoader;
        private final Driver driver;
        private boolean closed;

        private DriverHandle(URLClassLoader classLoader, Driver driver) {
            this.classLoader = classLoader;
            this.driver = driver;
        }

        public synchronized Connection connect(String jdbcUrl, Properties properties) throws SQLException {
            if (closed) throw new SQLException("JDBC driver handle is closed");
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) throw new SQLException("JDBC driver did not accept the configured URL");
            return connection;
        }

        @Override public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                classLoader.close();
            }
        }
    }
}
