package io.dm7codex.plugin;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppMainLifecycleTest {
    static {
        System.setProperty("org.slf4j.simpleLogger.log.io.modelcontextprotocol.spec.McpTransport", "off");
    }
    @TempDir Path temporary;

    @Test void protocolErrorOutputFailureStillReleasesServerLifecycle() {
        var failingOutput = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("closed transport"); }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AppMain.runStdio(
                environment("output"), new ByteArrayInputStream("[]\n".getBytes(StandardCharsets.UTF_8)), failingOutput));
    }

    @Test void inboundTransportFailureStillReleasesServerLifecycle() {
        var failingInput = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("closed transport"); }
        };
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> AppMain.runStdio(
                environment("input"), failingInput, OutputStream.nullOutputStream()));
    }

    private Map<String, String> environment(String name) {
        return Map.of("PLUGIN_DATA", temporary.resolve(name).toString(), "CODEX_THREAD_ID", "lifecycle-" + name);
    }
}
