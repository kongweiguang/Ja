// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.goal.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.RuntimeLease;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.goal.domain.GoalModels;
import io.github.kongweiguang.ja.goal.port.out.PlanEvaluatorPort;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用当前 Thread 配置执行 Plan 的独立无 Tool 模型验收。
 *
 * <p>每次模型请求有独立审计身份；瞬时失败先结算 UNKNOWN，再用新的 ordinal 恢复。</p>
 */
public final class RuntimePlanEvaluatorAdapter implements PlanEvaluatorPort {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(RuntimePlanEvaluatorAdapter.class);
    private final ModelPort models;
    private final TurnRuntimeResolver runtimes;
    private final ConversationRepository conversations;
    private final WorkspaceUseCase workspaces;
    private final ObjectMapper json;
    private final Clock clock;
    private final PlanEvaluationAuditPort audit;
    private final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.CompletableFuture<PlanEvaluatorPort.Evaluation>> active =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 生产构造必须显式提供 audit，避免 evaluator 结果脱离 intent/usage 账本。 */
    public RuntimePlanEvaluatorAdapter(ModelPort models, TurnRuntimeResolver runtimes,
            ConversationRepository conversations, WorkspaceUseCase workspaces,
            ObjectMapper json, Clock clock, PlanEvaluationAuditPort audit) {
        this.models = Objects.requireNonNull(models, "models");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    /** 同一冻结输入在进程内只运行一个验收；重入复用 stage，重启则从审计最大 ordinal 恢复。 */
    @Override
    public CompletionStage<PlanEvaluatorPort.Evaluation> evaluate(GoalModels.PlanSnapshot snapshot,
                                                                    List<GoalModels.Evidence> evidence,
                                                                    PlanEvaluatorPort.EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            context.cancellation().throwIfCancellationRequested();
        } catch (java.util.concurrent.CancellationException cancelled) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    inconclusive("Plan evaluator was cancelled"));
        }
        PlanEvaluationSupport.Prepared prepared;
        try {
            prepared = PlanEvaluationSupport.prepare(json, snapshot, evidence);
        } catch (RuntimeException failure) {
            logFailure("facts", failure);
            return java.util.concurrent.CompletableFuture.completedFuture(inconclusive("Plan facts are unavailable"));
        }
        String logicalId = requestId(snapshot, prepared);
        java.util.concurrent.CompletableFuture<PlanEvaluatorPort.Evaluation> result =
                new java.util.concurrent.CompletableFuture<>();
        var existing = active.putIfAbsent(logicalId, result);
        if (existing != null) return existing;
        result.whenComplete((ignored, failure) -> active.remove(logicalId, result));
        dispatchAttempt(snapshot, prepared, context, logicalId, result);
        return result;
    }

    /** 读取最新已提交审计后才决定新 ordinal，绝不原样重放旧的 UNKNOWN 请求。 */
    private void dispatchAttempt(GoalModels.PlanSnapshot snapshot, PlanEvaluationSupport.Prepared prepared,
                                 PlanEvaluatorPort.EvaluationContext context, String logicalId,
                                 java.util.concurrent.CompletableFuture<PlanEvaluatorPort.Evaluation> result) {
        if (result.isDone()) return;
        try {
            context.cancellation().throwIfCancellationRequested();
            Optional<PlanEvaluationAuditPort.Prior> prior = audit.findLatest(snapshot.plan().planId(),
                    snapshot.plan().activePlanRevisionId(), snapshot.plan().activeRunId(), prepared.digest());
            if (prior.isPresent() && prior.get().outcome() == PlanEvaluationAuditPort.Outcome.SUCCEEDED) {
                result.complete(restorePrior(prior.get()));
                return;
            }
            if (prior.isPresent() && prior.get().outcome() == PlanEvaluationAuditPort.Outcome.FAILED) {
                result.complete(inconclusive("Plan evaluator request was rejected"));
                return;
            }
            if (prior.isPresent() && prior.get().outcome() == PlanEvaluationAuditPort.Outcome.RUNNING
                    && !audit.markInterrupted(prior.get().requestId(), clock.instant())) {
                throw new IllegalStateException("Plan evaluator interrupted request could not be settled");
            }
            int ordinal = prior.isPresent() ? Math.addExact(prior.get().attemptOrdinal(), 1) : 1;
            String requestId = ordinal == 1 ? logicalId : logicalId + "_a" + ordinal;
            ConversationRepository.ThreadSnapshot thread = conversations.readThread(snapshot.plan().ownerThreadId())
                    .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
            Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
            if (!thread.preferences().providerId().startsWith("provider_")
                    || !thread.preferences().modelId().startsWith("model_")) {
                throw new IllegalStateException("Plan evaluator model selection is invalid");
            }
            @SuppressWarnings("PMD.CloseResource") // invokeModel 接管并在异步 Provider 结算后关闭租约。
            RuntimeLease lease = runtimes.resolve(new TurnRuntimeRequest(thread.threadId(), null,
                    workspace.root(), workspace.workspaceId(), thread.preferences().providerId(),
                    thread.preferences().modelId(), thread.preferences().reasoningLevel(),
                    thread.preferences().accessMode(), thread.preferences().collaborationMode(),
                    // 与 Goal evaluator 共用普通配置解析上下文：此处不接纳 Turn，也不需要或伪造执行 binding。
                    // 实际模型请求在下方强制使用空 Tool 目录和独立冻结 Prompt。
                    io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER,
                    clock.instant()));
            invokeModel(snapshot, prepared, lease, requestId, ordinal, context)
                    .whenComplete((value, failure) -> {
                        if (result.isDone()) return;
                        ModelPort.ModelUnavailableException provider = EvaluationRetry.retryableProvider(failure);
                        if (provider != null && !context.cancellation().isCancellationRequested()) {
                            Thread.startVirtualThread(() -> {
                                try {
                                    EvaluationRetry.await(EvaluationRetry.delay(provider, ordinal), context.cancellation());
                                    dispatchAttempt(snapshot, prepared, context, logicalId, result);
                                } catch (RuntimeException stopped) {
                                    result.complete(inconclusive("Plan evaluator was stopped"));
                                }
                            });
                        } else if (failure != null) {
                            result.complete(inconclusive("Plan evaluator did not produce a verifiable result"));
                        } else {
                            result.complete(value);
                        }
                    });
        } catch (RuntimeException failure) {
            logFailure("runtime-or-audit", failure);
            result.complete(inconclusive("Plan evaluator runtime or audit is unavailable"));
        }
    }

    /** Provider 仅接收 frozen payload 和空 Tool 列表；租约只在 stage 终态释放。 */
    private CompletionStage<PlanEvaluatorPort.Evaluation> invokeModel(GoalModels.PlanSnapshot snapshot,
            PlanEvaluationSupport.Prepared prepared, RuntimeLease lease, String requestId, int attemptOrdinal,
            PlanEvaluatorPort.EvaluationContext context) {
        String promptRevision = "prompt_" + prepared.digest();
        io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile profile;
        try {
            profile = lease.requestProfile(promptRevision);
        } catch (RuntimeException failure) {
            lease.close();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    inconclusive("Plan evaluator runtime is unavailable"));
        }
        try {
            audit.recordIntent(new PlanEvaluationAuditPort.Intent(requestId, snapshot.plan().planId(),
                    snapshot.plan().activePlanRevisionId(), snapshot.plan().activeRunId(),
                    snapshot.plan().ownerThreadId(), profile, prepared.digest(), attemptOrdinal, clock.instant()));
        } catch (RuntimeException failure) {
            logFailure("intent", failure);
            lease.close();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    inconclusive("Plan evaluator intent was not persisted"));
        }
        AtomicReference<ModelUsage> reportedUsage = new AtomicReference<>();
        StringBuilder output = new StringBuilder();
        ModelPort.ModelRequest request = new ModelPort.ModelRequest(lease.model(),
                new ModelPort.PromptPayload(PlanEvaluationSupport.systemPrompt(), promptRevision),
                List.of(new ModelMessage(ModelRole.USER, List.of(new TextContent(prepared.json())))),
                List.of(), null, 1,
                ModelPort.RequestDeadlinePolicy.TURN_MANAGED);
        CancellationSource providerCancellation = new CancellationSource();
        // 注册跨越 Provider stage，由 cleanup/whenComplete 统一关闭，不能在此处 try-with-resources 提前释放。
        @SuppressWarnings("PMD.CloseResource")
        CancellationToken.Registration cancellationRegistration = context.cancellation().onCancellation(
                () -> providerCancellation.cancel("plan_cancelled"));
        CompletionStage<ModelPort.ModelOutcome> stage;
        try {
            providerCancellation.throwIfCancellationRequested();
            stage = models.start(request, event -> {
                if (event instanceof ModelPort.TextDelta delta) {
                    if (output.length() + delta.text().length() > 64_000) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Plan evaluator output is too large"));
                    }
                    output.append(delta.text());
                } else if (event instanceof ModelPort.UsageEvent usage) {
                    ModelUsage previous = reportedUsage.get();
                    if (previous != null && !previous.equals(usage.usage())) {
                        return java.util.concurrent.CompletableFuture.failedFuture(
                                new IllegalStateException("Plan evaluator reported conflicting usage"));
                    }
                    reportedUsage.set(usage.usage());
                } else if (event instanceof ModelPort.ToolCallReady) {
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("Plan evaluator attempted a Tool call"));
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }, providerCancellation);
        } catch (RuntimeException failure) {
            stage = java.util.concurrent.CompletableFuture.failedFuture(failure);
        }
        if (stage == null) {
            stage = java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("Plan evaluator model stage is missing"));
        }
        return stage.handle((outcome, failure) -> {
            ModelUsage usage = reportedUsage.get();
            if (failure != null || outcome == null || outcome.finishReason() != ModelPort.FinishReason.STOP
                    || outcome.continuation() != null) {
                return settleFailure(snapshot, requestId, profile, usage, failure, attemptOrdinal,
                        context.cancellation().isCancellationRequested());
            }
            if (outcome.usage() != null) {
                if (usage != null && !usage.equals(outcome.usage())) {
                    return settleFailure(snapshot, requestId, profile, usage,
                            new IllegalStateException("Plan evaluator usage conflicted"), attemptOrdinal,
                            context.cancellation().isCancellationRequested());
                }
                usage = outcome.usage();
            }
            try {
                GoalModels.PlanRevision revision = snapshot.currentRevision();
                PlanEvaluationSupport.Parsed parsed = PlanEvaluationSupport.decode(json, output.toString(),
                        revision.definition().acceptanceCriteria(), prepared.evidenceComplete(),
                        prepared.stepsComplete());
                PlanEvaluatorPort.Evaluation result = new PlanEvaluatorPort.Evaluation(
                        parsed.verdict(), parsed.summary());
                recordUsage(requestId, snapshot, profile, usage, PlanEvaluationAuditPort.Outcome.SUCCEEDED,
                        attemptOrdinal,
                        new PlanEvaluationAuditPort.EvaluationResult(parsed.verdict(), parsed.criteria(),
                                parsed.summary()));
                return result;
            } catch (RuntimeException failureInDecode) {
                return settleFailure(snapshot, requestId, profile, usage, failureInDecode, attemptOrdinal,
                        context.cancellation().isCancellationRequested());
            }
        }).whenComplete((ignored, failure) -> {
            cancellationRegistration.close();
            lease.close();
        });
    }

    /** 成功审计直接恢复原结论，不把旧 UNKNOWN 或 RUNNING 请求误作成功。 */
    private PlanEvaluatorPort.Evaluation restorePrior(PlanEvaluationAuditPort.Prior prior) {
        if (prior.outcome() == PlanEvaluationAuditPort.Outcome.SUCCEEDED && prior.evaluation() != null) {
            PlanEvaluationAuditPort.EvaluationResult saved = prior.evaluation();
            return new PlanEvaluatorPort.Evaluation(saved.verdict(), saved.summary());
        }
        return inconclusive("Plan evaluator request already has a persisted result");
    }

    /** request identity 绑定冻结 Plan/run 与完整输入摘要，新增证据自然产生新请求。 */
    private static String requestId(GoalModels.PlanSnapshot snapshot,
                                    PlanEvaluationSupport.Prepared prepared) {
        return "request_plan_eval_" + PlanEvaluationSupport.digest(
                snapshot.plan().planId() + ':' + snapshot.plan().activeRunId() + ':'
                        + prepared.digest()).substring(0, 32);
    }

    /** 每次失败都独立结算；只有已分类瞬时故障才允许外层用新 ordinal 再次请求。 */
    private PlanEvaluatorPort.Evaluation settleFailure(GoalModels.PlanSnapshot snapshot, String requestId,
            io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile profile,
            ModelUsage usage, Throwable failure, int attemptOrdinal, boolean cancelled) {
        ModelPort.ModelUnavailableException provider = EvaluationRetry.retryableProvider(failure);
        try {
            recordUsage(requestId, snapshot, profile, usage,
                    provider != null || cancelled ? PlanEvaluationAuditPort.Outcome.UNKNOWN
                            : PlanEvaluationAuditPort.Outcome.FAILED,
                    attemptOrdinal, null);
        } catch (RuntimeException auditFailure) {
            // 审计失败后不能再次请求 Provider，否则前次计费事实会消失。
            logFailure("audit-write", auditFailure);
            return inconclusive("Plan evaluator audit state is unavailable");
        }
        if (provider != null) throw new java.util.concurrent.CompletionException(provider);
        return inconclusive("Plan evaluator did not produce a verifiable result");
    }

    /** 将 ModelUsage 映射为通用 ProviderRequestUsage，保留 UNKNOWN 的一等事实。 */
    private void recordUsage(String requestId, GoalModels.PlanSnapshot snapshot,
            io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile profile,
            ModelUsage usage, PlanEvaluationAuditPort.Outcome outcome,
            int attemptOrdinal,
            PlanEvaluationAuditPort.EvaluationResult evaluation) {
        ProviderRequestUsage facts = new ProviderRequestUsage(requestId, 1, attemptOrdinal,
                ProviderRequestUsage.Purpose.ASSISTANT,
                usage == null ? ProviderRequestUsage.Certainty.UNKNOWN : ProviderRequestUsage.Certainty.KNOWN,
                profile, usage);
        audit.recordUsage(new PlanEvaluationAuditPort.Usage(requestId, snapshot.plan().planId(),
                snapshot.plan().activePlanRevisionId(), snapshot.plan().activeRunId(), facts, outcome,
                clock.instant(), evaluation));
    }


    /** 完成门不可验证时返回稳定摘要；不得把异常文本或 Provider 响应泄漏到 Plan summary。 */
    private static PlanEvaluatorPort.Evaluation inconclusive(String summary) {
        LOGGER.warn("Plan verification inconclusive reason={}", summary);
        return new PlanEvaluatorPort.Evaluation(GoalModels.EvaluationVerdict.INCONCLUSIVE, summary);
    }

    /** 仅记录异常类型和编译期调用位置，保留 Native 诊断能力而不泄露 Provider 或数据库载荷。 */
    private static void logFailure(String stage, Throwable failure) {
        FailureDiagnostics.Summary summary = FailureDiagnostics.summarize(failure);
        LOGGER.warn("Plan evaluator failed stage={} type={} origin={}", stage, summary.type(), summary.origin());
    }
}
