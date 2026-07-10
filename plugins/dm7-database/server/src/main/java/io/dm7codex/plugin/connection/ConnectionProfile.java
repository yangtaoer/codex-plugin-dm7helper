package io.dm7codex.plugin.connection;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record ConnectionProfile(
        UUID id,
        String name,
        Path driverJar,
        String driverSha256,
        String driverClass,
        String jdbcUrl,
        String username,
        String schema,
        int connectTimeoutSeconds,
        int socketTimeoutSeconds,
        int queryTimeoutSeconds,
        int maxRows,
        long maxBytes,
        boolean isDefault
) {
    public static final int MAX_TIMEOUT_SECONDS = 3_600;
    public static final int MAX_ROWS_LIMIT = 10_000;
    public static final long MAX_BYTES_LIMIT = 50L * 1024 * 1024;
    public static final String DEFAULT_DRIVER_CLASS = "dm7.jdbc.driver.Dm7Driver";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]{0,127}");

    public ConnectionProfile {
        id = Objects.requireNonNull(id, "id");
        name = requireText(name, "name", 128);
        driverJar = Objects.requireNonNull(driverJar, "driverJar").toAbsolutePath().normalize();
        if (!SHA_256.matcher(Objects.requireNonNull(driverSha256, "driverSha256")).matches()) {
            throw new IllegalArgumentException("driverSha256 must be a SHA-256 hex digest");
        }
        driverSha256 = driverSha256.toLowerCase(Locale.ROOT);
        driverClass = driverClass == null || driverClass.isBlank()
                ? DEFAULT_DRIVER_CLASS : requireText(driverClass, "driverClass", 256);
        jdbcUrl = requireText(jdbcUrl, "jdbcUrl", 8_192);
        if (!jdbcUrl.regionMatches(true, 0, "jdbc:dm7:", 0, "jdbc:dm7:".length())) {
            throw new IllegalArgumentException("jdbcUrl must use the DM7 JDBC scheme");
        }
        username = requireText(username, "username", 256);
        schema = schema == null || schema.isBlank() ? null : schema.trim();
        if (schema != null && !isSafeIdentifier(schema)) {
            throw new IllegalArgumentException("schema must be a single valid identifier");
        }
        requireRange(connectTimeoutSeconds, "connectTimeoutSeconds", 1, MAX_TIMEOUT_SECONDS);
        requireRange(socketTimeoutSeconds, "socketTimeoutSeconds", 1, MAX_TIMEOUT_SECONDS);
        requireRange(queryTimeoutSeconds, "queryTimeoutSeconds", 1, MAX_TIMEOUT_SECONDS);
        requireRange(maxRows, "maxRows", 1, MAX_ROWS_LIMIT);
        if (maxBytes < 1 || maxBytes > MAX_BYTES_LIMIT) {
            throw new IllegalArgumentException("maxBytes is outside the allowed range");
        }
    }

    public static boolean isSafeIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String requireText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return trimmed;
    }

    private static void requireRange(int value, String name, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the allowed range");
        }
    }
}
