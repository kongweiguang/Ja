// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
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
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实临时 SQLite 上验证 Task 跨表事务、幂等 Mailbox 与 FIFO fencing。 */
final class MybatisTaskRepositoryTest extends PersistenceTestSupport {
    private static final String WORKSPACE = "ws_task_test";
    private static final String ROOT = "thr_task_root";

    /** 侧聊自身输入允许同源接纳，继承历史不应被当成本轮输入或普通跨会话消息。 */
    @Test
    void consumesFirstFollowupAfterInheritedUserHistory() throws Exception {
        try (TestDatabase database = database("inherited-followup")) {
            setupRoot(database.sessions());
            var conversation = database.agentStore();
            var tasks = repository(database);
            conversation.admit(turn(ROOT, "turn_parent", "item_parent", "PARENT", 0, START));
            conversation.commitTerminal(terminal(ROOT, "turn_parent", "item_parent_done", 1));
            idleChild(tasks, "thr_first_followup", tasks.freezeEffectiveContext(ROOT, conversation.readThread(ROOT).orElseThrow().revision()));
            var first = turn("thr_first_followup", "turn_child_first", "item_child_first", "CHILD", 0, START.plusSeconds(2));
            tasks.admitFollowUp(new TaskModels.FollowUpAdmission(new TaskModels.MailboxEnvelope("msg_child_first", "thr_first_followup",
                    "thr_first_followup", null, TaskModels.MailboxKind.FOLLOW_UP, content("CHILD"), "first-follow", START.plusSeconds(2)),
                    first, 0, "activity_child_first", summary("发送")));
            var claim = tasks.claimPendingMessages("thr_first_followup", "turn_child_first", 8, START.plusSeconds(3));
            var consumed = conversation.consumeTaskMailbox(new ConversationRepository.TaskMailboxCommit(
                    "thr_first_followup", "turn_child_first", TurnState.QUEUED, claim.messages(), 0, START.plusSeconds(4), first.initialExecution()));
            assertEquals("item_child_first", consumed.userMessages().getFirst().messageId());
            assertEquals(3, messageCount(database.sessions(), "thr_first_followup"));
        }
    }

    /** 同一父版本可以创建多个独立侧边会话，先前子任务的终态活动不应破坏新建事务。 */
    @Test
    void createsAnotherIdleTaskAfterSiblingFinishes() throws Exception {
        try (TestDatabase database = database("multiple-idle")) {
            setupRoot(database.sessions());
            var conversation = database.agentStore();
            var tasks = repository(database);
            conversation.admit(turn(ROOT, "turn_root", "item_root", "root", 0, START));
            conversation.commitTerminal(terminal(ROOT, "turn_root", "item_root_done", 1));
            long revision = conversation.readThread(ROOT).orElseThrow().revision();
            idleChild(tasks, "thr_idle_one", tasks.freezeEffectiveContext(ROOT, revision));
            conversation.admit(turn("thr_idle_one", "turn_one", "item_one", "one", 0, START.plusSeconds(2)));
            conversation.commitTerminal(terminal("thr_idle_one", "turn_one", "item_one_done", 3));
            idleChild(tasks, "thr_idle_two", tasks.freezeEffectiveContext(ROOT, revision));
            assertEquals(2, tasks.listTree(ROOT).size());
            assertEquals(TaskModels.State.IDLE, tasks.findTask("thr_idle_two").orElseThrow().projection().state());
        }
    }

