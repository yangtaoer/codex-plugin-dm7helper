package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import io.dm7codex.plugin.runtime.RuntimePaths;

import static org.junit.jupiter.api.Assertions.*;

class DmConnectionFactoryTest {
    @TempDir Path tempDir;

    @Test void passesCredentialsAndTimeoutsExecutesValidatedSchemaAndBuildsSafeFingerprint() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver"));
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config"), vault);
        UUID id = UUID.randomUUID();
        ConnectionProfile profile = new ConnectionProfile(id, "fixture", fixture.jar(), fixture.sha256(), fixture.driverClass(),
                "jdbc:dm7://fixture.invalid:5236?dbname=TEST", "fixture-user", "业务模式", 7, 19, 31, 1000, 1024, true);
        char[] secret = "fixture-secret".toCharArray();
        repository.save(profile, Optional.of(secret));
        DmConnectionFactory factory = new DmConnectionFactory(repository, vault, loader("main"));
        String fingerprint;
        try (DmConnectionFactory.ManagedConnection managed = factory.open(id)) {
            fingerprint = managed.databaseFingerprint();
            assertEquals("fixture-user", System.getProperty("dm7.fixture.user"));
            assertEquals("fixture-secret", System.getProperty("dm7.fixture.password"));
            assertEquals("7000", System.getProperty("dm7.fixture.connectTimeout"));
            assertEquals("19000", System.getProperty("dm7.fixture.socketTimeout"));
            assertEquals("true", managed.connection().getClientInfo("propertiesEmpty"));
            assertEquals("SET SCHEMA 业务模式", System.getProperty("dm7.fixture.schemaSql"));
        }
        assertFalse(fingerprint.contains("fixture-secret"));
        assertFalse(fingerprint.contains(profile.jdbcUrl()));
    }

    @Test void timeoutConversionRejectsIntegerOverflow() {
        assertThrows(ArithmeticException.class, () -> DmConnectionFactory.timeoutMilliseconds(Integer.MAX_VALUE));
    }

    @Test void rejectsUnsafeSchemaBeforeConnecting() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver"));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionProfile(UUID.randomUUID(), "fixture", fixture.jar(),
                fixture.sha256(), fixture.driverClass(), "jdbc:dm7://fixture.invalid:5236", "user", "x; DROP TABLE y",
                10, 30, 60, 1000, 1024, true));
    }

    @Test void databaseFingerprintDoesNotDependOnPasswordQueryParameter() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver-fingerprint"));
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets-fingerprint"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config-fingerprint"), vault);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        repository.save(profile(firstId, "one", fixture, "first-url-secret"), Optional.empty());
        repository.save(profile(secondId, "two", fixture, "second-url-secret"), Optional.empty());
        DmConnectionFactory factory = new DmConnectionFactory(repository, vault, loader("fingerprint"));
        String first;
        String second;
        try (DmConnectionFactory.ManagedConnection managed = factory.open(firstId)) { first = managed.databaseFingerprint(); }
        try (DmConnectionFactory.ManagedConnection managed = factory.open(secondId)) { second = managed.databaseFingerprint(); }
        assertEquals(first, second);
    }

    @Test void managedCloseClosesDriverHandleAndSuppressesItsFailure() {
        AtomicBoolean handleClosed = new AtomicBoolean();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> { if (method.getName().equals("close")) throw new SQLException("connection close"); return null; });
        AutoCloseable handle = () -> { handleClosed.set(true); throw new Exception("handle close"); };
        DmConnectionFactory.ManagedConnection managed = new DmConnectionFactory.ManagedConnection(connection, handle, "fp");
        Exception thrown = assertThrows(Exception.class, managed::close);
        assertTrue(handleClosed.get());
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test void sanitizesOrdinaryDriverCleanupFailureAfterConnectFailure() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("ordinary-connect-cleanup"));
        CredentialVault vault = CredentialVault.open(tempDir.resolve("ordinary-connect-secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(
                tempDir.resolve("ordinary-connect-config"), vault);
        UUID id = UUID.randomUUID();
        repository.save(new ConnectionProfile(id, "ordinary-connect", fixture.jar(), fixture.sha256(),
                fixture.driverClass(), "jdbc:dm7://fixture.invalid:5236?forceFailure=true", "fixture-user", null,
                7, 19, 31, 1000, 1024, false), Optional.empty());

        SQLException failure = assertThrows(SQLException.class,
                () -> new DmConnectionFactory(repository, vault, cleanupFailingLoader("ordinary-connect"))
                        .open(id));

        assertEquals("Database connection failed", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("JDBC driver cleanup failed", failure.getSuppressed()[0].getMessage());
    }

    @Test void sanitizesOrdinaryDriverCleanupFailureAfterCredentialFailure() throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("ordinary-credential-cleanup"));
        SecretStore secrets = new SecretStore() {
            @Override public void put(UUID id, char[] value) {}
            @Override public Optional<char[]> read(UUID id) { throw new IllegalStateException("credential-secret"); }
            @Override public void delete(UUID id) {}
        };
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(
                tempDir.resolve("ordinary-credential-config"), secrets);
        UUID id = UUID.randomUUID();
        repository.save(profile(id, "ordinary-credential", fixture, "url-secret"), Optional.empty());

        SQLException failure = assertThrows(SQLException.class,
                () -> new DmConnectionFactory(repository, secrets, cleanupFailingLoader("ordinary-credential"))
                        .open(id));

        assertEquals("Saved credential could not be read", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("JDBC driver cleanup failed", failure.getSuppressed()[0].getMessage());
    }

    private static ConnectionProfile profile(UUID id, String name, FakeDriverJar.Fixture fixture, String urlPassword) {
        return new ConnectionProfile(id, name, fixture.jar(), fixture.sha256(), fixture.driverClass(),
                "jdbc:dm7://fixture.invalid:5236?dbname=TEST&dbPassword=" + urlPassword, "fixture-user", null,
                7, 19, 31, 1000, 1024, false);
    }

    private DmDriverLoader loader(String name) {
        return new DmDriverLoader(RuntimePaths.forTest(tempDir.resolve("plugin-data-" + name)));
    }

    private DmDriverLoader cleanupFailingLoader(String name) {
        DmDriverLoader.LoaderFileOps fileOps = new DmDriverLoader.LoaderFileOps() {
            @Override public void close(URLClassLoader loader) throws Exception {
                loader.close();
                throw new Exception("driver-close-secret");
            }
            @Override public void delete(Path stagedJar) throws Exception {
                Files.deleteIfExists(stagedJar);
                throw new Exception("driver-delete-secret");
            }
        };
        return new DmDriverLoader(RuntimePaths.forTest(tempDir.resolve("plugin-data-" + name)), fileOps);
    }
}
