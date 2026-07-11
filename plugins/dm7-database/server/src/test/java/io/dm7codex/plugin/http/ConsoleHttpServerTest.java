package io.dm7codex.plugin.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dm7codex.plugin.execution.ExecutionEventBus;
import io.dm7codex.plugin.runtime.SessionState;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ConsoleHttpServerTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();
    private ConsoleTokenService tokens; private FakeBackend backend; private ConsoleHttpServer server;
    private URI base; private HttpClient client; private String cookie;

    @BeforeEach void start() throws Exception {
        tokens = new ConsoleTokenService(); backend = new FakeBackend();
        server = new ConsoleHttpServer(tokens, backend, new ExecutionEventBus(8),8,1,
                Duration.ofMillis(200),Duration.ofSeconds(15));
        base = server.start(); assertTrue(InetAddress.getByName(base.getHost()).isLoopbackAddress());
        assertEquals(base, server.start()); client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var state = new SessionState("internal-a", "external", 1, null, Path.of("active.sql"), Instant.now());
        URI redeem = URI.create((String) server.open(state).get("url"));
        var response = request("POST", redeem.getRawPath()+"?"+redeem.getRawQuery(), "", null, base.toString());
        assertEquals(303, response.statusCode()); assertEquals("/app/", response.headers().firstValue("Location").orElseThrow());
        cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Strict"));
        assertEquals(401, request("POST", redeem.getRawPath()+"?"+redeem.getRawQuery(), "", null, base.toString()).statusCode());
    }

    @AfterEach void close() { server.close(); }

    @Test void enforcesHostOriginCookieHeadersMethodsMediaAndBounds() throws Exception {
        assertEquals(401, request("GET", "/api/runtime", null, null, null).statusCode());
        assertEquals(403, raw("POST", "/api/query", "{}", cookie, "http://evil.test", null).statusCode());
        assertEquals(405, request("GET", "/api/query", null, cookie, null).statusCode());
        assertEquals("POST", request("GET", "/api/query", null, cookie, null).headers().firstValue("Allow").orElseThrow());
        assertEquals(415, raw("POST", "/api/query", "{}", cookie, base.toString(), null, "text/plain").statusCode());
        assertEquals(400, request("POST", "/api/query", "{", cookie, base.toString()).statusCode());
        assertEquals(400, request("POST", "/api/query", "{\"password\":\"a\",\"password\":\"b\"}", cookie, base.toString()).statusCode());
        assertEquals(422,call("POST","/api/query",Map.of("sql",7)).statusCode());
        assertEquals(422,call("DELETE","/api/connections/id-a",Map.of("leaveWithoutDefault","yes")).statusCode());
        assertEquals(422,call("POST","/api/query",Map.of("sql","select 1","unknown",true)).statusCode());
        assertEquals(404,call("GET","/api/connections/missing",null).statusCode());
        assertEquals(409,call("POST","/api/connections",Map.of("name","duplicate")).statusCode());
        assertEquals(413, request("POST", "/api/query", "x".repeat(ConsoleHttpServer.MAX_BODY_BYTES + 1), cookie, base.toString()).statusCode());
        var ok = request("GET", "/api/runtime", null, cookie, null);
        assertEquals(200, ok.statusCode()); assertEquals("no-referrer", ok.headers().firstValue("Referrer-Policy").orElseThrow());
        assertTrue(ok.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
    }

    @Test void slowChunkedRequestTimesOutAndBodyCapacityRecovers() throws Exception {
        server.close();server=new ConsoleHttpServer(tokens,backend,new ExecutionEventBus(8),8,1,1,
                Duration.ofMillis(200),Duration.ofSeconds(15),Duration.ofMillis(150));base=server.start();
        var state=new SessionState("internal-a","external",1,null,Path.of("active.sql"),Instant.now());
        URI redeem=URI.create((String)server.open(state).get("url"));var redeemed=request("POST",redeem.getRawPath()+"?"+redeem.getRawQuery(),"",null,base.toString());
        cookie=redeemed.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        try(var slow=new java.net.Socket("127.0.0.1",base.getPort())){
            slow.setSoTimeout(2000);String headers="POST /api/query HTTP/1.1\r\nHost: 127.0.0.1:"+base.getPort()
                    +"\r\nOrigin: "+base+"\r\nCookie: "+cookie+"\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\n{";
            slow.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));slow.getOutputStream().flush();
            String response=new String(slow.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 408")||response.isEmpty(),response);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
            HttpResponse<String> recovered;do{recovered=call("POST","/api/query",Map.of("sql","select 1"));if(recovered.statusCode()==429)Thread.sleep(20);}while(recovered.statusCode()==429);
            assertEquals(200,recovered.statusCode());
        });
    }

    @Test void rejectsDnsRebindingHostAtTheSocketBoundary() throws Exception {
        try(var socket=new java.net.Socket("127.0.0.1",base.getPort())){
            socket.getOutputStream().write(("GET /api/runtime HTTP/1.1\r\nHost: evil.test:"+base.getPort()+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 403"),response);
        }
    }

    @Test void exposesEveryApiGroupWithSessionScopeAndChineseUtf8() throws Exception {
        var calls = List.of(
                call("GET", "/api/runtime", null), call("GET", "/api/connections", null),
                call("POST", "/api/connections", Map.of("name", "中文", "password", "never-return")),
                call("PUT", "/api/connections/id-a", Map.of("name", "更新")),
                call("DELETE", "/api/connections/id-a", Map.of()),
                call("POST", "/api/connections/id-a/default", Map.of()),
                call("POST", "/api/connections/id-a/test", Map.of()),
                call("GET", "/api/connections/diagnostics?jdbcUrl=jdbc%3Adm7%3Aexample", null),
                call("POST", "/api/sql/classify", Map.of("sql", "select 1")),
                call("POST", "/api/query", Map.of("sql", "select '达梦'")),
                call("POST", "/api/execute", Map.of("sql", "update t set n=1", "purpose", "test")),
                call("GET", "/api/metadata?limit=10", null),
                call("GET", "/api/executions/run-a", null),
                call("POST", "/api/executions/run-a/cancel", Map.of()),
                call("GET", "/api/history?limit=10", null),
                call("GET", "/api/release", null),
                call("POST", "/api/release/export", Map.of("confirm", true)));
        for (HttpResponse<String> result : calls) { assertEquals(200, result.statusCode(), result.body()); assertTrue(result.body().contains("中文响应")); assertFalse(result.body().contains("never-return")); }
        assertEquals(Set.of("internal-a"), backend.seenSessions);
    }

    @Test void deleteAcceptsExplicitDefaultDispositionInJsonBody() throws Exception {
        assertEquals(200,call("DELETE","/api/connections/id-a",Map.of("replacementDefaultId","id-b")).statusCode());
        assertEquals("id-b",backend.lastInput.get("replacementDefaultId"));
    }

    @Test void recoverRouteRequiresPostExactFieldsAndTypedConfirmation() throws Exception {
        assertEquals(405,call("GET","/api/release/recover",null).statusCode());
        assertEquals(422,call("POST","/api/release/recover",Map.of("version","v001","confirm","yes")).statusCode());
        assertEquals(422,call("POST","/api/release/recover",Map.of("version",1,"confirm",true)).statusCode());
        assertEquals(422,call("POST","/api/release/recover",Map.of("version","v001","confirm",true,"sessionId","other")).statusCode());
        var response=call("POST","/api/release/recover",Map.of("version","v001","confirm",true));
        assertEquals(200,response.statusCode());assertEquals("v001",backend.lastInput.get("version"));assertEquals(true,backend.lastInput.get("confirm"));
        var unavailable=call("POST","/api/release/recover",Map.of("version","v404","confirm",true));assertEquals(409,unavailable.statusCode());assertTrue(unavailable.body().contains("RELEASE_RECOVERY_UNAVAILABLE"));assertTrue(unavailable.body().contains("correlationId"));
        var databaseFailure=call("POST","/api/release/recover",Map.of("version","v500","confirm",true));assertEquals(500,databaseFailure.statusCode());assertTrue(databaseFailure.body().contains("INTERNAL_ERROR"));assertTrue(databaseFailure.body().contains("correlationId"));assertFalse(databaseFailure.body().contains("C:\\private\\state.db"));
    }

    @Test void secretBearingClassificationFailureIsSafeAndDoesNotEchoSql() throws Exception {
        String sql="create user demo identified by never-echo-this";
        var response=call("POST","/api/sql/classify",Map.of("sql",sql));
        assertEquals(422,response.statusCode());
        assertTrue(response.body().contains("SQL_REJECTED"));
        assertTrue(response.body().contains("correlationId"));
        assertFalse(response.body().contains("never-echo-this"));
    }

    @Test void credentialRecoveryFailuresMapToSafeCorrelated409And500() throws Exception {
        var recoverable=call("DELETE","/api/connections/id-a",Map.of("replacementDefaultId","recovery"));
        assertEquals(409,recoverable.statusCode());assertTrue(recoverable.body().contains("CREDENTIAL_RECOVERY_REQUIRED"));
        assertTrue(recoverable.body().contains("correlationId"));assertFalse(recoverable.body().contains("secret"));
        var uncertain=call("DELETE","/api/connections/id-a",Map.of("replacementDefaultId","uncertain"));
        assertEquals(500,uncertain.statusCode());assertTrue(uncertain.body().contains("CREDENTIAL_STATE_UNCERTAIN"));
        assertTrue(uncertain.body().contains("correlationId"));assertFalse(uncertain.body().contains("secret"));
    }

    @Test void constrainsArtifactDownloadsAndStaticClasspath() throws Exception {
        var download = call("GET", "/api/release/artifacts/good/download", null);
        assertEquals(200, download.statusCode()); assertEquals("-- 中文\n", download.body());
        assertEquals("attachment; filename=\"release.sql\"", download.headers().firstValue("Content-Disposition").orElseThrow());
        assertEquals(Long.toString("-- 中文\n".getBytes(StandardCharsets.UTF_8).length),download.headers().firstValue("Content-Length").orElseThrow());
        assertEquals(404, call("GET", "/api/release/artifacts/other/download", null).statusCode());
        assertEquals(200, request("GET", "/app/sql", null, cookie, null).statusCode());
        assertEquals(200, request("GET", "/app/app.js", null, cookie, null).statusCode());
        assertEquals(404, request("GET", "/app/missing.js", null, cookie, null).statusCode());
        for (String path : List.of("/app/%2e%2e/secret", "/app/%252e%252e/secret", "/app/%2fsecret", "/app/a%00b"))
            assertTrue(request("GET", path, null, cookie, null).statusCode() >= 400);
    }

    @Test void spaHtmlGetsUniqueMatchingCspNonceWithoutWeakeningOtherResponses() throws Exception {
        var first=request("GET","/app/sql",null,cookie,null);
        var second=request("GET","/app/sql",null,cookie,null);
        String firstNonce=htmlNonce(first.body()),secondNonce=htmlNonce(second.body());
        assertNotEquals(firstNonce,secondNonce);
        assertTrue(firstNonce.matches("[A-Za-z0-9_-]{32,}"));
        assertTrue(first.headers().firstValue("Content-Security-Policy").orElseThrow()
                .contains("style-src 'self' 'nonce-"+firstNonce+"'"));
        assertFalse(first.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'unsafe-inline'"));
        assertFalse(first.body().contains("__DM7_CSP_NONCE__"));
        assertEquals("no-store",first.headers().firstValue("Cache-Control").orElseThrow());
        var asset=request("GET","/app/app.js",null,cookie,null);
        assertEquals("no-cache",asset.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(asset.headers().firstValue("Content-Security-Policy").orElseThrow().contains("nonce-"));
        assertFalse(asset.body().contains(firstNonce));
    }

    private static String htmlNonce(String html) {
        var match=java.util.regex.Pattern.compile("<meta name=\"csp-nonce\" content=\"([^\"]+)\"").matcher(html);
        assertTrue(match.find(),html);return match.group(1);
    }

    @Test void boundedDownloadsReturn429AndReleasePermitAfterDisconnect() throws Exception {
        backend.blockDownloads=true;
        var first=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return call("GET","/api/release/artifacts/slow/download",null);}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(backend.downloadEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(429,call("GET","/api/release/artifacts/good/download",null).statusCode());
        backend.releaseDownload.countDown();assertEquals(200,first.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());
        assertEquals(0,server.activeDownloadClients());
    }

    @Test void abortedDownloadClosesSnapshotAndReleasesPermit() throws Exception {
        try(var socket=new java.net.Socket("127.0.0.1",base.getPort())){
            String request="GET /api/release/artifacts/disconnect/download HTTP/1.1\r\nHost: 127.0.0.1:"+base.getPort()+"\r\nCookie: "+cookie+"\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            assertTrue(backend.downloadEntered.await(2,java.util.concurrent.TimeUnit.SECONDS));
        }
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{while(server.activeDownloadClients()!=0)Thread.sleep(20);});
        assertEquals(200,call("GET","/api/release/artifacts/good/download",null).statusCode());
        try(var files=Files.walk(temporary)){assertFalse(files.anyMatch(path->path.getFileName().toString().startsWith("download-")&&path.toString().endsWith(".tmp")));}
    }

    @Test void shutdownReleasesListenerAndNewServerCanStart() throws Exception {
        URI old = base; server.close(); assertThrows(Exception.class, () -> client.send(
                HttpRequest.newBuilder(old.resolve("/app/")).timeout(Duration.ofMillis(300)).GET().build(), HttpResponse.BodyHandlers.ofString()));
        server = new ConsoleHttpServer(tokens, backend, new ExecutionEventBus(8)); assertNotNull(server.start());
    }

    private HttpResponse<String> call(String method, String path, Object body) throws Exception {
        return request(method, path, body == null ? null : JSON.writeValueAsString(body), cookie,
                Set.of("POST","PUT","PATCH","DELETE").contains(method) ? base.toString() : null);
    }
    private HttpResponse<String> request(String method, String path, String body, String cookie, String origin) throws Exception {
        return raw(method, path, body, cookie, origin, null);
    }
    private HttpResponse<String> raw(String method, String path, String body, String cookie, String origin, String hostOverride) throws Exception {
        return raw(method,path,body,cookie,origin,hostOverride,"application/json; charset=utf-8");
    }
    private HttpResponse<String> raw(String method, String path, String body, String cookie, String origin, String hostOverride, String contentType) throws Exception {
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
        if(cookie!=null) builder.header("Cookie",cookie); if(origin!=null) builder.header("Origin",origin);
        if(body!=null) builder.header("Content-Type",contentType);
        builder.method(method, body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private final class FakeBackend implements ConsoleHttpServer.Backend {
        final Set<String> seenSessions = new HashSet<>();
        volatile boolean blockDownloads;
        final java.util.concurrent.CountDownLatch downloadEntered=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseDownload=new java.util.concurrent.CountDownLatch(1);
        volatile Map<String,Object> lastInput=Map.of();
        @Override public Map<String,Object> call(String operation, Map<String,Object> input, SessionState session) throws Exception {
            lastInput=input;
            if(operation.equals("sql.classify")&&String.valueOf(input.get("sql")).contains("never-echo-this"))throw new io.dm7codex.plugin.sql.SqlClassificationService.ClassificationRejected("EMBEDDED_CREDENTIALS");
            if(operation.equals("connections.delete")&&"recovery".equals(input.get("replacementDefaultId")))throw ConsoleHttpServer.BackendProblem.credentialRecoveryRequired();
            if(operation.equals("connections.delete")&&"uncertain".equals(input.get("replacementDefaultId")))throw ConsoleHttpServer.BackendProblem.credentialStateUncertain();
            seenSessions.add(session.sessionId());
            if(operation.equals("connections.get")&&"missing".equals(input.get("id")))throw ConsoleHttpServer.BackendProblem.notFound();
            if(operation.equals("connections.create")&&"duplicate".equals(input.get("name")))throw ConsoleHttpServer.BackendProblem.conflict();
            if(operation.equals("release.recover")&&"v404".equals(input.get("version")))throw ConsoleHttpServer.BackendProblem.releaseRecoveryUnavailable();
            if(operation.equals("release.recover")&&"v500".equals(input.get("version")))throw new java.sql.SQLException("C:\\private\\state.db unavailable");
            return Map.of("operation",operation,"message","中文响应");
        }
        @Override public Optional<ConsoleHttpServer.Download> download(String id, SessionState session) throws Exception {
            seenSessions.add(session.sessionId());
            if(blockDownloads&&id.equals("slow")){downloadEntered.countDown();releaseDownload.await();}
            if(id.equals("disconnect"))downloadEntered.countDown();
            if(!id.equals("good")&&!id.equals("slow")&&!id.equals("disconnect"))return Optional.empty();
            Path source=id.equals("disconnect")?largeTemporaryFile():temporaryFile("-- 中文\n");return Optional.of(ConsoleHttpServer.Download.snapshot("release.sql",
                    "application/sql; charset=utf-8",source,source.getParent().resolve("snapshots"),sha256(source),
                    id.equals("disconnect")?16L*1024*1024:1024));
        }
        private Path temporaryFile(String value)throws Exception{Path dir=temporary.resolve(UUID.randomUUID().toString());Files.createDirectories(dir);Path file=dir.resolve("release.sql");Files.writeString(file,value,StandardCharsets.UTF_8);return file;}
        private Path largeTemporaryFile()throws Exception{Path dir=temporary.resolve(UUID.randomUUID().toString());Files.createDirectories(dir);Path file=dir.resolve("release.sql");try(var out=Files.newOutputStream(file)){byte[] block=new byte[64*1024];for(int i=0;i<128;i++)out.write(block);}return file;}
        private static String sha256(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    }
}
