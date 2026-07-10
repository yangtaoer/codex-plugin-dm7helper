package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import java.lang.ref.WeakReference;

import static org.junit.jupiter.api.Assertions.*;

class DmDriverLoaderTest {
    @TempDir Path tempDir;

    @Test void verifiesHashLoadsExplicitDriverAndDoesNotRegisterGlobally() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver"));
        Path cache = tempDir.resolve("cache");
        DmDriverLoader loader = new DmDriverLoader(cache);
        ConnectionProfile profile = profile(fixture);
        DmDriverLoader.DriverHandle handle = loader.load(profile);
        WeakReference<ClassLoader> classLoader;
        try {
            assertEquals(0, handle.registeredDriverCount());
            Properties properties = new Properties();
            properties.setProperty("user", "fixture-user");
            properties.setProperty("registerOnConnect", "true");
            assertNotNull(handle.connect(profile.jdbcUrl(), properties));
            assertEquals(1, handle.registeredDriverCount());
            classLoader = new WeakReference<>(handle.driverClassLoader());
        } finally {
            handle.close();
        }
        assertEquals(0, handle.registeredDriverCount());
        assertDirectoryEmpty(cache);
        handle = null;
        for (int i = 0; i < 20 && classLoader.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(classLoader.get(), "closed driver classloader must be releasable");
    }

    @Test void rejectsMissingDirectoryWrongHashAndNonDriverWithoutLeakingPath() throws Exception {
        Path cache = tempDir.resolve("reject-cache");
        DmDriverLoader loader = new DmDriverLoader(cache);
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("valid"));
        Exception hash = assertThrows(Exception.class, () -> loader.load(new ConnectionProfile(UUID.randomUUID(), "x",
                fixture.jar(), "f".repeat(64), fixture.driverClass(), "jdbc:dm7://host:5236", "u", null,
                10, 30, 60, 1000, 1024, true)));
        assertFalse(hash.getMessage().contains(fixture.jar().toString()));
        Files.createDirectories(tempDir.resolve("directory.jar"));
        assertThrows(Exception.class, () -> loader.load(profile(tempDir.resolve("directory.jar"), "0".repeat(64), fixture.driverClass())));
        assertThrows(Exception.class, () -> loader.load(profile(tempDir.resolve("missing.jar"), "0".repeat(64), fixture.driverClass())));
        FakeDriverJar.Fixture nonDriver = FakeDriverJar.createNonDriver(tempDir.resolve("not-driver"));
        assertThrows(Exception.class, () -> loader.load(profile(nonDriver.jar(), nonDriver.sha256(), nonDriver.driverClass())));
        assertDirectoryEmpty(cache);
    }

    @Test void sourceJarReplacementAfterLoadCannotChangeExecutedDriver() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("source"));
        Path cache = tempDir.resolve("staging-cache");
        DmDriverLoader loader = new DmDriverLoader(cache);
        ConnectionProfile profile = profile(fixture);
        try (DmDriverLoader.DriverHandle handle = loader.load(profile)) {
            Files.writeString(fixture.jar(), "replacement-not-a-jar");
            assertNotNull(handle.connect(profile.jdbcUrl(), new Properties()));
            try (var files = Files.list(cache)) {
                assertEquals(1, files.count());
            }
        }
        assertDirectoryEmpty(cache);
    }

    @Test void classInitializationFailureCleansStaticRegistrationAndStaging() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.createRegisterThenFail(tempDir.resolve("register-fail"));
        Path cache = tempDir.resolve("failure-cleanup-cache");
        DmDriverLoader loader = new DmDriverLoader(cache);
        assertThrows(Exception.class, () -> loader.load(profile(fixture)));
        @SuppressWarnings("unchecked")
        WeakReference<ClassLoader> failedLoader =
                (WeakReference<ClassLoader>) System.getProperties().remove("dm7.fixture.failedLoader");
        assertNotNull(failedLoader);
        for (int i = 0; i < 20 && failedLoader.get() != null; i++) {
            System.gc();
            Thread.sleep(25);
        }
        assertNull(failedLoader.get(), "failed driver loader must not be retained by DriverManager");
        assertDirectoryEmpty(cache);
    }

    private static void assertDirectoryEmpty(Path directory) throws Exception {
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    static ConnectionProfile profile(FakeDriverJar.Fixture fixture) {
        return profile(fixture.jar(), fixture.sha256(), fixture.driverClass());
    }

    private static ConnectionProfile profile(Path jar, String sha, String driverClass) {
        return new ConnectionProfile(UUID.randomUUID(), "fixture", jar, sha, driverClass,
                "jdbc:dm7://fixture.invalid:5236?dbname=TEST", "fixture-user", "业务模式",
                7, 19, 31, 1000, 1024, true);
    }
}
