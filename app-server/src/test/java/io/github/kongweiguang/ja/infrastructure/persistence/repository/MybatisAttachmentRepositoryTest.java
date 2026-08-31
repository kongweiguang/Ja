// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.attachment.adapter.out.persistence.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
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

import java.util.List;

import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.runtime;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                    attachments.findBound("att_first", "thr_attachment").orElseThrow().status());
            assertEquals("turn_attachment",
                    attachments.findBound("att_second", "thr_attachment").orElseThrow().boundTurnId());
            ThreadSnapshot history = database.history(conversations)
                    .readThread("thr_attachment", null, 20).orElseThrow();
            assertEquals("分析 att_first.txt 等 2 个文件", history.thread().title());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.ThreadPreferences.TitleSource.PLACEHOLDER,
                    history.thread().preferences().titleSource());
            assertEquals(2, history.items().stream()
                    .filter(ThreadSnapshot.AttachmentItem.class::isInstance).count());
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
                runtime("provider_attachment", "model_attachment", "cfg_attachment"),
                "item_attachment", new ModelMessage(ModelRole.USER,
                List.of(new TextContent("inspect attachments"))), attachmentIds, 0, START.plusSeconds(1));
    }

    /** attachment-only 历史只保存真实附件块和关系，不写入或投影伪用户提示。 */
    private static ConversationRepository.TurnAdmission attachmentOnlyAdmission(List<String> attachmentIds) {
        return new ConversationRepository.TurnAdmission(
                "thr_attachment", "turn_attachment",
                runtime("provider_attachment", "model_attachment", "cfg_attachment"),
                "item_attachment", new ModelMessage(ModelRole.USER,
                attachmentIds.stream().map(AttachmentContent::new)
                        .map(ModelContent.class::cast).toList()),
                attachmentIds, 0, START.plusSeconds(1));
    }
}
