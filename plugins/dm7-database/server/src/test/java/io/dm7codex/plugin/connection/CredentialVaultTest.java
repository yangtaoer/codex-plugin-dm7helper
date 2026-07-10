package io.dm7codex.plugin.connection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CredentialVaultTest {
    @TempDir Path tempDir;

    @Test void encryptsUtf8SecretWithFreshIvAndNeverPersistsPlaintext() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
        UUID id = UUID.randomUUID();
        char[] secret = "中文测试口令-123".toCharArray();
        vault.put(id, secret);
        assertArrayEquals(secret, vault.read(id).orElseThrow());
        String first = Files.readString(tempDir.resolve("secrets/vault.json"), StandardCharsets.UTF_8);
        assertFalse(first.contains(new String(secret)));
        vault.put(id, secret);
        String second = Files.readString(tempDir.resolve("secrets/vault.json"), StandardCharsets.UTF_8);
        assertNotEquals(first, second);
        assertEquals(32, Files.size(tempDir.resolve("secrets/master.key")));
    }

    @Test void deleteRemovesSecretAndFilesAreOwnerOnlyWherePosixIsAvailable() throws Exception {
        CredentialVault vault = CredentialVault.open(tempDir.resolve("secrets"));
        UUID id = UUID.randomUUID();
        vault.put(id, "temporary".toCharArray());
        vault.delete(id);
        assertTrue(vault.read(id).isEmpty());
        Path key = tempDir.resolve("secrets/master.key");
        if (Files.getFileStore(key).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(key));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(tempDir.resolve("secrets/vault.json")));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(key, AclFileAttributeView.class);
            assertNotNull(acl);
            var owner = Files.getOwner(key);
            assertFalse(acl.getAcl().isEmpty());
            assertTrue(acl.getAcl().stream().allMatch(entry -> entry.principal().equals(owner)));
        }
    }

    @Test void refusesStorageWhenOwnerOnlyPermissionsAreUnavailable() throws Exception {
        Path zip = tempDir.resolve("no-security-view.zip");
        try (var fileSystem = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            assertThrows(Exception.class, () -> CredentialVault.open(fileSystem.getPath("/secrets")));
        }
    }

    @Test void concurrentVaultInstancesDoNotLoseUpdatesOrLeaveTemporaryFiles() throws Exception {
        Path directory = tempDir.resolve("shared-secrets");
        CredentialVault first = CredentialVault.open(directory);
        CredentialVault second = CredentialVault.open(directory);
        UUID[] ids = new UUID[20];
        var executor = Executors.newFixedThreadPool(6);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < ids.length; i++) {
                ids[i] = UUID.randomUUID();
                int index = i;
                futures.add(executor.submit(() ->
                        (index % 2 == 0 ? first : second).put(ids[index], ("secret-" + index).toCharArray())));
            }
            for (var future : futures) future.get();
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        for (int i = 0; i < ids.length; i++) {
            assertArrayEquals(("secret-" + i).toCharArray(), first.read(ids[i]).orElseThrow());
        }
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
