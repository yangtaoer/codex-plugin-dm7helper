package io.dm7codex.plugin.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JdbcUrlDiagnostics {
    private static final Pattern LEGACY_PATH = Pattern.compile("(?i)^jdbc:dm7://[^/?#]+/([^/?#]+)(?:[?#].*)?$");
    private static final Pattern USER_INFO = Pattern.compile("(?i)(jdbc:dm7://[^:/?#]+:)[^@/?#]+@");
    private static final Pattern SECRET_KEY = Pattern.compile(".*(?:password|passwd|pwd|token|secret|credential).*");

    private JdbcUrlDiagnostics() {}

    public static UrlDiagnostic inspect(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        List<String> warnings = new ArrayList<>();
        Matcher legacy = LEGACY_PATH.matcher(jdbcUrl);
        if (legacy.matches()) {
            String segment = legacy.group(1);
            if (ConnectionProfile.isSafeIdentifier(segment)) {
                warnings.add("The legacy DM7 path segment may be ignored; use dbname=" + segment + " to select the database.");
                warnings.add("To select the schema, use schema=" + segment + " or the separate schema setting.");
            } else {
                warnings.add("The legacy DM7 path segment may be ignored; use an explicit dbname parameter and schema setting.");
            }
        }
        if (!redact(jdbcUrl).equals(jdbcUrl)) {
            warnings.add("Sensitive JDBC URL parameters are present and are hidden from diagnostics.");
        }
        return new UrlDiagnostic(jdbcUrl, warnings);
    }

    public static String redact(String jdbcUrl) {
        if (jdbcUrl == null) return null;
        String redacted = USER_INFO.matcher(jdbcUrl).replaceAll("$1***@");
        int queryAt = redacted.indexOf('?');
        if (queryAt < 0) return redacted;
        String prefix = redacted.substring(0, queryAt + 1);
        String query = redacted.substring(queryAt + 1);
        String[] parts = query.split("&", -1);
        for (int i = 0; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals > 0 && SECRET_KEY.matcher(parts[i].substring(0, equals).toLowerCase(Locale.ROOT)).matches()) {
                parts[i] = parts[i].substring(0, equals + 1) + "***";
            }
        }
        return prefix + String.join("&", parts);
    }

    public record UrlDiagnostic(String original, List<String> warnings) {
        public UrlDiagnostic {
            Objects.requireNonNull(original, "original");
            warnings = List.copyOf(warnings);
        }
    }
}
