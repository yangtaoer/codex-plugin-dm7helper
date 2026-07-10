package io.dm7codex.plugin.release;

import java.io.IOException;

public final class ReleaseExportLockTimeout extends IOException {
    public ReleaseExportLockTimeout(String safeMessage) {
        super(safeMessage);
    }
}
