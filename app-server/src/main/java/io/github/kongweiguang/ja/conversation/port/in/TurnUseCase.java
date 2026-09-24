// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 定义 Turn 接纳、取消和有界关闭用例，使入站 adapter 不依赖具体服务类。
 */
public interface TurnUseCase extends DeadlineCloseable {
    /**
     * 接纳稳定 Operation 请求；模型、Prompt 与 Tool 环境在每次 Provider 安全点重新解析。
     */
    Accepted start(TurnStartRequest request, TurnEventSink sink);

    /** 在已有问题下创建隐藏 Turn；source 由存储重新解析并在准入事务中校验。 */
    Accepted continueQuestion(InternalTurnStartRequest request, TurnEventSink sink);

    /** 仅重答当前路径最后一个失败问题，并在新 USER Turn 准入时切换旧后缀。 */
    Accepted reask(TurnStartRequest request, String sourceMessageId, TurnEventSink sink);

    /** 显式恢复一个 SUSPENDED Turn；成功保持原 turnId 并重新进入同 Thread FIFO。 */
    default Accepted resume(String turnId, long expectedThreadRevision, TurnEventSink sink) {
        throw new UnsupportedOperationException("turn resume is unavailable");
    }

    /**
     * 提交已显示在原 Tool 详情上的恢复裁决。调用方只提供当前 Thread/恢复 revision 与显式选择，
     * 文件核实、执行游标和 ToolResult 配对仍由 Java Kernel 的存储事务唯一决定。
     */
    default ToolRecoveryResponse respondToolRecovery(ToolRecoveryRequest request) {
        throw new UnsupportedOperationException("tool recovery response is unavailable");
    }

    /**
     * 仅凭全局唯一 Turn ID 提交最高优先级停止意图；当前版本由持久化 owner 在事务内读取。
     */
    CancelResult cancel(String turnId);

    /** 默认把消息作为普通后续输入加入活动 Turn 的权威队列。 */
    default InputMutation enqueueInput(String turnId, UserContent content) {
        throw new UnsupportedOperationException("input queue is unavailable");
    }

    /** 问答期间的新指令可携带当前连接出口，替代旧问题后继续原 Turn。 */
    default InputMutation enqueueInput(String turnId, UserContent content, TurnEventSink sink) {
        return enqueueInput(turnId, content);
    }

    /** 把指定条目按点击顺序提升为下一个安全点的 Steering。 */
    default InputMutation prioritizeInput(String turnId, String inputId, long expectedInputRevision) {
        throw new UnsupportedOperationException("input queue prioritization is unavailable");
    }

    /** 编辑尚未消费的条目完整内容，并清除旧问题等待重新校验。 */
    default InputMutation updateInput(String turnId, String inputId, long expectedInputRevision,
                                      UserContent content) {
        throw new UnsupportedOperationException("input queue update is unavailable");
    }

    /** 删除尚未消费的条目。 */
    default InputMutation deleteInput(String turnId, String inputId, long expectedInputRevision) {
        throw new UnsupportedOperationException("input queue deletion is unavailable");
    }

    /**
     * 在组合关闭前停止新 Turn 接纳。
     */
    void stopAccepting();

    /**
     * 在给定预算内等待所有已经接纳的 Turn 退出。
     */
    boolean awaitQuiescence(Duration timeout);

    /**
     * 接纳回执包含持久化 revision，并允许调用方在完成时释放配置代际。
     */
    record Accepted(String threadId, String turnId, long threadRevision, boolean queued,
                    CompletionStage<TurnResult> completion) {
        /**
         * 拒绝未持久化的负 revision 和缺失的完成阶段。
         */
        public Accepted {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (threadRevision < 0 || completion == null) throw new IllegalArgumentException("invalid acceptance");
        }
    }

    /**
     * 取消回执表达提交时观察到的状态，不承诺异步清理已经结束。
     */
    record CancelResult(boolean accepted, String turnId, TurnState status, long threadRevision) {
        /**
         * 保证取消关联和值域在跨 adapter 返回前完整。
         */
        public CancelResult {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(status, "status");
            if (threadRevision < 0) throw new IllegalArgumentException("invalid cancel result");
        }
    }

