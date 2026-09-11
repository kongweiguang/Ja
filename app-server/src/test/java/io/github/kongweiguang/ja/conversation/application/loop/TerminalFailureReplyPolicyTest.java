// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证失败收口正文稳定、有界且不吸收运行时异常文本。 */
final class TerminalFailureReplyPolicyTest {
    private final TerminalFailureReplyPolicy policy = new TerminalFailureReplyPolicy();

    /** 每个公开终态错误类别都必须返回可读且长度受控的固定回复。 */
    @Test
    void coversKnownTerminalFailureCategories() {
        List<String> codes = List.of(
                "MODEL_UNAVAILABLE", "MODEL_PROTOCOL_ERROR", "BUDGET_EXCEEDED",
                "REQUEST_DEADLINE_EXCEEDED", "CONTEXT_LIMIT", "SUMMARY_FAILURE", "CONFLICT", "INVALID_STATE",
                "THREAD_BUSY", "APPROVAL_EXPIRED", "MCP_SERVER_UNAVAILABLE", "INTERNAL_ERROR");

        for (String code : codes) {
            String reply = policy.replyFor(code);
            assertFalse(reply.isBlank(), code);
            assertTrue(reply.length() <= 512, code);
            assertTrue(reply.contains("本轮未能完成"), code);
        }
    }

    /** 摘要失败必须使用专门的可恢复文案，不能退回会误导用户的通用内部错误提示。 */
    @Test
    void summaryFailureUsesDedicatedRecoveryReply() {
        String summaryFailure = policy.replyFor("SUMMARY_FAILURE");
        String fallback = policy.replyFor("INTERNAL_ERROR");

        assertTrue(summaryFailure.contains("对话摘要生成失败"));
        assertTrue(summaryFailure.contains("重新编辑原问题"));
        assertFalse(summaryFailure.equals(fallback));
    }

    /** 未知或缺失错误码必须安全降级到同一固定正文，不能把动态错误内容反射给用户。 */
    @Test
    void unknownCodesUseStableSafeFallback() {
        String fallback = policy.replyFor("INTERNAL_ERROR");

        assertEquals(fallback, policy.replyFor(null));
        assertEquals(fallback, policy.replyFor(""));
        assertEquals(fallback, policy.replyFor("UNKNOWN_/secret/path_provider_payload"));
        assertFalse(fallback.contains("secret"));
        assertFalse(fallback.contains("provider_payload"));
    }

    /** 无 Provider intent 的应急路径必须得到稳定消息 ID，避免恢复或重复结算制造多条失败回复。 */
    @Test
    void derivesStableMessageIdentityWithoutProviderIntent() {
        io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.Ready execution =
                new io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.Ready(
                        new io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.Common(
                                0, 0, 1, null, List.of(),
                                java.time.Instant.parse("2026-09-01T00:01:00Z"),
                                io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER),
                        io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.Next.ASSISTANT,
                        null);

        String first = policy.messageIdFor("turn_failure", execution);
        assertEquals(first, policy.messageIdFor("turn_failure", execution));
        assertTrue(first.matches("item_failure_[0-9a-f]{64}"));
    }
}
