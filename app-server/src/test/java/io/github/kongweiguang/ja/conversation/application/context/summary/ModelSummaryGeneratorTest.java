// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context.summary;

import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.ContextMessage;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                long tokens = prompt.evictedMessages().size() <= 2 ? 5_000 : 30_000;
                return new ModelPort.InputTokenEstimate(tokens, "1".repeat(64));
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

    /** candidate、repair 与下一 chunk 的每次真实请求都必须使用刚打开的运行环境并及时释放。 */
    @Test
    void reopensRuntimeBeforeEveryCandidateRepairAndChunkProviderCall() {
        AtomicInteger runtimeSequence = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        List<String> invokedModels = new java.util.ArrayList<>();
        List<String> pendingModels = new java.util.ArrayList<>();
        SummaryModel.Factory models = binding -> new SummaryModel() {
            /** 精确切成两个完整 Turn，且不同 runtime 不改变分块决定。 */
            @Override public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                long tokens = prompt.evictedMessages().size() <= 2 ? 5_000 : 30_000;
                return new ModelPort.InputTokenEstimate(tokens, "7".repeat(64));
            }

            /** 首次 candidate 触发 repair，其余调用返回覆盖当前块的合法全量摘要。 */
            @Override public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                invokedModels.add(binding.configuration().modelId());
                if (invokedModels.size() == 1) {
                    return new SummaryGenerator.SummaryResult(
                            SummaryDocument.empty(), new CheckpointUsage(100, 10, 110, 0, 0));
                }
                List<SummaryDocument.Fact> facts = new java.util.ArrayList<>();
                prompt.previousSummary().ifPresent(value -> facts.addAll(value.allFacts()));
                prompt.evictedMessages().forEach(value ->
                        facts.add(new SummaryDocument.Fact("source-" + value.ordinal(), value.ordinal())));
                return new SummaryGenerator.SummaryResult(new SummaryDocument(
                        facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                        new CheckpointUsage(100, 10, 110, 0, 0));
            }
        };
        ModelSummaryGenerator.RequestRuntimeFactory runtimes = () -> {
            int sequence = runtimeSequence.incrementAndGet();
            String modelId = "model_runtime_" + sequence;
            String generation = "cfg_runtime_" + sequence;
            ModelPort.ModelConfiguration configuration = configuration(modelId, generation);
            return new ModelSummaryGenerator.RequestRuntime(
                    new SummaryModel.TurnBinding("thread-1", configuration,
                            Instant.parse("2026-08-25T12:05:00Z"), CancellationToken.none()),
                    Optional.of(summaryProfile(modelId, generation, sequence)), releases::incrementAndGet);
        };
        List<ContextMessage> messages = List.of(
                ContextMessage.text("message-1", "turn-1", 1, ContextMessage.Role.USER, "first", 1),
                ContextMessage.text("message-2", "turn-1", 2, ContextMessage.Role.ASSISTANT, "paired", 1),
                ContextMessage.text("message-3", "turn-2", 3, ContextMessage.Role.USER, "second", 1));
        SummaryGenerator.SummaryRequest request = new SummaryGenerator.SummaryRequest(
                "thread-1", Optional.empty(), messages, Optional.empty(), "strategy-test",
                ContextBudget.capabilities(32_000, 3_000, true));

        SummaryGenerator.SummaryResult result = new ModelSummaryGenerator(
                models, runtimes, CLOCK, ModelSummaryGenerator.Limits.defaults(),
                recordingOperation(pendingModels)).generate(request);

        assertEquals(3, invokedModels.size());
        assertEquals(invokedModels, pendingModels);
        assertEquals(runtimeSequence.get(), releases.get());
        assertTrue(runtimeSequence.get() > invokedModels.size(),
                "本地计量与真实发送各自持有独立短租约");
        assertEquals(List.of(1L, 2L, 3L), result.document().allFacts().stream()
                .map(SummaryDocument.Fact::sourceOrdinal).toList());
    }

    /** 已接纳首块后强杀，Resume 必须从持久 nextTurn 继续且不得再次调用首块 Provider。 */
    @Test
    void resumesMultiChunkSummaryFromPersistedAcceptedCursor() {
        AtomicInteger calls = new AtomicInteger();
        SummaryModel exact = new SummaryModel() {
            /** 两条同 Turn 可容纳，加入第二个 Turn 后迫使稳定分块。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(
                        prompt.evictedMessages().size() <= 2 ? 5_000 : 30_000, "6".repeat(64));
            }

            /** 返回包含旧摘要与当前块的完整替换文档。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                calls.incrementAndGet();
                List<SummaryDocument.Fact> facts = new java.util.ArrayList<>();
                prompt.previousSummary().ifPresent(value -> facts.addAll(value.allFacts()));
                prompt.evictedMessages().forEach(value ->
                        facts.add(new SummaryDocument.Fact("source-" + value.ordinal(), value.ordinal())));
                return new SummaryGenerator.SummaryResult(new SummaryDocument(
                        facts, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of()),
                        new CheckpointUsage(5_000, 20, 5_020, 0, 0));
            }
        };
        List<ContextMessage> messages = List.of(
                ContextMessage.text("message-1", "turn-1", 1, ContextMessage.Role.USER, "first", 1),
                ContextMessage.text("message-2", "turn-1", 2, ContextMessage.Role.ASSISTANT, "paired", 1),
                ContextMessage.text("message-3", "turn-2", 3, ContextMessage.Role.USER, "second", 1));
        SummaryGenerator.SummaryRequest request = new SummaryGenerator.SummaryRequest(
                "thread-1", Optional.empty(), messages, Optional.empty(), "strategy-test",
                ContextBudget.capabilities(32_000, 3_000, true));
        AtomicReference<ModelSummaryGenerator.SummaryOperation.Progress> persisted = new AtomicReference<>();
        ModelSummaryGenerator.SummaryOperation crashing = operation(
                persisted, ModelSummaryGenerator.SummaryOperation.Stage.CANDIDATE);

        assertThrows(IllegalStateException.class, () -> new ModelSummaryGenerator(
                exact, BINDING, CLOCK, ModelSummaryGenerator.Limits.defaults(), crashing).generate(request));
        assertEquals(1, calls.get());
        assertEquals(1, persisted.get().nextTurn());

        SummaryGenerator.SummaryResult resumed = new ModelSummaryGenerator(
                exact, BINDING, CLOCK, ModelSummaryGenerator.Limits.defaults(),
                operation(persisted, null)).generate(request);

        assertEquals(2, calls.get());
        assertEquals(List.of(1L, 2L, 3L), resumed.document().allFacts().stream()
                .map(SummaryDocument.Fact::sourceOrdinal).toList());
        assertEquals(new CheckpointUsage(10_000, 40, 10_040, 0, 0), resumed.usage());
    }

    /** candidate 已结算为 REPAIR 后强杀，Resume 只能发送一次冻结 repair，不得重发 candidate。 */
    @Test
    void resumesRepairAfterCandidateSettlementWithoutRepeatingCandidate() {
        AtomicInteger calls = new AtomicInteger();
        ModelSummaryGenerator generator;
        SummaryModel repairable = model(prompt -> calls.incrementAndGet() == 1
                ? new SummaryGenerator.SummaryResult(SummaryDocument.empty(),
                new CheckpointUsage(100, 10, 110, 0, 0))
                : new SummaryGenerator.SummaryResult(document("repaired"),
                new CheckpointUsage(120, 12, 132, 0, 0)));
        AtomicReference<ModelSummaryGenerator.SummaryOperation.Progress> persisted = new AtomicReference<>();
        generator = new ModelSummaryGenerator(repairable, BINDING, CLOCK,
                new ModelSummaryGenerator.Limits(10, 1_000, 500),
                operation(persisted, ModelSummaryGenerator.SummaryOperation.Stage.REPAIR));

        assertThrows(IllegalStateException.class, () -> generator.generate(request(message(100))));
        assertEquals(1, calls.get());
        assertEquals(ModelSummaryGenerator.SummaryOperation.Stage.REPAIR, persisted.get().stage());
        assertEquals(List.of("EMPTY_DOCUMENT", "UNCOVERED_CRITICAL_EVIDENCE"),
                persisted.get().violations());

        SummaryGenerator.SummaryResult resumed = new ModelSummaryGenerator(
                repairable, BINDING, CLOCK, new ModelSummaryGenerator.Limits(10, 1_000, 500),
                operation(persisted, null)).generate(request(message(100)));

        assertEquals(2, calls.get());
        assertEquals("repaired", resumed.document().goals().getFirst().text());
        assertEquals(new CheckpointUsage(220, 22, 242, 0, 0), resumed.usage());
    }

    /** repair 已结算为 FALLBACK_PENDING 后强杀，Resume 只能本地回退且不得重发两个 Provider 请求。 */
    @Test
    void resumesFallbackAfterRepairSettlementWithoutRepeatingProviderCalls() {
        AtomicInteger calls = new AtomicInteger();
        SummaryModel invalid = model(prompt -> {
            calls.incrementAndGet();
            return new SummaryGenerator.SummaryResult(document("oversized"),
                    new CheckpointUsage(100, 501, 601, 0, 0));
        });
        AtomicReference<ModelSummaryGenerator.SummaryOperation.Progress> persisted = new AtomicReference<>();
        ModelSummaryGenerator generator = new ModelSummaryGenerator(invalid, BINDING, CLOCK,
                new ModelSummaryGenerator.Limits(10, 1_000, 500),
                operation(persisted, ModelSummaryGenerator.SummaryOperation.Stage.FALLBACK_PENDING));

        assertThrows(IllegalStateException.class, () -> generator.generate(request(message(100))));
        assertEquals(2, calls.get());
        assertEquals(ModelSummaryGenerator.SummaryOperation.Stage.FALLBACK_PENDING,
                persisted.get().stage());
        assertEquals(List.of("OUTPUT_BUDGET_EXCEEDED"), persisted.get().violations());

        SummaryGenerator.SummaryResult resumed = new ModelSummaryGenerator(
                invalid, BINDING, CLOCK, new ModelSummaryGenerator.Limits(10, 1_000, 500),
                operation(persisted, null)).generate(request(message(100)));

        assertEquals(2, calls.get());
        assertEquals(List.of("user: " + "source".repeat(10)), resumed.document().criticalFacts().stream()
                .map(SummaryDocument.Fact::text).toList());
        assertEquals(new CheckpointUsage(200, 1_002, 1_202, 0, 0), resumed.usage());
    }

    /** 连续十轮使用全量替换与显式退休，证明旧事实不会随 Checkpoint 次数单调膨胀。 */
    @Test
    void tenRollingSummariesRetireSupersededFactsWithoutGrowth() {
        SummaryModel replacing = new SummaryModel() {
            /** 固定精确计量，让用例只验证滚动摘要与退休约束。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(100, "2".repeat(64));
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
        return configuration("model_test", "cfg_test");
    }

    /** 为逐请求刷新测试构造相同协议但身份不同的无网络模型配置。 */
    private static ModelPort.ModelConfiguration configuration(String modelId, String generation) {
        return new ModelPort.ModelConfiguration("provider_test", modelId, generation,
                ModelPort.Api.OPENAI_RESPONSES, modelId, URI.create("http://localhost:60842"),
                "test-secret", Duration.ofSeconds(5), Duration.ofSeconds(30),
                java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
    }

    /** Profile 与同一 runtime 的模型身份绑定，便于证明 intent 和实际调用没有跨租约错配。 */
    private static ProviderRequestProfile summaryProfile(String modelId, String generation, int sequence) {
        return new ProviderRequestProfile(
                "provider_test", modelId, "openai_responses", modelId, null, null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, generation,
                "prompt_runtime_" + sequence, "d".repeat(64), 32_000, 3_000);
    }

    /** 记录每次 ProviderPending 的 Profile，同时以内存游标执行真实 Summary 子阶段状态机。 */
    private static ModelSummaryGenerator.SummaryOperation recordingOperation(List<String> pendingModels) {
        return new ModelSummaryGenerator.SummaryOperation() {
            private Progress progress;

            /** 首次进入创建稳定计划游标，后续调用复用已结算阶段。 */
            @Override public Progress start(String fingerprint, SummaryDocument initial) {
                if (progress == null) {
                    progress = Progress.candidate(initial, CheckpointUsage.none(), 0, 0, fingerprint);
                }
                return progress;
            }

            /** intent 必须携带刚打开 runtime 的完整 Profile，空值表示自动摘要接线错误。 */
            @Override public void begin(String promptFingerprint, Optional<ProviderRequestProfile> profile) {
                pendingModels.add(profile.orElseThrow().modelId());
            }

            /** settlement 与下一阶段在同一内存边界推进，模拟仓储原子提交后的可恢复状态。 */
            @Override public void settle(CheckpointUsage usage, Progress accepted) {
                progress = accepted;
            }

            /** 无 Provider 的确定性回退只推进游标，不生成虚构请求。 */
            @Override public void advance(Progress accepted) {
                progress = accepted;
            }
        };
    }

    /** 提供可控摘要结果与稳定官方计量替身，使测试不再依赖生产端口的失败默认值。 */
    private static SummaryModel model(
            Function<SummaryModel.SummaryPrompt, SummaryGenerator.SummaryResult> delegate) {
        return new SummaryModel() {
            /** 返回足以验证窗口分支的固定 Provider 计量与合法指纹。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(100, "0".repeat(64));
            }

            /** 将结构化结果选择留给各测试用例，计量行为保持统一。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                return delegate.apply(prompt);
            }
        };
    }

    /** 构造仅用于故障注入的持久 Operation；指定阶段 settle 可模拟提交成功后的进程退出。 */
    private static ModelSummaryGenerator.SummaryOperation operation(
            AtomicReference<ModelSummaryGenerator.SummaryOperation.Progress> persisted,
            ModelSummaryGenerator.SummaryOperation.Stage crashAfterStage) {
        return new ModelSummaryGenerator.SummaryOperation() {
            private final AtomicBoolean crashOnce = new AtomicBoolean();

            /** 复用已提交游标；首次进入时创建稳定计划起点。 */
            @Override
            public Progress start(String fingerprint, SummaryDocument initial) {
                Progress current = persisted.get();
                if (current != null) {
                    assertEquals(fingerprint, current.planFingerprint());
                    return current;
                }
                Progress created = Progress.candidate(
                        initial, CheckpointUsage.none(), 0, 0, fingerprint);
                persisted.set(created);
                return created;
            }

            /** 测试替身不模拟 ProviderPending；强杀点只放在 settlement 已提交之后。 */
            @Override
            public void begin(String promptFingerprint,
                              Optional<io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile> profile) {
                // 故障注入替身不模拟 Provider pending，但仍接纳生产接口的可选请求画像。
            }

            /** 先保存 accepted 再抛错，模拟 SQLite commit 已完成而 JVM 随后退出。 */
            @Override
            public void settle(CheckpointUsage usage, Progress accepted) {
                persisted.set(accepted);
                if (accepted.stage() == crashAfterStage && crashOnce.compareAndSet(false, true)) {
                    throw new IllegalStateException("simulated crash");
                }
            }

            /** fallback advance 只接纳最终 CANDIDATE 游标，不参与 Provider settlement 故障注入。 */
            @Override public void advance(Progress accepted) { persisted.set(accepted); }
        };
    }
}
