// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Summary v2 领域回归集，锁定事实来源、退休审计和确定性降级证据。 */
final class SummaryDocumentTest {
    /** 锁定事实分区和来源 ordinal 原样进入提示，避免恢复文本失去可追溯性。 */
    @Test
    void rendersPartitionedFactsWithSourceOrdinals() {
        SummaryDocument document = document();

        String prompt = document.toPromptText();

        assertTrue(prompt.contains("Goals:\n- [source:1] ship v2"));
        assertTrue(prompt.contains("Current Progress:\n- [source:2] migrate tests"));
        assertEquals(List.of(1L, 2L), document.allFacts().stream()
                .map(SummaryDocument.Fact::sourceOrdinal).toList());
    }

    /** 退休项仅供验证和审计使用，不得作为已经有效的事实重新进入普通模型提示。 */
    @Test
    void excludesRetirementsFromPromptText() {
        SummaryDocument retired = new SummaryDocument(document().goals(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new SummaryDocument.Retirement("obsolete blocker", 3,
                        SummaryDocument.Status.RESOLVED)));

        assertFalse(retired.toPromptText().contains("obsolete blocker"));
    }

    /** 确定性 ledger 保留旧关键事实，只补用户原文和 Tool 错误，不扩散模型普通文本。 */
    @Test
    void evidenceLedgerKeepsCriticalEvidenceOnly() {
        SummaryDocument previous = new SummaryDocument(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(new SummaryDocument.Fact("existing fact", 1)),
                List.of(), List.of(), List.of());
        ContextMessage user = ContextMessage.text("user-2", "turn-2", 2,
                ContextMessage.Role.USER, "requested behavior", 3);
        ContextMessage assistant = ContextMessage.text("assistant-3", "turn-3", 3,
                ContextMessage.Role.ASSISTANT, "speculative reply", 3);
        ContextMessage toolError = new ContextMessage("tool-4", "turn-4", 4, ContextMessage.Role.TOOL,
                List.of(new ContextMessage.ToolResultBlock("call-4", "read",
                        ContextMessage.ToolOutput.full("", "artifact://tool/4", 1, "read failed"))), 3);

        SummaryDocument ledger = SummaryDocument.evidenceLedger(previous,
                List.of(user, assistant, toolError));

        assertEquals(List.of("existing fact", "user: requested behavior",
                        "Tool read failed; artifact=artifact://tool/4"),
                ledger.criticalFacts().stream().map(SummaryDocument.Fact::text).toList());
        assertEquals(List.of(1L, 2L, 4L), ledger.criticalFacts().stream()
                .map(SummaryDocument.Fact::sourceOrdinal).toList());
    }

    /** 同分区重复事实失败关闭，避免模型重试使滚动摘要无界膨胀。 */
    @Test
    void rejectsDuplicateFacts() {
        SummaryDocument.Fact duplicate = new SummaryDocument.Fact("same", 1);

        assertThrows(IllegalArgumentException.class, () -> new SummaryDocument(
                List.of(duplicate, duplicate), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    /** 构造两个分区的最小完整文档，避免测试依赖旧版摘要字段。 */
    private static SummaryDocument document() {
        return new SummaryDocument(List.of(new SummaryDocument.Fact("ship v2", 1)), List.of(), List.of(),
                List.of(new SummaryDocument.Fact("migrate tests", 2)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
