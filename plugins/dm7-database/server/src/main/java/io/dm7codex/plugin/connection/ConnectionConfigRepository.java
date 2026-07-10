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
    private final CredentialVault vault;
    private final Object lock;

    private ConnectionConfigRepository(Path configFile, CredentialVault vault) {
        this.configFile = configFile;
        this.vault = vault;
        this.lock = LOCKS.computeIfAbsent(configFile, ignored -> new Object());
    }

    public static ConnectionConfigRepository open(Path configDirectory, CredentialVault vault) throws IOException {
        Path directory = Objects.requireNonNull(configDirectory, "configDirectory").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        Path file = directory.resolve("connections.json");
        if (!Files.exists(file)) {
            CredentialVault.atomicReplace(file, JSON.writeValueAsBytes(JSON.createObjectNode().set("connections", JSON.createArrayNode())), false);
        }
        return new ConnectionConfigRepository(file, Objects.requireNonNull(vault, "vault"));
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
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(newPassword, "newPassword");
        synchronized (lock) {
            Map<UUID, ConnectionProfile> profiles = readProfiles();
            rejectDuplicateName(profiles, profile);
            ConnectionProfile normalized = normalizeDefault(profiles, profile);
            if (normalized.isDefault()) {
                profiles.replaceAll((id, current) -> id.equals(normalized.id()) ? current : withDefault(current, false));
            }
            profiles.put(normalized.id(), normalized);
            enforceOneDefault(profiles);

            Optional<char[]> previous = Optional.empty();
            if (newPassword.isPresent()) previous = vault.read(normalized.id());
            try {
                newPassword.ifPresent(value -> vault.put(normalized.id(), value));
                writeProfiles(profiles);
            } catch (RuntimeException e) {
                if (newPassword.isPresent()) restoreSecret(normalized.id(), previous);
                throw e;
            } finally {
                previous.ifPresent(value -> Arrays.fill(value, '\0'));
            }
            return profiles.get(normalized.id());
        }
    }

    public void delete(UUID id) {
        Objects.requireNonNull(id, "id");
        synchronized (lock) {
            Map<UUID, ConnectionProfile> profiles = readProfiles();
            ConnectionProfile removed = profiles.remove(id);
            if (removed == null) return;
            if (!profiles.isEmpty() && profiles.values().stream().noneMatch(ConnectionProfile::isDefault)) {
                UUID replacement = profiles.keySet().stream().sorted().findFirst().orElseThrow();
                profiles.put(replacement, withDefault(profiles.get(replacement), true));
            }
            writeProfiles(profiles);
            vault.delete(id);
        }
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
            enforceOneDefault(profiles);
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

    private static void enforceOneDefault(Map<UUID, ConnectionProfile> profiles) {
        long count = profiles.values().stream().filter(ConnectionProfile::isDefault).count();
        if (!profiles.isEmpty() && count != 1) {
            throw new IllegalStateException("Connection configuration must have exactly one default");
        }
    }

    private void restoreSecret(UUID id, Optional<char[]> previous) {
        try {
            if (previous.isPresent()) vault.put(id, previous.get()); else vault.delete(id);
        } catch (RuntimeException ignored) {
            // Preserve the original failure; the vault remains fail-closed and will surface on the next operation.
        }
    }

    private static ConnectionProfile withDefault(ConnectionProfile profile, boolean value) {
        return new ConnectionProfile(profile.id(), profile.name(), profile.driverJar(), profile.driverSha256(),
                profile.driverClass(), profile.jdbcUrl(), profile.username(), profile.schema(), profile.connectTimeoutSeconds(),
                profile.socketTimeoutSeconds(), profile.queryTimeoutSeconds(), profile.maxRows(), profile.maxBytes(), value);
    }
}
