// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * Tool 消息中与既有调用标识配对的结果内容块。
 *
 * @param callId 模型生成的工具调用标识
 * @param content 内容
 * @param error 是否为错误结果
 * @param media 结果关联的受管媒体引用，历史中不含媒体正文
 */
public record ToolResultContent(String callId,
                                String content,
                                boolean error,
                                List<ToolMediaReference> media) implements ModelContent {
    /**
     * 既有纯文本结果通过空媒体列表表示，避免改变普通 Tool 的历史语义。
     */
    public ToolResultContent(String callId, String content, boolean error) {
        this(callId, content, error, List.of());
    }

    /**
     * 保留空结果并验证文本形状；长 Tool 结果由 artifact 与分段上下文投影处理。
     */
    public ToolResultContent {
        callId = ContractChecks.identifier(callId, "callId");
        content = ContractChecks.text(content, "content", Integer.MAX_VALUE, true);
        media = List.copyOf(Objects.requireNonNull(media, "media"));
        if (media.size() > 10 || media.stream().map(ToolMediaReference::attachmentId).distinct().count() != media.size()) {
            throw new IllegalArgumentException("invalid tool result media references");
        }
    }
}
