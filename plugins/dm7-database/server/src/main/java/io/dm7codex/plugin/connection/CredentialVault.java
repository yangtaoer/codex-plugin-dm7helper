package io.dm7codex.plugin.connection;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CredentialVault implements SecretStore {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final Set<PosixFilePermission> OWNER_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final Path directory;
    private final Path keyFile;
    private final Path vaultFile;
    private final byte[] key;
    private final Object processLock;
    private final SecureRandom random = new SecureRandom();

    private CredentialVault(Path directory, byte[] key, Object processLock) {
        this.directory = directory;
        this.keyFile = directory.resolve("master.key");
        this.vaultFile = directory.resolve("vault.json");
        this.key = key;
        this.processLock = processLock;
    }

    public static CredentialVault open(Path directory) throws IOException {
        Path normalized = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Object processLock = LOCKS.computeIfAbsent(normalized, ignored -> new Object());
        synchronized (processLock) {
            Files.createDirectories(normalized);
            secure(normalized, true);
            Path keyFile = normalized.resolve("master.key");
            if (!Files.exists(keyFile)) {
                byte[] candidate = new byte[KEY_BYTES];
                new SecureRandom().nextBytes(candidate);
                try {
                    atomicCreate(keyFile, candidate);
                } finally {
                    Arrays.fill(candidate, (byte) 0);
                }
            }
            secure(keyFile, false);
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length != KEY_BYTES) {
                Arrays.fill(key, (byte) 0);
                throw new IOException("Credential master key has an invalid size");
            }
            Path vaultFile = normalized.resolve("vault.json");
            if (!Files.exists(vaultFile)) {
                atomicReplace(vaultFile, JSON.writeValueAsBytes(new VaultDocument(List.of())), true);
            }
            secure(vaultFile, false);
            return new CredentialVault(normalized, key, processLock);
        }
    }

    public void put(UUID connectionId, char[] secret) {
        synchronized (processLock) {
            putLocked(connectionId, secret);
        }
    }

    private void putLocked(UUID connectionId, char[] secret) {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(secret, "secret");
        byte[] plaintext = encode(secret);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(connectionId.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] ciphertext = cipher.doFinal(plaintext);
            Map<UUID, VaultEntry> entries = readEntries();
            entries.put(connectionId, new VaultEntry(connectionId.toString(),
                    Base64.getEncoder().encodeToString(iv), Base64.getEncoder().encodeToString(ciphertext)));
            writeEntries(entries);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Credential encryption failed", e);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(iv, (byte) 0);
        }
    }

    public Optional<char[]> read(UUID connectionId) {
        synchronized (processLock) {
            return readLocked(connectionId);
        }
    }

    private Optional<char[]> readLocked(UUID connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        VaultEntry entry = readEntries().get(connectionId);
        if (entry == null) return Optional.empty();
        byte[] iv;
        byte[] ciphertext;
        try {
            iv = Base64.getDecoder().decode(entry.ivBase64());
            ciphertext = Base64.getDecoder().decode(entry.ciphertextBase64());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Credential vault contains invalid encoded data");
        }
        if (iv.length != IV_BYTES) {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            throw new IllegalStateException("Credential vault contains an invalid IV");
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(connectionId.toString().getBytes(StandardCharsets.US_ASCII));
            plaintext = cipher.doFinal(ciphertext);
            return Optional.of(decode(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Credential decryption failed");
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void delete(UUID connectionId) {
        synchronized (processLock) {
            deleteLocked(connectionId);
        }
    }

    private void deleteLocked(UUID connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        Map<UUID, VaultEntry> entries = readEntries();
        if (entries.remove(connectionId) != null) writeEntries(entries);
    }

    private Map<UUID, VaultEntry> readEntries() {
        try {
            VaultDocument document = JSON.readValue(Files.readAllBytes(vaultFile), VaultDocument.class);
            Map<UUID, VaultEntry> result = new LinkedHashMap<>();
            if (document.entries() == null) throw new IOException("missing entries");
            for (VaultEntry entry : document.entries()) {
                UUID id = UUID.fromString(entry.connectionId());
                if (result.put(id, entry) != null) throw new IOException("duplicate entry");
            }
            return result;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Credential vault could not be read");
        }
    }

    private void writeEntries(Map<UUID, VaultEntry> entries) {
        try {
            List<VaultEntry> ordered = new ArrayList<>(entries.values());
            ordered.sort(Comparator.comparing(VaultEntry::connectionId));
            atomicReplace(vaultFile, JSON.writeValueAsBytes(new VaultDocument(ordered)), true);
        } catch (IOException e) {
            throw new IllegalStateException("Credential vault could not be written");
        }
    }

    private static byte[] encode(char[] value) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
            byte[] result = new byte[buffer.remaining()];
            buffer.get(result);
            if (buffer.hasArray()) Arrays.fill(buffer.array(), (byte) 0);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Credential contains invalid Unicode data");
        }
    }

    private static char[] decode(byte[] value) {
        try {
            CharBuffer buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value));
            char[] result = new char[buffer.remaining()];
            buffer.get(result);
            if (buffer.hasArray()) Arrays.fill(buffer.array(), '\0');
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Credential contains invalid UTF-8 data");
        }
    }

    private static void atomicCreate(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".master-", ".tmp");
        try {
            secure(temp, false);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Another opener created the key first. Its key is authoritative.
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic credential file creation is not supported", e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void atomicReplace(Path target, byte[] bytes, boolean restricted) throws IOException {
        boolean existed = Files.exists(target);
        Path temp = Files.createTempFile(target.getParent(), ".atomic-", ".tmp");
        try {
            if (restricted) secure(temp, false);
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic credential persistence is not supported", e);
            }
            if (restricted) {
                try {
                    secure(target, false);
                } catch (IOException e) {
                    if (!existed) Files.deleteIfExists(target);
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static void secure(Path path, boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, directory ? OWNER_DIRECTORY : OWNER_FILE);
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Owner-only file permissions are unavailable");
        String userName = currentProcessUserName();
        if (userName == null || userName.isBlank()) throw new IOException("Current user identity is unavailable");
        UserPrincipal currentUser = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(userName);
        AclEntry expected = windowsAclEntry(currentUser, directory);
        acl.setAcl(List.of(expected));
        List<AclEntry> actual = acl.getAcl();
        if (actual.size() != 1 || !actual.get(0).equals(expected)) {
            throw new IOException("Owner-only ACL verification failed");
        }
    }

    private static String currentProcessUserName() {
        String account = System.getenv("USERNAME");
        String domain = System.getenv("USERDOMAIN");
        if (account != null && !account.isBlank() && domain != null && !domain.isBlank()) {
            return domain + "\\" + account;
        }
        return System.getProperty("user.name");
    }

    static AclEntry windowsAclEntry(UserPrincipal currentUser, boolean directory) {
        EnumSet<AclEntryPermission> permissions = EnumSet.of(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.DELETE,
                AclEntryPermission.SYNCHRONIZE);
        if (directory) {
            permissions.add(AclEntryPermission.EXECUTE);
            permissions.add(AclEntryPermission.DELETE_CHILD);
        }
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Objects.requireNonNull(currentUser, "currentUser"))
                .setPermissions(permissions);
        if (directory) {
            builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        }
        return builder.build();
    }

    private record VaultEntry(String connectionId, String ivBase64, String ciphertextBase64) {}
    private record VaultDocument(List<VaultEntry> entries) {}
}
