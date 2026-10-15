// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.model;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.SummaryTurnSplit;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 中立的结构化摘要扩展端口；其数据类型不依赖 Context 应用服务。
 */
public interface SummaryModel extends AutoCloseable {
    /**
     * 限制版本与 Thread 身份为单行有界文本，避免提示注入诊断和日志。
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
            || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 为冻结提示执行保守本地估算，不能隐式发起 Provider 调用或跳过容量准入。
     */
    ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt);

    /**
     * 对冻结提示生成一份完整摘要与对应的模型用量凭证。
     */
    Result summarize(SummaryPrompt prompt);

    /**
     * 释放本次绑定的请求资源；宿主拥有的 Provider 工厂不由该模型关闭。
     */
    @Override
    default void close() {
    }

    /**
     * 按当前执行配置和取消边界绑定一次摘要模型请求。
     */
    @FunctionalInterface
    interface Factory {
        /**
         * 为一个执行租约创建短期模型端口，不能跨请求复用失效凭据。
         */
        SummaryModel bind(TurnBinding binding);
    }

    /**
     * 摘要模型的身份、凭据选择和生命周期限制，不包含业务线程对象。
     *
     * @param threadId 所属对话的稳定标识
     * @param configuration 配置
     * @param deadline 显式的 Turn 截止时间；空值表示由宿主取消，而非单次网络超时控制总时长
     * @param cancellationToken 取消控制令牌
     */
    record TurnBinding(String threadId,
                       ModelPort.ModelConfiguration configuration,
                       Optional<Instant> deadline,
                       CancellationToken cancellationToken) {
        /**
         * 确保 Provider 配置、显式可选 Deadline 和取消令牌均由宿主显式提供。
         */
        public TurnBinding {
            threadId = required(threadId, "threadId");
            Objects.requireNonNull(configuration, "configuration");
            deadline = Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(cancellationToken, "cancellationToken");
        }

        /**
         * 隐去 API key，同时保留诊断需要的模型身份与请求截止时间。
         */
        @Override
        public String toString() {
            return "TurnBinding[threadId=" + threadId + ", providerId=" + configuration.providerId()
                   + ", modelId=" + configuration.modelId() + ", api=" + configuration.api()
                   + ", deadline=" + deadline + "]";
        }
    }

    /**
     * Provider 所需的结构化历史，所有消息类型均为领域投影而非应用 DTO。
     *
     * @param promptVersion 提示词版本
     * @param strategyVersion 策略版本
     * @param threadId 所属对话的稳定标识
     * @param previousSummary 前一次摘要（可选）
     * @param evictedMessages 被移除消息列表
     * @param splitTurn 被拆分的执行轮次（可选）
     * @param maxOutputTokens 单次模型输出的 Token 上限
     * @param violations 违规项
     */
    record SummaryPrompt(String promptVersion,
                         String strategyVersion,
                         String threadId,
                         Optional<SummaryDocument> previousSummary,
                         List<SummaryContextMessage> evictedMessages,
                         Optional<SummaryTurnSplit> splitTurn,
                         int maxOutputTokens,
                         List<String> violations) {
        /**
         * 冻结摘要输入，并要求调用方明确输出容量以免 Adapter 猜测模型窗口。
         */
        public SummaryPrompt {
            promptVersion = required(promptVersion, "promptVersion");
            strategyVersion = required(strategyVersion, "strategyVersion");
            threadId = required(threadId, "threadId");
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
     * 将模型结构化输出与实际用量一起返回，便于调用方原子写入 checkpoint。
     *
     * @param document 文档
     * @param usage 用量
     */
    record Result(SummaryDocument document,
                  CheckpointUsage usage) {
        /**
         * 不允许以 null 隐藏缺失摘要或未统计用量。
         */
        public Result {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(usage, "usage");
        }
    }
}
