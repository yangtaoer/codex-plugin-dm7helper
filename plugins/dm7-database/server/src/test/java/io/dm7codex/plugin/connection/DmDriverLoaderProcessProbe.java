package io.dm7codex.plugin.connection;

import io.dm7codex.plugin.runtime.RuntimePaths;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.Properties;

public final class DmDriverLoaderProcessProbe {
    private DmDriverLoaderProcessProbe() {}

    public static void main(String[] args) throws Exception {
        if ("close".equals(args[2])) {
            runCloseProbe(args);
            return;
        }
        if ("factory-connect-cleanup".equals(args[2])) {
            runFactoryCleanupProbe(args, false);
            return;
        }
        if ("factory-credential-cleanup".equals(args[2])) {
            runFactoryCleanupProbe(args, true);
            return;
        }
        FakeDriverJar.Fixture fixture = FakeDriverJar.createThrowingDriverAction(Path.of(args[1]));
        FakeDriverJar.Fixture normal = FakeDriverJar.create(Path.of(args[1]).resolveSibling("normal-fixture"));
        RuntimePaths paths = RuntimePaths.forTest(Path.of(args[0]));
        CredentialVault vault = CredentialVault.open(paths.secretsDirectory());
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(paths.configDirectory(), vault);
        UUID maliciousId = UUID.randomUUID();
        UUID normalId = UUID.randomUUID();
        repository.save(profile(maliciousId, "malicious", fixture), Optional.empty());
        repository.save(profile(normalId, "normal", normal), Optional.empty());
        DmDriverLoader loader = new DmDriverLoader(paths);
        DmConnectionFactory factory = new DmConnectionFactory(repository, vault, loader);
        ConnectionTestService service = new ConnectionTestService(factory, repository);

        ConnectionTestService.ConnectionTestResult malicious = service.test(maliciousId);
        if (malicious.success() || !malicious.restartRequired()) System.exit(2);
        if (!malicious.warnings().equals(java.util.List.of("JDBC_DRIVER_ISOLATION_RESTART_REQUIRED"))) System.exit(3);
        ConnectionTestService.ConnectionTestResult later = service.test(normalId);
        if (later.success() || !later.restartRequired()) System.exit(4);
        try {
            factory.open(normalId);
            System.exit(5);
        } catch (DmDriverLoader.DriverIsolationException expected) {
            if (!expected.restartRequired()) System.exit(6);
        }
        System.exit(0);
    }

    private static void runCloseProbe(String[] args) throws Exception {
        FakeDriverJar.Fixture normal = FakeDriverJar.create(Path.of(args[1]));
        DmDriverLoader loader = new DmDriverLoader(RuntimePaths.forTest(Path.of(args[0])));
        DmDriverLoader.DriverHandle handle = loader.load(DmDriverLoaderTest.profile(normal));
        Properties properties = new Properties();
        properties.setProperty("registerThrowingOnConnect", "true");
        handle.connect("jdbc:dm7://fixture.invalid:5236", properties);
        try {
            handle.close();
            System.exit(10);
        } catch (DmDriverLoader.DriverIsolationException expected) {
            if (!expected.restartRequired()) System.exit(11);
        }
        try {
            handle.connect("jdbc:dm7://fixture.invalid:5236", new Properties());
            System.exit(12);
        } catch (DmDriverLoader.DriverIsolationException expected) {
            if (!expected.restartRequired()) System.exit(13);
        }
        System.exit(0);
    }

    private static void runFactoryCleanupProbe(String[] args, boolean credentialFailure) throws Exception {
        FakeDriverJar.Fixture failing = credentialFailure
                ? FakeDriverJar.createDelayedCredentialRegistration(Path.of(args[1]))
                : FakeDriverJar.createConnectRegisterThenFail(Path.of(args[1]));
        FakeDriverJar.Fixture normal = FakeDriverJar.create(Path.of(args[1]).resolveSibling("normal-after-cleanup"));
        RuntimePaths paths = RuntimePaths.forTest(Path.of(args[0]));
        CredentialVault realVault = CredentialVault.open(paths.secretsDirectory());
        SecretStore secrets = credentialFailure ? new SecretStore() {
            @Override public void put(UUID id, char[] value) {}
            @Override public Optional<char[]> read(UUID id) {
                System.setProperty("dm7.fixture.armCredentialRegistration", "true");
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!"true".equals(System.getProperty("dm7.fixture.credentialRegistered"))
                        && System.nanoTime() < deadline) Thread.onSpinWait();
                throw new IllegalStateException("fixture credential failure");
            }
            @Override public boolean contains(UUID id) { return false; }
            @Override public void delete(UUID id) {}
        } : realVault;
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(paths.configDirectory(), secrets);
        UUID failingId = UUID.randomUUID();
        UUID normalId = UUID.randomUUID();
        repository.save(profile(failingId, "cleanup-failure", failing), Optional.empty());
        repository.save(profile(normalId, "normal-after", normal), Optional.empty());
        DmDriverLoader loader = new DmDriverLoader(paths);
        DmConnectionFactory factory = new DmConnectionFactory(repository, secrets, loader);
        ConnectionTestService service = new ConnectionTestService(factory, repository);
        ConnectionTestService.ConnectionTestResult result = service.test(failingId);
        if (result.success() || !result.restartRequired()) System.exit(20);
        if (!result.warnings().equals(java.util.List.of("JDBC_DRIVER_ISOLATION_RESTART_REQUIRED"))) System.exit(21);
        try {
            factory.open(normalId);
            System.exit(22);
        } catch (DmDriverLoader.DriverIsolationException expected) {
            if (!expected.restartRequired()) System.exit(23);
        }
        System.exit(0);
    }

    private static ConnectionProfile profile(UUID id, String name, FakeDriverJar.Fixture fixture) {
        return new ConnectionProfile(id, name, fixture.jar(), fixture.sha256(), fixture.driverClass(),
                "jdbc:dm7://fixture.invalid:5236?dbname=TEST", "fixture-user", null,
                7, 19, 31, 1000, 1024, false);
    }
}
