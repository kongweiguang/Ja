// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolPrepared;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolResult;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ToolStarted;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.Usage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsageCertainty;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.UsagePurpose;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceWriteClaimPort;
import io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskMailboxQueueInputSource;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.domain.model.ThreadDefinition;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationAcl;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.nio.file.Path;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    private static final List<String> AGENT_STORE_TABLES = List.of(
            "agent_conversation", "agent_execution", "agent_record", "agent_event", "agent_operation_receipt",
            "agent_commit_receipt", "agent_lease");
    private final Map<AgentStore.ExecutionKey, AgentStoreProjectionFixture> openExecutions = new HashMap<>();

    /** 在 TestDatabase 关闭前释放本测试登记的 execution lease，保持租约回收仍可访问 SQLite。 */
    private void releaseOpenExecutionLeases() {
        openExecutions.values().forEach(AgentStoreProjectionFixture::close);
        openExecutions.clear();
    }

    /** 将 lease 清理绑定到同一个 try-with-resources，确保它先于 TestDatabase 关闭。 */
    private final class ExecutionLeaseCleanup implements AutoCloseable {
        /** 资源按声明逆序关闭；此时 TestDatabase 仍持有可用 datasource。 */
        @Override
        public void close() {
            releaseOpenExecutionLeases();
        }
    }

    /** 侧聊自身输入允许同源接纳，继承历史不应被当成本轮输入或普通跨会话消息。 */
    @Test
    void consumesFirstFollowupAfterInheritedUserHistory() throws Exception {
        try (TestDatabase database = database("inherited-followup");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            admitUser(database, tasks, turn(ROOT, "turn_parent", "item_parent", "PARENT", 0, START));
            commitTerminal(database, terminal(ROOT, "turn_parent", "item_parent_done", 1));
            idleChild(tasks, "thr_first_followup", tasks.freezeEffectiveContext(ROOT, "turn_parent",
                    threadRevision(database.sessions(), ROOT)));
            TaskModels.MailboxEnvelope followUp = new TaskModels.MailboxEnvelope("msg_child_first", ROOT,
                    "thr_first_followup", null, TaskModels.MailboxKind.FOLLOW_UP, content("CHILD"),
                    "first-follow", START.plusSeconds(2));
            admitFollowUp(database, tasks, followUp, "turn_child_first", "item_child_first", List.of());

            TaskModels.Detail detail = tasks.readTask("thr_first_followup", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.MailboxState.CONSUMED, detail.mailbox().getFirst().state());
            assertEquals("turn_child_first", detail.mailbox().getFirst().boundTurnId());
            assertEquals(1, turnCount(database.sessions(), "thr_first_followup"));
            assertEquals(1, messageCount(database, "thr_first_followup"));
        }
    }

    /** 同一父版本可以创建多个独立侧边会话，先前子任务的终态活动不应破坏新建事务。 */
    @Test
    void createsAnotherIdleTaskAfterSiblingFinishes() throws Exception {
        try (TestDatabase database = database("multiple-idle");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            admitUser(database, tasks, turn(ROOT, "turn_root", "item_root", "root", 0, START));
            commitTerminal(database, terminal(ROOT, "turn_root", "item_root_done", 1));
            long revision = threadRevision(database.sessions(), ROOT);
            idleChild(tasks, "thr_idle_one", tasks.freezeEffectiveContext(ROOT, "turn_root", revision));
            admitUser(database, tasks, turn("thr_idle_one", "turn_one", "item_one", "one", 0,
                    START.plusSeconds(2)));
            commitTerminal(database, terminal("thr_idle_one", "turn_one", "item_one_done", 3));
            idleChild(tasks, "thr_idle_two", tasks.freezeEffectiveContext(ROOT, "turn_root", revision));
            assertEquals(2, tasks.listTree(ROOT).size());
            assertEquals(TaskModels.State.IDLE, tasks.findTask("thr_idle_two").orElseThrow().projection().state());
        }
    }

    /** 首轮上下文必须固定在创建快照；UI 元数据按真实 revision CAS 更新且不得伪造已发生的对话轮次。 */
    @Test
    void inheritsFrozenHistoryForEveryFirstTurnAfterPreferencesChange() throws Exception {
        for (String mode : List.of("followup", "user", "plan")) {
            try (TestDatabase database = database("first-context-" + mode);
                 ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
                setupRoot(database);
                MybatisTaskRepository tasks = repository(database);
                admitUser(database, tasks, turn(ROOT, "turn_parent_context", "item_parent_context",
                        "PARENT_FROZEN_MARKER", 0, START));
                commitTerminal(database, terminal(ROOT, "turn_parent_context", "item_parent_done", 1));
                long parentRevision = threadRevision(database.sessions(), ROOT);
                TaskModels.EffectiveContextSnapshot frozen =
                        tasks.freezeEffectiveContext(ROOT, "turn_parent_context", parentRevision);
                String childId = "thr_context_" + mode;
                idleChild(tasks, childId, frozen);
                MybatisHistoryService history = database.history();
                long childRevision = threadRevision(database.sessions(), childId);
                history.updatePreferences(childId, ConversationTestFixtures.preferences(), childRevision);
                history.renameThread(childId, "renamed child", childRevision + 1);
                assertEquals(0, turnCount(database.sessions(), childId));
                admitUser(database, tasks, turn(ROOT, "turn_parent_later", "item_parent_later",
                        "PARENT_LATER_MARKER", parentRevision, START.plusSeconds(2)));
                commitTerminal(database, terminal(ROOT, "turn_parent_later", "item_parent_later_done", 3));
                String firstTurn = "turn_first_" + mode;
                if (mode.equals("followup")) {
                    admitFollowUp(database, tasks, new TaskModels.MailboxEnvelope("msg_first", ROOT, childId,
                                    null, TaskModels.MailboxKind.FOLLOW_UP, content("CHILD_INPUT"), "first-key",
                                    START.plusSeconds(4)), firstTurn, "item_first", List.of());
                } else if (mode.equals("user")) {
                    admitUser(database, tasks,
                            turn(childId, firstTurn, "item_first", "CHILD_INPUT", 0, START.plusSeconds(4)));
                } else {
                    admitContinuation(database, tasks, childId, firstTurn, START.plusSeconds(4),
                            Optional.of(frozen.initialContextSeed()));
                }
                TaskModels.ContextSeed childSeed = tasks.readTask(childId, 0, 0, 20).orElseThrow().contextSeed();
                String modelHistory = childSeed.initialContextSeed().toString();
                assertTrue(modelHistory.contains("PARENT_FROZEN_MARKER"), mode);
                assertFalse(modelHistory.contains("PARENT_LATER_MARKER"), mode);
                assertFalse(history.readThread(childId, null, 200).orElseThrow().items().toString()
                        .contains("PARENT_FROZEN_MARKER"));
                commitTerminal(database, terminal(childId, firstTurn, "item_first_done", 5));
                long revision = threadRevision(database.sessions(), childId);
                admitUser(database, tasks, turn(childId, "turn_second", "item_second", "SECOND_INPUT",
                        revision, START.plusSeconds(6)));
                assertEquals(1, countInitialContextSeeds(database.sessions(), childId), mode);
            }
        }
    }

    /** 附件通过冻结引用而非重绑授权；同工作区未被 seed 引用的附件仍不可读，嵌套 Side 也保持边界。 */
    @Test
    void inheritedAttachmentsKeepOriginalBindingAndFrozenScope() throws Exception {
        try (TestDatabase database = database("inherited-attachments");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            attachParentMessage(database, "one", 0, START);
            long revision = threadRevision(database.sessions(), ROOT);
            idleChild(tasks, "thr_attachment_child",
                    tasks.freezeEffectiveContext(ROOT, "turn_attachment_one", revision));
            attachParentMessage(database, "two", revision, START.plusSeconds(2));
            admitUser(database, tasks, turn("thr_attachment_child", "turn_attachment_child",
                    "item_attachment_child", "检查继承附件", 0, START.plusSeconds(4)));
            long childRevision = threadRevision(database.sessions(), "thr_attachment_child");
            TaskModels.EffectiveContextSnapshot child = tasks.freezeEffectiveContext(
                    "thr_attachment_child", "turn_attachment_child", childRevision);
            assertTrue(child.initialContextSeed().toString().contains("att_one"));
            idleChild(tasks, "thr_attachment_nested", child);
            try (SqlSession session = database.sessions().openSession()) {
                AttachmentMapper attachments = session.getMapper(AttachmentMapper.class);
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
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft("seed_" + id, frozen.parentThreadId(), null, frozen.parentRevision(),
                TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, frozen.initialContextSeed(),
                frozen.references(), frozen.permissionCeiling(), START);
        ThreadDefinition thread = new ThreadDefinition(id, WORKSPACE, "侧边任务", ConversationAcl.fromConversation(ConversationTestFixtures.preferences()), START);
        tasks.admitIdleChild(new TaskModels.ChildAdmission(thread, frozen.parentThreadId(), frozen.parentRevision(), null,
                "侧边任务", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT, seed, "activity_" + id, summary("创建")));
    }

    /** 经真实 admission 绑定附件，SQLite 的 attachment_id UNIQUE 约束必须继续成立。 */
    private void attachParentMessage(TestDatabase database, String suffix, long revision, Instant at) {
        try (SqlSession session = database.sessions().openSession()) {
            AttachmentMapper attachments = session.getMapper(AttachmentMapper.class);
            String sha = (suffix.equals("one") ? "a" : "b").repeat(64);
            attachments.insertBlob(new AttachmentRecords.BlobInsert(sha, 3, "TEXT", "text/plain", at.toString()));
            attachments.insertAttachment(new AttachmentRecords.AttachmentInsert("att_" + suffix, WORKSPACE, sha,
                    suffix + ".txt", 3, "TEXT", "text/plain", at.toString(), at.plusSeconds(3600).toString()));
            session.commit();
        }
        try (AgentStoreProjectionFixture execution = openUserForStore(database, database.jaAgentStore(), ROOT,
                "turn_attachment_" + suffix, "item_attachment_" + suffix,
                new UserContent(List.of(new AttachmentContent("att_" + suffix))), at, revision)) {
            completeForStore(execution, "附件背景已保存", at.plusSeconds(1));
        }
    }

    /** SIDE_TASK 会实际注入 frozen context，而不是只留下一个永不消费的 seed。 */
    @Test
    void admitsSideTaskWithEffectiveContextAndAtomicProjection() throws Exception {
        try (TestDatabase database = database("task-side");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            assertEquals(Set.of("version", "accessMode"),
                    frozen.permissionCeiling().members().keySet());
            assertEquals(new JsonText("task_access_v1"), frozen.permissionCeiling().get("version"));
            assertEquals(new JsonText("approval_required"), frozen.permissionCeiling().get("accessMode"));
            ChildFixture fixture = child("thr_task_side", "turn_task_side", "item_task_side",
                    "seed_side", "activity_side", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());

            AgentStoreProjectionFixture admission = admitChild(database, tasks, fixture);

            assertEquals(1, threadRevision(database.sessions(), "thr_task_side"));
            TaskModels.Detail detail = tasks.readTask("thr_task_side", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.QUEUED, detail.task().projection().state());
            assertEquals(1, detail.activities().size());
            assertEquals(64, detail.contextSeed().fingerprint().length());
            ThreadSnapshot history = database.history().readThread("thr_task_side", null, 20).orElseThrow();
            assertEquals(1, history.items().size());
            assertTrue(history.items().getFirst() instanceof ThreadSnapshot.UserInputItem);
        }
    }

    /** idle 侧边任务只提交元数据/冻结上下文，不生成首轮 Turn、USER fact 或 provider execution。 */
    @Test
    void admitsIdleSideTaskWithoutTurnOrUserFact() throws Exception {
        try (TestDatabase database = database("task-idle-side");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            ThreadPreferences preferences = ConversationTestFixtures.preferences();
            ThreadDefinition thread = new ThreadDefinition(
                    "thr_task_idle", WORKSPACE, "idle-side", ConversationAcl.fromConversation(preferences), START);
            TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft("seed_idle", ROOT, null, 0,
                    TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null, frozen.initialContextSeed(), frozen.references(),
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
        try (TestDatabase database = database("task-title-projection");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            ChildFixture fixture = child("thr_task_title", "turn_task_title", "item_task_title",
                    "seed_title", "activity_title", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());
            admitChild(database, tasks, fixture);
            TaskModels.Summary before = tasks.findTask("thr_task_title").orElseThrow();
            MybatisHistoryService history = database.history();

            ThreadSummary renamed = history.renameThread(
                    "thr_task_title", "新的侧边任务名称", threadRevision(database.sessions(), "thr_task_title"));

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
        try (TestDatabase database = database("task-subagent");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_agent", "turn_task_agent", "item_task_agent",
                    "seed_agent", "activity_agent", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);

            assertEquals(TaskModels.Lifecycle.ATTACHED,
                    tasks.findTask("thr_task_agent").orElseThrow().lineage().lifecycle());
            assertEquals(1, messageCount(database, "thr_task_agent"));

            database.history().renameThread(ROOT, "父会话已变化", 1);
            ChildFixture stale = child("thr_task_stale", "turn_task_stale", "item_task_stale",
                    "seed_stale", "activity_stale", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission(), 1, "turn_parent_agent");
            TaskRepositoryException conflict = assertThrows(TaskRepositoryException.class,
                    () -> admitFrozenChild(database, tasks, stale, policy(database, ROOT)));
            assertEquals(TaskRepositoryException.Code.CONTEXT_REVISION_CONFLICT, conflict.code());
            assertEquals(0, threadCount(database.sessions(), "thr_task_stale"));
            assertTrue(database.agentStore().loadConversation(
                    new AgentStore.ConversationKey("ja.local", "thr_task_stale", "user_local")).isEmpty());
        }
    }

    /** 委派方重开仓储后仍能分页读生命周期事实；测试不再用普通消息伪造任务状态活动。 */
    @Test
    void readsBoundedPersistentRootActivitiesWithoutChildHistory() throws Exception {
        try (TestDatabase database = database("task-root-activities");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository first = repository(database);
            ChildFixture fixture = child("thr_task_activity", "turn_task_activity", "item_task_activity",
                    "seed_activity", "activity_initial", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, first, fixture);
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
        try (TestDatabase database = database("side-chat-activity-isolation");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            ChildFixture fixture = child("thr_task_isolated", "turn_task_isolated", "item_task_isolated",
                    "seed_isolated", "activity_isolated", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());
            admitChild(database, tasks, fixture);
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
                    new ThreadDefinition("thr_task_side_agent", WORKSPACE,
                            "侧聊的子任务", ConversationAcl.fromConversation(ConversationTestFixtures.preferences()), START.plusSeconds(2)),
                    "thr_task_isolated", 1, "turn_task_isolated", "侧聊的子任务",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED, agentSeed,
                    "activity_side_agent", summary("已委派"));
            admitChild(database, tasks, agent, turn("thr_task_side_agent", "turn_task_side_agent",
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
        try (TestDatabase database = database("side-chat-context-tool-boundary");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            AgentStoreProjectionFixture fixture = admitUser(database, tasks,
                    turn(ROOT, "turn_frozen_tools", "item_context_question", "背景问题", 0, START));
            commitToolCallProposal(fixture, database, "call_unfinished", START.plusSeconds(1));
            commitToolCallPreparedAndStarted(fixture, database, "call_unfinished", START.plusSeconds(3));
            long beforeRevision = threadRevision(database.sessions(), ROOT);
            InitialContextSeed before = tasks.freezeEffectiveContext(ROOT, "turn_frozen_tools",
                    beforeRevision).initialContextSeed();
            assertEquals(1, before.messages().size());
            commitToolCallResult(fixture, database, "call_unfinished", START.plusSeconds(4));
            long afterRevision = threadRevision(database.sessions(), ROOT);
            InitialContextSeed after = tasks.freezeEffectiveContext(ROOT, "turn_frozen_tools",
                    afterRevision).initialContextSeed();
            assertEquals(3, after.messages().size());
        }
    }

    /**
     * 空闲侧聊关闭不依赖模型请求，冲突回滚及并发重试/重开仍回放同一回执；错误按 TaskRepository gateway 分类断言。
     * @author kongweiguang
     */
    @Test
    void closesIdleTemporarySideChatWithoutModelTurn() throws Exception {
        String sideChatId = "thr_task_idle_purge";
        try (TestDatabase database = database("side-chat-idle-purge");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            ChildFixture fixture = child(sideChatId, "turn_unused", "item_unused",
                    "seed_idle_purge", "activity_idle_purge", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());
            tasks.admitIdleChild(fixture.child());
            TaskModels.SideChatCloseState closing = tasks.beginSideChatClose(sideChatId);
            assertEquals(TaskModels.SideChatClosePhase.CLOSING, closing.phase());
            assertEquals(ROOT, closing.ownerRootThreadId());
            assertEquals(List.of(sideChatId), closing.threadIds());

            TaskRepositoryException mismatch = assertThrows(TaskRepositoryException.class,
                    () -> tasks.deleteClosedSideChat(sideChatId, ROOT, List.of(sideChatId, "thr_unexpected")));
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, mismatch.code());
            assertEquals(1, threadCount(database.sessions(), sideChatId));
            try (SqlSession session = database.sessions().openSession()) {
                assertNull(PersistenceMappers.open(session).sideChatPurges().selectCloseReceipt(sideChatId));
            }

            TaskModels.SideChatCloseReceipt expected =
                    new TaskModels.SideChatCloseReceipt(sideChatId, ROOT, List.of(sideChatId));
            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                MybatisTaskRepository competing = repository(database);
                Future<TaskModels.SideChatCloseReceipt> first = executor.submit(
                        () -> tasks.deleteClosedSideChat(sideChatId, ROOT, closing.threadIds()));
                Future<TaskModels.SideChatCloseReceipt> second = executor.submit(
                        () -> competing.deleteClosedSideChat(sideChatId, ROOT, closing.threadIds()));
                assertEquals(expected, first.get(10, TimeUnit.SECONDS));
                assertEquals(expected, second.get(10, TimeUnit.SECONDS));
            }
            assertTrue(tasks.findTask("thr_task_idle_purge").isEmpty());
            assertTrue(tasks.listTemporarySideChats().isEmpty());
            assertTrue(database.history().readThread(sideChatId, null, 1).isEmpty());
            assertEquals(0, threadCount(database.sessions(), "thr_task_idle_purge"));
            assertEquals(1, threadCount(database.sessions(), ROOT));
        }

        try (TestDatabase reopened = database("side-chat-idle-purge")) {
            MybatisTaskRepository tasks = repository(reopened);
            TaskModels.SideChatCloseState closed = tasks.beginSideChatClose(sideChatId);
            assertEquals(TaskModels.SideChatClosePhase.CLOSED, closed.phase());
            assertEquals(ROOT, closed.ownerRootThreadId());
            assertEquals(List.of(sideChatId), closed.threadIds());
            assertEquals(new TaskModels.SideChatCloseReceipt(sideChatId, ROOT, List.of(sideChatId)),
                    tasks.deleteClosedSideChat(sideChatId, ROOT, closed.threadIds()));
        }
    }

    /**
     * 已运行的临时侧聊仅在终态后清理；fork child 必须持有独立冻结历史并在来源清理后仍可读取。
     * @author kongweiguang
     */
    @Test
    void purgesCompletedSideChatConversationFacts() throws Exception {
        try (TestDatabase database = database("side-chat-completed-purge");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRootForStore(database);
            MybatisTaskRepository tasks = repository(database);
            MybatisHistoryService history = database.history();
            history.bindThreadForkSnapshots(tasks);
            AgentStore store = database.jaAgentStore();
            String parentTurnId = "turn_side_purge_parent";
            try (AgentStoreProjectionFixture parent = openUserForStore(database, store, ROOT,
                    parentTurnId, "item_side_purge_parent", content("创建一个临时侧聊"), START, 0)) {
                completeForStore(parent, "父会话保持可用", START.plusSeconds(1));
            }

            long parentRevision = database.history().readThread(ROOT, null, 20).orElseThrow().thread().revision();
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, parentTurnId,
                    parentRevision);
            String sideChatId = "thr_task_done_purge";
            try (AgentStoreProjectionFixture sideChat = admitSideChatForStore(database, store, tasks,
                    sideChatId, parentTurnId, parentRevision, frozen)) {
                completeForStore(sideChat, "临时侧聊已完成", START.plusSeconds(10));
            }

            ThreadSnapshot settledSideChat = history.readThread(sideChatId, null, 32).orElseThrow();
            String settledReplyId = settledSideChat.items().stream()
                    .filter(ThreadSnapshot.TextItem.class::isInstance)
                    .map(ThreadSnapshot.TextItem.class::cast)
                    .filter(item -> item.kind() == ThreadSnapshot.TextKind.FINAL_ANSWER)
                    .map(ThreadSnapshot.TextItem::itemId)
                    .reduce((left, right) -> right).orElseThrow();
            ThreadUseCase.ThreadForkResult descendant = history.forkThread(new ThreadUseCase.ThreadForkRequest(
                    sideChatId, settledReplyId, settledSideChat.thread().revision(), "op_" + "8".repeat(32)));
            assertEquals(2, history.readThread(descendant.thread().threadId(), null, 32).orElseThrow().items().size());

            TaskModels.SideChatCloseState closing = tasks.beginSideChatClose(sideChatId);
            assertEquals(TaskModels.SideChatClosePhase.CLOSING, closing.phase());
            assertEquals(ROOT, closing.ownerRootThreadId());
            TaskModels.SideChatCloseReceipt receipt = tasks.deleteClosedSideChat(sideChatId, ROOT,
                    closing.threadIds());
            assertEquals(List.of(sideChatId), receipt.closedThreadIds());
            assertEquals(ROOT, receipt.ownerRootThreadId());
            for (String table : AGENT_STORE_TABLES) {
                assertEquals(0, agentStoreRowCount(database.sessions(), table, sideChatId),
                        "purged Side Chat Store rows in " + table);
                assertTrue(agentStoreRowCount(database.sessions(), table, ROOT) > 0,
                        "ordinary parent conversation remains in " + table);
            }
            assertEquals(0, threadCount(database.sessions(), sideChatId));
            assertEquals(1, threadCount(database.sessions(), ROOT));
            assertTrue(tasks.listRootActivities(ROOT, 128).isEmpty());
            assertTrue(history.readThread(sideChatId, null, 32).isEmpty());
            ThreadSnapshot survivingDescendant = history.readThread(descendant.thread().threadId(), null, 32)
                    .orElseThrow(() -> new AssertionError("forked history must outlive temporary source cleanup"));
            assertEquals(2, survivingDescendant.items().size());
            assertTrue(survivingDescendant.items().stream().allMatch(ThreadSnapshot.OriginItem.class::isInstance));
            assertTrue(history.findForFirstTurn(descendant.thread().threadId()).isPresent());
        }
    }

    /** 在真实 History owner 中创建普通主会话，避免 purge 测试绕过生产 Thread 锚点。 */
    private void setupRootForStore(TestDatabase database) {
        database.history().register(new Workspace.Registration(WORKSPACE, temp.toAbsolutePath(), "Side Chat purge test", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(ROOT, WORKSPACE, "Root",
                ConversationTestFixtures.preferences(), START));
    }

    /** 普通 USER admission 使用 Core submit 回执；Ja 的 turn/start 方法仍保留在业务 admission facts 中。 */
    private AgentStoreProjectionFixture openUserForStore(TestDatabase database, AgentStore store,
            String threadId, String turnId, String messageId, UserContent input, Instant acceptedAt,
            long expectedRevision) {
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, turnId, "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.USER, "turn/start",
                acceptedAt, null, null, null, null, null, null, null, expectedRevision, Optional.empty());
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(input), messageId, Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turnId, "agent.submit", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.empty());
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts,
                ConversationTestFixtures.preferences(), expectedRevision);
    }

    /** Child admission 把冻结 seed 与 USER 输入一同提交，避免测试构造出生产不允许的半成准入。 */
    private AgentStoreProjectionFixture admitSideChatForStore(TestDatabase database, AgentStore store,
            MybatisTaskRepository tasks, String threadId, String parentTurnId, long parentRevision,
            TaskModels.EffectiveContextSnapshot frozen) {
        Instant acceptedAt = START.plusSeconds(5);
        ThreadPreferences preferences = ConversationTestFixtures.preferences();
        UserContent brief = content("只用于验证临时侧聊生命周期");
        String suffix = threadId.substring(threadId.lastIndexOf('_') + 1);
        TaskModels.ChildAdmission child = new TaskModels.ChildAdmission(
                new ThreadDefinition(threadId, WORKSPACE, "Temporary side chat", ConversationAcl.fromConversation(preferences), acceptedAt),
                ROOT, parentRevision, parentTurnId, "Temporary side chat", TaskModels.Kind.SIDE_TASK,
                TaskModels.Lifecycle.INDEPENDENT,
                new TaskModels.ContextSeedDraft("seed_purge_" + suffix, ROOT, parentTurnId, parentRevision,
                        TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, brief, frozen.initialContextSeed(), frozen.references(),
                        frozen.permissionCeiling(), acceptedAt),
                "activity_purge_" + suffix, summary("侧聊已创建"));
        AgentIdentity identity = new AgentIdentity("ja.local", threadId, "turn_done_purge", "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.CHILD_TASK, "task/create",
                acceptedAt, null, parentTurnId, parentTurnId, null, null, null, null, 0,
                Optional.of(new AdmissionFacts.ThreadInitialization(WORKSPACE, "Temporary side chat",
                        preferences, acceptedAt)));
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forChild(
                tasks, facts, child, SubagentPolicy.defaultPolicy(), new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(brief), "item_done_purge",
                Optional.empty(), new AgentStore.OperationIdentity("operation_turn_done_purge", "agent.submit", "b".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), Optional.ofNullable(child.contextSeed().initialContextSeed()));
        return AgentStoreProjectionFixture.open(store, database.mapper(), request, facts, preferences, 0,
                participant);
    }

    /** Core Terminal 唯一提交助手正文、真实用量与任务投影，测试不再调用 Ja 侧旧终态写入口。 */
    private static AgentStore.CommitReceipt completeForStore(AgentStoreProjectionFixture fixture,
                                                              String finalText, Instant occurredAt) {
        return completeForStore(fixture, finalText, finalText, occurredAt);
    }

    /** Usage 先按 UNKNOWN 登记再结算为 KNOWN，符合请求级计量状态机且终态精确引用结算事实。 */
    private static AgentStore.CommitReceipt completeForStore(AgentStoreProjectionFixture fixture,
            String finalText, String terminalSummary, Instant occurredAt) {
        String turnId = fixture.execution().turnId();
        String modelRequestId = "modelreq_terminal_" + turnId;
        AgentStore.RecordWrite pendingUsage = fixture.record("agent.usage", "terminal-usage-pending",
                new Usage(modelRequestId, null, 1, 1, UsagePurpose.ASSISTANT,
                        UsageCertainty.UNKNOWN, modelRequestIdentity(), null), occurredAt);
        TurnExecutionState.Ready pendingCursor = new TurnExecutionState.Ready(
                new TurnExecutionState.Common(0, 0, 1, "prompt_test", List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
        fixture.commitRecords(TurnState.RUNNING, pendingCursor, List.of(pendingUsage), List.of(), occurredAt);
        AgentStore.RecordWrite assistant = fixture.record("agent.assistant", "side-chat-final",
                new AssistantMessage("message_final_" + turnId, new ModelMessage(ModelRole.ASSISTANT,
                        List.of(new TextContent(finalText))), finalText, null, 1), occurredAt);
        AgentStore.RecordWrite usage = fixture.record("agent.usage", "terminal-usage",
                new Usage(modelRequestId, new ModelUsage(1, 1, 2), 1, 1,
                        UsagePurpose.ASSISTANT, UsageCertainty.KNOWN, modelRequestIdentity(), null), occurredAt);
        TurnExecutionState.Ready cursor = new TurnExecutionState.Ready(
                new TurnExecutionState.Common(1, 0, 2, "prompt_test", List.of()),
                TurnExecutionState.Next.COMPLETION, null);
        return fixture.commitRecords(TurnState.COMPLETED, cursor,
                List.of(assistant, usage, fixture.knownEmptyChangeSet(occurredAt)),
                List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, terminalSummary, null, null,
                        assistant.recordId(), usage.recordId())), occurredAt);
    }

    /** 不合成最终助手事实，验证 Core FAILED 终态仅携带真实错误并释放未消费邮箱。 */
    private void failForStore(AgentStoreProjectionFixture fixture, String errorCode,
                              String errorMessage, Instant occurredAt) {
        fixture.commitRecords(TurnState.FAILED, readyCursor(), List.of(),
                List.of(new ExecutionEvent.Terminal(TurnState.FAILED, "执行失败", errorCode,
                        errorMessage, null, null)), occurredAt);
        closeExecution(fixture);
    }

    /** 取消请求与执行事件同 Store 控制事务持久化，Ja 传播测试不直接写 turns 状态列。 */
    private void requestCancellation(TestDatabase database, AgentStoreProjectionFixture fixture,
                                     String reasonCode, Instant occurredAt) {
        AgentStore store = database.jaAgentStore();
        AgentStore.ConversationSnapshot conversation = store.loadConversation(fixture.execution().conversation())
                .orElseThrow();
        AgentStore.ExecutionSnapshot execution = store.loadExecution(fixture.execution()).orElseThrow();
        String operationKey = "cancel_" + fixture.execution().turnId().substring("turn_".length());
        String recordId = fixture.recordId("agent.execution.cancel-request", operationKey);
        AgentStore.RecordWrite request = fixture.controls().encodeCancelRequest(recordId,
                new ControlRequest(operationKey, reasonCode, occurredAt));
        ExecutionEvent.ControlRecorded event = new ExecutionEvent.ControlRecorded(
                ExecutionEvent.ControlAction.CANCEL_REQUESTED, recordId, operationKey, reasonCode);
        AgentStore.OperationIdentity operation = new AgentStore.OperationIdentity(operationKey, "turn/cancel", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}"));
        store.controlCommit(new AgentStore.ControlCommit(fixture.execution(), conversation.revision(),
                execution.state(), execution.mutationVersion(), AgentStore.ControlKind.CANCEL_REQUEST,
                List.of(request), List.of(fixture.event("cancel-request", event, occurredAt)), operation,
                Optional.empty(), occurredAt));
        closeExecution(fixture);
    }

    /** 终态只记录真实取消，不伪造答案或失败正文。 */
    private void cancelForStore(AgentStoreProjectionFixture fixture, Instant occurredAt) {
        fixture.commitRecords(TurnState.CANCELLED, readyCursor(), List.of(),
                List.of(new ExecutionEvent.Terminal(TurnState.CANCELLED, "已取消", null, null, null, null)),
                occurredAt);
        closeExecution(fixture);
    }

    /** 让测试显式拥有的 fixture 释放租约；关闭数据库仍由外层 TestDatabase 管理。 */
    private void closeExecution(AgentStoreProjectionFixture fixture) {
        openExecutions.remove(fixture.execution());
        fixture.close();
    }

    /** 真实 Store fixture 按当前快照 cursor 提交状态，使回归覆盖可持久化的 Suspended 与 Running Turn。 */
    private static void commitState(TestDatabase database, AgentStoreProjectionFixture fixture,
                                    TurnState target, Instant occurredAt) {
        AgentStore.ExecutionSnapshot snapshot = database.agentStore().loadExecution(fixture.execution()).orElseThrow();
        TurnExecutionState cursor = new TurnExecutionStateCodec(database.mapper())
                .read(snapshot.cursorSchemaVersion(), snapshot.cursor());
        fixture.commit(target, cursor, List.of(), List.of(), occurredAt);
    }

    /** 邮箱只经只读来源转为初始队列命令，绑定仍由 Store admission participant 原子完成。 */
    private static List<AgentInputCommand.Enqueue> mailboxInputs(TestDatabase database,
            MybatisTaskRepository tasks, String threadId) {
        return new TaskMailboxQueueInputSource(tasks, new JacksonJsonValueCodec(database.mapper()))
                .loadPendingInputs(threadId, 8);
    }

    /** 消费绑定的 MESSAGE 时，命令、USER 事实与事件共用 Core Store commit。 */
    private static void consumeQueuedMailboxMessage(TestDatabase database,
            AgentStoreProjectionFixture fixture, AgentInputCommand.Enqueue input, long queueRevision,
            Instant occurredAt) {
        String messageId = AgentRecordIds.derive(fixture.execution(), "agent.input.message-id",
                input.operationKey());
        String commandId = fixture.recordId("agent.input.queue.command", "consume:" + input.operationKey());
        AgentInputQueue.Command command = new AgentInputQueue.Command(commandId,
                AgentInputQueue.Action.CONSUME, input.inputId(), null, 1, queueRevision,
                null, null, occurredAt, Map.of());
        StorePayloadCodec codec = new StorePayloadCodec(database.mapper());
        List<AgentStore.RecordWrite> records = List.of(
                codec.encodeUser(fixture.recordId("agent.user.message", "consumed:" + input.operationKey()),
                        messageId, input.content(), occurredAt),
                fixture.controls().encodeInputQueueCommand(commandId, command, occurredAt));
        fixture.commitRecords(TurnState.RUNNING, readyCursor(), records,
                List.of(new ExecutionEvent.InputAccepted(messageId)), occurredAt);
    }

    /** 测试提交与 admission 相同的 READY 游标，避免为构造 Ja 事实自造执行协议状态。 */
    private static TurnExecutionState.Ready readyCursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, "prompt_test", List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** Tool proposal 与调用意图使用真实 typed facts，让上下文冻结规则依据已提交记录判断完整前缀。 */
    private static void commitToolCallProposal(AgentStoreProjectionFixture fixture, TestDatabase database,
                                               String callId, Instant occurredAt) {
        JsonObject arguments = JsonObjects.builder().putText("command", "echo context").build();
        AgentStore.RecordWrite pendingUsage = fixture.record("agent.usage", "usage-pending-" + callId,
                new Usage("modelreq_" + callId, null, 1, 1, UsagePurpose.ASSISTANT,
                        UsageCertainty.UNKNOWN, modelRequestIdentity(), null), occurredAt);
        TurnExecutionState.Ready initial = contextCursor(0, 1);
        fixture.commitRecords(TurnState.RUNNING, initial, List.of(pendingUsage), List.of(), occurredAt);

        Instant completedAt = occurredAt.plusSeconds(1);
        ToolCallContent call = new ToolCallContent(callId, "shell", arguments);
        AssistantMessage assistantFact = new AssistantMessage("message_" + callId,
                new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("正在处理"), call)),
                "正在处理", null, 1);
        AgentStore.RecordWrite assistant = fixture.record("agent.assistant", "assistant-" + callId,
                assistantFact, completedAt);
        AgentStore.RecordWrite usage = fixture.record("agent.usage", "usage-completed-" + callId,
                new Usage("modelreq_" + callId, new ModelUsage(12, 3, 15), 1, 1,
                        UsagePurpose.ASSISTANT, UsageCertainty.KNOWN, modelRequestIdentity(), null), completedAt);
        fixture.commitRecords(TurnState.RUNNING, contextCursor(1, 2), List.of(assistant, usage),
                List.of(new ExecutionEvent.ModelCompleted(assistant.recordId(), 1,
                        List.of(callId), usage.recordId())), completedAt);

        ToolPresentation proposal = new ToolPresentation(ToolPresentation.Kind.SHELL, "shell",
                ToolPresentation.Status.PENDING, "等待参数校验", null, null, List.of(), List.of(),
                null, null, null, null, null, null, false, null);
        AgentStore.RecordWrite prepared = fixture.record("agent.tool.prepared", "prepared-" + callId,
                new ToolPrepared("batch_" + callId, callId, "shell", arguments, 0,
                        ToolSideEffect.EXTERNAL, toolBinding(), false), completedAt.plusSeconds(1));
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Tools(contextCommon(1, 2), "batch_" + callId,
                        assistantFact.messageId(), 0, 0, 0), List.of(prepared,
                        toolPresentationFact(fixture, callId, 0, ToolState.PREPARED, proposal,
                                completedAt.plusSeconds(1))), List.of(), completedAt.plusSeconds(1));
    }

    /** 未有结果时仍提交真实派发意图；冻结上下文只能排除未完成调用，不能把 RUNNING 伪作结果。 */
    private static void commitToolCallPreparedAndStarted(AgentStoreProjectionFixture fixture,
            TestDatabase database, String callId, Instant occurredAt) {
        String batchId = "batch_" + callId;
        fixture.commitRecords(TurnState.RUNNING,
                new TurnExecutionState.Tools(contextCommon(1, 2), batchId,
                        "message_" + callId, 0, 0, 0),
                List.of(fixture.record("agent.tool.started", "started-" + callId,
                        new ToolStarted(callId, 0), occurredAt)),
                List.of(new ExecutionEvent.ToolStarted(callId, batchId, "shell", 0)), occurredAt);
    }

    /** ToolResult、模型可见 ToolMessage 和终态事件同一提交，已完整调用才进入子任务冻结历史。 */
    private static void commitToolCallResult(AgentStoreProjectionFixture fixture, TestDatabase database,
                                             String callId, Instant occurredAt) {
        String output = "完成";
        String toolMessageId = "item_tool_" + callId;
        AgentStore.RecordWrite result = fixture.record("agent.tool.result", "result-" + callId,
                new ToolResult(callId, ToolState.SUCCEEDED, output, false, output), occurredAt);
        AgentStore.RecordWrite message = fixture.record("agent.tool.message", "message-" + callId,
                new ToolMessage(toolMessageId, new ModelMessage(ModelRole.TOOL,
                        List.of(new ToolResultContent(callId, output, false)))), occurredAt);
        ToolPresentation completed = new ToolPresentation(ToolPresentation.Kind.SHELL, "shell",
                ToolPresentation.Status.SUCCESS, "echo context", output, "命令已完成", List.of(), List.of(),
                "echo context", ".", output, null, 0, 1L, false, null);
        fixture.commitRecords(TurnState.RUNNING, contextCursor(1, 2), List.of(result, message,
                        toolPresentationFact(fixture, callId, 1, ToolState.SUCCEEDED, completed, occurredAt)),
                List.of(new ExecutionEvent.ToolCompleted(callId, ToolState.SUCCEEDED,
                        result.recordId(), message.recordId(), null)), occurredAt);
    }

    /** 每个模型事实都使用完整 profile 字段，避免简化 fixture 掩盖 Ja usage 投影缺值。 */
    private static ModelRequestIdentity modelRequestIdentity() {
        JsonObject attributes = JsonObjects.builder().putText("accessMode", "APPROVAL_REQUIRED")
                .putText("collaborationMode", "DEFAULT").putText("configGeneration", "cfg_test")
                .putText("promptRevision", "prompt_test").putText("toolCatalogRevision", "b".repeat(64))
                .putText("bindingFingerprint", "c".repeat(64)).build();
        return new ModelRequestIdentity("provider_test", "model_test", "openai_responses", "model_test",
                "medium", "medium", 100_000, 8_192, attributes);
    }

    /** 构造与执行核心一致的单轮 READY 游标，轮次和供应商 ordinal 不靠消息扫描推断。 */
    private static TurnExecutionState.Ready contextCursor(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Ready(contextCommon(modelRound, nextProviderOrdinal),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 多阶段测试共享同一计数向量，保证 provider 回合更新单调。 */
    private static TurnExecutionState.Common contextCommon(int modelRound, int nextProviderOrdinal) {
        return new TurnExecutionState.Common(modelRound, 0, nextProviderOrdinal, "prompt_test", List.of());
    }

    /** ToolPrepared 持有显式安全执行绑定，展示投影不能用裸 tool name 代替真实授权事实。 */
    private static ToolExecutionBinding toolBinding() {
        return new ToolExecutionBinding("shell", "1".repeat(64), "2".repeat(64), "3".repeat(64),
                JsonObjects.builder().putText("routeKind", "BUILTIN").putText("localName", "shell")
                        .putText("serverId", "local_server").putText("remoteName", "shell")
                        .putText("schemaHash", "1".repeat(64)).putText("routeHash", "4".repeat(64))
                        .putText("accessMode", "APPROVAL_REQUIRED").build());
    }

    /** 宿主 presentation extension 与工具事实同一 Store commit，不能通过拼接 Ja 时间线绕过 CAS。 */
    private static AgentStore.RecordWrite toolPresentationFact(AgentStoreProjectionFixture fixture,
            String callId, long revision, ToolState state, ToolPresentation presentation, Instant occurredAt) {
        JsonObject payload = JsonObjects.builder().putText("callId", callId).putNumber("revision", revision)
                .putText("state", state.name()).putText("presentationJson",
                        new ToolPresentationCodec(new ObjectMapper()).write(presentation)).build();
        return fixture.extensionFact("agent.extension.fact", "presentation:" + callId + ':' + revision,
                "ja.tool", "presentation", 1, payload, occurredAt);
    }

    /** 表名仅来自固定白名单，参数化会话身份避免为表名绑定而引入动态 SQL 注入面。 */
    private static int agentStoreRowCount(SqlSessionFactory sessions, String table, String threadId)
            throws SQLException {
        if (!AGENT_STORE_TABLES.contains(table)) {
            throw new IllegalArgumentException("unknown AgentStore table");
        }
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE tenant_id=? AND thread_id=?";
        try (SqlSession session = sessions.openSession();
             PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
            statement.setString(1, "ja.local");
            statement.setString(2, threadId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("AgentStore row count returned no row");
                }
                return rows.getInt(1);
            }
        }
    }

    /**
     * Activity 仍在但 projection 关系损坏时整次根 Timeline 读取失败，直接检查 repository gateway 的稳定错误。
     * @author kongweiguang
     */
    @Test
    void failsClosedWhenRootActivityProjectionRelationshipIsMissing() throws Exception {
        try (TestDatabase database = database("task-root-activity-corrupt");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_corrupt", "turn_task_corrupt", "item_task_corrupt",
                    "seed_corrupt", "activity_corrupt", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
            try (SqlSession session = database.sessions().openSession();
                 java.sql.Statement statement = session.getConnection().createStatement()) {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.executeUpdate("DELETE FROM ja_task_projection WHERE task_thread_id='thr_task_corrupt'");
                session.commit();
            }

            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> tasks.listRootActivities(ROOT, 20));
            assertEquals(TaskRepositoryException.Code.INVALID_STATE, failure.code());
        }
    }

    /**
     * MESSAGE 与 FOLLOW_UP 分别提交 USER 历史并使用不同准入语义；冲突保留 TaskRepository gateway 分类。
     * @author kongweiguang
     */
    @Test
    void separatesQueueOnlyMessageFromFollowUpTurnAdmission() throws Exception {
        try (TestDatabase database = database("task-mailbox");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_mail", "turn_task_mail", "item_task_mail",
                    "seed_mail", "activity_mail", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
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
            String authoritativeRootTitle = database.history().readThread(ROOT, null, 20)
                    .orElseThrow().thread().title();
            assertEquals(authoritativeRootTitle, first.mailbox().senderTitle());
            TaskModels.MailboxEnvelope conflict = new TaskModels.MailboxEnvelope("msg_queue_conflict", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.MESSAGE, content("不同内容"),
                    "queue-key", now.plusSeconds(2));
            TaskRepositoryException idempotencyConflict = assertThrows(TaskRepositoryException.class,
                    () -> tasks.enqueueMessage(conflict));
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, idempotencyConflict.code());
            assertEquals(1, turnCount(database.sessions(), "thr_task_mail"));
            commitTerminal(database, terminal("thr_task_mail", "turn_task_mail", "item_task_mail_done", 15));

            TaskModels.MailboxEnvelope follow = new TaskModels.MailboxEnvelope("msg_follow", ROOT,
                    "thr_task_mail", null, TaskModels.MailboxKind.FOLLOW_UP, content("继续完成"),
                    "follow-key", START.plusSeconds(20));
            TaskModels.MailboxEnvelope followRetry = new TaskModels.MailboxEnvelope(
                    "msg_follow_retry", ROOT, "thr_task_mail", null, TaskModels.MailboxKind.FOLLOW_UP,
                    content("继续完成"), "follow-key", START.plusSeconds(21));
            List<AgentInputCommand.Enqueue> queued = mailboxInputs(database, tasks, "thr_task_mail");
            AgentStoreProjectionFixture followExecution = admitFollowUp(database, tasks, follow,
                    "turn_task_follow", "item_task_follow", queued);
            TaskModels.FollowUpAdmissionReceipt admitted = tasks.findFollowUpByIdempotency(follow).orElseThrow();
            TaskModels.FollowUpAdmissionReceipt replayedBeforeRevision =
                    tasks.findFollowUpByIdempotency(followRetry).orElseThrow();
            TaskRepositoryException contentCollision = assertThrows(TaskRepositoryException.class,
                    () -> tasks.findFollowUpByIdempotency(new TaskModels.MailboxEnvelope(
                            "msg_follow_collision", ROOT, "thr_task_mail", null,
                            TaskModels.MailboxKind.FOLLOW_UP, content("不同内容"), "follow-key",
                            START.plusSeconds(21))));
            assertEquals(2, turnCount(database.sessions(), "thr_task_mail"));
            assertEquals("msg_follow", admitted.mailbox().messageId());
            assertEquals("turn_task_follow", admitted.admission().turnId());
            assertEquals(admitted.mailbox().messageId(), replayedBeforeRevision.mailbox().messageId());
            assertEquals(admitted.admission().turnId(), replayedBeforeRevision.admission().turnId());
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, contentCollision.code());
            assertEquals(TaskModels.MailboxState.CONSUMED, admitted.mailbox().state());
            assertEquals("turn_task_follow", admitted.mailbox().boundTurnId());
            assertEquals("BOUND", mailboxState(database, "msg_queue"));
            consumeQueuedMailboxMessage(database, followExecution, queued.getFirst(), 2,
                    START.plusSeconds(23));
            assertEquals("CONSUMED", mailboxState(database, "msg_queue"));
            assertEquals(4, messageCount(database, "thr_task_mail"));
            TaskModels.Detail detail = tasks.readTask("thr_task_mail", 0, 0, 20).orElseThrow();
            assertTrue(detail.mailbox().stream().anyMatch(value -> value.messageId().equals("msg_follow")
                    && value.state() == TaskModels.MailboxState.CONSUMED
                    && "turn_task_follow".equals(value.boundTurnId())));
            assertTrue(detail.mailbox().stream().anyMatch(value -> value.messageId().equals("msg_queue")
                    && value.state() == TaskModels.MailboxState.CONSUMED
                    && "turn_task_follow".equals(value.boundTurnId())));
        }
    }

    /** 跨 Workspace 普通消息只接受文本，并在发送方硬删除后保留目标 Mailbox 与冻结来源。 */
    @Test
    void routesCrossWorkspaceTextOnlyAndPreservesMailboxAfterSenderDeletion() throws Exception {
        try (TestDatabase database = database("task-mailbox-cross-workspace");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            setupExternalRoot(database);
            MybatisTaskRepository tasks = repository(database);
            String targetThreadId = "thr_task_cross_target";
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null,
                    threadRevision(database.sessions(), ROOT));
            idleChild(tasks, targetThreadId, frozen);
            TaskModels.Summary before = tasks.findTask(targetThreadId).orElseThrow();
            TaskModels.Detail beforeDetail = tasks.readTask(targetThreadId, 0, 0, 20).orElseThrow();

            TaskModels.MessageEnqueueReceipt receipt = tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                    "msg_cross_text", "thr_task_external_root", targetThreadId, null,
                    TaskModels.MailboxKind.MESSAGE, content("跨 Workspace 的纯文本"), "cross-text",
                    START.plusSeconds(10)));
            assertTrue(receipt.inserted());
            assertEquals(ROOT, receipt.mailbox().rootThreadId());
            assertEquals("External", receipt.mailbox().senderTitle());

            TaskModels.Summary after = tasks.findTask(targetThreadId).orElseThrow();
            assertEquals(before.projection().revision(), after.projection().revision());
            assertEquals(before.projection().unreadCount(), after.projection().unreadCount());
            TaskModels.Detail afterDetail = tasks.readTask(targetThreadId, 0, 0, 20).orElseThrow();
            assertEquals(beforeDetail.activities().size(), afterDetail.activities().size());
            assertEquals(1, afterDetail.mailbox().size());

            UserContent skillContent = new UserContent(List.of(
                    new SkillReferenceContent("user:cross-workspace"), new TextContent("带 Skill")));
            UserContent workspaceContent = new UserContent(List.of(
                    new ResourceReferenceContent("ws_task_external", "notes.md", "file"),
                    new TextContent("带路径引用")));
            UserContent attachmentContent = new UserContent(List.of(
                    new AttachmentContent("att_cross_workspace"), new TextContent("带附件")));
            assertCrossWorkspaceContentRejected(tasks, targetThreadId,
                    skillContent, "cross-skill");
            assertCrossWorkspaceContentRejected(tasks, targetThreadId,
                    workspaceContent, "cross-workspace-reference");
            assertCrossWorkspaceContentRejected(tasks, targetThreadId,
                    attachmentContent, "cross-attachment");

            try (SqlSession session = database.sessions().openSession();
                 java.sql.Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("DELETE FROM ja_conversation_subagent_policy WHERE thread_id='thr_task_external_root'");
                statement.executeUpdate("DELETE FROM ja_conversation_thread WHERE thread_id='thr_task_external_root'");
                session.commit();
            }

            TaskModels.Detail surviving = tasks.readTask(targetThreadId, 0, 0, 20).orElseThrow();
            assertEquals(1, surviving.mailbox().size());
            assertEquals("thr_task_external_root", surviving.mailbox().getFirst().senderThreadId());
            assertEquals("External", surviving.mailbox().getFirst().senderTitle());
            assertEquals(ROOT, surviving.mailbox().getFirst().rootThreadId());

            List<AgentInputCommand.Enqueue> inputs = mailboxInputs(database, tasks, targetThreadId);
            AgentStoreProjectionFixture receiver = admitUser(database, tasks,
                    turn(targetThreadId, "turn_cross_receive", "item_cross_receive", "处理外部消息", 0,
                            START.plusSeconds(13)), inputs);
            assertEquals("BOUND", mailboxState(database, "msg_cross_text"));
            consumeQueuedMailboxMessage(database, receiver, inputs.getFirst(), 2, START.plusSeconds(14));
            TimelineMessageRow displayed = timelineMessage(database, targetThreadId, "turn_cross_receive");
            assertEquals("thr_task_external_root", displayed.sourceThreadId());
            assertEquals("External", displayed.sourceTitle());
            assertEquals("跨 Workspace 的纯文本", displayed.previewText());
        }
    }

    /**
     * 跨 Workspace 非文本拒绝保留 TaskRepository gateway 的关系错误分类，不降格为存储事务故障。
     * @author kongweiguang
     */
    private static void assertCrossWorkspaceContentRejected(MybatisTaskRepository tasks, String targetThreadId,
                                                            UserContent content, String operationKey) {
        TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                () -> tasks.enqueueMessage(new TaskModels.MailboxEnvelope(
                        "msg_" + operationKey.replace('-', '_'), "thr_task_external_root", targetThreadId,
                        null, TaskModels.MailboxKind.MESSAGE, content, operationKey,
                        START.plusSeconds(11))));
        assertEquals(TaskRepositoryException.Code.RELATION_INVALID, failure.code());
    }

    /** 重启恢复只从父取消事实派生 ATTACHED 欠账，INDEPENDENT 侧边任务不得阻止欠账收敛。 */
    @Test
    void derivesPendingCancellationOnlyForAttachedChildren() throws Exception {
        try (TestDatabase database = database("task-cancellation-recovery");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            String parentTurnId = "turn_task_parent_cancelled";
            AgentStoreProjectionFixture parent = admitUser(database, tasks,
                    turn(ROOT, parentTurnId, "item_task_parent_cancelled", "父任务", 0, START));
            long parentRevision = threadRevision(database.sessions(), ROOT);
            ChildFixture attached = child("thr_task_attached_cancel", "turn_task_attached_cancel",
                    "item_task_attached_cancel", "seed_attached_cancel", "activity_attached_cancel",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission(),
                    parentRevision, parentTurnId);
            ChildFixture independent = child("thr_task_side_survivor", "turn_task_side_survivor",
                    "item_task_side_survivor", "seed_side_survivor", "activity_side_survivor",
                    TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT,
                    TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    tasks.freezeEffectiveContext(ROOT, parentTurnId, parentRevision).initialContextSeed(),
                    new JsonArray(List.of()),
                    permission(), parentRevision, parentTurnId);
            admitChild(database, tasks, attached);
            admitChild(database, tasks, independent);
            requestCancellation(database, parent, "USER_REQUESTED", START.plusSeconds(1));

            assertEquals(List.of(new TaskModels.CancellationPropagation(ROOT, parentTurnId)),
                    tasks.pendingCancellationPropagations());

            AgentStoreProjectionFixture attachedExecution = openExecutions.get(attached.turn().executionKey());
            cancelForStore(attachedExecution, START.plusSeconds(2));
            closeExecution(attachedExecution);
            assertTrue(tasks.pendingCancellationPropagations().isEmpty());
            assertEquals(TaskModels.State.QUEUED,
                    tasks.findTask("thr_task_side_survivor").orElseThrow().projection().state());
        }
    }

    /** 恢复终态扩展不伪造 FINAL_ANSWER，但会原子收敛 Child projection、Activity 与祖先统计。 */
    @Test
    void reconcilesRecoveryTerminalWithoutSyntheticFinalAnswer() throws Exception {
        try (TestDatabase database = database("task-recovery-terminal");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            idleChild(tasks, "thr_task_recovery", tasks.freezeEffectiveContext(ROOT, null,
                    threadRevision(database.sessions(), ROOT)));
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_recovery_pending", ROOT,
                            "thr_task_recovery", null, TaskModels.MailboxKind.MESSAGE,
                            content("恢复后继续"), "recovery-pending", START.plusSeconds(1)));
            List<AgentInputCommand.Enqueue> queued = mailboxInputs(database, tasks, "thr_task_recovery");
            AgentStoreProjectionFixture execution = admitUser(database, tasks,
                    turn("thr_task_recovery", "turn_task_recovery", "item_task_recovery",
                            "启动恢复任务", 0, START.plusSeconds(2)), queued);
            assertEquals("BOUND", mailboxState(database, "msg_recovery_pending"));
            failForStore(execution, "MODEL_UNAVAILABLE", "模型调用已明确失败", START.plusSeconds(8));

            TaskModels.Detail detail = tasks.readTask("thr_task_recovery", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.FAILED, detail.task().projection().state());
            assertEquals(TaskModels.ActivityKind.FAILED, detail.activities().getLast().kind());
            assertEquals(TaskModels.MailboxState.PENDING, detail.mailbox().getFirst().state());
            assertEquals(1, messageCount(database, "thr_task_recovery"),
                    "failure without a final message must keep only the real USER input");
        }
    }

    /** Workspace 写租约严格按 claim sequence 获取，并用 fencing token 拒绝旧 owner。 */
    @Test
    void serializesWorkspaceWriteClaimsWithFencingTokens() throws Exception {
        try (TestDatabase database = database("task-claims");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_claim", "turn_task_claim", "item_task_claim",
                    "seed_claim", "activity_claim", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);

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
        try (TestDatabase database = database("task-process-generation");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_generation", "turn_task_generation", "item_task_generation",
                    "seed_generation", "activity_generation", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);

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

    /** Store Terminal 同事务提交 Task 投影、活动和父 Mailbox；重放相同终态不能重复写入。 */
    @Test
    void extendsConversationTerminalTransactionExactlyOnce() throws Exception {
        try (TestDatabase database = database("task-terminal");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_terminal", "turn_task_terminal", "item_task_terminal",
                    "seed_terminal", "activity_terminal", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
            AgentStoreProjectionFixture execution = openExecutions.get(fixture.turn().executionKey());
            completeForStore(execution, "最终结果", "完成", START.plusSeconds(20));

            TaskModels.Detail completed = tasks.readTask("thr_task_terminal", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.COMPLETED, completed.task().projection().state());
            assertEquals(2, completed.activities().size());
            assertEquals(1, completed.mailbox().size());
            assertEquals(TaskModels.MailboxKind.FINAL_ANSWER, completed.mailbox().getFirst().kind());
            assertEquals("最终结果", completed.mailbox().getFirst().content().text());
            AgentStore.ExecutionSnapshot committedExecution = database.agentStore()
                    .loadExecution(execution.execution()).orElseThrow();
            TurnExecutionState committedCursor = new TurnExecutionStateCodec(database.mapper()).read(
                    committedExecution.cursorSchemaVersion(), committedExecution.cursor());
            String modelRequestId = "modelreq_terminal_" + execution.execution().turnId();
            AgentStore.RecordWrite assistant = execution.record("agent.assistant", "side-chat-final",
                    new AssistantMessage("message_final_" + execution.execution().turnId(),
                            new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("最终结果"))),
                            "最终结果", null, 1), START.plusSeconds(20));
            AgentStore.RecordWrite usage = execution.record("agent.usage", "terminal-usage",
                    new Usage(modelRequestId, new ModelUsage(1, 1, 2), 1, 1,
                            UsagePurpose.ASSISTANT, UsageCertainty.KNOWN, modelRequestIdentity(), null),
                    START.plusSeconds(20));
            AgentStore.StoreConflictException duplicate = assertThrows(AgentStore.StoreConflictException.class,
                    () -> execution.commitRecords(TurnState.COMPLETED, committedCursor,
                            List.of(assistant, usage, execution.knownEmptyChangeSet(START.plusSeconds(20))),
                            List.of(new ExecutionEvent.Terminal(TurnState.COMPLETED, "完成", null, null,
                                    assistant.recordId(), usage.recordId())), START.plusSeconds(20)));
            assertNotNull(duplicate.getMessage());
            closeExecution(execution);
            TaskModels.Detail afterDuplicate = tasks.readTask("thr_task_terminal", 0, 0, 20).orElseThrow();
            assertEquals(2, afterDuplicate.activities().size());
            assertEquals(1, afterDuplicate.mailbox().size());
            assertEquals(1, tasks.deleteTree("thr_task_terminal",
                    afterDuplicate.task().projection().revision(), START.plusSeconds(21)));
            assertTrue(tasks.findTask("thr_task_terminal").isEmpty());
        }
    }

    /** 子任务续写的父 Mailbox 应收到前后段摘要；普通短状态词不能替代最终正文。 */
    @Test
    void childTerminalUsesContinuedAnswerPreviewForParent() throws Exception {
        try (TestDatabase database = database("task-continued-answer");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_continued", "turn_task_continued",
                    "item_task_continued", "seed_continued", "activity_continued",
                    TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
            AgentStoreProjectionFixture execution = openExecutions.get(fixture.turn().executionKey());
            completeForStore(execution, "second part", "first part second part", START.plusSeconds(20));
            TaskModels.Detail completed = tasks.readTask("thr_task_continued", 0, 0, 20).orElseThrow();
            assertEquals("first part second part", completed.mailbox().getFirst().content().text());
            closeExecution(execution);
        }
    }

    /** Plan 内部续跑只重新排队一次既有 Side Task，随后由同一终态投影结算。 */
    @Test
    void reopensCompletedSideTaskForPlanContinuation() throws Exception {
        try (TestDatabase database = database("task-hidden-continuation");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);

            String suffix = "plan";
            String taskThreadId = "thr_task_hidden_" + suffix;
            String firstTurnId = "turn_task_hidden_" + suffix;
            ChildFixture fixture = child(taskThreadId, firstTurnId, "item_hidden_" + suffix,
                    "seed_hidden_" + suffix, "activity_hidden_" + suffix, TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());
            admitChild(database, tasks, fixture);
            commitTerminal(database, terminal(taskThreadId, firstTurnId,
                    "item_hidden_final_" + suffix, 5));

            TaskModels.Summary completed = tasks.findTask(taskThreadId).orElseThrow();
            assertEquals(TaskModels.State.COMPLETED, completed.projection().state());
            String continuationTurnId = "turn_hidden_continuation_" + suffix;
            admitContinuation(database, tasks, taskThreadId, continuationTurnId,
                    START.plusSeconds(10), Optional.empty());

            TaskModels.Detail running = tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.QUEUED, running.task().projection().state());
            assertEquals(null, running.task().projection().completedAt());
            assertEquals(3, running.activities().size());
            assertEquals(TaskModels.ActivityKind.RESUMED, running.activities().getLast().kind());

            commitTerminal(database, terminal(taskThreadId, continuationTurnId,
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

    /** Task 扩展失败会回滚已经执行的 Conversation Turn CAS 与最终消息，不暴露半终态。 */
    @Test
    void rollsBackConversationTerminalWhenTaskExtensionFails() throws Exception {
        try (TestDatabase database = database("task-terminal-rollback");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_rollback", "turn_task_rollback", "item_task_rollback",
                    "seed_rollback", "activity_rollback", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
            tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_conflicting_terminal",
                            "thr_task_rollback", ROOT, "turn_task_rollback", TaskModels.MailboxKind.MESSAGE,
                            content("冲突事实"), TaskTerminalPersistence.terminalOperationKey(
                            "turn_task_rollback"), START.plusSeconds(10)));
            AgentStoreProjectionFixture execution = openExecutions.get(fixture.turn().executionKey());

            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> completeForStore(execution, "最终结果", "完成", START.plusSeconds(20)));

            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, failure.code());
            assertEquals(TurnState.RUNNING, TurnState.valueOf(database.agentStore().loadExecution(
                    execution.execution()).orElseThrow().state()));
            assertEquals(1, messageCount(database, "thr_task_rollback"));
            TaskModels.Detail unchanged = tasks.readTask("thr_task_rollback", 0, 0, 20).orElseThrow();
            assertEquals(TaskModels.State.QUEUED, unchanged.task().projection().state());
            assertEquals(1, unchanged.activities().size());
            assertEquals(1, unchanged.mailbox().size());
            closeExecution(execution);
        }
    }

    /**
     * 已读边界按服务端 sequence 单调推进，未来 sequence 与旧 revision 均拒绝；冲突用 gateway code 表达。
     * @author kongweiguang
     */
    @Test
    void advancesSeenBoundaryWithProjectionCas() throws Exception {
        try (TestDatabase database = database("task-seen");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture fixture = child("thr_task_seen", "turn_task_seen", "item_task_seen",
                    "seed_seen", "activity_seen", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, fixture);
            TaskModels.Summary initial = tasks.findTask("thr_task_seen").orElseThrow();

            TaskModels.Summary seen = tasks.markSeen("thr_task_seen", initial.projection().revision(),
                    initial.projection().latestActivitySequence(), START.plusSeconds(5));

            assertEquals(0, seen.projection().unreadCount());
            assertEquals(initial.projection().latestActivitySequence(),
                    seen.projection().lastSeenActivitySequence());
            TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                    () -> tasks.markSeen("thr_task_seen",
                    initial.projection().revision(), initial.projection().latestActivitySequence() + 1,
                    START.plusSeconds(6)));
            assertEquals(TaskRepositoryException.Code.CAS_CONFLICT, failure.code());
        }
    }

    /** 复用共享 SQLite Task repository，避免测试私造与 Ja Store 不同的事务 owner。 */
    private static MybatisTaskRepository repository(TestDatabase database) {
        return database.taskRepository();
    }

    /** 根 Workspace/Thread 通过 History owner 创建，确保对应的 AgentStore conversation 也已存在。 */
    private static void setupRoot(TestDatabase database) {
        setupRoot(database, SubagentPolicy.defaultPolicy());
    }

    /** 根 Thread 的策略是 Child 继承测试的唯一来源，不从测试数组或模型偏好推断。 */
    private static void setupRoot(TestDatabase database, SubagentPolicy policy) {
        database.history().register(new Workspace.Registration(WORKSPACE, Path.of("C:/dev/task-test"), "Task test", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(ROOT, WORKSPACE, "Root",
                ConversationTestFixtures.preferences(), START));
        if (!policy.equals(SubagentPolicy.defaultPolicy())) {
            try (SqlSession session = database.sessions().openSession();
                 PreparedStatement statement = session.getConnection().prepareStatement(
                         """
                             UPDATE ja_conversation_subagent_policy SET is_enabled=?,provider_id=?,model_id=?,reasoning_level=? \
                             WHERE thread_id=?\
                             """)) {
                statement.setBoolean(1, policy.enabled());
                statement.setString(2, policy.providerId());
                statement.setString(3, policy.modelId());
                statement.setString(4, policy.reasoningLevel());
                statement.setString(5, ROOT);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("root Task policy is missing");
                }
                session.commit();
            } catch (SQLException failure) {
                throw new IllegalStateException("cannot configure root Task policy fixture", failure);
            }
        }
    }

    /** 构造独立 Workspace 的根 Thread，验证跨域消息不依赖任务树 lineage。 */
    private static void setupExternalRoot(TestDatabase database) {
        String workspaceId = "ws_task_external";
        String threadId = "thr_task_external_root";
        database.history().register(new Workspace.Registration(workspaceId, Path.of("C:/dev/task-test-external"), "External task test", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        database.history().createThread(new ThreadSummary.Creation(threadId, workspaceId, "External",
                ConversationTestFixtures.preferences(), START));
    }

    /** Side Task 与 Subagent 都必须复制 parent 的不可变策略，不能回读最新全局设置。 */
    @Test
    void childAdmissionsInheritParentSubagentPolicy() throws Exception {
        SubagentPolicy parentPolicy = new SubagentPolicy(false, "provider_child", "model_child", "high");
        try (TestDatabase database = database("task-policy-inheritance");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database, parentPolicy);
            MybatisTaskRepository tasks = repository(database);
            TaskModels.EffectiveContextSnapshot frozen = tasks.freezeEffectiveContext(ROOT, null, 0);
            ChildFixture side = child("thr_task_policy_side", "turn_task_policy_side", "item_policy_side",
                    "seed_policy_side", "activity_policy_side", TaskModels.Kind.SIDE_TASK,
                    TaskModels.Lifecycle.INDEPENDENT, TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                    frozen.initialContextSeed(), frozen.references(), frozen.permissionCeiling());
            admitChild(database, tasks, side);
            ChildFixture subagent = child("thr_task_policy_agent", "turn_task_policy_agent", "item_policy_agent",
                    "seed_policy_agent", "activity_policy_agent", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                    null, new JsonArray(List.of()), permission());
            admitChild(database, tasks, subagent);

            assertPolicy(database, ROOT, parentPolicy);
            assertPolicy(database, "thr_task_policy_side", parentPolicy);
            assertPolicy(database, "thr_task_policy_agent", parentPolicy);
        }
    }

    /** 全局最新 Turn 已切出旧分支且分页只含旧暂停时，迟到 expiry 不能把当前运行 Task 回拨为 SUSPENDED。 */
    @Test
    void ignoresLateSuspensionWhenNewerCurrentTurnIsRunning() throws Exception {
        String taskThreadId = "thr_task_late_expiry";
        String oldTurnId = "turn_task_old_wait";
        String currentTurnId = "turn_task_current_running";
        String latestOffPathTurnId = "turn_task_latest_off_path";
        try (TestDatabase database = database("task-late-expiry-current-turn");
             ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
            setupRoot(database);
            MybatisTaskRepository tasks = repository(database);
            ChildFixture child = child(taskThreadId, oldTurnId, "item_task_old_wait", "seed_old_wait",
                    "activity_task_old_wait", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED,
                    TaskModels.InheritanceMode.BRIEF_ONLY, null, new JsonArray(List.of()), permission());
            AgentStoreProjectionFixture oldWait = admitChild(database, tasks, child);
            commitState(database, oldWait, TurnState.SUSPENDED, START.plusSeconds(3));
            closeExecution(oldWait);

            AgentStoreProjectionFixture currentRunning = admitUser(database, tasks,
                    turn(taskThreadId, currentTurnId, "item_task_current_running", "继续任务",
                            threadRevision(database.sessions(), taskThreadId),
                            START.plusSeconds(1)));
            commitState(database, currentRunning, TurnState.RUNNING, START.plusSeconds(4));
            closeExecution(currentRunning);

            AgentStoreProjectionFixture latestOffPath = admitUser(database, tasks,
                    turn(taskThreadId, latestOffPathTurnId, "item_task_latest_off_path", "另一个分支",
                            threadRevision(database.sessions(), taskThreadId),
                            START.plusSeconds(2)));
            closeExecution(latestOffPath);
            TaskModels.Summary projection = tasks.findTask(taskThreadId).orElseThrow();
            tasks.recordActivity(new TaskModels.ActivityMutation(taskThreadId,
                    projection.projection().revision(), TaskModels.State.RUNNING,
                    "activity_guard_manual_resume_record_01", taskThreadId, currentTurnId,
                    TaskModels.ActivityKind.RESUMED, summary("当前任务正在运行"), "当前任务正在运行",
                    START.plusSeconds(5)));
            int activityCountBeforeExpiry = tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow().activities().size();

            try (SqlSession session = database.sessions().openSession()) {
                try (PreparedStatement statement = session.getConnection().prepareStatement(
                        "UPDATE ja_conversation_turn SET is_current_path=0 WHERE thread_id=? AND turn_id=?")) {
                    statement.setString(1, taskThreadId);
                    statement.setString(2, latestOffPathTurnId);
                    assertEquals(1, statement.executeUpdate());
                }
                session.commit();
            }
            try (SqlSession session = database.sessions().openSession()) {
                PersistenceMappers mapper = PersistenceMappers.open(session);
                List<PersistenceRecords.TurnRow> bounded = mapper.agent()
                        .selectSnapshotTurns(taskThreadId, List.of(oldTurnId), true);
                List<String> boundedCurrent = bounded.stream().filter(PersistenceRecords.TurnRow::currentPath)
                        .map(PersistenceRecords.TurnRow::turnId).toList();
                assertEquals(List.of(oldTurnId), boundedCurrent,
                        "the regression fixture must reproduce the bounded-snapshot blind spot");
                assertEquals(currentTurnId, mapper.agent().selectLatestCurrentTurnId(taskThreadId));
                session.commit();
            }
            assertTrue(tasks.reconcileRecoveredSuspension(oldTurnId, START.plusSeconds(6)).isEmpty());

            TaskModels.Summary unchanged = tasks.findTask(taskThreadId).orElseThrow();
            assertEquals(TaskModels.State.RUNNING, unchanged.projection().state());
            assertEquals(activityCountBeforeExpiry,
                    tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow().activities().size(),
                    "stale expiry must not append a suspension activity");
            assertEquals(TurnState.SUSPENDED.name(), database.agentStore().loadExecution(oldWait.execution())
                    .orElseThrow().state());
            assertEquals(TurnState.RUNNING.name(), database.agentStore().loadExecution(currentRunning.execution())
                    .orElseThrow().state());
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

    /** 准备后的策略变化或缺失不能留下混合 Child、Turn、正文或 Core 执行事实。 */
    @Test
    void rollsBackChildAdmissionWhenFrozenParentPolicyChangesOrDisappears() throws Exception {
        for (String mode : List.of("changed", "missing")) {
            try (TestDatabase database = database("child-policy-" + mode);
                 ExecutionLeaseCleanup cleanup = new ExecutionLeaseCleanup()) {
                setupRoot(database);
                MybatisTaskRepository tasks = repository(database);
                String parentTurnId = "turn_parent_policy";
                ensureParentTurn(database, tasks, database.jaAgentStore(), ROOT, parentTurnId, true);
                SubagentPolicy frozenPolicy = policy(database, ROOT);
                String childId = "thr_policy_" + mode;
                ChildFixture child = child(childId, "turn_policy_" + mode, "item_policy_" + mode,
                        "seed_policy_" + mode, "activity_policy_" + mode, TaskModels.Kind.SUBAGENT,
                        TaskModels.Lifecycle.ATTACHED, TaskModels.InheritanceMode.BRIEF_ONLY,
                        null, new JsonArray(List.of()), permission(),
                        threadRevision(database.sessions(), ROOT), parentTurnId);
                String sql = mode.equals("missing")
                        ? "DELETE FROM ja_conversation_subagent_policy WHERE thread_id=?"
                        : "UPDATE ja_conversation_subagent_policy SET is_enabled=0 WHERE thread_id=?";
                try (SqlSession session = database.sessions().openSession();
                     PreparedStatement statement = session.getConnection().prepareStatement(sql)) {
                    statement.setString(1, ROOT);
                    assertEquals(1, statement.executeUpdate());
                    session.commit();
                }
                TaskRepositoryException failure = assertThrows(TaskRepositoryException.class,
                        () -> admitFrozenChild(database, tasks, child, frozenPolicy));
                assertEquals(TaskRepositoryException.Code.INVALID_STATE, failure.code());
                assertEquals(0, threadCount(database.sessions(), childId));
                assertEquals(0, turnCount(database.sessions(), childId));
                assertEquals(0, messageCount(database, childId));
                assertTrue(tasks.findTask(childId).isEmpty());
                assertTrue(database.agentStore().loadConversation(
                        new AgentStore.ConversationKey("ja.local", childId, "user_local")).isEmpty());
                try (SqlSession session = database.sessions().openSession()) {
                    assertNull(PersistenceMappers.open(session).subagentPolicies().select(childId));
                }
            }
        }
    }

    /**
     * 首轮 admission fixture 使用同一父行策略，避免测试替真实冻结值补入默认配置。
     */
    private static SubagentPolicy policy(TestDatabase database, String threadId) {
        try (SqlSession session = database.sessions().openSession()) {
            PersistenceRecords.SubagentPolicyRow row = PersistenceMappers.open(session)
                    .subagentPolicies().select(threadId);
            assertNotNull(row);
            return new SubagentPolicy(row.enabled(), row.providerId(), row.modelId(), row.reasoningLevel());
        }
    }

    /** Child 测试固定使用真实 parent Turn 和 Store revision，避免失效的旧 fixture 隐式假设 revision 0。 */
    private AgentStoreProjectionFixture admitChild(TestDatabase database, MybatisTaskRepository tasks,
                                                    ChildFixture fixture) {
        return admitChild(database, tasks, fixture.child(), fixture.turn());
    }

    /** 从 Task owner 的冻结草稿创建同事务 Child admission，并保留可供后续 Core commit 的 execution lease。 */
    private AgentStoreProjectionFixture admitChild(TestDatabase database, MybatisTaskRepository tasks,
            TaskModels.ChildAdmission draft, TaskTurnDraft turn) {
        AgentStore store = database.jaAgentStore();
        String parentThreadId = draft.parentThreadId();
        String suffix = draft.childThread().threadId().substring(
                draft.childThread().threadId().lastIndexOf('_') + 1);
        String parentTurnId = draft.originTurnId() == null ? "turn_parent_" + suffix : draft.originTurnId();
        ensureParentTurn(database, tasks, store, parentThreadId, parentTurnId,
                draft.lifecycle() == TaskModels.Lifecycle.ATTACHED);
        long parentRevision = threadRevision(database.sessions(), parentThreadId);
        TaskModels.ContextSeedDraft seed = draft.contextSeed();
        TaskModels.EffectiveContextSnapshot frozen = seed.inheritanceMode()
                == TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
                ? tasks.freezeEffectiveContext(parentThreadId, parentTurnId, parentRevision) : null;
        TaskModels.ContextSeedDraft currentSeed = new TaskModels.ContextSeedDraft(seed.contextSeedId(),
                parentThreadId, parentTurnId, parentRevision, seed.inheritanceMode(), turn.content(),
                frozen == null ? seed.initialContextSeed() : frozen.initialContextSeed(),
                frozen == null ? seed.references() : frozen.references(),
                frozen == null ? seed.permissionCeiling() : frozen.permissionCeiling(), seed.createdAt());
        TaskModels.ChildAdmission child = new TaskModels.ChildAdmission(draft.childThread(), parentThreadId,
                parentRevision, parentTurnId, draft.taskName(), draft.kind(), draft.lifecycle(), currentSeed,
                draft.activityId(), draft.activitySummary());
        String rootTurnId = tasks.rootTurnIdForAdmission(parentThreadId, parentTurnId,
                draft.lifecycle() == TaskModels.Lifecycle.ATTACHED);
        Instant at = turn.requestedAt();
        ThreadPreferences preferences = ConversationAcl.toConversation(draft.childThread().preferences());
        AgentIdentity identity = new AgentIdentity("ja.local", turn.threadId(), turn.turnId(), "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.CHILD_TASK, "task/create", at,
                null, parentTurnId, rootTurnId, null, null, null, null, 0,
                Optional.of(new AdmissionFacts.ThreadInitialization(WORKSPACE, draft.childThread().title(),
                        preferences, draft.childThread().createdAt())));
        TaskAdmissionTransactionParticipant business = TaskAdmissionTransactionParticipant.forChild(tasks,
                facts, child, policy(database, parentThreadId), new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        AgentExecutionRequest request = request(identity, turn, "task/create",
                Optional.ofNullable(currentSeed.initialContextSeed()), List.of());
        AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(store, database.mapper(), request,
                facts, preferences, 0, business);
        openExecutions.put(execution.execution(), execution);
        return execution;
    }

    /** 使用显式冻结策略与父 revision 提交 Child，确认准入失败时 Core 与 Ja 同事务回滚。 */
    private AgentStoreProjectionFixture admitFrozenChild(TestDatabase database, MybatisTaskRepository tasks,
            ChildFixture fixture, SubagentPolicy frozenPolicy) {
        TaskModels.ChildAdmission child = fixture.child();
        TaskTurnDraft turn = fixture.turn();
        String parentTurnId = child.originTurnId();
        AgentIdentity identity = new AgentIdentity("ja.local", turn.threadId(), turn.turnId(), "user_local");
        Instant at = turn.requestedAt();
        ThreadPreferences preferences = ConversationAcl.toConversation(child.childThread().preferences());
        String rootTurnId = tasks.rootTurnIdForAdmission(child.parentThreadId(), parentTurnId, true);
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.CHILD_TASK, "task/create", at,
                null, parentTurnId, rootTurnId, null, null, null, null, 0,
                Optional.of(new AdmissionFacts.ThreadInitialization(WORKSPACE, child.childThread().title(),
                        preferences, child.childThread().createdAt())));
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forChild(tasks,
                facts, child, frozenPolicy, new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        AgentExecutionRequest request = request(identity, turn, "task/create",
                Optional.ofNullable(child.contextSeed().initialContextSeed()), List.of());
        return AgentStoreProjectionFixture.open(database.jaAgentStore(), database.mapper(), request, facts,
                preferences, 0, participant);
    }

    /** 普通 Turn 使用 Thread 当前 revision；Task Thread 再以同一 admission transaction 更新 Task 投影。 */
    private AgentStoreProjectionFixture admitUser(TestDatabase database, MybatisTaskRepository tasks,
                                                   TaskTurnDraft turn) {
        return admitUser(database, tasks, turn, List.of());
    }

    /** 输入队列只通过初始 admission facts 绑定 Mailbox，不提前写 Ja 消息或独立 claim 状态。 */
    private AgentStoreProjectionFixture admitUser(TestDatabase database, MybatisTaskRepository tasks,
            TaskTurnDraft turn, List<AgentInputCommand.Enqueue> initialQueuedInputs) {
        AgentStore store = database.jaAgentStore();
        long revision = threadRevision(database.sessions(), turn.threadId());
        AgentIdentity identity = new AgentIdentity("ja.local", turn.threadId(), turn.turnId(), "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, WORKSPACE, TurnOrigin.USER, "turn/start",
                turn.requestedAt(), null, null, null, null, null, null, null, revision, Optional.empty());
        Optional<InitialContextSeed> seed = turnCount(database.sessions(), turn.threadId()) == 0
                ? tasks.findInitialContextSeedForFirstTurn(turn.threadId()) : Optional.empty();
        TaskAdmissionTransactionParticipant participant = tasks.findTask(turn.threadId())
                .map(task -> TaskAdmissionTransactionParticipant.forUserTurn(
                        tasks, facts, new TaskAdmissionTransactionParticipant.UserTurnDraft(
                                task.projection().revision(), seed), new StorePayloadCodec(database.mapper()),
                        new TurnExecutionStateCodec(database.mapper())))
                .orElse(null);
        AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(store, database.mapper(),
                request(identity, turn, "turn/start", seed, initialQueuedInputs), facts,
                threadPreferences(tasks, turn.threadId()), revision,
                participant);
        openExecutions.put(execution.execution(), execution);
        return execution;
    }

    /** FOLLOW_UP 由 Core USER admission 与 Task 邮箱消费同事务落库，不复用旧的单独 admit API。 */
    private AgentStoreProjectionFixture admitFollowUp(TestDatabase database, MybatisTaskRepository tasks,
            TaskModels.MailboxEnvelope followUp, String turnId, String itemId,
            List<AgentInputCommand.Enqueue> initialQueuedInputs) {
        TaskModels.Detail task = tasks.readTask(followUp.targetThreadId(), 0, 0, 20).orElseThrow();
        long threadRevision = threadRevision(database.sessions(), followUp.targetThreadId());
        Instant acceptedAt = followUp.createdAt();
        AgentIdentity identity = new AgentIdentity("ja.local", followUp.targetThreadId(), turnId, "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, task.thread().workspaceId(), TurnOrigin.CHILD_TASK,
                "task/followup", acceptedAt, null, followUp.causalTurnId(), null,
                null, null, null, null, threadRevision, Optional.empty());
        Optional<InitialContextSeed> seed = turnCount(database.sessions(), followUp.targetThreadId()) == 0
                ? tasks.findInitialContextSeedForFirstTurn(followUp.targetThreadId()) : Optional.empty();
        TaskAdmissionTransactionParticipant.FollowUpDraft draft =
                new TaskAdmissionTransactionParticipant.FollowUpDraft(followUp,
                        task.task().projection().revision(), "activity_follow_" + turnId.substring("turn_".length()),
                        summary("已追加后续任务"), seed);
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forFollowUp(
                tasks, facts, draft, new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        TaskTurnDraft turn = turn(followUp.targetThreadId(), turnId, itemId, followUp.content().text(),
                threadRevision, acceptedAt);
        AgentExecutionRequest request = request(identity, turn, "task/followup", seed, initialQueuedInputs);
        AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(database.jaAgentStore(),
                database.mapper(), request, facts, ConversationAcl.toConversation(task.thread().preferences()), threadRevision, participant);
        openExecutions.put(execution.execution(), execution);
        return execution;
    }

    /** Plan 首轮显式携带 frozen seed；既有 Task 续跑不重复导入上下文或伪造 USER 正文。 */
    private AgentStoreProjectionFixture admitContinuation(TestDatabase database, MybatisTaskRepository tasks,
            String taskThreadId, String turnId, Instant acceptedAt,
            Optional<InitialContextSeed> seed) {
        TaskModels.Detail task = tasks.readTask(taskThreadId, 0, 0, 20).orElseThrow();
        long threadRevision = threadRevision(database.sessions(), taskThreadId);
        String suffix = turnId.substring("turn_".length());
        String runId = "run_test_" + suffix;
        String planRevisionId = "plan_revision_test_" + suffix;
        AgentIdentity identity = new AgentIdentity("ja.local", taskThreadId, turnId, "user_local");
        AdmissionFacts facts = new AdmissionFacts(identity, task.thread().workspaceId(), TurnOrigin.PLAN_EXECUTION,
                "plan/execute", acceptedAt, null, null, null, planRevisionId, runId, null, null,
                threadRevision, Optional.empty());
        TaskAdmissionTransactionParticipant.ContinuationDraft draft =
                new TaskAdmissionTransactionParticipant.ContinuationDraft(task.task().projection().revision(),
                        "activity_task_resumed_" + suffix,
                        summary("计划执行已排队"), seed);
        TaskAdmissionTransactionParticipant participant = TaskAdmissionTransactionParticipant.forContinuation(
                tasks, facts, draft, new StorePayloadCodec(database.mapper()),
                new TurnExecutionStateCodec(database.mapper()));
        ContextInstruction instruction = new ContextInstruction("instruction_" + suffix,
                "执行已批准计划");
        AgentExecutionRequest request = new AgentExecutionRequest(identity,
                AgentExecutionRequest.AdmissionKind.INTERNAL, Optional.empty(), null, Optional.of(instruction),
                new AgentStore.OperationIdentity("operation_" + turnId, "agent.internal", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), acceptedAt, List.of(),
                Optional.empty(), seed);
        AgentStoreProjectionFixture execution = AgentStoreProjectionFixture.open(database.jaAgentStore(),
                database.mapper(), request, facts, ConversationAcl.toConversation(task.thread().preferences()), threadRevision, participant);
        openExecutions.put(execution.execution(), execution);
        return execution;
    }

    /** 父 Turn 以正式 Store admission 建立；ATTACHED Child 需要它保持活动以供因果取消校验。 */
    private void ensureParentTurn(TestDatabase database, MybatisTaskRepository tasks, AgentStore store,
                                  String threadId, String turnId, boolean keepActive) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("ja.local", threadId, "user_local"), turnId);
        if (store.loadExecution(key).isPresent()) {
            return;
        }
        Instant at = START.plusSeconds(Math.max(1, store.loadConversation(key.conversation())
                .map(AgentStore.ConversationSnapshot::revision).orElse(0L) + 1));
        TaskTurnDraft parent = new TaskTurnDraft(threadId, turnId, "item_parent_" + turnId.substring(5),
                content("冻结测试父上下文"), threadRevision(database.sessions(), threadId), at);
        AgentStoreProjectionFixture execution = admitUser(database, tasks, parent);
        if (!keepActive) {
            completeForStore(execution, "父会话保持可用", at.plusSeconds(1));
            openExecutions.remove(execution.execution());
            execution.close();
        }
    }

    /** 请求保留 Ja facts 的业务身份，Core 回执类型则固定匹配 USER 准入协议。 */
    private static AgentExecutionRequest request(AgentIdentity identity, TaskTurnDraft turn, String method) {
        return request(identity, turn, method, Optional.empty(), List.of());
    }

    /** 首轮背景和初始队列都冻结进 Core admission，测试不得在准入之后补写隐藏上下文。 */
    private static AgentExecutionRequest request(AgentIdentity identity, TaskTurnDraft turn, String method,
            Optional<InitialContextSeed> seed, List<AgentInputCommand.Enqueue> initialQueuedInputs) {
        return new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(turn.content()), turn.messageId(), Optional.empty(),
                new AgentStore.OperationIdentity("operation_" + turn.turnId(), "agent.submit", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), AgentExecutionRequest.HistorySelection.currentConversation(), turn.requestedAt(),
                initialQueuedInputs, Optional.empty(), seed);
    }

    /** Task Thread 的 Preferences 必须来自持久快照，不把测试默认值当成 admission 配置。 */
    private static ThreadPreferences threadPreferences(MybatisTaskRepository tasks, String threadId) {
        return tasks.findTask(threadId).flatMap(ignored -> tasks.readTask(threadId, 0, 0, 1))
                .map(detail -> ConversationAcl.toConversation(detail.thread().preferences())).orElseGet(ConversationTestFixtures::preferences);
    }

    /** 只用于证明冻结 seed 以 conversation 级事实存在一次，后续 USER 不得重复导入背景。 */
    private static int countInitialContextSeeds(SqlSessionFactory sessions, String threadId) throws SQLException {
        try (Connection connection = sessions.getConfiguration().getEnvironment().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT COUNT(*) FROM agent_record \
                 WHERE tenant_id='ja.local' AND thread_id=? AND record_type='agent.context.seed'\
                 """)) {
            statement.setString(1, threadId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("AgentStore seed count returned no row");
                }
                return rows.getInt(1);
            }
        }
    }

    /** 按新 Task aggregate revision 回读线程头，测试不猜测 Core/Ja 提交后推进了几次版本。 */
    private static long threadRevision(SqlSessionFactory sessions, String threadId) {
        try (SqlSession session = sessions.openSession()) {
            PersistenceRecords.ThreadRow row = PersistenceMappers.open(session).history().selectThread(threadId);
            if (row == null) {
                throw new AssertionError("thread is missing: " + threadId);
            }
            return row.revision();
        }
    }

    /** 在 AgentStore 事务中写入 typed Assistant 与 Terminal，使 Ja/Task facts 由权威事件派生。 */
    private AgentStore.CommitReceipt commitTerminal(TestDatabase database, TerminalDraft terminal) {
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey("ja.local", terminal.threadId(), "user_local"), terminal.turnId());
        AgentStoreProjectionFixture fixture = openExecutions.get(key);
        if (fixture == null) {
            throw new AssertionError("execution fixture is missing: " + key);
        }
        AgentStore.CommitReceipt receipt = completeForStore(fixture, terminal.publicText(), terminal.summary(),
                terminal.occurredAt());
        closeExecution(fixture);
        return receipt;
    }

    /** Child fixture 保存业务请求草稿，真实 parent revision 在 Store admission 前从 History owner 读取。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, InitialContextSeed initialContextSeed,
                                      JsonArray references, JsonObject permission) {
        return child(threadId, turnId, itemId, seedId, activityId, kind, lifecycle, inheritance,
                initialContextSeed, references, permission, 0);
    }

    /** 显式 parent revision 变体用于 stale admission 整体回滚测试。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, InitialContextSeed initialContextSeed,
                                      JsonArray references, JsonObject permission, long parentRevision) {
        return child(threadId, turnId, itemId, seedId, activityId, kind, lifecycle, inheritance,
                initialContextSeed, references, permission, parentRevision, null);
    }

    /** 显式 origin Turn 变体用于验证 ATTACHED 取消因果链与 INDEPENDENT 隔离。 */
    private static ChildFixture child(String threadId, String turnId, String itemId, String seedId,
                                      String activityId, TaskModels.Kind kind, TaskModels.Lifecycle lifecycle,
                                      TaskModels.InheritanceMode inheritance, InitialContextSeed initialContextSeed,
                                      JsonArray references, JsonObject permission, long parentRevision,
                                      String originTurnId) {
        ThreadPreferences preferences = ConversationTestFixtures.preferences();
        ThreadDefinition thread = new ThreadDefinition(
                threadId, WORKSPACE, threadId, ConversationAcl.fromConversation(preferences), START);
        TaskModels.ContextSeedDraft seed = new TaskModels.ContextSeedDraft(seedId, ROOT, originTurnId,
                parentRevision, inheritance, content("完成任务"), initialContextSeed, references, permission, START);
        TaskModels.ChildAdmission admission = new TaskModels.ChildAdmission(thread, ROOT, parentRevision,
                originTurnId, threadId, kind, lifecycle, seed, activityId, summary("已派发"));
        return new ChildFixture(admission, turn(threadId, turnId, itemId, "完成任务", 0, START));
    }

    /** Turn 草稿只保留业务身份与内容；执行身份、权限和 revision 由真实 admission fixture 构造。 */
    private static TaskTurnDraft turn(String threadId, String turnId, String itemId,
                                      String text, long threadRevision, Instant occurredAt) {
        return new TaskTurnDraft(threadId, turnId, itemId, content(text), threadRevision, occurredAt);
    }

    /** 多 Turn 测试只描述终态事实，记录身份与事件由 Store codec 按 execution 派生。 */
    private static TerminalDraft terminal(String threadId, String turnId, String itemId, long second) {
        return new TerminalDraft(threadId, turnId, itemId, TurnState.COMPLETED, "完成", "结果",
                START.plusSeconds(second));
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

    /** 通过 App 历史 owner 读取当前受支持的分页上限，统计可见 timeline 条目。 */
    private static int messageCount(TestDatabase database, String threadId) {
        return database.history().readThread(threadId, null, 100).map(page -> page.items().size()).orElse(0);
    }

    /** 核对权威 Mailbox 状态，避免只凭 Timeline 项目推断消息已绑定或消费。 */
    private static String mailboxState(TestDatabase database, String messageId) throws SQLException {
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT state FROM ja_task_mailbox_message WHERE message_id=?")) {
            statement.setString(1, messageId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("mailbox message is missing: " + messageId);
                }
                return rows.getString(1);
            }
        }
    }

    /** 从 Ja 自有时间线表验证有界 Mailbox 预览，不把它误作 Core 保存的完整正文。 */
    private static TimelineMessageRow timelineMessage(TestDatabase database, String threadId, String turnId)
            throws SQLException {
        String sql = """
            SELECT item_id,preview_text,source_thread_id,source_title FROM ja_conversation_timeline_entry \
            WHERE thread_id=? AND turn_id=? AND kind='THREAD_MESSAGE' ORDER BY created_at_ms DESC LIMIT 1\
            """;
        try (Connection connection = database.database().dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, threadId);
            statement.setString(2, turnId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new AssertionError("Ja mailbox timeline projection is missing");
                }
                return new TimelineMessageRow(rows.getString("item_id"), rows.getString("preview_text"),
                        rows.getString("source_thread_id"), rows.getString("source_title"));
            }
        }
    }

    /**
     * 保存用于断言的 Ja Timeline 预览字段，与 Core 的完整 mailbox 正文保持独立。
     *
     * @param itemId 条目标识
     * @param previewText 有界公开预览
     * @param sourceThreadId 来源对话标识
     * @param sourceTitle 来源标题
     */
    private record TimelineMessageRow(String itemId,
                                      String previewText,
                                      String sourceThreadId,
                                      String sourceTitle) { }

    /** Thread 是否存在使用 HistoryMapper 权威查询，不扫描数据库文件。 */
    private static int threadCount(SqlSessionFactory sessions, String threadId) {
        try (SqlSession session = sessions.openSession()) {
            return session.getMapper(io.github.kongweiguang.ja.infrastructure.persistence.mapper.HistoryMapper.class)
                    .selectThread(threadId) == null ? 0 : 1;
        }
    }

    /**
     * 把 Child 与其首个 Turn admission 绑定成不可拆分夹具，避免测试构造出跨父 revision 的伪状态。
     *
     * @param child 侧边任务
     * @param turn 执行轮次
     */
    private record ChildFixture(TaskModels.ChildAdmission child,
                                TaskTurnDraft turn) { }

    /**
     * 以当前 core 数据面接纳用户或子任务请求，不在测试中重新声明旧 ConversationRepository 协议。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param messageId 消息标识
     * @param content 内容
     * @param requestedThreadRevision 请求对话修订版本
     * @param requestedAt 请求时间
     */
    private record TaskTurnDraft(String threadId,
                                 String turnId,
                                 String messageId,
                                 UserContent content,
                                 long requestedThreadRevision,
                                 Instant requestedAt) {
        /** 执行 key 与准入事实来自同一 thread/turn 二元组，不另造身份映射。 */
        AgentStore.ExecutionKey executionKey() {
            return new AgentStore.ExecutionKey(new AgentStore.ConversationKey("ja.local", threadId, "user_local"), turnId);
        }
    }

    /**
     * 终态 fixture 只承载 Ja 测试关心的安全公开值，Core 事件及执行游标均由 helper 生成。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param itemId 条目标识
     * @param state 当前状态
     * @param summary 摘要
     * @param publicText 公开文本
     * @param occurredAt 事件发生时间
     */
    private record TerminalDraft(String threadId,
                                 String turnId,
                                 String itemId,
                                 TurnState state,
                                 String summary,
                                 String publicText,
                                 Instant occurredAt) { }
}
