// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.List;
import java.util.Objects;

/**
 * 仅供冻结 Provider 请求使用的多模态工具结果；持久历史仍使用无正文的 ToolMediaReference。
 *
 * @param callId 与结果配对的 Provider 原生工具调用标识
 * @param content 面向模型的工具结果文本
 * @param error 工具结果是否表示失败
 * @param images 已授权且通过 Provider 能力检查的原生图片内容
 */
public record NativeToolResultContent(String callId,
                                      String content,
                                      boolean error,
                                      List<NativeAttachmentContent> images) implements ModelContent {
    /**
     * 图片正文只能经过附件授权和 Provider 能力检查进入模型适配器，且必须保留 Tool 配对身份。
     */
    public NativeToolResultContent {
        callId = ContractChecks.identifier(callId, "callId");
        content = ContractChecks.text(content, "content", Integer.MAX_VALUE, true);
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        if (images.size() > 10 || images.stream().anyMatch(image -> image.kind() != NativeAttachmentContent.Kind.IMAGE)) {
            throw new IllegalArgumentException("invalid native tool result media");
        }
    }
}
