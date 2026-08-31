// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 模型摘要生成回归集，锁定结构化提示版本、输入输出预算与失败关闭行为。 */
final class ModelSummaryGeneratorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), ZoneOffset.UTC);
    private static final SummaryModel.TurnBinding BINDING = new SummaryModel.TurnBinding(
            "thread-1", configuration(), Instant.parse("2026-08-25T12:05:00Z"), CancellationToken.none());

    /** 锁定生成器委托带版本的结构化提示并回传 usage，避免摘要协议悄然漂移。 */
    @Test
    void delegatesVersionedStructuredPromptAndUsage() {
        AtomicReference<SummaryModel.SummaryPrompt> observed = new AtomicReference<>();
        CheckpointUsage usage = new CheckpointUsage(90, 20, 110, 5, 0);
        SummaryDocument document = document("structured");
        ModelSummaryGenerator generator = new ModelSummaryGenerator(model(prompt -> {
            observed.set(prompt);
            return new SummaryGenerator.SummaryResult(document, usage);
        }), BINDING, CLOCK, new ModelSummaryGenerator.Limits(10, 1_000, 500));

        SummaryGenerator.SummaryResult result = generator.generate(request(message(100)));

        assertEquals(document, result.document());
        assertEquals(usage, result.usage());
        assertEquals(ModelSummaryGenerator.PROMPT_VERSION, observed.get().promptVersion());
        assertEquals("strategy-test", observed.get().strategyVersion());
        assertEquals(500, observed.get().maxOutputTokens());
        assertEquals(List.of(1L), observed.get().evictedMessages().stream()
                .map(ContextMessage::ordinal).toList());
    }

    /** 锁定超大输入在调用模型前失败，避免发送已知超限的付费请求。 */
    @Test
    void oversizedInputFailsBeforeModelCall() {
        AtomicBoolean invoked = new AtomicBoolean();
        ModelSummaryGenerator generator = new ModelSummaryGenerator(model(prompt -> {
            invoked.set(true);
            return new SummaryGenerator.SummaryResult(document("unexpected"), CheckpointUsage.none());
        }), BINDING, CLOCK, new ModelSummaryGenerator.Limits(1, 50, 500));

        ContextException failure = assertThrows(ContextException.class,
                () -> generator.generate(request(message(100))));

        assertEquals(ContextException.Code.SUMMARY_FAILURE, failure.code());
        assertFalse(invoked.get());
    }

    /** 首次空摘要只允许一次带违规码的修复，并接受满足证据覆盖的修复结果。 */
    @Test
    void repairsInvalidDocumentOnce() {
        AtomicInteger invocations = new AtomicInteger();
        AtomicReference<SummaryModel.SummaryPrompt> repair = new AtomicReference<>();
        ModelSummaryGenerator generator = new ModelSummaryGenerator(model(prompt -> {
            if (invocations.incrementAndGet() == 1) {
                return new SummaryGenerator.SummaryResult(SummaryDocument.empty(), CheckpointUsage.none());
            }
            repair.set(prompt);
            return new SummaryGenerator.SummaryResult(document("repaired"), CheckpointUsage.none());
        }),
                BINDING, CLOCK, new ModelSummaryGenerator.Limits(10, 1_000, 500));

        SummaryGenerator.SummaryResult result = generator.generate(request(message(100)));

        assertEquals("repaired", result.document().goals().getFirst().text());
        assertEquals(2, invocations.get());
        assertEquals(List.of("EMPTY_DOCUMENT", "UNCOVERED_CRITICAL_EVIDENCE"), repair.get().violations());
    }

    /** 修复仍超限时回退确定性 evidence ledger，防止预算违约摘要进入检查点。 */
    @Test
    void fallsBackToEvidenceLedgerAfterFailedRepair() {
        AtomicInteger invocations = new AtomicInteger();
        ModelSummaryGenerator generator = new ModelSummaryGenerator(model(prompt -> {
            invocations.incrementAndGet();
            return
                new SummaryGenerator.SummaryResult(document("bounded"),
                        new CheckpointUsage(10, 501, 511, 0, 0));
        }),
                BINDING, CLOCK, new ModelSummaryGenerator.Limits(10, 1_000, 500));

        SummaryGenerator.SummaryResult result = generator.generate(request(message(100)));

        assertEquals(2, invocations.get());
        assertEquals(new CheckpointUsage(20, 1_002, 1_022, 0, 0), result.usage());
        assertEquals(List.of("user: " + "source".repeat(10)), result.document().criticalFacts().stream()
                .map(SummaryDocument.Fact::text).toList());
    }

    /** Provider 精确计量只能在完整 Turn 边界切块，并让每块输出预算服从 Profile 最大输出。 */
    @Test
    void chunksAtCompleteTurnBoundariesUsingExactProviderCounts() {
        List<SummaryModel.SummaryPrompt> sent = new java.util.ArrayList<>();
        SummaryModel exact = new SummaryModel() {
            /** 两条同 Turn 消息可容纳，加入下一 Turn 后精确报告超窗。 */
            @Override
            public ModelPort.InputTokenCount countInputTokens(SummaryPrompt prompt) {
                long tokens = prompt.evictedMessages().size() <= 2 ? 5_000 : 30_000;
                return new ModelPort.InputTokenCount(tokens, "1".repeat(64));
            }

            /** 返回包含旧摘要和本块全部来源的完整替换文档，模拟滚动摘要。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                sent.add(prompt);
                List<SummaryDocument.Fact> facts = new java.util.ArrayList<>();
                prompt.previousSummary().ifPresent(value -> facts.addAll(value.allFacts()));
                prompt.evictedMessages().forEach(value ->
                        facts.add(new SummaryDocument.Fact("source-" + value.ordinal(), value.ordinal())));
                SummaryDocument value = new SummaryDocument(facts, List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
                return new SummaryGenerator.SummaryResult(value,
                        new CheckpointUsage(5_000, 20, 5_020, 0, 0));
            }
        };
        ModelSummaryGenerator generator = new ModelSummaryGenerator(exact, BINDING, CLOCK,
                ModelSummaryGenerator.Limits.defaults());
        List<ContextMessage> messages = List.of(
                ContextMessage.text("message-1", "turn-1", 1, ContextMessage.Role.USER, "first", 1),
                ContextMessage.text("message-2", "turn-1", 2, ContextMessage.Role.ASSISTANT, "paired", 1),
                ContextMessage.text("message-3", "turn-2", 3, ContextMessage.Role.USER, "second", 1));
        SummaryGenerator.SummaryRequest request = new SummaryGenerator.SummaryRequest(
                "thread-1", Optional.empty(), messages, Optional.empty(), "strategy-test",
                io.github.kongweiguang.ja.conversation.domain.ContextBudget.capabilities(
                        32_000, 3_000, true));

        SummaryGenerator.SummaryResult result = generator.generate(request);

        assertEquals(2, sent.size());
        assertEquals(List.of("turn-1", "turn-1"), sent.getFirst().evictedMessages().stream()
                .map(ContextMessage::turnId).toList());
        assertEquals(List.of("turn-2"), sent.getLast().evictedMessages().stream()
                .map(ContextMessage::turnId).toList());
        assertEquals(3_000, sent.getFirst().maxOutputTokens());
        assertEquals(List.of(1L, 2L, 3L), result.document().allFacts().stream()
                .map(SummaryDocument.Fact::sourceOrdinal).toList());
        assertEquals(new CheckpointUsage(10_000, 40, 10_040, 0, 0), result.usage());
    }

    /** 连续十轮使用全量替换与显式退休，证明旧事实不会随 Checkpoint 次数单调膨胀。 */
    @Test
    void tenRollingSummariesRetireSupersededFactsWithoutGrowth() {
        SummaryModel replacing = new SummaryModel() {
            /** 固定精确计量，让用例只验证滚动摘要与退休约束。 */
            @Override
            public ModelPort.InputTokenCount countInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenCount(100, "2".repeat(64));
            }

            /** 每轮仅保留最新事实，并用 SUPERSEDED 精确退休上一轮事实。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                ContextMessage current = prompt.evictedMessages().getLast();
                List<SummaryDocument.Retirement> retirements = prompt.previousSummary().stream()
                        .flatMap(value -> value.allFacts().stream())
                        .map(value -> new SummaryDocument.Retirement(value.text(), value.sourceOrdinal(),
                                SummaryDocument.Status.SUPERSEDED))
                        .toList();
                SummaryDocument replacement = new SummaryDocument(
                        List.of(new SummaryDocument.Fact("goal-" + current.ordinal(), current.ordinal())),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), retirements);
                return new SummaryGenerator.SummaryResult(replacement,
                        new CheckpointUsage(100, 10, 110, 0, 0));
            }
        };
        ModelSummaryGenerator generator = new ModelSummaryGenerator(replacing, BINDING, CLOCK,
                ModelSummaryGenerator.Limits.defaults());
        Optional<SummaryDocument> previous = Optional.empty();

        for (long round = 1; round <= 10; round++) {
            ContextMessage current = ContextMessage.text("message-" + round, "turn-" + round, round,
                    ContextMessage.Role.USER, "source-" + round, 10);
            SummaryGenerator.SummaryResult result = generator.generate(new SummaryGenerator.SummaryRequest(
                    "thread-1", previous, List.of(current), Optional.empty(), "strategy-test",
                    ContextBudget.capabilities(32_768, 4_096, true)));

            assertEquals(1, result.document().allFacts().size());
            assertEquals(round, result.document().allFacts().getFirst().sourceOrdinal());
            assertEquals(round == 1 ? 0 : 1, result.document().retirements().size());
            previous = Optional.of(result.document());
        }
    }

    /** 构造最小摘要请求，仅改变待测上下文消息以隔离生成器边界。 */
    private static SummaryGenerator.SummaryRequest request(ContextMessage message) {
        return new SummaryGenerator.SummaryRequest("thread-1", Optional.empty(), List.of(message),
                Optional.empty(), "strategy-test", ContextBudget.capabilities(36_000, 2_000, true));
    }

    /** 构造具有显式 token 估算的消息，用于精确触发输入预算分支。 */
    private static ContextMessage message(int estimatedTokens) {
        return ContextMessage.text("message-1", "turn-1", 1, ContextMessage.Role.USER,
                "source".repeat(Math.max(1, estimatedTokens / 10)), estimatedTokens);
    }

    /** 构造字段完整的摘要文档，使失败只来自待测字段而非夹具缺失。 */
    private static SummaryDocument document(String goal) {
        return new SummaryDocument(List.of(new SummaryDocument.Fact(goal, 1)), List.of(), List.of(),
                List.of(new SummaryDocument.Fact("progress", 1)), List.of(), List.of(), List.of(),
                List.of(new SummaryDocument.Fact("critical", 1)), List.of(), List.of(), List.of());
    }

    /** 提供无真实凭据的固定模型配置，确保测试不会触发外部 Provider。 */
    private static ModelPort.ModelConfiguration configuration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test", ModelPort.Provider.OPENAI,
                ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("http://localhost:60842"),
                "test-secret", Duration.ofSeconds(5), Duration.ofSeconds(30),
                java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
    }

    /** 提供可控摘要结果与稳定官方计量替身，使测试不再依赖生产端口的失败默认值。 */
    private static SummaryModel model(
            Function<SummaryModel.SummaryPrompt, SummaryGenerator.SummaryResult> delegate) {
        return new SummaryModel() {
            /** 返回足以验证窗口分支的固定 Provider 计量与合法指纹。 */
            @Override
            public ModelPort.InputTokenCount countInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenCount(100, "0".repeat(64));
            }

            /** 将结构化结果选择留给各测试用例，计量行为保持统一。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                return delegate.apply(prompt);
            }
        };
    }
}
