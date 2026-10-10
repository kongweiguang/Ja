// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentCapabilities;

import java.util.List;

/** 对外区分模型声明与 Provider Codec 实际接受的附件格式。
 *
 * @param declaredFormats 当前模型声明支持的媒体类型
 * @param effectiveFormats 被模型和 Provider Codec 共同接受的媒体类型
 * @param maxSingleBytes 单个附件的最大字节数
 * @param maxTotalBytes 单次模型请求的附件总字节数上限
 * @param maxAttachments 单次输入可引用的附件数量上限
 */
public record AttachmentCapabilitiesView(List<String> declaredFormats,
                                         List<String> effectiveFormats,
                                         long maxSingleBytes,
                                         long maxTotalBytes,
                                         int maxAttachments) {
    /** 冻结启动时的不可变配置快照，避免每次请求重新推断 Provider 能力。 */
    public AttachmentCapabilitiesView {
        declaredFormats = List.copyOf(declaredFormats);
        effectiveFormats = List.copyOf(effectiveFormats);
    }

    /** 只暴露已声明和 Codec 交集后的格式与实际上限。 */
    public static AttachmentCapabilitiesView from(AttachmentCapabilities capabilities) {
        return new AttachmentCapabilitiesView(capabilities.declaredFormats(), capabilities.effectiveFormats(),
                capabilities.maxSingleBytes(), capabilities.maxTotalBytes(), capabilities.maxAttachments());
    }
}
