// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.application.gateway.AttachmentRepository;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.support.AgentStoreProjectionFixture;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import org.junit.jupiter.api.Test;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import org.apache.ibatis.session.SqlSession;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentMapper;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 AgentStore 事务覆盖附件准入、预留、消费、取消及复问，不借用旧执行仓储。 */
final class MybatisAttachmentRepositoryTest extends PersistenceTestSupport {
    /** 仅在本连接绕过 schema CHECK 注入损坏历史值，验证读取仍分类为不可重试的元数据错误。 */
    @Test void classifiesMalformedStoredTimestampWithoutExposingItsValue() throws Exception {
        try (TestDatabase db = database("attachment-corrupt-time")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_corrupt_time", "c".repeat(64), 12));
            String invalidTime = "private-corrupt-timestamp";
            try (SqlSession session = db.sessions().openSession()) {
                try (java.sql.Statement pragma = session.getConnection().createStatement()) {
                    pragma.execute("PRAGMA ignore_check_constraints = ON");
                }
                try {
                    try (PreparedStatement statement = session.getConnection().prepareStatement(
                            "UPDATE ja_attachment SET created_at_ms = ? WHERE attachment_id = ?")) {
                        statement.setString(1, invalidTime);
                        statement.setString(2, "att_corrupt_time");
                        assertEquals(1, statement.executeUpdate());
                    }
                    try (PreparedStatement query = session.getConnection().prepareStatement(
                            "SELECT typeof(created_at_ms),created_at_ms FROM ja_attachment WHERE attachment_id=?")) {
                        query.setString(1, "att_corrupt_time");
                        try (ResultSet row = query.executeQuery()) {
                            assertTrue(row.next());
                            assertEquals("text", row.getString(1));
                            assertEquals(invalidTime, row.getString(2));
                        }
                    }
                    session.commit();
                } finally {
                    try (java.sql.Statement pragma = session.getConnection().createStatement()) {
                        pragma.execute("PRAGMA ignore_check_constraints = OFF");
                    }
                }
            }
            StorageException failure = assertThrows(StorageException.class,
                    () -> db.attachments().findDraft("att_corrupt_time", "ws_attachment"));
            assertEquals(StorageException.Code.INVALID_STATE, failure.code());
            assertEquals("attachment metadata is invalid", failure.getMessage());
            assertNull(failure.getCause());
            assertEquals("DRAFT", rawStatus(db, "att_corrupt_time"));
        }
    }

