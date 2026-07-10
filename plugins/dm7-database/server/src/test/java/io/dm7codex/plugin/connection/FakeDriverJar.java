package io.dm7codex.plugin.connection;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class FakeDriverJar {
    static final String DRIVER_CLASS = "fixture.FakeDmDriver";

    private FakeDriverJar() {}

    static Fixture create(Path directory) throws Exception {
        return compile(directory, DRIVER_CLASS, """
                package fixture;
                import java.lang.reflect.*;
                import java.sql.*;
                import java.util.*;
                import java.util.logging.Logger;

                public final class FakeDmDriver implements Driver {
                    static {
                        try { DriverManager.registerDriver(new FakeDmDriver()); }
                        catch (SQLException e) { throw new ExceptionInInitializerError(e); }
                    }
                    public Connection connect(String url, Properties info) throws SQLException {
                        System.setProperty("dm7.fixture.user", String.valueOf(info.getProperty("user")));
                        System.setProperty("dm7.fixture.password", String.valueOf(info.getProperty("password")));
                        System.setProperty("dm7.fixture.connectTimeout", String.valueOf(info.getProperty("connectTimeout")));
                        System.setProperty("dm7.fixture.socketTimeout", String.valueOf(info.getProperty("socketTimeout")));
                        if ("true".equals(info.getProperty("registerOnConnect"))) DriverManager.registerDriver(new FakeDmDriver());
                        if (url.contains("forceFailure")) throw new SQLException("connection rejected: " + url + " " + info);
                        return proxy(Connection.class, (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getMetaData" -> metadata();
                                case "createStatement" -> statement();
                                case "getClientInfo" -> args != null && args.length == 1 && "propertiesEmpty".equals(args[0])
                                        ? Boolean.toString(info.isEmpty()) : null;
                                case "close" -> null;
                                case "isClosed" -> false;
                                case "unwrap" -> null;
                                case "isWrapperFor" -> false;
                                default -> defaultValue(method.getReturnType());
                            };
                        });
                    }
                    private static DatabaseMetaData metadata() {
                        return proxy(DatabaseMetaData.class, (p, m, a) -> switch (m.getName()) {
                            case "getDriverName" -> "Fake DM7 JDBC";
                            case "getDriverVersion" -> "7.0-test";
                            case "getDatabaseProductVersion" -> "DM Database Server 7-test";
                            case "getUserName" -> "测试用户";
                            default -> defaultValue(m.getReturnType());
                        });
                    }
                    private static Statement statement() {
                        return proxy(Statement.class, (p, m, a) -> {
                            if (m.getName().equals("execute")) {
                                System.setProperty("dm7.fixture.schemaSql", String.valueOf(a[0]));
                                return false;
                            }
                            if (m.getName().equals("executeQuery")) {
                                String sql = String.valueOf(a[0]);
                                String value = sql.contains("中文连接测试") ? "中文连接测试" : "业务模式";
                                return resultSet(value);
                            }
                            return defaultValue(m.getReturnType());
                        });
                    }
                    private static ResultSet resultSet(String value) {
                        final boolean[] first = {true};
                        return proxy(ResultSet.class, (p, m, a) -> switch (m.getName()) {
                            case "next" -> { boolean result = first[0]; first[0] = false; yield result; }
                            case "getString" -> value;
                            default -> defaultValue(m.getReturnType());
                        });
                    }
                    @SuppressWarnings("unchecked")
                    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
                        return (T) Proxy.newProxyInstance(FakeDmDriver.class.getClassLoader(), new Class<?>[]{type}, handler);
                    }
                    private static Object defaultValue(Class<?> type) {
                        if (!type.isPrimitive()) return null;
                        if (type == boolean.class) return false;
                        if (type == byte.class) return (byte) 0;
                        if (type == short.class) return (short) 0;
                        if (type == int.class) return 0;
                        if (type == long.class) return 0L;
                        if (type == float.class) return 0F;
                        if (type == double.class) return 0D;
                        if (type == char.class) return '\0';
                        return null;
                    }
                    public boolean acceptsURL(String url) { return url.startsWith("jdbc:dm7:"); }
                    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 7; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public Logger getParentLogger() { return Logger.getGlobal(); }
                }
                """);
    }

    static Fixture createNonDriver(Path directory) throws Exception {
        return compile(directory, "fixture.NotADriver", "package fixture; public final class NotADriver {}\n");
    }

    static Fixture createRegisterThenFail(Path directory) throws Exception {
        return compile(directory, "fixture.RegisterThenFailDriver", """
                package fixture;
                import java.lang.ref.WeakReference;
                import java.sql.*;
                import java.util.Properties;
                import java.util.logging.Logger;
                public final class RegisterThenFailDriver implements Driver {
                    private static boolean registrationComplete;
                    static {
                        try {
                            System.getProperties().put("dm7.fixture.failedLoader",
                                    new WeakReference<>(RegisterThenFailDriver.class.getClassLoader()));
                            DriverManager.registerDriver(new RegisterThenFailDriver());
                        } catch (SQLException e) { throw new ExceptionInInitializerError(e); }
                    }
                    public RegisterThenFailDriver() {
                        if (registrationComplete) throw new IllegalStateException("fixture construction failure");
                        registrationComplete = true;
                    }
                    public Connection connect(String u, Properties p) { return null; }
                    public boolean acceptsURL(String u) { return true; }
                    public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) { return new DriverPropertyInfo[0]; }
                    public int getMajorVersion() { return 7; }
                    public int getMinorVersion() { return 0; }
                    public boolean jdbcCompliant() { return false; }
                    public Logger getParentLogger() { return Logger.getGlobal(); }
                }
                """);
    }

    private static Fixture compile(Path directory, String className, String source) throws Exception {
        Files.createDirectories(directory);
        Path sourceRoot = directory.resolve("src");
        Path classes = directory.resolve("classes");
        Path sourceFile = sourceRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.createDirectories(classes);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-encoding", "UTF-8", "-d", classes.toString(), sourceFile.toString());
        if (result != 0) throw new IllegalStateException("fixture compilation failed");
        Path jar = directory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var files = Files.walk(classes)) {
                files.filter(Files::isRegularFile).forEach(file -> add(output, classes, file));
            }
        }
        return new Fixture(jar, sha256(jar), className);
    }

    private static void add(JarOutputStream output, Path root, Path file) {
        try {
            output.putNextEntry(new JarEntry(root.relativize(file).toString().replace('\\', '/')));
            Files.copy(file, output);
            output.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    record Fixture(Path jar, String sha256, String driverClass) {}
}
