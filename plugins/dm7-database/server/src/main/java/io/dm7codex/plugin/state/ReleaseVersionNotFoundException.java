package io.dm7codex.plugin.state;

import java.sql.SQLException;

public final class ReleaseVersionNotFoundException extends SQLException {
    public ReleaseVersionNotFoundException() { super("Release version does not exist"); }
}
