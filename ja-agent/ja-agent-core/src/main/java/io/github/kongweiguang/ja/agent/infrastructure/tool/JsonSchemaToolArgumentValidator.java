// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.tool;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.shared.Hashing;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Core 内置的 JSON Schema 2020-12 Tool 参数校验器，错误内容不会携带参数值。
 */
public final class JsonSchemaToolArgumentValidator implements ToolArgumentValidator {
    private static final int MAX_SCHEMA_CHARACTERS = 1_048_576;
    private static final int MAX_COMPILED_SCHEMAS = 256;
    private static final ObjectMapper JSON = argumentMapper();
    private static final Set<String> META_RESOURCES = Set.of("schema", "meta/core", "meta/applicator",
            "meta/unevaluated", "meta/validation", "meta/meta-data", "meta/format-annotation", "meta/content");
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaCacheEnabled(false)
                    .resourceLoaders(loaders -> loaders.values(List::clear).add(JsonSchemaToolArgumentValidator::metaResource))
                    .nodeReader(reader -> reader.jsonMapper(JSON))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().failFast(true).typeLoose(false).build()));
    private static final Schema META_SCHEMA = REGISTRY.getSchema(
            SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

    private final JsonValueCodec codec;
    private final Map<String, Schema> schemas = new BoundedSchemaCache(MAX_COMPILED_SCHEMAS);

    /**
     * 复用同一严格 JSON 方言，避免 Schema 与 Tool 参数经不同编码路径产生类型差异。
     */
    public JsonSchemaToolArgumentValidator(JsonValueCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * 提前初始化惰性校验器，把 Schema 配置错误与用户参数错误分开；失败保留调用位置但不暴露 Schema 正文。
     */
    @SuppressWarnings("PMD.PreserveStackTrace") // 原异常可能包含用户 Schema；脱敏 cause 复制调用帧，避免异常链泄露正文。
    private static Schema compileSchema(String source) {
        try {
            JsonNode root = JSON.readTree(source);
            if (root == null || (!root.isObject() && !root.isBoolean())) {
                throw invalidSchema();
            }
            if (!META_SCHEMA.validate(root).isEmpty()) {
                throw invalidSchema();
            }
            Schema schema = REGISTRY.getSchema(root.deepCopy());
            schema.initializeValidators();
            return schema;
        } catch (RuntimeException failure) {
            IllegalStateException diagnostic = new IllegalStateException("JSON Schema compilation failed");
            diagnostic.setStackTrace(failure.getStackTrace());
            throw new IllegalArgumentException("Tool input schema is invalid", diagnostic);
        }
    }

    /**
     * 只读取依赖包自带的标准元 Schema；工具不能借 $ref 访问网络、文件或任意 classpath 资源。
     */
    private static com.networknt.schema.resource.InputStreamSource metaResource(com.networknt.schema.AbsoluteIri location) {
        String prefix = "classpath:draft/2020-12/";
        String value = location.toString();
        if (!value.startsWith(prefix) || !META_RESOURCES.contains(value.substring(prefix.length()))) {
            return null;
        }
        return () -> {
            java.io.InputStream resource = Schema.class.getResourceAsStream("/draft/2020-12/" + value.substring(prefix.length()));
            if (resource == null) {
                throw new java.io.IOException("bundled JSON Schema metadata is unavailable");
            }
            return resource;
        };
    }

    /**
     * 只保留 JSON Pointer 位置、属性标识及标准约束名，不回传第三方错误正文。
     */
    private static String safeDiagnostic(com.networknt.schema.Error error) {
        String location = safeLocation(error.getInstanceLocation() == null
                ? null : error.getInstanceLocation().toString());
        String property = safeProperty(error.getProperty());
        String keyword = safeKeyword(error.getKeyword());
        String detail = switch (keyword) {
            case "required" -> "a required field is missing";
            case "type" -> "the JSON type is incorrect";
            case "additionalProperties" -> "an undeclared field is present";
            case "minimum", "exclusiveMinimum", "maximum", "exclusiveMaximum", "multipleOf",
                 "minLength", "maxLength", "pattern", "format", "enum", "const", "uniqueItems",
                 "minItems", "maxItems", "minProperties", "maxProperties" ->
                    "the declared value constraint is not satisfied";
            default -> "the declared schema constraint is not satisfied";
        };
        return "Tool arguments violate '" + keyword + "' at " + location
               + (property == null ? "" : " for field '" + property + "'") + ": " + detail + ".";
    }

    /**
     * 调整网络解析约束以支持模型提供的长文本参数，同时仍限制结构嵌套和数字复杂度。
     */
    private static ObjectMapper argumentMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder().maxNestingDepth(64).maxNumberLength(1_000)
                .maxStringLength(Integer.MAX_VALUE).maxDocumentLength(Long.MAX_VALUE)
                .maxTokenCount(Long.MAX_VALUE).build();
        return JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
                .streamReadConstraints(constraints).build()).build();
    }

    /**
     * 验证诊断中的位置仅包含 JSON Pointer 安全字符，不暴露实例值。
     */
    private static String safeLocation(String value) {
        if (value == null || value.isBlank() || value.length() > 256
            || !value.matches("[A-Za-z0-9_.$/\\[\\]~\\-]+")) {
                return "$";
            }
        return value;
    }

    /**
     * 验证 Schema 属性名为普通标识，避免控制字符进入模型纠错上下文。
     */
    private static String safeProperty(String value) {
        if (value == null || value.isBlank() || value.length() > 128
            || !value.matches("[A-Za-z0-9_.\\-]+")) {
                return null;
            }
        return value;
    }

    /**
     * 未知约束使用固定词，阻止第三方诊断字符串越过扩展边界。
     */
    private static String safeKeyword(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_\\-]{0,63}")) {
            return "validation";
        }
        return value;
    }

    /**
     * Schema 定义错误不能伪装成可由模型修正的参数失败，也不保留第三方 cause。
     */
    private static IllegalArgumentException invalidSchema() {
        return new IllegalArgumentException("Tool input schema is invalid");
    }

    /**
     * 非法参数返回脱敏原因供模型纠正；非法 Schema 属于配置故障，必须明确失败关闭。
     */
    @Override
    public Optional<String> invalidReason(JsonObject schema, JsonObject arguments) {
        String schemaJson = Objects.requireNonNull(codec.encode(Objects.requireNonNull(schema, "schema")),
                "encoded schema");
        Schema compiled = compiledSchema(schemaJson);
        String argumentsJson;
        try {
            argumentsJson = Objects.requireNonNull(codec.encode(Objects.requireNonNull(arguments, "arguments")),
                    "encoded arguments");
        } catch (RuntimeException encodingFailure) {
            return Optional.of("Tool arguments could not be encoded as JSON.");
        }
        try {
            List<com.networknt.schema.Error> errors = compiled.validate(argumentsJson, InputFormat.JSON);
            return errors.isEmpty() ? Optional.empty() : Optional.of(safeDiagnostic(errors.getFirst()));
        } catch (RuntimeException validationFailure) {
            return Optional.of("Tool arguments could not be validated against the declared schema.");
        }
    }

    /**
     * 使用可共享的 immutable compiled schema，并在同一锁内完成 LRU 淘汰。
     */
    private Schema compiledSchema(String source) {
        if (source.isBlank() || source.length() > MAX_SCHEMA_CHARACTERS) {
            throw invalidSchema();
        }
        String key = Hashing.sha256Hex(source);
        synchronized (schemas) {
            Schema cached = schemas.get(key);
            if (cached != null) {
                return cached;
            }
            Schema compiled = compileSchema(source);
            schemas.put(key, compiled);
            return compiled;
        }
    }

    /**
     * 静态缓存容器避免匿名可序列化子类隐式捕获校验器实例。
     */
    private static final class BoundedSchemaCache extends LinkedHashMap<String, Schema> {
        private static final long serialVersionUID = 1L;

        private final int maximumEntries;

        /**
         * 保留最近使用顺序和显式上限，避免动态工具 Schema 图无限占用进程内存。
         */
        private BoundedSchemaCache(int maximumEntries) {
            super(16, 0.75f, true);
            this.maximumEntries = maximumEntries;
        }

        /**
         * 插入超限条目时只淘汰最久未访问的 Schema。
         */
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
            return size() > maximumEntries;
        }
    }
}
