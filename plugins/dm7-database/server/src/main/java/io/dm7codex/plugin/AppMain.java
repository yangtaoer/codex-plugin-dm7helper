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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import static java.nio.charset.StandardCharsets.UTF_8;

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
            var input = new ProtocolGuardInputStream(stdin, stdout);
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

    private static final class ProtocolGuardInputStream extends InputStream {
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final byte[] PARSE_ERROR = ("{\"jsonrpc\":\"2.0\",\"id\":null,"
                + "\"error\":{\"code\":-32700,\"message\":\"Parse error\"}}\n").getBytes(UTF_8);
        private static final byte[] INVALID_REQUEST = ("{\"jsonrpc\":\"2.0\",\"id\":null,"
                + "\"error\":{\"code\":-32600,\"message\":\"Invalid Request\"}}\n").getBytes(UTF_8);
        private final BufferedReader reader;
        private final java.io.OutputStream stdout;
        private final CountDownLatch eof = new CountDownLatch(1);
        private byte[] pending = new byte[0];
        private int cursor;

        private ProtocolGuardInputStream(InputStream input, java.io.OutputStream stdout) {
            this.reader = new BufferedReader(new InputStreamReader(input, UTF_8));
            this.stdout = stdout;
        }

        @Override public synchronized int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
        }

        @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length == 0) return 0;
                while (cursor >= pending.length) {
                    String line = reader.readLine();
                    if (line == null) { eof.countDown(); return -1; }
                    JsonNode message;
                    try {
                        message = JSON.readTree(line);
                    } catch (IOException malformed) {
                        writeError(PARSE_ERROR);
                        continue;
                    }
                    if (!validJsonRpc(message)) {
                        writeError(INVALID_REQUEST);
                        continue;
                    }
                    pending = (line + "\n").getBytes(UTF_8);
                    cursor = 0;
                }
                int count = Math.min(length, pending.length - cursor);
                System.arraycopy(pending, cursor, bytes, offset, count);
                cursor += count;
                return count;
            } catch (IOException | RuntimeException failure) {
                eof.countDown();
                throw failure;
            }
        }

        private void writeError(byte[] error) throws IOException {
            synchronized (stdout) { stdout.write(error); stdout.flush(); }
        }

        private static boolean validJsonRpc(JsonNode message) {
            if (message == null || !message.isObject()
                    || !message.path("jsonrpc").isTextual()
                    || !"2.0".equals(message.path("jsonrpc").textValue())) return false;
            boolean hasMethod = message.has("method");
            boolean hasResult = message.has("result");
            boolean hasError = message.has("error");
            boolean hasId = message.has("id");
            if (hasMethod) {
                if (!message.get("method").isTextual() || message.get("method").textValue().isBlank()
                        || hasResult || hasError || (message.has("params") && !message.get("params").isObject())) {
                    return false;
                }
                return !hasId || validId(message.get("id"));
            }
            if (!hasId || !validId(message.get("id")) || hasResult == hasError || message.has("params")) return false;
            if (hasError) {
                JsonNode error = message.get("error");
                return error.isObject() && error.path("code").isIntegralNumber()
                        && error.path("message").isTextual();
            }
            return true;
        }

        private static boolean validId(JsonNode id) {
            return id != null && (id.isTextual() || id.isIntegralNumber());
        }
        private void awaitEof() throws InterruptedException { eof.await(); }
    }
}
