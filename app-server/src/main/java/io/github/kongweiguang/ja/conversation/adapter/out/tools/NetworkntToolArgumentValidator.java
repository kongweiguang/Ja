// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 为全部 Tool 调用方提供与 Provider 无关的 JSON Schema 2020-12 校验边界。
 */
public final class NetworkntToolArgumentValidator {
    private static final int MAX_SCHEMA_CHARACTERS = 1_048_576;
    private static final int MAX_ARGUMENT_CHARACTERS = 4_194_304;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaCacheEnabled(false)
                    .resourceLoaders(loaders -> loaders.values(List::clear))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder()
                            .failFast(true)
                            .typeLoose(false)
                            .build()));
    private final Schema schema;

    /**
     * 只接受协议无关的 JSON 文本，避免把 Jackson 2/3 类型泄漏给 Provider；Schema 在构造时编译，
     * 从而让非法 Tool 定义在进入 Agent Loop 前失败关闭。
     */
    public NetworkntToolArgumentValidator(String schemaJson) {
        requireBounded(schemaJson, MAX_SCHEMA_CHARACTERS, "tool input schema is invalid");
        try {
            JsonNode root = JSON.readTree(schemaJson);
            if (root == null || (!root.isObject() && !root.isBoolean())) {
                throw invalid("tool input schema must be a JSON object or boolean");
            }
            this.schema = REGISTRY.getSchema(root.deepCopy());
        } catch (RuntimeException failure) {
            if (failure instanceof ToolSchemaException bounded) {
                throw bounded;
            }
            throw invalid("tool input schema is invalid");
        }
    }

    /**
     * 使用 networknt 3 的原生 JSON 文本入口校验完整参数；第三方异常和具体校验路径均不会越过此边界，
     * 以免 Schema、参数值或实现堆栈进入 Tool/RPC 错误。
     */
    public void validate(String argumentsJson) {
        requireBounded(argumentsJson, MAX_ARGUMENT_CHARACTERS, "tool arguments could not be validated");
        List<com.networknt.schema.Error> errors;
        try {
            errors = schema.validate(argumentsJson, InputFormat.JSON);
        } catch (RuntimeException failure) {
            throw invalid("tool arguments could not be validated");
        }
        if (!errors.isEmpty()) {
            throw invalid(safeDiagnostic(errors.getFirst()));
        }
    }

    /**
     * 只暴露首个失败的 Schema 字段、实例位置、约束类型和经过数值白名单过滤的约束值；绝不使用
     * 第三方 message 或 instanceNode，因为二者可能把命令、路径、正文或凭据值带回模型上下文。
     */
    private static String safeDiagnostic(com.networknt.schema.Error error) {
        String location = safeLocation(error.getInstanceLocation() == null
                ? null : error.getInstanceLocation().toString());
        String property = safeProperty(error.getProperty());
        if (property == null) property = safeLocationProperty(location);
        String keyword = safeKeyword(error.getKeyword());
        return switch (keyword) {
            case "required" -> safeProperty(error.getProperty()) == null
                    ? "a required Tool field is missing at " + location
                    : "required Tool field '" + safeProperty(error.getProperty()) + "' is missing at " + location;
            case "type" -> "Tool field at " + location + " has the wrong JSON type";
            case "additionalProperties" -> property == null
                    ? "Tool arguments contain an unexpected field at " + location
                    : "Tool field '" + property + "' is not allowed at " + location;
            case "minLength" -> constraintDiagnostic(property, location, keyword,
                    "requires a minimum length of " + numericArgument(error, "the schema-defined minimum"));
            case "maxLength" -> constraintDiagnostic(property, location, keyword,
                    "requires a maximum length of " + numericArgument(error, "the schema-defined maximum"));
            case "minItems" -> constraintDiagnostic(property, location, keyword,
                    "requires at least " + numericArgument(error, "the schema-defined minimum") + " item(s)");
            case "maxItems" -> constraintDiagnostic(property, location, keyword,
                    "allows at most " + numericArgument(error, "the schema-defined maximum") + " item(s)");
            case "minProperties" -> constraintDiagnostic(property, location, keyword,
                    "requires at least " + numericArgument(error, "the schema-defined minimum")
                            + " properties");
            case "maxProperties" -> constraintDiagnostic(property, location, keyword,
                    "allows at most " + numericArgument(error, "the schema-defined maximum")
                            + " properties");
            case "minimum" -> constraintDiagnostic(property, location, keyword,
                    "must be at least " + numericArgument(error, "the schema-defined minimum"));
            case "exclusiveMinimum" -> constraintDiagnostic(property, location, keyword,
                    "must be greater than " + numericArgument(error, "the schema-defined minimum"));
            case "maximum" -> constraintDiagnostic(property, location, keyword,
                    "must be at most " + numericArgument(error, "the schema-defined maximum"));
            case "exclusiveMaximum" -> constraintDiagnostic(property, location, keyword,
                    "must be less than " + numericArgument(error, "the schema-defined maximum"));
            case "multipleOf" -> constraintDiagnostic(property, location, keyword,
                    "must be a multiple of " + numericArgument(error, "the schema-defined value"));
            case "pattern", "format", "enum", "const", "uniqueItems" ->
                    constraintDiagnostic(property, location, keyword, "does not satisfy the declared value constraint");
            default -> "Tool field at " + location + " violates the '" + keyword + "' constraint";
        };
    }

    /** 将已审计字段与固定约束说明拼接，保留修正方向但不把 Schema 正文带入诊断。 */
    private static String constraintDiagnostic(String property, String location, String keyword, String detail) {
        String field = property == null ? "Tool field at " + location : "Tool field '" + property + "' at " + location;
        return field + " violates the '" + keyword + "' constraint: " + detail;
    }

    /** 只接受 networknt 首个约束参数中的有限数值；实际参数长度等第二个值永不进入公开诊断。 */
    private static String numericArgument(com.networknt.schema.Error error, String fallback) {
        try {
            Object[] arguments = error.getArguments();
            if (arguments != null && arguments.length > 0) {
                String value = arguments[0] instanceof Number number ? number.toString()
                        : arguments[0] instanceof String text ? text : null;
                if (value == null) return fallback;
                if (value.length() <= 64
                        && value.matches("-?(?:0|[1-9][0-9]{0,63})(?:\\.[0-9]{1,63})?")) {
                    return value;
                }
            }
        } catch (RuntimeException ignored) {
            // 第三方 Error 实例异常时回退到固定描述，不能让诊断路径改变执行结果。
        }
        return fallback;
    }

    /** 从安全的实例位置补出字段名，使 networknt 未提供 property 元数据时仍能指出修正字段。 */
    private static String safeLocationProperty(String location) {
        if (location == null || location.isBlank() || "$".equals(location) || "/".equals(location)) return null;
        int separator = location.lastIndexOf('/');
        String candidate = separator < 0 ? location : location.substring(separator + 1);
        if (candidate.isBlank() && separator > 0) {
            candidate = location.substring(0, separator);
            separator = candidate.lastIndexOf('/');
            candidate = separator < 0 ? candidate : candidate.substring(separator + 1);
        }
        return safeProperty(candidate);
    }

    /** 保留引擎返回的 JSON Pointer 位置，限制字符和长度，避免诊断包含参数值。 */
    private static String safeLocation(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || !value.matches("[A-Za-z0-9_.$/\\[\\]~\\-]+")) return "$";
        return value;
    }

    /** 字段名来自公开 Tool Schema，但仍限制为普通标识，拒绝控制字符和诊断注入。 */
    private static String safeProperty(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || !value.matches("[A-Za-z0-9_.\\-]+")) return null;
        return value;
    }

    /** 未知约束名统一降级为 validation，避免第三方字符串进入 ToolResult。 */
    private static String safeKeyword(String value) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9_\\-]{0,63}")) return "validation";
        return value;
    }

    /**
     * 在解析前实施字符上限，并用固定错误替换可能包含输入正文的底层诊断。
     */
    private static void requireBounded(String document, int maxCharacters, String errorMessage) {
        if (document == null || document.isBlank() || document.length() > maxCharacters) {
            throw invalid(errorMessage);
        }
    }

    /**
     * 创建不回显 Schema 或参数值的非重试校验错误。
     */
    private static ToolSchemaException invalid(String message) {
        return new ToolSchemaException(message);
    }
}
