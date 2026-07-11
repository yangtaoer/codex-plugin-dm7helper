package io.dm7codex.plugin.connection;

/** Test fixture that preserves the production exception constructor's package boundary. */
public final class DriverIsolationFixture {
    private DriverIsolationFixture() {}

    public static DmDriverLoader.DriverIsolationException restartRequired() {
        return new DmDriverLoader.DriverIsolationException("restart", true);
    }
}
