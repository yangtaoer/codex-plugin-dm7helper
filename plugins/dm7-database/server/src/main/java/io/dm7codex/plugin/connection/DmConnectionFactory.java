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
    @FunctionalInterface
    public interface ConnectionOpener {
        ManagedConnection open(UUID profileId) throws SQLException;
        default ConnectionLimits limits(UUID profileId) throws SQLException {
            return new ConnectionLimits(ConnectionProfile.MAX_ROWS_LIMIT,
                    ConnectionProfile.MAX_BYTES_LIMIT, ConnectionProfile.MAX_TIMEOUT_SECONDS);
        }
    }

    public record ConnectionLimits(int maxRows, long maxBytes, int queryTimeoutSeconds) {}
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
            Exception handleCleanup = close(handle);
            throw credentialFailure(e, handleCleanup);
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
            return new ManagedConnection(connection, handle, databaseFingerprint(profile));
        } catch (SQLException | RuntimeException e) {
            Exception connectionCleanup = connection == null ? null : close(connection);
            Exception handleCleanup = close(handle);
            throw connectionFailure(e, connectionCleanup, handleCleanup);
        } finally {
            properties.clear();
            Arrays.fill(password, '\0');
        }
    }

    public ConnectionLimits limits(UUID profileId) throws SQLException {
        ConnectionProfile profile = profiles.find(Objects.requireNonNull(profileId, "profileId"))
                .orElseThrow(() -> new SQLException("Connection profile was not found"));
        return new ConnectionLimits(profile.maxRows(), profile.maxBytes(), profile.queryTimeoutSeconds());
    }

    static int timeoutMilliseconds(int seconds) {
        return Math.multiplyExact(seconds, 1_000);
    }

    public static String databaseFingerprint(ConnectionProfile profile) {
        Objects.requireNonNull(profile, "profile");
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

    private static Exception close(AutoCloseable closeable) {
        try {
            closeable.close();
            return null;
        } catch (Exception failure) {
            return failure;
        }
    }

    private static SQLException credentialFailure(RuntimeException original, Exception handleCleanup) {
        DmDriverLoader.DriverIsolationException isolation = isolation(original, null, handleCleanup);
        if (isolation != null) {
            suppress(isolation, original);
            suppressCleanup(isolation, handleCleanup, "JDBC driver cleanup failed");
            throw isolation;
        }
        SQLException safe = new SQLException("Saved credential could not be read");
        suppressSafeCleanupMarker(safe, handleCleanup, "JDBC driver cleanup failed");
        return safe;
    }

    private static SQLException connectionFailure(
            Exception original, Exception connectionCleanup, Exception handleCleanup) {
        DmDriverLoader.DriverIsolationException isolation = isolation(original, connectionCleanup, handleCleanup);
        if (isolation != null) {
            suppress(isolation, original);
            suppressCleanup(isolation, connectionCleanup, "Database connection cleanup failed");
            suppressCleanup(isolation, handleCleanup, "JDBC driver cleanup failed");
            throw isolation;
        }
        SQLException safe = original instanceof SQLException sql
                ? new SQLException("Database connection failed", sql.getSQLState(), sql.getErrorCode())
                : new SQLException("Database connection failed");
        suppressSafeCleanupMarker(safe, connectionCleanup, "Database connection cleanup failed");
        suppressSafeCleanupMarker(safe, handleCleanup, "JDBC driver cleanup failed");
        return safe;
    }

    private static DmDriverLoader.DriverIsolationException isolation(
            Exception original, Exception connectionCleanup, Exception handleCleanup) {
        if (handleCleanup instanceof DmDriverLoader.DriverIsolationException failure) return failure;
        if (connectionCleanup instanceof DmDriverLoader.DriverIsolationException failure) return failure;
        if (original instanceof DmDriverLoader.DriverIsolationException failure) return failure;
        return null;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (secondary != null && secondary != primary) primary.addSuppressed(secondary);
    }

    private static void suppressCleanup(Throwable primary, Exception cleanup, String safeMessage) {
        if (cleanup == null || cleanup == primary) return;
        if (cleanup instanceof DmDriverLoader.DriverIsolationException) {
            primary.addSuppressed(cleanup);
        } else {
            primary.addSuppressed(new SQLException(safeMessage));
        }
    }

    private static void suppressSafeCleanupMarker(SQLException primary, Exception cleanup, String safeMessage) {
        if (cleanup != null) primary.addSuppressed(new SQLException(safeMessage));
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
