package io.dm7codex.plugin.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;

final class JsonHttp {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private JsonHttp() {}

    static Map<String,Object> readObject(HttpExchange exchange, int maximum) throws IOException, HttpProblem {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:\s*;.*)?"))
            throw new HttpProblem(415, "UNSUPPORTED_MEDIA_TYPE", "请求必须使用 JSON。");
        byte[] bytes = bounded(exchange.getRequestBody(), maximum);
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject()) throw new IOException("object required");
            @SuppressWarnings("unchecked") Map<String,Object> value = JSON.convertValue(node, Map.class);
            return Collections.unmodifiableMap(new LinkedHashMap<>(value));
        } catch (IOException | IllegalArgumentException malformed) {
            throw new HttpProblem(400, "MALFORMED_JSON", "JSON 请求无效。");
        }
    }

    static Map<String,Object> readObject(HttpExchange exchange,int maximum,ExecutorService readers,Duration timeout)
            throws IOException,HttpProblem {
        Future<Map<String,Object>> future;
        try { future=readers.submit(()->readObject(exchange,maximum)); }
        catch(RejectedExecutionException busy){throw new HttpProblem(429,"BODY_READER_LIMIT","请求读取器已达上限。");}
        try{return future.get(timeout.toMillis(),TimeUnit.MILLISECONDS);}
        catch(TimeoutException slow){future.cancel(true);if(readers instanceof ThreadPoolExecutor pool)pool.purge();try{exchange.getRequestBody().close();}catch(IOException ignored){}throw new HttpProblem(408,"REQUEST_TIMEOUT","请求体读取超时。");}
        catch(InterruptedException interrupted){future.cancel(true);Thread.currentThread().interrupt();throw new HttpProblem(400,"REQUEST_INTERRUPTED","请求已中断。");}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof HttpProblem problem)throw problem;if(cause instanceof IOException io)throw io;if(cause instanceof RuntimeException runtime)throw runtime;throw new IOException("request body read failed");}
    }

    static byte[] bounded(InputStream input, int maximum) throws IOException, HttpProblem {
        var output = new ByteArrayOutputStream(Math.min(maximum, 8192));
        byte[] chunk = new byte[8192]; int count;
        while ((count=input.read(chunk)) >= 0) {
            if (output.size() + count > maximum) throw new HttpProblem(413, "REQUEST_TOO_LARGE", "请求体超出限制。");
            output.write(chunk,0,count);
        }
        return output.toByteArray();
    }

    static final class HttpProblem extends Exception {
        private final int status; private final String code; private final String safeMessage;
        HttpProblem(int status, String code, String safeMessage) { super(code); this.status=status; this.code=code; this.safeMessage=safeMessage; }
        int status(){return status;} String code(){return code;} String safeMessage(){return safeMessage;}
    }
}
