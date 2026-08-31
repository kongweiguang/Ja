// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;

import java.util.Locale;
import java.util.Objects;

/**
 * 将安全 ToolPresentation 映射为 JA-RPC 字段闭集，不复用数据库 JSON codec 或存储格式。
 */
final class ToolPresentationWireMapper {
    private final ObjectMapper mapper;

    /** 固定 transport 自有的 ObjectMapper，使协议投影不依赖 persistence 实现。 */
    ToolPresentationWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 只写 JA-RPC v2 声明的展示字段；空可选值保持缺失，原始 Tool 参数和结果没有映射入口。
     */
    ObjectNode map(ToolPresentation value) {
        Objects.requireNonNull(value, "value");
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", wire(value.kind()));
        node.put("status", wire(value.status()));
        node.put("title", value.title());
        ArrayNode paths = node.putArray("relativePaths");
        value.relativePaths().forEach(paths::add);
        appendPreviews(node, value);
        appendShellFacts(node, value);
        node.put("truncated", value.truncated());
        return node;
    }

    /** 展示摘要与 artifact identity 共用可选语义，缺失时不发送 JSON null。 */
    private static void appendPreviews(ObjectNode node, ToolPresentation value) {
        optional(node, "inputPreview", value.inputPreview());
        optional(node, "outputPreview", value.outputPreview());
        optional(node, "artifactId", value.artifactId());
    }

    /** Shell 专属事实单独投影，使普通文件 Tool 不携带无意义占位字段。 */
    private static void appendShellFacts(ObjectNode node, ToolPresentation value) {
        optionalPairs(node, "command", value.command(), "relativeCwd", value.relativeCwd(),
                "stdout", value.stdout(), "stderr", value.stderr());
        if (value.exitCode() != null) node.put("exitCode", value.exitCode());
        if (value.durationMs() != null) node.put("durationMs", value.durationMs());
    }

    /** 固定字段和值成对输入，只用于本类声明的 transport 白名单，避免自由 Map 成为协议入口。 */
    private static void optionalPairs(ObjectNode node, String... fieldsAndValues) {
        if (fieldsAndValues.length % 2 != 0) throw new IllegalArgumentException("field/value pairs required");
        for (int index = 0; index < fieldsAndValues.length; index += 2) {
            optional(node, fieldsAndValues[index], fieldsAndValues[index + 1]);
        }
    }

    /** 协议枚举固定使用小写词汇，不能继承 Jackson 全局命名策略。 */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** 可选文本缺失时不发送 JSON null，保持 schema 的 optional 语义。 */
    private static void optional(ObjectNode node, String field, String value) {
        if (value != null) node.put(field, value);
    }
}
