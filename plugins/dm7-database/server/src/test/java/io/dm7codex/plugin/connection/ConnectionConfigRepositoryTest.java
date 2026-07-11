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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;

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

    @Test void reportsCredentialPresenceWithoutReadingAndSupportsExplicitClear() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("presence-secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("presence-config"), vault);
        UUID id = UUID.randomUUID();
        ConnectionProfile profile = profile(id, "密码状态", false);
        assertFalse(repository.hasPassword(id));
        repository.save(profile, Optional.of("temporary-value".toCharArray()));
        assertTrue(repository.hasPassword(id));
        repository.save(profile, Optional.of("replacement-value".toCharArray()));
        assertArrayEquals("replacement-value".toCharArray(), vault.read(id).orElseThrow());
        repository.save(profile, Optional.empty(), true);
        assertFalse(repository.hasPassword(id));
    }

    @Test void replacementAndClearAreMutuallyExclusive() throws Exception {
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(
                tempDir.resolve("conflict-config"), CredentialVault.open(tempDir.resolve("conflict-secrets")));
        assertThrows(IllegalArgumentException.class, () -> repository.save(
                profile(UUID.randomUUID(), "冲突", false), Optional.of("replacement".toCharArray()), true));
    }

    @Test void failedConfigurationWriteRestoresReplacedCredential() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        Path config = tempDir.resolve("save-rollback-config");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        UUID id = UUID.randomUUID();
        ConnectionProfile saved = repository.save(profile(id, "回滚凭据", false), Optional.of("old-value".toCharArray()));
        AtomicBoolean once = new AtomicBoolean();
        secrets.beforePut = () -> {
            if (!once.compareAndSet(false, true)) return;
            try {
                Path file = config.resolve("connections.json");
                Files.delete(file); Files.createDirectory(file);
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
        assertThrows(IllegalStateException.class, () -> repository.save(saved, Optional.of("new-value".toCharArray())));
        assertArrayEquals("old-value".toCharArray(), secrets.read(id).orElseThrow());
    }

    @Test void validatesOperationalBounds() {
        ConnectionProfile valid = profile(UUID.randomUUID(), "边界", false);
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 0, 30, 60, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 0, 60, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 0, 1000, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 60, 10_001, 1024));
        assertThrows(IllegalArgumentException.class, () -> copy(valid, 10, 30, 60, 1000, 50L * 1024 * 1024 + 1));
    }

    @Test void driverPathIsPersistedAsStableAbsoluteNormalizedPath() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("path-secrets"));
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("path-config"), vault);
        ConnectionProfile saved = repository.save(profile(UUID.randomUUID(), "stable-path", false), Optional.empty());
        assertTrue(saved.driverJar().isAbsolute());
        assertEquals(saved.driverJar().normalize(), repository.find(saved.id()).orElseThrow().driverJar());
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

    @Test void initializationHoldsSharedLockSoConcurrentOpenAndSaveCannotBeOverwritten() throws Exception {
        Path config = tempDir.resolve("open-race-config");
        SecretStore secrets = new RecordingSecretStore();
        CountDownLatch initializerPaused = new CountDownLatch(1);
        CountDownLatch releaseInitializer = new CountDownLatch(1);
        CountDownLatch saveCompleted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> ConnectionConfigRepository.open(config, secrets, () -> {
                initializerPaused.countDown();
                try {
                    if (!releaseInitializer.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertTrue(initializerPaused.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets, () -> {});
                repository.save(profile(UUID.randomUUID(), "saved-during-open", false), Optional.empty());
                saveCompleted.countDown();
                return repository;
            });
            assertFalse(saveCompleted.await(250, TimeUnit.MILLISECONDS));
            releaseInitializer.countDown();
            first.get();
            assertEquals(1, second.get().list().size());
        } finally {
            releaseInitializer.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void deletingMissingProfileStillAttemptsIdempotentSecretCleanup() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("missing-delete"), secrets);
        UUID id = UUID.randomUUID();
        repository.delete(id);
        assertEquals(1, secrets.deleteCalls.get());
    }

    @Test void secretDeleteFailureRollsBackOriginalConfiguration() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("delete-rollback"), secrets);
        ConnectionProfile saved = repository.save(profile(UUID.randomUUID(), "must-survive", false), Optional.empty());
        secrets.deleteFailure = new IllegalStateException("injected secret failure");
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> repository.delete(saved.id()));
        assertEquals("injected secret failure", failure.getMessage());
        assertTrue(repository.find(saved.id()).isPresent());
    }

    @Test void rollbackFailureIsSuppressedOnSecretDeleteFailure() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        Path config = tempDir.resolve("delete-suppressed");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        ConnectionProfile saved = repository.save(profile(UUID.randomUUID(), "rollback-failure", false), Optional.empty());
        secrets.beforeDelete = () -> {
            try {
                Path file = config.resolve("connections.json");
                Files.delete(file);
                Files.createDirectory(file);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        secrets.deleteFailure = new IllegalStateException("injected secret failure");
        CredentialStateException failure = assertThrows(CredentialStateException.class, () -> repository.delete(saved.id()));
        assertEquals(CredentialStateException.State.UNCERTAIN, failure.state());
        assertTrue(failure.getCause().getSuppressed().length >= 1);
    }

    @Test void replacementRestoreFailureDeletesCredentialAndReportsRecoveryRequired() throws Exception {
        ScriptedSecretStore secrets = new ScriptedSecretStore();
        Path config = tempDir.resolve("replace-recovery-config");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        UUID id = UUID.randomUUID();
        ConnectionProfile saved = repository.save(profile(id, "替换恢复", false), Optional.of("old".toCharArray()));
        secrets.onPut = call -> {
            if (call == 2) breakConfiguration(config);
            if (call == 3) throw new IllegalStateException("restore failed");
        };
        CredentialStateException failure = assertThrows(CredentialStateException.class,
                () -> repository.save(saved, Optional.of("new".toCharArray())));
        assertEquals(CredentialStateException.State.RECOVERY_REQUIRED, failure.state());
        assertFalse(secrets.contains(id));
        assertTrue(failure.getCause().getSuppressed().length >= 1);
        assertTrue(secrets.restoreArgument == null || allZero(secrets.restoreArgument));
    }

    @Test void clearRestoreFailureFallsBackToDeleteAndReportsRecoveryRequired() throws Exception {
        ScriptedSecretStore secrets = new ScriptedSecretStore();
        Path config = tempDir.resolve("clear-recovery-config");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        UUID id = UUID.randomUUID();
        ConnectionProfile saved = repository.save(profile(id, "清除恢复", false), Optional.of("old".toCharArray()));
        secrets.onDelete = call -> { if (call == 1) breakConfiguration(config); };
        secrets.onPut = call -> { if (call == 2) throw new IllegalStateException("restore failed"); };
        CredentialStateException failure = assertThrows(CredentialStateException.class,
                () -> repository.save(saved, Optional.empty(), true));
        assertEquals(CredentialStateException.State.RECOVERY_REQUIRED, failure.state());
        assertFalse(secrets.contains(id));
        assertTrue(secrets.restoreArgument == null || allZero(secrets.restoreArgument));
    }

    @Test void createOrphanCleanupFailureRetriedThenReportsRecoveryRequired() throws Exception {
        ScriptedSecretStore secrets = new ScriptedSecretStore();
        Path config = tempDir.resolve("create-recovery-config");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        secrets.onPut = call -> { if (call == 1) breakConfiguration(config); };
        secrets.onDelete = call -> { if (call == 1) throw new IllegalStateException("first cleanup failed"); };
        UUID id = UUID.randomUUID();
        CredentialStateException failure = assertThrows(CredentialStateException.class,
                () -> repository.save(profile(id, "新建孤儿", false), Optional.of("new".toCharArray())));
        assertEquals(CredentialStateException.State.RECOVERY_REQUIRED, failure.state());
        assertFalse(secrets.contains(id));
    }

    @Test void fallbackDeleteFailureMarksCredentialStateUncertainAndKeepsAllFailures() throws Exception {
        ScriptedSecretStore secrets = new ScriptedSecretStore();
        Path config = tempDir.resolve("uncertain-config");
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(config, secrets);
        UUID id = UUID.randomUUID();
        ConnectionProfile saved = repository.save(profile(id, "不确定状态", false), Optional.of("old".toCharArray()));
        secrets.onPut = call -> { if (call == 2) breakConfiguration(config); if (call == 3) throw new IllegalStateException("restore failed"); };
        secrets.onDelete = call -> { throw new IllegalStateException("fail closed failed"); };
        CredentialStateException failure = assertThrows(CredentialStateException.class,
                () -> repository.save(saved, Optional.of("new".toCharArray())));
        assertEquals(CredentialStateException.State.UNCERTAIN, failure.state());
        assertTrue(failure.getCause().getSuppressed().length >= 2);
    }

    @Test void deletingDefaultRequiresExplicitReplacementOrExplicitNoDefault() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("explicit-delete"), secrets);
        ConnectionProfile first = repository.save(profile(UUID.randomUUID(), "默认", false), Optional.empty());
        ConnectionProfile second = repository.save(profile(UUID.randomUUID(), "替代", false), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> repository.delete(first.id(), Optional.empty(), false));
        assertThrows(IllegalArgumentException.class, () -> repository.delete(first.id(), Optional.of(first.id()), false));
        assertThrows(IllegalArgumentException.class, () -> repository.delete(first.id(), Optional.of(UUID.randomUUID()), false));
        repository.delete(first.id(), Optional.of(second.id()), false);
        assertTrue(repository.find(second.id()).orElseThrow().isDefault());
    }

    @Test void explicitNoDefaultIsStableUntilUserSelectsOne() throws Exception {
        RecordingSecretStore secrets = new RecordingSecretStore();
        ConnectionConfigRepository repository = ConnectionConfigRepository.open(tempDir.resolve("no-default"), secrets);
        ConnectionProfile first = repository.save(profile(UUID.randomUUID(), "默认", false), Optional.empty());
        ConnectionProfile second = repository.save(profile(UUID.randomUUID(), "保留", false), Optional.empty());
        repository.delete(first.id(), Optional.empty(), true);
        assertEquals(0, repository.list().stream().filter(ConnectionProfile::isDefault).count());
        ConnectionProfile third = repository.save(profile(UUID.randomUUID(), "新增", false), Optional.empty());
        assertFalse(third.isDefault());
        assertEquals(0, repository.list().stream().filter(ConnectionProfile::isDefault).count());
        repository.setDefault(second.id());
        assertTrue(repository.find(second.id()).orElseThrow().isDefault());
    }

    private static void breakConfiguration(Path config) {
        try { Path file=config.resolve("connections.json"); Files.delete(file); Files.createDirectory(file); }
        catch(Exception e){ throw new IllegalStateException(e); }
    }

    private static boolean allZero(char[] value) { for(char c:value)if(c!='\0')return false;return true; }

    static ConnectionProfile profile(UUID id, String name, boolean isDefault) {
        return new ConnectionProfile(id, name, Path.of("driver.jar"), "0".repeat(64), FakeDriverJar.DRIVER_CLASS,
                "jdbc:dm7://db.example.invalid:5236?dbname=TEST", "tester", "业务模式",
                10, 30, 60, 1000, 10L * 1024 * 1024, isDefault);
    }

    private static ConnectionProfile copy(ConnectionProfile p, int connect, int socket, int query, int rows, long bytes) {
        return new ConnectionProfile(p.id(), p.name(), p.driverJar(), p.driverSha256(), p.driverClass(), p.jdbcUrl(),
                p.username(), p.schema(), connect, socket, query, rows, bytes, p.isDefault());
    }

    private static final class RecordingSecretStore implements SecretStore {
        private final AtomicInteger deleteCalls = new AtomicInteger();
        private final java.util.Map<UUID,char[]> values = new HashMap<>();
        private RuntimeException deleteFailure;
        private Runnable beforePut = () -> {};
        private Runnable beforeDelete = () -> {};

        @Override public void put(UUID connectionId, char[] secret) { beforePut.run(); values.put(connectionId, secret.clone()); }
        @Override public Optional<char[]> read(UUID connectionId) { return Optional.ofNullable(values.get(connectionId)).map(char[]::clone); }
        @Override public boolean contains(UUID connectionId) { return values.containsKey(connectionId); }
        @Override public void delete(UUID connectionId) {
            deleteCalls.incrementAndGet();
            beforeDelete.run();
            if (deleteFailure != null) throw deleteFailure;
            values.remove(connectionId);
        }
    }

    private static final class ScriptedSecretStore implements SecretStore {
        interface Action { void accept(int call); }
        final java.util.Map<UUID,char[]> values=new HashMap<>();
        final AtomicInteger puts=new AtomicInteger(),deletes=new AtomicInteger();
        Action onPut=ignored->{},onDelete=ignored->{}; char[] restoreArgument;
        @Override public void put(UUID id,char[] secret){int call=puts.incrementAndGet();if(call>=2)restoreArgument=secret;onPut.accept(call);values.put(id,secret.clone());}
        @Override public Optional<char[]> read(UUID id){return Optional.ofNullable(values.get(id)).map(char[]::clone);}
        @Override public boolean contains(UUID id){return values.containsKey(id);}
        @Override public void delete(UUID id){int call=deletes.incrementAndGet();onDelete.accept(call);values.remove(id);}
    }
}
