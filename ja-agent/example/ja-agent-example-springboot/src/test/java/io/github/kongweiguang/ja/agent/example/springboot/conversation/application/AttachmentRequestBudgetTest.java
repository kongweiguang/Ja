// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestFingerprint;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件预算策略验证 checkpoint、队列替换、引用次数与操作回执的独立边界。
 */
final class AttachmentRequestBudgetTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity("tenant_demo", "thread_demo", "turn_demo", "user_demo");

    /** 只统计 checkpoint 保留区间，并在 UPDATE 时去掉旧项后保留所有重复引用。 */
    @Test
    void countsRetainedHistoryAndQueueReplacementOccurrences() {
        List<ContextMessage> context = List.of(
                message("message_old", 1, "att_old"),
                message("message_current", 5, "att_shared", "att_shared"));
        List<AttachmentRequestBudget.QueueItem> queue = List.of(
                new AttachmentRequestBudget.QueueItem("input_replace", List.of("att_removed")),
                new AttachmentRequestBudget.QueueItem("input_keep", List.of("att_queued")));

        List<String> references = AttachmentRequestBudget.collectReferences(context, 5, queue,
                Optional.of("input_replace"), List.of("att_shared", "att_new"));
        List<String> persistedReferences = AttachmentRequestBudget.collectReferences(context, 5, queue,
                Optional.of("input_replace"), List.of());

        assertEquals(List.of("att_shared", "att_shared", "att_queued", "att_shared", "att_new"), references);
        assertEquals(List.of("att_shared", "att_shared", "att_queued"), persistedReferences);
    }

    /** 对每次引用分别累加；超限使用现有稳定的请求总量错误返回可恢复状态。 */
    @Test
    void repeatedIdsConsumeBudgetForEveryOccurrence() {
        Map<String, AttachmentMetadata> metadata = Map.of("att_shared", metadata("att_shared", 4));

        assertEquals(8, AttachmentRequestBudget.totalBytes(List.of("att_shared", "att_shared"), metadata, 8));
        AttachmentException failure = assertThrows(AttachmentException.class,
                () -> AttachmentRequestBudget.totalBytes(List.of("att_shared", "att_shared"), metadata, 7));
        assertEquals(AttachmentException.Reason.REQUEST_TOO_LARGE, failure.reason());
    }

    /** Store 按租户和用户查回执；同操作键跨 Thread/Turn 时仍交由 Core 判定冲突或回放。 */
    @Test
    void onlyMatchingAdmissionReceiptSkipsBudgetPreflight() {
        String operationKey = "submit_demo";
        String scopedOperationKey = AgentRequestFingerprint.submissionOperationKey(IDENTITY, operationKey);
        AgentStore.OperationReceipt receipt = new AgentStore.OperationReceipt(
                new AgentStore.ConversationKey(IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId()),
                IDENTITY.turnId(), scopedOperationKey, "a".repeat(64), "agent.turn.submit", 1, 1, 0, 0,
                JsonObjects.builder().build(), Instant.EPOCH);

        assertTrue(AttachmentRequestBudget.receiptMatches(IDENTITY, operationKey, Optional.of(receipt)));
        assertTrue(AttachmentRequestBudget.receiptMatches(
                new AgentIdentity("tenant_demo", "thread_other", "turn_other", "user_demo"),
                operationKey, Optional.of(receipt)));
        assertFalse(AttachmentRequestBudget.receiptMatches(
                new AgentIdentity("tenant_other", "thread_demo", "turn_demo", "user_demo"),
                operationKey, Optional.of(receipt)));
        assertFalse(AttachmentRequestBudget.receiptMatches(
                new AgentIdentity("tenant_demo", "thread_demo", "turn_demo", "user_other"),
                operationKey, Optional.of(receipt)));
        assertFalse(AttachmentRequestBudget.receiptMatches(IDENTITY, "other_operation", Optional.of(receipt)));
        AgentStore.OperationReceipt rawKeyReceipt = new AgentStore.OperationReceipt(
                new AgentStore.ConversationKey(IDENTITY.tenantId(), IDENTITY.threadId(), IDENTITY.userId()),
                IDENTITY.turnId(), operationKey, "b".repeat(64), "agent.turn.submit", 1, 1, 0, 0,
                JsonObjects.builder().build(), Instant.EPOCH);
        assertFalse(AttachmentRequestBudget.receiptMatches(IDENTITY, operationKey, Optional.of(rawKeyReceipt)));
        assertFalse(AttachmentRequestBudget.receiptMatches(IDENTITY, operationKey, Optional.empty()));
    }

    /** 审批挂起可留下未配对 ToolCallBlock；预算仍提取其前的附件引用，不解析 Tool 配对状态。 */
    @Test
    void countsAttachmentsWhenApprovalLeavesAnUnpairedToolCall() {
        ContextMessage pendingApproval = new ContextMessage("message_pending", IDENTITY.turnId(), 1,
                ContextMessage.Role.ASSISTANT,
                List.of(new ContextMessage.AttachmentBlock("att_evidence"),
                        new ContextMessage.ToolCallBlock("call_pending", "save_note", "{}")), 0);

        assertEquals(List.of("att_evidence"), AttachmentRequestBudget.collectReferences(
                List.of(pendingApproval), 0, List.of(), Optional.empty(), List.of()));
    }

    /** 构造只含附件块的历史消息，使附件引用计数不受正文或估算 Token 影响。 */
    private static ContextMessage message(String id, long ordinal, String... attachmentIds) {
        List<ContextMessage.Block> blocks = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            blocks.add(new ContextMessage.AttachmentBlock(attachmentId));
        }
        return new ContextMessage(id, IDENTITY.turnId(), ordinal, ContextMessage.Role.USER, blocks, 0);
    }

    /** 只提供计费需要的不可变元数据，不构造或读取文件正文。 */
    private static AttachmentMetadata metadata(String id, long sizeBytes) {
        return new AttachmentMetadata(id, IDENTITY.threadId(), "evidence.png", "image/png", sizeBytes,
                "0".repeat(64), Instant.EPOCH, true);
    }
}
