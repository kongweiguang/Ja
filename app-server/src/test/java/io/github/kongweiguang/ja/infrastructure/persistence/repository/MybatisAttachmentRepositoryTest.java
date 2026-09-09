// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.attachment.adapter.out.persistence.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.support.PersistenceTestSupport;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.execution;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 SQLite 验证草稿生命周期与 Turn admission 的附件原子绑定。 */
final class MybatisAttachmentRepositoryTest extends PersistenceTestSupport {
    /** 两个同 Workspace 草稿与 Turn、消息、偏好 CAS 在一个事务内同时提交。 */
    @Test
    void bindsDraftsAtomicallyDuringTurnAdmission() throws Exception {
        try (TestDatabase database = database("attachment-admission")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_first", "1".repeat(64), 12));
            attachments.createDraft(draft("att_second", "2".repeat(64), 24));

            ConversationRepository.AdmissionReceipt receipt = conversations.admit(
                    attachmentOnlyAdmission(List.of("att_first", "att_second")));

            assertEquals(1, receipt.threadRevision());
            assertEquals("分析 att_first.txt 等 2 个文件", receipt.provisionalTitle());
            assertEquals(AttachmentMetadata.Status.BOUND,
                    attachments.findThread("att_first", "thr_attachment").orElseThrow().status());
            assertEquals("item_attachment",
                    attachments.findThread("att_second", "thr_attachment").orElseThrow().boundMessageId());
            ThreadSnapshot history = database.history(conversations)
                    .readThread("thr_attachment", null, 20).orElseThrow();
            assertEquals("分析 att_first.txt 等 2 个文件", history.thread().title());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ThreadPreferences.TitleSource.PLACEHOLDER,
                    history.thread().preferences().titleSource());
            assertEquals(2, history.items().stream()
                    .filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .map(ThreadSnapshot.UserInputItem.class::cast)
                    .mapToLong(item -> item.attachments().size()).sum());
            assertFalse(history.items().stream().anyMatch(ThreadSnapshot.TextItem.class::isInstance));
        }
    }

    /** 超过 250 MiB 必须回滚 Turn 和所有 DRAFT→BOUND 变化，不留下部分关系。 */
    @Test
    void rollsBackAllBindingsWhenAggregateQuotaIsExceeded() throws Exception {
        try (TestDatabase database = database("attachment-quota")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_large_a", "a".repeat(64), 90L * 1024 * 1024));
            attachments.createDraft(draft("att_large_b", "b".repeat(64), 90L * 1024 * 1024));
            attachments.createDraft(draft("att_large_c", "c".repeat(64), 90L * 1024 * 1024));

            StorageException failure = assertThrows(StorageException.class, () -> conversations.admit(
                    admission(List.of("att_large_a", "att_large_b", "att_large_c"))));

            assertEquals(StorageException.Code.CAS_CONFLICT, failure.code());
            assertFalse(conversations.findTurn("thr_attachment", "turn_attachment").isPresent());
            ThreadSnapshot history = database.history(conversations)
                    .readThread("thr_attachment", null, 20).orElseThrow();
            assertEquals("Attachment", history.thread().title());
            assertEquals(0, history.thread().revision());
            assertEquals(0, history.turns().size());
            assertEquals(0, history.items().size());
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                assertEquals("DRAFT", PersistenceMappers.open(session).attachments()
                        .selectAttachment("att_large_a").status());
                assertEquals("DRAFT", PersistenceMappers.open(session).attachments()
                        .selectAttachment("att_large_b").status());
                assertEquals("DRAFT", PersistenceMappers.open(session).attachments()
                        .selectAttachment("att_large_c").status());
            }
        }
    }

    /** 排队附件保持 DRAFT 但由 input 独占，不能再被 Composer、GC 或后续 Turn 抢占。 */
    @Test
    void reservesQueuedDraftAgainstPreviewDiscardExpiryAndAdmission() throws Exception {
        try (TestDatabase database = database("attachment-reservation")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_reserved", "3".repeat(64), 32));
            conversations.admit(admission(List.of()));

            ConversationRepository.QueueMutation queued = conversations.enqueueInput(pending(
                    "input_reserved", List.of("att_reserved"), START.plusSeconds(2)));

            assertEquals(List.of("att_reserved"), queued.inputQueue().items().getFirst().attachments().stream()
                    .map(io.github.kongweiguang.ja.conversation.domain.AttachmentSummary::attachmentId).toList());
            assertTrue(attachments.findDraft("att_reserved", "ws_attachment").isEmpty());
            assertEquals(AttachmentMetadata.Status.DRAFT,
                    attachments.findThread("att_reserved", "thr_attachment").orElseThrow().status());
            assertEquals(0, attachments.expireDrafts(START.plusSeconds(172_800)));
            assertThrows(StorageException.class,
                    () -> attachments.discardDraft("att_reserved", START.plusSeconds(3)));
            assertThrows(StorageException.class, () -> conversations.admit(new ConversationRepository.TurnAdmission(
                    "thr_attachment", "turn_second", "item_second",
                    new ModelMessage(ModelRole.USER, List.of(new AttachmentContent("att_reserved"))),
                    List.of("att_reserved"), 1, START.plusSeconds(4), execution("cfg_attachment"))));
            assertFalse(conversations.findTurn("thr_attachment", "turn_second").isPresent());
        }
    }

    /** 编辑只丢弃被移出的附件并重建稳定 ordinal；删除输入会立即释放并丢弃全部剩余预留。 */
    @Test
    void updatesAndDeletesAttachmentReservationsAtomically() throws Exception {
        try (TestDatabase database = database("attachment-reservation-update")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_removed", "4".repeat(64), 10));
            attachments.createDraft(draft("att_kept", "5".repeat(64), 11));
            attachments.createDraft(draft("att_added", "6".repeat(64), 12));
            conversations.admit(admission(List.of()));
            conversations.enqueueInput(pending("input_edit", List.of("att_removed", "att_kept"),
                    START.plusSeconds(2)));

            ConversationRepository.QueueMutation updated = conversations.updateInput(
                    "thr_attachment", "turn_attachment", "input_edit", 1,
                    attachmentContent(List.of("att_kept", "att_added")), START.plusSeconds(3));

            assertEquals(List.of("att_kept", "att_added"), updated.inputQueue().items().getFirst()
                    .attachments().stream().map(
                            io.github.kongweiguang.ja.conversation.domain.AttachmentSummary::attachmentId).toList());
            assertEquals("DISCARDED",
                    rawAttachment(database, "att_removed").status());
            assertEquals("input_edit", reservation(database, "att_kept"));
            assertEquals("input_edit", reservation(database, "att_added"));

            conversations.deleteInput("thr_attachment", "turn_attachment", "input_edit", 2,
                    START.plusSeconds(4));

            assertEquals("DISCARDED", rawAttachment(database, "att_kept").status());
            assertEquals("DISCARDED", rawAttachment(database, "att_added").status());
            assertNull(reservation(database, "att_kept"));
            assertNull(reservation(database, "att_added"));
        }
    }

    /** 消费把预留迁到本次生成的精确 USER Message，附件-only 输入也必须形成独立历史项。 */
    @Test
    void consumesAttachmentOnlyInputIntoExactUserMessage() throws Exception {
        try (TestDatabase database = database("attachment-reservation-consume")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_consumed", "7".repeat(64), 13));
            conversations.admit(admission(List.of()));
            ConversationRepository.QueueMutation queued = conversations.enqueueInput(pending(
                    "input_consumed", List.of("att_consumed"), START.plusSeconds(2)));

            ConversationRepository.InputConsumption consumed = conversations.consumeInput(
                    "thr_attachment", "turn_attachment", ConversationRepository.InputSelection.from(
                            queued.inputQueue().items().getFirst()), 0, START.plusSeconds(3),
                    execution("cfg_attachment")).orElseThrow();

            AttachmentMetadata metadata = attachments.findThread(
                    "att_consumed", "thr_attachment").orElseThrow();
            assertEquals(AttachmentMetadata.Status.BOUND, metadata.status());
            assertEquals(consumed.userItemId(), metadata.boundMessageId());
            assertTrue(consumed.inputQueue().items().isEmpty());
            ThreadSnapshot history = database.history(conversations)
                    .readThread("thr_attachment", null, 20).orElseThrow();
            ThreadSnapshot.UserInputItem queuedItem = history.items().stream()
                    .filter(ThreadSnapshot.UserInputItem.class::isInstance)
                    .map(ThreadSnapshot.UserInputItem.class::cast)
                    .filter(item -> item.content().attachmentIds().contains("att_consumed"))
                    .findFirst().orElseThrow();
            assertEquals("", queuedItem.content().text());
            assertEquals(List.of("att_consumed"), queuedItem.attachments().stream()
                    .map(io.github.kongweiguang.ja.conversation.domain.AttachmentSummary::attachmentId).toList());
        }
    }

    /** SUSPENDED 直接取消也必须和普通终态一样立即丢弃附件并删除预留关系。 */
    @Test
    void cancelSuspendedReleasesQueuedAttachments() throws Exception {
        try (TestDatabase database = database("attachment-suspended-cancel")) {
            MybatisConversationRepository conversations = prepareThread(database);
            MybatisAttachmentRepository attachments = database.attachments();
            attachments.createDraft(draft("att_suspended", "8".repeat(64), 14));
            conversations.admit(admission(List.of()));
            conversations.enqueueInput(pending("input_suspended", List.of("att_suspended"),
                    START.plusSeconds(2)));
            try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
                session.getConnection().createStatement().executeUpdate(
                        "UPDATE turns SET state='SUSPENDED' WHERE turn_id='turn_attachment'");
                session.commit();
            }

            conversations.cancelSuspended("turn_attachment", 1, START.plusSeconds(3));

            assertEquals("DISCARDED",
                    rawAttachment(database, "att_suspended").status());
            assertNull(reservation(database, "att_suspended"));
            assertTrue(conversations.peekInput("turn_attachment", null).isEmpty());
        }
    }

    /** 创建绑定测试所需的单一 Workspace 与 Thread。 */
    private MybatisConversationRepository prepareThread(TestDatabase database) {
        MybatisConversationRepository conversations = database.agentStore();
        MybatisHistoryService history = database.history(conversations);
        history.register(new Workspace.Registration("ws_attachment", temp.resolve("attachment-project"),
                "Attachment", Workspace.Trust.TRUSTED, START));
        conversations.createThread(new ConversationRepository.ThreadDefinition(
                "thr_attachment", "ws_attachment", "Attachment",
                preferences("provider_attachment", "model_attachment"), START));
        return conversations;
    }

    /** 生成一个内容已由 blob store 验证过的数据库草稿事实。 */
    private static AttachmentRepository.Draft draft(String attachmentId, String sha256, long sizeBytes) {
        return new AttachmentRepository.Draft(attachmentId, "ws_attachment", attachmentId + ".txt",
                sizeBytes, sha256, AttachmentMetadata.MediaKind.TEXT, "text/plain",
                START, START.plusSeconds(86_400));
    }

    /** 将候选附件列表冻结到同一个 Turn admission。 */
    private static ConversationRepository.TurnAdmission admission(List<String> attachmentIds) {
        return new ConversationRepository.TurnAdmission(
                "thr_attachment", "turn_attachment",
                "item_attachment", new ModelMessage(ModelRole.USER,
                List.of(new TextContent("inspect attachments"))), attachmentIds, 0, START.plusSeconds(1),
                execution("cfg_attachment"));
    }

    /** attachment-only 历史只保存真实附件块和关系，不写入或投影伪用户提示。 */
    private static ConversationRepository.TurnAdmission attachmentOnlyAdmission(List<String> attachmentIds) {
        return new ConversationRepository.TurnAdmission(
                "thr_attachment", "turn_attachment",
                "item_attachment", new ModelMessage(ModelRole.USER,
                attachmentIds.stream().map(AttachmentContent::new)
                        .map(ModelContent.class::cast).toList()),
                attachmentIds, 0, START.plusSeconds(1), execution("cfg_attachment"));
    }

    /** 构造只含附件的规范队列输入，正文为空也保持可提交。 */
    private static ConversationRepository.PendingInput pending(String inputId, List<String> attachmentIds,
                                                               Instant createdAt) {
        return new ConversationRepository.PendingInput(inputId, "thr_attachment", "turn_attachment",
                ConversationRepository.InputKind.FOLLOW_UP, attachmentContent(attachmentIds), createdAt);
    }

    /** 附件 block 顺序就是后续 reservation/message relation 的 ordinal。 */
    private static UserContent attachmentContent(List<String> attachmentIds) {
        return new UserContent(attachmentIds.stream().map(AttachmentContent::new)
                .map(io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock.class::cast).toList());
    }

    /** 绕过公开可见性过滤读取测试附件终态，仅用于验证本事务是否完整清理。 */
    private static io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords.AttachmentRow
            rawAttachment(TestDatabase database, String attachmentId) {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
            return PersistenceMappers.open(session).attachments().selectAttachment(attachmentId);
        }
    }

    /** 读取唯一 reservation owner，验证编辑、删除与终态不会留下幽灵占用。 */
    private static String reservation(TestDatabase database, String attachmentId) {
        try (org.apache.ibatis.session.SqlSession session = database.sessions().openSession()) {
            return PersistenceMappers.open(session).attachments().selectReservationInputId(attachmentId);
        }
    }
}