    /** 所有队列 mutation 统一返回提交后的权威全量投影。 */
    record InputMutation(boolean accepted, String inputId, InputQueue inputQueue) {
        /** 响应不能只返回局部补丁，否则 ACK 与事件乱序时无法确定性收敛。 */
        public InputMutation {
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (!accepted) throw new IllegalArgumentException("successful input mutation required");
        }
    }

    /** 恢复请求不包含路径、哈希、运行时配置或 Tool 参数，避免客户端扩张文件读取或工具路由权限。 */
    record ToolRecoveryRequest(String turnId, String callId, long expectedThreadRevision,
                               long expectedRecoveryRevision, ToolRecoveryDisposition disposition,
                               String idempotencyKey) {
        /** 将 transport 的闭集选择映射为 domain 意图，并拒绝客户端伪造版本或空幂等身份。 */
        public ToolRecoveryRequest {
            if (turnId == null || callId == null || expectedThreadRevision < 0 || expectedRecoveryRevision < 1
                    || disposition == null || idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("invalid Tool recovery request");
            }
        }
    }

    /** 用户只能明确重试或跳过；VERIFIED 是服务端确定性文件比较的内部结论。 */
    enum ToolRecoveryDisposition {
        /** 用户接受重复副作用风险，服务端为原未知调用创建新的执行尝试。 */
        RETRY,
        /** 用户明确不执行该项，模型随后收到包含未知事实的跳过结果。 */
        SKIP
    }

    /** 裁决回执保留新 revision，供 handler 在最后一项后安全复用唯一的 Resume 入口。 */
    record ToolRecoveryResponse(String threadId, String turnId, long threadRevision,
                                ToolRecoveryDisposition disposition, boolean changed) {
        /** response 不携带执行游标，既不让 Rust 缓存也不让前端根据它自行续跑。 */
        public ToolRecoveryResponse {
            if (threadId == null || turnId == null || threadRevision < 0 || disposition == null) {
                throw new IllegalArgumentException("invalid Tool recovery response");
            }
        }
    }

    /** Queue mutation 只向 Transport 暴露稳定错误类别。 */
    enum InputMutationFailure {
        /** Turn 不存在、未活动或不再接收输入。 */
        TURN_NOT_FOUND,
        /** 单 Turn 队列达到数量或字节上限。 */
        QUEUE_FULL,
        /** 指定条目不存在或已经消费/删除。 */
        INPUT_NOT_FOUND,
        /** 条目 revision 已过期。 */
        CONFLICT,
        /** Workspace 引用在准入或编辑时已经失效。 */
        WORKSPACE_REFERENCE_INVALID,
        /** Skill ID 不属于本消息冻结的已启用目录。 */
        SKILL_UNAVAILABLE,
        /** Skill 身份有效，但 SKILL.md 实时加载失败。 */
        SKILL_LOAD_FAILED,
        /** 单条结构化 content 或队列总字节超过硬上限。 */
        CONTENT_TOO_LARGE
    }

    /** 首轮 content 准入只暴露稳定类别，文件系统与 Skill 来源细节不会进入 RPC。 */
    enum ContentFailure {
        /** Workspace 引用身份、路径 containment 或目标类型校验失败。 */
        WORKSPACE_REFERENCE_INVALID,
        /** Skill ID 不属于当前冻结且已启用的配置代际。 */
        SKILL_UNAVAILABLE,
        /** Skill 身份有效，但当前 SKILL.md 无法安全完整读取。 */
        SKILL_LOAD_FAILED,
        /** 单条结构化 content 超过公开硬上限。 */
        CONTENT_TOO_LARGE
    }

    /** 首轮准入失败发生在持久化与模型调用前，因此客户端可以原样保留草稿和 Chip。 */
    final class ContentValidationException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final ContentFailure failure;

