// @author kongweiguang
// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * conversation 的领域持久化端口；调用成功返回即代表对应事务已经提交。
 */
public interface ConversationRepository extends AutoCloseable {
    /**
     * 在无兼容别名的 fresh schema 中创建一个 Thread。
     */
    ThreadSnapshot createThread(ThreadDefinition thread);

    /**
     * 原子写入 QUEUED Turn、用户完整 blocks 与唯一 Thread revision。
     */
    AdmissionReceipt admit(TurnAdmission admission);

    /** 隐藏 Goal continuation 只创建 Turn/execution，不写 USER message 或用户时间线。 */
    default AdmissionReceipt admitContinuation(ContinuationAdmission admission) {
        throw new UnsupportedOperationException("Goal continuation admission is unavailable");
    }

    /**
     * 用 Thread revision CAS 原子提交非终态及其全部事实。
     */
    CommitReceipt commit(CommitRequest request);

    /** 回答 request_user_input 后在同一 Turn CAS 内完成 ToolResult/消息/cursor 结算，仍保留 SUSPENDED。 */
    default CommitReceipt settleInteractionAnswer(InteractionAnswerSettlement request) {
        throw new UnsupportedOperationException("interaction settlement is unavailable");
    }

    /** 将未持久化的 Interaction、Tools cursor 与 SUSPENDED Turn 放入一个 SQLite 事务。 */
    default InteractionSuspensionReceipt suspendForInteraction(InteractionSuspensionRequest request) {
        throw new UnsupportedOperationException("interaction suspension is unavailable");
    }

    /** 原子回答 Interaction 并结算内部 Tool；重复幂等提交只返回已落库请求，不再次推进 cursor。 */
    default InteractionAnswerReceipt respondInteraction(InteractionRequest answered, long expectedRevision,
                                                         String idempotencyKey, Instant occurredAt) {
        throw new UnsupportedOperationException("interaction response is unavailable");
    }

    /**
     * 将已经形成独立回复边界的 STOP Assistant 作为 Final 提交，但不消费或关闭输入队列；
     * 该入口用于队首校验失败后的可恢复挂起，避免把正常答复误投影为工作过程。
     */
    CommitReceipt commitAssistantSettlement(CommitRequest request);

    /**
     * 仅当存在排队输入时，按 Steering 优先、同类 FIFO 的规则把前一模型结算与下一条 USER Message
     * 放进同一事务；空结果表示没有可消费输入且不得提交 request 中的任何事实。
     */
    Optional<InputConsumption> commitWithNextInput(CommitRequest request, InputSelection selection);

    /**
     * 将安全点已 claim 的 Task Mailbox 批次作为 USER messages 提交，并在同一事务消费 BOUND 行、
     * 推进 Thread/Turn 版本与 execution cursor；禁止存在第二条先消费后写消息的路径。
     */
    TaskMailboxConsumption consumeTaskMailbox(TaskMailboxCommit request);

    /**
     * 仅收敛取消声明前已经越过执行边界的 Tool batch；该入口不放宽其它非终态事实。
     */
    CommitReceipt commitCancellationToolBatch(CancellationToolBatchCommit request);

    /**
     * 唯一终态、最终 assistant blocks 与全部事实必须在同一事务提交。
     */
    CommitReceipt commitTerminal(TerminalCommit request);

    /**
     * 在同一个 SQLite 事务内登记取消请求并推进 Thread/Turn 两个版本；返回成功即已提交。
     * 取消登记保持 Turn 非终态，终态提交必须随后通过 Turn mutation version 再赢一次门。
     */
    CancellationClaim claimCancellation(String threadId, String turnId, long expectedThreadRevision,
                                        String reason, Instant occurredAt);

    /** 读取 Resume 所需的唯一权威投影；execution 必须经过严格 Codec 解码。 */
    default Optional<ResumeCandidate> findResumeCandidate(String turnId) {
        return Optional.empty();
    }

    /** 以 Thread revision 和 Turn mutation 双门把最早 SUSPENDED Turn 原子放回 QUEUED。 */
    default ResumeReceipt resume(String turnId, long expectedThreadRevision,
                                 long expectedTurnMutationVersion, Instant occurredAt) {
        throw new UnsupportedOperationException("turn resume is unavailable");
    }

    /** 没有进程内 owner 的 SUSPENDED Turn 由存储直接收敛取消终态。 */
    default CancelResult cancelSuspended(String turnId, long expectedThreadRevision, Instant occurredAt) {
        throw new UnsupportedOperationException("suspended cancellation is unavailable");
    }

    /**
     * 将已登记取消的活动 Turn 安全转为 SUSPENDED；execution cursor 保留给显式 Resume 对账。
     * 该入口只用于受控 Plan pause，不能被普通用户取消路径复用。
     */
    default boolean suspendCancelled(String threadId, String turnId, long expectedThreadRevision,
                                     long expectedTurnMutationVersion, Instant occurredAt) {
        return false;
    }

    /** Plan pause 可同时替换剩余活动预算；旧实现默认退回仅保留游标的窄兼容入口。 */
    default boolean suspendCancelled(String threadId, String turnId, long expectedThreadRevision,
                                     long expectedTurnMutationVersion, TurnExecutionState execution,
                                     Instant occurredAt) {
        return suspendCancelled(threadId, turnId, expectedThreadRevision,
                expectedTurnMutationVersion, occurredAt);
    }

