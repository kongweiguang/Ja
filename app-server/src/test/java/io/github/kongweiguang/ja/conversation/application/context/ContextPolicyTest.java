// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.compaction.ToolOutputProjector;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上下文预算策略回归集，锁定投影上限、压缩触发与 Tool 消息配对不变量。 */
final class ContextPolicyTest {
    private static final ContextTokenMeter ESTIMATED_METER = ContextPolicyTest::measureEstimatedTokens;

    /** 测试计量只复现 fixture 声明的权重，生产计量仍由实际 Provider codec 独占。 */
    private static ContextTokenMeter.Measurement measureEstimatedTokens(
            List<ContextMessage> messages, SummaryDocument summary,
            Optional<ModelContinuation> continuation, boolean localCompaction) {
        long tokens = Math.max(1L, (summary.toPromptText().length() + 3L) / 4L);
        for (ContextMessage message : messages) {
            tokens = Math.addExact(tokens, message.estimatedTokens());
        }
        return new ContextTokenMeter.Measurement(tokens, "0".repeat(64));
    }

    /** 为权威计量边界构造固定值，测试 Provider observation 与当前请求计量不再混为一层。 */
    private static ContextTokenMeter fixedMeter(long tokens) {
        return (messages, summary, continuation, localCompaction) ->
                new ContextTokenMeter.Measurement(tokens, "1".repeat(64));
    }

    /** 锁定 Tool 输出投影有界且保留截断元数据，避免预算控制丢失可追溯性。 */
    @Test
    void toolOutputProjectionIsBoundedAndMetadataComplete() {
        ToolOutputProjector projector = new ToolOutputProjector(new ToolProjectionLimits(3, 3));
        ContextMessage.ToolOutput output = ContextMessage.ToolOutput.full(
                "HEAD-middle-TAIL", "artifact://tool/1", 17, "command failed");

        ToolOutputProjector.Projection projection = projector.project(output);

        assertTrue(projection.truncated());
        assertEquals("HEA", projection.head());
        assertEquals("AIL", projection.tail());
        assertTrue(projection.promptText().contains("HEA"));
        assertTrue(projection.promptText().contains("AIL"));
        assertTrue(projection.promptText().contains("artifact://tool/1"));
        assertTrue(projection.promptText().contains("exit_code=17"));
        assertTrue(projection.promptText().contains("error=command failed"));
        assertFalse(projection.promptText().contains("middle"));
    }

    /** 锁定当前 Provider 能力直接决定发送上限，并随窗口动态调整最近消息尾部。 */
    @Test
    void providerCapabilitiesSetCeilingAndDynamicTailTarget() {
        ContextBudget budget = ContextBudget.capabilities(40_000, 1_000, true);

        assertEquals(39_000, budget.sendCeilingTokens());
        assertEquals(9_750, budget.recentTailTokens());
    }

    /** 覆盖主流 32K、200K、1M 与 4M 窗口，锁定显式 reserve、压缩目标和尾部预算的单调有界关系。 */
    @Test
    void productionWindowMatrixKeepsCompactionHeadroomBounded() {
        long[] windows = {32_768L, 200_000L, 1_000_000L, 4_000_000L};
        long[] outputs = {4_096L, 8_192L, 32_768L, 65_536L};
        for (int index = 0; index < windows.length; index++) {
            ContextBudget budget = ContextBudget.capabilities(windows[index], outputs[index], true);
            long ceiling = windows[index] - outputs[index];
            long reserve = Math.min(16_384L, ceiling * 2L / 5L);

            assertEquals(ceiling, budget.sendCeilingTokens());
            assertEquals(reserve, budget.compactionReserveTokens());
            assertEquals(Math.min(ceiling, windows[index] - reserve), budget.automaticCompactionThreshold());
            assertEquals(ceiling * 3L / 5L, budget.compactedTargetTokens());
            assertTrue(budget.compactedTargetTokens() <= budget.automaticCompactionThreshold());
            assertTrue(budget.recentTailTokens() >= 8_000L);
            assertTrue(budget.recentTailTokens() <= 20_000L);
            assertTrue(budget.recentTailTokens() <= ceiling);
        }
    }

