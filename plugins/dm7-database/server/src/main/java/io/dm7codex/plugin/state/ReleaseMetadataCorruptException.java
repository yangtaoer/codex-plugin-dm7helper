package io.dm7codex.plugin.state;

import java.sql.SQLException;

public final class ReleaseMetadataCorruptException extends SQLException {
    public ReleaseMetadataCorruptException(Throwable cause) {
        super("Release metadata is corrupt", cause);
    }
}