    /** 新 Turn 可以持久排队，但进程内执行准入不得跨过遗留 SUSPENDED head。 */
    default boolean hasSuspendedTurn(String threadId) {
        return false;
    }

    /** 读取同一 Tool 尚未关闭的审批，使恢复复用原 ID 而不制造第二个请求。 */
    default Optional<PendingApproval> findApproval(String turnId, String callId) {
        return Optional.empty();
    }

    /** 读取 Provider tool batch 已原子保存的精确绑定，执行阶段禁止从当前同名目录重建。 */
    default Optional<ToolBinding> findToolBinding(String turnId, String callId) {
        return Optional.empty();
    }

    /**
     * 先在 SQLite 原子提交审批决定和 WAITING_APPROVAL -> RUNNING，再允许 Broker 唤醒 waiter；
     * 返回 false 表示迟到、重复、过期或已不再由该审批拥有状态门。
     */
    default boolean resolveApproval(String approvalId, ApprovalDecision decision, Instant resolvedAt) {
        return false;
    }

    /** 为活动 Turn 追加一条持久 FIFO 输入，并返回提交后的完整权威队列。 */
    default QueueMutation enqueueInput(PendingInput input) {
        throw new UnsupportedOperationException("pending input is unavailable");
    }

    /** 读取下一条真实 FIFO head 供外部引用和 Skill 正文在消费事务前完成无副作用校验。 */
    default Optional<InputQueue.QueuedInput> peekInput(String turnId, InputKind kind) {
        return Optional.empty();
    }

    /** 消费前重验排队附件仍由该输入独占预留；失败必须保留 FIFO 队首供用户修复。 */
    default boolean queuedAttachmentsAvailable(String threadId, InputQueue.QueuedInput input, Instant now) {
        return input.content().attachmentIds().isEmpty();
    }

    /** 把消费期失效的精确队首标为需要处理；相同问题重复标记保持幂等。 */
    default QueueMutation markInputNeedsAttention(String threadId, String turnId, InputSelection selection,
                                                  InputQueue.Issue issue, Instant occurredAt) {
        throw new UnsupportedOperationException("pending input attention is unavailable");
    }

    /** 按条目 revision 把普通输入提升为 Steering；重复提升保持幂等且不推进队列版本。 */
    default QueueMutation prioritizeInput(String threadId, String turnId, String inputId,
                                          long expectedInputRevision, Instant occurredAt) {
        throw new UnsupportedOperationException("pending input prioritization is unavailable");
    }

    /** 按条目 revision 编辑尚未消费的完整内容，不允许重建 identity 或入队顺序。 */
    default QueueMutation updateInput(String threadId, String turnId, String inputId,
                                      long expectedInputRevision, UserContent content, Instant occurredAt) {
        throw new UnsupportedOperationException("pending input update is unavailable");
    }

    /** 按条目 revision 删除尚未消费的条目；删除只做状态结算以保留恢复审计。 */
    default QueueMutation deleteInput(String threadId, String turnId, String inputId,
                                      long expectedInputRevision, Instant occurredAt) {
        throw new UnsupportedOperationException("pending input deletion is unavailable");
    }

    /**
     * 在同一事务标记首条输入已消费、写入 USER Message，并推进 Thread/Turn revision。
     */
    default Optional<InputConsumption> consumeInput(String threadId, String turnId, InputSelection selection,
                                                    long expectedTurnMutationVersion, Instant occurredAt,
                                                    TurnExecutionState executionState) {
        return Optional.empty();
    }

    /**
     * 取消 CAS 仅向应用暴露领域化失败，持久化 Adapter 必须在端口外完成异常翻译。
     */
    final class CancellationClaimException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final CancellationFailure failure;

