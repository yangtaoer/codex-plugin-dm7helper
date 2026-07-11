package io.dm7codex.plugin.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dm7codex.plugin.connection.ConnectionProfile;
import io.dm7codex.plugin.connection.DmDriverLoader;
import io.dm7codex.plugin.mcp.Dm7ServicesBackend;
import io.dm7codex.plugin.runtime.RuntimePaths;
import io.dm7codex.plugin.runtime.SessionIdentity;
import io.dm7codex.plugin.runtime.SessionState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class Dm7IntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<String> ENV = List.of(
            "DM7_IT_JDBC_URL", "DM7_IT_USERNAME", "DM7_IT_PASSWORD", "DM7_IT_DRIVER_JAR");
    @TempDir Path temporary;

    @Test void productionPathAcceptanceAndSanitizedEvidence() throws Throwable {
        var config = Config.fromEnvironment(System.getenv());
        String suffix = randomSuffix(), table = "CODEX_DM7_IT_" + suffix,
                mixedTable = "CODEX_DM7_IT_MIX_" + suffix, atomicTable = "CODEX_DM7_IT_ATOMIC_" + suffix,
                missingTable = "CODEX_DM7_IT_MISSING_" + suffix;
        Path runtime = temporary.resolve("runtime");
        var cases = new ArrayList<Map<String,Object>>();
        String driverVersion = null, serverVersion = null, fingerprint = null, schema = null, profileId = null;
        boolean mainSucceeded = false;
        boolean[] exactCleanupConfirmed = {false};
        try (var backend = Dm7ServicesBackend.open(RuntimePaths.forTest(runtime))) {
            SessionState session = backend.initialize(new SessionIdentity("integration-" + suffix, "integration", "verified"));
            Throwable primaryFailure = null;
            try {
                timed(cases, "url-diagnostics-and-connection", () -> {
                    @SuppressWarnings("unchecked") var warnings = (List<String>) backend.call(
                            "connections.diagnostics", Map.of("jdbcUrl", config.url()), session).get("warnings");
                    assertTrue(warnings.stream().anyMatch(value -> value.contains("legacy DM7 path segment")),
                            "expected safe legacy-path diagnostic");
                });
                Map<String,Object> created = createProfile(backend, session, config);
                profileId = (String) created.get("id");
                assertEquals(config.driverSha256Lower(), created.get("driverSha256"));
                assertEquals(2, ((Number)created.get("connectTimeoutSeconds")).intValue());
                assertEquals(3, ((Number)created.get("socketTimeoutSeconds")).intValue());
                assertEquals(4, ((Number)created.get("queryTimeoutSeconds")).intValue());
                final String activeProfileId = profileId;
                Map<String,Object> tested = timedValue(cases, "driver-isolation-and-versions", () ->
                        backend.call("dm7_test_connection", Map.of("connectionId", activeProfileId), session));
                assertEquals(true, tested.get("success"), "production connection test failed");
                assertEquals(true, tested.get("chineseRoundTrip"), "connection Chinese probe failed");
                driverVersion = safeVersion(tested.get("driverVersion"), config);
                serverVersion = safeVersion(tested.get("serverVersion"), config);
                schema = requiredIdentifier(tested.get("actualSchema"));
                final String activeSchema = schema;
                final String qualified = qualified(schema, table), mixedQualified = qualified(schema, mixedTable),
                        atomicQualified = qualified(schema, atomicTable), missingQualified = qualified(schema, missingTable);

                timed(cases, "chinese-round-trip-and-metadata", () -> {
                    assertExecution(exec(backend, session, activeProfileId,
                            "CREATE TABLE " + qualified + " (\"ID\" INT, \"中文列名\" VARCHAR(200))", "TEST", false, false));
                    assertExecution(exec(backend, session, activeProfileId,
                            "INSERT INTO " + qualified + " (\"ID\", \"中文列名\") VALUES (?, ?)", "TEST", true, false,
                            parameter(Types.INTEGER, 1), parameter(Types.VARCHAR, "中文验证：达梦数据库")));
                    Map<String,Object> query = query(backend, session, activeProfileId,
                            "SELECT \"中文列名\" FROM " + qualified + " WHERE \"ID\" = 1", 10, 1_048_576, 4);
                    assertEquals(true, query.get("success"));
                    @SuppressWarnings("unchecked") var columns = (List<Map<String,Object>>) query.get("columns");
                    @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) query.get("rows");
                    assertEquals(1, rows.size()); assertEquals("中文列名", columns.get(0).get("outputLabel"));
                    assertEquals("中文验证：达梦数据库", rows.get(0).get("中文列名"));
                    @SuppressWarnings("unchecked") var items = (List<Map<String,Object>>) backend.call("dm7_describe_schema",
                            Map.of("connectionId",activeProfileId,"schemaPattern",activeSchema,"objectPattern",table,"limit",10),session).get("items");
                    Map<String,Object> tableMetadata=items.stream().filter(item -> table.equals(item.get("name"))).findFirst().orElseThrow();
                    @SuppressWarnings("unchecked") var metadataColumns=(List<Map<String,Object>>)tableMetadata.get("columns");
                    assertTrue(metadataColumns.stream().anyMatch(column->"中文列名".equals(column.get("name"))));
                    assertReleaseZero(backend, session);
                });

                timed(cases, "parameterized-update-delete", () -> {
                    assertExecution(exec(backend, session, activeProfileId,
                            "INSERT INTO " + qualified + " (\"ID\", \"中文列名\") VALUES (?, ?)", "TEST", true, false,
                            parameter(Types.INTEGER,2),parameter(Types.VARCHAR,"待删除")));
                    Map<String,Object> update = exec(backend, session, activeProfileId,
                            "UPDATE " + qualified + " SET \"中文列名\" = ? WHERE \"ID\" = ?", "TEST", true, false,
                            parameter(Types.VARCHAR,"更新完成"),parameter(Types.INTEGER,1));
                    assertStatementRowCount(update, 1);
                    Map<String,Object> updated=query(backend,session,activeProfileId,"SELECT \"中文列名\" FROM "+qualified+" WHERE \"ID\" = 1",1,1024,4);
                    @SuppressWarnings("unchecked") var updatedRows=(List<Map<String,Object>>)updated.get("rows");
                    assertEquals("更新完成",updatedRows.get(0).get("中文列名"));
                    Map<String,Object> delete = exec(backend, session, activeProfileId,
                            "DELETE FROM " + qualified + " WHERE \"ID\" = ?", "TEST", true, false,
                            parameter(Types.INTEGER,2));
                    assertStatementRowCount(delete, 1); assertReleaseZero(backend, session);
                    Map<String,Object> deleted=query(backend,session,activeProfileId,"SELECT COUNT(*) AS \"C\" FROM "+qualified+" WHERE \"ID\" = 2",1,1024,4);
                    @SuppressWarnings("unchecked") var deletedRows=(List<Map<String,Object>>)deleted.get("rows");
                    assertEquals(0L,((Number)deletedRows.get(0).get("C")).longValue());
                });

                timed(cases, "limits-and-timeouts", () -> {
                    assertExecution(exec(backend, session, activeProfileId,
                            "INSERT INTO " + qualified + " (\"ID\", \"中文列名\") VALUES (3, '限制一');" +
                            "INSERT INTO " + qualified + " (\"ID\", \"中文列名\") VALUES (4, '限制二')",
                            "TEST", true, false));
                    Map<String,Object> rowLimited = query(backend, session, activeProfileId,
                            "SELECT \"ID\", \"中文列名\" FROM " + qualified + " ORDER BY \"ID\"",1,1_048_576,1);
                    assertEquals(1, ((Number)rowLimited.get("returnedRows")).intValue());
                    assertEquals(true,rowLimited.get("truncated"));
                    Map<String,Object> byteLimited = query(backend, session, activeProfileId,
                            "SELECT \"中文列名\" FROM " + qualified + " WHERE \"ID\" = 1",10,16,1);
                    assertEquals(true,byteLimited.get("success"));
                    assertEquals(true,byteLimited.get("truncated"));
                    assertTrue(((Number)byteLimited.get("bytes")).longValue() <= 16);
                });

                timed(cases, "atomic-ddl-preflight", () -> {
                    assertThrows(RuntimeException.class, () -> exec(backend, session, activeProfileId,
                            "CREATE TABLE " + atomicQualified + " (\"ID\" INT)","TEST",true,false));
                    @SuppressWarnings("unchecked") var absent=(List<Map<String,Object>>)backend.call("dm7_describe_schema",
                            Map.of("connectionId",activeProfileId,"schemaPattern",activeSchema,"objectPattern",atomicTable,"limit",10),session).get("items");
                    assertTrue(absent.isEmpty(),"atomic DDL preflight executed before rejection");
                    assertReleaseZero(backend,session);
                });

                timed(cases, "non-atomic-mixed-failure", () -> {
                    assertExecution(exec(backend,session,activeProfileId,"CREATE TABLE " + mixedQualified + " (\"ID\" INT)","TEST",false,false));
                    Map<String,Object> result=exec(backend,session,activeProfileId,
                            "INSERT INTO " + mixedQualified + " VALUES (1); INSERT INTO " + missingQualified + " VALUES (1)",
                            "TEST",false,true);
                    @SuppressWarnings("unchecked") var statements=(List<Map<String,Object>>)result.get("statements");
                    assertEquals(2,statements.size());assertEquals(true,statements.get(0).get("success"));assertEquals(false,statements.get(1).get("success"));
                    Map<String,Object> query=query(backend,session,activeProfileId,"SELECT COUNT(*) AS \"C\" FROM "+mixedQualified,1,1024,4);
                    @SuppressWarnings("unchecked") var rows=(List<Map<String,Object>>)query.get("rows");
                    assertEquals(1L,((Number)rows.get(0).get("C")).longValue());assertReleaseZero(backend,session);
                });

                long cancellationStarted=System.nanoTime();
                Capability capability=rawCapabilityProbe(config,runtime,schema);
                assertTrue(capability.queryTimeoutEnforced(),"bounded query timeout was not enforced");
                cases.add(caseResult("cancellation-capability",elapsed(cancellationStarted),capability.cancellationSupported()));

                timed(cases,"test-purpose-release-exclusion",()->{
                    for(String purpose:List.of("TEST","MOCK","SEED","SAMPLE")){
                        Map<String,Object> result=exec(backend,session,activeProfileId,
                                "UPDATE "+qualified+" SET \"ID\" = \"ID\" WHERE \"ID\" = -999",purpose,true,false);
                        @SuppressWarnings("unchecked") var statements=(List<Map<String,Object>>)result.get("statements");
                        assertEquals(false,statements.get(0).get("recorded"));assertReleaseZero(backend,session);
                    }
                });
                Map<String,Object> finalQuery=query(backend,session,activeProfileId,"SELECT 1 AS \"OK\"",1,1024,4);
                fingerprint=(String)finalQuery.get("databaseFingerprint");
                assertTrue(fingerprint.matches("[a-f0-9]{64}"));
                mainSucceeded=true;
            } catch (Throwable failure) {
                primaryFailure = failure;
                throw failure;
            } finally {
                long cleanupStarted=System.nanoTime();
                Throwable cleanupFailure=null;
                try{exactCleanupConfirmed[0]=rawCleanupAndVerify(config,runtime,schema,List.of(table,mixedTable,atomicTable));}
                catch(Throwable failure){cleanupFailure=merge(cleanupFailure,failure);}
                try{if(profileId!=null)backend.call("connections.delete",Map.of("id",profileId),session);}
                catch(Throwable failure){cleanupFailure=merge(cleanupFailure,failure);}
                try{assertReleaseZero(backend,session);}
                catch(Throwable failure){cleanupFailure=merge(cleanupFailure,failure);}
                try{deleteTree(runtime);assertFalse(Files.exists(runtime),"temporary integration runtime was not removed");}
                catch(Throwable failure){cleanupFailure=merge(cleanupFailure,failure);}
                if(cleanupFailure==null)cases.add(caseResult("cleanup-and-zero-release",elapsed(cleanupStarted),null));
                else if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);
                else throw cleanupFailure;
            }
        }
        assertTrue(mainSucceeded,"integration cases did not complete");
        writeCleanupManifest(List.of(table,mixedTable,atomicTable));
        writeCandidate(config,driverVersion,serverVersion,fingerprint,cases,exactCleanupConfirmed[0]);
    }

    private static Map<String,Object> createProfile(Dm7ServicesBackend backend,SessionState session,Config c)throws Exception{
        var input=new LinkedHashMap<String,Object>();input.put("name","DM7 integration isolated");input.put("driverJar",c.driverJar().toString());
        input.put("driverClass",ConnectionProfile.DEFAULT_DRIVER_CLASS);input.put("jdbcUrl",c.url());input.put("username",c.username());input.put("password",c.password());
        input.put("connectTimeoutSeconds",2);input.put("socketTimeoutSeconds",3);input.put("queryTimeoutSeconds",4);
        input.put("maxRows",1000);input.put("maxBytes",10L*1024*1024);input.put("isDefault",true);
        return backend.call("connections.create",input,session);
    }

    private static Map<String,Object> exec(Dm7ServicesBackend b,SessionState s,String id,String sql,String purpose,
            boolean atomic,boolean continueOnError,Map<String,Object>... parameters)throws Exception{
        var input=new LinkedHashMap<String,Object>();input.put("connectionId",id);input.put("sql",sql);input.put("purpose",purpose);
        input.put("atomic",atomic);input.put("continueOnError",continueOnError);input.put("timeoutSeconds",4);input.put("parameters",List.of(parameters));
        return b.call("dm7_execute",input,s);
    }
    private static Map<String,Object> query(Dm7ServicesBackend b,SessionState s,String id,String sql,int rows,long bytes,int timeout)throws Exception{
        return b.call("dm7_query",Map.of("connectionId",id,"sql",sql,"maxRows",rows,"maxBytes",bytes,"timeoutSeconds",timeout),s);
    }
    private static Map<String,Object> parameter(int type,Object value){return Map.of("jdbcType",type,"value",value);}
    private static void assertExecution(Map<String,Object> result){assertEquals(true,result.get("success"),"production mutation failed");}
    private static void assertStatementRowCount(Map<String,Object> result,long count){assertExecution(result);@SuppressWarnings("unchecked")var rows=(List<Map<String,Object>>)result.get("statements");assertEquals(count,((Number)rows.get(0).get("rowCount")).longValue());}
    private static void assertReleaseZero(Dm7ServicesBackend b,SessionState s)throws Exception{assertEquals(0,((Number)b.call("release.preview",Map.of(),s).get("statementCount")).intValue());}

    private static Capability rawCapabilityProbe(Config c,Path runtime,String schema)throws Exception{
        ConnectionProfile p=c.profile(schema);try(var handle=new DmDriverLoader(RuntimePaths.forTest(runtime)).load(p)){
            var props=c.properties();try{return new Capability(timeoutProbe(handle.connect(c.url(),props)),cancelProbe(handle.connect(c.url(),props)));}
            finally{props.clear();}
        }
    }
    private static boolean timeoutProbe(java.sql.Connection connection)throws Exception{
        String bounded="SELECT SUM(SQRT(N)) FROM (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 100000000)";boolean timeout=false;
        try{
            try(var calibration=connection.createStatement();var rows=calibration.executeQuery("SELECT COUNT(*) FROM (SELECT LEVEL FROM DUAL CONNECT BY LEVEL <= 1000)")){assertTrue(rows.next());assertEquals(1000L,rows.getLong(1));}
            var executor=Executors.newSingleThreadExecutor();var statement=connection.createStatement();
            try{statement.setQueryTimeout(1);var future=executor.submit(()->{try(var rows=statement.executeQuery(bounded)){while(rows.next()){}return false;}catch(java.sql.SQLException terminal){return true;}});
                try{timeout=future.get(5,TimeUnit.SECONDS);}catch(TimeoutException watchdog){try{statement.cancel();}catch(Exception ignored){}future.cancel(true);throw new IllegalStateException("bounded query timeout watchdog expired");}
            }finally{executor.shutdownNow();if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("bounded timeout worker did not terminate");try{statement.close();}catch(java.sql.SQLException close){if(!timeout)throw close;}}
            return timeout;
        }finally{try{connection.close();}catch(java.sql.SQLException close){if(!timeout)throw close;}}
    }
    private static boolean cancelProbe(java.sql.Connection connection)throws Exception{
        String bounded="SELECT SUM(SQRT(N)) FROM (SELECT LEVEL AS N FROM DUAL CONNECT BY LEVEL <= 100000000)";boolean cancelled=false,terminalObserved=false;
        try{
            var executor=Executors.newSingleThreadExecutor();var statement=connection.createStatement();
            try{statement.setQueryTimeout(4);var future=executor.submit(()->{try(var rows=statement.executeQuery(bounded)){while(rows.next()){}return false;}catch(java.sql.SQLException terminal){return true;}});
                Thread.sleep(100);boolean cancelAccepted;try{statement.cancel();cancelAccepted=true;}catch(Exception unsupported){cancelAccepted=false;}
                long cancelledAt=System.nanoTime();try{boolean failed=future.get(5,TimeUnit.SECONDS);terminalObserved=true;cancelled=cancelAccepted&&failed&&(System.nanoTime()-cancelledAt)<2_000_000_000L;}
                catch(TimeoutException watchdog){future.cancel(true);cancelled=false;}finally{try{statement.cancel();}catch(Exception ignored){}}
            }finally{executor.shutdownNow();if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("bounded cancellation worker did not terminate");try{statement.close();}catch(java.sql.SQLException close){if(!terminalObserved)throw close;}}
            return cancelled;
        }finally{try{connection.close();}catch(java.sql.SQLException close){if(!terminalObserved)throw close;}}
    }
    private static boolean rawCleanupAndVerify(Config c,Path runtime,String schema,List<String> tables)throws Exception{
        if(schema==null)throw new IllegalStateException("cleanup identifiers unavailable");ConnectionProfile p=c.profile(schema);try(var handle=new DmDriverLoader(RuntimePaths.forTest(runtime)).load(p)){
            var props=c.properties();try(var connection=handle.connect(c.url(),props)){
                for(String table:tables)try(var statement=connection.createStatement()){statement.setQueryTimeout(4);statement.execute("DROP TABLE "+qualified(schema,table));}catch(Exception ignored){}
                DatabaseMetaData metadata=connection.getMetaData();
                for(String table:tables)try(var found=metadata.getTables(null,schema,table,new String[]{"TABLE"})){if(found.next())throw new IllegalStateException("cleanup failed for "+table);}
            }finally{props.clear();}
            return true;
        }catch(Exception failure){if(failure.getMessage()!=null&&failure.getMessage().startsWith("cleanup failed for CODEX_DM7_IT_"))throw failure;throw new IllegalStateException("isolated object cleanup could not be confirmed");}
    }

    private void writeCandidate(Config c,String driverVersion,String serverVersion,String fingerprint,List<Map<String,Object>> cases,boolean cleanupConfirmed)throws Exception{
        assertNotNull(driverVersion);assertNotNull(serverVersion);assertNotNull(fingerprint);
        assertTrue(cleanupConfirmed,"cleanup confirmation was not completed");
        var report=new LinkedHashMap<String,Object>();report.put("passed",true);report.put("driverSha256",c.driverSha256Lower().toUpperCase(Locale.ROOT));
        report.put("driverVersion",driverVersion);report.put("serverVersion",serverVersion);report.put("targetFingerprint",fingerprint);
        report.put("cases",cases);report.put("cleanupConfirmed",true);
        for(String value:c.values())assertFalse(JSON.writeValueAsString(report).contains(value));
        Path candidate=Path.of(System.getProperty("dm7.integration.candidate","target/dm7-integration-candidate.json")).toAbsolutePath().normalize();
        Files.createDirectories(candidate.getParent());Path temp=Files.createTempFile(candidate.getParent(),".dm7-integration-",".tmp");
        try{JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),report);Files.move(temp,candidate,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    private void writeCleanupManifest(List<String> names)throws Exception{
        Path manifest=Path.of(System.getProperty("dm7.integration.cleanup-manifest","target/dm7-integration-cleanup-manifest.json")).toAbsolutePath().normalize();
        Files.createDirectories(manifest.getParent());Path temp=Files.createTempFile(manifest.getParent(),".dm7-cleanup-",".tmp");
        try{JSON.writeValue(temp.toFile(),Map.of("objectNames",names));Files.move(temp,manifest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}
    }
    private static void timed(List<Map<String,Object>> cases,String name,Throwing action)throws Exception{long start=System.nanoTime();action.run();cases.add(caseResult(name,elapsed(start),null));}
    private static <T>T timedValue(List<Map<String,Object>> cases,String name,ThrowingValue<T> action)throws Exception{long start=System.nanoTime();T value=action.run();cases.add(caseResult(name,elapsed(start),null));return value;}
    private static Map<String,Object> caseResult(String name,long duration,Boolean supported){var value=new LinkedHashMap<String,Object>();value.put("name",name);value.put("passed",true);value.put("durationMs",duration);if(supported!=null)value.put("supported",supported);return value;}
    private static long elapsed(long start){return Math.max(0,(System.nanoTime()-start)/1_000_000);}
    private static void deleteTree(Path root)throws Exception{
        if(!Files.exists(root))return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private static Throwable merge(Throwable existing,Throwable additional){if(existing==null)return additional;existing.addSuppressed(additional);return existing;}
    private static String safeVersion(Object raw,Config c){String value=String.valueOf(raw).replaceAll("[\\r\\n\\t]"," ").trim();assertFalse(value.isBlank());assertTrue(value.length()<=160);for(String secret:c.values())assertFalse(value.contains(secret));return value;}
    private static String requiredIdentifier(Object raw){String value=String.valueOf(raw);if(!ConnectionProfile.isSafeIdentifier(value))throw new IllegalStateException("current schema is not safely qualifiable");return value;}
    private static String qualified(String schema,String object){return "\""+schema+"\".\""+object+"\"";}
    private static String randomSuffix(){byte[] bytes=new byte[8];RANDOM.nextBytes(bytes);return HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);}
    private static String sha256(Path path)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path)){byte[] buffer=new byte[8192];for(int read;(read=input.read(buffer))>=0;)digest.update(buffer,0,read);}return HexFormat.of().formatHex(digest.digest());}
    @FunctionalInterface interface Throwing{void run()throws Exception;}@FunctionalInterface interface ThrowingValue<T>{T run()throws Exception;}
    record Capability(boolean queryTimeoutEnforced,boolean cancellationSupported){}

    record Config(String url,String username,String password,Path driverJar,String driverSha256Lower){
        static Config fromEnvironment(Map<String,String> env)throws Exception{var values=new ArrayList<String>();for(String name:ENV){String value=env.get(name);if(value==null||value.isBlank())throw new IllegalStateException("integration environment is incomplete");values.add(value);}Path driver=Path.of(values.get(3)).toAbsolutePath().normalize();if(!Files.isRegularFile(driver)||Files.isSymbolicLink(driver))throw new IllegalStateException("integration driver is invalid");if(!values.get(0).startsWith("jdbc:dm7:"))throw new IllegalStateException("integration URL is invalid");return new Config(values.get(0),values.get(1),values.get(2),driver,sha256(driver));}
        List<String> values(){return List.of(url,username,password,driverJar.toString());}
        Properties properties(){var p=new Properties();p.setProperty("user",username);p.setProperty("password",password);p.setProperty("connectTimeout","2000");p.setProperty("socketTimeout","3000");return p;}
        ConnectionProfile profile(String schema){return new ConnectionProfile(UUID.randomUUID(),"integration-cleanup",driverJar,driverSha256Lower,ConnectionProfile.DEFAULT_DRIVER_CLASS,url,username,schema,2,3,4,1000,10L*1024*1024,false);}
    }
}
