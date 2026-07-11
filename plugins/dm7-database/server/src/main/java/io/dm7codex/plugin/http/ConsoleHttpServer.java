package io.dm7codex.plugin.http;

import com.sun.net.httpserver.*;
import io.dm7codex.plugin.execution.ExecutionEventBus;
import io.dm7codex.plugin.runtime.SessionState;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secured loopback-only HTTP adapter for the packaged console SPA. */
public final class ConsoleHttpServer implements AutoCloseable {
    public static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String COOKIE = "dm7_console";
    private final ConsoleTokenService tokens; private final Backend backend; private final ExecutionEventBus events;
    private final int maxSseClients; private final Duration pollInterval; private final Duration heartbeatInterval;
    private final Semaphore sseClients;
    private final int maxDownloadClients;
    private final Semaphore downloadClients;
    private final ThreadPoolExecutor requestBodyReaders;
    private final Duration requestBodyTimeout;
    private final HttpSecurity.BrowserSessions browserSessions = new HttpSecurity.BrowserSessions();
    private final LinkedHashMap<String,SessionState> sessionStates = new LinkedHashMap<>(16,.75f,true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpServer server; private URI base; private HttpSecurity security; private ThreadPoolExecutor executor;

    public ConsoleHttpServer(ConsoleTokenService tokens, Backend backend, ExecutionEventBus events) {
        this(tokens,backend,events,8,2,4,Duration.ofMillis(200),Duration.ofSeconds(15),Duration.ofSeconds(3));
    }
    ConsoleHttpServer(ConsoleTokenService tokens, Backend backend, ExecutionEventBus events,
                      int maxSseClients, Duration pollInterval, Duration heartbeatInterval) {
        this(tokens,backend,events,maxSseClients,2,4,pollInterval,heartbeatInterval,Duration.ofSeconds(3));
    }
    ConsoleHttpServer(ConsoleTokenService tokens, Backend backend, ExecutionEventBus events,
                      int maxSseClients,int maxDownloadClients,Duration pollInterval, Duration heartbeatInterval) {
        this(tokens,backend,events,maxSseClients,maxDownloadClients,4,pollInterval,heartbeatInterval,Duration.ofSeconds(3));
    }
    ConsoleHttpServer(ConsoleTokenService tokens,Backend backend,ExecutionEventBus events,int maxSseClients,
                      int maxDownloadClients,int maxBodyReaders,Duration pollInterval,Duration heartbeatInterval,
                      Duration requestBodyTimeout){
        this.tokens=Objects.requireNonNull(tokens); this.backend=Objects.requireNonNull(backend); this.events=Objects.requireNonNull(events);
        if(maxSseClients<1||maxSseClients>64||pollInterval.isNegative()||pollInterval.isZero()
                ||heartbeatInterval.isNegative()||heartbeatInterval.isZero()||maxDownloadClients<1||maxDownloadClients>16
                ||maxBodyReaders<1||maxBodyReaders>16||requestBodyTimeout.isNegative()||requestBodyTimeout.isZero())throw new IllegalArgumentException("invalid HTTP bounds");
        this.maxSseClients=maxSseClients;this.pollInterval=pollInterval;this.heartbeatInterval=heartbeatInterval;
        this.sseClients=new Semaphore(maxSseClients);
        this.maxDownloadClients=maxDownloadClients;this.downloadClients=new Semaphore(maxDownloadClients);
        this.requestBodyTimeout=requestBodyTimeout;
        this.requestBodyReaders=new ThreadPoolExecutor(maxBodyReaders,maxBodyReaders,0,TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxBodyReaders),r->{var t=new Thread(r,"dm7-request-body");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized URI start() throws IOException {
        if (closed.get()) throw new IllegalStateException("server is closed");
        if (base != null) return base;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        if (!loopback.isLoopbackAddress()) throw new IllegalStateException("loopback unavailable");
        HttpServer candidate = HttpServer.create(new InetSocketAddress(loopback,0), 0);
        executor = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), runnable -> { var t=new Thread(runnable,"dm7-console-http"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());
        candidate.setExecutor(executor);
        int port=candidate.getAddress().getPort(); base=URI.create("http://127.0.0.1:"+port); security=new HttpSecurity(base);
        try { candidate.createContext("/", this::handle); server=candidate; candidate.start(); return base; }
        catch(RuntimeException failure){candidate.stop(0);executor.shutdownNow();server=null;base=null;throw failure;}
    }

    public Map<String,Object> open(SessionState state) throws Exception {
        Objects.requireNonNull(state); URI uri=start();
        synchronized (sessionStates) {
            sessionStates.put(state.sessionId(),state);
            while(sessionStates.size()>128) sessionStates.remove(sessionStates.keySet().iterator().next());
        }
        Map<String,Object> runtime=backend.call("runtime",Map.of(),state);
        Map<String,Object> listed=backend.call("connections.list",Map.of(),state);
        String token=tokens.issue(state.sessionId());
        var result=new LinkedHashMap<String,Object>();result.put("url",uri+"/console/redeem?token="+token);
        result.put("sessionShortId",state.sessionId().substring(0,Math.min(12,state.sessionId().length())));
        result.put("currentVersion",runtime.getOrDefault("currentVersion",String.format("v%03d",state.version())));
        result.put("connection",safeCurrentConnection(listed));return Collections.unmodifiableMap(result);
    }
    private static Map<String,Object> safeCurrentConnection(Map<String,Object> listed){
        Object raw=listed.get("connections");if(!(raw instanceof List<?> values)||values.isEmpty())return Map.of("configured",false,"connected",false);
        Map<?,?> selected=values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(value->Boolean.TRUE.equals(value.get("isDefault"))).findFirst().orElse(null);
        if(selected==null)return Map.of("configured",true,"connected",false,"isDefault",false);
        var safe=new LinkedHashMap<String,Object>();for(String key:List.of("id","name","urlSummary","schema","isDefault","configured","connected"))if(selected.containsKey(key))safe.put(key,selected.get(key));
        safe.putIfAbsent("configured",true);safe.putIfAbsent("connected",false);return Collections.unmodifiableMap(safe);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            applyHeaders(exchange, true);
            String raw=exchange.getRequestURI().getRawPath();
            if(!HttpSecurity.safePath(raw)) throw new JsonHttp.HttpProblem(400,"INVALID_PATH","请求路径无效。");
            String host=exchange.getRequestHeaders().getFirst("Host"); String origin=exchange.getRequestHeaders().getFirst("Origin");
            var authority=security.validateAuthority(host,origin,exchange.getRequestMethod());
            if(authority.isPresent()) throw new JsonHttp.HttpProblem(403,authority.get(),"请求来源被拒绝。");
            if(raw.equals("/console/redeem")){ redeem(exchange); return; }
            if(raw.startsWith("/app/")){ SessionState ignored=authenticate(exchange); staticAsset(exchange,raw); return; }
            if(raw.startsWith("/api/")){ SessionState state=authenticate(exchange); api(exchange,raw,state); return; }
            throw new JsonHttp.HttpProblem(404,"NOT_FOUND","资源不存在。");
        } catch(JsonHttp.HttpProblem problem){ safeError(exchange,problem.status(),problem.code(),problem.safeMessage()); }
        catch(BackendProblem problem){safeError(exchange,problem.status(),problem.code(),problem.safeMessage());}
        catch(io.dm7codex.plugin.sql.SqlClassificationService.ClassificationRejected|io.dm7codex.plugin.sql.SecretBearingSqlException rejected){safeError(exchange,422,"SQL_REJECTED","SQL 不符合安全执行约束。");}
        catch(DownloadRejected rejected){safeError(exchange,409,rejected.code(),"导出文件校验失败。");}
        catch(RejectedExecutionException busy){ safeError(exchange,429,"SERVER_BUSY","服务忙，请稍后重试。"); }
        catch(IllegalArgumentException|ClassCastException|java.time.DateTimeException invalid){ safeError(exchange,422,"INVALID_ARGUMENT","请求参数无效。"); }
        catch(Exception failure){ safeError(exchange,500,"INTERNAL_ERROR","服务器无法完成请求。"); }
        finally { exchange.close(); }
    }

    private void redeem(HttpExchange x) throws IOException, JsonHttp.HttpProblem {
        method(x,"POST"); Map<String,String> query=query(x.getRequestURI().getRawQuery());
        if(query.size()!=1 || !query.containsKey("token")) throw new JsonHttp.HttpProblem(400,"INVALID_TOKEN","控制台令牌无效。");
        String sessionId=tokens.consume(query.get("token")).orElseThrow(() -> new JsonHttp.HttpProblem(401,"INVALID_TOKEN","控制台令牌无效。"));
        synchronized(sessionStates){ if(!sessionStates.containsKey(sessionId)) throw new JsonHttp.HttpProblem(401,"INVALID_TOKEN","控制台令牌无效。"); }
        String cookie=browserSessions.create(sessionId,security.origin());
        x.getResponseHeaders().set("Set-Cookie",COOKIE+"="+cookie+"; Path=/; HttpOnly; SameSite=Strict");
        x.getResponseHeaders().set("Location","/app/"); x.sendResponseHeaders(303,-1);
    }

    private SessionState authenticate(HttpExchange x) throws JsonHttp.HttpProblem {
        String cookieHeader=x.getRequestHeaders().getFirst("Cookie"), value=null;
        if(cookieHeader!=null) for(String item:cookieHeader.split(";")){ String[] pair=item.trim().split("=",2); if(pair.length==2&&pair[0].equals(COOKIE)) value=pair[1]; }
        String sessionId=browserSessions.authenticate(value,security.origin()).orElseThrow(() -> new JsonHttp.HttpProblem(401,"AUTH_REQUIRED","需要控制台会话。"));
        synchronized(sessionStates){ SessionState state=sessionStates.get(sessionId); if(state==null) throw new JsonHttp.HttpProblem(401,"AUTH_REQUIRED","控制台会话已失效。"); return state; }
    }

    private void api(HttpExchange x,String path,SessionState state) throws Exception {
        String method=x.getRequestMethod(); Map<String,Object> input;
        String operation;
        if(path.equals("/api/runtime")){ allow(x,"GET"); operation="runtime"; input=queryObject(x); }
        else if(path.equals("/api/connections")){ allow(x,"GET","POST"); operation=method.equals("GET")?"connections.list":"connections.create"; input=bodyOrQuery(x); }
        else if(path.equals("/api/connections/diagnostics")){ allow(x,"GET"); operation="connections.diagnostics"; input=queryObject(x); }
        else if(path.matches("/api/connections/[^/]+/default")){ allow(x,"POST"); operation="connections.default"; input=withId(bodyOrQuery(x),segment(path,3)); }
        else if(path.matches("/api/connections/[^/]+/test")){ allow(x,"POST"); operation="connections.test"; input=withId(bodyOrQuery(x),segment(path,3)); }
        else if(path.matches("/api/connections/[^/]+")){ allow(x,"GET","PUT","DELETE"); operation="connections."+switch(method){case"GET"->"get";case"PUT"->"update";default->"delete";}; input=withId(bodyOrQuery(x),segment(path,3)); }
        else if(path.equals("/api/sql/classify")){ allow(x,"POST"); operation="sql.classify"; input=bodyOrQuery(x); }
        else if(path.equals("/api/query")){ allow(x,"POST"); operation="query"; input=bodyOrQuery(x); }
        else if(path.equals("/api/execute")){ allow(x,"POST"); operation="execute"; input=bodyOrQuery(x); }
        else if(path.equals("/api/metadata")){ allow(x,"GET"); operation="metadata"; input=queryObject(x); }
        else if(path.matches("/api/executions/[^/]+/cancel")){ allow(x,"POST"); operation="executions.cancel"; input=withId(bodyOrQuery(x),segment(path,3)); }
        else if(path.matches("/api/executions/[^/]+")){ allow(x,"GET"); operation="executions.get"; input=withId(queryObject(x),segment(path,3)); }
        else if(path.equals("/api/history")){ allow(x,"GET"); operation="history"; input=queryObject(x); }
        else if(path.equals("/api/release")){ allow(x,"GET"); operation="release.preview"; input=queryObject(x); }
        else if(path.equals("/api/release/export")){ allow(x,"POST"); operation="release.export"; input=bodyOrQuery(x); }
        else if(path.matches("/api/release/artifacts/[^/]+/download")){ allow(x,"GET"); download(x,segment(path,4),state); return; }
        else if(path.equals("/api/events")){ allow(x,"GET"); if(x.getRequestURI().getRawQuery()!=null)throw new JsonHttp.HttpProblem(400,"UNKNOWN_FIELD","事件请求不允许查询参数。");sse(x,state); return; }
        else throw new JsonHttp.HttpProblem(404,"NOT_FOUND","资源不存在。");
        validateFields(operation,input);validateTypes(operation,input);
        json(x,200,backend.call(operation,input,state));
    }

    private Map<String,Object> bodyOrQuery(HttpExchange x) throws IOException,JsonHttp.HttpProblem { return switch(x.getRequestMethod()){case"POST","PUT","PATCH","DELETE"->JsonHttp.readObject(x,MAX_BODY_BYTES,requestBodyReaders,requestBodyTimeout);default->queryObject(x);}; }
    private static Map<String,Object> withId(Map<String,Object> values,String id){var copy=new LinkedHashMap<>(values); copy.put("id",id); return copy;}
    private static void validateFields(String operation,Map<String,Object> input)throws JsonHttp.HttpProblem{
        Set<String> allowed=switch(operation){
            case"runtime","connections.list","release.preview"->Set.of();
            case"connections.create","connections.update"->Set.of("id","name","driverJar","driverClass","jdbcUrl","username","password","clearPassword","schema","connectTimeoutSeconds","socketTimeoutSeconds","queryTimeoutSeconds","maxRows","maxBytes","isDefault");
            case"connections.get","connections.default","connections.test","executions.get","executions.cancel"->Set.of("id");
            case"connections.delete"->Set.of("id","replacementDefaultId","leaveWithoutDefault");
            case"connections.diagnostics"->Set.of("jdbcUrl");
            case"sql.classify"->Set.of("sql");
            case"query"->Set.of("connectionId","executionId","sql","parameters","maxRows","maxBytes","timeoutSeconds");
            case"execute"->Set.of("connectionId","executionId","sql","parameters","purpose","atomic","continueOnError","timeoutSeconds");
            case"metadata"->Set.of("connectionId","schemaPattern","objectPattern","offset","limit");
            case"history"->Set.of("status","source","purpose","offset","limit","startedAfter","startedBefore","recorded","correlationId","success","kind");
            case"release.export"->Set.of("confirm"); default->Set.of();};
        if(!allowed.containsAll(input.keySet()))throw new JsonHttp.HttpProblem(422,"UNKNOWN_FIELD","请求包含不允许的字段。");
    }
    private static void validateTypes(String operation,Map<String,Object> input)throws JsonHttp.HttpProblem{
        if(operation.equals("sql.classify")||operation.equals("query")||operation.equals("execute"))requireText(input,"sql");
        if(operation.equals("execute"))requireText(input,"purpose");
        if(operation.equals("release.export")&&!(input.get("confirm")instanceof Boolean))invalidType();
        if(operation.startsWith("connections.")&&!operation.equals("connections.list")&&!operation.equals("connections.create")&&!operation.equals("connections.diagnostics"))requireText(input,"id");
        for(String key:List.of("name","driverJar","driverClass","jdbcUrl","username","password","schema"))if(input.containsKey(key)&&!(input.get(key)instanceof String))invalidType();
        if(input.containsKey("clearPassword")&&!(input.get("clearPassword")instanceof Boolean))invalidType();
        if(input.containsKey("replacementDefaultId")&&!(input.get("replacementDefaultId")instanceof String))invalidType();
        if(input.containsKey("leaveWithoutDefault")&&!(input.get("leaveWithoutDefault")instanceof Boolean))invalidType();
        for(String key:List.of("connectTimeoutSeconds","socketTimeoutSeconds","queryTimeoutSeconds","maxRows","maxBytes"))if(input.containsKey(key)&&!(input.get(key)instanceof Number))invalidType();
        if(input.containsKey("isDefault")&&!(input.get("isDefault")instanceof Boolean))invalidType();
    }
    private static void requireText(Map<String,Object> input,String key)throws JsonHttp.HttpProblem{if(!(input.get(key)instanceof String text)||text.isBlank())invalidType();}
    private static void invalidType()throws JsonHttp.HttpProblem{throw new JsonHttp.HttpProblem(422,"INVALID_FIELD_TYPE","请求字段类型无效。");}
    private static String segment(String path,int index){return path.split("/")[index];}
    private void download(HttpExchange x,String id,SessionState state)throws Exception{
        if(!downloadClients.tryAcquire())throw new JsonHttp.HttpProblem(429,"DOWNLOAD_CLIENT_LIMIT","下载连接已达上限。");
        try(var value=backend.download(id,state).orElseThrow(()->new JsonHttp.HttpProblem(404,"NOT_FOUND","导出文件不存在。"))){
            String filename=value.filename().replaceAll("[^A-Za-z0-9._-]","_");
            x.getResponseHeaders().set("Content-Type",value.contentType());
            x.getResponseHeaders().set("Content-Disposition","attachment; filename=\""+filename+"\"");
            x.getResponseHeaders().set("Content-Length",Long.toString(value.length()));
            x.sendResponseHeaders(200,value.length());
            try(var out=x.getResponseBody()){value.writeTo(out);}catch(IOException disconnected){/* client disconnected */}
        }finally{downloadClients.release();}
    }
    int activeDownloadClients(){return maxDownloadClients-downloadClients.availablePermits();}

    private void staticAsset(HttpExchange x,String path)throws IOException,JsonHttp.HttpProblem{
        allow(x,"GET","HEAD");String relative=path.substring("/app/".length());boolean asset=relative.contains(".");
        if(relative.isEmpty()||!asset)relative="index.html";byte[] bytes;
        try(var in=ConsoleHttpServer.class.getResourceAsStream("/web/"+relative)){if(in==null)throw new JsonHttp.HttpProblem(404,"NOT_FOUND","资源不存在。");bytes=JsonHttp.bounded(in,5*1024*1024);}
        String type=mime(relative);x.getResponseHeaders().set("Content-Type",type);x.getResponseHeaders().set("Cache-Control",relative.equals("index.html")?"no-store":"no-cache");
        if(relative.equals("index.html")){
            String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(new java.security.SecureRandom().generateSeed(32));
            String html=new String(bytes,StandardCharsets.UTF_8);
            int placeholder=html.indexOf("__DM7_CSP_NONCE__");
            if(placeholder<0||html.indexOf("__DM7_CSP_NONCE__",placeholder+1)>=0)throw new JsonHttp.HttpProblem(500,"INVALID_WEB_ASSET","控制台资源无效。");
            bytes=html.replace("__DM7_CSP_NONCE__",nonce).getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Security-Policy",HttpSecurity.CSP.replace("style-src 'self'","style-src 'self' 'nonce-"+nonce+"'"));
        }
        if(x.getRequestMethod().equals("HEAD"))x.sendResponseHeaders(200,-1);else send(x,200,bytes);
    }
    private static String mime(String name){String lower=name.toLowerCase(Locale.ROOT);if(lower.endsWith(".html"))return"text/html; charset=utf-8";if(lower.endsWith(".js")||lower.endsWith(".mjs"))return"text/javascript; charset=utf-8";if(lower.endsWith(".css"))return"text/css; charset=utf-8";if(lower.endsWith(".json"))return"application/json; charset=utf-8";if(lower.endsWith(".svg"))return"image/svg+xml";if(lower.endsWith(".png"))return"image/png";if(lower.endsWith(".ico"))return"image/x-icon";if(lower.endsWith(".woff2"))return"font/woff2";return"application/octet-stream";}

    private void sse(HttpExchange x,SessionState state)throws IOException,JsonHttp.HttpProblem{
        long after=parseLastEventId(x.getRequestHeaders().getFirst("Last-Event-ID"));
        var retained=events.events(state.sessionId(),0);
        if(after>0&&(retained.isEmpty()||retained.get(0).sequence()>after+1||after>retained.get(retained.size()-1).sequence()))
            throw new JsonHttp.HttpProblem(409,"EVENT_REPLAY_MISSED","事件重放窗口已过期，请刷新状态。");
        if(!sseClients.tryAcquire())throw new JsonHttp.HttpProblem(429,"SSE_CLIENT_LIMIT","实时连接已达上限。");
        try{
            x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            x.getResponseHeaders().set("Connection","keep-alive"); x.sendResponseHeaders(200,0);
            try(var out=x.getResponseBody()){
                writeSse(out,": connected\n\n"); long last=after; Instant heartbeat=Instant.now();
                while(!closed.get()&&!Thread.currentThread().isInterrupted()){
                    var available=events.events(state.sessionId(),last);
                    for(var event:available){
                        var data=new LinkedHashMap<String,Object>();data.put("executionId",event.executionId().toString());
                        data.put("status",event.status().name().toLowerCase(Locale.ROOT));data.put("timestamp",event.timestamp().toString());data.put("detail",event.detail());
                        String message="id: "+event.sequence()+"\nevent: "+event.status().name().toLowerCase(Locale.ROOT)
                                +"\ndata: "+JsonHttp.JSON.writeValueAsString(data)+"\n\n";
                        writeSse(out,message);last=event.sequence();heartbeat=Instant.now();
                    }
                    if(Duration.between(heartbeat,Instant.now()).compareTo(heartbeatInterval)>=0){writeSse(out,": heartbeat\n\n");heartbeat=Instant.now();}
                    try{Thread.sleep(pollInterval.toMillis());}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}
                }
            }catch(IOException disconnected){/* Normal browser disconnect; no details are logged. */}
        }finally{sseClients.release();}
    }
    private static void writeSse(OutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));out.flush();}
    private static long parseLastEventId(String value)throws JsonHttp.HttpProblem{if(value==null||value.isBlank())return 0;try{long id=Long.parseLong(value);if(id<0)throw new NumberFormatException();return id;}catch(NumberFormatException invalid){throw new JsonHttp.HttpProblem(400,"INVALID_EVENT_ID","Last-Event-ID 无效。");}}
    int activeSseClients(){return maxSseClients-sseClients.availablePermits();}
    private static Map<String,Object> queryObject(HttpExchange x)throws JsonHttp.HttpProblem{var result=new LinkedHashMap<String,Object>();query(x.getRequestURI().getRawQuery()).forEach(result::put);return result;}
    private static Map<String,String> query(String raw)throws JsonHttp.HttpProblem{var result=new LinkedHashMap<String,String>();if(raw==null||raw.isEmpty())return result;for(String part:raw.split("&",-1)){String[] pair=part.split("=",2);try{String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8);String val=URLDecoder.decode(pair.length==2?pair[1]:"",StandardCharsets.UTF_8);if(result.putIfAbsent(key,val)!=null)throw new JsonHttp.HttpProblem(400,"DUPLICATE_FIELD","请求字段重复。");}catch(IllegalArgumentException bad){throw new JsonHttp.HttpProblem(400,"MALFORMED_QUERY","查询参数无效。");}}return result;}
    private static void method(HttpExchange x,String expected)throws JsonHttp.HttpProblem{allow(x,expected);}
    private static void allow(HttpExchange x,String...methods)throws JsonHttp.HttpProblem{if(Arrays.stream(methods).noneMatch(x.getRequestMethod()::equals)){x.getResponseHeaders().set("Allow",String.join(", ",methods));throw new JsonHttp.HttpProblem(405,"METHOD_NOT_ALLOWED","请求方法不受支持。");}}
    private static void applyHeaders(HttpExchange x,boolean noStore){HttpSecurity.responseHeaders(noStore).forEach((k,v)->x.getResponseHeaders().set(k,v));}
    private static void json(HttpExchange x,int status,Object value)throws IOException{x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");send(x,status,JsonHttp.JSON.writeValueAsBytes(value));}
    private static void safeError(HttpExchange x,int status,String code,String message)throws IOException{String correlation=UUID.randomUUID().toString();json(x,status,Map.of("ok",false,"code",code,"message",message,"correlationId",correlation));}
    private static void send(HttpExchange x,int status,byte[] bytes)throws IOException{x.sendResponseHeaders(status,bytes.length);try(var out=x.getResponseBody()){out.write(bytes);}}

    @Override public synchronized void close(){if(!closed.compareAndSet(false,true))return;if(server!=null)server.stop(0);if(executor!=null)executor.shutdownNow();requestBodyReaders.shutdownNow();synchronized(sessionStates){sessionStates.clear();}}
    public interface Backend { Map<String,Object> call(String operation,Map<String,Object> input,SessionState session)throws Exception; Optional<Download> download(String id,SessionState session)throws Exception; }
    public static final class Download implements AutoCloseable {
        private final String filename;private final String contentType;private final Path temporary;private final FileChannel snapshot;private final long length;
        private Download(String filename,String contentType,Path temporary,FileChannel snapshot,long length){this.filename=filename;this.contentType=contentType;this.temporary=temporary;this.snapshot=snapshot;this.length=length;}
        public static Download snapshot(String filename,String contentType,Path source,Path snapshotDirectory,
                                        String expectedSha256,long maximumBytes)throws IOException,DownloadRejected{
            Objects.requireNonNull(filename);Objects.requireNonNull(contentType);Objects.requireNonNull(source);Objects.requireNonNull(snapshotDirectory);
            if(maximumBytes<1||expectedSha256==null||!expectedSha256.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("invalid snapshot limits");
            Path directory=snapshotDirectory.toAbsolutePath().normalize(),parent=directory.getParent();if(parent==null)throw new DownloadRejected("SNAPSHOT_DIRECTORY_UNSAFE");
            Path parentReal=parent.toRealPath();try{Files.createDirectory(directory);}catch(FileAlreadyExistsException exists){/* validate below */}
            if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new DownloadRejected("SNAPSHOT_DIRECTORY_UNSAFE");
            Path directoryReal=directory.toRealPath();if(!Objects.equals(directoryReal.getParent(),parentReal))throw new DownloadRejected("SNAPSHOT_DIRECTORY_UNSAFE");
            Path temporary=Files.createTempFile(directoryReal,"download-",".tmp");FileChannel target=null;
            try(var input=FileChannel.open(source,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))){
                target=FileChannel.open(temporary,Set.of(StandardOpenOption.READ,StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.DELETE_ON_CLOSE,LinkOption.NOFOLLOW_LINKS));
                var digest=MessageDigest.getInstance("SHA-256");var buffer=ByteBuffer.allocate(64*1024);long length=0;
                while(input.read(buffer)>=0){if(buffer.position()==0)continue;buffer.flip();length+=buffer.remaining();
                    if(length>maximumBytes)throw new DownloadRejected("ARTIFACT_TOO_LARGE");digest.update(buffer.asReadOnlyBuffer());
                    while(buffer.hasRemaining())target.write(buffer);buffer.clear();}
                byte[] expected=HexFormat.of().parseHex(expectedSha256);if(!MessageDigest.isEqual(expected,digest.digest()))throw new DownloadRejected("ARTIFACT_SHA_MISMATCH");
                target.position(0);return new Download(filename,contentType,temporary,target,length);
            }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            catch(Exception failure){if(target!=null)try{target.close();}catch(IOException close){failure.addSuppressed(close);}try{Files.deleteIfExists(temporary);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(failure instanceof IOException io)throw io;if(failure instanceof DownloadRejected rejected)throw rejected;if(failure instanceof RuntimeException runtime)throw runtime;throw new IOException("snapshot failed");}
        }
        public String filename(){return filename;}public String contentType(){return contentType;}public long length(){return length;}
        public synchronized void writeTo(OutputStream output)throws IOException{snapshot.position(0);byte[] bytes=new byte[64*1024];var buffer=ByteBuffer.wrap(bytes);long remaining=length;while(remaining>0){buffer.clear();buffer.limit((int)Math.min(bytes.length,remaining));int count=snapshot.read(buffer);if(count<0)throw new EOFException("snapshot truncated");output.write(bytes,0,count);remaining-=count;}}
        @Override public synchronized void close()throws IOException{try{snapshot.close();}finally{Files.deleteIfExists(temporary);}}
    }
    public static final class DownloadRejected extends Exception {private final String code;public DownloadRejected(String code){super(code);this.code=code;}public String code(){return code;}}
    public static final class BackendProblem extends RuntimeException {private final int status;private final String code;private final String safeMessage;private BackendProblem(int status,String code,String safeMessage){super(code);this.status=status;this.code=code;this.safeMessage=safeMessage;}public static BackendProblem notFound(){return new BackendProblem(404,"NOT_FOUND","资源不存在。");}public static BackendProblem conflict(){return new BackendProblem(409,"CONFLICT","操作与当前状态冲突。");}public static BackendProblem credentialRecoveryRequired(){return new BackendProblem(409,"CREDENTIAL_RECOVERY_REQUIRED","凭据已安全移除，请重新输入密码后重试。");}public static BackendProblem credentialStateUncertain(){return new BackendProblem(500,"CREDENTIAL_STATE_UNCERTAIN","凭据状态无法确认，请重启插件并重新保存连接。");}int status(){return status;}String code(){return code;}String safeMessage(){return safeMessage;}}
}
