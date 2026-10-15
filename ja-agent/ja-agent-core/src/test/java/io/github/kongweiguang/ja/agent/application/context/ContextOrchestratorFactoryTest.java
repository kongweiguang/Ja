// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.context;

import io.github.kongweiguang.ja.agent.application.context.summary.ModelSummaryGenerator;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.context.ToolProjection;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
        return new ContextTokenMeter.Measurement(tokens, tokens, "0".repeat(64));
    }

    /** 验证 Factory 在类型化摘要创建追加式 Checkpoint 前绑定准确的 Turn 上下文。 */
    @Test
    void bindsFrozenTurnProfileDeadlineAndCancellation() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        ManualToken token = new ManualToken();
        ModelPort.ModelConfiguration configuration = configuration();
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration, Optional.of(NOW.plusSeconds(30)), token);
        AtomicReference<SummaryModel.TurnBinding> observedBinding = new AtomicReference<>();
        AtomicReference<SummaryModel.SummaryPrompt> observedPrompt = new AtomicReference<>();
        CheckpointUsage usage = new CheckpointUsage(80, 20, 100, 5, 0);
        ContextOrchestratorFactory factory = new ContextOrchestratorFactory(store, CLOCK, captured -> {
            observedBinding.set(captured);
            return summaryModel(prompt -> {
                observedPrompt.set(prompt);
                return new SummaryModel.Result(summary(), usage);
            });
        });

        ContextOrchestrator orchestrator = factory.create("thread-1", runtime(binding), testOperation());
        ContextOrchestrator.Execution<String> execution = orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64),
                        CheckpointStore.ProjectionBinding.unbound(), TEST_METER,
                        CancellationToken.none(), Optional.empty()),
                receipt -> { }, prompt -> "ok");

        org.junit.jupiter.api.Assertions.assertNotSame(binding, observedBinding.get());
        assertSame(configuration, observedBinding.get().configuration());
        assertSame(token, observedBinding.get().cancellationToken());
        assertEquals(Optional.of(NOW.plusSeconds(30)), observedBinding.get().deadline());
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
            return summaryModel(prompt -> new SummaryModel.Result(summary(), CheckpointUsage.none()));
        });
        SummaryModel.TurnBinding expired = new SummaryModel.TurnBinding(
                "thread-1", configuration(), Optional.of(NOW), new ManualToken());

        ContextException failure = assertThrows(ContextException.class, () -> factory.create(
                "thread-1", runtime(expired), testOperation()).execute(
                        new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                                Optional.empty(), new ToolProjectionLimits(64, 64),
                                CheckpointStore.ProjectionBinding.unbound(), TEST_METER,
                                CancellationToken.none(), Optional.empty()),
                        receipt -> { }, prompt -> "unexpected"));

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
                "thread-1", configuration(), Optional.of(NOW.plusSeconds(30)), token);
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored -> summaryModel(prompt -> {
            invoked.set(true);
            return new SummaryModel.Result(summary(), CheckpointUsage.none());
        })).create("thread-1", runtime(binding), testOperation());
        token.cancel();

        assertThrows(CancellationException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64),
                        CheckpointStore.ProjectionBinding.unbound(), TEST_METER,
                        CancellationToken.none(), Optional.empty()),
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
                "thread-1", configuration(), Optional.of(NOW.plusSeconds(30)), token);
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored -> summaryModel(prompt -> {
            token.cancel();
            return new SummaryModel.Result(summary(), CheckpointUsage.none());
        })).create("thread-1", runtime(binding), testOperation());

        assertThrows(CancellationException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-1", 4, history(), normalBudget(), true,
                        Optional.empty(), new ToolProjectionLimits(64, 64),
                        CheckpointStore.ProjectionBinding.unbound(), TEST_METER,
                        CancellationToken.none(), Optional.empty()),
                receipt -> { }, prompt -> "unexpected"));
        assertTrue(store.appended.isEmpty());
    }

    /** 验证单 Turn Orchestrator 即使无需调用摘要模型，也拒绝用于另一 Thread。 */
    @Test
    void boundOrchestratorCannotBeReusedForAnotherThread() {
        MemoryStore store = new MemoryStore("thread-1", 4);
        SummaryModel.TurnBinding binding = new SummaryModel.TurnBinding(
                "thread-1", configuration(), Optional.of(NOW.plusSeconds(30)), new ManualToken());
        ContextOrchestrator orchestrator = new ContextOrchestratorFactory(store, CLOCK, ignored ->
                summaryModel(prompt -> new SummaryModel.Result(summary(), CheckpointUsage.none())))
                .create("thread-1", runtime(binding), testOperation());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> orchestrator.execute(
                new ContextOrchestrator.Request("thread-2", 4,
                        List.of(ContextMessage.text("message-x", "turn-x", 1,
                                ContextMessage.Role.USER, "small", 2)),
                        normalBudget(), false, Optional.empty(),
                        new ToolProjectionLimits(64, 64), CheckpointStore.ProjectionBinding.unbound(), TEST_METER,
                        CancellationToken.none(), Optional.empty()),
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
            Function<SummaryModel.SummaryPrompt, SummaryModel.Result> delegate) {
        return new SummaryModel() {
            /** 返回固定官方计量夹具，指纹仅用于满足强类型端口契约。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                return new ModelPort.InputTokenEstimate(100, 100, "0".repeat(64));
            }

            /** 把摘要结果及取消竞争留给单个测试定义。 */
            @Override
            public SummaryModel.Result summarize(SummaryPrompt prompt) {
                return delegate.apply(prompt);
            }
        };
    }

    /** 每次摘要计量和发送都打开新的测试租约，避免测试重新引入隐式绑定重载。 */
    private static ModelSummaryGenerator.RequestRuntimeFactory runtime(SummaryModel.TurnBinding source) {
        return () -> new ModelSummaryGenerator.RequestRuntime(new SummaryModel.TurnBinding(
                source.threadId(), source.configuration(), source.deadline(), source.cancellationToken()),
                Optional.empty(), () -> { });
    }

    /** 只为纯策略测试提供显式执行所有者，检查点仍必须带有真实形状的 Turn CAS。 */
    private static ModelSummaryGenerator.SummaryOperation testOperation() {
        return new ModelSummaryGenerator.SummaryOperation() {
            private Progress progress;

            /** 创建同一测试计划的第一个持久游标。 */
            @Override
            public Progress start(String fingerprint, SummaryDocument initial) {
                if (progress == null) {
                    progress = Progress.candidate(initial, CheckpointUsage.none(), 0, 0, fingerprint);
                }
                return progress;
            }

            /** 不发送真实 Provider，仅接受摘要边界提供的可选请求身份。 */
            @Override public void begin(String promptFingerprint,
                                        Optional<io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.ModelRequestIdentity> profile) { }

            /** 用一个内存原子替身记录摘要已结算的下一游标。 */
            @Override public void settle(CheckpointUsage usage, Progress accepted) { progress = accepted; }

            /** 这些 fixture 不制造网络故障，意外进入重试应使测试失败。 */
            @Override public void retry(int failedAttempt, long delayMillis, String reasonCode) {
                throw new AssertionError("unexpected Provider retry");
            }

            /** 固定检查点 CAS 的源版本，测试检查点仓储负责校验 revision。 */
            @Override public long checkpointSourceRevision(long originalRevision) { return originalRevision; }

            /** 明确提供测试执行身份，验证策略不能再通过空 operation 写检查点。 */
            @Override
            public Optional<CheckpointStore.TurnOperation> checkpointTurnOperation() {
                TurnExecutionState.Common common = new TurnExecutionState.Common(0, 0, 1, null, List.of());
                TurnExecutionState.Ready ready = new TurnExecutionState.Ready(
                        common, TurnExecutionState.Next.ASSISTANT, null);
                return Optional.of(new CheckpointStore.TurnOperation("turn_test", 0, ready));
            }
        };
    }

    /** 最小追加式 Store 记录与生产持久化相同的 Thread CAS 契约。 */
    private static final class MemoryStore implements CheckpointStore {
        private final String threadId;
        private long revision;
        private final List<ContextCheckpoint> appended = new ArrayList<>();
        private final Map<String, ProjectionSnapshot> projections = new HashMap<>();

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
            assertTrue(request.turnOperation().isPresent(),
                    "context compression requires a persistent execution owner");
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

        /** 复用同一模型绑定阶段，只有输入绑定改变时才为策略规划创建新阶段。 */
        @Override
        public synchronized ProjectionSnapshot prepareProjection(ProjectionRequest request) {
            ProjectionSnapshot current = projections.get(request.threadId());
            return current != null && current.binding().equals(request.binding()) ? current
                    : advanceProjection(request, current == null ? ProjectionReason.INITIAL
                            : ProjectionReason.MODEL_BINDING);
        }

        /** 显式递增阶段身份，避免测试的内存 Store 暗中替代投影仓储语义。 */
        @Override
        public synchronized ProjectionSnapshot advanceProjection(ProjectionRequest request,
                                                                 ProjectionReason reason) {
            ProjectionSnapshot previous = projections.get(request.threadId());
            ProjectionSnapshot next = new ProjectionSnapshot("projection_" + UUID.randomUUID(),
                    request.threadId(), previous == null ? 1 : previous.stageNumber() + 1,
                    request.sourceRevision(), reason, request.binding(), Map.of());
            projections.put(request.threadId(), next);
            return next;
        }

        /** 返回复制后的冻结投影，使后续断言不受调用方 Map 修改影响。 */
        @Override
        public synchronized ProjectionSnapshot persistProjection(ProjectionSnapshot snapshot,
                                                                 Map<String, ToolProjection> selections) {
            ProjectionSnapshot frozen = new ProjectionSnapshot(snapshot.stageId(), snapshot.threadId(),
                    snapshot.stageNumber(), snapshot.sourceRevision(), snapshot.reason(), snapshot.binding(),
                    selections);
            projections.put(snapshot.threadId(), frozen);
            return frozen;
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
