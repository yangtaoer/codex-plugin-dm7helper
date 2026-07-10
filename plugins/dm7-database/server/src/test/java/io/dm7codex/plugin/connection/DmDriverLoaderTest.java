package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DmDriverLoaderTest {
    @TempDir Path tempDir;

    @Test void verifiesHashLoadsExplicitDriverAndDoesNotRegisterGlobally() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver"));
        DmDriverLoader loader = new DmDriverLoader();
        ConnectionProfile profile = profile(fixture);
        try (DmDriverLoader.DriverHandle handle = loader.load(profile)) {
            Properties properties = new Properties();
            properties.setProperty("user", "fixture-user");
            assertNotNull(handle.connect(profile.jdbcUrl(), properties));
        }
        assertTrue(Collections.list(DriverManager.getDrivers()).stream()
                .noneMatch(driver -> driver.getClass().getName().equals(FakeDriverJar.DRIVER_CLASS)));
    }

    @Test void rejectsMissingDirectoryWrongHashAndNonDriverWithoutLeakingPath() throws Exception {
        DmDriverLoader loader = new DmDriverLoader();
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
