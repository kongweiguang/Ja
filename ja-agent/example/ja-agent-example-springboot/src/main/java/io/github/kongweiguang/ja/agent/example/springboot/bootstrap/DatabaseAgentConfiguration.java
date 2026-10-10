// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Agent 配置以明文 JSON 保存；示例只在启动装配时读取，不维护配置管理协议。
 */
public final class DatabaseAgentConfiguration {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 保存宿主数据源和环境来源，数据库初始化留到 Spring 生命周期回调。 */
    public DatabaseAgentConfiguration(DataSource dataSource, Environment environment) {
        jdbc = new JdbcTemplate(dataSource);
        this.environment = environment;
    }

    /** 为单一 demo profile 建立毫秒时间列并幂等播种；后续启动不覆盖宿主修改。 */
    @PostConstruct
    void initialize() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS ja_agent_demo_config (
                    profile text NOT NULL,
                    revision bigint NOT NULL CHECK (revision > 0),
                    config jsonb NOT NULL,
                    updated_at_ms bigint NOT NULL
                        DEFAULT (floor(extract(epoch FROM clock_timestamp()) * 1000))::bigint,
                    CONSTRAINT pk_ja_agent_demo_config PRIMARY KEY (profile)
                )
                """);
        Map<String, Object> model = Map.of("providerId", value("JA_EXAMPLE_PROVIDER_ID", "provider_demo"), "modelId", value("JA_EXAMPLE_MODEL_ID", "model_demo"),
                "api", value("JA_EXAMPLE_MODEL_API", "OPENAI_RESPONSES"), "name", value("JA_EXAMPLE_MODEL", "gpt-6-luna"),
                "baseUri", value("JA_EXAMPLE_MODEL_BASE_URI", ""), "apiKey", value("JA_EXAMPLE_MODEL_API_KEY", ""), "reasoningLevel", value("JA_EXAMPLE_REASONING_LEVEL", ""),
                "connectTimeoutSeconds", 10, "requestTimeoutSeconds", 120,
                "inputModalities", configuredInputModalities());
        String defaults = encode(Map.of("model", model, "context", Map.of("windowTokens", Integer.parseInt(value("JA_EXAMPLE_CONTEXT_WINDOW_TOKENS", "256000")),
                        "maxOutputTokens", Integer.parseInt(value("JA_EXAMPLE_MAX_OUTPUT_TOKENS", "8192")), "autoCompact", Boolean.parseBoolean(value("JA_EXAMPLE_AUTO_COMPACT", "true"))),
                "mcpServers", List.of(Map.of("id", "demo", "url", "http://127.0.0.1:8091/mcp", "headers", Map.of()))));
        jdbc.update("INSERT INTO ja_agent_demo_config(profile, revision, config) VALUES ('demo', 1, ?::jsonb) ON CONFLICT (profile) DO NOTHING", defaults);
    }

    /**
     * 限制网络超时为正值，避免配置错误导致无限等待。
     */
    private static Duration timeout(JsonNode node, String field) {
        int seconds = node.path(field).asInt();
        if (seconds < 1 || seconds > 3600) {
            throw new IllegalArgumentException("invalid timeout");
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * 错误只返回字段名，不包含敏感环境值。
     */
    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("missing config field " + field);
        }
        return value;
    }

    /**
     * 用一条查询直接映射完整明文配置，revision 只标识本次冻结的运行时代际。
     */
    public AgentProperties load() {
        return jdbc.queryForObject("SELECT revision, config::text FROM ja_agent_demo_config WHERE profile='demo'",
                (row, index) -> decode(parse(row.getString(2)), row.getLong(1)));
    }

    /**
     * 直接映射模型、上下文和 MCP 字段，体验开关仍由 Spring 装配条件决定。
     */
    private AgentProperties decode(JsonNode root, long revision) {
        if (root == null || !root.isObject() || root.toString().length() > 65536)
            {
                throw new IllegalArgumentException("configuration limit");
            }
        JsonNode model = root.path("model");
        JsonNode context = root.path("context");
        int window = context.path("windowTokens").asInt();
        int output = context.path("maxOutputTokens").asInt();
        if (window < 1024 || output < 1 || output > window) {
            throw new IllegalArgumentException("context budget");
        }
        ArrayList<AgentProperties.McpServer> servers = new java.util.ArrayList<AgentProperties.McpServer>();
        if (!root.path("mcpServers").isArray() || root.path("mcpServers").size() > 16)
            {
                throw new IllegalArgumentException("MCP definitions required");
            }
        for (JsonNode server : root.path("mcpServers")) {
            LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<String, String>();
            server.path("headers").fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
            servers.add(new AgentProperties.McpServer(required(server, "id"), required(server, "url"), headers));
        }
        Set<ModelPort.InputModality> inputModalities = inputModalities(model.path("inputModalities"));
        return new AgentProperties(new AgentProperties.Model(required(model, "providerId"), required(model, "modelId"), "cfg_db_" + revision,
                ModelPort.Api.valueOf(required(model, "api")), required(model, "name"), model.path("baseUri").asText(), model.path("apiKey").asText(),
                timeout(model, "connectTimeoutSeconds"), timeout(model, "requestTimeoutSeconds"), model.path("reasoningLevel").asText(),
                inputModalities),
                new AgentProperties.Context(window, output, context.path("autoCompact").asBoolean()), servers);
    }

    /**
     * 解析数据库中冻结的模态数组；旧配置缺少该字段时失败，避免静默扩大文件输入能力。
     */
    private static Set<ModelPort.InputModality> inputModalities(JsonNode node) {
        if (!node.isArray() || node.isEmpty() || node.size() > ModelPort.InputModality.values().length) {
            throw new IllegalArgumentException("model inputModalities are required");
        }
        EnumSet<ModelPort.InputModality> modalities = EnumSet.noneOf(ModelPort.InputModality.class);
        for (JsonNode value : node) {
            if (!value.isTextual() || !modalities.add(ModelPort.InputModality.valueOf(value.textValue()))) {
                throw new IllegalArgumentException("invalid model inputModalities");
            }
        }
        if (!modalities.contains(ModelPort.InputModality.TEXT)) {
            throw new IllegalArgumentException("text input modality is required");
        }
        return Set.copyOf(modalities);
    }

    /**
     * 新建数据库的演示模型默认声明图片与 PDF；企业可通过环境变量缩小声明范围。
     */
    private List<String> configuredInputModalities() {
        String configured = value("JA_EXAMPLE_MODEL_INPUT_MODALITIES", "TEXT,IMAGE,PDF");
        List<String> values = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        inputModalities(mapper.valueToTree(values));
        return values;
    }

    /**
     * Spring 测试环境与实际宿主环境使用同一查找规则。
     */
    private String value(String name, String fallback) {
        return environment.getProperty(name, fallback);
    }

    /**
     * 结构化 JSON 转换使数据库参数保持独立于 SQL 文本。
     */
    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid configuration", failure);
        }
    }

    /**
     * 数据库中损坏的配置显式失败，不猜测新模型或工具。
     */
    private JsonNode parse(String value) {
        try {
            return mapper.readTree(value);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("invalid stored configuration", failure);
        }
    }
}
