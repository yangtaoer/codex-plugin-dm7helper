package io.dm7codex.plugin.connection;

import io.dm7codex.plugin.runtime.RuntimePaths;
import java.nio.file.Path;

public final class DmDriverLoaderProcessProbe {
    private DmDriverLoaderProcessProbe() {}

    public static void main(String[] args) throws Exception {
        FakeDriverJar.Fixture fixture = FakeDriverJar.createThrowingDriverAction(Path.of(args[1]));
        DmDriverLoader loader = new DmDriverLoader(RuntimePaths.forTest(Path.of(args[0])));
        try {
            loader.load(DmDriverLoaderTest.profile(fixture));
            System.exit(2);
        } catch (DmDriverLoader.DriverIsolationException expected) {
            if (!expected.restartRequired()) System.exit(3);
            if (expected.getSuppressed().length == 0) System.exit(4);
            System.exit(0);
        }
    }
}
