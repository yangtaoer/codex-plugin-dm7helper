package io.dm7codex.plugin.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SessionIdentityResolver {
    private static final String PROCESS_FALLBACK_ID = UUID.randomUUID().toString();

    private SessionIdentityResolver() {}

    public static SessionIdentity resolve(Map<String, String> environment) {
        return resolve(environment, Map.of());
    }

    public static SessionIdentity resolve(Map<String, String> environment, Map<String, Object> requestMeta) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(requestMeta, "requestMeta");
        var threadId = environment.get("CODEX_THREAD_ID");
        if (threadId != null && !threadId.isBlank()) {
            return new SessionIdentity(threadId, "codex_thread", "verified");
        }
        var metadataId = trustedMetadataId(requestMeta);
        if (metadataId != null) {
            return new SessionIdentity(metadataId, "mcp_request_meta", "verified");
        }
        return new SessionIdentity(PROCESS_FALLBACK_ID, "process_uuid", "process_fallback");
    }

    private static String trustedMetadataId(Map<String, Object> values) {
        if (!values.containsKey("openai") || values.containsKey("OpenAI")) return null;
        if (!(values.get("openai") instanceof Map<?, ?> openai)) return null;
        if (!(openai.get("thread_id") instanceof String value) || openai.size() != 1) return null;
        try {
            return UUID.fromString(value).toString().equals(value) ? value : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
