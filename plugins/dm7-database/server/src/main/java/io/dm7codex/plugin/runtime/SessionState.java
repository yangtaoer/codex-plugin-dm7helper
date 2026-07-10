package io.dm7codex.plugin.runtime;

import java.nio.file.Path;
import java.time.Instant;

public record SessionState(
        String sessionId,
        String externalIdHash,
        int version,
        String databaseFingerprint,
        Path activeSql,
        Instant createdAt) {}
