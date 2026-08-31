// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.time.Instant;
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

    /**
     * 用 Thread revision CAS 原子提交非终态及其全部事实。
     */
    CommitReceipt commit(CommitRequest request);

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

    /** 为活动 Turn 追加一条持久 FIFO 输入，重启不会丢失未消费内容。 */
    default void enqueueInput(PendingInput input) {
        throw new UnsupportedOperationException("pending input is unavailable");
    }

    /**
     * 在同一事务标记首条输入已消费、写入 USER Message，并推进 Thread/Turn revision。
     */
    default Optional<InputConsumption> consumeInput(String threadId, String turnId, InputKind kind,
                                                    long expectedTurnMutationVersion, Instant occurredAt) {
        return Optional.empty();
    }

    /** Turn 取消时取消全部剩余输入；不提供单项取消或重排。 */
    default void cancelInputs(String turnId, Instant occurredAt) {
        // No-op only for in-memory/test adapters that never accept queued input.
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

    /** steering 与 follow-up 是唯一两类输入，类型决定消费边界而非优先级。 */
    enum InputKind {
        /** 当前 Tool 完成后、下一模型调用前消费。 */
        STEERING,
        /** Turn 准备结束且没有 steering 时消费。 */
        FOLLOW_UP
    }

    /** 一条不可编辑的待消费输入；inputId 同时提供 FIFO 并列时的确定顺序。 */
    record PendingInput(String inputId, String threadId, String turnId, InputKind kind,
                        String text, Instant createdAt) {
        /** 限制身份和正文，避免排队接口成为无界存储通道。 */
        public PendingInput {
            inputId = identifier(inputId, "input_", "inputId");
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            text = ConversationRepository.text(text, "inputText", 4_000_000, false);
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** 消费事务返回新 USER Message 与两套权威 revision。 */
    record InputConsumption(String inputId, ModelMessage message, long threadRevision,
                            long turnMutationVersion) {
        /** 校验消息角色与版本，Loop 不从本地状态推导消费结果。 */
        public InputConsumption {
            inputId = identifier(inputId, "input_", "inputId");
            Objects.requireNonNull(message, "message");
            if (message.role() != ModelRole.USER || threadRevision < 0 || turnMutationVersion < 0) {
                throw new IllegalArgumentException("invalid input consumption");
            }
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
            // 仅迁移前历史 Thread 可为空；create/admit 输入仍各自在构造器中强制完整。
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * 将用户消息与冻结配置一次性接纳为 QUEUED Turn 的事务输入。
     */
    record TurnAdmission(String threadId, String turnId, TurnRuntimeSnapshot runtime,
                         String messageId, ModelMessage userMessage, List<String> attachmentIds,
                         long expectedThreadRevision, Instant requestedAt) {
        /**
         * admission 也使用 CAS，防止并发请求绕过同 Thread FIFO 的数据库事实。
         */
        public TurnAdmission {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(runtime, "runtime");
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
                         long expectedTurnMutationVersion, Instant occurredAt) {
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
                          long expectedTurnMutationVersion, Instant occurredAt) {
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

    /**
     * 取消、审批和恢复门禁读取的权威 Turn 投影。
     */
    record TurnSnapshot(String threadId, String turnId, TurnState state, TurnRuntimeSnapshot runtime,
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
            ToolResultFact, ApprovalFact, UsageFact {
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
     * Tool 首次进入 PREPARED 时冻结的调用参数、顺序和副作用类别。
     */
    record ToolPreparedFact(String callId, String toolName, JsonObject arguments,
                            int ordinal, ToolSideEffect sideEffect, ToolPresentation presentation) implements Fact {
        /**
         * ordinal 在首次 PREPARED 时冻结，result 只能更新同一行而不能另建配对。
         */
        public ToolPreparedFact {
            callId = identifier(callId, "call_", "callId");
            toolName = text(toolName, "toolName", 512, false);
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) throw new IllegalArgumentException("invalid tool ordinal");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(presentation, "presentation");
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
    record UsageFact(ModelUsage usage, int modelRound) implements Fact {
        /**
         * 限制轮次范围并要求完整用量，避免错误聚合污染 Turn 总计。
         */
        public UsageFact {
            Objects.requireNonNull(usage, "usage");
            if (modelRound < 1 || modelRound > 128) throw new IllegalArgumentException("invalid modelRound");
        }
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
