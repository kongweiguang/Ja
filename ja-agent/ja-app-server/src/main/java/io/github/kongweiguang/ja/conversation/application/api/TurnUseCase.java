// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.api;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 定义 Turn 接纳、取消和有界关闭用例，使入站 adapter 不依赖具体服务类。
 */
public interface TurnUseCase extends DeadlineCloseable {
    /** Plan ACL 使用的隐藏 Turn 准入；具体实现仍由 Conversation owner 持有。 */
    Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                               ConversationTurnEventSink sink, NativeExecutionSnapshot inherited);

    /** Plan 暂停只保留可恢复游标，不让调用方直接接触 Engine。 */
    CompletionStage<Void> suspendPlanRun(String turnId);

    /** 绑定隐藏 Turn 的唯一恢复回调，保留 Conversation 提供的权威 TurnResult。 */
    void registerResumeContinuation(String turnId, Consumer<CompletionStage<TurnResult>> continuation);

    /** 清理尚未消费的隐藏 Turn 恢复回调。 */
    void clearResumeContinuation(String turnId);

    /** Plan 恢复可显式传递已认证原生快照。 */
    default Accepted resume(String turnId, long expectedThreadRevision,
                            ConversationTurnEventSink sink, NativeExecutionSnapshot inherited) {
        throw new UnsupportedOperationException("turn resume with native context is unavailable");
    }

    /**
     * 接纳稳定 Operation 请求；模型、Prompt 与 Tool 环境在每次 Provider 安全点重新解析。
     */
    Accepted start(TurnStartRequest request, ConversationTurnEventSink sink);

    /**
     * 带持久客户端提交身份的启动；实现必须在同一事务绑定 Turn 准入和回执。
     */
    default Accepted start(TurnStartRequest request, ConversationTurnEventSink sink,
                           String operationKey, String requestFingerprint) {
        throw new UnsupportedOperationException("client operation admission is unavailable");
    }

    /**
     * 在已有问题下创建隐藏 Turn；source 由存储重新解析并在准入事务中校验。
     */
    Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink);

    /**
     * 继续回复重试只复用已提交 Turn，不能再生成另一条隐藏执行链。
     */
    default Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink,
                                      String operationKey, String requestFingerprint) {
        throw new UnsupportedOperationException("client operation admission is unavailable");
    }

    /**
     * 仅重答当前路径最后一个失败问题，并在新 USER Turn 准入时切换旧后缀。
     */
    Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink);

    /**
     * 重答重试只复用原路径切换结果，不能再次切走已改变的会话后缀。
     */
    default Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink,
                           String operationKey, String requestFingerprint) {
        throw new UnsupportedOperationException("client operation admission is unavailable");
    }

    /**
     * 返回持久提交身份；空值表示尚无可证实的业务提交，调用方不得据此盲目重发副作用。
     */
    default Optional<ClientOperationReceipt> readClientOperation(String operationKey) {
        throw new UnsupportedOperationException("client operation read is unavailable");
    }

    /**
     * 查询目标 Thread 当前唯一的非终态 Turn，供业务消息选择安全的 Engine 投递入口。
     */
    default Optional<ActiveTurn> findActiveTurn(String threadId) {
        throw new UnsupportedOperationException("active turn query is unavailable");
    }

    /**
     * 返回当前进程实际持有执行 owner 的 Turn；持久 RUNNING 本身不能证明仍有执行者。
     */
    Optional<String> locallyActiveTurnId(String threadId);

    /**
     * 按准确 Thread/Turn 身份查询当前进程是否持有该句柄，避免较早 owner 掩盖最新失主 Turn。
     */
    boolean locallyOwnsTurn(String threadId, String turnId);

    /**
     * 显式恢复一个暂停或失去本地 owner 的非终态 Turn；成功保持原 turnId 并进入 Engine FIFO。
     */
    default Accepted resume(String turnId, long expectedThreadRevision, ConversationTurnEventSink sink) {
        throw new UnsupportedOperationException("turn resume is unavailable");
    }

    /**
     * 为启动时仍等待审批的 Turn 仅接入持久事件观察，不准入执行、不取得运行资源或恢复 Driver。
     * 返回 false 表示 Turn 已移除或状态过期、已有本地执行/观察者、正在关停或并发操作占用该身份。
     *
     * @author kongweiguang
     */
    boolean observeApprovalWait(String turnId, ConversationTurnEventSink sink);

    /**
     * 提交已显示在原 Tool 详情上的恢复裁决。调用方只提供当前 Thread/恢复 revision 与显式选择，
     * 文件核实、执行游标和 ToolResult 配对仍由 Java Kernel 的存储事务唯一决定。
     */
    default ToolRecoveryResponse respondToolRecovery(ToolRecoveryRequest request) {
        throw new UnsupportedOperationException("tool recovery response is unavailable");
    }

    /**
     * 提交最高优先级停止意图并返回终态凭据；受理结果保留给现有 RPC，completion 等待权威投影。
     */
    CancelResult cancel(String turnId);

    /**
     * 问答期间的新指令携带连接出口，替代旧问题后继续原 Turn。
     */
    default InputMutation enqueueInput(String turnId, UserContent content, InputKind kind,
                                       ConversationTurnEventSink sink,
                                       String operationKey, String requestFingerprint) {
        throw new UnsupportedOperationException("input queue is unavailable");
    }

    /**
     * 将已持久的 Ja Task mailbox 消息绑定到活动 Turn 的 MESSAGE 队列项，不伪装成用户手输。
     */
    default InputMutation enqueueTaskMessage(String turnId, AgentInputCommand.Enqueue command) {
        throw new UnsupportedOperationException("task message queue is unavailable");
    }

    /**
     * 只读外部入队的同事务回执；未知状态必须由客户端保留输入等待核实。
     */
    default Optional<InputOperationReceipt> readInputOperation(String operationKey) {
        return Optional.empty();
    }

    /**
     * 把指定条目按点击顺序提升为下一个安全点的 Steering。
     */
    default InputMutation prioritizeInput(String turnId, String inputId, long expectedInputRevision) {
        throw new UnsupportedOperationException("input queue prioritization is unavailable");
    }

    /**
     * 编辑尚未消费的条目完整内容，并清除旧问题等待重新校验。
     */
    default InputMutation updateInput(String turnId, String inputId, long expectedInputRevision,
                                      UserContent content) {
        throw new UnsupportedOperationException("input queue update is unavailable");
    }

    /**
     * 删除尚未消费的条目。
     */
    default InputMutation deleteInput(String turnId, String inputId, long expectedInputRevision) {
        throw new UnsupportedOperationException("input queue deletion is unavailable");
    }

    /**
     * 在组合关闭前停止新 Turn 接纳。
     */
    void stopAccepting();

    /**
     * 在准入写屏障内确认无活动 owner 并停止新接纳，避免关闭检查后插入新执行。
     */
    boolean stopAcceptingIfQuiescent();

    /**
     * 在给定预算内等待已接纳的 Turn 退出；零预算只取即时快照，不等待。
     */
    boolean awaitQuiescence(Duration timeout);

    /**
     * 提交方式由用户按键冻结，存储层在同一事务决定安全点消费顺序。
     */
    enum InputKind {
        /**
         * 当前 Turn 的下一个安全点优先消费。
         */
        STEERING,
        /**
         * 排在当前任务之后，按提交顺序消费。
         */
        FOLLOW_UP
    }

    /**
     * 用户只能明确重试或跳过；VERIFIED 是服务端确定性文件比较的内部结论。
     */
    enum ToolRecoveryDisposition {
        /**
         * 用户接受重复副作用风险，服务端为原未知调用创建新的执行尝试。
         */
        RETRY,
        /**
         * 用户明确不执行该项，模型随后收到包含未知事实的跳过结果。
         */
        SKIP
    }

    /**
     * Queue mutation 只向 Transport 暴露稳定错误类别。
     */
    enum InputMutationFailure {
        /**
         * Turn 不存在、未活动或不再接收输入。
         */
        TURN_NOT_FOUND,
        /**
         * 单 Turn 队列达到数量或字节上限。
         */
        QUEUE_FULL,
        /**
         * 指定条目不存在或已经消费/删除。
         */
        INPUT_NOT_FOUND,
        /**
         * 条目 revision 已过期。
         */
        CONFLICT,
        /**
         * Workspace 引用在准入或编辑时已经失效。
         */
        WORKSPACE_REFERENCE_INVALID,
        /**
         * Skill ID 不属于本消息冻结的已启用目录。
         */
        SKILL_UNAVAILABLE,
        /**
         * Skill 身份有效，但 SKILL.md 实时加载失败。
         */
        SKILL_LOAD_FAILED,
        /**
         * 单条结构化 content 或队列总字节超过硬上限。
         */
        CONTENT_TOO_LARGE
    }

    /**
     * 首轮 content 准入只暴露稳定类别，文件系统与 Skill 来源细节不会进入 RPC。
     */
    enum ContentFailure {
        /**
         * Workspace 引用身份、路径 containment 或目标类型校验失败。
         */
        WORKSPACE_REFERENCE_INVALID,
        /**
         * Skill ID 不属于当前冻结且已启用的配置代际。
         */
        SKILL_UNAVAILABLE,
        /**
         * Skill 身份有效，但当前 SKILL.md 无法安全完整读取。
         */
        SKILL_LOAD_FAILED,
        /**
         * 单条结构化 content 超过公开硬上限。
         */
        CONTENT_TOO_LARGE
    }

    /**
     * 取消查找失败只保留入站错误映射需要的稳定类别。
     */
    enum CancelFailure {
        /**
         * 请求引用的 Turn 不存在或已无法归属到运行期 owner。
         */
        TURN_NOT_FOUND
    }

    /**
     * Resume 的稳定失败闭集，Transport 只映射这些类别而不解析异常文本。
     */
    enum ResumeFailure {
        /**
         * Turn 不存在、不是 SUSPENDED，或持久执行状态已经不可继续。
         */
        TURN_NOT_RESUMABLE,
        /**
         * 同一 Thread 更早的非终态 Turn 尚未处理，当前 Turn 不能越过 FIFO 恢复。
         */
        TURN_RESUME_ORDER_CONFLICT,
        /**
         * 当前最早 Tool 结果未知，自动文件核实无法确认，需使用原 Tool 详情中的明确裁决。
         */
        RECOVERY_REQUIRED
    }

    /**
     * 继续/编辑问题不满足路径状态门时使用的稳定错误类别。
     */
    enum QuestionRecoveryFailure {
        /**
         * 当前消息已失去重答资格，避免覆盖已变化的历史后缀。
         */
        NOT_REASKABLE
    }

    /**
     * 含有会话版本的活动 Turn 快照；调用者只能据此路由，不能据此自行派发执行。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param state 当前状态
     * @param threadRevision 对话修订版本
     * @param mutationVersion 状态变更版本，用于并发提交校验
     */
    record ActiveTurn(String threadId,
                      String turnId,
                      TurnState state,
                      long threadRevision,
                      long mutationVersion) {
        /**
         * 拒绝不完整身份与负版本，避免业务协调器把陈旧或无主状态当作有效执行者。
         */
        public ActiveTurn {
            if (threadId == null || !threadId.startsWith("thr_") || turnId == null
                || !turnId.startsWith("turn_") || state == null
                || threadRevision < 0 || mutationVersion < 0 || state.terminal()) {
                throw new IllegalArgumentException("invalid active Turn snapshot");
            }
        }
    }

    /**
     * 回执不包含用户正文或队列快照，避免断线查询扩大可见数据。
     *
     * @param operationKey 操作标识
     * @param fingerprint 当前内容或配置的校验指纹
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param inputId 输入标识
     * @param kind 类型分类
     */
    record InputOperationReceipt(String operationKey,
                                 String fingerprint,
                                 String threadId,
                                 String turnId,
                                 String inputId,
                                 InputKind kind) {
    }

    /**
     * 接纳回执包含持久化 revision，并允许调用方在完成时释放配置代际。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadRevision 对话修订版本
     * @param queued 是否已进入队列
     * @param completion 执行完成时的异步信号
     */
    record Accepted(String threadId,
                    String turnId,
                    long threadRevision,
                    boolean queued,
                    CompletionStage<TurnResult> completion) {
        /**
         * 拒绝未持久化的负 revision 和缺失的完成阶段。
         */
        public Accepted {
            Objects.requireNonNull(threadId, "threadId");
            Objects.requireNonNull(turnId, "turnId");
            if (threadRevision < 0 || completion == null) {
                throw new IllegalArgumentException("invalid acceptance");
            }
        }
    }

    /**
     * 取消回执表达提交时观察到的状态，并携带等待清理及终态投影的唯一阶段。
     *
     * @param accepted 是否已受理
     * @param turnId 本次可恢复执行的稳定标识
     * @param status 当前状态分类
     * @param threadRevision 对话修订版本
     * @param completion 终态持久化和投影完成信号
     */
    record CancelResult(boolean accepted,
                        String turnId,
                        TurnState status,
                        long threadRevision,
                        CompletionStage<TurnResult> completion) {
        /**
         * 保证取消关联和值域在跨 adapter 返回前完整。
         */
        public CancelResult {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(completion, "completion");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("invalid cancel result");
            }
        }
    }

    /**
     * 所有队列 mutation 统一返回提交后的权威全量投影。
     *
     * @param accepted 是否已受理
     * @param inputId 输入标识
     * @param inputQueue 输入队列
     */
    record InputMutation(boolean accepted,
                         String inputId,
                         InputQueue inputQueue) {
        /**
         * 响应不能只返回局部补丁，否则 ACK 与事件乱序时无法确定性收敛。
         */
        public InputMutation {
            Objects.requireNonNull(inputId, "inputId");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (!accepted) {
                throw new IllegalArgumentException("successful input mutation required");
            }
        }
    }

    /**
     * 恢复请求不包含路径、哈希、运行时配置或 Tool 参数，避免客户端扩张文件读取或工具路由权限。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     * @param expectedThreadRevision 预期对话修订版本
     * @param expectedRecoveryRevision 预期恢复修订版本
     * @param disposition 处置方式
     * @param operationKey 重复提交同一业务操作时复用的幂等键
     */
    record ToolRecoveryRequest(String turnId,
                               String callId,
                               long expectedThreadRevision,
                               long expectedRecoveryRevision,
                               ToolRecoveryDisposition disposition,
                               String operationKey) {
        /**
         * 保留原生 callId，同时映射闭集裁决并拒绝客户端伪造版本或空幂等身份。
         */
        public ToolRecoveryRequest {
            if (turnId == null || expectedThreadRevision < 0 || expectedRecoveryRevision < 1
                || disposition == null || operationKey == null || operationKey.isBlank()) {
                throw new IllegalArgumentException("invalid Tool recovery request");
            }
            callId = JaToolCallId.require(callId);
        }
    }

    /**
     * 裁决回执保留新 revision，供 handler 在最后一项后安全复用唯一的 Resume 入口。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param threadRevision 对话修订版本
     * @param disposition 处置方式
     * @param changed 是否发生变化
     */
    record ToolRecoveryResponse(String threadId,
                                String turnId,
                                long threadRevision,
                                ToolRecoveryDisposition disposition,
                                boolean changed) {
        /**
         * response 不携带执行游标，既不让 Rust 缓存也不让前端根据它自行续跑。
         */
        public ToolRecoveryResponse {
            if (threadId == null || turnId == null || threadRevision < 0 || disposition == null) {
                throw new IllegalArgumentException("invalid Tool recovery response");
            }
        }
    }

    /**
     * 首轮准入失败发生在持久化与模型调用前，因此客户端可以原样保留草稿和 Chip。
     */
    final class ContentValidationException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final ContentFailure failure;

        /**
         * 只保存稳定失败类别与进程内 cause，避免把底层错误文本交给 transport。
         */
        private ContentValidationException(ContentFailure failure, Throwable cause) {
            super("user content validation failed", cause, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * application 层通过闭集工厂映射 Workspace 与 Skill 端口失败。
         */
        public static ContentValidationException of(ContentFailure failure) {
            return of(failure, null);
        }

        /**
         * 保留底层引用校验故障供进程内排查，外部仍只看到稳定失败枚举。
         */
        public static ContentValidationException of(ContentFailure failure, Throwable cause) {
            return new ContentValidationException(failure, cause);
        }

        /**
         * 返回 transport 可安全穷举的失败类别。
         */
        public ContentFailure failure() {
            return failure;
        }
    }

    /**
     * 无堆栈应用异常阻止 SQL 与持久化状态越过 RPC 边界。
     */
    final class InputMutationException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final InputMutationFailure failure;

        /**
         * 只保存稳定失败类别与进程内 cause，不暴露仓储错误消息。
         */
        private InputMutationException(InputMutationFailure failure, Throwable cause) {
            super("input queue mutation failed", cause, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 应用服务通过闭集工厂映射 Repository 失败。
         */
        public static InputMutationException of(InputMutationFailure failure) {
            return of(failure, null);
        }

        /**
         * 保留队列竞争或容量故障的原始 cause，transport 只映射稳定分类。
         */
        public static InputMutationException of(InputMutationFailure failure, Throwable cause) {
            return new InputMutationException(failure, cause);
        }

        /**
         * 返回 transport 可安全穷举的失败类别。
         */
        public InputMutationFailure failure() {
            return failure;
        }
    }

    /**
     * 无堆栈错误避免把 SQL 与路径校验细节泄漏到 JA-RPC。
     */
    final class QuestionRecoveryException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final QuestionRecoveryFailure failure;

        /**
         * 仅保存协议映射需要的闭集失败类型。
         */
        private QuestionRecoveryException(QuestionRecoveryFailure failure) {
            super("question recovery failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 由应用服务将持久化资格门失败转换为 transport 可穷举异常。
         */
        public static QuestionRecoveryException of(QuestionRecoveryFailure failure) {
            return new QuestionRecoveryException(failure);
        }

        /**
         * 返回稳定失败类别。
         */
        public QuestionRecoveryFailure failure() {
            return failure;
        }
    }

    /**
     * 无堆栈 Resume 异常避免运行时、路径和持久化细节越过入站边界。
     */
    final class TurnResumeException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final ResumeFailure failure;

        /**
         * 只保留稳定失败类别。
         */
        private TurnResumeException(ResumeFailure failure) {
            super("turn resume failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 应用层通过闭集工厂构造，Adapter 不得制造任意内部状态。
         */
        public static TurnResumeException of(ResumeFailure failure) {
            return new TurnResumeException(failure);
        }

        /**
         * 返回稳定映射类别。
         */
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
