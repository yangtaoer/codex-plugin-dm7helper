package io.dm7codex.plugin.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void missingPluginDataFailsClosedOutsideTests() {
        var pluginRoot = tempDir.resolve("read-only-plugin");

        assertThrows(
                IllegalStateException.class,
                () -> RuntimePaths.fromEnvironment(Map.of(), pluginRoot));
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
