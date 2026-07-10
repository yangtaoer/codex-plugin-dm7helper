package io.dm7codex.plugin.runtime;

import java.util.Objects;

public record SessionIdentity(String externalId, String source, String isolation) {
    public SessionIdentity {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(isolation, "isolation");
        if (externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be blank");
        }
    }
}
