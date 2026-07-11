package io.dm7codex.plugin.mcp;

import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Dm7ToolSchemas {
    public static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private Dm7ToolSchemas() {}

    public static Map<String, Tool> definitions() {
        var tools = new LinkedHashMap<String, Tool>();
        add(tools, "dm7_open_console", "打开本地 DM7 管理控制台；当前版本未提供控制台后端。", schema(), false, false, false);
        add(tools, "dm7_list_connections", "列出已保存的连接摘要，不返回密码、完整 JDBC URL 或驱动路径。", schema(), true, false, false);
        add(tools, "dm7_test_connection", "使用已保存凭据测试 DM7 连接；会只读访问外部数据库。",
                schema(prop("connectionId", string("连接 ID；省略时使用默认连接。"))), true, false, true);
        add(tools, "dm7_query", "执行单条只读查询或 EXPLAIN；修改语句会被拒绝。",
                schema(props(
                        "connectionId", string("连接 ID；省略时使用默认连接。"),
                        "sql", string("单条只读 SQL。"),
                        "parameters", array("可选绑定参数；当前执行后端仅接受空数组。"),
                        "maxRows", integer("最大返回行数。", 1, 10_000, 1_000),
                        "maxBytes", integer("最大结果字节数。", 1, 52_428_800, 10_485_760),
                        "timeoutSeconds", integer("查询超时秒数。", 1, 3_600, 60)), "sql"), true, false, true);
        var purpose = string("变更用途，决定是否进入当前会话发版日志。");
        purpose.put("enum", List.of("production_change", "migration", "test", "mock", "seed", "sample"));
        add(tools, "dm7_execute", "执行 DDL/DML 脚本并返回逐语句结果；这是数据库修改操作。",
                schema(props(
                        "connectionId", string("连接 ID；省略时使用默认连接。"),
                        "sql", string("DDL/DML SQL 脚本。"),
                        "parameters", array("可选绑定参数；当前执行后端仅接受空数组。"),
                        "purpose", purpose,
                        "atomic", bool("是否以插件事务原子执行；包含 DDL 时不可用。", true),
                        "continueOnError", bool("非原子模式下失败后是否继续。", false),
                        "timeoutSeconds", integer("每条语句超时秒数。", 1, 3_600, 60)), "sql", "purpose"), false, true, true);
        add(tools, "dm7_describe_schema", "分页读取 schema、表、视图和列元数据。",
                schema(props(
                        "connectionId", string("连接 ID；省略时使用默认连接。"),
                        "schemaPattern", string("可选 schema 匹配模式。"),
                        "objectPattern", string("可选对象匹配模式。"),
                        "offset", integer("分页偏移。", 0, Integer.MAX_VALUE, 0),
                        "limit", integer("每页对象数。", 1, 200, 50))), true, false, true);
        add(tools, "dm7_get_execution", "读取本会话执行历史摘要及逐语句状态。",
                schema(props("executionId", string("执行 ID。")), "executionId"), true, false, false);
        add(tools, "dm7_cancel_execution", "取消正在运行的执行并清理 JDBC 资源；会修改本地执行状态。",
                schema(props("executionId", string("执行 ID。")), "executionId"), false, true, false);
        add(tools, "dm7_get_release_log", "读取当前会话活动发版日志的版本、计数和预览。", schema(), true, false, false);
        add(tools, "dm7_release_export", "明确确认后封存并导出当前会话发版 SQL，同时轮换活动版本。",
                schema(props("confirm", bool("必须显式为 true 才会导出。", null)), "confirm"), false, true, false);
        return Collections.unmodifiableMap(tools);
    }

    private static void add(Map<String, Tool> tools, String name, String description,
                            Map<String, Object> inputSchema, boolean readOnly,
                            boolean destructive, boolean openWorld) {
        var annotations = ToolAnnotations.builder().title(name)
                .readOnlyHint(readOnly).destructiveHint(destructive)
                .idempotentHint(readOnly).openWorldHint(openWorld).returnDirect(false).build();
        tools.put(name, Tool.builder(name).title(name).description(description)
                .inputSchema(inputSchema).annotations(annotations).build());
    }

    private static Map<String, Object> schema() { return schema(new LinkedHashMap<>()); }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("$schema", DRAFT_2020_12);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> prop(String name, Map<String, Object> definition) {
        var properties = new LinkedHashMap<String, Object>();
        properties.put(name, definition);
        return properties;
    }

    private static Map<String, Object> props(Object... pairs) {
        var properties = new LinkedHashMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) {
            properties.put((String) pairs[index], cast(pairs[index + 1]));
        }
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }

    private static Map<String, Object> string(String description) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "string"); result.put("minLength", 1); result.put("description", description);
        return result;
    }

    private static Map<String, Object> integer(String description, long minimum, long maximum, long defaultValue) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "integer"); result.put("minimum", minimum); result.put("maximum", maximum);
        result.put("default", defaultValue); result.put("description", description);
        return result;
    }

    private static Map<String, Object> bool(String description, Boolean defaultValue) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "boolean");
        if (defaultValue != null) result.put("default", defaultValue);
        result.put("description", description);
        return result;
    }

    private static Map<String, Object> array(String description) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", "array"); result.put("items", Map.of());
        result.put("default", List.of()); result.put("description", description);
        return result;
    }
}
