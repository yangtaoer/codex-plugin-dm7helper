package io.dm7codex.plugin.http;

import static io.dm7codex.plugin.execution.ExecutionModels.ExecutionStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import io.dm7codex.plugin.execution.ExecutionEventBus;
import io.dm7codex.plugin.runtime.SessionState;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SseEndpointTest {
    private ExecutionEventBus bus; private ConsoleHttpServer server; private URI base; private HttpClient client; private String cookie;
    @BeforeEach void start() throws Exception {
        bus=new ExecutionEventBus(3); server=new ConsoleHttpServer(new ConsoleTokenService(),new EmptyBackend(),bus,
                2,Duration.ofMillis(50),Duration.ofMillis(100)); base=server.start();client=HttpClient.newHttpClient();
        var state=new SessionState("sse-session","hash",1,null,Path.of("active.sql"),Instant.now());
        URI redeem=URI.create((String)server.open(state).get("url"));
        var response=send("POST",redeem.getRawPath()+"?"+redeem.getRawQuery(),null,base.toString(),null,HttpResponse.BodyHandlers.ofString());
        cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
    }
    @AfterEach void close(){server.close();}

    @Test void streamsChineseLiveEventsAndOrderedReplayWithoutDuplicates() throws Exception {
        UUID id=UUID.randomUUID(); bus.publish("sse-session",id,QUEUED,Instant.now(),"排队");
        bus.publish("sse-session",id,EXECUTING,Instant.now(),"正在执行中文 SQL");
        var first=open(null); String initial=readUntil(first.body(),"\n\n",3);
        assertTrue(initial.contains(": connected"));
        String one=readUntil(first.body(),"\n\n",20),two=readUntil(first.body(),"\n\n",20);
        assertTrue(one.contains("id: 1")); assertTrue(one.contains("event: queued"));
        assertTrue(two.contains("id: 2")); assertTrue(two.contains("正在执行中文 SQL")); first.body().close();
        bus.publish("sse-session",id,COMPLETED,Instant.now(),"完成");
        var replay=open("1"); readUntil(replay.body(),"\n\n",3);
        String replayTwo=readUntil(replay.body(),"\n\n",20), replayThree=readUntil(replay.body(),"\n\n",20);
        assertTrue(replayTwo.contains("id: 2")); assertTrue(replayThree.contains("id: 3")); replay.body().close();
    }

    @Test void rejectsRetentionMissAndBoundsConcurrentClients() throws Exception {
        UUID id=UUID.randomUUID(); for(int i=0;i<5;i++)bus.publish("sse-session",id,EXECUTING,Instant.now(),"e"+i);
        var miss=open("1"); assertEquals(409,miss.statusCode()); miss.body().close();
        var a=open(null); var b=open(null); assertEquals(200,a.statusCode());assertEquals(200,b.statusCode());
        var third=open(null);assertEquals(429,third.statusCode());third.body().close();
        a.body().close(); b.body().close();
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->{while(server.activeSseClients()!=0)Thread.sleep(20);});
    }

    private HttpResponse<InputStream> open(String last)throws Exception{return send("GET","/api/events",cookie,null,last,HttpResponse.BodyHandlers.ofInputStream());}
    private <T> HttpResponse<T> send(String method,String path,String cookie,String origin,String last,HttpResponse.BodyHandler<T> handler)throws Exception{var b=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));if(cookie!=null)b.header("Cookie",cookie);if(origin!=null)b.header("Origin",origin);if(last!=null)b.header("Last-Event-ID",last);b.method(method,HttpRequest.BodyPublishers.noBody());return client.send(b.build(),handler);}
    private static String readUntil(InputStream input,String ending,int maxLines)throws Exception{var out=new ByteArrayOutputStream();int newlines=0;while(newlines<maxLines){int b=input.read();if(b<0)break;out.write(b);String s=out.toString(StandardCharsets.UTF_8);if(s.endsWith(ending))return s;if(b=='\n')newlines++;}return out.toString(StandardCharsets.UTF_8);}
    private static final class EmptyBackend implements ConsoleHttpServer.Backend{public Map<String,Object>call(String o,Map<String,Object>i,SessionState s){return Map.of();}public Optional<ConsoleHttpServer.Download>download(String i,SessionState s){return Optional.empty();}}
}
