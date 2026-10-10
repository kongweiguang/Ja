// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluationAuditPort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ProviderProtocolException;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnToolSessionFactory;
import io.github.kongweiguang.ja.agent.application.concurrent.CancellationSource;
import io.github.kongweiguang.ja.plan.domain.PlanModels;
import io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluatorPort;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 无付费网络的 Plan evaluator 整链路恢复夹具。 */
final class RuntimePlanEvaluatorRecoveryTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
    @TempDir Path workspaceRoot;

    /** 六次可恢复 HTTP 失败各留下 UNKNOWN 审计，第七次成功且不要求人工继续。 */
    @Test
    void retriesBeyondOldAttemptLimitWithDistinctAuditIdentities() throws Exception {
        PlanModels.PlanSnapshot snapshot = snapshot();
        InMemoryAudit audit = new InMemoryAudit();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        ModelPort models = proxy(ModelPort.class, (method, args) -> {
            if (!"start".equals(method)) {
                throw new AssertionError(method);
            }
            if (attempts.incrementAndGet() <= 6) {
                return CompletableFuture.failedFuture(new ProviderProtocolException(
                        "HTTP_STATUS", "provider unavailable", true, Duration.ZERO));
            }
            ModelEventSink sink = (ModelEventSink) args[1];
            sink.onEvent(new ModelPort.TextDelta("""
                {\
                "verdict":"met","summary":"verified","criteria":[{\
                "criterionId":"criterion_tests","verdict":"met","reason":"passed"}]}\
                """))
                    .toCompletableFuture().join();
            return CompletableFuture.completedFuture(new ModelPort.ModelOutcome(
                    ModelPort.FinishReason.STOP, null, new ModelUsage(12, 6, 18)));
        });
        TurnRuntimeResolver runtimes = proxy(TurnRuntimeResolver.class, (method, args) -> {
            if (!"resolve".equals(method)) {
                throw new AssertionError(method);
            }
            return lease(releases);
        });
        ThreadProjection thread = new ThreadProjection(
                "thr_test", "ws_test", new ThreadPreferences("provider_test", "model_test",
                "medium", AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL), 0, List.of());
        ConversationProjectionRepository conversations = proxy(ConversationProjectionRepository.class, (method, args) -> {
            if (!"readThread".equals(method)) {
                throw new AssertionError(method);
            }
            return Optional.of(thread);
        });
        Workspace workspace = new Workspace("ws_test", workspaceRoot, "Workspace", Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, 0);
        WorkspaceUseCase workspaces = proxy(WorkspaceUseCase.class, (method, args) -> {
            if (!"requireOpenWorkspace".equals(method)) {
                throw new AssertionError(method);
            }
            return workspace;
        });
        RuntimePlanEvaluatorAdapter evaluator = new RuntimePlanEvaluatorAdapter(models, runtimes,
                conversations, workspaces, new ObjectMapper(), CLOCK, audit);

        PlanEvaluatorPort.Evaluation result = evaluator.evaluate(snapshot, evidence(),
                new PlanEvaluatorPort.EvaluationContext(new CancellationSource()))
                .toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(PlanModels.EvaluationVerdict.MET, result.verdict());
        assertEquals(7, attempts.get());
        assertEquals(7, releases.get());
        assertEquals(7, audit.intents.size());
        assertEquals(7, audit.usages.size());
        assertEquals(6, audit.usages.values().stream()
                .filter(usage -> usage.outcome() == PlanEvaluationAuditPort.Outcome.UNKNOWN).count());
        assertEquals(7, audit.intents.stream().map(PlanEvaluationAuditPort.Intent::evaluationId).distinct().count());
        assertEquals(7, audit.intents.stream().map(PlanEvaluationAuditPort.Intent::modelRequestId).distinct().count());
    }

    /** 夹具只需打开一次短租约；无 Tool、附件或 Prompt 读取路径。 */
    private static RuntimeLease lease(AtomicInteger releases) {
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "model_test", URI.create("http://127.0.0.1"),
                "test-secret", Duration.ofSeconds(5), Duration.ofSeconds(30),
                java.util.Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        return new RuntimeLease("cfg_test", model, AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, TurnLimits.defaults(), List.of(), java.util.Set.of(),
                proxy(TurnToolSessionFactory.class, (method, args) -> { throw new AssertionError(method); }),
                new ToolProjectionLimits(64_000, 16_000),
                proxy(AgentPromptSession.class, (method, args) -> { throw new AssertionError(method); }),
                request -> { throw new AssertionError("unexpected attachment read"); },
                List.of("test-secret"), "a".repeat(64), "prompt_test", "medium", releases::incrementAndGet);
    }

    /** Plan、步骤和证据身份一致，使失败只可能来自被注入的 Provider。 */
    private static PlanModels.PlanSnapshot snapshot() {
        PlanModels.PlanStep step = new PlanModels.PlanStep("step_tests", "run tests", "", true, List.of());
        PlanModels.AcceptanceCriterion criterion = new PlanModels.AcceptanceCriterion(
                "criterion_tests", "tests pass", true);
        PlanModels.PlanDefinition definition = new PlanModels.PlanDefinition(
                "verify", List.of(), List.of(), List.of(), List.of(),
                List.of(step), List.of(criterion), List.of(), List.of("check report"));
        PlanModels.PlanRevision revision = new PlanModels.PlanRevision("planrev_test", "plan_test", 1, definition,
                "{\"objective\":\"verify\"}", "a".repeat(64), "AGENT", Instant.EPOCH);
        PlanModels.Plan plan = new PlanModels.Plan("plan_test", "thr_test", "verify", PlanModels.PlanStatus.VERIFYING,
                3, "planrev_test", "run_test", Instant.EPOCH, Instant.EPOCH);
        PlanModels.StepExecution execution = new PlanModels.StepExecution("step_tests", "run_test",
                PlanModels.StepStatus.SUCCEEDED, 1, null, "passed", Instant.EPOCH, Instant.EPOCH);
        return new PlanModels.PlanSnapshot(plan, null, revision, null, List.of(execution), 1);
    }

    /** 一条显式关联必要条件的报告足以完成事实门。 */
    private static List<PlanModels.Evidence> evidence() {
        return List.of(new PlanModels.Evidence("evidence_test", "plan_test", "run_test",
                "planrev_test", "criterion_tests", "step_tests", PlanModels.EvidenceSource.TEST_REPORT,
                "report_test", "tests passed", "b".repeat(64), Instant.EPOCH, Instant.EPOCH));
    }

    /** 动态端口替身只接纳测试声明的方法，新增隐式副作用会立即暴露。 */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, PortCall call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (instance, method, args) -> call.invoke(method.getName(), args));
    }

    /** 方法名和参数是唯一输入，夹具不读取未授权的真实端口。 */
    @FunctionalInterface
    private interface PortCall {
        /** 代理只转发被明确识别的方法，不默许新增出站调用。 */
        Object invoke(String method, Object[] args);
    }

    /** 内存审计在每次尝试后发布独立终态，模拟数据库最新 ordinal 读取。 */
    private static final class InMemoryAudit implements PlanEvaluationAuditPort {
        private final List<Intent> intents = new CopyOnWriteArrayList<>();
        private final Map<String, Usage> usages = new ConcurrentHashMap<>();

        /** intent 必须先于模型请求出现。 */
        @Override public void recordIntent(Intent intent) { intents.add(intent); }

        /** 失败与成功的用量都按评估 ID 保留，Provider 身份由 usage 内部独立承载。 */
        @Override public void recordUsage(Usage usage) { usages.put(usage.evaluationId(), usage); }

        /** 直接身份查找复用最新索引映射。 */
        @Override public Optional<Prior> find(String evaluationId) {
            return intents.stream().filter(intent -> intent.evaluationId().equals(evaluationId))
                    .findFirst().map(this::prior);
        }

        /** 冻结输入的最大 ordinal 决定下一次身份，不把 UNKNOWN 当成成功。 */
        @Override public Optional<Prior> findLatest(String planId, String planRevisionId,
                                                      String runId, String inputDigest) {
            return intents.stream().filter(intent -> intent.planId().equals(planId)
                            && intent.planRevisionId().equals(planRevisionId)
                            && intent.runId().equals(runId) && intent.inputDigest().equals(inputDigest))
                    .max(java.util.Comparator.comparingInt(Intent::attemptOrdinal)).map(this::prior);
        }

        /** 模拟重启后的 UNKNOWN 结算；本用例正常重试前已显式结算失败。 */
        @Override public boolean markInterrupted(String evaluationId, Instant observedAt) {
            Intent intent = intents.stream().filter(item -> item.evaluationId().equals(evaluationId))
                    .findFirst().orElse(null);
            if (intent == null || usages.containsKey(evaluationId)) {
                return false;
            }
            usages.put(evaluationId, new Usage(evaluationId, intent.planId(), intent.planRevisionId(), intent.runId(),
                    new io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage(
                            intent.modelRequestId(),
                            intent.attemptOrdinal(), 1,
                            io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage.Purpose.ASSISTANT,
                            io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage.Certainty.UNKNOWN,
                            intent.profile(), null), Outcome.UNKNOWN, observedAt));
            return true;
        }

        /** 尚未结算的尝试保持 RUNNING，成功结论只来自对应 Usage。 */
        private Prior prior(Intent intent) {
            Usage usage = usages.get(intent.evaluationId());
            return new Prior(intent.evaluationId(), intent.modelRequestId(), intent.attemptOrdinal(),
                    usage == null ? Outcome.RUNNING : usage.outcome(),
                    usage == null ? null : usage.evaluation());
        }
    }
}
