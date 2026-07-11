package io.dm7codex.plugin;

import io.dm7codex.plugin.mcp.Dm7McpServer;
import io.dm7codex.plugin.mcp.Dm7ServicesBackend;
import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentityResolver;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class AppMain {
    private AppMain() {}

    public static void main(String[] args) {
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        if (args.length != 1 || !"--stdio".equals(args[0])) {
            System.err.println("DM7 plugin supports only --stdio protocol mode.");
            System.exit(2);
        }
        try {
            runStdio(System.getenv(), System.in, System.out);
        } catch (Throwable startupFailure) {
            System.err.println("DM7 MCP server could not start safely.");
            System.exit(1);
        }
    }

    static void runStdio(Map<String, String> environment, InputStream stdin, java.io.OutputStream stdout)
            throws Exception {
        RuntimePaths paths = RuntimePaths.fromEnvironment(environment, pluginRoot());
        try (var backend = Dm7ServicesBackend.open(paths)) {
            var adapter = new Dm7McpServer(
                    () -> SessionIdentityResolver.resolve(environment), backend::initialize,
                    backend, Dm7McpServer.ConsoleLauncher.unavailable());
            var input = new EofAwareInputStream(stdin);
            var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, stdout);
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("dm7-database", "0.1.0")
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    // Validation belongs in handlers so session initialization always runs first.
                    .validateToolInputs(false)
                    .tools(adapter.toolSpecifications())
                    .build();
            try {
                input.awaitEof();
            } finally {
                server.closeGracefully();
            }
        }
    }

    private static Path pluginRoot() throws URISyntaxException {
        Path location = Path.of(AppMain.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath().normalize();
        if (java.nio.file.Files.isRegularFile(location)) {
            Path lib = location.getParent();
            return lib == null || lib.getParent() == null ? location.getParent() : lib.getParent();
        }
        return location;
    }

    private static final class EofAwareInputStream extends FilterInputStream {
        private final CountDownLatch eof = new CountDownLatch(1);
        private EofAwareInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read(); if (value < 0) eof.countDown(); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length); if (count < 0) eof.countDown(); return count;
        }
        private void awaitEof() throws InterruptedException { eof.await(); }
    }
}