        /**
         * 只保存稳定类别，不携带 SQL、路径、存储消息或查询参数。
         */
        private CancellationClaimException(CancellationFailure failure) {
            super("cancellation claim failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /**
         * 供持久化 Adapter 把技术异常收敛为 conversation 端口语义。
         */
        public static CancellationClaimException of(CancellationFailure failure) {
            return new CancellationClaimException(failure);
        }

        /**
         * 返回应用层可安全分支的稳定失败类别。
         */
        public CancellationFailure failure() {
            return failure;
        }
    }

    /**
     * 取消登记的闭集失败语义，禁止应用识别基础设施异常代码。
     */
    enum CancellationFailure {
        /**
         * 指定 Turn 不存在或已不再允许登记取消。
         */
        NOT_FOUND,

        /**
         * 调用方持有的 Thread revision 已过期。
         */
        CONFLICT,

        /**
         * 存储暂时不可完成登记，调用方不得把它伪装成业务冲突。
         */
        UNAVAILABLE
    }

    /** Resume 只恢复 Operation；workspace root 用于下一 Provider 安全点解析最新环境。 */
    record ResumeCandidate(String threadId, String turnId, String workspaceId, Path workspaceRoot,
                           long threadRevision, long turnMutationVersion,
                           TurnExecutionState execution, String promptSummary,
                           String latestCheckpointSummary, UserContent originalContent,
                           String internalContext, boolean provisionalTitleEligible) {
        /**
         * 候选在同一 SQLite 快照绑定执行游标、Prompt 与来源专属上下文；USER 内容和内部上下文
         * 必须二选一，防止恢复路径重新猜测 Turn 来源。
         */
        public ResumeCandidate {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            workspaceId = identifier(workspaceId, "ws_", "workspaceId");
            workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
            if (threadRevision < 0 || turnMutationVersion < 0) throw new IllegalArgumentException("invalid resume revisions");
            Objects.requireNonNull(execution, "execution");
            promptSummary = boundedSummary(promptSummary, "promptSummary");
            latestCheckpointSummary = boundedSummary(latestCheckpointSummary, "latestCheckpointSummary");
            boolean internal = execution.common().origin().internal();
            if (internal != (originalContent == null) || internal != (internalContext != null)) {
                throw new IllegalArgumentException("resume content does not match Turn origin");
            }
            if (internal) internalContext = boundedSummary(internalContext, "internalContext");
        }

        /** Summary 允许为空但必须有界，防止 Resume 候选成为无界数据库投影。 */
        private static String boundedSummary(String value, String field) {
            if (value == null || value.length() > 4_000_000 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }

        /**
         * 内部 Turn 在首个 checkpoint 前恢复其不可见 admission 上下文，之后恢复精确 checkpoint；
         * 用户 Turn 保持既有的 Thread 最新摘要语义。
         */
        public String initialSummary() {
            if (!execution.common().origin().internal()) return latestCheckpointSummary;
            return execution.common().promptCheckpointId() == null ? internalContext : promptSummary;
        }
    }

    /** Resume CAS 返回两套新 revision，队列和执行器不得自行递增。 */
    record ResumeReceipt(String threadId, String turnId, long threadRevision, long turnMutationVersion) { }

    /** SUSPENDED 直接取消的原子回执。 */
    record CancelResult(String turnId, long threadRevision, long turnMutationVersion) { }

    /** steering 与 follow-up 是唯一两类输入，类型决定消费边界而非优先级。 */
    enum InputKind {
        /** 当前 Tool 完成后、下一模型调用前消费。 */
        STEERING,
        /** Turn 准备结束且没有 steering 时消费。 */
        FOLLOW_UP
    }

    /** 新入队输入只携带创建事实；条目和队列 revision 由 SQLite 事务分配。 */
    record PendingInput(String inputId, String threadId, String turnId, InputKind kind,
                        UserContent content, Instant createdAt) {
        /** 限制身份和结构化内容，精确 JSON 字节预算由持久化事务统一执行。 */
        public PendingInput {
            inputId = identifier(inputId, "input_", "inputId");
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Peek 与消费事务共享的精确条目门，防止校验后编辑或提升被误吞。 */
    record InputSelection(String inputId, InputKind kind, long inputRevision) {
        /** 身份、类型与条目 revision 必须全部来自同一个权威 peek。 */
        public InputSelection {
            inputId = identifier(inputId, "input_", "inputId");
            Objects.requireNonNull(kind, "kind");
            if (inputRevision < 1) throw new IllegalArgumentException("invalid input revision");
        }

        /** 从公开队列条目冻结消费门，不复制内容或问题文本。 */
        public static InputSelection from(InputQueue.QueuedInput input) {
            Objects.requireNonNull(input, "input");
            return new InputSelection(input.inputId(), InputKind.valueOf(input.kind().name()),
                    input.inputRevision());
        }
    }

    /** 队列 CRUD 的内部回执额外携带是否真实变化与当前 Thread revision，供事件安全发布。 */
    record QueueMutation(String inputId, InputQueue inputQueue, long threadRevision, boolean changed) {
        /** 回执必须来自同一提交事务，应用层不得自行拼接 revision。 */
        public QueueMutation {
            inputId = identifier(inputId, "input_", "inputId");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (threadRevision < 0) throw new IllegalArgumentException("invalid queue mutation revision");
        }
    }

    /** 消费事务返回原条目、公开 USER item、消费后队列与两套权威 revision。 */
    record InputConsumption(InputQueue.QueuedInput input, String userItemId, ModelMessage message,
                            Instant occurredAt, InputQueue inputQueue, long threadRevision,
                            long turnMutationVersion) {
        /** 校验消息角色、关联与版本，Loop 不从本地状态推导消费结果。 */
        public InputConsumption {
            Objects.requireNonNull(input, "input");
            userItemId = identifier(userItemId, "item_", "userItemId");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(inputQueue, "inputQueue");
            if (message.role() != ModelRole.USER || threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid input consumption");
            }
        }
    }

    /** Task Mailbox 消费请求冻结 BOUND 批次和当前 Turn CAS/execution authority。 */
    record TaskMailboxCommit(String threadId, String turnId, TurnState state,
                             List<TaskMailboxPort.ClaimedMessage> messages,
                             long expectedTurnMutationVersion, Instant occurredAt,
                             TurnExecutionState executionState) {
        /**
         * 所有消息必须严格递增且由当前 Turn 持有，避免调用方截取、重排或跨 Thread 拼接 claim。
         */
        public TaskMailboxCommit {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(state, "state");
            if (state.terminal()) throw new IllegalArgumentException("terminal Turn cannot consume mailbox");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            if (messages.isEmpty() || messages.size() > 256) {
                throw new IllegalArgumentException("invalid mailbox consumption size");
            }
            long previous = 0;
            for (TaskMailboxPort.ClaimedMessage message : messages) {
                if (!threadId.equals(message.targetThreadId())
                        || !turnId.equals(message.boundTurnId())
                        || message.sequence() <= previous) {
                    throw new IllegalArgumentException("invalid mailbox consumption claim");
                }
                previous = message.sequence();
            }
            if (expectedTurnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid turn mutation version");
            }
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(executionState, "executionState");
        }
    }

    /** 已提交 Mailbox USER messages 与两套权威版本；列表顺序与 claim sequence 完全一致。 */
    record TaskMailboxConsumption(List<StoredMessage> userMessages,
                                  List<io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ThreadMessageItem> messageItems,
                                  long threadRevision, long turnMutationVersion,
                                  TurnExecutionState executionState) {
        /** Loop 只使用事务回执推进本地上下文与 CAS，不从请求自行推导新版本。 */
        public TaskMailboxConsumption {
            userMessages = List.copyOf(Objects.requireNonNull(userMessages, "userMessages"));
            messageItems = List.copyOf(Objects.requireNonNull(messageItems, "messageItems"));
            if (userMessages.isEmpty() || userMessages.stream().anyMatch(value ->
                    value.message().role() != ModelRole.USER)) {
                throw new IllegalArgumentException("invalid mailbox USER messages");
            }
            if (messageItems.size() > userMessages.size()) {
                throw new IllegalArgumentException("invalid mailbox message item count");
            }
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid mailbox consumption revision");
            }
            Objects.requireNonNull(executionState, "executionState");
        }
    }

    /** 队列存储异常只暴露应用层可恢复类别，不把 SQL 或约束名带到 RPC。 */
    final class InputQueueException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final InputQueueFailure failure;

        /** 只保存稳定失败类别。 */
        private InputQueueException(InputQueueFailure failure) {
            super("input queue mutation failed", null, false, false);
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /** 持久化 Adapter 通过闭集工厂收敛技术失败。 */
        public static InputQueueException of(InputQueueFailure failure) {
            return new InputQueueException(failure);
        }

        /** 返回应用层可安全映射的稳定失败类别。 */
        public InputQueueFailure failure() {
            return failure;
        }
    }

    /** 输入队列 CRUD 的稳定失败闭集。 */
    enum InputQueueFailure {
        /** Turn 已终态、关闭输入门或已登记取消。 */
        NOT_ACCEPTING,
        /** 条目数量或总 UTF-8 字节预算已耗尽。 */
        CAPACITY,
        /** 请求的待处理条目不存在或已解决。 */
        NOT_FOUND,
        /** 条目 revision 已过期。 */
        CONFLICT
    }

    /** 恢复 Tool 审批所需的最小持久事实；decision 非空时不得再次等待用户。 */
    record PendingApproval(String approvalId, ApprovalDecision decision, Instant expiresAt) {
        /** 审批 ID 与到期时刻来自权威行，恢复路径不得生成替代身份。 */
        public PendingApproval {
            approvalId = identifier(approvalId, "appr_", "approvalId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 读取取消、审批与迟到回调门禁使用的权威 Turn 状态。
     */
    Optional<TurnSnapshot> findTurn(String threadId, String turnId);

    /**
     * 在一个数据库快照中读取永久消息历史和当前 Turn 投影。
     */
    Optional<ThreadSnapshot> readThread(String threadId);

    /**
     * 释放由 composition root 注入的持久化服务引用；数据库资源仍由 Solon 生命周期持有。
     */
    @Override
    void close();

    /**
     * fresh schema 中创建 Thread 所需的稳定身份与初始展示信息。
     */
    record ThreadDefinition(String threadId, String workspaceId, String title,
                            ThreadPreferences preferences, Instant createdAt) {
        /**
         * Thread 初始 revision 固定为零，首次 admission 才推进 CAS。
         */
        public ThreadDefinition {
            threadId = identifier(threadId, "thr_", "threadId");
            workspaceId = identifier(workspaceId, "ws_", "workspaceId");
            title = text(title, "title", 4_096, true);
            preferences = Objects.requireNonNull(preferences, "preferences");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 将用户消息与 Operation 游标一次性接纳为 QUEUED Turn 的事务输入。
     */
    record TurnAdmission(String threadId, String turnId, String messageId,
                         ModelMessage userMessage, List<String> attachmentIds,
                          long expectedThreadRevision, Instant requestedAt,
                          TurnExecutionState initialExecution) {
        /**
         * admission 也使用 CAS，防止并发请求绕过同 Thread FIFO 的数据库事实。
         */
        public TurnAdmission {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            messageId = identifier(messageId, "item_", "messageId");
            Objects.requireNonNull(userMessage, "userMessage");
            attachmentIds = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
            if (attachmentIds.size() > 10 || attachmentIds.stream().distinct().count() != attachmentIds.size()) {
                throw new IllegalArgumentException("admission attachmentIds exceed limits or repeat");
            }
            for (String attachmentId : attachmentIds) {
                identifier(attachmentId, "att_", "attachmentId");
            }
            if (userMessage.role() != ModelRole.USER || expectedThreadRevision < 0) {
                throw new IllegalArgumentException("admission requires a USER message and valid revision");
            }
            requestedAt = Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(initialExecution, "initialExecution");
        }
    }

    /** continuation admission 保留普通 Thread CAS，但明确没有消息、附件和标题副作用。 */
    record ContinuationAdmission(String threadId, String turnId, long expectedThreadRevision,
                                 Instant requestedAt, TurnExecutionState initialExecution,
                                 String hiddenContext) {
        /** 内部 Turn 仍使用公开稳定身份和非负版本，并在 admission 事务内冻结结构化上下文。 */
        public ContinuationAdmission {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            if (expectedThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(initialExecution, "initialExecution");
            if (!initialExecution.common().origin().internal()) {
                throw new IllegalArgumentException("continuation requires an internal Turn origin");
            }
            hiddenContext = text(hiddenContext, "hiddenContext", 1_000_000, false);
        }
    }

    /**
     * admission 提交后数据库返回的 Thread 与 Turn 两套独立版本。
     */
    record AdmissionReceipt(String threadId, String turnId, long threadRevision,
                            long turnMutationVersion, String provisionalTitle) {
        /**
         * admission 返回外部 revision 与新 Turn v0，调用方不得从二者互相推导。
         */
        public AdmissionReceipt {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid admission receipt revision");
            }
            provisionalTitle = provisionalTitle == null ? null
                    : text(provisionalTitle, "provisionalTitle", 4_096, false);
        }

        /** 空值表示本次未取得首次标题所有权，后续成功也不得补做自动标题。 */
        public boolean createdProvisionalTitle() {
            return provisionalTitle != null;
        }
    }

    /**
     * 已提交的取消登记；重复登记复用原有版本，不产生第二次 revision 或 callback 触发资格。
     */
    record CancellationClaim(boolean accepted, TurnState status, long threadRevision,
                             long turnMutationVersion) {
        /**
         * 版本由 SQLite 事务返回，调用方不得以本地快照自行推导。
         */
        public CancellationClaim {
            Objects.requireNonNull(status, "status");
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid cancellation claim revision");
            }
        }
    }

    /**
     * 非终态状态迁移及其事实的 Turn 内部 CAS 提交请求。
     */
    record CommitRequest(String threadId, String turnId, TurnState state, List<Fact> facts,
                          long expectedTurnMutationVersion, Instant occurredAt,
                          TurnExecutionState executionState) {
        /**
         * 内部提交只锁定所属 Turn，避免同 Thread 后继 admission 使前序执行失去提交资格。
         */
        public CommitRequest {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(state, "state");
            if (state.terminal()) throw new IllegalArgumentException("terminal state requires commitTerminal");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            if (expectedTurnMutationVersion < 0) throw new IllegalArgumentException("invalid turn mutation version");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(executionState, "executionState");
        }
    }

    /** Interaction 回答的恢复结算输入；调用方随后再以正常 Resume CAS 获得执行资格。 */
    record InteractionAnswerSettlement(String threadId, String turnId, String callId,
                                       String content, long expectedTurnMutationVersion,
                                       Instant occurredAt, TurnExecutionState executionState) {
        /** 回答结算必须绑定同一个持久 Tool cursor，版本冲突由事务拒绝。 */
        public InteractionAnswerSettlement {
            // 该输入绑定原 Tool 调用与持久游标，不能由后端猜测缺失的调用身份。
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            callId = identifier(callId, "call_", "callId");
            content = text(content == null ? "" : content, "content", 4_000_000, true);
            if (expectedTurnMutationVersion < 0) throw new IllegalArgumentException("invalid turn mutation version");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            executionState = Objects.requireNonNull(executionState, "executionState");
        }
    }

    /**
     * 取消声明后的 Tool batch 专用事务输入；强类型闭集避免模型、用量或审批事实穿过取消门。
     */
    record CancellationToolBatchCommit(CommitRequest request) {
        /**
         * batch 必须包含至少一个结果和唯一完整 TOOL 消息；Started 只能描述同批已有结果的调用。
         */
        public CancellationToolBatchCommit {
            Objects.requireNonNull(request, "request");
            Map<String, ToolResultFact> results = new java.util.LinkedHashMap<>();
            java.util.Set<String> started = new java.util.LinkedHashSet<>();
            ToolResultMessageFact resultMessage = null;
            for (Fact fact : request.facts()) {
                switch (fact) {
                    case ToolStartedFact value -> {
                        if (!started.add(value.callId())) {
                            throw new IllegalArgumentException("duplicate Tool started fact");
                        }
                    }
                    case ToolResultFact value -> {
                        if (results.putIfAbsent(value.callId(), value) != null) {
                            throw new IllegalArgumentException("duplicate Tool result fact");
                        }
                    }
                    case ToolResultMessageFact value -> {
                        if (resultMessage != null) {
                            throw new IllegalArgumentException("multiple Tool result messages");
                        }
                        resultMessage = value;
                    }
                    default -> throw new IllegalArgumentException("cancellation Tool batch contains illegal fact");
                }
            }
            if (results.isEmpty() || resultMessage == null || !results.keySet().containsAll(started)) {
                throw new IllegalArgumentException("incomplete cancellation Tool batch");
            }
            List<String> messageCallIds = resultMessage.message().content().stream()
                    .map(ToolResultContent.class::cast)
                    .map(ToolResultContent::callId)
                    .toList();
            if (messageCallIds.size() != results.size()
                || new java.util.LinkedHashSet<>(messageCallIds).size() != messageCallIds.size()
                || !results.keySet().equals(new java.util.LinkedHashSet<>(messageCallIds))) {
                throw new IllegalArgumentException("Tool result message does not match result facts");
            }
        }
    }

    /**
     * 唯一终态、最终消息、错误投影和事实的原子提交请求。
     */
    record TerminalCommit(String threadId, String turnId, TurnState state, String summary,
                          String errorCode, String errorMessage, String finalMessageId,
                          ModelMessage finalMessage, List<Fact> facts,
                          long expectedTurnMutationVersion, Instant occurredAt,
                          TurnChangeSet changeSet, String changeSetSha256,
                          Long changeSetByteLength, String changeSetUnifiedDiff) {
        /**
         * 终态以 Turn version 赢得唯一门；后继 Turn 的 admission 不参与该内部 CAS。
         */
        public TerminalCommit {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(state, "state");
            if (!state.terminal()) throw new IllegalArgumentException("terminal state required");
            summary = text(summary == null ? "" : summary, "summary", 1_000_000, true);
            if ((finalMessageId == null) != (finalMessage == null)) {
                throw new IllegalArgumentException("final message id and blocks must be paired");
            }
            if (finalMessageId != null) {
                finalMessageId = identifier(finalMessageId, "item_", "finalMessageId");
                if (finalMessage.role() != ModelRole.ASSISTANT) {
                    throw new IllegalArgumentException("final message must be ASSISTANT");
                }
            }
            errorCode = errorCode == null ? null : text(errorCode, "errorCode", 128, false);
            errorMessage = errorMessage == null ? null : text(errorMessage, "errorMessage", 65_536, true);
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            if (expectedTurnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid turn mutation version");
            }
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(changeSet, "changeSet");
            boolean hasArtifact = changeSet.artifactId() != null;
            if (hasArtifact != (changeSetSha256 != null && changeSetByteLength != null
                    && changeSetUnifiedDiff != null)) {
                throw new IllegalArgumentException("change set artifact fields must be paired");
            }
            if (hasArtifact && (!changeSetSha256.matches("[0-9a-f]{64}")
                    || changeSetUnifiedDiff.isEmpty()
                    || changeSetByteLength != changeSetUnifiedDiff.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    || changeSetByteLength > 2L * 1024 * 1024)) {
                throw new IllegalArgumentException("invalid change set artifact integrity");
            }
        }

        /** 既有仓储测试默认提交明确 complete/零修改事实，不再允许缺失 ChangeSet。 */
        public TerminalCommit(String threadId, String turnId, TurnState state, String summary,
                              String errorCode, String errorMessage, String finalMessageId,
                              ModelMessage finalMessage, List<Fact> facts,
                              long expectedTurnMutationVersion, Instant occurredAt) {
            this(threadId, turnId, state, summary, errorCode, errorMessage, finalMessageId, finalMessage,
                    facts, expectedTurnMutationVersion, occurredAt, TurnChangeSet.emptyComplete(),
                    null, null, null);
        }
    }

    /**
     * 一次提交成功后权威返回的通知 revision 与后续 Turn CAS 版本。
     */
    record CommitReceipt(long threadRevision, long turnMutationVersion) {
        /**
         * 两个版本分别服务通知排序与 Turn 内部 CAS，均只能单调非负。
         */
        public CommitReceipt {
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid commit receipt revision");
            }
        }
    }

    /** request_user_input 原子挂起提交后的双版本及 Interaction 事件游标。 */
    record InteractionSuspensionReceipt(long threadRevision, long turnMutationVersion,
                                        long interactionEventSequence) {
        /** 三个水位必须一起提交，调用方不能用零事件号冒充问题已经持久化。 */
        public InteractionSuspensionReceipt {
            if (threadRevision < 0 || turnMutationVersion < 0 || interactionEventSequence < 1) {
                throw new IllegalArgumentException("invalid interaction suspension receipt");
            }
        }
    }

    /** Interaction Tool 将自身控制流交给持久化层时的完整事务输入。 */
    record InteractionSuspensionRequest(InteractionRequest interaction, TurnExecutionState execution,
                                        long expectedTurnMutationVersion, Instant occurredAt) {
        /** 快照与游标必须同事务归属，拒绝部分初始化的挂起输入。 */
        public InteractionSuspensionRequest {
            Objects.requireNonNull(interaction, "interaction");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (expectedTurnMutationVersion < 0) throw new IllegalArgumentException("invalid turn mutation version");
        }
    }

    /** Interaction 回答事务的结果；newlySettled=false 表示幂等重试没有产生第二次 Tool 结算。 */
    record InteractionAnswerReceipt(InteractionRequest request, CommitReceipt turnReceipt,
                                    boolean newlySettled) {
        /** 幂等回执保持原请求与 Turn 双版本，避免第二次提交触发新恢复。 */
        public InteractionAnswerReceipt {
            Objects.requireNonNull(request, "request");
            if (newlySettled != (turnReceipt != null)) {
                throw new IllegalArgumentException("interaction answer receipt mismatch");
            }
        }
    }

    /**
     * 取消、审批和恢复门禁读取的权威 Turn 投影。
     */
    record TurnSnapshot(String threadId, String turnId, TurnState state,
                        Instant requestedAt, Instant updatedAt, Instant completedAt,
                        long threadRevision, long turnMutationVersion) {
        /**
         * 快照同时携带外部可观察 revision 与当前 Turn CAS token，禁止混用。
         */
        public TurnSnapshot {
            if (threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid turn snapshot revision");
            }
        }
    }

    /**
     * 按永久 ordinal 排序的不可变历史消息。
     */
    record StoredMessage(String messageId, String turnId, long ordinal,
                         ModelMessage message, Instant createdAt) {
    }

    /**
     * 同一数据库快照中读取的 Thread、Turn 与消息历史。
     */
    record ThreadSnapshot(String threadId, String workspaceId, String title, ThreadPreferences preferences,
                          long revision, List<TurnSnapshot> turns, List<StoredMessage> messages,
                          Instant createdAt, Instant updatedAt) {
        /**
         * 快照集合复制后再发布，避免事务结束后仍持有可变 Mapper 结果。
         */
        public ThreadSnapshot {
            Objects.requireNonNull(preferences, "preferences");
            turns = List.copyOf(turns);
            messages = List.copyOf(messages);
        }
    }

    /**
     * 单次状态迁移可与状态一起原子持久化的事实闭集。
     */
    sealed interface Fact permits AssistantFact, ToolResultMessageFact, ToolPreparedFact, ToolStartedFact,
            ToolResultFact, ApprovalFact, UsageFact, ReasoningSummaryFact {
    }

    /** 已公开的 reasoning 摘要独立保存；失败/取消也可保留它，但不创建或冒充 Assistant 消息。 */
    record ReasoningSummaryFact(String messageId, String text, int modelRound) implements Fact {
        /**
         * 绑定最终或独立的 Timeline identity，使摘要与回复或无回复终态在同一事务落库。
         */
        public ReasoningSummaryFact {
            messageId = identifier(messageId, "item_", "messageId");
            text = validateReasoningSummary(text);
            if (modelRound < 1 || modelRound > 128) throw new IllegalArgumentException("invalid modelRound");
        }

        /** 摘要必须保持有界且可写入 timeline；空白摘要不应制造一个看似有内容的条目。 */
        private static String validateReasoningSummary(String value) {
            if (value == null || value.length() > 1_048_576 || value.indexOf('\0') >= 0 || value.isBlank()) {
                throw new IllegalArgumentException("invalid reasoningSummary");
            }
            return value;
        }
    }

    /**
     * 模型完整 assistant 消息事实，包含文本和 Tool 调用的原始顺序。
     */
    record AssistantFact(String messageId, ModelMessage message, String publicText, String reasoningSummary,
                         int modelRound) implements Fact {
        /**
         * 保存完整有序 blocks，使重启后的 Tool call 上下文与模型实际输入一致。
         */
        public AssistantFact {
            messageId = identifier(messageId, "item_", "messageId");
            Objects.requireNonNull(message, "message");
            if (message.role() != ModelRole.ASSISTANT) throw new IllegalArgumentException("assistant role required");
            publicText = text(publicText, "publicText", 1_048_576, true);
            if (reasoningSummary != null) {
                reasoningSummary = text(reasoningSummary, "reasoningSummary", 1_048_576, false);
            }
            if (modelRound < 1 || modelRound > 128) throw new IllegalArgumentException("invalid modelRound");
        }
    }

    /**
     * 与一批 Tool 结果行同事务保存的完整 TOOL 角色消息。
     */
    record ToolResultMessageFact(String messageId, ModelMessage message) implements Fact {
        /**
         * 在同一事务保存完整有序的 Tool 结果消息及其结果行，避免恢复时失配。
         */
        public ToolResultMessageFact {
            messageId = identifier(messageId, "item_", "messageId");
            Objects.requireNonNull(message, "message");
            if (message.role() != ModelRole.TOOL
                || message.content().stream().anyMatch(content ->
                    !(content instanceof ToolResultContent))) {
                throw new IllegalArgumentException("TOOL result message required");
            }
        }
    }

    /**
     * Tool 首次进入 PREPARED 时冻结调用事实；未知名称没有可执行 binding，仍需保留原生调用以回注错误。
     */
    record ToolPreparedFact(String callId, String toolName, JsonObject arguments,
                            int ordinal, ToolSideEffect sideEffect, ToolPresentation presentation,
                            ToolBinding binding) implements Fact {
        /**
         * ordinal 在首次 PREPARED 时冻结，result 只能更新同一行而不能另建配对；binding 可空只表达
         * Provider 请求了目录外名称，执行器据此失败关闭，禁止用伪造 route/hash 冒充真实能力。
         */
        public ToolPreparedFact {
            callId = identifier(callId, "call_", "callId");
            toolName = text(toolName, "toolName", 512, false);
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) throw new IllegalArgumentException("invalid tool ordinal");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(presentation, "presentation");
            if (binding != null
                    && (!callId.equals(binding.callId()) || !toolName.equals(binding.localName()))) {
                throw new IllegalArgumentException("Tool fact and binding identity differ");
            }
        }
    }

    /**
     * Tool batch 的不可变执行证据；完整 descriptor、目录修订与权限必须同 Assistant/Tool 行事务提交。
     */
    record ToolBinding(String batchId, String callId, AgentTool.RouteKind routeKind,
                       String localName, String serverId, String remoteName,
                       String schemaHash, String routeHash, String catalogRevision,
                       AccessMode accessMode) {
        /** 绑定只接受稳定身份与 SHA-256，执行时可直接与当前 descriptor 做严格相等比较。 */
        public ToolBinding {
            batchId = identifier(batchId, "batch_", "batchId");
            callId = identifier(callId, "call_", "callId");
            Objects.requireNonNull(routeKind, "routeKind");
            localName = text(localName, "localName", 512, false);
            serverId = text(serverId, "serverId", 256, false);
            remoteName = text(remoteName, "remoteName", 512, false);
            if (schemaHash == null || !schemaHash.matches("[0-9a-f]{64}")
                    || routeHash == null || !routeHash.matches("[0-9a-f]{64}")
                    || catalogRevision == null || !catalogRevision.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid Tool binding hash");
            }
            Objects.requireNonNull(accessMode, "accessMode");
        }

        /** 将持久事实投影为 Adapter descriptor，catalog/access mode 继续独立校验。 */
        public AgentTool.ToolBindingDescriptor descriptor() {
            return new AgentTool.ToolBindingDescriptor(routeKind, localName, serverId, remoteName,
                    schemaHash, routeHash);
        }
    }

    /**
     * Tool 已越过执行边界的事实，用于崩溃恢复区分不确定副作用。
     */
    record ToolStartedFact(String callId) implements Fact {
        /**
         * 只接受已在同一 Turn 中 PREPARED 的稳定调用标识。
         */
        public ToolStartedFact {
            callId = identifier(callId, "call_", "callId");
        }
    }

    /**
     * Tool 终态与有界输出事实，不能表示仍在运行的中间状态。
     */
    record ToolResultFact(String callId, ToolState state, String content, boolean error,
                          ToolPresentation presentation, String artifactContent) implements Fact {
        /**
         * Tool result 必须终结同一 call 行；启动恢复的不确定副作用只保留在内部 ToolState。
         */
        public ToolResultFact {
            callId = identifier(callId, "call_", "callId");
            Objects.requireNonNull(state, "state");
            if (state == ToolState.PREPARED || state == ToolState.RUNNING) {
                throw new IllegalArgumentException("tool result requires terminal tool state");
            }
            content = text(content == null ? "" : content, "toolContent", 4_000_000, true);
            Objects.requireNonNull(presentation, "presentation");
            artifactContent = text(artifactContent == null ? "" : artifactContent,
                    "artifactContent", 4_000_000, true);
            if ((presentation.artifactId() == null) != artifactContent.isEmpty()) {
                throw new IllegalArgumentException("artifact presentation mismatch");
            }
        }
    }

    /**
     * Tool 审批请求或解析事实；空决定表示尚待用户响应。
     */
    record ApprovalFact(String approvalId, String callId, ApprovalDecision decision,
                        Instant expiresAt, ToolPresentation presentation) implements Fact {
        /**
         * decision 为 null 表示请求；非 null 只允许解析已存在且未过期的同一 approval。
         */
        public ApprovalFact {
            approvalId = identifier(approvalId, "appr_", "approvalId");
            callId = identifier(callId, "call_", "callId");
            Objects.requireNonNull(presentation, "presentation");
            ToolPresentation.Status expected = decision == null
                    ? ToolPresentation.Status.WAITING_APPROVAL : ToolPresentation.Status.RUNNING;
            if (presentation.status() != expected) {
                throw new IllegalArgumentException("approval presentation status does not match decision");
            }
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 单个模型轮次的 Provider 用量事实。
     */
    record UsageFact(String requestId, ModelUsage usage, int modelRound, int requestOrdinal,
                     UsagePurpose purpose, UsageCertainty certainty,
                     ProviderRequestProfile profile) implements Fact {
        /**
         * UNKNOWN 只有请求身份与完整 Profile 而无 token；KNOWN 必须携带完整 Provider 用量。
         */
        public UsageFact {
            requestId = identifier(requestId, "request_", "requestId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(certainty, "certainty");
            Objects.requireNonNull(profile, "profile");
            if (modelRound < 1 || modelRound > 128 || requestOrdinal < 1) {
                throw new IllegalArgumentException("invalid Provider request ordinal");
            }
            if ((certainty == UsageCertainty.KNOWN) != (usage != null)) {
                throw new IllegalArgumentException("usage certainty does not match token facts");
            }
        }
    }

    /** Provider 请求用途参与唯一键和恢复解释。 */
    enum UsagePurpose {
        /** 普通 Assistant 或 Tool continuation 请求。 */
        ASSISTANT,
        /** Turn 内自动或 overflow Summary 请求。 */
        SUMMARY
    }

    /** UNKNOWN 明确表示可能计费但 token 不可知，绝不能聚合成零。 */
    enum UsageCertainty {
        /** Provider 返回了可审计的完整 token 用量。 */
        KNOWN,
        /** 请求可能已计费，但崩溃使 token 用量不可知。 */
        UNKNOWN
    }

    /**
     * 约束 fresh v1 的稳定标识，不接受旧前缀或宽松别名。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 在进入数据库前限制非可信文本，错误信息不回显原始内容。
     */
    private static String text(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || value.indexOf('\0') >= 0
            || (!allowEmpty && value.isBlank())) throw new IllegalArgumentException("invalid " + field);
        return value;
    }
}
