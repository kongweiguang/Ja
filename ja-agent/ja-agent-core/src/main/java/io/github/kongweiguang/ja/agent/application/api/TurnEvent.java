// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.api;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.time.Instant;
import java.util.Objects;

/**
 * 未提交的模型草稿流；持久事实使用 AgentEvent，客户端不能用草稿推断执行已完成。
 */
public sealed interface TurnEvent permits TurnEvent.TextDelta, TurnEvent.ReasoningSummaryDelta, TurnEvent.Reset {
    /**
     * 请求身份隔离重试前后的草稿，序号用于发现订阅遗漏而非数据库恢复游标。
     */
    Context context();

    /**
     * 流序号在同一执行内严格递增，modelRequestId 随每次真实模型请求变化。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param modelRequestId 一次真实模型请求的标识
     * @param sequence 当前记录的顺序号
     * @param occurredAt 事件发生时间
     */
    record Context(AgentIdentity identity,
                   String modelRequestId,
                   long sequence,
                   Instant occurredAt) {
        /**
         * 使用业务无关身份，不要求 Ja Thread 前缀或本地工作区。
         */
        public Context {
            Objects.requireNonNull(identity, "identity");
            modelRequestId = ContractChecks.identifier(modelRequestId, "modelRequestId");
            if (sequence < 1) {
                throw new IllegalArgumentException("stream sequence must be positive");
            }
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }

    /**
     * 正文增量只在当前请求草稿中追加，最终内容以持久消息为准。
     *
     * @param context 当前操作的上下文
     * @param text 文本内容
     */
    record TextDelta(Context context,
                     String text) implements TurnEvent {
        /**
         * 限制单片大小以配合有界队列；大模型片段由发布者分片，不能静默截断。
         */
        public TextDelta {
            Objects.requireNonNull(context, "context");
            text = ContractChecks.text(text, "text", 65_536, false);
        }
    }

    /**
     * 仅传递供应商公开的推理摘要，不承载隐藏推理或认证信息。
     *
     * @param context 当前操作的上下文
     * @param text 文本内容
     */
    record ReasoningSummaryDelta(Context context,
                                 String text) implements TurnEvent {
        /**
         * 摘要与正文共享同一执行流序列，保留实际到达顺序。
         */
        public ReasoningSummaryDelta {
            Objects.requireNonNull(context, "context");
            text = ContractChecks.text(text, "text", 65_536, false);
        }
    }

    /**
     * 丢弃 context.modelRequestId 对应的未提交草稿；不删除任何已经持久化的历史。
     *
     * @param context 当前操作的上下文
     * @param reasonCode 原因代码
     */
    record Reset(Context context,
                 String reasonCode) implements TurnEvent {
        /**
         * 发布稳定错误分类，让重试和取消都能明确撤销半截草稿。
         */
        public Reset {
            Objects.requireNonNull(context, "context");
            reasonCode = ContractChecks.identifier(reasonCode, "reasonCode");
        }
    }
}
