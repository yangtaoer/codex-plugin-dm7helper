package io.dm7codex.plugin.connection;

import java.util.Optional;
import java.util.UUID;

public interface SecretStore {
    void put(UUID connectionId, char[] secret);
    Optional<char[]> read(UUID connectionId);
    boolean contains(UUID connectionId);
    void delete(UUID connectionId);
}