    /** 改偏好/名称不消耗首轮；普通发送与 Goal/Plan 首轮都读取同一冻结历史，后续不重复复制。 */
    @Test
    void inheritsFrozenHistoryForEveryFirstTurnAfterPreferencesChange() throws Exception {
        for (String mode : List.of("followup", "user", "goal", "plan")) {
            try (TestDatabase database = database("first-context-" + mode)) {
                setupRoot(database.sessions());
                MybatisConversationRepository conversation = database.agentStore();
                MybatisTaskRepository tasks = repository(database);
                conversation.admit(turn(ROOT, "turn_parent_context", "item_parent_context", "PARENT_FROZEN_MARKER", 0, START));
                conversation.commitTerminal(terminal(ROOT, "turn_parent_context", "item_parent_done", 1));
                long parentRevision = conversation.readThread(ROOT).orElseThrow().revision();
                var frozen = tasks.freezeEffectiveContext(ROOT, parentRevision);
                String childId = "thr_context_" + mode;
                idleChild(tasks, childId, frozen);
                var history = database.history(conversation);
                history.updatePreferences(childId, ConversationTestFixtures.preferences(), 0);
                history.renameThread(childId, "renamed child", 1);
                assertEquals(0, turnCount(database.sessions(), childId));
                conversation.admit(turn(ROOT, "turn_parent_later", "item_parent_later", "PARENT_LATER_MARKER", parentRevision, START.plusSeconds(2)));
                conversation.commitTerminal(terminal(ROOT, "turn_parent_later", "item_parent_later_done", 3));
                String firstTurn = "turn_first_" + mode;
                if (mode.equals("followup")) {
                    tasks.admitFollowUp(new TaskModels.FollowUpAdmission(
                            new TaskModels.MailboxEnvelope("msg_first", ROOT, childId, null,
                                    TaskModels.MailboxKind.FOLLOW_UP, content("CHILD_INPUT"), "first-key", START.plusSeconds(4)),
                            turn(childId, firstTurn, "item_first", "CHILD_INPUT", 2, START.plusSeconds(4)),
                            tasks.findTask(childId).orElseThrow().projection().revision(), "activity_first", summary("发送")));
                } else if (mode.equals("user")) {
                    conversation.admit(turn(childId, firstTurn, "item_first", "CHILD_INPUT", 2, START.plusSeconds(4)));
                } else {
                    TurnOrigin origin = mode.equals("goal") ? TurnOrigin.GOAL_CONTINUATION : TurnOrigin.PLAN_EXECUTION;
                    conversation.admitContinuation(new ConversationRepository.ContinuationAdmission(childId, firstTurn,
                            2, START.plusSeconds(4), internalExecution(origin), "{\"kind\":\"" + origin.name() + "\"}"));
                }
                String modelHistory = conversation.readThread(childId).orElseThrow().messages().toString();
                assertTrue(modelHistory.contains("PARENT_FROZEN_MARKER"), mode);
                assertFalse(modelHistory.contains("PARENT_LATER_MARKER"), mode);
                assertFalse(history.readThread(childId, null, 200).orElseThrow().items().toString().contains("PARENT_FROZEN_MARKER"));
                conversation.commitTerminal(terminal(childId, firstTurn, "item_first_done", 5));
                long revision = conversation.readThread(childId).orElseThrow().revision();
                conversation.admit(turn(childId, "turn_second", "item_second", "SECOND_INPUT", revision, START.plusSeconds(6)));
                assertEquals(1, conversation.readThread(childId).orElseThrow().messages().stream()
                        .filter(message -> message.toString().contains("PARENT_FROZEN_MARKER")).count(), mode);
            }
        }
    }

