package io.dm7codex.plugin.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SessionIdentityResolver {
    private static final String PROCESS_FALLBACK_ID = UUID.randomUUID().toString();

    private SessionIdentityResolver() {}

    public static SessionIdentity resolve(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        var threadId = environment.get("CODEX_THREAD_ID");
        if (threadId != null && !threadId.isBlank()) {
            return new SessionIdentity(threadId, "codex_thread", "verified");
        }
        return new SessionIdentity(PROCESS_FALLBACK_ID, "process_uuid", "process_fallback");
    }
}
