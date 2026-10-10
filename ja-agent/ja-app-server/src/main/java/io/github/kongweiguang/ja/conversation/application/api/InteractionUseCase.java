// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 对话内结构化提问用例；Transport 只依赖此接口，不接触 SQLite 行或 Tool 实现。
 */
public interface InteractionUseCase {
    /**
     * 读取指定 Thread 的请求快照；不存在时返回空。
     */
    Optional<InteractionSnapshot> read(String threadId, String interactionId);

    /**
     * 返回观察游标之后的事件，客户端据序号去重并在缺口时重新 read。
     */
    List<InteractionEvent> observe(String threadId, long afterSequence);

    /**
     * 注册 Thread 级轻量观察；回调只收事件，断线/缺口由调用方重新 read 对账。
     */
    AutoCloseable subscribe(String threadId, Consumer<InteractionEvent> observer);

    /**
     * 保存可恢复草稿，正文由调用方按公共 JSON 约束编码。
     */
    InteractionDraft saveDraft(String threadId, String interactionId, List<InteractionAnswer> answers,
                               int page, boolean collapsed, long expectedDraftRevision,
                               String operationKey, Instant occurredAt);

    /**
     * 以 expectedInteractionRevision、答案幂等键原子提交所有问题的答案。
     */
    InteractionRequest respond(String threadId, String interactionId, long expectedInteractionRevision,
                               List<InteractionAnswer> answers, String operationKey,
                               Instant occurredAt);

    /**
     * 将当前连接的事件路由传入答案恢复流程；实现必须传播 sink，不能静默降级到无连接执行。
     */
    InteractionRequest respond(String threadId, String interactionId, long expectedInteractionRevision,
                               List<InteractionAnswer> answers, String operationKey,
                               Instant occurredAt, ConversationTurnEventSink resumeSink);

    /**
     * 显式取消或替代待回答请求；不因超时自动同意。
     */
    io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest cancel(
            String threadId, String interactionId, long expectedInteractionRevision, InteractionStatus status,
            String operationKey, Instant occurredAt);

    /**
     * 入站边界使用的稳定错误闭集，禁止 Transport 依赖 application 实现异常。
     */
    enum Failure {
        /**
         * 请求或观察资源不存在。
         */
        NOT_FOUND,
        /**
         * revision 已变化，需要重新读取。
         */
        REVISION_CONFLICT,
        /**
         * 当前生命周期不允许该动作。
         */
        INVALID_STATE,
        /**
         * 答案或参数违反领域约束。
         */
        INVALID,
        /**
         * 持久化或恢复设施暂不可用。
         */
        UNAVAILABLE
    }

    /**
     * 无堆栈 Interaction 异常只跨越 UseCase 边界携带机器可读 code。
     */
    final class InteractionException extends RuntimeException {
        private final Failure failure;

        /**
         * 使用无堆栈稳定异常跨越应用边界，避免把内部实现细节写入 RPC。
         */
        private InteractionException(Failure failure) {
            super("interaction operation failed", null, false, false);
            this.failure = java.util.Objects.requireNonNull(failure, "failure");
        }

        /**
         * 将领域错误码包装成 Transport 可映射的异常。
         */
        public static InteractionException of(Failure failure) {
            return new InteractionException(failure);
        }

        /** 保留 Core 冲突供本地诊断；对外消息仍只暴露稳定的 Interaction 分类。 */
        public static InteractionException of(Failure failure, Throwable cause) {
            return new InteractionException(failure, cause);
        }

        /** 机器可读错误保持无堆栈，同时允许已分类的内部原因留在 cause 链中。 */
        private InteractionException(Failure failure, Throwable cause) {
            super("interaction operation failed", java.util.Objects.requireNonNull(cause, "cause"), false, false);
            this.failure = java.util.Objects.requireNonNull(failure, "failure");
        }

        /**
         * 返回机器可读闭集错误，调用方据此选择重读或保留输入。
         */
        public Failure code() {
            return failure;
        }
    }
}
