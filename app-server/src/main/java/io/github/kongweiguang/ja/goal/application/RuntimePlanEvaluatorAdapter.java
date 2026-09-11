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
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用当前 Thread 配置执行 Plan 的独立无 Tool 模型验收。
 *
 * <p>Provider 请求只接受 Coordinator 传入的取消源和剩余 Run 时限；adapter 不创建默认预算，
 * 并在超时、取消或响应异常时把计量结算为 UNKNOWN。</p>
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
    private final ScheduledExecutorService deadlines;

    /** 生产构造必须显式提供 audit，避免 evaluator 结果脱离 intent/usage 账本。 */
    public RuntimePlanEvaluatorAdapter(ModelPort models, TurnRuntimeResolver runtimes,
            ConversationRepository conversations, WorkspaceUseCase workspaces,
            ObjectMapper json, Clock clock, PlanEvaluationAuditPort audit,
            ScheduledExecutorService deadlines) {
        this.models = Objects.requireNonNull(models, "models");
        this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.json = Objects.requireNonNull(json, "json");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.deadlines = Objects.requireNonNull(deadlines, "deadlines");
    }

    /** 仅异步执行冻结验收；取消和剩余预算由调用方传入并贯穿 Provider 生命周期。 */
    @Override
    public CompletionStage<PlanEvaluatorPort.Evaluation> evaluate(GoalModels.PlanSnapshot snapshot,
                                                                    List<GoalModels.Evidence> evidence,
                                                                    PlanEvaluatorPort.EvaluationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            context.cancellation().throwIfCancellationRequested();
            if (context.remainingBudget().isZero()) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                        inconclusive("Plan evaluator Run budget is exhausted"));
            }
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
        String requestId = requestId(snapshot, prepared);
        try {
            Optional<PlanEvaluationAuditPort.Prior> prior = audit.find(requestId);
            if (prior.isPresent()) return restorePrior(prior.get());
        } catch (RuntimeException failure) {
            logFailure("audit-read", failure);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    inconclusive("Plan evaluator audit state is unavailable"));
        }
        ConversationRepository.ThreadSnapshot thread;
        try {
            thread = conversations.readThread(snapshot.plan().ownerThreadId())
                    .orElseThrow(() -> new IllegalStateException("Plan owner Thread is unavailable"));
            Workspace workspace = workspaces.requireOpenWorkspace(thread.workspaceId());
            if (!thread.preferences().providerId().startsWith("provider_")
                    || !thread.preferences().modelId().startsWith("model_")) {
                throw new IllegalStateException("Plan evaluator model selection is invalid");
            }
            RuntimeLease lease = runtimes.resolve(new TurnRuntimeRequest(thread.threadId(), null,
                    workspace.root(), workspace.workspaceId(), thread.preferences().providerId(),
                    thread.preferences().modelId(), thread.preferences().reasoningLevel(),
                    thread.preferences().accessMode(), thread.preferences().collaborationMode(),
                    // 与 Goal evaluator 共用普通配置解析上下文：此处不接纳 Turn，也不需要或伪造执行 binding。
                    // 实际模型请求在下方强制使用空 Tool 目录和独立冻结 Prompt。
                    io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin.USER,
                    Duration.ofMinutes(30), clock.instant()));
            return invokeModel(snapshot, prepared, lease, requestId, context);
        } catch (RuntimeException failure) {
            logFailure("runtime", failure);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    inconclusive("Plan evaluator runtime is unavailable"));
        }
    }

    /** Provider 仅接收 frozen payload 和空 Tool 列表；租约只在 stage 终态释放。 */
    private CompletionStage<PlanEvaluatorPort.Evaluation> invokeModel(GoalModels.PlanSnapshot snapshot,
            PlanEvaluationSupport.Prepared prepared, RuntimeLease lease, String requestId,
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
                    snapshot.plan().ownerThreadId(), profile, prepared.digest(), clock.instant()));
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
                List.of(), null, 1, ModelPort.RetryPolicy.SINGLE_ATTEMPT);
        CancellationSource providerCancellation = new CancellationSource();
        // 注册跨越 Provider stage，由 cleanup/whenComplete 统一关闭，不能在此处 try-with-resources 提前释放。
        @SuppressWarnings("PMD.CloseResource")
        CancellationToken.Registration cancellationRegistration = context.cancellation().onCancellation(
                () -> providerCancellation.cancel("plan_cancelled"));
        long timeoutMillis = Math.max(1L, context.remainingBudget().toMillis());
        ScheduledFuture<?> timeout;
        try {
            timeout = deadlines.schedule(
                    () -> providerCancellation.cancel("plan_deadline"), timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            cancellationRegistration.close();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    settleFailure(snapshot, requestId, profile, lease, null));
        }
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
            cleanup(cancellationRegistration, timeout);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    settleFailure(snapshot, requestId, profile, lease, reportedUsage.get()));
        }
        if (stage == null) {
            cleanup(cancellationRegistration, timeout);
            return java.util.concurrent.CompletableFuture.completedFuture(
                    settleFailure(snapshot, requestId, profile, lease, reportedUsage.get()));
        }
        CompletionStage<ModelPort.ModelOutcome> bounded = stage.toCompletableFuture()
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
        return bounded.handle((outcome, failure) -> {
            ModelUsage usage = reportedUsage.get();
            if (failure != null || outcome == null || outcome.finishReason() != ModelPort.FinishReason.STOP
                    || outcome.continuation() != null) {
                return settleFailure(snapshot, requestId, profile, lease, usage);
            }
            if (outcome.usage() != null) {
                if (usage != null && !usage.equals(outcome.usage())) {
                    return settleFailure(snapshot, requestId, profile, lease, usage);
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
                        new PlanEvaluationAuditPort.EvaluationResult(parsed.verdict(), parsed.criteria(),
                                parsed.summary()));
                return result;
            } catch (RuntimeException failureInDecode) {
                return settleFailure(snapshot, requestId, profile, lease, usage);
            }
        }).whenComplete((ignored, failure) -> {
            cleanup(cancellationRegistration, timeout);
            lease.close();
        });
    }

    /** 释放 deadline 与取消回调，避免完成后的 evaluator 继续持有 Run/Provider 引用。 */
    private static void cleanup(CancellationToken.Registration registration, ScheduledFuture<?> timeout) {
        registration.close();
        timeout.cancel(false);
    }

    /** 成功审计优先恢复原结论；UNKNOWN/RUNNING/FAILED 永不隐式重试 Provider。 */
    private CompletionStage<PlanEvaluatorPort.Evaluation> restorePrior(PlanEvaluationAuditPort.Prior prior) {
        if (prior.outcome() == PlanEvaluationAuditPort.Outcome.SUCCEEDED && prior.evaluation() != null) {
            PlanEvaluationAuditPort.EvaluationResult saved = prior.evaluation();
            return java.util.concurrent.CompletableFuture.completedFuture(
                    new PlanEvaluatorPort.Evaluation(saved.verdict(), saved.summary()));
        }
        return java.util.concurrent.CompletableFuture.completedFuture(
                inconclusive("Plan evaluator request already has a persisted result"));
    }

    /** request identity 绑定冻结 Plan/run 与完整输入摘要，新增证据自然产生新请求。 */
    private static String requestId(GoalModels.PlanSnapshot snapshot,
                                    PlanEvaluationSupport.Prepared prepared) {
        return "request_plan_eval_" + PlanEvaluationSupport.digest(
                snapshot.plan().planId() + ':' + snapshot.plan().activeRunId() + ':'
                        + prepared.digest()).substring(0, 32);
    }

    /** 终态审计使用 UNKNOWN 计量，防止 Provider 异常被误记为零成本或成功。 */
    private PlanEvaluatorPort.Evaluation settleFailure(GoalModels.PlanSnapshot snapshot, String requestId,
            io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile profile, RuntimeLease lease,
            ModelUsage usage) {
        try {
            recordUsage(requestId, snapshot, profile, usage, PlanEvaluationAuditPort.Outcome.UNKNOWN, null);
        } catch (RuntimeException auditFailure) {
            // 审计落库失败同样不能把 evaluator 结论提升为成功；稳定返回 INCONCLUSIVE 供恢复路径处理。
            logFailure("audit-write", auditFailure);
        } finally {
            lease.close();
        }
        return inconclusive("Plan evaluator did not produce a verifiable result");
    }

    /** 将 ModelUsage 映射为通用 ProviderRequestUsage，保留 UNKNOWN 的一等事实。 */
    private void recordUsage(String requestId, GoalModels.PlanSnapshot snapshot,
            io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile profile,
            ModelUsage usage, PlanEvaluationAuditPort.Outcome outcome,
            PlanEvaluationAuditPort.EvaluationResult evaluation) {
        ProviderRequestUsage facts = new ProviderRequestUsage(requestId, 1, 1,
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