        /** 只保存稳定失败类别。 */
        private ContentValidationException(ContentFailure failure) {
            super("user content validation failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** application 层通过闭集工厂映射 Workspace 与 Skill 端口失败。 */
        public static ContentValidationException of(ContentFailure failure) {
            return new ContentValidationException(failure);
        }

        /** 返回 transport 可安全穷举的失败类别。 */
        public ContentFailure failure() {
            return failure;
        }
    }

    /** 无堆栈应用异常阻止 SQL 与持久化状态越过 RPC 边界。 */
    final class InputMutationException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final InputMutationFailure failure;

        /** 只保存稳定失败类别。 */
        private InputMutationException(InputMutationFailure failure) {
            super("input queue mutation failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 应用服务通过闭集工厂映射 Repository 失败。 */
        public static InputMutationException of(InputMutationFailure failure) {
            return new InputMutationException(failure);
        }

        /** 返回 transport 可安全穷举的失败类别。 */
        public InputMutationFailure failure() {
            return failure;
        }
    }

    /**
     * 取消查找失败只保留入站错误映射需要的稳定类别。
     */
    enum CancelFailure {
        /** 请求引用的 Turn 不存在或已无法归属到运行期 owner。 */
        TURN_NOT_FOUND
    }

    /** Resume 的稳定失败闭集，Transport 只映射这些类别而不解析异常文本。 */
    enum ResumeFailure {
        /** Turn 不存在、不是 SUSPENDED，或持久执行状态已经不可继续。 */
        TURN_NOT_RESUMABLE,
        /** 同一 Thread 更早的非终态 Turn 尚未处理，当前 Turn 不能越过 FIFO 恢复。 */
        TURN_RESUME_ORDER_CONFLICT,
        /** 当前最早 Tool 结果未知，自动文件核实无法确认，需使用原 Tool 详情中的明确裁决。 */
        RECOVERY_REQUIRED
    }

    /** 继续/编辑问题不满足路径状态门时使用的稳定错误类别。 */
    enum QuestionRecoveryFailure {
        /** 当前消息已失去重答资格，避免覆盖已变化的历史后缀。 */
        NOT_REASKABLE
    }

    /** 无堆栈错误避免把 SQL 与路径校验细节泄漏到 JA-RPC。 */
    final class QuestionRecoveryException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final QuestionRecoveryFailure failure;

        /** 仅保存协议映射需要的闭集失败类型。 */
        private QuestionRecoveryException(QuestionRecoveryFailure failure) {
            super("question recovery failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 由应用服务将持久化资格门失败转换为 transport 可穷举异常。 */
        public static QuestionRecoveryException of(QuestionRecoveryFailure failure) {
            return new QuestionRecoveryException(failure);
        }

        /** 返回稳定失败类别。 */
        public QuestionRecoveryFailure failure() { return failure; }
    }

    /** 无堆栈 Resume 异常避免运行时、路径和持久化细节越过入站边界。 */
    final class TurnResumeException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final ResumeFailure failure;

        /** 只保留稳定失败类别。 */
        private TurnResumeException(ResumeFailure failure) {
            super("turn resume failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 应用层通过闭集工厂构造，Adapter 不得制造任意内部状态。 */
        public static TurnResumeException of(ResumeFailure failure) {
            return new TurnResumeException(failure);
        }

        /** 返回稳定映射类别。 */
        public ResumeFailure failure() {
            return failure;
        }
    }

    /**
     * 无堆栈应用异常防止内部存储与并发细节越过 transport。
     */
    final class TurnCancellationException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final CancelFailure failure;

        /**
         * 只保存稳定失败类别，禁止携带查找键或持久化消息。
         */
        private TurnCancellationException(CancelFailure failure) {
            super("turn cancellation failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 由应用实现创建分类异常，入站 adapter 不能构造任意内部失败。
         */
        public static TurnCancellationException of(CancelFailure failure) {
            return new TurnCancellationException(failure);
        }

        /**
         * 返回用于稳定错误目录映射的取消失败类别。
         */
        public CancelFailure failure() {
            return failure;
        }
    }
}
