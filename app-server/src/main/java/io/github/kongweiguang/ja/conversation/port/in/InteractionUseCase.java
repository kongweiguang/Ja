// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** 对话内结构化提问用例；Transport 只依赖此接口，不接触 SQLite 行或 Tool 实现。 */
public interface InteractionUseCase {
    /** 读取指定 Thread 的请求快照；不存在时返回空。 */
    Optional<InteractionSnapshot> read(String threadId, String requestId);

    /** 返回观察游标之后的事件，客户端据序号去重并在缺口时重新 read。 */
    List<InteractionEvent> observe(String threadId, long afterSequence);

    /** 注册 Thread 级轻量观察；回调只收事件，断线/缺口由调用方重新 read 对账。 */
    AutoCloseable subscribe(String threadId, Consumer<InteractionEvent> observer);

    /** 保存可恢复草稿，正文由调用方按公共 JSON 约束编码。 */
    InteractionDraft saveDraft(String threadId, String requestId, List<InteractionAnswer> answers,
                               int page, boolean collapsed, long expectedRevision,
                               String idempotencyKey, Instant occurredAt);

    /** 以 request revision、答案幂等键原子提交所有问题的答案。 */
    InteractionRequest respond(String threadId, String requestId, long expectedRevision,
                               List<InteractionAnswer> answers, String idempotencyKey,
                               Instant occurredAt);

    /** RPC 回答可携带当前连接的 Turn 事件路由；断线时仍由持久快照恢复，不依赖旧连接存活。 */
    default InteractionRequest respond(String threadId, String requestId, long expectedRevision,
                                       List<InteractionAnswer> answers, String idempotencyKey,
                                       Instant occurredAt, TurnEventSink resumeSink) {
        return respond(threadId, requestId, expectedRevision, answers, idempotencyKey, occurredAt);
    }

    /** 显式取消或替代待回答请求；不因超时自动同意。 */
    io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest cancel(
            String threadId, String requestId, long expectedRevision, InteractionStatus status,
            String idempotencyKey, Instant occurredAt);

    /** 入站边界使用的稳定错误闭集，禁止 Transport 依赖 application 实现异常。 */
    enum Failure {
        /** 请求或观察资源不存在。 */ NOT_FOUND,
        /** revision 已变化，需要重新读取。 */ REVISION_CONFLICT,
        /** 当前生命周期不允许该动作。 */ INVALID_STATE,
        /** 答案或参数违反领域约束。 */ INVALID,
        /** 持久化或恢复设施暂不可用。 */ UNAVAILABLE
    }

    /** 无堆栈 Interaction 异常只跨越 UseCase 边界携带机器可读 code。 */
    final class InteractionException extends RuntimeException {
        private final Failure failure;

        /** 使用无堆栈稳定异常跨越应用边界，避免把内部实现细节写入 RPC。 */
        private InteractionException(Failure failure) {
            super("interaction operation failed", null, false, false);
            this.failure = java.util.Objects.requireNonNull(failure, "failure");
        }

        /** 将领域错误码包装成 Transport 可映射的异常。 */
        public static InteractionException of(Failure failure) {
            return new InteractionException(failure);
        }

        /** 返回机器可读闭集错误，调用方据此选择重读或保留输入。 */
        public Failure code() {
            return failure;
        }
    }
}
