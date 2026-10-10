// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskMailboxQueueInputSource;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 锁定邮箱读取、准入绑定、模型来源与消费/取消的同事务闭环。 */
final class TaskMailboxAdmissionPersistenceTest extends PersistenceTestSupport {
    private static final String TARGET = "thr_mail_target";
    private static final String SENDER = "thr_mail_sender";

    /** FIFO 查询不抢占消息，容量以 Core 批次为界；同一消息跨查询保留同一输入与幂等身份。 */
    @Test
    void readsBoundedPendingMessagesWithoutClaimingOrChangingIdentity() throws Exception {
        try (TestDatabase db = database("mailbox-pending-fifo")) {
            prepare(db);
            MybatisTaskRepository tasks = db.taskRepository();
            for (int index = 0; index < 10; index++) {
                enqueue(tasks, index);
            }
            TaskMailboxQueueInputSource source = source(db, tasks);
            List<AgentInputCommand.Enqueue> first = source.loadPendingInputs(TARGET, 20);
            assertEquals(8, first.size());
            assertEquals(first, source.loadPendingInputs(TARGET, 8));
            assertEquals(List.of("msg_mail_0", "msg_mail_1", "msg_mail_2", "msg_mail_3",
                    "msg_mail_4", "msg_mail_5", "msg_mail_6", "msg_mail_7"), first.stream()
                    .map(value -> value.hostMetadata().get("ja.mailbox.messageId")).toList());
            assertEquals(10, scalar(db, "SELECT COUNT(*) FROM ja_task_mailbox_message WHERE state='PENDING'"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM ja_conversation_turn"));
        }
    }

    /** 绑定消息不等于消费；篡改消费正文时整笔回滚，正确消费同时生成带来源的 USER 与展示原文。 */
    @Test
    void admissionAndConsumptionPreserveSourceAndRollbackTamperedBody() throws Exception {
        try (TestDatabase db = database("mailbox-consume-atomic")) {
            prepare(db);
            MybatisTaskRepository tasks = db.taskRepository();
            enqueue(tasks, 0);
            AgentInputCommand.Enqueue input = source(db, tasks).loadPendingInputs(TARGET, 1).getFirst();
            try (AgentStoreProjectionFixture run = admit(db, "turn_mail_first", List.of(input))) {
                assertEquals("BOUND", mailboxState(db, "msg_mail_0"));
                assertTrue(tasks.listPending(TARGET, 8).isEmpty());
                assertEquals(1, scalar(db, "SELECT COUNT(*) FROM ja_conversation_message"));
                long version = execution(db, "turn_mail_first").mutationVersion();
                assertThrows(RuntimeException.class, () -> consume(db, run, "turn_mail_first", input,
                        text("伪造的模型输入"), 2));
                assertEquals(version, execution(db, "turn_mail_first").mutationVersion());
                assertEquals("BOUND", mailboxState(db, "msg_mail_0"));
                assertEquals(1, scalar(db, "SELECT COUNT(*) FROM ja_conversation_message"));

                consume(db, run, "turn_mail_first", input, input.content(), 2);
                assertEquals("CONSUMED", mailboxState(db, "msg_mail_0"));
                assertEquals(2, scalar(db, "SELECT COUNT(*) FROM ja_conversation_message"));
                ThreadSnapshot snapshot = db.history().readThread(TARGET, null, 20).orElseThrow();
                ThreadSnapshot.ThreadMessageItem displayed = snapshot.items().stream()
                        .filter(ThreadSnapshot.ThreadMessageItem.class::isInstance)
                        .map(ThreadSnapshot.ThreadMessageItem.class::cast).findFirst().orElseThrow();
                assertEquals(SENDER, displayed.sourceThreadId());
                assertEquals("发送方", displayed.sourceTitle());
                assertEquals("消息 0", displayed.content());
                JsonValueCodec json = new JacksonJsonValueCodec(db.mapper());
                assertEquals(new JsonText(SENDER), json.decodeObject(input.content().text()).get("sourceThreadId"));
                assertEquals(new JsonText("消息 0"), json.decodeObject(input.content().text()).get("message"));
                assertEquals(version + 1, execution(db, "turn_mail_first").mutationVersion());
            }
        }
    }

    /** 准入拒绝时不能先 claim 邮箱、留下 Turn 或输入事实；同一原消息仍可按正确内容再次准入。 */
    @Test
    void rejectedAdmissionLeavesPendingMessageAndNoPartialExecution() throws Exception {
        try (TestDatabase db = database("mailbox-admission-rollback")) {
            prepare(db);
            MybatisTaskRepository tasks = db.taskRepository();
            enqueue(tasks, 0);
            AgentInputCommand.Enqueue input = source(db, tasks).loadPendingInputs(TARGET, 1).getFirst();
            AgentInputCommand.Enqueue tampered = new AgentInputCommand.Enqueue(input.operationKey(), input.inputId(),
                    input.kind(), text("被篡改"), input.hostMetadata());
            assertThrows(RuntimeException.class, () -> admit(db, "turn_mail_rejected", List.of(tampered)));
            assertEquals("PENDING", mailboxState(db, "msg_mail_0"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM ja_conversation_turn"));
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM agent_execution"));
            assertEquals(List.of(input), source(db, tasks).loadPendingInputs(TARGET, 8));
            try (AgentStoreProjectionFixture ignored = admit(db, "turn_mail_accepted", List.of(input))) {
                assertNotNull(execution(db, "turn_mail_accepted"));
                assertEquals("BOUND", mailboxState(db, "msg_mail_0"));
            }
        }
    }

    /** 终态只释放尚未消费的绑定；新任务沿用原邮箱幂等身份，已消费消息不能重新投递。 */
    @Test
    void terminalReleasesOnlyUnconsumedMessagesForNextAdmission() throws Exception {
        try (TestDatabase db = database("mailbox-terminal-release")) {
            prepare(db);
            MybatisTaskRepository tasks = db.taskRepository();
            enqueue(tasks, 0);
            enqueue(tasks, 1);
            List<AgentInputCommand.Enqueue> inputs = source(db, tasks).loadPendingInputs(TARGET, 8);
            try (AgentStoreProjectionFixture run = admit(db, "turn_mail_old", inputs)) {
                consume(db, run, "turn_mail_old", inputs.getFirst(), inputs.getFirst().content(), 3);
                run.commitRecords(TurnState.CANCELLED, cursor(), List.of(),
                        List.of(new ExecutionEvent.Terminal(TurnState.CANCELLED, "", "CANCELLED", "取消", null, null)),
                        START.plusSeconds(30));
            }
            assertEquals("CONSUMED", mailboxState(db, "msg_mail_0"));
            assertEquals("PENDING", mailboxState(db, "msg_mail_1"));
            List<AgentInputCommand.Enqueue> pending = source(db, tasks).loadPendingInputs(TARGET, 8);
            assertEquals(List.of(inputs.get(1)), pending);
            try (AgentStoreProjectionFixture ignored = admit(db, "turn_mail_new", pending)) {
                assertEquals("BOUND", mailboxState(db, "msg_mail_1"));
                assertEquals("CONSUMED", mailboxState(db, "msg_mail_0"));
            }
        }
    }

    /** 两个普通 Thread 的通信不要求先建立子任务，来源权限仍由真实 Task repository 校验。 */
    private void prepare(TestDatabase db) {
        db.history().register(new Workspace.Registration("ws_mail", temp.resolve("mail-project"), "Mailbox", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        db.history().createThread(new ThreadSummary.Creation(SENDER, "ws_mail", "发送方", preferences(), START));
        db.history().createThread(new ThreadSummary.Creation(TARGET, "ws_mail", "接收方", preferences(), START));
    }

    /** 稳定消息身份由业务邮箱持久化，测试通过公开 enqueue 而非直接插入展示行。 */
    private static void enqueue(MybatisTaskRepository tasks, int index) {
        tasks.enqueueMessage(new TaskModels.MailboxEnvelope("msg_mail_" + index, SENDER, TARGET, null,
                TaskModels.MailboxKind.MESSAGE, text("消息 " + index), "mail-key-" + index,
                START.plusSeconds(index + 1)));
    }

    /** 真实查询与真实 JSON 编码组合，测试不构造一份与生产不同的队列消息。 */
    private static TaskMailboxQueueInputSource source(TestDatabase db, MybatisTaskRepository tasks) {
        return new TaskMailboxQueueInputSource(tasks, new JacksonJsonValueCodec(db.mapper()));
    }

    /** 初始邮箱绑定与 USER 准入同事务完成；Core 回执使用 submit 类型，业务 RPC 方法仍由 Ja facts 表达。 */
    private static AgentStoreProjectionFixture admit(TestDatabase db, String turn, List<AgentInputCommand.Enqueue> inputs) {
        long revision = db.history().readThread(TARGET, null, 20).orElseThrow().thread().revision();
        Instant at = START.plusSeconds(20 + revision * 20);
        AgentIdentity identity = new AgentIdentity("ja.local", TARGET, turn, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT,
                Optional.of(text("处理待投递消息")), "item_" + turn, Optional.empty(),
                new AgentStore.OperationIdentity("op_" + turn, "agent.submit", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), at, inputs,
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_mail", TurnOrigin.USER, "turn/start", at,
                null, null, null, null, null, null, null, revision, Optional.empty());
        return AgentStoreProjectionFixture.open(db.jaAgentStore(), db.mapper(), request, facts, preferences(), revision);
    }

    /** 采用真实 Core 的待消费 messageId 与命令身份；不以 fixture 自造身份绕过生产关联校验。 */
    private static void consume(TestDatabase db, AgentStoreProjectionFixture run, String turn,
            AgentInputCommand.Enqueue input, UserContent content, long queueRevision) {
        Instant at = START.plusSeconds(25);
        String message = AgentRecordIds.derive(key(turn), "agent.input.message-id", input.operationKey());
        String commandId = run.recordId("agent.input.queue.command", "consume:" + queueRevision);
        AgentInputQueue.Command command = new AgentInputQueue.Command(commandId, AgentInputQueue.Action.CONSUME,
                input.inputId(), null, 1, queueRevision, null, null, at, Map.of());
        List<AgentStore.RecordWrite> writes = new ArrayList<>();
        writes.add(new StorePayloadCodec(db.mapper()).encodeUser(run.recordId("input-consumed", message), message, content, at));
        writes.add(run.controls().encodeInputQueueCommand(commandId, command, at));
        run.commitRecords(TurnState.RUNNING, cursor(), writes, List.of(new ExecutionEvent.InputAccepted(message)), at);
    }

    /** 合法游标只提供事务上下文，测试不会发起模型请求或把邮箱查询当执行行为。 */
    private static TurnExecutionState cursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, "prompt_mail_test", List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }

    /** 使用完整租户身份读取 Core 权威执行版本。 */
    private static AgentStore.ExecutionSnapshot execution(TestDatabase db, String turn) {
        return db.agentStore().loadExecution(key(turn)).orElseThrow();
    }

    /** Ja 测试租户不从工作区路径推导，避免两个身份体系在测试中偶然等同。 */
    private static AgentStore.ExecutionKey key(String turn) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey("ja.local", TARGET, "user_local"), turn);
    }

    /** 直接核对权威邮箱状态，不用 UI 投影状态替代业务是否已消费。 */
    private static String mailboxState(TestDatabase db, String message) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = db.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement("SELECT state FROM ja_task_mailbox_message WHERE message_id=?")) {
            query.setString(1, message);
            try (ResultSet result = query.executeQuery()) { assertTrue(result.next()); return result.getString(1); }
        }
    }

    /** 固定测试 SQL 统计整笔失败是否留下事实；不接受用户输入拼接查询。 */
    private static long scalar(TestDatabase db, String sql) throws Exception {
        try (org.apache.ibatis.session.SqlSession session = db.sessions().openSession();
             PreparedStatement query = session.getConnection().prepareStatement(sql);
             ResultSet result = query.executeQuery()) { assertTrue(result.next()); return result.getLong(1); }
    }

    /** 统一构造有正文的合法 USER 内容，避免测试依赖无效空输入。 */
    private static UserContent text(String value) { return new UserContent(List.of(new TextContent(value))); }
}


