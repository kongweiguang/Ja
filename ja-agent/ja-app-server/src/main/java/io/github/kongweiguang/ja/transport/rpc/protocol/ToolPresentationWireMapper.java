// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;

import java.util.Locale;
import java.util.Objects;

/**
 * 将安全 ToolPresentation 映射为 JA-RPC 字段闭集，不复用数据库 JSON codec 或存储格式。
 */
final class ToolPresentationWireMapper {
    private final ObjectMapper mapper;

    /**
     * 固定 transport 自有的 ObjectMapper，使协议投影不依赖 persistence 实现。
     */
    ToolPresentationWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 问答结果仅投影人类可见文案；稳定 ID 与模型原始结果不进入 Renderer。
     */
    private static void appendInteractionAnswers(ObjectNode node, ToolPresentation value) {
        if (value.interactionAnswers().isEmpty()) {
            return;
        }
        ArrayNode answers = node.putArray("interactionAnswers");
        for (ToolPresentation.InteractionAnswerView answer : value.interactionAnswers()) {
            ObjectNode item = answers.addObject().put("question", answer.question())
                    .put("skipped", answer.skipped());
            ArrayNode labels = item.putArray("answers");
            answer.answers().forEach(labels::add);
        }
    }

    /**
     * 只发布受管图片身份与展示元数据；像素读取仍须经过所属 Thread 的附件授权边界。
     */
    private static void appendMedia(ObjectNode node, ToolPresentation value) {
        if (value.media().isEmpty()) {
            return;
        }
        ArrayNode media = node.putArray("media");
        value.media().forEach(reference -> media.addObject()
                .put("attachmentId", reference.attachmentId())
                .put("mediaType", reference.mediaType())
                .put("displayName", reference.displayName())
                .put("sizeBytes", reference.sizeBytes()));
    }

    /**
     * 摘要、预览与 artifact identity 共用可选语义，缺失时不发送 JSON null。
     */
    private static void appendPreviews(ObjectNode node, ToolPresentation value) {
        optional(node, "inputPreview", value.inputPreview());
        optional(node, "outputPreview", value.outputPreview());
        optional(node, "summary", value.summary());
        optional(node, "artifactId", value.artifactId());
    }

    /**
     * Shell 专属事实单独投影，使普通文件 Tool 不携带无意义占位字段。
     */
    private static void appendShellFacts(ObjectNode node, ToolPresentation value) {
        optionalPairs(node, "command", value.command(), "relativeCwd", value.relativeCwd(),
                "stdout", value.stdout(), "stderr", value.stderr());
        if (value.exitCode() != null) {
            node.put("exitCode", value.exitCode());
        }
        if (value.durationMs() != null) {
            node.put("durationMs", value.durationMs());
        }
    }

    /**
     * 只把 UI 提交裁决所需的单调版本传给 Renderer，Tool identity 继续由 Timeline item 外层提供。
     */
    private static void appendRecovery(ObjectNode node, ToolPresentation value) {
        if (value.recovery() != null) {
            node.putObject("recovery").put("revision", value.recovery().revision());
        }
    }

    /**
     * 固定字段和值成对输入，只用于本类声明的 transport 白名单，避免自由 Map 成为协议入口。
     */
    private static void optionalPairs(ObjectNode node, String... fieldsAndValues) {
        if (fieldsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("field/value pairs required");
        }
        for (int index = 0; index < fieldsAndValues.length; index += 2) {
            optional(node, fieldsAndValues[index], fieldsAndValues[index + 1]);
        }
    }

    /**
     * 协议枚举固定使用小写词汇，不能继承 Jackson 全局命名策略。
     */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 可选文本缺失时不发送 JSON null，保持 schema 的 optional 语义。
     */
    private static void optional(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    /**
     * 实时与历史共用 JA-RPC 展示闭集，媒体只携带受管引用；空可选值保持缺失，不映射原始参数或像素。
     */
    ObjectNode map(ToolPresentation value) {
        Objects.requireNonNull(value, "value");
        ObjectNode node = mapper.createObjectNode();
        node.put("kind", wire(value.kind()));
        node.put("status", wire(value.status()));
        node.put("title", value.title());
        ArrayNode paths = node.putArray("relativePaths");
        value.relativePaths().forEach(paths::add);
        appendInteractionAnswers(node, value);
        appendMedia(node, value);
        appendPreviews(node, value);
        appendShellFacts(node, value);
        appendRecovery(node, value);
        node.put("truncated", value.truncated());
        return node;
    }
}
