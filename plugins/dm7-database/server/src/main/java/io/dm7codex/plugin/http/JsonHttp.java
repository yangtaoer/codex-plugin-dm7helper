package io.dm7codex.plugin.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