    /** 固定真实 256K 配置与极小窗口边界，防止输出预算和 reserve 再次双扣。 */
    @Test
    void reserveSharesOutputSpaceAndKeepsSmallWindowsSendable() {
        assertEquals(239_616, ContextBudget.capabilities(256_000, 8_192, true)
                .automaticCompactionThreshold());
        assertEquals(90, ContextBudget.capabilities(100, 10, true).sendCeilingTokens());
        assertEquals(64, ContextBudget.capabilities(100, 10, true).automaticCompactionThreshold());
        assertEquals(1, ContextBudget.capabilities(2, 1, true).automaticCompactionThreshold());
    }

    /** 输出预算超过窗口时立即拒绝，避免压缩策略消费不可能的 Provider 能力。 */
    @Test
    void rejectsImpossibleProviderBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> ContextBudget.capabilities(1_000, 1_001, true));
    }

    /** 锁定 Provider 上下文溢出会强制压缩，避免相同超限请求被原样重发。 */
    @Test
    void providerOverflowObservationForcesCompaction() {
        ContextBudget budget = ContextBudget.capabilities(10_000, 0, true);
        ContextMessage message = ContextMessage.text("m-1", "turn-1", 1, ContextMessage.Role.USER,
                "small", 1);

        ContextPolicy.Plan plan = new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1",
                List.of(message), SummaryDocument.empty(), 0, 0, 0, Optional.empty(), budget, false, Optional.empty(),
                new ToolProjectionLimits(64, 64)), fixedMeter(12_000));

        assertFalse(plan.fullPromptFits());
        assertTrue(plan.requiresCompaction());
    }

    /** 锁定最近消息裁剪不会拆散 Tool 调用与结果，避免构造非法 Provider 历史。 */
    @Test
    void recentTailPreservesToolCallResultPair() {
        ContextPolicy policy = new ContextPolicy();
        String oldText = "old-".repeat(5_000);
        String arguments = "x".repeat(16_000);
        String resultText = "result-".repeat(2_000);
        List<ContextMessage> source = List.of(
                ContextMessage.text("m-1", "turn-1", 1, ContextMessage.Role.USER, oldText, 3_000),
                new ContextMessage("m-2", "turn-2", 2, ContextMessage.Role.ASSISTANT,
                        List.of(new ContextMessage.ToolCallBlock("call-1", "read", arguments)), 4_000),
                new ContextMessage("m-3", "turn-2", 3, ContextMessage.Role.TOOL,
                        List.of(new ContextMessage.ToolResultBlock("call-1", "read",
                                ContextMessage.ToolOutput.full(resultText, "artifact://1", 0, null))), 3_000),
                ContextMessage.text("m-4", "turn-3", 4, ContextMessage.Role.USER, "latest", 1_000));
        ContextBudget budget = ContextBudget.capabilities(10_000, 500, true);

        ContextPolicy.Plan plan = policy.plan(new ContextPolicy.PlanningInput("thread-1", source,
                SummaryDocument.empty(), 0, 0, 0, Optional.empty(), budget, false,
                Optional.<ModelContinuation>empty(),
                new ToolProjectionLimits(64, 64)), ESTIMATED_METER);

        boolean retainedCall = plan.retained().stream().anyMatch(ContextMessage::hasToolCall);
        boolean retainedResult = plan.retained().stream().anyMatch(ContextMessage::hasToolResult);
        boolean evictedCall = plan.evicted().stream().anyMatch(ContextMessage::hasToolCall);
        boolean evictedResult = plan.evicted().stream().anyMatch(ContextMessage::hasToolResult);
        assertEquals(retainedCall, retainedResult);
        assertEquals(evictedCall, evictedResult);
        assertTrue(plan.retained().stream().anyMatch(message -> message.messageId().equals("m-4")));
    }

    /** 锁定单个超大 Turn 生成可恢复的分片证据，而不是静默丢弃内容。 */
    @Test
    void oversizedSingleTurnProducesSplitEvidence() {
        String huge = "0123456789".repeat(20_000);
        ContextMessage message = ContextMessage.text("m-1", "turn-1", 1, ContextMessage.Role.USER,
                huge, 50_000);
        ContextBudget budget = ContextBudget.capabilities(10_000, 500, true);

        ContextPolicy.Plan plan = new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1",
                List.of(message), SummaryDocument.empty(), 0, 0, 0, Optional.empty(), budget, false,
                Optional.<ModelContinuation>empty(),
                new ToolProjectionLimits(64, 64)), ESTIMATED_METER);

        assertTrue(plan.splitTurn().isPresent());
        assertEquals(1, plan.evicted().size());
        assertEquals(1, plan.retained().size());
        assertTrue(plan.evicted().getFirst().ordinal() == plan.retained().getFirst().ordinal());
        assertTrue(plan.evicted().getFirst().estimatedTokens() > plan.retained().getFirst().estimatedTokens());
    }

    /** 锁定最大来源标识生成的分片 ID 仍满足长度上限，避免持久化拒绝。 */
    @Test
    void splitIdentifiersRemainBoundedAtMaximumSourceLength() {
        String messageId = "m".repeat(256);
        ContextMessage message = ContextMessage.text(messageId, "turn-1", 1, ContextMessage.Role.USER,
                "0123456789".repeat(20_000), 50_000);
        ContextBudget budget = ContextBudget.capabilities(10_000, 500, true);

        ContextPolicy.Plan first = new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1",
                List.of(message), SummaryDocument.empty(), 0, 0, 0, Optional.empty(), budget, false,
                Optional.empty(), new ToolProjectionLimits(64, 64)), ESTIMATED_METER);
        ContextPolicy.Plan second = new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1",
                List.of(message), SummaryDocument.empty(), 0, 0, 0, Optional.empty(), budget, false,
                Optional.empty(), new ToolProjectionLimits(64, 64)), ESTIMATED_METER);

        String prefixId = first.evicted().getFirst().messageId();
        String suffixId = first.retained().getFirst().messageId();
        assertTrue(prefixId.length() <= 256);
        assertTrue(suffixId.length() <= 256);
        assertFalse(prefixId.equals(suffixId));
        assertEquals(prefixId, second.evicted().getFirst().messageId());
        assertEquals(suffixId, second.retained().getFirst().messageId());
    }

    /** 锁定孤立 Tool 结果被判为非法状态，防止无调用来源的结果进入上下文。 */
    @Test
    void orphanToolResultIsInvalidState() {
        ContextMessage orphan = new ContextMessage("m-1", "turn-1", 1, ContextMessage.Role.TOOL,
                List.of(new ContextMessage.ToolResultBlock("missing", "read",
                        ContextMessage.ToolOutput.full("result", null, 1, null))), 2);

        ContextException failure = assertThrows(ContextException.class,
                () -> new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1", List.of(orphan),
                        SummaryDocument.empty(), 0, 0, 0, Optional.empty(),
                        ContextBudget.capabilities(10_000, 100, true),
                        false, Optional.<ModelContinuation>empty(), new ToolProjectionLimits(64, 64)),
                        ESTIMATED_METER));

        assertEquals(ContextException.Code.INVALID_STATE, failure.code());
    }

    /** 锁定缺少结果的 Tool 调用被判为非法状态，避免发送不完整调用对。 */
    @Test
    void toolCallWithoutResultIsInvalidState() {
        ContextMessage unresolved = new ContextMessage("m-1", "turn-1", 1, ContextMessage.Role.ASSISTANT,
                List.of(new ContextMessage.ToolCallBlock("call-1", "read", "{}")), 2);

        ContextException failure = assertThrows(ContextException.class,
                () -> new ContextPolicy().plan(new ContextPolicy.PlanningInput("thread-1", List.of(unresolved),
                        SummaryDocument.empty(), 0, 0, 0, Optional.empty(),
                        ContextBudget.capabilities(10_000, 100, true),
                        false, Optional.<ModelContinuation>empty(), new ToolProjectionLimits(64, 64)),
                        ESTIMATED_METER));

        assertEquals(ContextException.Code.INVALID_STATE, failure.code());
    }

    /** 锁定溢出恢复投影只能收缩不能增长，避免重试扩大原有超限负载。 */
    @Test
    void overflowProjectionShrinkNeverGrows() {
        assertEquals(new ToolProjectionLimits(1, 0),
                new ToolProjectionLimits(1, 0).shrinkOnce());
        assertEquals(new ToolProjectionLimits(0, 1),
                new ToolProjectionLimits(0, 1).shrinkOnce());
        assertEquals(new ToolProjectionLimits(2, 1),
                new ToolProjectionLimits(4, 3).shrinkOnce());
    }
}
