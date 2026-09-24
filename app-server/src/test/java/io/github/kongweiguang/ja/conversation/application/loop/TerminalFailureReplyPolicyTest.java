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
                "MODEL_UNAVAILABLE", "MODEL_PROTOCOL_ERROR", "MODEL_UPSTREAM_REJECTED",
                "MODEL_STREAM_INVALID", "MODEL_IDLE_TIMEOUT", "BUDGET_EXCEEDED",
                "REQUEST_DEADLINE_EXCEEDED", "CONTEXT_LIMIT", "SUMMARY_FAILURE", "CONFLICT", "INVALID_STATE",
                "THREAD_BUSY", "APPROVAL_EXPIRED", "MCP_SERVER_UNAVAILABLE", "INTERNAL_ERROR");

        for (String code : codes) {
            String reply = policy.replyFor(code);
            assertFalse(reply.isBlank(), code);
            assertTrue(reply.length() <= 512, code);
            assertTrue(reply.endsWith("。"), code);
        }
    }

    /** 摘要失败必须使用专门的可恢复文案，不能退回会误导用户的通用内部错误提示。 */
    @Test
    void summaryFailureUsesDedicatedRecoveryReply() {
        String summaryFailure = policy.replyFor("SUMMARY_FAILURE");
        String fallback = policy.replyFor("INTERNAL_ERROR");

        assertTrue(summaryFailure.contains("对话摘要生成失败"));
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

    /** 不依赖 Provider intent 的失败收口使用独立稳定 ID，避免与模型回答碰撞。 */
    @Test
    void derivesStableFailureMessageIdentityFromTurn() {
        String first = policy.failureMessageIdFor("turn_failure");
        assertEquals(first, policy.failureMessageIdFor("turn_failure"));
        assertTrue(first.matches("item_failure_[0-9a-f]{64}"));
    }

    /** 半截正文使用不同 namespace，避免与固定失败回复发生历史 item identity 冲突。 */
    @Test
    void derivesStablePartialMessageIdentity() {
        String first = policy.partialMessageIdFor("turn_failure", 2);
        assertEquals(first, policy.partialMessageIdFor("turn_failure", 2));
        assertTrue(first.matches("item_partial_[0-9a-f]{64}"));
        assertFalse(first.equals(policy.partialMessageIdFor("turn_failure", 3)));
    }

    /** 同轮不同 Provider request 的失败草稿都可审计且身份互异，重放同一 request 保持幂等。 */
    @Test
    void derivesDistinctPartialAuditIdentityPerProviderRequest() {
        String first = policy.partialMessageIdForRequest("turn_failure", "request_first");
        String second = policy.partialMessageIdForRequest("turn_failure", "request_second");

        assertEquals(first, policy.partialMessageIdForRequest("turn_failure", "request_first"));
        assertTrue(first.matches("item_partial_[0-9a-f]{64}"));
        assertTrue(second.matches("item_partial_[0-9a-f]{64}"));
        assertFalse(first.equals(second));
    }
}
