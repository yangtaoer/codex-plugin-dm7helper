package io.dm7codex.plugin;

import io.dm7codex.plugin.mcp.Dm7McpServer;
import io.dm7codex.plugin.mcp.Dm7ServicesBackend;
import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentityResolver;
import io.dm7codex.plugin.http.ConsoleHttpServer;
import io.dm7codex.plugin.http.ConsoleTokenService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
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
            System.err.println("Failure kinds: " + safeFailureKinds(startupFailure));
            try { writeStartupDiagnostic(System.getenv(), pluginRoot(), startupFailure); }
            catch (Throwable ignored) { /* stderr remains the safe fallback */ }
            System.exit(1);
        }
    }

    static String safeFailureKinds(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        var kinds = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (depth > 0) kinds.append(" -> ");
            String kind = current.getClass().getSimpleName();
            kinds.append(kind.isBlank() ? "Throwable" : kind);
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return kinds.toString();
    }

    static void writeStartupDiagnostic(Map<String, String> environment, Path root, Throwable failure)
            throws IOException {
        Path logs = RuntimePaths.fromEnvironment(environment, root).logsDirectory();
        java.nio.file.Files.createDirectories(logs);
        java.nio.file.Files.writeString(logs.resolve("startup-failure.log"),
                safeFailureKinds(failure) + "\n", UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE);
    }

    static void runStdio(Map<String, String> environment, InputStream stdin, java.io.OutputStream stdout)
            throws Exception {
        RuntimePaths paths = RuntimePaths.fromEnvironment(environment, pluginRoot());
        try (var runtime = new LazyRuntime(paths)) {
            var adapter = adapter(environment, runtime);
            var input = new ProtocolGuardInputStream(stdin, stdout);
            var defaults = McpJsonDefaults.getMapper();
            if (!(defaults instanceof JacksonMcpJsonMapper jacksonDefaults)) {
                throw new IllegalStateException("The configured MCP JSON mapper cannot preserve numeric precision");
            }
            var preciseJson = new JacksonMcpJsonMapper(jacksonDefaults.getObjectMapper().copy()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
            var transport = new StdioServerTransportProvider(preciseJson, input, stdout);
            McpSyncServer server = McpServer.sync(transport)
                    .serverInfo("dm7-database", "0.1.0")
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    // Validation belongs in handlers so session initialization always runs first.
                    .validateToolInputs(false)
                    .jsonMapper(preciseJson)
                    .tools(adapter.toolSpecifications())
                    .build();
            try {
                input.awaitEof();
            } finally {
                server.closeGracefully();
            }
        }
    }

    static Dm7McpServer adapter(Map<String,String> environment,Dm7ServicesBackend backend,ConsoleHttpServer console) {
        return new Dm7McpServer(() -> SessionIdentityResolver.resolve(environment), backend::initialize,
                backend, console::open);
    }

    private static Dm7McpServer adapter(Map<String,String> environment, LazyRuntime runtime) {
        return new Dm7McpServer(() -> SessionIdentityResolver.resolve(environment), runtime::initialize,
                runtime::call, runtime::openConsole);
    }

    private static final class LazyRuntime implements AutoCloseable {
        private final RuntimePaths paths;
        private Bundle bundle;

        private LazyRuntime(RuntimePaths paths) { this.paths = Objects.requireNonNull(paths); }

        private io.dm7codex.plugin.runtime.SessionState initialize(
                io.dm7codex.plugin.runtime.SessionIdentity identity) throws Exception {
            return bundle().backend().initialize(identity);
        }

        private Map<String,Object> call(String operation, Map<String,Object> arguments,
                io.dm7codex.plugin.runtime.SessionState session) throws Exception {
            return bundle().backend().call(operation, arguments, session);
        }

        private Map<String,Object> openConsole(io.dm7codex.plugin.runtime.SessionState session)
                throws Exception {
            return bundle().console().open(session);
        }

        private synchronized Bundle bundle() throws Exception {
            if (bundle != null) return bundle;
            Dm7ServicesBackend backend = Dm7ServicesBackend.open(paths);
            try {
                var console = new ConsoleHttpServer(new ConsoleTokenService(), backend, backend.eventBus());
                bundle = new Bundle(backend, console);
                return bundle;
            } catch (Exception failure) {
                backend.close();
                throw failure;
            }
        }

        @Override public synchronized void close() throws Exception {
            if (bundle == null) return;
            Exception failure = null;
            try { bundle.console().close(); }
            catch (Exception closeFailure) { failure = closeFailure; }
            try { bundle.backend().close(); }
            catch (Exception closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            bundle = null;
            if (failure != null) throw failure;
        }

        private record Bundle(Dm7ServicesBackend backend, ConsoleHttpServer console) {}
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
        private static final ObjectMapper JSON = new ObjectMapper()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
            this.reader = new BufferedReader(new InputStreamReader(input, UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)));
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
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (CharacterCodingException malformedUtf8) {
                        writeError(PARSE_ERROR);
                        eof.countDown();
                        return -1;
                    }
                    if (line == null) { eof.countDown(); return -1; }
                    JsonNode message;
                    try {
                        message = JSON.readTree(line);
                    } catch (IOException malformed) {
                        writeError(PARSE_ERROR);
                        continue;
                    }
                    if (message == null || message.isMissingNode()) {
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
                        && error.path("code").canConvertToInt()
                        && error.path("message").isTextual();
            }
            return message.get("result") != null && message.get("result").isObject();
        }

        private static boolean validId(JsonNode id) {
            return id != null && (id.isTextual()
                    || (id.isIntegralNumber() && id.canConvertToLong()));
        }
        private void awaitEof() throws InterruptedException { eof.await(); }
    }
}
