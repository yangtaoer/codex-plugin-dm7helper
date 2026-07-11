package io.dm7codex.plugin.release;

final class UntrustedReleasePathException extends IllegalStateException {
    UntrustedReleasePathException() { super("Release path is not trusted"); }
}
