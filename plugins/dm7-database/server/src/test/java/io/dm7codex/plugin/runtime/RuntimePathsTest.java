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
    void missingPluginDataUsesSandboxWritableUserTempFallback() {
        var pluginRoot = tempDir.resolve("read-only-plugin");
        var userTemp = tempDir.resolve("user temp");

        var paths = RuntimePaths.fromEnvironment(
                Map.of("TEMP", userTemp.toString(), "CODEX_HOME", tempDir.resolve("read-only-home").toString()),
                pluginRoot);

        assertEquals(userTemp.resolve("dm7-codex-plugin-data").toAbsolutePath().normalize(),
                paths.pluginData());
        assertTrue(paths.stateDatabase().startsWith(paths.pluginData()));
    }

    @Test
    void missingPluginDataAndTempUsesJvmTempFallback() {
        var pluginRoot = tempDir.resolve("read-only-plugin");
        var original = System.getProperty("java.io.tmpdir");
        var jvmTemp = tempDir.resolve("jvm temp");
        System.setProperty("java.io.tmpdir", jvmTemp.toString());
        try {
            var paths = RuntimePaths.fromEnvironment(Map.of(), pluginRoot);

            assertEquals(jvmTemp.resolve("dm7-codex-plugin-data").toAbsolutePath().normalize(),
                    paths.pluginData());
        } finally {
            if (original == null) System.clearProperty("java.io.tmpdir");
            else System.setProperty("java.io.tmpdir", original);
        }
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
