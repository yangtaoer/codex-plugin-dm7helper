package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionConfigRepositoryTest {
    @TempDir Path tempDir;

    @Test void enforcesUniqueNamesAndExactlyOneDefault() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config"), vault);
        ConnectionProfile one = repository.save(profile(UUID.randomUUID(), "主库", false), Optional.empty());
        assertTrue(one.isDefault());
        ConnectionProfile two = repository.save(profile(UUID.randomUUID(), "报表库", true), Optional.empty());
        assertTrue(two.isDefault());
        assertFalse(repository.find(one.id()).orElseThrow().isDefault());
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(profile(UUID.randomUUID(), "报表库", false), Optional.empty()));
    }

    @Test void omittedPasswordPreservesSecretDeleteRemovesItAndJsonNeverContainsPassword() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("config"), vault);
        UUID id = UUID.randomUUID();
        char[] password = "仅用于测试的中文口令".toCharArray();
        ConnectionProfile saved = repository.save(profile(id, "连接一", false), Optional.of(password));
        repository.save(new ConnectionProfile(saved.id(), "连接一-改", saved.driverJar(), saved.driverSha256(),
                saved.driverClass(), saved.jdbcUrl(), saved.username(), saved.schema(), saved.connectTimeoutSeconds(),
                saved.socketTimeoutSeconds(), saved.queryTimeoutSeconds(), saved.maxRows(), saved.maxBytes(), saved.isDefault()),
                Optional.empty());
        assertArrayEquals(password, vault.read(id).orElseThrow());
        String json = Files.readString(tempDir.resolve("config/connections.json"), StandardCharsets.UTF_8);
        assertFalse(json.toLowerCase().contains("password"));
        assertFalse(json.contains(new String(password)));
        repository.delete(id);
        assertTrue(vault.read(id).isEmpty());
        assertTrue(repository.list().isEmpty());
    }

    @Test void validatesOperationalBounds() {
        ConnectionProfile valid = profile(UUID.randomUUID(), "边界", false);
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 0, 30, 60, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 0, 60, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 0, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 60, 10_001, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 60, 1000, 50L * 1024 * 1024 + 1));
    }

    @Test void concurrentRepositoryInstancesKeepEveryProfileAndOneDefault() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("concurrent-secrets"));
        Path config = tempDir.resolve("concurrent-config");
        ConnectionConfigRepository first = ConnectionConfigRepository.open(config, vault);
        ConnectionConfigRepository second = ConnectionConfigRepository.open(config, vault);
        var executor = Executors.newFixedThreadPool(6);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 20; i++) {
                int index = i;
                futures.add(executor.submit(() -> (index % 2 == 0 ? first : second)
                        .save(profile(UUID.randomUUID(), "connection-" + index, false), Optional.empty())));
            }
            for (var future : futures) future.get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(20, first.list().size());
        assertEquals(1, first.list().stream().filter(ConnectionProfile::isDefault).count());
        try (var files = Files.list(config)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    static ConnectionProfile profile(UUID id, String name, boolean isDefault) {
        return new ConnectionProfile(id, name, Path.of("driver.jar"), "0".repeat(64), FakeDriverJar.DRIVER_CLASS,
                "jdbc:dm7://db.example.invalid:5236?dbname=TEST", "tester", "业务模式",
                10, 30, 60, 1000, 10L * 1024 * 1024, isDefault);
    }

    private static ConnectionProfile copy(ConnectionProfile p, int connect, int socket, int query, int rows, long bytes) {
        return new ConnectionProfile(p.id(), p.name(), p.driverJar(), p.driverSha256(), p.driverClass(), p.jdbcUrl(),
                p.username(), p.schema(), connect, socket, query, rows, bytes, p.isDefault());
    }
}
