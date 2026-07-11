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
        long skipped = 0;
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
                SELECT OWNER, OBJECT_NAME, OBJECT_TYPE FROM (
                  SELECT ordered_objects.*, ROWNUM AS RN FROM (
                    SELECT OWNER, OBJECT_NAME, OBJECT_TYPE FROM (
                      SELECT OWNER, TABLE_NAME AS OBJECT_NAME, 'TABLE' AS OBJECT_TYPE FROM ALL_TABLES
                      UNION ALL
                      SELECT OWNER, VIEW_NAME AS OBJECT_NAME, 'VIEW' AS OBJECT_TYPE FROM ALL_VIEWS
                    ) WHERE (? IS NULL OR OWNER LIKE ?) AND (? IS NULL OR OBJECT_NAME LIKE ?)
                    ORDER BY OWNER, OBJECT_NAME
                  ) ordered_objects WHERE ROWNUM <= ?
                ) WHERE RN > ?
                """;
        var objects = new ArrayList<SchemaObject>();
        try (var statement = connection.prepareStatement(sql)) {
            bindPattern(statement, 1, request.schemaPattern());
            bindPattern(statement, 3, request.objectPattern());
            long upper = Math.addExact(request.offset(), Math.addExact((long) request.limit(), 1L));
            statement.setLong(5, upper);
            statement.setLong(6, request.offset());
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
                SELECT COLUMN_NAME, DATA_TYPE, NULLABLE, COLUMN_ID
                FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? ORDER BY COLUMN_ID
                """;
        var columns = new ArrayList<SchemaColumn>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String typeName = rows.getString(2);
                    columns.add(new SchemaColumn(rows.getString(1), dmJdbcType(typeName), typeName,
                            !"N".equalsIgnoreCase(rows.getString(3)), rows.getInt(4)));
                }
            }
        }
        return List.copyOf(columns);
    }

    static int dmJdbcType(String typeName) {
        if (typeName == null) return Types.OTHER;
        String type = typeName.toUpperCase(java.util.Locale.ROOT);
        if (type.startsWith("VARCHAR")) return Types.VARCHAR;
        if (type.startsWith("CHAR")) return Types.CHAR;
        if (type.startsWith("NVARCHAR")) return Types.NVARCHAR;
        if (type.startsWith("NCHAR")) return Types.NCHAR;
        if (type.startsWith("NCLOB")) return Types.NCLOB;
        if (type.startsWith("LONGVARCHAR")) return Types.LONGVARCHAR;
        if (type.startsWith("LONGVARBINARY") || type.startsWith("IMAGE")) return Types.LONGVARBINARY;
        if (type.startsWith("NUMBER") || type.startsWith("DECIMAL") || type.startsWith("NUMERIC")) return Types.NUMERIC;
        if (type.startsWith("BIGINT")) return Types.BIGINT;
        if (type.startsWith("INT") || type.startsWith("INTEGER")) return Types.INTEGER;
        if (type.startsWith("SMALLINT")) return Types.SMALLINT;
        if (type.startsWith("DOUBLE")) return Types.DOUBLE;
        if (type.startsWith("REAL")) return Types.REAL;
        if (type.startsWith("FLOAT")) return Types.FLOAT;
        if (type.startsWith("TINYINT")) return Types.TINYINT;
        if (type.startsWith("DATETIME")) return Types.TIMESTAMP;
        if (type.startsWith("DATE")) return Types.DATE;
        if (type.startsWith("TIMESTAMP")) return Types.TIMESTAMP;
        if (type.startsWith("TIME")) return Types.TIME;
        if (type.startsWith("BLOB")) return Types.BLOB;
        if (type.startsWith("CLOB") || type.startsWith("TEXT")) return Types.CLOB;
        if (type.startsWith("BINARY") || type.startsWith("VARBINARY") || type.startsWith("RAW")) return Types.VARBINARY;
        if (type.startsWith("BIT") || type.startsWith("BOOLEAN")) return Types.BOOLEAN;
        return Types.OTHER;
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

    public record MetadataRequest(String schemaPattern, String objectPattern, long offset, int limit) {
        public MetadataRequest {
            if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
            if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
            if (offset > Long.MAX_VALUE - limit - 1L) throw new IllegalArgumentException("offset is too large");
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
        public SchemaObject {
            schema = require(schema, "schema"); name = require(name, "name");
            type = require(type, "type"); columns = List.copyOf(columns);
        }
    }
    public record SchemaColumn(String name, int jdbcType, String typeName, boolean nullable, int ordinal) {
        public SchemaColumn {
            name = require(name, "name"); typeName = require(typeName, "typeName");
            if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
        }
    }
    public record SchemaPage(List<SchemaObject> items, long offset, int limit, boolean hasMore) {
        public SchemaPage {
            items = List.copyOf(items);
            if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException("invalid schema page");
        }
    }
    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " is blank");
        return value;
    }
}