    /** 附件通过冻结引用而非重绑授权；同工作区未被 seed 引用的附件仍不可读，嵌套 Side 也保持边界。 */
    @Test
    void inheritedAttachmentsKeepOriginalBindingAndFrozenScope() throws Exception {
        try (TestDatabase database = database("inherited-attachments")) {
            setupRoot(database.sessions());
            MybatisConversationRepository conversation = database.agentStore();
            MybatisTaskRepository tasks = repository(database);
            attachParentMessage(database, conversation, "one", 0, START);
            long revision = conversation.readThread(ROOT).orElseThrow().revision();
            idleChild(tasks, "thr_attachment_child", tasks.freezeEffectiveContext(ROOT, revision));
            attachParentMessage(database, conversation, "two", revision, START.plusSeconds(2));
            conversation.admitContinuation(new ConversationRepository.ContinuationAdmission("thr_attachment_child",
                    "turn_attachment_child", 0, START.plusSeconds(4), internalExecution(TurnOrigin.GOAL_CONTINUATION), "{}"));
            var child = conversation.readThread("thr_attachment_child").orElseThrow();
            assertTrue(child.messages().toString().contains("att_one"));
            idleChild(tasks, "thr_attachment_nested", tasks.freezeEffectiveContext("thr_attachment_child", child.revision()));
            try (SqlSession session = database.sessions().openSession()) {
                var attachments = session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper.class);
                assertNotNull(attachments.selectThreadAttachment("att_one", ROOT));
                assertEquals("item_attachment_one", attachments.selectThreadAttachment("att_one", "thr_attachment_child").boundMessageId());
                assertNotNull(attachments.selectThreadAttachment("att_one", "thr_attachment_nested"));
                assertNull(attachments.selectThreadAttachment("att_two", "thr_attachment_child"));
                assertNull(attachments.selectThreadAttachment("att_two", "thr_attachment_nested"));
            }
        }
    }

    /** 测试使用真实 idle admission 保留零 Turn，所有父身份从冻结快照取得。 */
    private static void idleChild(MybatisTaskRepository tasks, String id, TaskModels.EffectiveContextSnapshot frozen) {
        var seed = new TaskModels.ContextSeedDraft("seed_" + id, frozen.parentThreadId(), null, frozen.parentRevision(),
                TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, frozen.context(), frozen.references(), frozen.permissionCeiling(), START);
        var thread = new ConversationRepository.ThreadDefinition(id, WORKSPACE, "侧边任务", ConversationTestFixtures.preferences(), START);
        tasks.admitIdleChild(new TaskModels.ChildAdmission(thread, frozen.parentThreadId(), frozen.parentRevision(), null,
                "侧边任务", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT, seed, "activity_" + id, summary("创建")));
    }

    /** 经真实 admission 绑定附件，SQLite 的 attachment_id UNIQUE 约束必须继续成立。 */
    private static void attachParentMessage(TestDatabase database, MybatisConversationRepository conversation,
                                            String suffix, long revision, Instant at) {
        try (SqlSession session = database.sessions().openSession()) {
            var attachments = session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper.class);
            String sha = (suffix.equals("one") ? "a" : "b").repeat(64);
            attachments.insertBlob(new AttachmentRecords.BlobInsert(sha, 3, "TEXT", "text/plain", at.toString()));
            attachments.insertAttachment(new AttachmentRecords.AttachmentInsert("att_" + suffix, WORKSPACE, sha,
                    suffix + ".txt", 3, "TEXT", "text/plain", at.toString(), at.plusSeconds(3600).toString()));
            session.commit();
        }
        conversation.admit(new ConversationRepository.TurnAdmission(ROOT, "turn_attachment_" + suffix,
                "item_attachment_" + suffix, new ModelMessage(ModelRole.USER, List.of(new AttachmentContent("att_" + suffix))),
                List.of("att_" + suffix), revision, at, ConversationTestFixtures.execution("cfg_test")));
        conversation.commitTerminal(terminal(ROOT, "turn_attachment_" + suffix, "item_attachment_done_" + suffix, suffix.equals("one") ? 1 : 3));
    }

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

    /** idle 侧边任务只提交元数据/冻结上下文，不生成首轮 Turn、USER fact 或 provider execution。 */
    @Test
    void admitsIdleSideTaskWithoutTurnOrUserFact() throws Exception {
        try (TestDatabase database = database("task-idle-side")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ThreadPreferences preferences = ConversationTestFixtures.preferences();
            ConversationRepository.ThreadDefinition thread = new ConversationRepository.ThreadDefinition(
                    "thr_task_idle", WORKSPACE, "idle-side", preferences, START);
            TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft("seed_idle", ROOT, null, 0,
                    TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, frozen.context(), frozen.references(),
                    frozen.permissionCeiling(), START);
            TaskModels.ChildAdmission admission = new TaskModels.ChildAdmission(thread, ROOT, 0, null,
                    "idle-side", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT, seed,
                    "activity_idle", summary("已创建 idle-side"));

            TaskModels.Summary result = tasks.admitIdleChild(admission);

            assertEquals(TaskModels.State.IDLE, result.projection().state());
            TaskModels.Detail detail = tasks.readTask("thr_task_idle", 0, 0, 20).orElseThrow();
            assertNull(detail.contextSeed().taskBrief());
            assertEquals(TaskModels.ActivityKind.CREATED, detail.activities().getFirst().kind());
            try (SqlSession session = database.sessions().openSession()) {
                assertTrue(session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                        .selectTurns("thr_task_idle").isEmpty());
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
            assertTrue(tasks.listRootActivities(ROOT, 20).isEmpty(),
                    "侧聊改名只能改变侧聊投影，不能制造主会话活动");
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

    /** 委派方重开仓储后仍能分页读生命周期事实；测试不再用普通消息伪造任务状态活动。 */
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
                TaskModels.Summary current = first.findTask("thr_task_activity").orElseThrow();
                first.recordActivity(new TaskModels.ActivityMutation("thr_task_activity",
                        current.projection().revision(), TaskModels.State.RUNNING,
                        "activity_message_" + index, "thr_task_activity", "turn_task_activity",
                        TaskModels.ActivityKind.RESUMED, summary("消息" + index), "消息" + index,
                        START.plusSeconds(index)));
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

    /** 来源关系不等于委派关系：侧聊的创建及状态变化始终只能由侧聊自身详情读取。 */
    @Test
    void sideChatActivitiesNeverEnterSourceTimeline() throws Exception {
        try (TestDatabase database = database("side-chat-activity-isolation")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ChildFixture fixture = child("thr_task_isolated", "turn_task_isolated", "item_task_isolated",
                    "seed_isolated", "activity_isolated", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());
            tasks.admitChild(fixture.child(), fixture.turn());
            TaskModels.Summary current = tasks.findTask("thr_task_isolated").orElseThrow();
            tasks.recordActivity(new TaskModels.ActivityMutation("thr_task_isolated",
                    current.projection().revision(), TaskModels.State.RUNNING,
                    "activity_isolated_running", "thr_task_isolated", "turn_task_isolated",
                    TaskModels.ActivityKind.RESUMED, summary("正在运行"), "正在运行", START.plusSeconds(1)));
            assertTrue(tasks.listRootActivities(ROOT, 128).isEmpty());
            assertEquals(2, tasks.readTask("thr_task_isolated", 0, 0, 20).orElseThrow().activities().size());
            TaskModels.ContextSeedDraft agentSeed = new TaskModels.ContextSeedDraft(
                    "seed_side_agent", "thr_task_isolated", "turn_task_isolated", 1,
                    TaskModels.InheritanceMode.BRIEF_ONLY, content("独立委派"), null,
                    new JsonArray(List.of()), permission(), START.plusSeconds(2));
            TaskModels.ChildAdmission agent = new TaskModels.ChildAdmission(
                    new ConversationRepository.ThreadDefinition("thr_task_side_agent", WORKSPACE,
                            "侧聊的子任务", ConversationTestFixtures.preferences(), START.plusSeconds(2)),
                    "thr_task_isolated", 1, "turn_task_isolated", "侧聊的子任务",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED, agentSeed,
                    "activity_side_agent", summary("已委派"));
            tasks.admitChild(agent, turn("thr_task_side_agent", "turn_task_side_agent",
                    "item_task_side_agent", "独立委派", 0, START.plusSeconds(2)));
            assertTrue(tasks.listRootActivities(ROOT, 128).isEmpty(), "侧聊子任务不能穿透来源关系");
            assertEquals("thr_task_side_agent", tasks.listRootActivities("thr_task_isolated", 128)
                    .getFirst().task().lineage().taskThreadId());
            assertEquals(1, tasks.findTask("thr_task_isolated").orElseThrow().projection().descendantCount());
        }
    }

    /** 正在执行的父工具批次不能作为悬空调用复制到侧聊；完整结算后才能进入冻结历史。 */
    @Test
    void freezesOnlySettledToolCallPrefixes() throws Exception {
        try (TestDatabase database = database("side-chat-context-tool-boundary")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            database.agentStore().admit(turn(ROOT, "turn_frozen_tools", "item_context_question",
                    "背景问题", 0, START));
            try (SqlSession session = database.sessions().openSession()) {
                session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                        .insertMessage(new io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords.MessageInsert(
                                "item_unfinished_call", ROOT, "turn_frozen_tools", 2, "ASSISTANT",
                                "[{\"kind\":\"tool_call\",\"callId\":\"call_unfinished\",\"name\":\"shell\",\"arguments\":{}}]",
                                START.toString()));
                session.commit();
            }
            JsonArray before = (JsonArray) tasks.freezeEffectiveContext(ROOT, 1).context().get("messages");
            assertEquals(1, before.values().size());
            try (SqlSession session = database.sessions().openSession()) {
                session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.AgentMapper.class)
                        .insertMessage(new io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords.MessageInsert(
                                "item_finished_result", ROOT, "turn_frozen_tools", 3, "TOOL",
                                "[{\"kind\":\"tool_result\",\"callId\":\"call_unfinished\",\"content\":\"完成\",\"error\":false}]",
                                START.toString()));
                session.commit();
            }
            JsonArray after = (JsonArray) tasks.freezeEffectiveContext(ROOT, 1).context().get("messages");
            assertEquals(3, after.values().size());
        }
    }

    /** 空闲侧聊关闭不能依赖首轮模型请求；标记、冻结上下文与正文身份必须一并清除。 */
    @Test
    void closesIdleTemporarySideChatWithoutModelTurn() throws Exception {
        try (TestDatabase database = database("side-chat-idle-purge")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ChildFixture fixture = child("thr_task_idle_purge", "turn_unused", "item_unused",
                    "seed_idle_purge", "activity_idle_purge", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());
            tasks.admitIdleChild(fixture.child());
            assertEquals(List.of("thr_task_idle_purge"), tasks.beginSideChatClose("thr_task_idle_purge"));
            assertEquals(1, tasks.deleteClosedSideChat("thr_task_idle_purge"));
            assertTrue(tasks.findTask("thr_task_idle_purge").isEmpty());
            assertTrue(tasks.listTemporarySideChats().isEmpty());
            assertTrue(database.history(database.agentStore()).readThread("thr_task_idle_purge", null, 1).isEmpty());
            assertEquals(0, threadCount(database.sessions(), "thr_task_idle_purge"));
            assertEquals(1, threadCount(database.sessions(), ROOT));
        }
    }

    /** 已运行的侧聊仅在终态后可清理，临时正文不以不可见 tombstone 长期保留。 */
    @Test
    void purgesCompletedSideChatConversationFacts() throws Exception {
        try (TestDatabase database = database("side-chat-completed-purge")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ChildFixture fixture = child("thr_task_done_purge", "turn_done_purge", "item_done_purge",
                    "seed_done_purge", "activity_done_purge", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());
            tasks.admitChild(fixture.child(), fixture.turn());
            database.agentStore().commitTerminal(terminal("thr_task_done_purge", "turn_done_purge",
                    "item_done_purge_final", 3));
            tasks.beginSideChatClose("thr_task_done_purge");
            assertEquals(1, tasks.deleteClosedSideChat("thr_task_done_purge"));
            assertEquals(0, messageCount(database.sessions(), "thr_task_done_purge"));
            assertEquals(0, threadCount(database.sessions(), "thr_task_done_purge"));
            assertTrue(tasks.listRootActivities(ROOT, 128).isEmpty());
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

    /** QueueOnly 幂等重试不增加 Activity 或 projection revision；Follow-up 才创建并绑定新的 Child Turn。 */
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
            TaskModels.MessageEnqueueReceipt first = tasks.enqueueMessage(message);
            TaskModels.MailboxEnvelope messageRetry = new TaskModels.MailboxEnvelope("msg_queue_retry", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("调整方向"),
                    "queue-key", now.plusSeconds(1));
            TaskModels.MessageEnqueueReceipt retried = tasks.enqueueMessage(messageRetry);
            assertTrue(first.inserted());
            assertFalse(retried.inserted());
            assertEquals(first.mailbox().sequence(), retried.mailbox().sequence());
            assertEquals("msg_queue", retried.mailbox().messageId());
            assertEquals("Root", first.mailbox().senderTitle());
            TaskModels.MailboxEnvelope conflict = new TaskModels.MailboxEnvelope("msg_queue_conflict", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("不同内容"),
                    "queue-key", now.plusSeconds(2));
            TaskRepositoryException idempotencyConflict = assertThrows(TaskRepositoryException.class,
                    () -> tasks.enqueueMessage(conflict));
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

    /** Child 到 root 的 QueueOnly 只保留 Mailbox，重试不追加 Activity 且内容漂移失败关闭。 */
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

            TaskModels.MessageEnqueueReceipt first = tasks.enqueueMessage(message);
            TaskModels.MessageEnqueueReceipt replay = tasks.enqueueMessage(
                    new TaskModels.MailboxEnvelope("msg_child_root_retry", "thr_task_sender", ROOT,
                            "turn_task_sender", TaskModels.MailboxKind.MESSAGE, content("阶段结果"),
                            "child-root-key", START.plusSeconds(11)));
            TaskRepositoryException collision = assertThrows(TaskRepositoryException.class,
                    () -> tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                            "msg_child_root_collision", "thr_task_sender", ROOT, "turn_task_sender",
                            TaskModels.MailboxKind.MESSAGE, content("不同内容"), "child-root-key",
                            START.plusSeconds(12))));

            assertTrue(first.inserted());
            assertFalse(replay.inserted());
            assertEquals("thr_task_sender", first.mailbox().senderTitle());
            assertEquals(first.mailbox().messageId(), replay.mailbox().messageId());
            assertEquals(1, tasks.readTask("thr_task_sender", 0, 0, 20).orElseThrow().activities().size());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, collision.code());
        }
    }

    /** 跨 Workspace 普通消息只接受文本，并在发送方硬删除后保留目标 Mailbox 与冻结来源。 */
    @Test
    void routesCrossWorkspaceTextOnlyAndPreservesMailboxAfterSenderDeletion() throws Exception {
        try (TestDatabase database = database("task-mailbox-cross-workspace")) {
            setupRoot(database.sessions());
            setupExternalRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            ChildFixture target = child("thr_task_cross_target", "turn_task_cross_target",
                    "item_task_cross_target", "seed_cross_target", "activity_cross_target",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission());
            tasks.admitChild(target.child(), target.turn());
            TaskModels.Summary before = tasks.findTask(target.child().childThread().threadId()).orElseThrow();
            TaskModels.Detail beforeDetail = tasks.readTask(target.child().childThread().threadId(), 0, 0, 20)
                    .orElseThrow();

            TaskModels.MessageEnqueueReceipt receipt = tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                    "msg_cross_text", "thr_task_external_root", target.child().childThread().threadId(), null,
                    TaskModels.MailboxKind.MESSAGE, content("跨 Workspace 的纯文本"), "cross-text",
                    START.plusSeconds(10)));
            assertTrue(receipt.inserted());
            assertEquals(ROOT, receipt.mailbox().rootThreadId());
            assertEquals("External", receipt.mailbox().senderTitle());

            TaskModels.Summary after = tasks.findTask(target.child().childThread().threadId()).orElseThrow();
            assertEquals(before.projection().revision(), after.projection().revision());
            assertEquals(before.projection().unreadCount(), after.projection().unreadCount());
            TaskModels.Detail afterDetail = tasks.readTask(target.child().childThread().threadId(), 0, 0, 20)
                    .orElseThrow();
            assertEquals(beforeDetail.activities().size(), afterDetail.activities().size());
            assertEquals(1, afterDetail.mailbox().size());

            UserContent skillContent = new UserContent(List.of(
                    new SkillReferenceContent("skill_cross_workspace"), new TextContent("带 Skill")));
            UserContent workspaceContent = new UserContent(List.of(
                    new WorkspaceReferenceContent("ws_task_external", "notes.md",
                            WorkspaceReferenceContent.Kind.FILE), new TextContent("带路径引用")));
            UserContent attachmentContent = new UserContent(List.of(
                    new AttachmentContent("att_cross_workspace"), new TextContent("带附件")));
            assertCrossWorkspaceContentRejected(tasks, target.child().childThread().threadId(),
                    skillContent, "cross-skill");
            assertCrossWorkspaceContentRejected(tasks, target.child().childThread().threadId(),
                    workspaceContent, "cross-workspace-reference");
            assertCrossWorkspaceContentRejected(tasks, target.child().childThread().threadId(),
                    attachmentContent, "cross-attachment");

            try (SqlSession session = database.sessions().openSession();
                 java.sql.Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("DELETE FROM thread_subagent_policies WHERE thread_id='thr_task_external_root'");
                statement.executeUpdate("DELETE FROM threads WHERE thread_id='thr_task_external_root'");
                session.commit();
            }

            TaskModels.Detail surviving = tasks.readTask(target.child().childThread().threadId(), 0, 0, 20)
                    .orElseThrow();
            assertEquals(1, surviving.mailbox().size());
            assertEquals("thr_task_external_root", surviving.mailbox().getFirst().senderThreadId());
            assertEquals("External", surviving.mailbox().getFirst().senderTitle());
            assertEquals(ROOT, surviving.mailbox().getFirst().rootThreadId());

            TaskMailboxPort.ClaimBatch claim = tasks.claimPendingMessages(
                    target.child().childThread().threadId(), target.turn().turnId(), 8, START.plusSeconds(13));
            MybatisConversationRepository conversation = database.agentStore();
            ConversationRepository.TaskMailboxConsumption consumed = conversation.consumeTaskMailbox(
                    new ConversationRepository.TaskMailboxCommit(target.child().childThread().threadId(),
                            target.turn().turnId(), TurnState.QUEUED, claim.messages(), 0,
                            START.plusSeconds(14), target.turn().initialExecution()));
            assertEquals(1, consumed.messageItems().size());
            assertEquals("thr_task_external_root", consumed.messageItems().getFirst().sourceThreadId());
            assertEquals("External", consumed.messageItems().getFirst().sourceTitle());
            assertEquals("跨 Workspace 的纯文本", consumed.messageItems().getFirst().content());

            var history = database.history(conversation).readThread(
                    target.child().childThread().threadId(), null, 20).orElseThrow();
            assertTrue(history.items().stream().anyMatch(item ->
                    item instanceof io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot.ThreadMessageItem message
                            && message.sourceThreadId().equals("thr_task_external_root")
                            && message.sourceTitle().equals("External")
                            && message.content().equals("跨 Workspace 的纯文本")));
        }
    }

    /** 每个跨 Workspace 非文本尝试使用独立幂等键，验证拒绝不会物化新的 Mailbox 行。 */
    private static void assertCrossWorkspaceContentRejected(MybatisTaskRepository tasks, String targetThreadId,
                                                            UserContent content, String idempotencyKey) {
        TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                () -> tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                        "msg_" + idempotencyKey.replace('-', '_'), "thr_task_external_root", targetThreadId,
                        null, TaskModels.MailboxKind.MESSAGE, content, idempotencyKey,
                        START.plusSeconds(11))));
        assertEquals(TaskRepositoryException.Code.RELATION_INVALID, failure.code());
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
                            "claim-first", START.plusSeconds(1)));
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_claim_second", ROOT,
                            "thr_task_claim_mail", null, TaskModels.MailboxKind.MESSAGE, content("第二条"),
                            "claim-second", START.plusSeconds(2)));

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

    /** Conversation 端口逐字段重验 claim，并把 JSON 包装的 USER message、消费状态和 Turn cursor 原子提交。 */
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
                            content("来自父任务的约束"), "mail-commit", START.plusSeconds(1)));
            TaskMailboxPort.ClaimBatch claim = tasks.claimPendingMessages(
                    "thr_task_mail_commit", "turn_task_mail_commit", 8, START.plusSeconds(2));
            TaskMailboxPort.ClaimedMessage stored = claim.messages().getFirst();
            TaskMailboxPort.ClaimedMessage altered = new TaskMailboxPort.ClaimedMessage(
                    stored.sequence(), stored.messageId(), stored.rootThreadId(), stored.senderThreadId(), stored.senderTitle(),
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
            assertEquals(1, consumed.messageItems().size());
            assertEquals("thr_task_root", consumed.messageItems().getFirst().sourceThreadId());
            assertEquals("Root", consumed.messageItems().getFirst().sourceTitle());
            String encodedContext = consumed.userMessages().getFirst().message().content().stream()
                    .filter(TextContent.class::isInstance).map(TextContent.class::cast)
                    .map(TextContent::text).findFirst().orElseThrow();
            JsonNode context = new ObjectMapper().readTree(encodedContext);
            assertEquals("external_thread_message", context.path("kind").asText());
            assertEquals("thr_task_root", context.path("sourceThreadId").asText());
            assertEquals("Root", context.path("sourceTitle").asText());
            assertEquals("来自父任务的约束", context.path("message").asText());
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
                            content("恢复后继续"), "recovery-pending", START.plusSeconds(1)));
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

    /** Goal/Plan hidden Turn admission 只进入队列，并在终态提交时回传新的父 Mailbox。 */
    @Test
    void reopensCompletedSideTaskForGoalAndPlanContinuations() throws Exception {
        try (TestDatabase database = database("task-hidden-continuation")) {
            setupRoot(database.sessions());
            MybatisTaskRepository tasks = repository(database);
            MybatisConversationRepository conversation = database.agentStore();
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);

            for (TurnOrigin origin : List.of(TurnOrigin.GOAL_CONTINUATION, TurnOrigin.PLAN_EXECUTION)) {
                String suffix = origin == TurnOrigin.GOAL_CONTINUATION ? "goal" : "plan";
                String taskThreadId = "thr_task_hidden_" + suffix;
                String firstTurnId = "turn_task_hidden_" + suffix;
                ChildFixture fixture = child(taskThreadId, firstTurnId, "item_hidden_" + suffix,
                        "seed_hidden_" + suffix, "activity_hidden_" + suffix, TaskModels.Kind.SIDE_TASK,
                        TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                        frozen.context(), frozen.references(), frozen.permissionCeiling());
                tasks.admitChild(fixture.child(), fixture.turn());
                conversation.commitTerminal(terminal(taskThreadId, firstTurnId,
                        "item_hidden_final_" + suffix, 5));

                TaskModels.Summary completed = tasks.findTask(taskThreadId).orElseThrow();
                assertEquals(TaskModels.State.COMPLETED, completed.projection().state());
                long threadRevision = conversation.readThread(taskThreadId).orElseThrow().revision();
                String continuationTurnId = "turn_hidden_continuation_" + suffix;
                conversation.admitContinuation(new ConversationRepository.ContinuationAdmission(
                        taskThreadId, continuationTurnId, threadRevision, START.plusSeconds(10),
                        internalExecution(origin), "{\"kind\":\"" + origin.name() + "\"}"));

                TaskModels.Detail running = tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow();
                assertEquals(TaskModels.State.QUEUED, running.task().projection().state());
                assertEquals(null, running.task().projection().completedAt());
                assertEquals(3, running.activities().size());
                assertEquals(TaskModels.ActivityKind.RESUMED, running.activities().getLast().kind());

                conversation.commitTerminal(terminal(taskThreadId, continuationTurnId,
                        "item_hidden_continuation_final_" + suffix, 15));
                TaskModels.Detail finished = tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow();
                assertEquals(TaskModels.State.COMPLETED, finished.task().projection().state());
                assertEquals(4, finished.activities().size());
                assertTrue(finished.mailbox().stream()
                        .noneMatch(message -> continuationTurnId.equals(message.causalTurnId())
                                && message.kind() == TaskModels.MailboxKind.FINAL_ANSWER));
                try (SqlSession session = database.sessions().openSession()) {
                    assertTrue(session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskMapper.class)
                            .selectMailbox(ROOT, 0, 20).stream()
                            .noneMatch(message -> continuationTurnId.equals(message.causalTurnId())
                                    && message.kind().equals(TaskModels.MailboxKind.FINAL_ANSWER.name())));
                }
            }
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
                            "turn_task_rollback"), START.plusSeconds(10)));
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
            assertEquals(1, unchanged.activities().size());
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
        setupRoot(sessions, SubagentPolicy.defaultPolicy());
    }

    /** 根 Thread 的策略是 Child 继承测试的唯一来源，不从测试数组或模型偏好推断。 */
    private static void setupRoot(SqlSessionFactory sessions, SubagentPolicy policy) {
        try (SqlSession session = sessions.openSession()) {
            PersistenceMappers mapper = PersistenceMappers.open(session);
            mapper.history().insertWorkspace(new PersistenceRecords.WorkspaceInsert(WORKSPACE,
                    "C:/dev/task-test", "Task test", "TRUSTED", START.toString()));
            ThreadPreferences preferences = ConversationTestFixtures.preferences();
            mapper.history().insertThread(new PersistenceRecords.ThreadInsert(ROOT, WORKSPACE, "Root",
                    preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                    preferences.accessMode().name(), preferences.collaborationMode().name(),
                    preferences.titleSource().name(), START.toString()));
            mapper.subagentPolicies().insert(new PersistenceRecords.SubagentPolicyInsert(
                    ROOT, policy.enabled(), policy.providerId(), policy.modelId(), policy.reasoningLevel(), START.toString()));
            session.commit();
        }
    }

    /** 构造独立 Workspace 的根 Thread，验证跨域消息不依赖任务树 lineage。 */
    private static void setupExternalRoot(SqlSessionFactory sessions) {
        try (SqlSession session = sessions.openSession()) {
            PersistenceMappers mapper = PersistenceMappers.open(session);
            String workspaceId = "ws_task_external";
            String threadId = "thr_task_external_root";
            mapper.history().insertWorkspace(new PersistenceRecords.WorkspaceInsert(workspaceId,
                    "C:/dev/task-test-external", "External task test", "TRUSTED", START.toString()));
            ThreadPreferences preferences = ConversationTestFixtures.preferences();
            mapper.history().insertThread(new PersistenceRecords.ThreadInsert(threadId, workspaceId, "External",
                    preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                    preferences.accessMode().name(), preferences.collaborationMode().name(),
                    preferences.titleSource().name(), START.toString()));
            mapper.subagentPolicies().insert(new PersistenceRecords.SubagentPolicyInsert(
                    threadId, true, null, null, null, START.toString()));
            session.commit();
        }
    }

    /** Side Task 与 Subagent 都必须复制 parent 的不可变策略，不能回读最新全局设置。 */
    @Test
    void childAdmissionsInheritParentSubagentPolicy() throws Exception {
        SubagentPolicy parentPolicy = new SubagentPolicy(false, "provider_child", "model_child", "high");
        try (TestDatabase database = database("task-policy-inheritance")) {
            setupRoot(database.sessions(), parentPolicy);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, 0);
            ChildFixture side = child("thr_task_policy_side", "turn_task_policy_side", "item_policy_side",
                    "seed_policy_side", "activity_policy_side", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.context(), frozen.references(), frozen.permissionCeiling());
            tasks.admitChild(side.child(), side.turn());
            ChildFixture subagent = child("thr_task_policy_agent", "turn_task_policy_agent", "item_policy_agent",
                    "seed_policy_agent", "activity_policy_agent", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            tasks.admitChild(subagent.child(), subagent.turn());

            assertPolicy(database, ROOT, parentPolicy);
            assertPolicy(database, "thr_task_policy_side", parentPolicy);
            assertPolicy(database, "thr_task_policy_agent", parentPolicy);
        }
    }

    /** 直接读取策略表，验证 Child admission 的事务结果而不是内存 fixture。 */
    private static void assertPolicy(TestDatabase database, String threadId, SubagentPolicy expected)
            throws Exception {
        try (SqlSession session = database.sessions().openSession()) {
            PersistenceRecords.SubagentPolicyRow actual = PersistenceMappers.open(session)
                    .subagentPolicies().select(threadId);
            assertNotNull(actual);
            assertEquals(expected.enabled(), actual.enabled());
            assertEquals(expected.providerId(), actual.providerId());
            assertEquals(expected.modelId(), actual.modelId());
            assertEquals(expected.reasoningLevel(), actual.reasoningLevel());
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

    /** 构造与生产 hidden Turn 相同来源的 READY 游标，确保 continuation context 与 execution origin 一致。 */
    private static TurnExecutionState.Ready internalExecution(TurnOrigin origin) {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, null, List.of(),
                Instant.parse("2099-01-01T00:00:00Z"), origin), TurnExecutionState.Next.ASSISTANT, null);
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
