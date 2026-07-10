package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import io.dm7codex.plugin.runtime.RuntimePaths;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTestServiceTest {
    @TempDir Path tempDir;

    @Test void reportsLatencyVersionsIdentitySchemaChineseRoundTripAndWarnings() throws Exception {
        Setup setup = setup("jdbc:dm7://fixture.invalid:5236/SYSTEM?password=masked-value");
        ConnectionTestService.ConnectionTestResult result = setup.service.test(setup.id);
        assertTrue(result.success());
        assertTrue(result.latencyMs() >= 0);
        assertTrue(result.driverVersion().contains("7.0-test"));
        assertTrue(result.serverVersion().contains("7-test"));
        assertEquals("测试用户", result.actualUser());
        assertEquals("业务模式", result.actualSchema());
        assertTrue(result.chineseRoundTrip());
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("dbname=SYSTEM")));
        assertTrue(result.warnings().stream().noneMatch(value -> value.contains("masked-value")));
    }

    @Test void connectionFailureReturnsSafeResultWithoutUrlUsernameOrPassword() throws Exception {
        String urlMarker = "url-secret-marker";
        String usernameMarker = "user-secret-marker";
        String passwordMarker = "password-secret-marker";
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver-fail"));
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets-fail"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config-fail"), vault);
        UUID id = UUID.randomUUID();
        repository.save(new ConnectionProfile(id, "failure", fixture.jar(), fixture.sha256(), fixture.driverClass(),
                "jdbc:dm7://forceFailure.invalid:5236/SYSTEM?token=" + urlMarker, usernameMarker, null,
                10, 30, 60, 1000, 1024, true), Optional.of(passwordMarker.toCharArray()));
        ConnectionTestService service = new ConnectionTestService(new DmConnectionFactory(repository, vault,
                new DmDriverLoader(RuntimePaths.forTest(tempDir.resolve("plugin-data-fail")))), repository);
        ConnectionTestService.ConnectionTestResult result = service.test(id);
        assertFalse(result.success());
        String rendered = result.toString();
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("dbname=SYSTEM")));
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("Connection test failed")));
        assertFalse(rendered.contains(urlMarker));
        assertFalse(rendered.contains(usernameMarker));
        assertFalse(rendered.contains(passwordMarker));
    }

    private Setup setup(String url) throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.create(tempDir.resolve("driver-ok"));
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets-ok"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config-ok"), vault);
        UUID id = UUID.randomUUID();
        repository.save(new ConnectionProfile(id, "success", fixture.jar(), fixture.sha256(), fixture.driverClass(), url,
                "fixture-user", "业务模式", 10, 30, 60, 1000, 1024, true), Optional.of("fixture-password".toCharArray()));
        return new Setup(id, new ConnectionTestService(new DmConnectionFactory(repository, vault,
                new DmDriverLoader(RuntimePaths.forTest(tempDir.resolve("plugin-data-ok")))), repository));
    }

    private record Setup(UUID id, ConnectionTestService service) {}
}
