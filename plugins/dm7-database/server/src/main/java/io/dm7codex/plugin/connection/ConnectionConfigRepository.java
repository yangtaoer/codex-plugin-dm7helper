package io.dm7codex.plugin.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ConnectionConfigRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path configFile;
    private final SecretStore vault;
    private final Object lock;

    private ConnectionConfigRepository(Path configFile, SecretStore vault, Object lock) {
        this.configFile = configFile;
        this.vault = vault;
        this.lock = lock;
    }

    public static ConnectionConfigRepository open(Path configDirectory, SecretStore vault) throws IOException {
        return open(configDirectory, vault, () -> {});
    }

    static ConnectionConfigRepository open(Path configDirectory, SecretStore vault, Runnable beforeInitialize)
            throws IOException {
        Path directory = Objects.requireNonNull(configDirectory, "configDirectory").toAbsolutePath().normalize();
        Path file = directory.resolve("connections.json");
        Object lock = LOCKS.computeIfAbsent(file, ignored -> new Object());
        synchronized (lock) {
            Files.createDirectories(directory);
            if (!Files.exists(file)) {
                Objects.requireNonNull(beforeInitialize, "beforeInitialize").run();
                CredentialVault.atomicReplace(file,
                        JSON.writeValueAsBytes(JSON.createObjectNode().set("connections", JSON.createArrayNode())), false);
            }
            return new ConnectionConfigRepository(file, Objects.requireNonNull(vault, "vault"), lock);
        }
    }

    public List<ConnectionProfile> list() {
        synchronized (lock) {
            return List.copyOf(readProfiles().values());
        }
    }

    public Optional<ConnectionProfile> find(UUID id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            return Optional.ofNullable(readProfiles().get(id));
        }
    }

    public ConnectionProfile save(ConnectionProfile profile, Optional<char[]> newPassword) {
        return save(profile, newPassword, false);
    }

    public ConnectionProfile save(ConnectionProfile profile, Optional<char[]> newPassword, boolean clearPassword) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(newPassword, "newPassword");
        if (clearPassword && newPassword.isPresent()) {
            throw new IllegalArgumentException("Password replacement and clearing are mutually exclusive");
        }
        synchronized (lock) {
            Map<UUID, ConnectionProfile> profiles = readProfiles();
            rejectDuplicateName(profiles, profile);
            ConnectionProfile normalized = normalizeDefault(profiles, profile);
            if (normalized.isDefault()) {
                profiles.replaceAll((id, current) -> id.equals(normalized.id()) ? current : withDefault(current, false));
            }
            profiles.put(normalized.id(), normalized);
            enforceAtMostOneDefault(profiles);

            Optional<char[]> previous = Optional.empty();
            if (newPassword.isPresent() || clearPassword) previous = vault.read(normalized.id());
            try {
                newPassword.ifPresent(value -> vault.put(normalized.id(), value));
                if (clearPassword) vault.delete(normalized.id());
                writeProfiles(profiles);
            } catch (RuntimeException e) {
                if (newPassword.isPresent() || clearPassword) recoverSecret(normalized.id(), previous, e);
                throw e;
            } finally {
                previous.ifPresent(value -> Arrays.fill(value, '\0'));
            }
            return profiles.get(normalized.id());
        }
    }

    public boolean hasPassword(UUID id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            return vault.contains(id);
        }
    }

    public void delete(UUID id) {
        delete(id, Optional.empty(), false);
    }

    public void delete(UUID id, Optional<UUID> replacementDefaultId, boolean leaveWithoutDefault) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(replacementDefaultId, "replacementDefaultId");
        synchronized (lock) {
            Map<UUID, ConnectionProfile> profiles = readProfiles();
            Map<UUID, ConnectionProfile> original = new LinkedHashMap<>(profiles);
            Optional<char[]> previous = Optional.empty();
            RuntimeException readFailure = null;
            try {
                previous = vault.read(id);
            } catch (RuntimeException failure) {
                readFailure = failure;
            }
            try {
                ConnectionProfile removed = profiles.remove(id);
                if (removed != null) {
                    if (!removed.isDefault() && (replacementDefaultId.isPresent() || leaveWithoutDefault)) {
                        throw new IllegalArgumentException("Default replacement is only valid when deleting the default connection");
                    }
                    if (removed.isDefault() && !profiles.isEmpty()) {
                        if (replacementDefaultId.isPresent() == leaveWithoutDefault) {
                            throw new IllegalArgumentException("Choose one default replacement disposition");
                        }
                        if (replacementDefaultId.isPresent()) {
                            UUID replacement = replacementDefaultId.get();
                            if (replacement.equals(id) || !profiles.containsKey(replacement)) {
                                throw new IllegalArgumentException("Replacement connection was not found");
                            }
                            profiles.replaceAll((profileId, profile) -> withDefault(profile, profileId.equals(replacement)));
                        } else {
                            profiles.replaceAll((profileId, profile) -> withDefault(profile, false));
                        }
                    } else if (replacementDefaultId.isPresent() || leaveWithoutDefault) {
                        throw new IllegalArgumentException("Default replacement is not required");
                    }
                    enforceAtMostOneDefault(profiles);
                    writeProfiles(profiles);
                }
                try {
                    vault.delete(id);
                } catch (RuntimeException failure) {
                    if (readFailure != null) {
                        recoverUnreadableDeletedConnection(original, removed != null, readFailure, failure);
                    } else {
                        recoverDeletedConnection(id, original, removed != null, previous, failure);
                    }
                    throw failure;
                }
            } finally {
                if (previous.isPresent()) Arrays.fill(previous.get(), '\0');
            }
        }
    }

    private void recoverUnreadableDeletedConnection(Map<UUID, ConnectionProfile> original,
            boolean configChanged, RuntimeException readFailure, RuntimeException deleteFailure) {
        suppress(deleteFailure, readFailure);
        if (configChanged) {
            try {
                writeProfiles(original);
            } catch (RuntimeException rollbackFailure) {
                suppress(deleteFailure, rollbackFailure);
                throw new CredentialStateException(CredentialStateException.State.UNCERTAIN,
                        "Connection deletion state could not be recovered", deleteFailure);
            }
        }
        throw new CredentialStateException(CredentialStateException.State.RECOVERY_REQUIRED,
                "Credential could not be read or deleted; retry deletion or save a new credential", deleteFailure);
    }

    private void recoverDeletedConnection(UUID id, Map<UUID, ConnectionProfile> original,
            boolean configChanged, Optional<char[]> previous, RuntimeException primary) {
        boolean configRecovered = true;
        boolean secretRecovered = true;
        if (configChanged) {
            try { writeProfiles(original); }
            catch (RuntimeException rollbackFailure) { suppress(primary, rollbackFailure); configRecovered = false; }
        }
        try {
            if (previous.isPresent()) vault.put(id, previous.get()); else vault.delete(id);
        } catch (RuntimeException restoreFailure) {
            suppress(primary, restoreFailure);
            secretRecovered = false;
        }
        if (configRecovered && secretRecovered) return;
        boolean failedClosed = true;
        try { vault.delete(id); }
        catch (RuntimeException failClosedFailure) { suppress(primary, failClosedFailure); failedClosed = false; }
        CredentialStateException.State state = !configRecovered || !failedClosed
                ? CredentialStateException.State.UNCERTAIN
                : CredentialStateException.State.RECOVERY_REQUIRED;
        throw new CredentialStateException(state,
                state == CredentialStateException.State.RECOVERY_REQUIRED
                        ? "Saved credential was removed after a deletion recovery failure"
                        : "Connection deletion state could not be recovered",
                primary);
    }

    public ConnectionProfile setDefault(UUID id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            Map<UUID, ConnectionProfile> profiles = readProfiles();
            if (!profiles.containsKey(id)) throw new IllegalArgumentException("Connection profile was not found");
            profiles.replaceAll((profileId, profile) -> withDefault(profile, profileId.equals(id)));
            writeProfiles(profiles);
            return profiles.get(id);
        }
    }

    private Map<UUID, ConnectionProfile> readProfiles() {
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(configFile));
            JsonNode values = root.get("connections");
            if (values == null || !values.isArray()) throw new IOException("invalid document");
            Map<UUID, ConnectionProfile> profiles = new LinkedHashMap<>();
            for (JsonNode value : values) {
                ConnectionProfile profile = fromJson(value);
                if (profiles.put(profile.id(), profile) != null) throw new IOException("duplicate id");
            }
            enforceAtMostOneDefault(profiles);
            return profiles;
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalStateException("Connection configuration could not be read");
        }
    }

    private void writeProfiles(Map<UUID, ConnectionProfile> profiles) {
        try {
            ArrayNode connections = JSON.createArrayNode();
            profiles.values().stream().sorted(Comparator.comparing(p -> p.id().toString()))
                    .map(ConnectionConfigRepository::toJson).forEach(connections::add);
            ObjectNode root = JSON.createObjectNode();
            root.set("connections", connections);
            CredentialVault.atomicReplace(configFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root), false);
        } catch (IOException e) {
            throw new IllegalStateException("Connection configuration could not be written");
        }
    }

    private static ObjectNode toJson(ConnectionProfile profile) {
        ObjectNode value = JSON.createObjectNode();
        value.put("id", profile.id().toString());
        value.put("name", profile.name());
        value.put("driverJar", profile.driverJar().toString());
        value.put("driverSha256", profile.driverSha256());
        value.put("driverClass", profile.driverClass());
        value.put("jdbcUrl", profile.jdbcUrl());
        value.put("username", profile.username());
        if (profile.schema() == null) value.putNull("schema"); else value.put("schema", profile.schema());
        value.put("connectTimeoutSeconds", profile.connectTimeoutSeconds());
        value.put("socketTimeoutSeconds", profile.socketTimeoutSeconds());
        value.put("queryTimeoutSeconds", profile.queryTimeoutSeconds());
        value.put("maxRows", profile.maxRows());
        value.put("maxBytes", profile.maxBytes());
        value.put("isDefault", profile.isDefault());
        return value;
    }

    private static ConnectionProfile fromJson(JsonNode value) {
        return new ConnectionProfile(UUID.fromString(required(value, "id")), required(value, "name"),
                Path.of(required(value, "driverJar")), required(value, "driverSha256"), required(value, "driverClass"),
                required(value, "jdbcUrl"), required(value, "username"), nullable(value, "schema"),
                value.path("connectTimeoutSeconds").asInt(), value.path("socketTimeoutSeconds").asInt(),
                value.path("queryTimeoutSeconds").asInt(), value.path("maxRows").asInt(),
                value.path("maxBytes").asLong(), value.path("isDefault").asBoolean());
    }

    private static String required(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isTextual()) throw new IllegalArgumentException("Missing configuration field");
        return node.textValue();
    }

    private static String nullable(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || node.isNull() ? null : node.textValue();
    }

    private static void rejectDuplicateName(Map<UUID, ConnectionProfile> profiles, ConnectionProfile candidate) {
        String name = candidate.name().toLowerCase(Locale.ROOT);
        boolean duplicate = profiles.values().stream().anyMatch(existing -> !existing.id().equals(candidate.id())
                && existing.name().toLowerCase(Locale.ROOT).equals(name));
        if (duplicate) throw new IllegalArgumentException("Connection name is already in use");
    }

    private static ConnectionProfile normalizeDefault(Map<UUID, ConnectionProfile> profiles, ConnectionProfile profile) {
        if (profiles.isEmpty()) return withDefault(profile, true);
        ConnectionProfile existing = profiles.get(profile.id());
        if (existing != null && existing.isDefault() && !profile.isDefault()) return withDefault(profile, true);
        return profile;
    }

    private static void enforceAtMostOneDefault(Map<UUID, ConnectionProfile> profiles) {
        long count = profiles.values().stream().filter(ConnectionProfile::isDefault).count();
        if (count > 1) {
            throw new IllegalStateException("Connection configuration can have at most one default");
        }
    }

    private void recoverSecret(UUID id, Optional<char[]> previous, RuntimeException primary) {
        try {
            if (previous.isPresent()) vault.put(id, previous.get()); else vault.delete(id);
            return;
        } catch (RuntimeException restoreFailure) {
            suppress(primary, restoreFailure);
        }
        try {
            vault.delete(id);
        } catch (RuntimeException failClosedFailure) {
            suppress(primary, failClosedFailure);
            throw new CredentialStateException(CredentialStateException.State.UNCERTAIN,
                    "Credential state could not be recovered", primary);
        }
        throw new CredentialStateException(CredentialStateException.State.RECOVERY_REQUIRED,
                "Saved credential was removed after a persistence failure", primary);
    }

    private static void suppress(RuntimeException primary, RuntimeException secondary) {
        primary.addSuppressed(primary == secondary
                ? new IllegalStateException("Credential recovery repeated the original failure")
                : secondary);
    }

    private static ConnectionProfile withDefault(ConnectionProfile profile, boolean value) {
        return new ConnectionProfile(profile.id(), profile.name(), profile.driverJar(), profile.driverSha256(),
                profile.driverClass(), profile.jdbcUrl(), profile.username(), profile.schema(), profile.connectTimeoutSeconds(),
                profile.socketTimeoutSeconds(), profile.queryTimeoutSeconds(), profile.maxRows(), profile.maxBytes(), value);
    }
}
