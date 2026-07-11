package io.dm7codex.plugin.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.dm7codex.plugin.connection.DmConnectionFactory;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataServiceTest {
    @Test void requestValidatesPagination() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataService.MetadataRequest("SYSTEM", "T%", -1, 50));
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataService.MetadataRequest("SYSTEM", "T%", 0, 201));
        assertEquals(50, new MetadataService.MetadataRequest("SYSTEM", "T%", 0, 50).limit());
    }

    @Test void metadataFallsBackToParameterizedCatalogQuery() {
        var sql = new ArrayList<String>();
        var opener = new DmConnectionFactory.ConnectionOpener() {
            @Override public DmConnectionFactory.ManagedConnection open(UUID ignored) {
                DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{DatabaseMetaData.class}, (p, m, a) -> {
                            if (m.getName().equals("getTables")) throw new SQLFeatureNotSupportedException();
                            return null;
                        });
                Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Connection.class}, (p, m, a) -> switch (m.getName()) {
                            case "getMetaData" -> metadata;
                            case "prepareStatement" -> {
                                sql.add((String) a[0]);
                                yield preparedCatalogResult((String) a[0]);
                            }
                            case "close" -> null;
                            default -> null;
                        });
                return new DmConnectionFactory.ManagedConnection(connection, () -> {}, "fp");
            }
        };
        var page = new MetadataService(opener).describe(UUID.randomUUID(),
                new MetadataService.MetadataRequest("SYSTEM", "T%", 0, 50));
        assertEquals(1, page.items().size());
        assertTrue(sql.stream().anyMatch(value -> value.contains("ALL_TABLES")));
        assertTrue(sql.stream().anyMatch(value -> value.contains("ALL_VIEWS")));
        assertTrue(sql.stream().anyMatch(value -> value.contains("ROWNUM")));
        assertEquals(Types.NUMERIC, page.items().get(0).columns().get(0).jdbcType());
        assertTrue(sql.stream().allMatch(value -> value.contains("?")));
    }

    private static PreparedStatement preparedCatalogResult(String sql) {
        ResultSet rows = sql.contains("ALL_TAB_COLUMNS")
                ? TestJdbc.resultSet(List.of(List.of("ID", "NUMBER(10)", "N", 1)),
                        List.of("COLUMN_NAME", "DATA_TYPE", "NULLABLE", "COLUMN_ID"))
                : TestJdbc.resultSet(List.of(List.of("SYSTEM", "T1", "TABLE")),
                        List.of("OWNER", "OBJECT_NAME", "OBJECT_TYPE"));
        return (PreparedStatement) Proxy.newProxyInstance(MetadataServiceTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> rows;
                    case "setString", "setInt", "close" -> null;
                    default -> null;
                });
    }
}
