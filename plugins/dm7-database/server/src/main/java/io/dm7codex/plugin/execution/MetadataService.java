package io.dm7codex.plugin.execution;

import io.dm7codex.plugin.connection.DmConnectionFactory;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;

public final class MetadataService {
    public interface MetadataReader {
        SchemaPage describe(UUID profileId, MetadataRequest request);
    }
    private final DmConnectionFactory.ConnectionOpener connections;

    public MetadataService(DmConnectionFactory factory) { this(factory::open); }
    public MetadataService(DmConnectionFactory.ConnectionOpener connections) {
        this.connections = Objects.requireNonNull(connections);
    }

    public SchemaPage describe(UUID profileId, MetadataRequest request) {
        Objects.requireNonNull(profileId); Objects.requireNonNull(request);
        try (var managed = connections.open(profileId)) {
            var connection = managed.connection();
            List<SchemaObject> objects;
            try {
                objects = jdbcObjects(connection, request);
                if (objects.isEmpty()) objects = fallbackObjects(connection, request);
            } catch (SQLFeatureNotSupportedException unsupported) {
                objects = fallbackObjects(connection, request);
            }
            boolean hasMore = objects.size() > request.limit();
            if (hasMore) objects = new ArrayList<>(objects.subList(0, request.limit()));
            return new SchemaPage(objects, request.offset(), request.limit(), hasMore);
        } catch (Exception failure) {
            throw new MetadataAccessException(failure);
        }
    }

    private static List<SchemaObject> jdbcObjects(java.sql.Connection connection, MetadataRequest request)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        var result = new ArrayList<SchemaObject>();
        int skipped = 0;
        try (ResultSet tables = metadata.getTables(null, request.schemaPattern(),
                request.objectPattern(), new String[]{"TABLE", "VIEW"})) {
            while (tables.next() && result.size() <= request.limit()) {
                if (skipped++ < request.offset()) continue;
                String schema = tables.getString("TABLE_SCHEM");
                String name = tables.getString("TABLE_NAME");
                String type = tables.getString("TABLE_TYPE");
                var columns = jdbcColumns(metadata, schema, name);
                if (columns.isEmpty()) columns = fallbackColumns(connection, schema, name);
                result.add(new SchemaObject(schema, name, type, columns));
            }
        }
        return result;
    }

    private static List<SchemaColumn> jdbcColumns(DatabaseMetaData metadata, String schema, String table)
            throws SQLException {
        var columns = new ArrayList<SchemaColumn>();
        try (ResultSet rows = metadata.getColumns(null, schema, table, "%")) {
            while (rows.next()) columns.add(new SchemaColumn(rows.getString("COLUMN_NAME"),
                    rows.getInt("DATA_TYPE"), rows.getString("TYPE_NAME"),
                    rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    rows.getInt("ORDINAL_POSITION")));
        } catch (SQLFeatureNotSupportedException unsupported) {
            // An incomplete columns result is handled by the catalog fallback below when required.
        }
        return List.copyOf(columns);
    }

    private static List<SchemaObject> fallbackObjects(java.sql.Connection connection,
            MetadataRequest request) throws SQLException {
        String sql = """
                SELECT OWNER, OBJECT_NAME, OBJECT_TYPE FROM ALL_TABLES
                WHERE (? IS NULL OR OWNER LIKE ?) AND (? IS NULL OR OBJECT_NAME LIKE ?)
                ORDER BY OWNER, OBJECT_NAME OFFSET ? ROWS FETCH NEXT ? ROWS ONLY
                """;
        var objects = new ArrayList<SchemaObject>();
        try (var statement = connection.prepareStatement(sql)) {
            bindPattern(statement, 1, request.schemaPattern());
            bindPattern(statement, 3, request.objectPattern());
            statement.setInt(5, request.offset());
            statement.setInt(6, request.limit() + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String schema = rows.getString(1);
                    String name = rows.getString(2);
                    objects.add(new SchemaObject(schema, name, rows.getString(3),
                            fallbackColumns(connection, schema, name)));
                }
            }
        }
        return objects;
    }

    private static List<SchemaColumn> fallbackColumns(java.sql.Connection connection,
            String schema, String table) throws SQLException {
        String sql = """
                SELECT COLUMN_NAME, DATA_TYPE, DATA_TYPE_NAME, NULLABLE, COLUMN_ID
                FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID
                """;
        var columns = new ArrayList<SchemaColumn>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    int jdbcType;
                    try { jdbcType = rows.getInt(2); }
                    catch (SQLException incompatible) { jdbcType = Types.OTHER; }
                    columns.add(new SchemaColumn(rows.getString(1), jdbcType, rows.getString(3),
                            !"N".equalsIgnoreCase(rows.getString(4)), rows.getInt(5)));
                }
            }
        }
        return List.copyOf(columns);
    }

    private static void bindPattern(java.sql.PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
            statement.setNull(index + 1, Types.VARCHAR);
        } else {
            statement.setString(index, value);
            statement.setString(index + 1, value);
        }
    }

    public static final class MetadataAccessException extends RuntimeException {
        MetadataAccessException(Throwable cause) { super("Database metadata could not be read", cause); }
    }

    public record MetadataRequest(String schemaPattern, String objectPattern, int offset, int limit) {
        public MetadataRequest {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
            schemaPattern = pattern(schemaPattern, "schemaPattern");
            objectPattern = pattern(objectPattern, "objectPattern");
        }
        private static String pattern(String value, String name) {
            if (value == null) return null;
            String trimmed = value.trim();
            if (trimmed.length() > 256) throw new IllegalArgumentException(name + " is too long");
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    public record SchemaObject(String schema, String name, String type, List<SchemaColumn> columns) {
        public SchemaObject { columns = List.copyOf(columns); }
    }
    public record SchemaColumn(String name, int jdbcType, String typeName, boolean nullable, int ordinal) {}
    public record SchemaPage(List<SchemaObject> items, int offset, int limit, boolean hasMore) {
        public SchemaPage { items = List.copyOf(items); }
    }
}
