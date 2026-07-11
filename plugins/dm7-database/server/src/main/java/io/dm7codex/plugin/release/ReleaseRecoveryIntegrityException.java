package io.dm7codex.plugin.release;

import java.io.IOException;

final class ReleaseRecoveryIntegrityException extends IOException {
    ReleaseRecoveryIntegrityException() { super("Release recovery integrity validation failed"); }
}
