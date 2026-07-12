package io.dm7codex.plugin.runtime;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class RuntimePaths {
    private final Path pluginRoot;
    private final Path pluginData;

    private RuntimePaths(Path pluginRoot, Path pluginData) {
        this.pluginRoot = normalize(pluginRoot, "pluginRoot");
        this.pluginData = normalize(pluginData, "pluginData");
    }

    public static RuntimePaths fromEnvironment(Map<String, String> environment, Path pluginRoot) {
        Objects.requireNonNull(environment, "environment");
        var configuredData = environment.get("PLUGIN_DATA");
        if (configuredData == null || configuredData.isBlank()) {
            var userTemp = environment.get("TEMP");
            if (userTemp == null || userTemp.isBlank()) userTemp = environment.get("TMP");
            if (userTemp == null || userTemp.isBlank()) userTemp = environment.get("TMPDIR");
            if (userTemp == null || userTemp.isBlank()) userTemp = System.getProperty("java.io.tmpdir");
            if (userTemp == null || userTemp.isBlank()) {
                throw new IllegalStateException("A sandbox-writable temporary directory is required");
            }
            configuredData = Path.of(userTemp).resolve("dm7-codex-plugin-data").toString();
        }
        return new RuntimePaths(pluginRoot, Path.of(configuredData));
    }

    public static RuntimePaths forTest(Path pluginData) {
        var normalizedData = normalize(pluginData, "pluginData");
        return new RuntimePaths(normalizedData.resolve("plugin-root"), normalizedData);
    }

    public Path pluginRoot() {
        return pluginRoot;
    }

    public Path pluginData() {
        return pluginData;
    }

    public Path configDirectory() {
        return pluginData.resolve("config");
    }

    public Path secretsDirectory() {
        return pluginData.resolve("secrets");
    }

    public Path driverCacheDirectory() {
        return pluginData.resolve("cache").resolve("jdbc-drivers");
    }

    public Path stateDirectory() {
        return pluginData.resolve("state");
    }

    public Path stateDatabase() {
        return stateDirectory().resolve("plugin.db");
    }

    public Path sessionContextDirectory() {
        return pluginData.resolve("session-context");
    }

    public Path sessionsDirectory() {
        return pluginData.resolve("sessions");
    }

    public Path exportsDirectory() {
        return pluginData.resolve("exports");
    }

    public Path logsDirectory() {
        return pluginData.resolve("logs");
    }

    private static Path normalize(Path path, String name) {
        return Objects.requireNonNull(path, name).toAbsolutePath().normalize();
    }
}
