// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.acl;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState.Ready;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionFactsCodec;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.domain.model.TurnAdmission;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Task 事实挂入通用 AgentStore 准入，不复制 Core 已写的 Turn 或 USER 消息。
 */
public final class TaskAdmissionTransactionParticipant implements JaAdmissionTransactionParticipant {
    private final TaskRepository tasks;
    private final AdmissionFacts facts;
    private final TaskModels.ChildAdmission child;
    private final SubagentPolicy parentPolicy;
    private final FollowUpDraft followUp;
    private final UserTurnDraft userTurn;
    private final ContinuationDraft continuation;
    private final ExecutionRecordCodec records;
    private final ExecutionCursorCodec cursors;

    /**
     * 用显式工厂冻结一种 admission 意图，避免暴露多组可空业务字段供运行期猜分支。
     */
    private TaskAdmissionTransactionParticipant(TaskRepository tasks, AdmissionFacts facts,
                                                TaskModels.ChildAdmission child, SubagentPolicy parentPolicy,
                                                FollowUpDraft followUp,
                                                UserTurnDraft userTurn, ContinuationDraft continuation,
                                                ExecutionRecordCodec records,
                                                ExecutionCursorCodec cursors) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.facts = Objects.requireNonNull(facts, "facts");
        if ((child != null ? 1 : 0) + (followUp != null ? 1 : 0) + (userTurn != null ? 1 : 0)
            + (continuation != null ? 1 : 0) != 1) {
            throw new IllegalArgumentException("exactly one Task admission is required");
        }
        this.child = child;
        this.parentPolicy = parentPolicy;
        this.followUp = followUp;
        this.userTurn = userTurn;
        this.continuation = continuation;
        this.records = Objects.requireNonNull(records, "records");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /**
     * Child 首轮必须带冻结的 Thread、seed 与父因果事实。
     */
    public static TaskAdmissionTransactionParticipant forChild(TaskRepository tasks, AdmissionFacts facts,
                                                               TaskModels.ChildAdmission child,
                                                               SubagentPolicy parentPolicy,
                                                               ExecutionRecordCodec records,
                                                               ExecutionCursorCodec cursors) {
        return new TaskAdmissionTransactionParticipant(tasks, facts,
                Objects.requireNonNull(child, "child"), Objects.requireNonNull(parentPolicy, "parentPolicy"),
                null, null, null, records, cursors);
    }

    /**
     * Follow-up 必须把原始 mailbox 与 projection CAS 一起结算。
     */
    public static TaskAdmissionTransactionParticipant forFollowUp(TaskRepository tasks, AdmissionFacts facts,
                                                                  FollowUpDraft followUp,
                                                                  ExecutionRecordCodec records,
                                                                  ExecutionCursorCodec cursors) {
        return new TaskAdmissionTransactionParticipant(tasks, facts, null, null,
                Objects.requireNonNull(followUp, "followUp"), null, null, records, cursors);
    }

    /**
     * 普通 USER 首轮只扩展 Task 投影，Turn 和正文由 Core/Ja admission 唯一创建。
     */
    public static TaskAdmissionTransactionParticipant forUserTurn(TaskRepository tasks, AdmissionFacts facts,
                                                                  UserTurnDraft userTurn,
                                                                  ExecutionRecordCodec records,
                                                                  ExecutionCursorCodec cursors) {
        return new TaskAdmissionTransactionParticipant(tasks, facts, null, null, null,
                Objects.requireNonNull(userTurn, "userTurn"), null, records, cursors);
    }

    /**
     * Plan 执行没有 USER 正文；以显式 CAS 与 RESUMED Activity 在 Core admission 内同事务落盘。
     */
    public static TaskAdmissionTransactionParticipant forContinuation(
            TaskRepository tasks, AdmissionFacts facts, ContinuationDraft continuation,
            ExecutionRecordCodec records, ExecutionCursorCodec cursors) {
        return new TaskAdmissionTransactionParticipant(tasks, facts, null, null, null, null,
                Objects.requireNonNull(continuation, "continuation"), records, cursors);
    }

