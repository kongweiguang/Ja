// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointUsage;
import io.github.kongweiguang.ja.conversation.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryGenerator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.domain.ContextBudget;
import io.github.kongweiguang.ja.conversation.domain.ToolProjectionLimits;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖 Context 所有的生产组合边界，以及冻结 Profile、Deadline 与取消绑定。 */
final class ContextOrchestratorFactoryTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ContextTokenMeter TEST_METER = ContextOrchestratorFactoryTest::measureFixtureTokens;

    /** 组合测试只按 fixture 权重计量，确保新显式端口不依赖具体 Provider codec。 */
    private static ContextTokenMeter.Measurement measureFixtureTokens(
            List<ContextMessage> messages, SummaryDocument summary,
            Optional<ModelContinuation> continuation, boolean localCompaction) {
        long tokens = Math.max(1L, (summary.toPromptText().length() + 3L) / 4L);
        for (ContextMessage message : messages) {
            tokens = Math.addExact(tokens, message.estimatedTokens());
        }
        return new ContextTokenMeter.Measurement(tokens, "0".repeat(64));
    }

    /** 验证 Factory 在类型化摘要创建追加式 Checkpoint 前绑定准确的 Turn 上下文。 */
    @Test
    void bindsFrozenTurnProfileDeadlineAndCancellation() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        ManualToken token = new ManualToken();
        ModelPort.ModelConfiguration configuration = configuration();
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration, NOW.plusSeconds(30), token);
        AtomicReference<SummaryModel.TurnBinding> observedBinding = new AtomicReference<>();
        AtomicReference<SummaryModel.SummaryPrompt> observedPrompt = new AtomicReference<>();
        CheckpointUsage usage = new CheckpointUsage(80, 20, 100, 5, 0);
        ContextOrchestratorFactory factory = new ContextOrchestratorFactory(store, CLOCK, captured -> {
            observedBinding.set(captured);
            return summaryModel(prompt -> {
                observedPrompt.set(prompt);
                return new SummaryGenerator.SummaryResult(summary(), usage);
            });
        });

        ContextOrchestrator orchestrator = factory.create(binding);
        ContextOrchestrator.Execution<String> execution = orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64), TEST_METER),
                receipt -> { }, prompt -> "ok");

        assertSame(binding, observedBinding.get());
        assertSame(configuration, observedBinding.get().configuration());
        assertSame(token, observedBinding.get().cancellationToken());
        assertEquals(NOW.plusSeconds(30), observedBinding.get().deadline());
        assertEquals(ModelSummaryGenerator.PROMPT_VERSION, observedPrompt.get().promptVersion());
        CheckpointStore.ContextCheckpoint checkpoint = execution.checkpoint().orElseThrow();
        assertEquals("ok", execution.result());
        assertTrue(execution.compacted());
        assertFalse(execution.overflowRetried());
        assertTrue(execution.prompt().continuation().isEmpty());
        assertEquals("goal", execution.prompt().summary().goals().getFirst().text());
        assertEquals(4, checkpoint.sourceRevision());
        assertEquals(usage, checkpoint.usage());
        assertTrue(checkpoint.checkpointId().startsWith("checkpoint_"));
        io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted event =
                new io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Compacted(
                        new io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Context(
                                "evt_context", "ws_test", "thr_test", "turn_test", 5, NOW,
                                "cmp_test", io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.Trigger.AUTOMATIC,
                                4, 1_000L, (long) checkpoint.estimatedTokens(),
                                io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent.STRATEGY_VERSION),
                        checkpoint.checkpointId());
        assertEquals(checkpoint.checkpointId(), event.checkpointId());
        assertEquals(1, store.appended.size());
        assertFalse(binding.toString().contains("fixture-key"));
    }

    /** 验证过期 Turn 在模型绑定前失败，防止全局或默认 Profile 掩盖超时状态。 */
    @Test
    void rejectsExpiredBindingBeforeModelFactory() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        AtomicBoolean invoked = new AtomicBoolean();
        ContextOrchestratorFactory factory = new ContextOrchestratorFactory(store, CLOCK, binding -> {
            invoked.set(true);
            return prompt -> new SummaryGenerator.SummaryResult(summary(), CheckpointUsage.none());
        });
        SummaryModel.TurnBinding expired = new SummaryModel.TurnBinding(
                "thread-1", configuration(), NOW, new ManualToken());

        ContextException failure = assertThrows(ContextException.class, () -> factory.create(expired));

        assertEquals(ContextException.Code.SUMMARY_FAILURE, failure.code());
        assertFalse(invoked.get());
        assertTrue(store.appended.isEmpty());
    }

    /** 验证绑定后的取消在 Provider 准入时仍具权威性，且不会留下 Checkpoint。 */
    @Test
    void boundCancellationStopsSummaryBeforeProviderCall() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        ManualToken token = new ManualToken();
        AtomicBoolean invoked = new AtomicBoolean();
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration(), NOW.plusSeconds(30), token);
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored -> prompt -> {
            invoked.set(true);
            return new SummaryGenerator.SummaryResult(summary(), CheckpointUsage.none());
        }).create(binding);
        token.cancel();

        assertThrows(CancellationException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64), TEST_METER),
                receipt -> { }, prompt -> "unexpected"));
        assertFalse(invoked.get());
        assertTrue(store.appended.isEmpty());
    }

    /** 验证 Provider IO 期间胜出的取消会在追加 Checkpoint 前重新检查。 */
    @Test
    void cancellationDuringSummaryPreventsCheckpointAppend() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        ManualToken token = new ManualToken();
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration(), NOW.plusSeconds(30), token);
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored -> summaryModel(prompt -> {
            token.cancel();
            return new SummaryGenerator.SummaryResult(summary(), CheckpointUsage.none());
        })).create(binding);

        assertThrows(CancellationException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64), TEST_METER),
                receipt -> { }, prompt -> "unexpected"));
        assertTrue(store.appended.isEmpty());
    }

    /** 验证单 Turn Orchestrator 即使无需调用摘要模型，也拒绝用于另一 Thread。 */
    @Test
    void boundOrchestratorCannotBeReusedForAnotherThread() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration(), NOW.plusSeconds(30), new ManualToken());
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored -> prompt ->
                new SummaryGenerator.SummaryResult(summary(), CheckpointUsage.none())).create(binding);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-2", 4,
                        List.of(ContextMessage.text("message-x", "turn-x", 1,
                                ContextMessage.Role.USER, "small", 2)),
                        normalBudget(), false, Optional.empty(),
                        new ToolProjectionLimits(64, 64), TEST_METER),
                receipt -> { }, prompt -> "unexpected"));

        assertTrue(failure.getMessage().contains("Turn binding"));
        assertTrue(store.appended.isEmpty());
    }

    /** 构造真实不可变 Provider/Model 快照，且不导入任何 Provider 实现。 */
    private static ModelPort.ModelConfiguration configuration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "test-model", URI.create("http://localhost:60842"),
                "fixture-key", Duration.ofSeconds(5), Duration.ofSeconds(20),
                java.util.Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
    }

    /** 强制一个旧完整 Turn 进入摘要输入，同时保留最新完整 Turn。 */
    private static List<ContextMessage> history() {
        return List.of(
                ContextMessage.text("message-1", "turn-1", 1, ContextMessage.Role.USER, "old", 20_000),
                ContextMessage.text("message-2", "turn-2", 2, ContextMessage.Role.USER, "new", 2));
    }

    /** 为压缩摘要与最新尾部保留足够的 Provider 上下文空间。 */
    private static ContextBudget normalBudget() {
        return ContextBudget.capabilities(36_000, 2_000, true);
    }

    /** 返回非空类型化摘要，防止生产组合替换为抽取式降级实现。 */
    private static SummaryDocument summary() {
        return new SummaryDocument(List.of(fact("goal")), List.of(fact("constraint")), List.of(),
                List.of(fact("progress")), List.of(), List.of(), List.of(), List.of(fact("critical")),
                List.of(), List.of(), List.of());
    }

    /** Factory fixture 的全部摘要事实都来自首条不可变历史消息。 */
    private static SummaryDocument.Fact fact(String text) {
        return new SummaryDocument.Fact(text, 1);
    }

    /** 组合测试显式提供 Provider 计量，保证生产端口缺失计量时仍保持失败关闭。 */
    private static SummaryModel summaryModel(
            Function<SummaryModel.SummaryPrompt, SummaryGenerator.SummaryResult> delegate) {
        return new SummaryModel() {
            /** 返回固定官方计量夹具，指纹仅用于满足强类型端口契约。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(100, "0".repeat(64));
            }

            /** 把摘要结果及取消竞争留给单个测试定义。 */
            @Override
            public SummaryGenerator.SummaryResult summarize(SummaryPrompt prompt) {
                return delegate.apply(prompt);
            }
        };
    }

    /** 最小追加式 Store 记录与生产持久化相同的 Thread CAS 契约。 */
    private static final class MemoryStore implements CheckpointStore {
        private final String threadId;
        private long revision;
        private final List<ContextCheckpoint> appended = new ArrayList<>();

        /** 为 Factory 绑定的聚焦测试固定不可变源 revision。 */
        private MemoryStore(String threadId, long revision) {
            this.threadId = threadId;
            this.revision = revision;
        }

        /** 在同一同步快照中返回 Thread revision 与最新 Checkpoint。 */
        @Override
        public synchronized Snapshot read(String requestedThreadId) {
            assertEquals(threadId, requestedThreadId);
            Optional<ContextCheckpoint> latest = appended.isEmpty()
                    ? Optional.empty() : Optional.of(appended.getLast());
            return new Snapshot(threadId, revision, latest);
        }

        /** 把 Checkpoint 与源 revision 作为一个正式测试事务提交。 */
        @Override
        public synchronized CommittedCheckpoint commit(CommitRequest request) {
            assertEquals(threadId, request.threadId());
            Optional<ContextCheckpoint> existing = appended.stream()
                    .filter(value -> value.sourceRevision() == request.expectedThreadRevision())
                    .findFirst();
            if (existing.isPresent()) {
                return CommittedCheckpoint.reused(existing.orElseThrow(), revision);
            }
            if (revision != request.expectedThreadRevision()) {
                throw new CommitConflict("stale checkpoint source");
            }
            appended.add(request.checkpoint());
            revision++;
            return CommittedCheckpoint.created(request.checkpoint(), revision);
        }

    }

    /** 可变测试 Token 证明取消身份被保留，而非替换为空操作默认值。 */
    private static final class ManualToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        /** 返回单调测试取消位。 */
        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }

        /** 仅在取消后返回有界原因。 */
        @Override
        public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test cancelled") : Optional.empty();
        }

        /** 聚焦 Token 不保留回调，因为 Generator 会检查明确取消边界。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            return Registration.noop();
        }

        /** 在摘要调用开始前赢得单调取消竞争。 */
        private void cancel() {
            cancelled.set(true);
        }
    }
}
