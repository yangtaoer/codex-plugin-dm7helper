package io.dm7codex.plugin;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import io.dm7codex.plugin.mcp.Dm7ServicesBackend;
import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.http.ConsoleHttpServer;
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

    @Test void openConsoleToolUsesRealProcessOwnedLauncher() throws Exception {
        var environment=environment("console");
        try(var backend=Dm7ServicesBackend.open(RuntimePaths.fromEnvironment(environment,temporary));
            var console=new ConsoleHttpServer(new io.dm7codex.plugin.http.ConsoleTokenService(),backend,backend.eventBus())){
            var result=AppMain.adapter(environment,backend,console).call("dm7_open_console",Map.of());
            assertFalse(result.isError());
            var content=(Map<?,?>)result.structuredContent();
            assertTrue(((String)content.get("url")).contains("/console/redeem?token="));
        }
    }

    @Test void startupDiagnosticsExposeOnlyBoundedExceptionKinds() {
        var sensitive = new IOException("password=never-print-this",
                new IllegalStateException("jdbc:dm7://private-host"));

        var diagnostic = AppMain.safeFailureKinds(sensitive);

        assertEquals("IOException -> IllegalStateException", diagnostic);
        assertFalse(diagnostic.contains("password"));
        assertFalse(diagnostic.contains("jdbc"));
    }

    private Map<String, String> environment(String name) {
        return Map.of("PLUGIN_DATA", temporary.resolve(name).toString(), "CODEX_THREAD_ID", "lifecycle-" + name);
    }
}
