// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.TaskMailboxPort;
import io.github.kongweiguang.ja.conversation.port.out.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实临时 SQLite 上验证 Task 跨表事务、幂等 Mailbox 与 FIFO fencing。 */
final class MybatisTaskRepositoryTest extends PersistenceTestSupport {
    private static final String WORKSPACE = "ws_task_test";
    private static final String ROOT = "thr_task_root";

    /** SIDE_TASK 会实际注入 frozen context，而不是只留下一个永不消费的 seed。 */
    @Test
    void admitsSideTaskWithEffectiveContextAndAtomicProjection() throws Exception {
        try (TestDatabase database = database("task-side")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            assertEquals(Set.of("version", "accessMode"),
                    frozen.permissionCeiling().members().keySet());
            assertEquals(new JsonText("task_access_v1"), frozen.permissionCeiling().get("version"));
            assertEquals(new JsonText("approval_required"), frozen.permissionCeiling().get("accessMode"));
            ChildFixture fixture = child("thr_task_side", "turn_task_side", "item_task_side",
                    "seed_side", "activity_side", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());

            ConversationRepository.AdmissionReceipt receipt = tasks.admitChild(fixture.child(), fixture.turn());

            assertEquals(1, receipt.threadRevision());
            TaskModels.Detail detail = tasks.readTask("thr_task_side", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.QUEUED, detail.task().projection().state());
            assertEquals(1, detail.activities().size());
            assertEquals(64, detail.contextSeed().fingerprint().length());
            try (SqlSession session = database.sessions().openSession()) {
                List<PersistenceRecords.MessageRow> messages = session.getMapper(
                        io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                        .selectMessages("thr_task_side");
                assertEquals(1, messages.size());
                assertEquals("USER", messages.getFirst().role());
            }
        }
    }

    /** Thread rename 与 Task 展示投影同事务推进版本，旧摘要不能在提交后恢复旧名称。 */
    @Test
    void projectsRenamedChildThreadTitleAcrossEveryTaskSummaryPath() throws Exception {
        try (TestDatabase database = database("task-title-projection")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ChildFixture fixture = child("thr_task_title", "turn_task_title", "item_task_title",
                    "seed_title", "activity_title", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());
            ConversationRepository.AdmissionReceipt receipt = tasks.admitChild(fixture.child(), fixture.turn());
            TaskModels.Summary before = tasks.findTask("thr_task_title").orElseThrow();
            MybatisHistoryService history = database.history(database.agentStore());

            ThreadSummary renamed = history.renameThread(
                    "thr_task_title", "新的侧边任务名称", receipt.threadRevision());

            TaskModels.Detail detail = tasks.readTask("thr_task_title", 0, 0, 20).orElseThrow();
            assertEquals("新的侧边任务名称", renamed.title());
            assertEquals(before.projection().revision() + 1, detail.task().projection().revision());
            assertEquals("新的侧边任务名称", detail.task().lineage().taskName());
            assertEquals("新的侧边任务名称", tasks.listTree(ROOT).getFirst().lineage().taskName());
            assertEquals("新的侧边任务名称",
                    tasks.listRootActivities(ROOT, 20).getFirst().task().lineage().taskName());
            assertEquals(content("完成任务"), detail.contextSeed().taskBrief());
        }
    }

    /** SUBAGENT 的 BRIEF_ONLY 不复制父消息，且过期 parent revision 整体回滚。 */
    @Test
    void keepsSubagentBriefOnlyAndRollsBackStaleParentAdmission() throws Exception {
        try (TestDatabase database = database("task-subagent")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_agent", "turn_task_agent", "item_task_agent",
                    "seed_agent", "activity_agent", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());

            assertEquals(TaskModels.Lifecycle.ATTACHED,
                    tasks.findTask("thr_task_agent").orElseThrow().lineage().lifecycle());
            assertEquals(1, messageCount(database.sessions(), "thr_task_agent"));

            ChildFixture stale = child("thr_task_stale", "turn_task_stale", "item_task_stale",
                    "seed_stale", "activity_stale", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission(), 1);
            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> tasks.admitChild(stale.child(), stale.turn()));
            assertEquals(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT, failure.code());
            assertEquals(0, threadCount(database.sessions(), "thr_task_stale"));
        }
    }

    /** 根 Timeline 重开仓储后仍只读取最近 N 条持久活动，按时间正序返回且 Child 查询为空。 */
    @Test
    void readsBoundedPersistentRootActivitiesWithoutChildHistory() throws Exception {
        try (TestDatabase database = database("task-root-activities")) {
            setupRoot(database.sessions());
            MybatisTaskRepository first = repository(database);
            ChildFixture fixture = child("thr_task_activity", "turn_task_activity", "item_task_activity",
                    "seed_activity", "activity_initial", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            first.admitChild(fixture.child(), fixture.turn());
            for (int index = 1; index <= 5; index++) {
                first.enqueueMessage(new TaskModels.MailboxEnvelope("msg_activity_" + index, ROOT,
                                "thr_task_activity", null, TaskModels.MailboxKind.MESSAGE,
                                content("消息" + index), "activity-key-" + index,
                                START.plusSeconds(index)),
                        "activity_message_" + index, summary("消息" + index));
            }
            first.close();

            MybatisTaskRepository reopened = repository(database);
            List<TaskModels.ActivityProjection> recent = reopened.listRootActivities(ROOT, 3);

            assertEquals(List.of("activity_message_3", "activity_message_4", "activity_message_5"),
                    recent.stream().map(value -> value.activity().activityId()).toList());
            assertEquals(List.of("消息3", "消息4", "消息5"), recent.stream()
                    .map(value -> ((JsonText) value.activity().summary().get("text")).value()).toList());
            assertTrue(recent.stream().allMatch(value ->
                    "thr_task_activity".equals(value.task().lineage().taskThreadId())));
            assertTrue(reopened.listRootActivities("thr_task_activity", 3).isEmpty());
        }
    }

    /** Activity 仍在但 projection 关系损坏时整次根 Timeline 读取失败，不能静默漏掉审计事实。 */
    @Test
    void failsClosedWhenRootActivityProjectionRelationshipIsMissing() throws Exception {
        try (TestDatabase database = database("task-root-activity-corrupt")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_corrupt", "turn_task_corrupt", "item_task_corrupt",
                    "seed_corrupt", "activity_corrupt", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            try (SqlSession session = database.sessions().openSession();
                 java.sql.Statement statement = session.getConnection().createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.executeUpdate("DELETE FROM task_projections WHERE task_thread_id='thr_task_corrupt'");
                session.commit();
            }

            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> tasks.listRootActivities(ROOT, 20));

            assertEquals(TaskRepositoryException.Code.INVALID_STATE, failure.code());
        }
    }

    /** QueueOnly 幂等重试不增加 Activity；Follow-up 才创建并绑定新的 Child Turn。 */
    @Test
    void separatesQueueOnlyMessageFromFollowUpTurnAdmission() throws Exception {
        try (TestDatabase database = database("task-mailbox")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_mail", "turn_task_mail", "item_task_mail",
                    "seed_mail", "activity_mail", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            Instant now = START.plusSeconds(10);
            TaskModels.MailboxEnvelope message = new TaskModels.MailboxEnvelope("msg_queue", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("调整方向"),
                    "queue-key", now);
            TaskModels.MessageEnqueueReceipt first = tasks.enqueueMessage(
                    message, "activity_queue", summary("收到新消息"));
            TaskModels.MailboxEnvelope messageRetry = new TaskModels.MailboxEnvelope("msg_queue_retry", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("调整方向"),
                    "queue-key", now.plusSeconds(1));
            TaskModels.MessageEnqueueReceipt retried = tasks.enqueueMessage(
                    messageRetry, "activity_queue_retry", summary("不会写入"));
            assertTrue(first.inserted());
            assertFalse(retried.inserted());
            assertEquals(first.mailbox().sequence(), retried.mailbox().sequence());
            assertEquals("msg_queue", retried.mailbox().messageId());
            assertEquals("thr_task_mail", first.projectionOwner().lineage().taskThreadId());
            assertEquals(first.projectionOwner(), retried.projectionOwner());
            TaskModels.MailboxEnvelope conflict = new TaskModels.MailboxEnvelope("msg_queue_conflict", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("不同内容"),
                    "queue-key", now.plusSeconds(2));
            TaskRepositoryException idempotencyConflict = assertThrows(TaskRepositoryException.class,
                    () -> tasks.enqueueMessage(conflict, "activity_queue_conflict", summary("不会写入")));
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, idempotencyConflict.code());
            assertEquals(1, turnCount(database.sessions(), "thr_task_mail"));

            TaskModels.Summary current = tasks.findTask("thr_task_mail").orElseThrow();
            ConversationRepository.TurnAdmission followTurn = turn("thr_task_mail", "turn_task_follow",
                    "item_task_follow", "继续完成", 1, START.plusSeconds(20));
            TaskModels.MailboxEnvelope follow = new TaskModels.MailboxEnvelope("msg_follow", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.FOLLOW_UP, content("继续完成"),
                    "follow-key", START.plusSeconds(20));
            TaskModels.FollowUpAdmissionReceipt admitted = tasks.admitFollowUp(
                    new TaskModels.FollowUpAdmission(follow, followTurn,
                            current.projection().revision(), "activity_follow", summary("已追加后续任务")));

            ConversationRepository.TurnAdmission retryTurn = turn("thr_task_mail", "turn_task_follow_retry",
                    "item_task_follow_retry", "不会重复创建", 2, START.plusSeconds(21));
            TaskModels.MailboxEnvelope followRetry = new TaskModels.MailboxEnvelope(
                    "msg_follow_retry", ROOT, "thr_task_mail", null, TaskModels.MailboxKind.FOLLOW_UP,
                    content("继续完成"), "follow-key", START.plusSeconds(21));
            TaskModels.FollowUpAdmissionReceipt replayedBeforeRevision =
                    tasks.findFollowUpByIdempotency(followRetry).orElseThrow();
            TaskRepositoryException contentCollision = assertThrows(TaskRepositoryException.class,
                    () -> tasks.findFollowUpByIdempotency(new TaskModels.MailboxEnvelope(
                            "msg_follow_collision", ROOT, "thr_task_mail", null,
                            TaskModels.MailboxKind.FOLLOW_UP, content("不同内容"), "follow-key",
                            START.plusSeconds(21))));
            TaskModels.FollowUpAdmissionReceipt retriedFollowUp = tasks.admitFollowUp(
                    new TaskModels.FollowUpAdmission(followRetry, retryTurn,
                            current.projection().revision(), "activity_follow_retry", summary("不会写入")));

            assertEquals(2, turnCount(database.sessions(), "thr_task_mail"));
            assertEquals("msg_follow", admitted.mailbox().messageId());
            assertEquals("turn_task_follow", admitted.admission().turnId());
            assertEquals(admitted.mailbox().messageId(), replayedBeforeRevision.mailbox().messageId());
            assertEquals(admitted.admission().turnId(), replayedBeforeRevision.admission().turnId());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, contentCollision.code());
            assertEquals(admitted.mailbox().messageId(), retriedFollowUp.mailbox().messageId());
            assertEquals(admitted.admission().turnId(), retriedFollowUp.admission().turnId());
            TaskMailboxPort.ClaimBatch followUpClaim = tasks.claimPendingMessages(
                    "thr_task_mail", "turn_task_follow", 8, START.plusSeconds(22));
            database.agentStore().consumeTaskMailbox(new ConversationRepository.TaskMailboxCommit(
                    "thr_task_mail", "turn_task_follow", TurnState.QUEUED, followUpClaim.messages(), 0,
                    START.plusSeconds(23), followTurn.initialExecution()));
            assertEquals(3, messageCount(database.sessions(), "thr_task_mail"));
            TaskModels.Detail detail = tasks.readTask("thr_task_mail", 0, 0, 20).orElseThrow();
            assertTrue(detail.mailbox().stream().anyMatch(value -> value.messageId().equals("msg_follow")
                    && value.state() == TaskModels.MailboxState.CONSUMED
                    && "turn_task_follow".equals(value.boundTurnId())));
        }
    }

    /** Child 到 root 的 QueueOnly 回执必须指向发送方投影，重试不追加 Activity 且内容漂移失败关闭。 */
    @Test
    void routesChildToRootMessageThroughSenderProjectionExactlyOnce() throws Exception {
        try (TestDatabase database = database("task-mailbox-child-root")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_sender", "turn_task_sender", "item_task_sender",
                    "seed_sender", "activity_sender", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            TaskModels.MailboxEnvelope message = new TaskModels.MailboxEnvelope(
                    "msg_child_root", "thr_task_sender", ROOT, "turn_task_sender",
                    TaskModels.MailboxKind.MESSAGE, content("阶段结果"), "child-root-key",
                    START.plusSeconds(10));

            TaskModels.MessageEnqueueReceipt first = tasks.enqueueMessage(
                    message, "activity_child_root", summary("已发送消息"));
            TaskModels.MessageEnqueueReceipt replay = tasks.enqueueMessage(
                    new TaskModels.MailboxEnvelope("msg_child_root_retry", "thr_task_sender", ROOT,
                            "turn_task_sender", TaskModels.MailboxKind.MESSAGE, content("阶段结果"),
                            "child-root-key", START.plusSeconds(11)),
                    "activity_child_root_retry", summary("不会写入"));
            TaskRepositoryException collision = assertThrows(TaskRepositoryException.class,
                    () -> tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                            "msg_child_root_collision", "thr_task_sender", ROOT, "turn_task_sender",
                            TaskModels.MailboxKind.MESSAGE, content("不同内容"), "child-root-key",
                            START.plusSeconds(12)), "activity_child_root_collision", summary("不会写入")));

            assertTrue(first.inserted());
            assertFalse(replay.inserted());
            assertEquals("thr_task_sender", first.projectionOwner().lineage().taskThreadId());
            assertEquals(first.mailbox().messageId(), replay.mailbox().messageId());
            assertEquals(2, tasks.readTask("thr_task_sender", 0, 0, 20).orElseThrow().activities().size());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, collision.code());
        }
    }

    /** 重启恢复只从父取消事实派生 ATTACHED 欠账，INDEPENDENT 侧边任务不得阻止欠账收敛。 */
    @Test
    void derivesPendingCancellationOnlyForAttachedChildren() throws Exception {
        try (TestDatabase database = database("task-cancellation-recovery")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            MybatisConversationRepository conversation = database.agentStore();
            String parentTurnId = "turn_task_parent_cancelled";
            conversation.admit(turn(ROOT, parentTurnId, "item_task_parent_cancelled",
                    "父任务", 0, START));
            long parentRevision = conversation.readThread(ROOT).orElseThrow().revision();
            ChildFixture attached = child("thr_task_attached_cancel", "turn_task_attached_cancel",
                    "item_task_attached_cancel", "seed_attached_cancel", "activity_attached_cancel",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission(),
                    parentRevision, parentTurnId);
            ChildFixture independent = child("thr_task_side_survivor", "turn_task_side_survivor",
                    "item_task_side_survivor", "seed_side_survivor", "activity_side_survivor",
                    TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT,
                    TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    tasks.freezeEffectiveContext(ROOT, parentRevision).context(), new JsonArray(List.of()),
                    permission(), parentRevision, parentTurnId);
            tasks.admitChild(attached.child(), attached.turn());
            tasks.admitChild(independent.child(), independent.turn());
            conversation.claimCancellation(ROOT, parentTurnId, parentRevision,
                    "parent cancelled", START.plusSeconds(1));

            assertEquals(List.of(new TaskModels.CancellationPropagation(ROOT, parentTurnId)),
                    tasks.pendingCancellationPropagations());

            conversation.commitTerminal(terminal("thr_task_attached_cancel", "turn_task_attached_cancel",
                    "item_task_attached_cancel_final", 2));
            assertTrue(tasks.pendingCancellationPropagations().isEmpty());
            assertEquals(TaskModels.State.QUEUED,
                    tasks.findTask("thr_task_side_survivor").orElseThrow().projection().state());
        }
    }

    /** 多条 Follow-up 可在当前 Turn 运行时各自绑定，前序终态必须保留下一条 QUEUED 投影。 */
    @Test
    void preservesQueuedProjectionAcrossMultipleBoundFollowUps() throws Exception {
        try (TestDatabase database = database("task-multiple-followups")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_multi", "turn_task_multi", "item_task_multi",
                    "seed_multi", "activity_multi", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());

            TaskModels.Summary initial = tasks.findTask("thr_task_multi").orElseThrow();
            TaskModels.FollowUpAdmissionReceipt first = tasks.admitFollowUp(new TaskModels.FollowUpAdmission(
                    new TaskModels.MailboxEnvelope("msg_multi_one", ROOT, "thr_task_multi", null,
                            TaskModels.MailboxKind.FOLLOW_UP, content("第一条后续"), "multi-one",
                            START.plusSeconds(1)),
                    turn("thr_task_multi", "turn_task_multi_one", "item_task_multi_one",
                            "第一条后续", 1, START.plusSeconds(1)),
                    initial.projection().revision(), "activity_multi_one", summary("第一条后续")));
            TaskModels.Summary afterFirst = tasks.findTask("thr_task_multi").orElseThrow();
            TaskModels.FollowUpAdmissionReceipt second = tasks.admitFollowUp(new TaskModels.FollowUpAdmission(
                    new TaskModels.MailboxEnvelope("msg_multi_two", ROOT, "thr_task_multi", null,
                            TaskModels.MailboxKind.FOLLOW_UP, content("第二条后续"), "multi-two",
                            START.plusSeconds(2)),
                    turn("thr_task_multi", "turn_task_multi_two", "item_task_multi_two",
                            "第二条后续", 2, START.plusSeconds(2)),
                    afterFirst.projection().revision(), "activity_multi_two", summary("第二条后续")));

            assertEquals("turn_task_multi_one", first.mailbox().boundTurnId());
            assertEquals("turn_task_multi_two", second.mailbox().boundTurnId());
            assertTrue(tasks.claimPendingMessages("thr_task_multi", "turn_task_multi", 8,
                    START.plusSeconds(3)).messages().isEmpty());

            MybatisConversationRepository conversation = database.agentStore();
            conversation.commitTerminal(terminal("thr_task_multi", "turn_task_multi", "item_multi_final", 4));
            assertEquals(TaskModels.State.QUEUED,
                    tasks.findTask("thr_task_multi").orElseThrow().projection().state());
            assertEquals(List.of("msg_multi_one"), tasks.claimPendingMessages(
                    "thr_task_multi", "turn_task_multi_one", 8, START.plusSeconds(5)).messages().stream()
                    .map(TaskMailboxPort.ClaimedMessage::messageId).toList());
            conversation.commitTerminal(terminal(
                    "thr_task_multi", "turn_task_multi_one", "item_multi_one_final", 6));
            assertEquals(TaskModels.State.QUEUED,
                    tasks.findTask("thr_task_multi").orElseThrow().projection().state());
            conversation.commitTerminal(terminal(
                    "thr_task_multi", "turn_task_multi_two", "item_multi_two_final", 7));
            assertEquals(TaskModels.State.COMPLETED,
                    tasks.findTask("thr_task_multi").orElseThrow().projection().state());
        }
    }

    /**
     * 安全点 claim 只绑定有界 PENDING 行，同 Turn 重入读取原批次；只有 USER facts 同事务边界
     * 调用 consume helper 并提交后才变为 CONSUMED，回滚不得丢消息。
     */
    @Test
    void claimsMailboxIdempotentlyAndConsumesOnlyAfterTransactionCommit() throws Exception {
        try (TestDatabase database = database("task-mailbox-claim")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_claim_mail", "turn_task_claim_mail", "item_task_claim_mail",
                    "seed_claim_mail", "activity_claim_mail", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_claim_first", ROOT,
                            "thr_task_claim_mail", null, TaskModels.MailboxKind.MESSAGE, content("第一条"),
                            "claim-first", START.plusSeconds(1)),
                    "activity_claim_first", summary("第一条"));
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_claim_second", ROOT,
                            "thr_task_claim_mail", null, TaskModels.MailboxKind.MESSAGE, content("第二条"),
                            "claim-second", START.plusSeconds(2)),
                    "activity_claim_second", summary("第二条"));

            ListAppender<ILoggingEvent> logs = captureLogs(MybatisTaskRepository.class);
            TaskMailboxPort.ClaimBatch first;
            try {
                first = tasks.claimPendingMessages(
                        "thr_task_claim_mail", "turn_task_claim_mail", 1, START.plusSeconds(3));
            } finally {
                detachLogs(MybatisTaskRepository.class, logs);
            }
            TaskMailboxPort.ClaimBatch retried = tasks.claimPendingMessages(
                    "thr_task_claim_mail", "turn_task_claim_mail", 1, START.plusSeconds(4));

            assertEquals(1, first.messages().size());
            assertEquals("msg_claim_first", first.messages().getFirst().messageId());
            assertEquals(first.throughSequence(), retried.throughSequence());
            assertEquals("turn_task_claim_mail", retried.messages().getFirst().boundTurnId());
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch("event=task_mailbox_lag lag_ms=2000 message_count=1"::equals));
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(value -> value.contains("第一条") || value.contains("msg_claim_first")));

            try (SqlSession session = database.sessions().openSession()) {
                assertEquals(1, TaskMailboxPersistence.consumeBoundForTurn(
                        PersistenceMappers.open(session), "turn_task_claim_mail", START.plusSeconds(5)));
                session.rollback();
            }
            assertEquals(TaskModels.MailboxState.BOUND, tasks.readTask(
                    "thr_task_claim_mail", 0, 0, 20).orElseThrow().mailbox().getFirst().state());

            try (SqlSession session = database.sessions().openSession()) {
                assertEquals(1, TaskMailboxPersistence.consumeBoundForTurn(
                        PersistenceMappers.open(session), "turn_task_claim_mail", START.plusSeconds(6)));
                session.commit();
            }
            TaskMailboxPort.ClaimBatch second = tasks.claimPendingMessages(
                    "thr_task_claim_mail", "turn_task_claim_mail", 1, START.plusSeconds(7));
            assertEquals("msg_claim_second", second.messages().getFirst().messageId());
            database.agentStore().commitTerminal(new ConversationRepository.TerminalCommit(
                    "thr_task_claim_mail", "turn_task_claim_mail", TurnState.FAILED,
                    "终态竞争", "FAILED", "终态竞争", null, null, List.of(), 0,
                    START.plusSeconds(8)));
            TaskModels.Detail detail = tasks.readTask("thr_task_claim_mail", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.MailboxState.CONSUMED, detail.mailbox().getFirst().state());
            assertEquals(TaskModels.MailboxState.PENDING, detail.mailbox().get(1).state());

            long childThreadRevision = database.agentStore()
                    .findTurn("thr_task_claim_mail", "turn_task_claim_mail").orElseThrow().threadRevision();
            ConversationRepository.TurnAdmission nextTurn = turn("thr_task_claim_mail", "turn_task_claim_next",
                    "item_task_claim_next", "继续", childThreadRevision, START.plusSeconds(9));
            TaskModels.MailboxEnvelope follow = new TaskModels.MailboxEnvelope("msg_claim_follow", ROOT,
                    "thr_task_claim_mail", null, TaskModels.MailboxKind.FOLLOW_UP, content("继续"),
                    "claim-follow", START.plusSeconds(9));
            TaskModels.Summary failed = tasks.findTask("thr_task_claim_mail").orElseThrow();
            tasks.admitFollowUp(new TaskModels.FollowUpAdmission(follow, nextTurn,
                    failed.projection().revision(), "activity_claim_follow", summary("继续")));
            TaskMailboxPort.ClaimBatch reclaimed = tasks.claimPendingMessages(
                    "thr_task_claim_mail", "turn_task_claim_next", 8, START.plusSeconds(10));
            assertEquals(List.of("msg_claim_second", "msg_claim_follow"), reclaimed.messages().stream()
                    .map(TaskMailboxPort.ClaimedMessage::messageId).toList());
        }
    }

    /** Conversation 端口逐字段重验 claim，并把 USER message、消费状态和 Turn cursor 原子提交。 */
    @Test
    void commitsClaimedMailboxAsUserMessageAtomically() throws Exception {
        try (TestDatabase database = database("task-mailbox-conversation")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_mail_commit", "turn_task_mail_commit",
                    "item_task_mail_commit", "seed_mail_commit", "activity_mail_commit",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_mail_commit", ROOT,
                            "thr_task_mail_commit", null, TaskModels.MailboxKind.MESSAGE,
                            content("来自父任务的约束"), "mail-commit", START.plusSeconds(1)),
                    "activity_mail_commit_message", summary("来自父任务的约束"));
            TaskMailboxPort.ClaimBatch claim = tasks.claimPendingMessages(
                    "thr_task_mail_commit", "turn_task_mail_commit", 8, START.plusSeconds(2));
            TaskMailboxPort.ClaimedMessage stored = claim.messages().getFirst();
            TaskMailboxPort.ClaimedMessage altered = new TaskMailboxPort.ClaimedMessage(
                    stored.sequence(), stored.messageId(), stored.rootThreadId(), stored.senderThreadId(),
                    stored.targetThreadId(), stored.causalTurnId(), stored.kind(), content("篡改内容"),
                    stored.idempotencyKey(), stored.boundTurnId());
            MybatisConversationRepository conversation = database.agentStore();

            assertThrows(StorageException.class, () -> conversation.consumeTaskMailbox(
                    new ConversationRepository.TaskMailboxCommit("thr_task_mail_commit",
                            "turn_task_mail_commit", TurnState.QUEUED, List.of(altered), 0,
                            START.plusSeconds(3), fixture.turn().initialExecution())));
            assertEquals(1, messageCount(database.sessions(), "thr_task_mail_commit"));
            assertEquals(TaskModels.MailboxState.BOUND, tasks.readTask(
                    "thr_task_mail_commit", 0, 0, 20).orElseThrow().mailbox().getFirst().state());

            ConversationRepository.TaskMailboxConsumption consumed = conversation.consumeTaskMailbox(
                    new ConversationRepository.TaskMailboxCommit("thr_task_mail_commit",
                            "turn_task_mail_commit", TurnState.QUEUED, claim.messages(), 0,
                            START.plusSeconds(4), fixture.turn().initialExecution()));

            assertEquals(1, consumed.userMessages().size());
            assertEquals("来自父任务的约束", consumed.userMessages().getFirst().message().content().stream()
                    .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                    .map(TextContent::text).findFirst().orElseThrow());
            assertEquals(1, consumed.turnMutationVersion());
            assertEquals(2, messageCount(database.sessions(), "thr_task_mail_commit"));
            assertEquals(TaskModels.MailboxState.CONSUMED, tasks.readTask(
                    "thr_task_mail_commit", 0, 0, 20).orElseThrow().mailbox().getFirst().state());
        }
    }

    /** 恢复终态扩展不伪造 FINAL_ANSWER，但会原子收敛 Child projection、Activity 与祖先统计。 */
    @Test
    void reconcilesRecoveryTerminalWithoutSyntheticFinalAnswer() throws Exception {
        try (TestDatabase database = database("task-recovery-terminal")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_recovery", "turn_task_recovery", "item_task_recovery",
                    "seed_recovery", "activity_recovery", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_recovery_pending", ROOT,
                            "thr_task_recovery", null, TaskModels.MailboxKind.MESSAGE,
                            content("恢复后继续"), "recovery-pending", START.plusSeconds(1)),
                    "activity_recovery_pending", summary("恢复后继续"));
            tasks.claimPendingMessages("thr_task_recovery", "turn_task_recovery", 8,
                    START.plusSeconds(2));

            try (SqlSession session = database.sessions().openSession()) {
                TaskRecoveryPersistence.reconcileTerminal(PersistenceMappers.open(session), database.mapper(),
                        "turn_task_recovery", TurnState.FAILED, START.plusSeconds(8)).orElseThrow();
                session.commit();
            }

            TaskModels.Detail detail = tasks.readTask("thr_task_recovery", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.FAILED, detail.task().projection().state());
            assertEquals(TaskModels.ActivityKind.FAILED, detail.activities().getLast().kind());
            assertEquals(TaskModels.MailboxState.PENDING, detail.mailbox().getFirst().state());
        }
    }

    /** StartupRecovery 必须在 Turn 暂停的同一事务同步 Child projection，并只登记原 QUEUED 身份。 */
    @Test
    void startupRecoverySuspendsChildProjectionAndRegistersReadmission() throws Exception {
        try (TestDatabase database = database("task-startup-recovery")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_startup", "turn_task_startup", "item_task_startup",
                    "seed_startup", "activity_startup", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            StartupRecoveryService recovery = database.recovery();

            ListAppender<ILoggingEvent> logs = captureLogs(TaskRecoveryPersistence.class);
            try {
                recovery.recover();
            } finally {
                detachLogs(TaskRecoveryPersistence.class, logs);
            }

            TaskModels.Detail detail = tasks.readTask("thr_task_startup", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.SUSPENDED, detail.task().projection().state());
            assertEquals(TaskModels.ActivityKind.SUSPENDED, detail.activities().getLast().kind());
            assertEquals(List.of(new StartupRecoveryService.QueuedTurn(
                    "thr_task_startup", "turn_task_startup")), recovery.queuedTurns());
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .anyMatch("event=task_recovery_suspended suspended_count=1"::equals));
            assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .noneMatch(value -> value.contains("thr_task_startup") || value.contains("完成任务")));
        }
    }

    /** Workspace 写租约严格按 claim sequence 获取，并用 fencing token 拒绝旧 owner。 */
    @Test
    void serializesWorkspaceWriteClaimsWithFencingTokens() throws Exception {
        try (TestDatabase database = database("task-claims")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_claim", "turn_task_claim", "item_task_claim",
                    "seed_claim", "activity_claim", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());

            WorkspaceWriteClaimPort.WriteClaim first = tasks.enqueue(
                    "claim_first", WORKSPACE, "thr_task_claim",
                    "turn_task_claim", 1, START.plusSeconds(1));
            WorkspaceWriteClaimPort.WriteClaim second = tasks.enqueue(
                    "claim_second", WORKSPACE, "thr_task_claim",
                    "turn_task_claim", 1, START.plusSeconds(2));
            assertTrue(first.fencingToken() < second.fencingToken());
            assertFalse(tasks.release(second.claimId(), second.fencingToken(), START.plusSeconds(2)).isPresent());
            assertFalse(tasks.tryAcquire(second.claimId(), second.fencingToken(), START.plusSeconds(3)).isPresent());
            assertEquals(WorkspaceWriteClaimPort.State.HELD,
                    tasks.tryAcquire(first.claimId(), first.fencingToken(), START.plusSeconds(3))
                            .orElseThrow().state());
            assertFalse(tasks.heartbeat(first.claimId(), first.fencingToken() + 1,
                    START.plusSeconds(4)).isPresent());
            assertEquals(WorkspaceWriteClaimPort.State.RELEASED,
                    tasks.release(first.claimId(), first.fencingToken(), START.plusSeconds(5))
                            .orElseThrow().state());
            assertEquals(WorkspaceWriteClaimPort.State.HELD,
                    tasks.tryAcquire(second.claimId(), second.fencingToken(), START.plusSeconds(6))
                            .orElseThrow().state());
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED,
                    tasks.abandon(second.claimId(), second.fencingToken(), START.plusSeconds(7))
                            .orElseThrow().state());
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED,
                    tasks.abandon(second.claimId(), second.fencingToken(), START.plusSeconds(8))
                            .orElseThrow().state());
            assertFalse(tasks.release(second.claimId(), second.fencingToken(), START.plusSeconds(9)).isPresent());
        }
    }

    /**
     * 进程代际由持久 ledger 严格递增；相同时钟和回拨时钟只作为 released_at，旧 HELD/WAITING
     * 都会在下一代际事务内废弃，同时历史 claim 保留使 fencing token 继续递增且 FIFO 可前进。
     */
    @Test
    void allocatesPersistentProcessGenerationsAndAbandonsLegacyClaims() throws Exception {
        try (TestDatabase database = database("task-process-generation")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_generation", "turn_task_generation", "item_task_generation",
                    "seed_generation", "activity_generation", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());

            long firstGeneration = tasks.beginProcessGeneration(START);
            WorkspaceWriteClaimPort.WriteClaim held = tasks.enqueue(
                    "claim_generation_held", WORKSPACE, "thr_task_generation", "turn_task_generation",
                    firstGeneration, START.plusSeconds(1));
            WorkspaceWriteClaimPort.WriteClaim waiting = tasks.enqueue(
                    "claim_generation_waiting", WORKSPACE, "thr_task_generation", "turn_task_generation",
                    firstGeneration, START.plusSeconds(2));
            assertTrue(tasks.tryAcquire(held.claimId(), held.fencingToken(), START.plusSeconds(3)).isPresent());

            long sameClockGeneration = tasks.beginProcessGeneration(START);
            assertEquals(firstGeneration + 1, sameClockGeneration);
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED,
                    tasks.abandon(held.claimId(), held.fencingToken(), START).orElseThrow().state());
            assertEquals(WorkspaceWriteClaimPort.State.ABANDONED,
                    tasks.abandon(waiting.claimId(), waiting.fencingToken(), START).orElseThrow().state());

            MybatisTaskRepository reopened = repository(database);
            long rollbackClockGeneration = reopened.beginProcessGeneration(START.minusSeconds(30));
            assertEquals(sameClockGeneration + 1, rollbackClockGeneration);
            WorkspaceWriteClaimPort.WriteClaim currentFirst = reopened.enqueue(
                    "claim_generation_current_first", WORKSPACE, "thr_task_generation", "turn_task_generation",
                    rollbackClockGeneration, START.plusSeconds(4));
            WorkspaceWriteClaimPort.WriteClaim currentSecond = reopened.enqueue(
                    "claim_generation_current_second", WORKSPACE, "thr_task_generation", "turn_task_generation",
                    rollbackClockGeneration, START.plusSeconds(5));
            assertTrue(currentFirst.fencingToken() > waiting.fencingToken());
            assertFalse(reopened.tryAcquire(currentSecond.claimId(), currentSecond.fencingToken(),
                    START.plusSeconds(6)).isPresent());
            assertEquals(WorkspaceWriteClaimPort.State.HELD,
                    reopened.tryAcquire(currentFirst.claimId(), currentFirst.fencingToken(),
                            START.plusSeconds(6)).orElseThrow().state());
            assertTrue(reopened.release(currentFirst.claimId(), currentFirst.fencingToken(),
                    START.plusSeconds(7)).isPresent());
            assertEquals(WorkspaceWriteClaimPort.State.HELD,
                    reopened.tryAcquire(currentSecond.claimId(), currentSecond.fencingToken(),
                            START.plusSeconds(8)).orElseThrow().state());
        }
    }

    /** Conversation 的唯一终态事务同时提交 Task 投影、活动和父 Mailbox，重复 winner 不增写。 */
    @Test
    void extendsConversationTerminalTransactionExactlyOnce() throws Exception {
        try (TestDatabase database = database("task-terminal")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_terminal", "turn_task_terminal", "item_task_terminal",
                    "seed_terminal", "activity_terminal", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            MybatisConversationRepository conversation = database.agentStore();
            ConversationRepository.TerminalCommit terminal = new ConversationRepository.TerminalCommit(
                    "thr_task_terminal", "turn_task_terminal", TurnState.COMPLETED, "完成",
                    null, null, "item_task_terminal_final",
                    new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("最终结果"))),
                    List.of(), 0, START.plusSeconds(20));

            conversation.commitTerminal(terminal);

            TaskModels.Detail completed = tasks.readTask("thr_task_terminal", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.COMPLETED, completed.task().projection().state());
            assertEquals(2, completed.activities().size());
            assertEquals(1, completed.mailbox().size());
            assertEquals(TaskModels.MailboxKind.FINAL_ANSWER, completed.mailbox().getFirst().kind());
            assertEquals("最终结果", completed.mailbox().getFirst().content().text());
            StorageException duplicate = assertThrows(StorageException.class,
                    () -> conversation.commitTerminal(terminal));
            assertEquals(StorageException.Code.CAS_CONFLICT, duplicate.code());
            TaskModels.Detail afterDuplicate = tasks.readTask("thr_task_terminal", 0, 0, 20).orElseThrow();
            assertEquals(2, afterDuplicate.activities().size());
            assertEquals(1, afterDuplicate.mailbox().size());
            assertEquals(1, tasks.deleteTree("thr_task_terminal",
                    afterDuplicate.task().projection().revision(), START.plusSeconds(21)));
            assertTrue(tasks.findTask("thr_task_terminal").isEmpty());
        }
    }

    /** Task 扩展失败会回滚已经执行的 Conversation Turn CAS 与最终消息，不暴露半终态。 */
    @Test
    void rollsBackConversationTerminalWhenTaskExtensionFails() throws Exception {
        try (TestDatabase database = database("task-terminal-rollback")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_rollback", "turn_task_rollback", "item_task_rollback",
                    "seed_rollback", "activity_rollback", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_conflicting_terminal",
                            "thr_task_rollback", ROOT, "turn_task_rollback", TaskModels.MailboxKind.MESSAGE,
                            content("冲突事实"), TaskTerminalPersistence.terminalIdempotencyKey(
                            "turn_task_rollback"), START.plusSeconds(10)),
                    "activity_conflicting_terminal", summary("冲突事实"));
            MybatisConversationRepository conversation = database.agentStore();

            StorageException failure = assertThrows(StorageException.class,
                    () -> conversation.commitTerminal(new ConversationRepository.TerminalCommit(
                            "thr_task_rollback", "turn_task_rollback", TurnState.COMPLETED, "完成",
                            null, null, "item_task_rollback_final",
                            new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("最终结果"))),
                            List.of(), 0, START.plusSeconds(20))));

            assertEquals(StorageException.Code.TRANSACTION, failure.code());
            assertEquals(TurnState.QUEUED,
                    conversation.findTurn("thr_task_rollback", "turn_task_rollback").orElseThrow().state());
            assertEquals(1, messageCount(database.sessions(), "thr_task_rollback"));
            TaskModels.Detail unchanged = tasks.readTask("thr_task_rollback", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.QUEUED, unchanged.task().projection().state());
            assertEquals(2, unchanged.activities().size());
            assertEquals(1, unchanged.mailbox().size());
        }
    }

    /** 已读边界按服务端 sequence 单调推进，未来 sequence 与旧 revision 均拒绝。 */
    @Test
    void advancesSeenBoundaryWithProjectionCas() throws Exception {
        try (TestDatabase database = database("task-seen")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_seen", "turn_task_seen", "item_task_seen",
                    "seed_seen", "activity_seen", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(fixture.child(), fixture.turn());
            TaskModels.Summary initial = tasks.findTask("thr_task_seen").orElseThrow();

            TaskModels.Summary seen = tasks.markSeen("thr_task_seen", initial.projection().revision(),
                    initial.projection().latestActivitySequence(), START.plusSeconds(5));

            assertEquals(0, seen.projection().unreadCount());
            assertEquals(initial.projection().latestActivitySequence(),
                    seen.projection().lastSeenActivitySequence());
            assertThrows(TaskRepositoryException.class, () -> tasks.markSeen("thr_task_seen",
                    initial.projection().revision(), initial.projection().latestActivitySequence() + 1,
                    START.plusSeconds(6)));
        }
    }

    /** 测试 owner 对 JdbcTransactionFactory 显式 commit/rollback，并保留 Task 稳定错误分类。 */
    private static MybatisTaskRepository repository(TestDatabase database) {
        MybatisUnitOfWork.SessionOwner owner = new MybatisUnitOfWork.SessionOwner() {
            /** 任一异常回滚整个 Child 事务，Task 领域错误不被 StorageException 覆盖。 */
            @Override
            public <T> T execute(SqlSessionFactory sessions, MybatisUnitOfWork.Work<T> work) {
                try (SqlSession session = sessions.openSession()) {
                    try {
                        T result = work.apply(PersistenceMappers.open(session));
                        session.commit();
                        return result;
                    } catch (Throwable failure) {
                        session.rollback();
                        if (failure instanceof TaskRepositoryException task) throw task;
                        if (failure instanceof RuntimeException runtime) throw runtime;
                        throw new StorageException(StorageException.Code.TRANSACTION,
                                "test task transaction failed", failure);
                    }
                }
            }
        };
        return new MybatisTaskRepository(database.sessions(), database.mapper(), owner);
    }

    /** 根 Workspace/Thread 使用 fresh schema 当前稳定值，不经过待测 Task adapter。 */
    private static void setupRoot(SqlSessionFactory sessions) {
        try (SqlSession session = sessions.openSession()) {
            PersistenceMappers mapper = PersistenceMappers.open(session);
            mapper.history().insertWorkspace(new PersistenceRecords.WorkspaceInsert(WORKSPACE,
                    "C:/dev/task-test", "Task test", "TRUSTED", START.toString()));
            ThreadPreferences preferences = ConversationTestFixtures.preferences();
            mapper.history().insertThread(new PersistenceRecords.ThreadInsert(ROOT, WORKSPACE, "Root",
                    preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                    preferences.accessMode().name(), preferences.collaborationMode().name(),
                    preferences.titleSource().name(), START.toString()));
            session.commit();
        }
    }

    /** Child fixture 默认冻结 parent revision 0。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, JsonObject effective,
                                      JsonArray references, JsonObject permission) {
        return child(threadId, turnId, itemId, seedId, activityId, kind, lifecycle, inheritance,
                effective, references, permission, 0);
    }

    /** 显式 parent revision 变体用于 stale admission 整体回滚测试。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, JsonObject effective,
                                      JsonArray references, JsonObject permission, long parentRevision) {
        return child(threadId, turnId, itemId, seedId, activityId, kind, lifecycle, inheritance,
                effective, references, permission, parentRevision, null);
    }

    /** 显式 origin Turn 变体用于验证 ATTACHED 取消因果链与 INDEPENDENT 隔离。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, JsonObject effective,
                                      JsonArray references, JsonObject permission, long parentRevision,
                                      String originTurnId) {
        ThreadPreferences preferences = ConversationTestFixtures.preferences();
        ConversationRepository.ThreadDefinition thread = new ConversationRepository.ThreadDefinition(
                threadId, WORKSPACE, threadId, preferences, START);
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(seedId, ROOT, originTurnId,
                parentRevision, inheritance, content("完成任务"), effective, references, permission, START);
        TaskModels.ChildAdmission admission = new TaskModels.ChildAdmission(thread, ROOT, parentRevision,
                originTurnId, threadId, kind, lifecycle, seed, activityId, summary("已派发"));
        return new ChildFixture(admission, turn(threadId, turnId, itemId, "完成任务", 0, START));
    }

    /** Turn admission 使用和生产 TurnService 相同的 runtime/execution 快照。 */
    private static ConversationRepository.TurnAdmission turn(String threadId, String turnId, String itemId,
                                                             String text, long threadRevision, Instant occurredAt) {
        UserContent content = content(text);
        return new ConversationRepository.TurnAdmission(threadId, turnId,
                itemId, new ModelMessage(ModelRole.USER, List.copyOf(content.blocks())), List.of(),
                threadRevision, occurredAt, ConversationTestFixtures.execution("cfg_test"));
    }

    /** 多 Turn 投影测试使用公开文本终态，序号只负责生成互不冲突的稳定 fixture 身份。 */
    private static ConversationRepository.TerminalCommit terminal(String threadId, String turnId,
                                                                  String itemId, long second) {
        return new ConversationRepository.TerminalCommit(threadId, turnId, TurnState.COMPLETED, "完成",
                null, null, itemId, new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("结果"))),
                List.of(), 0, START.plusSeconds(second));
    }

    /** 测试消息始终是一条规范 text block。 */
    private static UserContent content(String text) {
        return new UserContent(List.of(new TextContent(text)));
    }

    /** Activity summary 只使用生产允许的安全 text 字段。 */
    private static JsonObject summary(String text) {
        return JsonObjects.builder().putText("text", text).build();
    }

    /** 测试 seed 使用当前严格 access variant，不保留无版本旧对象。 */
    private static JsonObject permission() {
        return JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
    }

    /** 直接计数只用于确认事务回滚和 QueueOnly 不创建 Turn。 */
    private static int turnCount(SqlSessionFactory sessions, String threadId) {
        try (SqlSession session = sessions.openSession()) {
            return session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                    .selectTurns(threadId).size();
        }
    }

    /** Message count 通过现有 AgentMapper 的稳定排序查询。 */
    private static int messageCount(SqlSessionFactory sessions, String threadId) {
        try (SqlSession session = sessions.openSession()) {
            return session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                    .selectMessages(threadId).size();
        }
    }

    /** Thread 是否存在使用 HistoryMapper 权威查询，不扫描数据库文件。 */
    private static int threadCount(SqlSessionFactory sessions, String threadId) {
        try (SqlSession session = sessions.openSession()) {
            return session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper.class)
                    .selectThread(threadId) == null ? 0 : 1;
        }
    }

    /** 捕获单一采集类的结构化日志，验证指标格式而不依赖生产滚动文件。 */
    private static ListAppender<ILoggingEvent> captureLogs(Class<?> owner) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(owner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** 每个测试解除 appender，防止并行 SQLite 用例互相污染日志断言。 */
    private static void detachLogs(Class<?> owner, ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(owner)).detachAppender(appender);
        appender.stop();
    }

    /** 把 Child 与其首个 Turn admission 绑定成不可拆分夹具，避免测试构造出跨父 revision 的伪状态。 */
    private record ChildFixture(TaskModels.ChildAdmission child,
                                ConversationRepository.TurnAdmission turn) { }
}
