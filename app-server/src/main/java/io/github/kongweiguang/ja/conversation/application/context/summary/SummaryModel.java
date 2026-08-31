// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 中立的结构化摘要模型端口，禁止上下文应用层依赖具体 SDK 或传输格式。
 */
@FunctionalInterface
public interface SummaryModel {
    /**
     * 使用 Provider 官方接口计量冻结摘要提示；实现必须让紧随其后的 summarize 复用同一 envelope。
     */
    default ModelPort.InputTokenCount countInputTokens(SummaryPrompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        throw new ModelPort.TokenCountUnavailableException(null);
    }

    /**
     * 对冻结提示执行一次摘要推理；实现需返回可校验文档和本次 Token 用量。
     */
    SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt);

    /**
     * 按当前 Turn 的模型配置、Deadline 和取消令牌绑定摘要模型实例。
     */
    @FunctionalInterface
    interface Factory {
        /**
         * 创建仅服务该 Turn 的摘要端口，禁止复用失效凭据或跨 Thread 状态。
         */
        SummaryModel bind(TurnBinding binding);
    }

    /**
     * 固定摘要调用所属 Thread、模型配置和生命周期边界，供调用前后双重校验。
     */
    record TurnBinding(
            String threadId,
            ModelPort.ModelConfiguration configuration,
            Instant deadline,
            CancellationToken cancellationToken) {
        /**
         * 校验并冻结 Turn 绑定，确保取消与 Deadline 不会被空依赖静默绕过。
         */
        public TurnBinding {
            threadId = SummaryModel.required(threadId, "threadId");
            configuration = Objects.requireNonNull(configuration, "configuration");
            deadline = Objects.requireNonNull(deadline, "deadline");
            cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        }

        /**
         * 仅输出可诊断的配置身份和 Deadline，刻意不暴露凭据或完整配置。
         */
        @Override
        public String toString() {
            return "TurnBinding[threadId=" + threadId + ", providerId=" + configuration.providerId()
                   + ", modelId=" + configuration.modelId() + ", configGeneration="
                   + configuration.configGeneration() + ", deadline=" + deadline + "]";
        }
    }

    /**
     * 发送给摘要模型的版本化结构，携带累积摘要、淘汰前缀和 split-turn 语义。
     */
    record SummaryPrompt(
            String promptVersion,
            String strategyVersion,
            String threadId,
            Optional<SummaryDocument> previousSummary,
            List<ContextMessage> evictedMessages,
            Optional<ContextPolicy.TurnSplit> splitTurn,
            int maxOutputTokens,
            List<String> violations) {
        /**
         * 冻结模型输入并要求正输出预算，使 Adapter 无需推断缺省容量。
         */
        public SummaryPrompt {
            promptVersion = SummaryModel.required(promptVersion, "promptVersion");
            strategyVersion = SummaryModel.required(strategyVersion, "strategyVersion");
            threadId = SummaryModel.required(threadId, "threadId");
            previousSummary = Objects.requireNonNull(previousSummary, "previousSummary");
            evictedMessages = List.copyOf(Objects.requireNonNull(evictedMessages, "evictedMessages"));
            splitTurn = Objects.requireNonNull(splitTurn, "splitTurn");
            violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
            if (maxOutputTokens <= 0) {
                throw new IllegalArgumentException("maxOutputTokens must be positive");
            }
        }

    }

    /**
     * 限制版本和 Thread 标识为单行有界文本，避免污染模型提示与诊断日志。
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
            || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
