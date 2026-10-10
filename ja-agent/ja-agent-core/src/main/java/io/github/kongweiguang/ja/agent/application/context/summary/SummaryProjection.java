// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context.summary;

import io.github.kongweiguang.ja.agent.application.context.ContextPolicy;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryTurnSplit;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;

import java.util.List;
import java.util.Objects;

/**
 * 在应用边界完成预算历史到中立摘要模型契约的单向投影。
 */
public final class SummaryProjection {
    /**
     * 静态转换器不保留请求状态，摘要模型租约单独管理 Provider 生命周期。
     */
    private SummaryProjection() {
    }

    /**
     * 去除 Context 应用实现信息，并阻止 reasoning opaque payload 进入摘要请求。
     */
    public static SummaryContextMessage message(ContextMessage message) {
        Objects.requireNonNull(message, "message");
        return new SummaryContextMessage(message.messageId(), message.turnId(), message.ordinal(),
                SummaryContextMessage.Role.valueOf(message.role().name()),
                message.blocks().stream().map(SummaryProjection::block).toList());
    }

    /**
     * 按消息顺序投影冻结历史，不能让后续应用修改影响当前模型调用。
     */
    public static List<SummaryContextMessage> messages(List<ContextMessage> messages) {
        return Objects.requireNonNull(messages, "messages").stream().map(SummaryProjection::message).toList();
    }

    /**
     * 将 ContextPolicy 的 split evidence 映射为扩展契约所需的稳定消息身份。
     */
    public static SummaryTurnSplit split(ContextPolicy.TurnSplit split) {
        Objects.requireNonNull(split, "split");
        return new SummaryTurnSplit(split.turnId(), messages(split.prefix()), messages(split.suffix()));
    }

    /**
     * 转换封闭块集合；不把提供商专有 reasoning 正文写入摘要输入。
     */
    private static SummaryContextMessage.Block block(ContextMessage.Block block) {
        if (block instanceof ContextMessage.TextBlock(String value1)) {
            return new SummaryContextMessage.Text(value1);
        }
        if (block instanceof ContextMessage.AttachmentBlock(String attachmentId)) {
            return new SummaryContextMessage.Attachment(attachmentId);
        }
        if (block instanceof ContextMessage.ToolCallBlock(String id, String name1, String arguments)) {
            return new SummaryContextMessage.ToolCall(id, name1, arguments);
        }
        if (block instanceof ContextMessage.ToolResultBlock(
                String callId, String name, ContextMessage.ToolOutput output
        )) {
            return new SummaryContextMessage.ToolResult(callId, name, output.content(),
                    output.artifactReference(), output.exitCode(), output.error(), output.promptProjection());
        }
        if (block instanceof ContextMessage.ReasoningBlock(ReasoningContent reasoning)) {
            return new SummaryContextMessage.Reasoning(reasoning.providerId(), reasoning.modelId(), reasoning.api(),
                    reasoning.upstreamModel(), reasoning.wireField());
        }
        throw new IllegalArgumentException("unsupported summary context block");
    }
}
