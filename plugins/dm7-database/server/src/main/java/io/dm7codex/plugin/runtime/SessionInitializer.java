package io.dm7codex.plugin.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.dm7codex.plugin.state.SessionRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;

public final class SessionInitializer {
    private static final String INITIAL_HEADER = """
            -- DM7 Codex release log
            -- version: v001
            -- database-fingerprint: unbound
            """;

    private final RuntimePaths paths;
    private final SessionRepository sessions;

    public SessionInitializer(RuntimePaths paths, SessionRepository sessions) {
        this.paths = paths;
        this.sessions = sessions;
    }

    public SessionState initialize(SessionIdentity identity) throws SQLException, IOException {
        var externalIdHash = sha256(identity.externalId());
        var sessionDirectory = paths.sessionsDirectory().resolve(externalIdHash).normalize();
        if (!sessionDirectory.startsWith(paths.sessionsDirectory())) {
            throw new IllegalStateException("Session path escaped PLUGIN_DATA");
        }
        var activeSql = sessionDirectory.resolve("active.sql");

        return sessions.initialize(identity, externalIdHash, activeSql, file -> {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    INITIAL_HEADER,
                    UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        });
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
