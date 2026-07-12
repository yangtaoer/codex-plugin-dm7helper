package io.dm7codex.plugin.runtime;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;

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
        if (metadataId.isPresent()) {
            return new SessionIdentity(metadataId.get(), "mcp_request_meta", "verified");
        }
        return new SessionIdentity(PROCESS_FALLBACK_ID, "process_uuid", "process_fallback");
    }

    private static Optional<String> trustedMetadataId(Map<?, ?> values) {
        for (var entry : values.entrySet()) {
            String key = String.valueOf(entry.getKey()).replaceAll("[^A-Za-z]", "").toLowerCase();
            Object value = entry.getValue();
            if (value instanceof String text && trustedKey(key) && trustedValue(text)) {
                return Optional.of(text);
            }
            if (value instanceof Map<?, ?> nested) {
                var found = trustedMetadataId(nested);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    private static boolean trustedKey(String key) {
        return key.equals("threadid") || key.equals("codexthreadid")
                || key.equals("conversationid") || key.equals("sessionid");
    }

    private static boolean trustedValue(String value) {
        return value.length() >= 16 && value.length() <= 128
                && value.matches("[A-Za-z0-9._:-]+");
    }
}
