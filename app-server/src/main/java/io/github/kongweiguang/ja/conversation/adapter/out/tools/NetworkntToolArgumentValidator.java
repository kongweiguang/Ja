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
            throw invalid("tool arguments do not match the JSON Schema");
        }
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