    /**
     * 先于通用 Ja 投影校验 admission 身份与正文，避免无效 Task 输入留下任何可见行。
     */
    @Override
    public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
        if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
            return;
        }
        if (continuation == null) {
            admission(transaction);
        }
        else {
            validateContinuation(transaction);
        }
    }

    /**
     * 通用 Ja Thread/Turn/message 投影完成后，以同一连接写入对应 Task 事实。
     */
    @Override
    public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
        if (transaction.kind() != AgentStore.TransactionKind.ADMISSION) {
            return;
        }
        if (continuation != null) {
            validateContinuation(transaction);
            TaskModels.ContinuationKind kind = TaskModels.ContinuationKind.PLAN;
            tasks.applyContinuationAdmission(new TaskModels.ContinuationAdmission(
                    facts.identity().threadId(), facts.identity().turnId(), kind,
                    continuation.expectedTaskRevision(), continuation.activityId(),
                    continuation.activitySummary(), facts.acceptedAt()), facts);
            return;
        }
        TurnAdmission turn = admission(transaction);
        if (child != null) {
            tasks.applyChildAdmission(child, turn, facts, parentPolicy);
        } else if (followUp != null) {
            tasks.applyFollowUpAdmission(new TaskModels.FollowUpAdmission(followUp.mailbox(), turn,
                    followUp.expectedTaskRevision(), followUp.activityId(), followUp.activitySummary()), facts);
        } else {
            tasks.applyUserTurnAdmission(new TaskModels.UserTurnAdmission(turn, userTurn.expectedTaskRevision()),
                    facts);
        }
    }

    /**
     * 只从本次唯一 user fact 解码规范 UserContent，并逐块核对冻结 brief 或队列消息，防止资源引用被折叠成正文。
     */
    private TurnAdmission admission(AgentStoreTransactionContext transaction) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        if (tx.kind() != AgentStore.TransactionKind.ADMISSION || !matchesAdmissionEnvelope(tx)
            || (userTurn == null ? facts.origin() != TurnOrigin.CHILD_TASK
                : facts.origin() != TurnOrigin.USER)) {
            throw new SQLException("Task admission does not match its AgentStore transaction");
        }
        if (userTurn == null) {
            verifyFrozenTaskFacts(tx);
        }
        else {
            verifyUserTurnFacts();
        }
        verifyInitialContextSeed(tx, expectedInitialContextSeed());

        UserMessage user = JaAdmissionFactsCodec.decodeUserMessage(records, tx);
        if (userTurn == null) {
            UserContent expectedContent = child == null ? followUp.mailbox().content()
                    : child.contextSeed().taskBrief();
            if (!expectedContent.equals(user.content())) {
                throw new SQLException("Task input does not match the unique Core user message");
            }
        }
        TurnExecutionState initial = cursors.read(tx.cursorSchemaVersion(), tx.cursor());
        if (!(initial instanceof Ready)) {
            throw new SQLException("Task admission cursor is not ready");
        }
        if (child != null && (!child.childThread().threadId().equals(tx.execution().conversation().threadId())
                              || child.childThread().createdAt().isAfter(tx.occurredAt()))) {
            throw new SQLException("Task child identity does not match its admission");
        }
        if (followUp != null && (!followUp.mailbox().targetThreadId()
                .equals(tx.execution().conversation().threadId())
                                 || followUp.mailbox().createdAt().isAfter(tx.occurredAt()))) {
            throw new SQLException("Task follow-up identity does not match its admission");
        }
        return new TurnAdmission(tx.execution().conversation().threadId(),
                tx.execution().turnId(), JaMessageIds.toItemId(user.messageId()),
                new ModelMessage(ModelRole.USER, new ArrayList<>(user.content().blocks())),
                user.content().attachmentIds(), facts.expectedThreadRevision(), tx.occurredAt(), initial);
    }

    /**
     * 首次 inherited context 必须与 Task 创建快照逐值相同，禁止首轮入口换父历史或省略摘要。
     */
    private void verifyInitialContextSeed(AgentStoreTransactionContext transaction,
                                          Optional<InitialContextSeed> expected) throws SQLException {
        List<AgentStore.RecordWrite> seedRecords = new ArrayList<>();
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (ExecutionRecordCodec.INITIAL_CONTEXT_SEED_RECORD_TYPE.equals(record.recordType())) {
                seedRecords.add(record);
            }
        }
        if (expected.isEmpty()) {
            if (!seedRecords.isEmpty()) {
                throw new SQLException("Task admission introduced an unowned context seed");
            }
            return;
        }
        if (seedRecords.size() != 1)
            {
                throw new SQLException("Task admission omitted or duplicated its frozen context seed");
            }
        AgentStore.RecordWrite record = seedRecords.getFirst();
        long sequence = transaction.records().indexOf(record) + 1L;
        InitialContextSeed actual;
        try {
            actual = records.decodeInitialContextSeed(new AgentStore.StoredRecord(record.recordId(),
                    record.recordType(), record.payloadSchemaVersion(), sequence,
                    record.payload(), record.occurredAt()));
        } catch (RuntimeException invalid) {
            throw new SQLException("Task admission context seed is invalid", invalid);
        }
        if (!expected.orElseThrow().equals(actual)) {
            throw new SQLException("Task admission changed the frozen Task context seed");
        }
    }

    /**
     * USER、Child 与 Follow-up 的 admission 按各自冻结来源核验 seed；Continuation 使用独立校验。
     */
    private Optional<InitialContextSeed> expectedInitialContextSeed() {
        if (child != null) {
            return Optional.ofNullable(child.contextSeed().initialContextSeed());
        }
        if (followUp != null) {
            return followUp.initialContextSeed();
        }
        if (userTurn != null) {
            return userTurn.initialContextSeed();
        }
        return Optional.empty();
    }

    /**
     * 隐藏续跑只沿 Core Ready 游标推进；首轮可核验 Task 冻结背景，不伪造 USER 或 Child 因果事实。
     */
    private void validateContinuation(AgentStoreTransactionContext transaction) throws SQLException {
        AgentStoreTransactionContext tx = Objects.requireNonNull(transaction, "transaction");
        boolean validOrigin = facts.origin() == TurnOrigin.PLAN_EXECUTION;
        boolean validIdentity = facts.planRevisionId() != null;
        if (tx.kind() != AgentStore.TransactionKind.ADMISSION || !validOrigin || !validIdentity
            || facts.runId() == null || facts.newThread().isPresent() || !matchesAdmissionEnvelope(tx)
            || continuation.activityId().isBlank()) {
            throw new SQLException("Task continuation does not match its AgentStore admission");
        }
        verifyInitialContextSeed(tx, continuation.initialContextSeed());
        TurnExecutionState initial = cursors.read(tx.cursorSchemaVersion(), tx.cursor());
        if (!(initial instanceof Ready)) {
            throw new SQLException("Task continuation cursor is not ready");
        }
    }

    /**
     * 两类准入共享身份、修订号和受理时刻约束；来源及 Thread 初始化仍由各自分支校验。
     */
    private boolean matchesAdmissionEnvelope(AgentStoreTransactionContext tx) {
        return facts.identity().tenantId().equals(tx.execution().conversation().tenantId())
               && facts.identity().threadId().equals(tx.execution().conversation().threadId())
               && facts.identity().turnId().equals(tx.execution().turnId())
               && facts.expectedThreadRevision() == tx.expectedThreadRevision()
               && facts.acceptedAt().equals(tx.occurredAt());
    }

    /**
     * 普通 USER 不可伪造新 Thread、父级来源或计划修订/运行身份；零 revision 是空闲 SIDE_TASK 首次准入的合法状态。
     */
    private void verifyUserTurnFacts() throws SQLException {
        if (facts.newThread().isPresent() || facts.parentTurnId() != null || facts.rootTurnId() != null
            || facts.planRevisionId() != null || facts.runId() != null) {
            throw new SQLException("ordinary Task USER admission carries internal or new-Thread facts");
        }
    }

    /**
     * Thread seed、来源 Turn 与 Task 草稿必须是同一组冻结事实，不能由准入事务重新推导。
     */
    private void verifyFrozenTaskFacts(AgentStoreTransactionContext tx) throws SQLException {
        String threadId = tx.execution().conversation().threadId();
        if (child != null) {
            AdmissionFacts.ThreadInitialization initialized = facts.newThread().orElseThrow(() ->
                    new SQLException("Child admission is missing its Thread initialization"));
            if (!child.childThread().threadId().equals(threadId)
                || !child.childThread().workspaceId().equals(facts.workspaceId())
                || !child.childThread().workspaceId().equals(initialized.workspaceId())
                || !child.childThread().title().equals(initialized.title())
                || !ConversationAcl.toConversation(child.childThread().preferences())
                        .equals(initialized.preferences())
                || !child.childThread().createdAt().equals(initialized.createdAt())
                || !Objects.equals(child.originTurnId(), facts.parentTurnId())
                || child.originTurnId() == null || facts.rootTurnId() == null) {
                throw new SQLException("Child admission facts changed after Task creation");
            }
            return;
        }
        TaskModels.MailboxEnvelope mailbox = Objects.requireNonNull(followUp, "followUp").mailbox();
        if (facts.newThread().isPresent() || !mailbox.targetThreadId().equals(threadId)
            || !Objects.equals(mailbox.causalTurnId(), facts.parentTurnId())
            || (mailbox.causalTurnId() == null) != (facts.rootTurnId() == null)) {
            throw new SQLException("Follow-up admission facts changed after Task creation");
        }
    }

    /**
     * Follow-up 草稿只保留已授权的 Mailbox 与 Task CAS，Turn 身份来自 Core 当前 admission。
     *
     * @param mailbox 收件箱
     * @param expectedTaskRevision 预期任务修订版本
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
     */
    public record FollowUpDraft(TaskModels.MailboxEnvelope mailbox,
                                long expectedTaskRevision,
                                String activityId,
                                io.github.kongweiguang.ja.agent.domain.json.JsonObject activitySummary,
                                Optional<InitialContextSeed> initialContextSeed) {
        /**
         * 邮箱内容和 Activity 身份在注册时冻结，核心消息提交时再补入真实 Turn。
         */
        public FollowUpDraft {
            Objects.requireNonNull(mailbox, "mailbox");
            if (mailbox.kind() != TaskModels.MailboxKind.FOLLOW_UP || expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid Task follow-up admission draft");
            }
            Objects.requireNonNull(activityId, "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
            initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        }
    }

    /**
     * 只冻结同事务 CAS 水位，不缓存或复制造成下一轮输入的普通 USER 正文。
     *
     * @param expectedTaskRevision 预期任务修订版本
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
     */
    public record UserTurnDraft(long expectedTaskRevision,
                                Optional<InitialContextSeed> initialContextSeed) {
        /**
         * Task revision 与首轮 frozen seed 来自同一只读 Task 快照，负值表示状态已损坏。
         */
        public UserTurnDraft {
            if (expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid Task revision");
            }
            initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        }
    }

    /**
     * 计划执行首次续跑可写入 Task 冻结的 seed；有既有 Turn 时 seed 必须为空。
     *
     * @param expectedTaskRevision 预期任务修订版本
     * @param activityId 活动标识
     * @param activitySummary 活动摘要
     * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
     */
    public record ContinuationDraft(long expectedTaskRevision,
                                    String activityId,
                                    io.github.kongweiguang.ja.agent.domain.json.JsonObject activitySummary,
                                    Optional<InitialContextSeed> initialContextSeed) {
        /**
         * Activity CAS 与首轮 seed 必须在准入前冻结，不能在 Core 提交后补写或替换父上下文。
         */
        public ContinuationDraft {
            if (expectedTaskRevision < 0) {
                throw new IllegalArgumentException("invalid Task revision");
            }
            Objects.requireNonNull(activityId, "activityId");
            Objects.requireNonNull(activitySummary, "activitySummary");
            initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        }
    }
}



