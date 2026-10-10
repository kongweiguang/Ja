// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.out.core;

import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;
import io.github.kongweiguang.ja.agent.infrastructure.model.anthropic.AnthropicMessagesAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiChatCompletionsAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiResponsesAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将冻结的模型输入模态与实际 Provider Codec 规则合成为示例可复用的能力快照。
 */
public final class AttachmentCapabilitiesFactory {
    private static final List<String> IMAGE_FORMATS = List.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");
    private static final String PDF_FORMAT = "application/pdf";

    /** 无状态工厂不持有连接或远程资源，能力计算只依赖已冻结模型配置与本地 Codec 规则。 */
    private AttachmentCapabilitiesFactory() {
    }

    /** 先取模型声明与所选 Codec 的交集，避免上传层承诺 Provider 实际无法编码的媒体。 */
    public static AttachmentCapabilities fromModel(ModelPort.ModelConfiguration model) {
        ModelPort.ModelConfiguration configuration = Objects.requireNonNull(model, "model");
        ModelPort.NativeAttachmentSupport support = switch (configuration.api()) {
            case ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.nativeAttachmentSupport();
            case OPENAI_RESPONSES -> OpenAiResponsesAdapter.nativeAttachmentSupport();
            case OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsAdapter.nativeAttachmentSupport();
        };
        List<String> declared = declaredFormats(configuration);
        List<String> effective = new ArrayList<>();
        long maxSingle = 0;
        for (ModelPort.NativeAttachmentRule rule : support.rules()) {
            for (String mediaType : declared) {
                if (rule.mediaTypes().contains(mediaType)) {
                    effective.add(mediaType);
                    maxSingle = Math.max(maxSingle, rule.maxBytes());
                }
            }
        }
        long maxTotal = effective.isEmpty() ? 0 : support.maxTotalBytes();
        return new AttachmentCapabilities(declared, effective, maxSingle, maxTotal, 10);
    }

    /** 只展开模型明确声明的 IMAGE/PDF 模态，不根据 Provider 名称猜测输入能力。 */
    private static List<String> declaredFormats(ModelPort.ModelConfiguration configuration) {
        List<String> formats = new ArrayList<>();
        if (configuration.inputModalities().contains(ModelPort.InputModality.IMAGE)) {
            formats.addAll(IMAGE_FORMATS);
        }
        if (configuration.inputModalities().contains(ModelPort.InputModality.PDF)) {
            formats.add(PDF_FORMAT);
        }
        return List.copyOf(formats);
    }
}
