package io.dm7codex.plugin.connection;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ConnectionTestService {
    private static final String CHINESE_PROBE = "中文连接测试";
    private final DmConnectionFactory connectionFactory;
    private final ConnectionConfigRepository profiles;

    public ConnectionTestService(DmConnectionFactory connectionFactory, ConnectionConfigRepository profiles) {
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
        this.profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public ConnectionTestResult test(UUID profileId) {
        ConnectionProfile profile = profiles.find(Objects.requireNonNull(profileId, "profileId")).orElse(null);
        if (profile == null) return failure(List.of());
        List<String> warnings = JdbcUrlDiagnostics.inspect(profile.jdbcUrl()).warnings();
        long started = System.nanoTime();
        try (DmConnectionFactory.ManagedConnection managed = connectionFactory.open(profileId)) {
            long latencyMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
            DatabaseMetaData metadata = managed.connection().getMetaData();
            String driverVersion = join(metadata.getDriverName(), metadata.getDriverVersion());
            String serverVersion = join(metadata.getDatabaseProductName(), metadata.getDatabaseProductVersion());
            String actualUser = safe(metadata.getUserName());
            String actualSchema = scalar(managed, profile, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')");
            boolean chineseRoundTrip = CHINESE_PROBE.equals(scalar(managed, profile, "SELECT '" + CHINESE_PROBE + "'"));
            return new ConnectionTestResult(true, latencyMs, driverVersion, serverVersion, actualUser,
                    actualSchema, chineseRoundTrip, warnings);
        } catch (Exception ignored) {
            return failure(warnings);
        }
    }

    private static String scalar(DmConnectionFactory.ManagedConnection managed, ConnectionProfile profile, String sql)
            throws Exception {
        try (Statement statement = managed.connection().createStatement()) {
            statement.setQueryTimeout(profile.queryTimeoutSeconds());
            statement.setMaxRows(1);
            try (ResultSet rows = statement.executeQuery(sql)) {
                return rows.next() ? safe(rows.getString(1)) : "";
            }
        }
    }

    private static ConnectionTestResult failure(List<String> diagnostics) {
        var warnings = new java.util.ArrayList<>(diagnostics);
        warnings.add("Connection test failed; verify the saved settings and JDBC driver.");
        return new ConnectionTestResult(false, 0, "", "", "", "", false,
                warnings);
    }

    private static String join(String left, String right) {
        left = safe(left);
        right = safe(right);
        return left.isEmpty() ? right : right.isEmpty() ? left : left + " " + right;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record ConnectionTestResult(
            boolean success,
            long latencyMs,
            String driverVersion,
            String serverVersion,
            String actualUser,
            String actualSchema,
            boolean chineseRoundTrip,
            List<String> warnings
    ) {
        public ConnectionTestResult {
            driverVersion = safe(driverVersion);
            serverVersion = safe(serverVersion);
            actualUser = safe(actualUser);
            actualSchema = safe(actualSchema);
            warnings = List.copyOf(warnings);
        }
    }
}
