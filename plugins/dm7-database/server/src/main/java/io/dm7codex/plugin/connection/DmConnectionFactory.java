package io.dm7codex.plugin.connection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

public final class DmConnectionFactory {
    private final ConnectionConfigRepository profiles;
    private final SecretStore vault;
    private final DmDriverLoader driverLoader;

    public DmConnectionFactory(ConnectionConfigRepository profiles, SecretStore vault, DmDriverLoader driverLoader) {
        this.profiles = Objects.requireNonNull(profiles, "profiles");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.driverLoader = Objects.requireNonNull(driverLoader, "driverLoader");
    }

    public ManagedConnection open(UUID profileId) throws SQLException {
        ConnectionProfile profile = profiles.find(Objects.requireNonNull(profileId, "profileId"))
                .orElseThrow(() -> new SQLException("Connection profile was not found"));
        DmDriverLoader.DriverHandle handle;
        try {
            handle = driverLoader.load(profile);
        } catch (DmDriverLoader.DriverIsolationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SQLException("JDBC driver validation failed");
        }
        char[] password;
        try {
            password = vault.read(profile.id()).orElseGet(() -> new char[0]);
        } catch (RuntimeException e) {
            closeQuietly(handle);
            throw new SQLException("Saved credential could not be read");
        }
        Connection connection = null;
        Properties properties = new Properties();
        try {
            properties.setProperty("user", profile.username());
            // java.sql.Driver requires a String here; clear the container and wipe the source char[] below.
            properties.setProperty("password", new String(password));
            properties.setProperty("connectTimeout", Integer.toString(timeoutMilliseconds(profile.connectTimeoutSeconds())));
            properties.setProperty("socketTimeout", Integer.toString(timeoutMilliseconds(profile.socketTimeoutSeconds())));
            connection = handle.connect(profile.jdbcUrl(), properties);
            if (profile.schema() != null) {
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(profile.queryTimeoutSeconds());
                    statement.execute("SET SCHEMA " + profile.schema());
                }
            }
            return new ManagedConnection(connection, handle, fingerprint(profile));
        } catch (SQLException | RuntimeException e) {
            if (connection != null) closeQuietly(connection);
            closeQuietly(handle);
            if (e instanceof DmDriverLoader.DriverIsolationException isolation) throw isolation;
            if (e instanceof SQLException sql) {
                throw new SQLException("Database connection failed", sql.getSQLState(), sql.getErrorCode());
            }
            throw new SQLException("Database connection failed");
        } finally {
            properties.clear();
            Arrays.fill(password, '\0');
        }
    }

    static int timeoutMilliseconds(int seconds) {
        return Math.multiplyExact(seconds, 1_000);
    }

    private static String fingerprint(ConnectionProfile profile) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, profile.driverSha256());
            update(digest, JdbcUrlDiagnostics.redact(profile.jdbcUrl()));
            update(digest, profile.username());
            update(digest, profile.schema() == null ? "" : profile.schema());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Safe cleanup path: do not surface driver messages that may contain connection data.
        }
    }

    public record ManagedConnection(Connection connection, AutoCloseable driverHandle, String databaseFingerprint)
            implements AutoCloseable {
        public ManagedConnection {
            Objects.requireNonNull(connection, "connection");
            Objects.requireNonNull(driverHandle, "driverHandle");
            Objects.requireNonNull(databaseFingerprint, "databaseFingerprint");
        }

        @Override public void close() throws Exception {
            Exception primary = null;
            try {
                connection.close();
            } catch (Exception e) {
                primary = e;
            }
            try {
                driverHandle.close();
            } catch (Exception e) {
                if (primary != null) primary.addSuppressed(e); else primary = e;
            }
            if (primary != null) throw primary;
        }
    }
}
