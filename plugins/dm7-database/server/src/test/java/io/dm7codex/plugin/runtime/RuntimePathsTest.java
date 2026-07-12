package io.dm7codex.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimePathsTest {
    @TempDir
    Path tempDir;

    @Test
    void missingPluginDataUsesPrivateCodexHomeFallback() {
        var pluginRoot = tempDir.resolve("read-only-plugin");
        var codexHome = tempDir.resolve("codex home");

        var paths = RuntimePaths.fromEnvironment(
                Map.of("CODEX_HOME", codexHome.toString()), pluginRoot);

        assertEquals(codexHome.resolve("plugin-data/dm7-database").toAbsolutePath().normalize(),
                paths.pluginData());
        assertTrue(paths.stateDatabase().startsWith(paths.pluginData()));
    }

    @Test
    void missingPluginDataAndCodexHomeUsesUserHomeFallback() {
        var pluginRoot = tempDir.resolve("read-only-plugin");
        var userHome = tempDir.resolve("user home");

        var paths = RuntimePaths.fromEnvironment(
                Map.of("USERPROFILE", userHome.toString()), pluginRoot);

        assertEquals(userHome.resolve(".codex/plugin-data/dm7-database").toAbsolutePath().normalize(),
                paths.pluginData());
    }

    @Test
    void writableLayoutIsConfinedToPluginData() {
        var pluginRoot = tempDir.resolve("installed-plugin").toAbsolutePath().normalize();
        var pluginData = tempDir.resolve("runtime-data").toAbsolutePath().normalize();

        var paths = RuntimePaths.fromEnvironment(
                Map.of("PLUGIN_DATA", pluginData.toString()), pluginRoot);

        assertEquals(pluginRoot, paths.pluginRoot());
        assertEquals(pluginData, paths.pluginData());
        assertEquals(pluginData.resolve("state/plugin.db"), paths.stateDatabase());
        for (var writablePath : List.of(
                paths.configDirectory(),
                paths.driverCacheDirectory(),
                paths.secretsDirectory(),
                paths.stateDirectory(),
                paths.sessionContextDirectory(),
                paths.sessionsDirectory(),
                paths.exportsDirectory(),
                paths.logsDirectory())) {
            assertTrue(writablePath.startsWith(pluginData), () -> writablePath + " escaped PLUGIN_DATA");
        }
    }

    @Test
    void testFactoryUsesOnlyTheProvidedTemporaryRoot() {
        var paths = RuntimePaths.forTest(tempDir);

        assertEquals(tempDir.toAbsolutePath().normalize(), paths.pluginData());
        assertTrue(paths.stateDatabase().startsWith(paths.pluginData()));
        assertTrue(paths.sessionsDirectory().startsWith(paths.pluginData()));
        assertEquals(paths.pluginData().resolve("cache/jdbc-drivers"), paths.driverCacheDirectory());
    }
}
