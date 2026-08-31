// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** ToolPresentation 的显式 JSON codec，避免数据库行或反射模型直接成为 JA-RPC DTO。 */
public final class ToolPresentationCodec {
    private final ObjectMapper mapper;

    /** 固定 ObjectMapper 归属，读写均通过字段白名单。 */
    public ToolPresentationCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 写入严格字段闭集，不序列化空值或原始 Tool 参数。 */
    public String write(ToolPresentation value) {
        ObjectNode node = mapper.createObjectNode()
                .put("kind", wire(value.kind())).put("title", value.title())
                .put("status", wire(value.status())).put("truncated", value.truncated());
        optional(node, "inputPreview", value.inputPreview());
        optional(node, "outputPreview", value.outputPreview());
        ArrayNode paths = node.putArray("relativePaths");
        value.relativePaths().forEach(paths::add);
        optional(node, "command", value.command());
        optional(node, "relativeCwd", value.relativeCwd());
        optional(node, "stdout", value.stdout());
        optional(node, "stderr", value.stderr());
        if (value.exitCode() != null) node.put("exitCode", value.exitCode());
        if (value.durationMs() != null) node.put("durationMs", value.durationMs());
        optional(node, "artifactId", value.artifactId());
        return node.toString();
    }

    /** 损坏或未知持久值失败关闭，不回退到 raw arguments/result。 */
    public ToolPresentation read(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            if (node == null || !node.isObject()) throw invalid();
            List<String> paths = new ArrayList<>();
            JsonNode pathValues = required(node, "relativePaths");
            if (!pathValues.isArray()) throw invalid();
            pathValues.forEach(path -> paths.add(text(path)));
            return new ToolPresentation(
                    ToolPresentation.Kind.valueOf(text(required(node, "kind")).toUpperCase(Locale.ROOT)),
                    text(required(node, "title")),
                    ToolPresentation.Status.valueOf(text(required(node, "status")).toUpperCase(Locale.ROOT)),
                    optionalText(node, "inputPreview"), optionalText(node, "outputPreview"), paths,
                    optionalText(node, "command"), optionalText(node, "relativeCwd"),
                    optionalText(node, "stdout"), optionalText(node, "stderr"),
                    optionalInteger(node, "exitCode"), optionalLong(node, "durationMs"),
                    required(node, "truncated").booleanValue(), optionalText(node, "artifactId"));
        } catch (RuntimeException | java.io.IOException failure) {
            if (failure instanceof StorageException storage) throw storage;
            throw invalid();
        }
    }

    /** 提供持久化测试和迁移内部使用的结构化节点，不把数据库 codec 暴露给 transport。 */
    public ObjectNode toNode(ToolPresentation value) {
        try {
            return (ObjectNode) mapper.readTree(write(value));
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("generated presentation JSON is invalid", impossible);
        }
    }

    /** 枚举只在唯一 codec 处转换成小写 wire 词汇。 */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 可空字符串缺失时不写 null，保持 optional 语义。 */
    private static void optional(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }

    /** 读取必需字段且拒绝 JSON null。 */
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) throw invalid();
        return value;
    }

    /** 读取强类型文本。 */
    private static String text(JsonNode value) {
        if (!value.isTextual()) throw invalid();
        return value.textValue();
    }

    /** 可选文本不接受其它 JSON 类型。 */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : text(value);
    }

    /** 可选 int 必须精确落在 Java int 范围。 */
    private static Integer optionalInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isInt() ? value.intValue() : invalidValue();
    }

    /** 可选 long 必须是 integral number。 */
    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.isIntegralNumber() ? value.longValue() : invalidValue();
    }

    /** 泛型辅助只用于条件表达式抛出稳定损坏错误。 */
    private static <T> T invalidValue() {
        throw invalid();
    }

    /** 存储损坏不回显 JSON 内容。 */
    private static StorageException invalid() {
        return new StorageException(StorageException.Code.INVALID_STATE, "tool presentation is invalid");
    }
}