    /** 两个草稿、真实 USER 消息和占位标题必须随同一次准入提交。 */
    @Test void bindsDraftsAtomicallyDuringTurnAdmission() throws Exception {
        try (TestDatabase db = database("attachment-admission")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_first", "1".repeat(64), 12));
            db.attachments().createDraft(draft("att_second", "2".repeat(64), 24));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment", content("att_first", "att_second"), null)) {
                assertEquals(AttachmentMetadata.Status.BOUND, db.attachments().findThread("att_first", "thr_attachment").orElseThrow().status());
                assertEquals("item_attachment", db.attachments().findThread("att_second", "thr_attachment").orElseThrow().boundMessageId());
                ThreadSnapshot history = history(db);
                assertEquals(1, history.thread().revision());
                assertEquals("分析 att_first.txt 等 2 个文件", history.thread().title());
                assertEquals(ThreadPreferences.TitleSource.PLACEHOLDER, history.thread().preferences().titleSource());
                assertEquals(2, history.items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance)
                        .map(ThreadSnapshot.UserInputItem.class::cast).mapToLong(item -> item.attachments().size()).sum());
                assertFalse(history.items().stream().anyMatch(ThreadSnapshot.TextItem.class::isInstance));
            }
        }
    }
    /** 批次总量超限必须回滚通用记录、Ja Turn 与每个附件，不能留下半绑定。 */
    @Test void rollsBackAllBindingsWhenAggregateQuotaIsExceeded() throws Exception {
        try (TestDatabase db = database("attachment-quota")) {
            prepareThread(db);
            for (String id : List.of("a", "b", "c")) {
                db.attachments().createDraft(draft("att_large_" + id, id.repeat(64), 90L * 1024 * 1024));
            }
            StorageException failure = assertThrows(StorageException.class, () -> admit(db,
                    "turn_attachment", "item_attachment", content("att_large_a", "att_large_b", "att_large_c"), null));
            assertEquals(StorageException.Code.CAS_CONFLICT, failure.code());
            assertTrue(failure.getMessage().contains("250 MiB"));
            assertEquals(0, history(db).turns().size());
            assertEquals(0, history(db).items().size());
            assertEquals(0, history(db).thread().revision());
            assertTrue(db.agentStore().loadExecution(key("turn_attachment")).isEmpty());
            for (String id : List.of("a", "b", "c")) {
                assertEquals("DRAFT", raw(db, "att_large_" + id).status());
            }
        }
    }
    /** 预留不能被准入、Composer 丢弃或到期回收抢走，但所属会话仍可预览。 */
    @Test void reservesQueuedDraftAgainstPreviewDiscardExpiryAndAdmission() throws Exception {
        try (TestDatabase db = database("attachment-reservation")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_reserved", "3".repeat(64), 32));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment", new UserContent(List.of(new TextContent("检查"))), null)) {
                queue(db, run, "input_reserved", AgentInputQueue.Action.ENQUEUE, 0, 1, content("att_reserved"));
                assertEquals("input_reserved", reservation(db, "att_reserved"));
                assertTrue(db.attachments().findDraft("att_reserved", "ws_attachment").isEmpty());
                assertEquals(AttachmentMetadata.Status.DRAFT, db.attachments().findThread("att_reserved", "thr_attachment").orElseThrow().status());
                assertEquals(0, db.attachments().expireDrafts(START.plusSeconds(172_800)));
                assertThrows(StorageException.class, () -> db.attachments().discardDraft("att_reserved", START.plusSeconds(3)));
                assertThrows(StorageException.class, () -> admit(db, "turn_second", "item_second", content("att_reserved"), null));
                assertTrue(db.agentStore().loadExecution(key("turn_second")).isEmpty());
            }
        }
    }
    /** 编辑移除项立即丢弃，保留和新增项按新顺序预留，删除不留下悬空占用。 */
    @Test void updatesAndDeletesAttachmentReservationsAtomically() throws Exception {
        try (TestDatabase db = database("attachment-update")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_removed", "4".repeat(64), 10));
            db.attachments().createDraft(draft("att_kept", "5".repeat(64), 11));
            db.attachments().createDraft(draft("att_added", "6".repeat(64), 12));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment", new UserContent(List.of(new TextContent("检查"))), null)) {
                queue(db, run, "input_edit", AgentInputQueue.Action.ENQUEUE, 0, 1, content("att_removed", "att_kept"));
                queue(db, run, "input_edit", AgentInputQueue.Action.UPDATE, 1, 2, content("att_kept", "att_added"));
                assertEquals("DISCARDED", raw(db, "att_removed").status());
                assertEquals("input_edit", reservation(db, "att_kept"));
                assertEquals("input_edit", reservation(db, "att_added"));
                assertEquals(List.of("att_kept", "att_added"), reservedOrder(db, "input_edit"));
                queue(db, run, "input_edit", AgentInputQueue.Action.DELETE, 2, 3, null);
                assertEquals("DISCARDED", raw(db, "att_kept").status());
                assertEquals("DISCARDED", raw(db, "att_added").status());
                assertNull(reservation(db, "att_kept"));
                assertNull(reservation(db, "att_added"));
            }
        }
    }
    /** 替换正文遇到失效附件时必须撤回前面已执行的丢弃动作，原输入仍可继续消费。 */
    @Test void rollsBackReservationReplacementWhenNewAttachmentIsUnavailable() throws Exception {
        try (TestDatabase db = database("attachment-update-rollback")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_original", "a".repeat(64), 10));
            db.attachments().createDraft(draft("att_invalid", "b".repeat(64), 11));
            db.attachments().discardDraft("att_invalid", START.plusSeconds(1));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment",
                    new UserContent(List.of(new TextContent("检查"))), null)) {
                queue(db, run, "input_rollback", AgentInputQueue.Action.ENQUEUE, 0, 1, content("att_original"));
                AgentStore.ExecutionSnapshot before = db.agentStore().loadExecution(key("turn_attachment")).orElseThrow();
                StorageException failure = assertThrows(StorageException.class, () -> queue(db, run,
                        "input_rollback", AgentInputQueue.Action.UPDATE, 1, 2, content("att_invalid")));
                assertEquals(StorageException.Code.CAS_CONFLICT, failure.code());
                assertEquals(before, db.agentStore().loadExecution(key("turn_attachment")).orElseThrow());
                assertEquals("DRAFT", raw(db, "att_original").status());
                assertEquals("input_rollback", reservation(db, "att_original"));
                assertEquals(List.of("att_original"), reservedOrder(db, "input_rollback"));
                queue(db, run, "input_rollback", AgentInputQueue.Action.CONSUME, 1, 2, content("att_original"));
                assertEquals("BOUND", raw(db, "att_original").status());
            }
        }
    }

    /** 附件-only 消费在同一提交中将 reservation 迁到确切 USER 消息关系。 */
    @Test void consumesAttachmentOnlyInputIntoExactUserMessage() throws Exception {
        try (TestDatabase db = database("attachment-consume")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_consumed", "7".repeat(64), 13));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment", new UserContent(List.of(new TextContent("检查"))), null)) {
                queue(db, run, "input_consumed", AgentInputQueue.Action.ENQUEUE, 0, 1, content("att_consumed"));
                queue(db, run, "input_consumed", AgentInputQueue.Action.CONSUME, 1, 2, content("att_consumed"));
                AttachmentMetadata attachment = db.attachments().findThread("att_consumed", "thr_attachment").orElseThrow();
                assertEquals(AttachmentMetadata.Status.BOUND, attachment.status());
                assertEquals("item_input_consumed", attachment.boundMessageId());
                assertNull(reservation(db, "att_consumed"));
                ThreadSnapshot.UserInputItem item = history(db).items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance)
                        .map(ThreadSnapshot.UserInputItem.class::cast).filter(value -> value.content().attachmentIds().contains("att_consumed"))
                        .findFirst().orElseThrow();
                assertEquals("", item.content().text());
                assertEquals(List.of("att_consumed"), item.attachments().stream().map(value -> value.attachmentId()).toList());
            }
        }
    }
    /** Reask 只改变可见路径，源关系保留用于审计、授权和 blob 引用计数。 */
    @Test void reusesSourceBoundAttachmentWithoutMovingItsAuditRelation() throws Exception {
        try (TestDatabase db = database("attachment-reask")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_shared", "9".repeat(64), 16));
            try (AgentStoreProjectionFixture first = admit(db, "turn_attachment", "item_attachment", content("att_shared"), null)) {
                first.commitRecords(TurnState.RUNNING, cursor(), List.of(), List.of(), START.plusSeconds(2));
                terminal(first, TurnState.FAILED);
            }
            try (AgentStoreProjectionFixture second = admit(db, "turn_reask", "item_reask", content("att_shared"), "item_attachment")) {
                assertEquals(List.of("turn_reask"), history(db).turns().stream().map(ThreadSnapshot.Turn::turnId).toList());
                assertEquals(1, history(db).items().stream().filter(ThreadSnapshot.UserInputItem.class::isInstance).count());
                try (SqlSession session = db.sessions().openSession()) {
                    AttachmentMapper mapper = PersistenceMappers.open(session).attachments();
                    assertTrue(mapper.isMessageAttachmentBoundTo("att_shared", "item_attachment", "thr_attachment", "ws_attachment"));
                    assertTrue(mapper.isMessageAttachmentBoundTo("att_shared", "item_reask", "thr_attachment", "ws_attachment"));
                }
                assertTrue(db.attachments().findUnreferencedBlobs(10).isEmpty());
                assertTrue(db.attachments().findAllBlobs().contains("9".repeat(64)));
            }
        }
    }
    /** 挂起取消通过 Store 状态推进，不能只改 Ja 投影伪造终态。 */
    @Test void cancelSuspendedReleasesQueuedAttachments() throws Exception {
        try (TestDatabase db = database("attachment-cancel")) {
            prepareThread(db);
            db.attachments().createDraft(draft("att_suspended", "8".repeat(64), 14));
            try (AgentStoreProjectionFixture run = admit(db, "turn_attachment", "item_attachment", new UserContent(List.of(new TextContent("检查"))), null)) {
                queue(db, run, "input_suspended", AgentInputQueue.Action.ENQUEUE, 0, 1, content("att_suspended"));
                run.commitRecords(TurnState.SUSPENDED, cursor(), List.of(), List.of(), START.plusSeconds(4));
                terminal(run, TurnState.CANCELLED);
                assertEquals("DISCARDED", raw(db, "att_suspended").status());
                assertNull(reservation(db, "att_suspended"));
                assertEquals("CANCELLED", db.agentStore().loadExecution(key("turn_attachment")).orElseThrow().state());
            }
        }
    }
    /** History 拥有 Workspace 和会话元数据，通用 Store 只处理执行事实。 */
    private void prepareThread(TestDatabase db) {
        db.history().register(new Workspace.Registration("ws_attachment", temp.resolve("attachment-project"), "Attachment", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, START));
        db.history().createThread(new ThreadSummary.Creation("thr_attachment", "ws_attachment", "Attachment",
                preferences("provider_attachment", "model_attachment"), START));
    }
    /** Driver 与同库参与者复用生产事务顺序，复问绑定准入前确切版本与来源。 */
    private static AgentStoreProjectionFixture admit(TestDatabase db, String turn, String message, UserContent input, String source) {
        long revision = history(db).thread().revision();
        Instant at = START.plusSeconds(revision * 30 + 1);
        AgentIdentity identity = new AgentIdentity("ja.local", "thr_attachment", turn, "user_local");
        AgentExecutionRequest request = new AgentExecutionRequest(identity, AgentExecutionRequest.AdmissionKind.USER_INPUT, Optional.of(input),
                message, Optional.empty(), new AgentStore.OperationIdentity("op_" + turn, "turn.start", "a".repeat(64), new io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec().decodeObject("{}")),
                Map.of(), source == null ? AgentExecutionRequest.HistorySelection.currentConversation()
                        : AgentExecutionRequest.HistorySelection.explicit(List.of(), revision), at, List.of(),
                Optional.empty(), Optional.empty());
        AdmissionFacts facts = new AdmissionFacts(identity, "ws_attachment", TurnOrigin.USER, source == null ? "turn/start" : "turn/reask",
                at, source, null, null, null, null, null, null, revision, Optional.empty());
        return AgentStoreProjectionFixture.open(db.jaAgentStore(), db.mapper(), request, facts,
                preferences("provider_attachment", "model_attachment"), revision);
    }
    /** 正文和命令作为一次提交；消费同时保存原输入身份的 USER 事实。 */
    private static void queue(TestDatabase db, AgentStoreProjectionFixture run, String input, AgentInputQueue.Action action,
            long inputRevision, long queueRevision, UserContent content) {
        Instant at = START.plusSeconds(queueRevision + 2);
        List<AgentStore.RecordWrite> records = new ArrayList<AgentStore.RecordWrite>();
        StorePayloadCodec codec = new StorePayloadCodec(db.mapper());
        String body = null;
        if (action == AgentInputQueue.Action.ENQUEUE || action == AgentInputQueue.Action.UPDATE) {
            body = run.recordId("agent.input.message", input + ":" + queueRevision);
            records.add(codec.encodeQueuedUser(body, "item_" + input, content, at));
        } else if (action == AgentInputQueue.Action.CONSUME) {
            records.add(codec.encodeUser(run.recordId("agent.user.message", input), "item_" + input, content, at));
        }
        String commandId = run.recordId("agent.input.queue.command", input + ":" + queueRevision);
        AgentInputQueue.Command command = new AgentInputQueue.Command(commandId, action, input,
                action == AgentInputQueue.Action.ENQUEUE ? AgentInputQueue.Kind.FOLLOW_UP : null,
                inputRevision, queueRevision, body, null, at, Map.of());
        records.add(run.controls().encodeInputQueueCommand(commandId, command, at));
        run.commitRecords(TurnState.RUNNING, cursor(), records, List.of(), at);
    }
    /** Core 终态事件同时驱动 Ja 诊断和附件释放，不另造一份终态记录。 */
    private static void terminal(AgentStoreProjectionFixture run, TurnState state) {
        String error = state == TurnState.FAILED ? "MODEL_FAILURE" : "CANCELLED";
        run.commitRecords(state, cursor(), List.of(),
                List.of(new ExecutionEvent.Terminal(state, "结束", error, "验证终态", null, null)), START.plusSeconds(20));
    }
    /** 测试不调用模型，但仍保存合法 READY 游标供数据库校验。 */
    private static TurnExecutionState cursor() {
        return new TurnExecutionState.Ready(new TurnExecutionState.Common(0, 0, 1, "prompt_projection_test", List.of()),
                TurnExecutionState.Next.ASSISTANT, null);
    }
    /** 当前快照验证 UI 可见路径，不用物理行数替代历史用例。 */
    private static ThreadSnapshot history(TestDatabase db) { return db.history().readThread("thr_attachment", null, 20).orElseThrow(); }
    /** 回滚检查使用完整租户和会话键。 */
    private static AgentStore.ExecutionKey key(String turn) { return new AgentStore.ExecutionKey(new AgentStore.ConversationKey("ja.local", "thr_attachment", "user_local"), turn); }
    /** 冻结已导入草稿元数据，不依赖测试机器的大文件。 */
    private static AttachmentRepository.Draft draft(String id, String hash, long bytes) {
        return new AttachmentRepository.Draft(id, "ws_attachment", id + ".txt", bytes, hash,
                AttachmentMetadata.MediaKind.TEXT, "text/plain", START, START.plusSeconds(86_400));
    }
    /** 保持附件-only 语义和原始顺序，不生成伪用户提示。 */
    private static UserContent content(String... ids) {
        return new UserContent(java.util.Arrays.stream(ids).map(AttachmentContent::new).map(UserContentBlock.class::cast).toList());
    }
    /** 读取过滤外的原行，仅用于核对终态清理。 */
    private static AttachmentRecords.AttachmentRow raw(TestDatabase db, String id) {
        try (SqlSession session = db.sessions().openSession()) { return PersistenceMappers.open(session).attachments().selectAttachment(id); }
    }
    /** 损坏时间字段不能经行映射读取；状态回滚断言只取独立的权威列。 */
    private static String rawStatus(TestDatabase db, String id) throws Exception {
        try (SqlSession session = db.sessions().openSession(); PreparedStatement query = session.getConnection().prepareStatement(
                "SELECT status FROM ja_attachment WHERE attachment_id=?")) {
            query.setString(1, id);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }
    /** 核对唯一 owner，不把 DRAFT 误认为未被占有。 */
    private static String reservation(TestDatabase db, String id) {
        try (SqlSession session = db.sessions().openSession()) { return PersistenceMappers.open(session).attachments().selectReservationInputId(id); }
    }
    /** 数据库 ordinal 决定界面附件展示顺序。 */
    private static List<String> reservedOrder(TestDatabase db, String input) throws Exception {
        try (SqlSession session = db.sessions().openSession(); PreparedStatement query = session.getConnection().prepareStatement(
                "SELECT attachment_id FROM ja_turn_input_attachment WHERE input_id=? ORDER BY ordinal")) {
            query.setString(1, input);
            try (ResultSet rows = query.executeQuery()) {
                List<String> ids = new ArrayList<String>();
                while (rows.next()) {
                    ids.add(rows.getString(1));
                }
                return ids;
            }
        }
    }
}
