package io.dm7codex.plugin.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dm7codex.plugin.connection.*;
import io.dm7codex.plugin.execution.*;
import io.dm7codex.plugin.execution.ExecutionModels.*;
import io.dm7codex.plugin.http.ConsoleHttpServer;
import io.dm7codex.plugin.release.ReleaseExportService;
import io.dm7codex.plugin.release.ReleaseLogService;
import io.dm7codex.plugin.runtime.*;
import io.dm7codex.plugin.sql.*;
import io.dm7codex.plugin.state.*;
import java.util.*;

/** Adapts the MCP contract to the existing application services without exposing secrets. */
public final class Dm7ServicesBackend implements Dm7McpServer.ToolBackend, ConsoleHttpServer.Backend, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final StateDatabase database;
    private final SessionInitializer initializer;
    private final ConnectionConfigRepository profiles;
    private final ConnectionTestService connectionTests;
    private final ExecutionService executions;
    private final MetadataService metadata;
    private final ExecutionRepository history;
    private final ReleaseLogService releaseLog;
    private final ReleaseExportService exports;
    private final ExportRepository exportRepository;
    private final ExecutionEventBus eventBus;
    private final ExecutionRegistry registry;
    private final SqlClassificationService classifier;
    private final SqlSummaryRedactor summaries=new SqlSummaryRedactor();
    private final java.nio.file.Path exportsRoot;

    private Dm7ServicesBackend(StateDatabase database, SessionInitializer initializer,
            ConnectionConfigRepository profiles, ConnectionTestService connectionTests,
            ExecutionService executions, MetadataService metadata, ExecutionRepository history,
            ReleaseLogService releaseLog, ReleaseExportService exports, ExportRepository exportRepository,
            ExecutionEventBus eventBus, ExecutionRegistry registry, java.nio.file.Path exportsRoot) {
        this.database = database; this.initializer = initializer; this.profiles = profiles;
        this.connectionTests = connectionTests; this.executions = executions; this.metadata = metadata;
        this.history = history; this.releaseLog = releaseLog; this.exports = exports;
        this.exportRepository=exportRepository;this.eventBus=eventBus;this.registry=registry;this.exportsRoot=exportsRoot.toAbsolutePath().normalize();
        this.classifier=new SqlClassificationService(new DmSqlParser(),new SqlSecurityPolicy(),ConsoleHttpServer.MAX_BODY_BYTES);
    }

    public static Dm7ServicesBackend open(RuntimePaths paths) throws Exception {
        Objects.requireNonNull(paths);
        StateDatabase state = StateDatabase.open(paths.stateDatabase());
        try {
            var sessions = new SessionRepository(state, paths.sessionsDirectory());
            var initializer = new SessionInitializer(paths, sessions);
            var vault = CredentialVault.open(paths.secretsDirectory());
            var profiles = ConnectionConfigRepository.open(paths.configDirectory(), vault);
            var factory = new DmConnectionFactory(profiles, vault, new DmDriverLoader(paths));
            var history = new ExecutionRepository(state);
            var registry = new ExecutionRegistry();
            var releaseLog = new ReleaseLogService(paths, sessions, java.time.Duration.ofSeconds(5));
            var eventBus=new ExecutionEventBus(2_000);
            var executions = new ExecutionService(factory, new DmSqlParser(), new SqlSecurityPolicy(),
                    releaseLog, history, eventBus, registry);
            var exportRepository=new ExportRepository(state);
            return new Dm7ServicesBackend(state, initializer, profiles,
                    new ConnectionTestService(factory, profiles), executions,
                    new MetadataService(factory), history, releaseLog,
                    new ReleaseExportService(paths, sessions, exportRepository),exportRepository,eventBus,registry,paths.exportsDirectory());
        } catch (Exception failure) {
            state.close();
            throw failure;
        }
    }

    public SessionState initialize(SessionIdentity identity) throws Exception {
        return initializer.initialize(identity);
    }

    public ExecutionEventBus eventBus(){return eventBus;}

    @Override public Map<String,Object> call(String operation,Map<String,Object> input,SessionState session)throws Exception{
        return switch(operation){
            case "runtime" -> runtime(session);
            case "connections.list" -> listConnections();
            case "connections.get" -> connection(findProfile(input));
            case "connections.create" -> saveProfile(input,null);
            case "connections.update" -> saveProfile(input,findProfile(input));
            case "connections.delete" -> deleteProfile(input);
            case "connections.default" -> {var profile=findProfile(input);yield connection(profiles.setDefault(profile.id()));}
            case "connections.test" -> {var profile=findProfile(input);yield testConnection(Map.of("connectionId",profile.id().toString()));}
            case "connections.diagnostics" -> diagnostics(required(input,"jdbcUrl"));
            case "sql.classify" -> classification(required(input,"sql"));
            case "query" -> query(input,session,ExecutionSource.CONSOLE);
            case "execute" -> execute(input,session,ExecutionSource.CONSOLE);
            case "metadata" -> describe(input);
            case "executions.get" -> {ensureExecution(input,session);yield getExecution(Map.of("executionId",required(input,"id")),session);}
            case "executions.cancel" -> {ensureExecution(input,session);yield cancel(Map.of("executionId",required(input,"id")),session);}
            case "history" -> history(input,session);
            case "release.preview" -> releasePreview(session);
            case "release.export" -> consoleExport(input,session);
            case "release.recover" -> recoverExport(input,session);
            default -> callMcp(operation,input,session);
        };
    }

    @Override public Optional<ConsoleHttpServer.Download> download(String id,SessionState session)throws Exception{
        if(id==null||!id.matches("[A-Za-z0-9._:-]{1,256}"))throw new IllegalArgumentException("invalid artifact id");
        var artifact=exportRepository.findArtifactById(session.sessionId(),id);
        if(artifact.isEmpty()||artifact.get().artifactPath()==null||!"COMPLETE".equals(artifact.get().state()))return Optional.empty();
        var path=artifact.get().artifactPath().toAbsolutePath().normalize();
        var trustedRoot=exportsRoot.toRealPath();
        if(!path.startsWith(exportsRoot)||!java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        if(java.nio.file.Files.isSymbolicLink(path))return Optional.empty();
        var real=path.toRealPath();if(!real.startsWith(trustedRoot))return Optional.empty();
        return Optional.of(ConsoleHttpServer.Download.snapshot(path.getFileName().toString(),
                "application/sql; charset=utf-8",real,exportsRoot.resolve(".download-snapshots"),
                artifact.get().artifactSha256(),50L*1024*1024));
    }

    private Map<String,Object> runtime(SessionState session)throws Exception{var release=releaseLog.inspect(session);return Map.of("sessionShortId",session.sessionId().substring(0,Math.min(12,session.sessionId().length())),"currentVersion",release.currentVersion(),"runningCount",history.countRunning(session.sessionId()),"connections",profiles.list().size());}
    private ConnectionProfile findProfile(Map<String,Object> input){return profiles.find(UUID.fromString(required(input,"id"))).orElseThrow(ConsoleHttpServer.BackendProblem::notFound);}
    private void ensureExecution(Map<String,Object> input,SessionState session)throws Exception{var found=history.findExecution(UUID.fromString(required(input,"id")).toString());if(found.isEmpty()||!found.get().sessionId().equals(session.sessionId()))throw ConsoleHttpServer.BackendProblem.notFound();}
    private Map<String,Object> consoleExport(Map<String,Object> input,SessionState session)throws Exception{
        if(!Boolean.TRUE.equals(input.get("confirm")))throw new IllegalArgumentException("confirmation required");
        try{var result=export(session);result.remove("path");result.put("downloadUrl","/api/release/artifacts/"+result.get("id")+"/download");return result;}
        catch(io.dm7codex.plugin.release.ReleaseExportLockTimeout|io.dm7codex.plugin.release.ReleaseLogConnectionMismatch conflict){throw ConsoleHttpServer.BackendProblem.conflict();}
    }
    private Map<String,Object> recoverExport(Map<String,Object> input,SessionState session)throws Exception{
        if(!Boolean.TRUE.equals(input.get("confirm")))throw new IllegalArgumentException("confirmation required");
        String text=required(input,"version");if(!text.matches("v[0-9]{3,9}"))throw new IllegalArgumentException("invalid version");
        int version=Integer.parseInt(text.substring(1));
        try{var sessions=new SessionRepository(database,session.activeSql().getParent().getParent());
        var release=sessions.findVersion(session.sessionId(),version);
        var historical=new SessionState(session.sessionId(),session.externalIdHash(),version,release.databaseFingerprint(),release.activeSql(),session.createdAt());
        var result=exportResult(exports.recover(historical));result.remove("path");result.put("downloadUrl","/api/release/artifacts/"+result.get("id")+"/download");return result;}
        catch(io.dm7codex.plugin.release.ReleaseExportLockTimeout conflict){throw ConsoleHttpServer.BackendProblem.conflict();}
        catch(io.dm7codex.plugin.release.ReleaseRecoveryNotAvailable|java.io.IOException|java.sql.SQLException|IllegalStateException|IllegalArgumentException|SecurityException unavailable){throw ConsoleHttpServer.BackendProblem.releaseRecoveryUnavailable();}
    }

    private Map<String,Object> releasePreview(SessionState session)throws Exception{
        var snapshot=releaseLog.inspect(session);int version=Integer.parseInt(snapshot.currentVersion().substring(1));
        var view=history.releaseView(session.sessionId(),version,100);var result=new LinkedHashMap<String,Object>();
        result.put("sessionShortId",session.sessionId().substring(0,Math.min(12,session.sessionId().length())));
        result.put("currentVersion",snapshot.currentVersion());result.put("databaseFingerprint",boundFingerprint(snapshot.databaseFingerprint()));
        result.put("bindingState",bindingState(snapshot.databaseFingerprint()));
        result.put("statementCount",view.recordedCount());result.put("excludedCount",view.excludedCount());result.put("failedCount",view.failedCount());
        result.put("sqlPreview",snapshot.sqlPreview());result.put("previewTruncated",snapshot.previewTruncated());
        result.put("firstSequence",snapshot.firstSequence());result.put("lastSequence",snapshot.lastSequence());
        result.put("runningCount",history.countRunning(session.sessionId()));result.put("entriesTruncated",view.truncated());
        result.put("entries",view.entries().stream().map(this::releaseEntry).toList());
        result.put("artifacts",exportRepository.listBySession(session.sessionId(),50).stream().map(this::artifactView).toList());return result;
    }
    private String bindingState(String fingerprint){if(fingerprint==null||fingerprint.equals("unbound"))return "UNBOUND";
        var profile=profiles.list().stream().filter(ConnectionProfile::isDefault).findFirst();if(profile.isEmpty())return "NO_DEFAULT";
        return DmConnectionFactory.databaseFingerprint(profile.get()).equals(fingerprint)?"MATCH":"MISMATCH";}
    private static String boundFingerprint(String fingerprint){return fingerprint==null||fingerprint.equals("unbound")?null:fingerprint;}
    private Map<String,Object> releaseEntry(ExecutionRepository.ReleaseEntryRecord entry){var item=new LinkedHashMap<String,Object>();
        item.put("sequence",entry.sequence());item.put("index",entry.statementIndex());item.put("kind",entry.kind());item.put("status",entry.status());
        item.put("source",entry.source());item.put("purpose",entry.purpose());item.put("recorded",entry.recorded());
        item.put("exclusionReason",entry.exclusionReason());item.put("createdAt",entry.createdAt().toString());item.put("sqlSummary",summaries.summarize(entry.rawSql()));return item;}
    private Map<String,Object> artifactView(ExportRepository.ExportArtifactRecord artifact){var item=new LinkedHashMap<String,Object>();
        item.put("id",artifact.exportId());item.put("state",artifact.state());item.put("version","v%03d".formatted(artifact.version()));
        item.put("filename",artifact.artifactPath()==null?null:artifact.artifactPath().getFileName().toString());item.put("sha256",artifact.artifactSha256());
        item.put("statementCount",artifact.statementCount());item.put("firstSequence",artifact.firstSequence());item.put("lastSequence",artifact.lastSequence());
        item.put("createdAt",artifact.createdAt().toString());item.put("completedAt",artifact.completedAt()==null?null:artifact.completedAt().toString());
        String integrity="RECOVERABLE";String expected=artifact.artifactSha256();Long bytes=null;boolean downloadable=false;
        if(!"COMPLETE".equals(artifact.state()))try{var sealed=exportRepository.findSealed(artifact.sessionId(),artifact.version());if(sealed.isEmpty())integrity="UNAVAILABLE";else{expected=sealed.get().sealedSourceSha256();var source=sealed.get().sealedSourcePath();if(!java.nio.file.Files.isRegularFile(source,java.nio.file.LinkOption.NOFOLLOW_LINKS))integrity="MISSING";else if(!sha256(source).equals(expected))integrity="TAMPERED";}}catch(Exception ignored){integrity="UNAVAILABLE";}
        if("COMPLETE".equals(artifact.state())&&artifact.artifactPath()!=null)try{
            var path=artifact.artifactPath();if(!java.nio.file.Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))integrity="MISSING";
            else if(!sha256(path).equals(artifact.artifactSha256()))integrity="TAMPERED";else{integrity="VERIFIED";bytes=java.nio.file.Files.size(path);downloadable=true;}}
        catch(Exception ignored){integrity="UNAVAILABLE";}item.put("sha256",expected);item.put("expectedSha256",expected);item.put("byteLength",bytes);item.put("integrityState",integrity);item.put("downloadAvailable",downloadable);
        item.put("downloadUrl",downloadable?"/api/release/artifacts/"+artifact.exportId()+"/download":null);return item;}
    private Map<String,Object> saveProfile(Map<String,Object> input,ConnectionProfile old)throws Exception{
        var allowed=Set.of("id","name","driverJar","driverClass","jdbcUrl","username","password","clearPassword","schema","connectTimeoutSeconds","socketTimeoutSeconds","queryTimeoutSeconds","maxRows","maxBytes","isDefault");
        if(!allowed.containsAll(input.keySet()))throw new IllegalArgumentException("unknown field");
        boolean clearPassword=Boolean.TRUE.equals(input.get("clearPassword"));
        if(old==null&&clearPassword)throw new IllegalArgumentException("clearPassword is not valid when creating a connection");
        UUID id=old==null?UUID.randomUUID():old.id();String requestedName=text(input,"name",old==null?null:old.name());
        if(profiles.list().stream().anyMatch(existing->!existing.id().equals(id)&&existing.name().equalsIgnoreCase(requestedName)))throw ConsoleHttpServer.BackendProblem.conflict();
        java.nio.file.Path driver=java.nio.file.Path.of(text(input,"driverJar",old==null?null:old.driverJar().toString())).toAbsolutePath().normalize();
        if(!driver.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")||!java.nio.file.Files.isRegularFile(driver,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.isSymbolicLink(driver))throw new IllegalArgumentException("driverJar invalid");
        String sha=sha256(driver);String password=nullableText(input,"password");
        if(password!=null&&password.isBlank())password=null;
        if(clearPassword&&password!=null)throw new IllegalArgumentException("Password replacement and clearing are mutually exclusive");
        char[] secret=password==null?null:password.toCharArray();
        try{
            var value=new ConnectionProfile(id,requestedName,driver,sha,
                    text(input,"driverClass",old==null?ConnectionProfile.DEFAULT_DRIVER_CLASS:old.driverClass()),
                    text(input,"jdbcUrl",old==null?null:old.jdbcUrl()),text(input,"username",old==null?null:old.username()),
                    input.containsKey("schema")?nullableText(input,"schema"):old==null?null:old.schema(),number(input,"connectTimeoutSeconds",old==null?10:old.connectTimeoutSeconds()).intValue(),
                    number(input,"socketTimeoutSeconds",old==null?30:old.socketTimeoutSeconds()).intValue(),number(input,"queryTimeoutSeconds",old==null?60:old.queryTimeoutSeconds()).intValue(),
                    number(input,"maxRows",old==null?1000:old.maxRows()).intValue(),number(input,"maxBytes",old==null?10L*1024*1024:old.maxBytes()).longValue(),
                    input.containsKey("isDefault")?(Boolean)input.get("isDefault"):old!=null&&old.isDefault());
            try{return connection(profiles.save(value,Optional.ofNullable(secret),clearPassword));}
            catch(CredentialStateException consistency){throw credentialProblem(consistency);}
            catch(IllegalArgumentException invalid){if(profiles.list().stream().anyMatch(existing->!existing.id().equals(id)&&existing.name().equalsIgnoreCase(requestedName)))throw ConsoleHttpServer.BackendProblem.conflict();throw invalid;}
        }finally{if(secret!=null)Arrays.fill(secret,'\0');}
    }
    private Map<String,Object> deleteProfile(Map<String,Object> input){
        var allowed=Set.of("id","replacementDefaultId","leaveWithoutDefault");
        if(!allowed.containsAll(input.keySet()))throw new IllegalArgumentException("unknown field");
        var profile=findProfile(input);String replacement=nullableText(input,"replacementDefaultId");
        boolean leave=Boolean.TRUE.equals(input.get("leaveWithoutDefault"));
        try{profiles.delete(profile.id(),replacement==null?Optional.empty():Optional.of(UUID.fromString(replacement)),leave);}
        catch(CredentialStateException consistency){throw credentialProblem(consistency);}
        String active=profiles.list().stream().filter(ConnectionProfile::isDefault).map(value->value.id().toString()).findFirst().orElse(null);
        var result=new LinkedHashMap<String,Object>();result.put("deleted",true);result.put("defaultConnectionId",active);return result;
    }
    static ConsoleHttpServer.BackendProblem credentialProblem(CredentialStateException failure){
        return failure.state()==CredentialStateException.State.RECOVERY_REQUIRED
                ?ConsoleHttpServer.BackendProblem.credentialRecoveryRequired()
                :ConsoleHttpServer.BackendProblem.credentialStateUncertain();
    }
    private Map<String,Object> connection(ConnectionProfile p){var m=new LinkedHashMap<String,Object>();m.put("id",p.id().toString());m.put("name",p.name());m.put("driverFileName",p.driverJar().getFileName().toString());m.put("driverSha256",p.driverSha256());m.put("configured",true);m.put("connected",false);m.put("hasPassword",profiles.hasPassword(p.id()));m.put("driverClass",p.driverClass());m.put("jdbcUrl",JdbcUrlDiagnostics.redact(p.jdbcUrl()));m.put("urlSummary",JdbcUrlDiagnostics.redact(p.jdbcUrl()));m.put("username",p.username());m.put("schema",p.schema());m.put("connectTimeoutSeconds",p.connectTimeoutSeconds());m.put("socketTimeoutSeconds",p.socketTimeoutSeconds());m.put("queryTimeoutSeconds",p.queryTimeoutSeconds());m.put("maxRows",p.maxRows());m.put("maxBytes",p.maxBytes());m.put("isDefault",p.isDefault());return m;}
    private static Map<String,Object> diagnostics(String url){var inspected=JdbcUrlDiagnostics.inspect(url);return Map.of("urlSummary",JdbcUrlDiagnostics.redact(url),"warnings",inspected.warnings());}
    private Map<String,Object> classification(String sql){
        var value=classifier.classify(sql);var result=new LinkedHashMap<String,Object>();
        result.put("statementCount",value.statementCount());result.put("kinds",value.kinds().stream().map(Enum::name).toList());
        result.put("queryOnly",value.queryOnly());result.put("requiresPurpose",value.requiresPurpose());result.put("atomicAllowed",value.atomicAllowed());return result;
    }
    private static String sha256(java.nio.file.Path path)throws Exception{var digest=java.security.MessageDigest.getInstance("SHA-256");try(var in=java.nio.file.Files.newInputStream(path)){byte[] b=new byte[8192];for(int n;(n=in.read(b))>=0;)digest.update(b,0,n);}return java.util.HexFormat.of().formatHex(digest.digest());}
    private static String text(Map<String,Object> m,String k,String fallback){Object v=m.get(k);if(v==null){if(fallback==null)throw new IllegalArgumentException(k+" required");return fallback;}if(!(v instanceof String s)||s.isBlank())throw new IllegalArgumentException(k+" invalid");return s;}
    private static String nullableText(Map<String,Object>m,String k){Object v=m.get(k);if(v==null)return null;if(!(v instanceof String s))throw new IllegalArgumentException(k+" invalid");return s;}
    private static Number number(Map<String,Object>m,String k,Number fallback){Object v=m.get(k);if(v==null)return fallback;if(!(v instanceof Number n))throw new IllegalArgumentException(k+" invalid");return n;}
    private Map<String,Object> history(Map<String,Object> input,SessionState session)throws Exception{
        int offset=integer(input,"offset",0),limit=integer(input,"limit",50);
        var filter=new ExecutionFilter(session.sessionId(),enumValue(input,"status",ExecutionStatus.class),
                enumValue(input,"source",ExecutionSource.class),enumValue(input,"purpose",SqlPurpose.class),
                instantValue(input,"startedAfter"),instantValue(input,"startedBefore"),booleanValue(input,"recorded"),
                uuidValue(input,"correlationId"),booleanValue(input,"success"),enumValue(input,"kind",SqlKind.class));
        var page=history.search(filter,offset,limit);var items=page.items().stream().map(this::historyItem).toList();
        return Map.of("items",items,"offset",page.offset(),"limit",page.limit(),"hasMore",page.hasMore());
    }
    private Map<String,Object> historyItem(ExecutionSummary s){var m=new LinkedHashMap<String,Object>();m.put("executionId",s.executionId().toString());m.put("correlationId",s.correlationId().toString());m.put("connectionFingerprint",s.connectionFingerprint());m.put("source",s.source().name());m.put("purpose",s.purpose().map(Enum::name).orElse(null));m.put("status",s.status().name());m.put("startedAt",s.startedAt().toString());m.put("completedAt",s.completedAt()==null?null:s.completedAt().toString());m.put("affectedRows",s.affectedRows());m.put("returnedRows",s.returnedRows());m.put("recorded",s.recorded());m.put("exclusionReason",s.exclusionReason());try{m.put("sqlSummary",summaries.summarize(history.findExecution(s.executionId().toString()).map(ExecutionRepository.ExecutionRecord::sqlText).orElse("")));}catch(Exception unavailable){m.put("sqlSummary","");}return m;}
    private static <E extends Enum<E>> E enumValue(Map<String,Object>m,String key,Class<E>type){String value=nullableText(m,key);return value==null?null:Enum.valueOf(type,value.toUpperCase(Locale.ROOT));}
    private static java.time.Instant instantValue(Map<String,Object>m,String key){String value=nullableText(m,key);return value==null?null:java.time.Instant.parse(value);}
    private static UUID uuidValue(Map<String,Object>m,String key){String value=nullableText(m,key);return value==null?null:UUID.fromString(value);}
    private static Boolean booleanValue(Map<String,Object>m,String key){Object value=m.get(key);if(value==null)return null;if(value instanceof Boolean b)return b;if(value instanceof String s&&(s.equalsIgnoreCase("true")||s.equalsIgnoreCase("false")))return Boolean.parseBoolean(s);throw new IllegalArgumentException(key+" invalid");}

    private Map<String, Object> callMcp(String name, Map<String, Object> arguments, SessionState session) throws Exception {
        return switch (name) {
            case "dm7_list_connections" -> listConnections();
            case "dm7_test_connection" -> testConnection(arguments);
            case "dm7_query" -> query(arguments, session);
            case "dm7_execute" -> execute(arguments, session);
            case "dm7_describe_schema" -> describe(arguments);
            case "dm7_get_execution" -> getExecution(arguments, session);
            case "dm7_cancel_execution" -> cancel(arguments, session);
            case "dm7_get_release_log" -> convert(releaseLog.inspect(session));
            case "dm7_release_export" -> export(session);
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
    }

    private Map<String, Object> listConnections() {
        var values = profiles.list().stream().map(this::connection).map(Collections::unmodifiableMap).toList();
        return Map.of("connections", values);
    }

    private Map<String, Object> testConnection(Map<String, Object> arguments) {
        var result = connectionTests.test(connectionId(arguments));
        return convert(result);
    }

    private Map<String, Object> query(Map<String, Object> arguments, SessionState session) {
        return query(arguments,session,ExecutionSource.MCP);
    }
    private Map<String, Object> query(Map<String, Object> arguments, SessionState session, ExecutionSource source) {
        var typedParameters = parameters(arguments);
        var result = executions.query(session, new QueryCommand(connectionId(arguments), executionId(arguments),
                required(arguments, "sql"), typedParameters, integer(arguments, "maxRows", 1_000),
                longValue(arguments, "maxBytes", 10_485_760), integer(arguments, "timeoutSeconds", 60),
                source));
        return queryResult(result);
    }

    private Map<String, Object> execute(Map<String, Object> arguments, SessionState session) {
        return execute(arguments,session,ExecutionSource.MCP);
    }
    private Map<String, Object> execute(Map<String, Object> arguments, SessionState session,ExecutionSource source) {
        var typedParameters = parameters(arguments);
        SqlPurpose purpose = SqlPurpose.valueOf(required(arguments, "purpose").toUpperCase(Locale.ROOT));
        var result = executions.execute(session, new ExecuteCommand(connectionId(arguments), executionId(arguments),
                required(arguments, "sql"), typedParameters, purpose, bool(arguments, "atomic", true),
                bool(arguments, "continueOnError", false), integer(arguments, "timeoutSeconds", 60), source));
        return executionResult(result);
    }

    private Map<String, Object> describe(Map<String, Object> arguments) {
        var page = metadata.describe(connectionId(arguments), new MetadataService.MetadataRequest(
                optional(arguments, "schemaPattern"), optional(arguments, "objectPattern"),
                longValue(arguments, "offset", 0), integer(arguments, "limit", 50)));
        return convert(page);
    }

    private Map<String, Object> getExecution(Map<String, Object> arguments, SessionState session) throws Exception {
        String id = UUID.fromString(required(arguments, "executionId")).toString();
        var record = history.findExecution(id).orElseThrow(() -> new IllegalArgumentException("Execution was not found"));
        if (!record.sessionId().equals(session.sessionId())) throw new IllegalArgumentException("Execution was not found");
        var summary = new LinkedHashMap<String, Object>();
        summary.put("executionId", record.executionId()); summary.put("correlationId", record.correlationId());
        summary.put("connectionFingerprint", record.connectionFingerprint()); summary.put("source", record.source());
        summary.put("purpose", record.purpose()); summary.put("phase", record.phase()); summary.put("status", record.status());
        summary.put("startedAt", instant(record.startedAt())); summary.put("completedAt", instant(record.completedAt()));
        summary.put("affectedRowCount", record.affectedRowCount()); summary.put("returnedRowCount", record.returnedRowCount());
        summary.put("recorded", record.recorded()); summary.put("exclusionReason", record.exclusionReason());
        summary.put("sqlSummary", summaries.summarize(record.sqlText()));
        var facts=history.findExecutionFacts(id).orElseThrow();var overallError=new LinkedHashMap<String,Object>();
        overallError.put("correlationId",facts.correlationId());overallError.put("phase",facts.phase());overallError.put("message",facts.errorMessage());overallError.put("sqlState",facts.sqlState());overallError.put("errorCode",facts.errorCode());overallError.put("restartRequired",facts.restartRequired());summary.put("error",facts.errorMessage()==null?null:overallError);
        var statements = history.findStatementDetails(id).stream().map(statement -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("index", statement.index()); item.put("kind", statement.kind());
            item.put("status", statement.status()); item.put("phase", statement.phase());
            item.put("rowCount", statement.rowCount()); item.put("recorded", statement.recorded());
            item.put("success",statement.success());item.put("committed",statement.committed());item.put("commitBehavior",statement.commitBehavior());item.put("elapsedMillis",statement.elapsedMillis());
            item.put("exclusionReason", statement.exclusionReason());var error=new LinkedHashMap<String,Object>();error.put("phase",statement.phase());error.put("message",statement.errorMessage());error.put("sqlState",statement.sqlState());error.put("errorCode",statement.errorCode());error.put("restartRequired",statement.restartRequired());item.put("error",statement.errorMessage()==null?null:error);
            item.put("sqlSummary", summaries.summarize(statement.rawSql()));
            return Collections.unmodifiableMap(item);
        }).toList();
        return Map.of("summary", summary, "statements", statements,
                "events", executionEvents(executions.events(session.sessionId(), 0), UUID.fromString(id)));
    }

    private Map<String, Object> cancel(Map<String, Object> arguments, SessionState session) throws Exception {
        UUID id = UUID.fromString(required(arguments, "executionId"));
        var record = history.findExecution(id.toString()).orElseThrow(() -> new IllegalArgumentException("Execution was not found"));
        if (!record.sessionId().equals(session.sessionId())) throw new IllegalArgumentException("Execution was not found");
        return Map.of("executionId", id.toString(), "cancelRequested", executions.cancel(id));
    }

    private Map<String, Object> export(SessionState session) throws Exception {
        return exportResult(exports.export(session));
    }

    private Map<String,Object> exportResult(ReleaseExportService.ExportArtifact artifact) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", artifact.id()); result.put("version", artifact.version());
        result.put("newActiveVersion", artifact.newActiveVersion());
        result.put("path", artifact.path().toAbsolutePath().normalize().toString());
        result.put("filename", artifact.filename()); result.put("byteLength", artifact.byteLength());
        result.put("sha256", artifact.sha256()); result.put("sealedSourceSha256", artifact.sealedSourceSha256());
        result.put("statementCount", artifact.statementCount()); result.put("firstSequence", artifact.firstSequence());
        result.put("lastSequence", artifact.lastSequence()); result.put("createdAt", artifact.createdAt().toString());
        return result;
    }

    private static Map<String, Object> queryResult(QueryResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", value.executionId().toString()); result.put("success", value.success());
        result.put("columns", value.columns().stream().map(Dm7ServicesBackend::convert).toList());
        result.put("rows", value.rows()); result.put("truncated", value.truncated());
        result.put("returnedRows", value.returnedRows()); result.put("bytes", value.bytes());
        result.put("elapsedMillis", value.elapsedMillis()); result.put("databaseFingerprint", value.databaseFingerprint());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> executionResult(ExecutionResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("executionId", value.executionId().toString()); result.put("success", value.success());
        result.put("status", value.status().name());
        result.put("statements", value.statements().stream().map(Dm7ServicesBackend::statementResult).toList());
        result.put("elapsedMillis", value.elapsedMillis()); result.put("databaseFingerprint", value.databaseFingerprint());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> statementResult(StatementResult value) {
        var result = new LinkedHashMap<String, Object>();
        result.put("index", value.index()); result.put("kind", value.kind().name());
        result.put("success", value.success()); result.put("committed", value.committed());
        result.put("rowCount", value.rowCount()); result.put("recorded", value.recorded());
        result.put("exclusionReason", value.exclusionReason()); result.put("commitBehavior", value.commitBehavior());
        result.put("elapsedMillis", value.elapsedMillis());
        result.put("error", value.error().map(Dm7ServicesBackend::safeError).orElse(null));
        return result;
    }

    private static Map<String, Object> safeError(SafeError value) {
        var error = new LinkedHashMap<String, Object>();
        error.put("correlationId", value.correlationId().toString()); error.put("phase", value.phase().name());
        error.put("message", value.message()); error.put("sqlState", value.sqlState());
        error.put("errorCode", value.errorCode()); error.put("restartRequired", value.restartRequired());
        return error;
    }

    static List<Map<String, Object>> executionEvents(List<ExecutionEvent> events, UUID executionId) {
        return events.stream().filter(event -> event.executionId().equals(executionId)).map(event -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("sequence", event.sequence()); item.put("status", event.status().name());
            item.put("timestamp", event.timestamp().toString()); item.put("detail", event.detail());
            return Collections.unmodifiableMap(item);
        }).toList();
    }

    private UUID connectionId(Map<String, Object> arguments) {
        String supplied = optional(arguments, "connectionId");
        if (supplied != null) {
            UUID id = UUID.fromString(supplied);
            if (profiles.find(id).isEmpty()) throw new IllegalArgumentException("Connection was not found");
            return id;
        }
        return profiles.list().stream().filter(ConnectionProfile::isDefault).map(ConnectionProfile::id)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("No default connection is configured"));
    }

    private static UUID executionId(Map<String, Object> arguments) {
        String value = optional(arguments, "executionId");
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    static List<SqlParameter> parameters(Map<String, ?> arguments) {
        Object raw = arguments.get("parameters");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> values)) throw new IllegalArgumentException("parameters must be an array");
        var result = new ArrayList<SqlParameter>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> item) || !(item.get("jdbcType") instanceof Number type)) {
                throw new IllegalArgumentException("parameter is invalid");
            }
            int jdbcType;
            try { jdbcType = number(type).intValueExact(); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("JDBC type is invalid"); }
            if (!item.containsKey("value")) throw new IllegalArgumentException("parameter value is required");
            Object parsed;
            try {
                parsed = parameterValue(item.get("value"), jdbcType);
                new DmLiteralRenderer().render(parsed, jdbcType);
            } catch (UnsafeNumericInputException unsafeNumeric) {
                throw unsafeNumeric;
            } catch (RuntimeException unsafe) {
                if (item.get("value") instanceof Number) throw new UnsafeNumericInputException();
                throw new IllegalArgumentException("JDBC parameter cannot be represented safely");
            }
            result.add(new SqlParameter(parsed, jdbcType));
        }
        return List.copyOf(result);
    }

    private static Object parameterValue(Object value, int type) {
        if (value == null) return null;
        return switch (type) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR,
                    java.sql.Types.CLOB, java.sql.Types.NCHAR, java.sql.Types.NVARCHAR,
                    java.sql.Types.LONGNVARCHAR, java.sql.Types.NCLOB -> requireValue(value, String.class);
            case java.sql.Types.TINYINT -> exactTinyInt(value);
            case java.sql.Types.SMALLINT -> exactSmallInt(value);
            case java.sql.Types.INTEGER -> exactInteger(value);
            case java.sql.Types.BIGINT -> exactBigInt(value);
            case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> number(value);
            case java.sql.Types.REAL -> finiteFloat(value);
            case java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> finiteDouble(value);
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> requireValue(value, Boolean.class);
            case java.sql.Types.DATE -> java.time.LocalDate.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIME -> java.time.LocalTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIME_WITH_TIMEZONE -> java.time.OffsetTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIMESTAMP -> java.time.LocalDateTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> java.time.OffsetDateTime.parse((String) requireValue(value, String.class));
            case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY,
                    java.sql.Types.BLOB -> Base64.getDecoder().decode((String) requireValue(value, String.class));
            default -> throw new IllegalArgumentException("JDBC parameter type is not supported");
        };
    }

    private static java.math.BigDecimal number(Object value) {
        if (!(value instanceof Number numeric)) {
            throw new IllegalArgumentException("JDBC numeric parameter must be a JSON number");
        }
        if ((numeric instanceof Double doubleValue && !Double.isFinite(doubleValue))
                || (numeric instanceof Float floatValue && !Float.isFinite(floatValue))) {
            throw new UnsafeNumericInputException();
        }
        try { return new java.math.BigDecimal(numeric.toString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("JDBC numeric parameter is invalid"); }
    }

    private static byte exactTinyInt(Object value) {
        try { return number(value).byteValueExact(); }
        catch (ArithmeticException invalid) { throw new UnsafeNumericInputException(); }
    }
    private static short exactSmallInt(Object value) {
        try { return number(value).shortValueExact(); }
        catch (ArithmeticException invalid) { throw new UnsafeNumericInputException(); }
    }
    private static int exactInteger(Object value) {
        try { return number(value).intValueExact(); }
        catch (ArithmeticException invalid) { throw new UnsafeNumericInputException(); }
    }
    private static long exactBigInt(Object value) {
        try { return number(value).longValueExact(); }
        catch (ArithmeticException invalid) { throw new UnsafeNumericInputException(); }
    }

    private static float finiteFloat(Object value) {
        var decimal = number(value);
        float result = decimal.floatValue();
        if (!Float.isFinite(result) || (result == 0f && decimal.signum() != 0)) {
            throw new UnsafeNumericInputException();
        }
        return result;
    }

    private static double finiteDouble(Object value) {
        var decimal = number(value);
        double result = decimal.doubleValue();
        if (!Double.isFinite(result) || (result == 0d && decimal.signum() != 0)) {
            throw new UnsafeNumericInputException();
        }
        return result;
    }

    private static Object requireValue(Object value, Class<?> type) {
        if (!type.isInstance(value)) throw new IllegalArgumentException("JDBC parameter value has the wrong type");
        return value;
    }

    private static String required(Map<String, Object> values, String key) {
        String result = optional(values, key);
        if (result == null) throw new IllegalArgumentException(key + " is required");
        return result;
    }
    private static String optional(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(key + " is invalid");
        return text;
    }
    private static int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key); if(value==null)return fallback;
        try{return value instanceof Number n?new java.math.BigDecimal(n.toString()).intValueExact():Integer.parseInt((String)value);}
        catch(RuntimeException invalid){throw new IllegalArgumentException(key+" is invalid");}
    }
    private static long longValue(Map<String, Object> values, String key, long fallback) {
        Object value = values.get(key); if(value==null)return fallback;
        try{return value instanceof Number n?new java.math.BigDecimal(n.toString()).longValueExact():Long.parseLong((String)value);}
        catch(RuntimeException invalid){throw new IllegalArgumentException(key+" is invalid");}
    }
    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key); return value == null ? fallback : (Boolean) value;
    }
    private static String instant(java.time.Instant value) { return value == null ? null : value.toString(); }
    private static Map<String, Object> convert(Object value) { return JSON.convertValue(value, MAP); }

    @Override public void close() {
        try { executions.close(); } finally { database.close(); }
    }
}
