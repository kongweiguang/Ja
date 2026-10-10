// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.conversation.application.permission.JaApprovalPolicy;
import io.github.kongweiguang.ja.conversation.application.policy.PlanToolPolicy;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolFactContributor;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolPresentationSerialization;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 将 Ja 应用工具映射为 Core 中立端口，宿主投影事实与权威工具结果走同一持久事务。
 */
public final class JaAgentToolAdapter implements AgentTool {
    private static final String WORKSPACE_NAMESPACE = "ja.workspace";
    private static final String TOOL_NAMESPACE = "ja.tool";
    private static final int FACT_SCHEMA = 1;
    private static final int MAX_TOOL_ARTIFACT_BYTES = 16 * 1024 * 1024;

    private final JaTool delegate;
    private final Dependencies dependencies;

    /**
     * 冻结一次请求的 Ja 上下文、权限、持久写租约和展示策略，避免恢复时重新读取漂移配置。
     */
    public JaAgentToolAdapter(JaTool delegate, Dependencies dependencies) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    /**
     * 通过固定调用身份派生的 context 检查每类 Ja 扩展事实只能由一个明确 owner 编码。
     */
    private static void rejectAdapterOwnedFacts(List<ExtensionFact> facts) {
        if (facts.stream().anyMatch(fact -> Set.of(WORKSPACE_NAMESPACE, TOOL_NAMESPACE, "ja.turn")
                .contains(fact.namespace()))) {
            throw new IllegalArgumentException("Ja tools cannot provide adapter-owned facts");
        }
    }

    /**
     * 把原生 Tool call identity 和两版脱敏 UI 快照一并放入 Ja tool namespace。
     */
    private static ExtensionFact presentationFact(String callId, long revision, String state,
                                                  String presentationJson) {
        return new ExtensionFact(TOOL_NAMESPACE, "presentation", FACT_SCHEMA,
                JsonObjects.builder().putText("callId", callId).putNumber("revision", revision)
                        .putText("state", state).putText("presentationJson", presentationJson).build());
    }

    /**
     * 过大 artifact 明确显示受限状态，不写入半截正文或生成无法读取的数据库引用。
     */
    private static ToolPresentation withoutArtifact(ToolPresentation value) {
        return new ToolPresentation(value.kind(), value.title(), value.status(), value.inputPreview(),
                value.outputPreview(), value.summary(), value.interactionAnswers(), value.relativePaths(),
                value.command(), value.relativeCwd(), value.stdout(), value.stderr(), value.exitCode(),
                value.durationMs(), true, null, value.recovery(), value.media());
    }

    /**
     * 只把 AgentTool outcome 映射成持久 store 的 ToolState 名称，保持 codec 大写协议。
     */
    private static String toolState(ToolOutcome outcome) {
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    /**
     * 只在路径位于当前 Workspace 时记录相对路径，保留收据原有物理 confinement 等级。
     */
    private static void appendMutationReceipt(
            List<ExtensionFact> facts, JaTool.ExecutionContext context, JaTool.MutationReceipt receipt) {
        Path root = context.workspaceRoot().toAbsolutePath().normalize();
        Path path = receipt.path().toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            facts.add(observationFailure(context, JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE));
            return;
        }
        Path relative = root.relativize(path).normalize();
        if (relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith("..")) {
            facts.add(observationFailure(context, JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE));
            return;
        }
        String relativePath = relative.toString().replace('\\', '/');
        boolean confined = false;
        if (receipt.confinedWorkspaceRoot() != null) {
            String declaredRelative = receipt.confinedRelativePath().replace('\\', '/');
            if (!receipt.confinedWorkspaceRoot().toAbsolutePath().normalize().equals(root)
                || !declaredRelative.equals(relativePath)) {
                facts.add(observationFailure(context, JaTool.MutationObservationFailure.CAPTURE_FAILED));
                return;
            }
            confined = true;
        }
        facts.add(new ExtensionFact(WORKSPACE_NAMESPACE, "mutation-receipt", FACT_SCHEMA,
                JsonObjects.builder().putText("workspaceId", context.workspaceId())
                        .putText("relativePath", relativePath)
                        .putBoolean("beforeExists", receipt.beforeExists())
                        .putBoolean("afterExists", receipt.afterExists())
                        .putText("beforeText", receipt.beforeText())
                        .putText("afterText", receipt.afterText())
                        .putNumber("beforeBytes", receipt.beforeBytes())
                        .putNumber("afterBytes", receipt.afterBytes())
                        .putText("beforeSha256", receipt.beforeSha256())
                        .putText("afterSha256", receipt.afterSha256())
                        .putBoolean("confined", confined).build()));
    }

    /**
     * 将无法恢复的写后观察原因持久化，避免成功正文掩盖变更集缺口。
     */
    private static ExtensionFact observationFailure(
            JaTool.ExecutionContext context, JaTool.MutationObservationFailure failure) {
        return new ExtensionFact(WORKSPACE_NAMESPACE, "mutation-observation-failure", FACT_SCHEMA,
                JsonObjects.builder().putText("workspaceId", context.workspaceId())
                        .putText("reason", Objects.requireNonNull(failure, "failure").name()).build());
    }

    /**
     * 将 coordinator 的数据库 claim 包成 Core 可传递的短期资源，并保留 Ja 内部重用入口。
     */
    private static WorkspaceSettlement settlement(WorkspaceWriteLeaseCoordinator.Lease lease) {
        return new WorkspaceSettlement(lease);
    }

    /**
     * 原始同步派发失败时释放租约，同时保留最初的失败作为主要诊断。
     */
    private static void closeWithSuppression(WorkspaceWriteLeaseCoordinator.Lease lease, Throwable failure) {
        try {
            lease.close();
        } catch (RuntimeException | Error closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * CompletionStage 失败通常被包一层 CompletionException；只去壳不替换真实原因。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable value = failure;
        while (value instanceof CompletionException && value.getCause() != null) {
            value = value.getCause();
        }
        return value;
    }

    /**
     * 每个 contributor 输出都复制且非空校验，避免提交前异步修改事实列表。
     */
    private static List<ExtensionFact> immutableFacts(List<ExtensionFact> values, String field) {
        List<ExtensionFact> facts = List.copyOf(Objects.requireNonNull(values, field));
        facts.forEach(value -> Objects.requireNonNull(value, field));
        if (facts.size() > 256) {
            throw new IllegalArgumentException("too many " + field);
        }
        return facts;
    }

    /**
     * 固定摘要编码与数据库 participant 一致，禁止平台默认字符集参与 artifact 校验。
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 统一拒绝空 stage，避免错误延迟到 Store 事务或异步回调后才暴露。
     */
    private static <T> CompletionStage<T> nonNullStage(CompletionStage<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    /**
     * 保持 Ja 工具声明与请求目录相同，避免适配器改写模型可见 Schema。
     */
    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    /**
     * 将逐调用副作用语义委托给已解析的 Ja 工具动作。
     */
    @Override
    public ToolSideEffect sideEffect(Invocation invocation) {
        return delegate.sideEffect(invocation);
    }

    /**
     * 静态副作用语义保持与 Ja 实现一致，不能因适配器默认值扩大执行权限。
     */
    @Override
    public ToolSideEffect sideEffect() {
        return delegate.sideEffect();
    }

    /**
     * 把 Ja 审批层级映射到 Core 闭集类型，不让 Ja 策略枚举进入通用循环。
     */
    @Override
    public ApprovalRequirement approvalRequirement(Invocation invocation) {
        Invocation call = Objects.requireNonNull(invocation, "invocation");
        JaTool.ApprovalRequirement declaration = delegate.approvalRequirement(call);
        if (dependencies.accessMode() == AccessMode.FULL_ACCESS
            || declaration == JaTool.ApprovalRequirement.USER_REQUIRED) {
            return JaApprovalPolicy.requirement(dependencies.accessMode(), declaration, Optional.empty(), false);
        }
        return JaApprovalPolicy.requirement(dependencies.accessMode(), declaration,
                Optional.of(delegate.bindingDescriptor(call)), delegate.isTrustedLocalReadAction(call));
    }

    /**
     * 保持静态审批声明一致，避免未选择具体 action 时降低既有策略要求。
     */
    @Override
    public ApprovalRequirement approvalRequirement() {
        JaTool.ApprovalRequirement declaration = delegate.approvalRequirement();
        if (dependencies.accessMode() == AccessMode.FULL_ACCESS
            || declaration == JaTool.ApprovalRequirement.USER_REQUIRED) {
            return JaApprovalPolicy.requirement(dependencies.accessMode(), declaration, Optional.empty(), false);
        }
        return ApprovalRequirement.REQUIRED;
    }

    /**
     * 保留动态路由工具声明的二次参数校验边界。
     */
    @Override
    public boolean validatesResolvedArguments() {
        return delegate.validatesResolvedArguments();
    }

    /**
     * 保留 Ja 工具已脱敏的确定参数错误，避免诊断复制模型原始参数。
     */
    @Override
    public Optional<InvocationValidationFailure> validationFailure(Invocation invocation) {
        Optional<JaTool.InvocationValidationFailure> invalid = delegate.validationFailure(invocation);
        if (invalid.isPresent()) {
            JaTool.InvocationValidationFailure failure = invalid.orElseThrow();
            return Optional.of(new AgentTool.InvocationValidationFailure(failure.code(), failure.message()));
        }
        PlanToolPolicy.Decision plan = PlanToolPolicy.validate(delegate, invocation,
                dependencies.origin(), dependencies.collaborationMode());
        return plan.proceed() ? Optional.empty()
                : Optional.of(new AgentTool.InvocationValidationFailure(plan.code(), plan.message()));
    }

    /**
     * 从冻结 access mode 与最终 Ja 路由生成权限指纹，不从工具名称猜测授权。
     */
    @Override
    public ToolExecutionBinding executionBinding(Invocation invocation, ToolBindingContext context) {
        Invocation call = Objects.requireNonNull(invocation, "invocation");
        ToolBindingContext binding = Objects.requireNonNull(context, "context");
        ToolSpec tool = Objects.requireNonNull(spec(), "tool spec");
        if (!tool.name().equals(call.toolName())) {
            throw new IllegalArgumentException("Tool binding name mismatch");
        }
        JaTool.ToolBindingDescriptor descriptor = Objects.requireNonNull(
                delegate.bindingDescriptor(call), "Ja tool binding descriptor");
        return dependencies.bindingCodec().encode(tool.name(), binding.catalogFingerprint(),
                dependencies.accessMode(), delegate.sideEffect(call), descriptor);
    }

    /**
     * 在 ToolPrepared 同一事务保存脱敏展示快照、工具准备事实和通用业务贡献事实。
     */
    @Override
    public List<ExtensionFact> preparedFacts(Invocation invocation, ExecutionContext context) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        List<ExtensionFact> facts = new ArrayList<>();
        facts.addAll(immutableFacts(delegate.preparedFacts(invocation, app), "Ja prepared facts"));
        for (JaToolFactContributor contributor : dependencies.factContributors()) {
            facts.addAll(immutableFacts(contributor.prepared(invocation, app), "contributor prepared facts"));
        }
        rejectAdapterOwnedFacts(facts);
        ToolPresentation prepared = ToolPresentationProjector.prepared(
                invocation, app.workspaceRoot(), dependencies.presentationSecrets());
        facts.add(presentationFact(invocation.callId(), 0, "PREPARED",
                dependencies.presentationCodec().write(prepared)));
        return immutableFacts(facts, "prepared facts");
    }

    /**
     * 将 Ja tool 与所有显式 contributor 的 Started 事实交给 Core 一同提交执行意图。
     */
    @Override
    public List<ExtensionFact> startedFacts(Invocation invocation, ExecutionContext context) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        List<ExtensionFact> facts = new ArrayList<>(immutableFacts(
                delegate.startedFacts(invocation, app), "Ja started facts"));
        for (JaToolFactContributor contributor : dependencies.factContributors()) {
            facts.addAll(immutableFacts(contributor.started(invocation, app), "contributor started facts"));
        }
        rejectAdapterOwnedFacts(facts);
        return immutableFacts(facts, "started facts");
    }

    /**
     * 外部副作用前读取工具自己的恢复凭证；该阶段不取得写租约也不触碰工作区。
     */
    @Override
    public Optional<RecoveryEvidence> prepareRecoveryEvidence(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
        return delegate.prepareRecoveryEvidence(invocation, appContext(invocation, context), cancellationToken);
    }

    /**
     * 按已冻结 call key 核验结果；仅 Applied 产出 Ja 结果事实，未知状态不伪造工具结果。
     */
    @Override
    public CompletionStage<RecoveryResolution> reconcile(
            Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
            CancellationToken cancellationToken) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        Function<CancellationToken, CompletionStage<RecoveryResolution>> operation = cancellation -> delegate.reconcile(
                invocation, app, evidence, cancellation).thenApply(resolution -> switch (
                Objects.requireNonNull(resolution, "recovery resolution")) {
            case JaTool.RecoveryResolution.Applied applied -> new RecoveryResolution.Applied(
                    toCoreResult(invocation, applied.result(), app), applied.verificationFingerprint());
            case JaTool.RecoveryResolution.NotApplied notApplied ->
                    new RecoveryResolution.NotApplied(notApplied.verificationFingerprint());
            case JaTool.RecoveryResolution.Unknown unknown -> new RecoveryResolution.Unknown(unknown.reasonCode());
        });
        if (requiresWorkspaceLease(invocation)) {
            return withLease(app, cancellationToken, operation, (resolution, lease) -> switch (resolution) {
                        case RecoveryResolution.Applied applied -> new LeaseDisposition<>(
                                new RecoveryResolution.Applied(applied.result().withSettlementResource(settlement(lease)),
                                        applied.verificationFingerprint()), true);
                        case RecoveryResolution.NotApplied notApplied -> new LeaseDisposition<>(
                                new RecoveryResolution.NotApplied(notApplied.verificationFingerprint(), settlement(lease)), true);
                        case RecoveryResolution.Unknown unknown -> new LeaseDisposition<>(
                                new RecoveryResolution.Unknown(unknown.reasonCode(), settlement(lease)), true);
                    },
                    new RecoveryResolution.Unknown("WORKSPACE_WRITE_COORDINATION_FAILED"));
        }
        return nonNullStage(operation.apply(cancellationToken), "tool recovery stage");
    }

    /**
     * 使用同一持久写 claim 完成核验，避免核验与重派之间释放 workspace fencing。
     */
    @Override
    public CompletionStage<RecoveryResolution> reconcileWithSettlement(
            Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
            CancellationToken cancellationToken, SettlementResource settlementResource) {
        if (!requiresWorkspaceLease(invocation)) {
            return AgentTool.super.reconcileWithSettlement(invocation, context, evidence,
                    cancellationToken, settlementResource);
        }
        if (!(settlementResource instanceof WorkspaceSettlement held)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "workspace recovery is missing its durable write claim"));
        }
        JaTool.ExecutionContext app = appContext(invocation, context);
        CompletionStage<JaTool.RecoveryResolution> stage = Objects.requireNonNull(delegate.reconcile(
                invocation, app, evidence, held.lease().cancellation()), "tool recovery stage");
        return stage.thenApply(resolution -> {
            held.lease().verifyOwnership();
            return switch (Objects.requireNonNull(resolution, "recovery resolution")) {
                case JaTool.RecoveryResolution.Applied applied -> new RecoveryResolution.Applied(
                        toCoreResult(invocation, applied.result(), app).withSettlementResource(held),
                        applied.verificationFingerprint());
                case JaTool.RecoveryResolution.NotApplied notApplied ->
                        new RecoveryResolution.NotApplied(notApplied.verificationFingerprint(), held);
                case JaTool.RecoveryResolution.Unknown unknown ->
                        new RecoveryResolution.Unknown(unknown.reasonCode(), held);
            };
        });
    }

    /**
     * 在 mutating Tool 的真实调用期间取得 SQLite FIFO/fencing claim，并记录派发耗时供同事务展示。
     */
    @Override
    public CompletionStage<ExecutionResult> execute(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        Function<CancellationToken, CompletionStage<ExecutionResult>> operation =
                cancellation -> executeMeasured(invocation, app, cancellation);
        if (requiresWorkspaceLease(invocation)) {
            ExecutionResult notDispatched = ExecutionResult.of(new ToolResult(ToolOutcome.FAILED,
                    "Workspace write access could not be coordinated. Retry after workspace activity settles.",
                    Optional.empty(), "WORKSPACE_WRITE_COORDINATION_FAILED"));
            return withLease(app, cancellationToken, operation,
                    (result, lease) -> new LeaseDisposition<>(result.withSettlementResource(settlement(lease)), true),
                    notDispatched);
        }
        return nonNullStage(operation.apply(cancellationToken), "tool execution stage");
    }

    /**
     * 在 NotApplied 证明和 Tool 派发之间复用同一 claim，并只给本次真实派发记录耗时。
     */
    @Override
    public CompletionStage<ExecutionResult> executeAfterNotApplied(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken,
            SettlementResource settlementResource) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        if (!requiresWorkspaceLease(invocation)) {
            return executeMeasured(invocation, app, cancellationToken)
                    .thenApply(result -> result.withSettlementResource(settlementResource));
        }
        if (!(settlementResource instanceof WorkspaceSettlement held)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "workspace retry is missing its durable write claim"));
        }
        return executeMeasured(invocation, app, held.lease().cancellation()).thenApply(result -> {
            held.lease().verifyOwnership();
            return result.withSettlementResource(held);
        });
    }

    /**
     * 结果投影后写入脱敏 presentation；恢复结果缺少实测耗时，不从重启间隔推算。
     */
    @Override
    public List<ExtensionFact> resultFacts(Invocation invocation, ExecutionContext context,
                                           ExecutionResult executionResult,
                                           io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware.ToolResultProjection visibleResult) {
        JaTool.ExecutionContext app = appContext(invocation, context);
        ExecutionResult execution = Objects.requireNonNull(executionResult, "executionResult");
        ToolResult authoritativeResult = execution.result();
        ToolResult visible = new ToolResult(authoritativeResult.outcome(), visibleResult.content(),
                visibleResult.structuredContent(), authoritativeResult.errorCode(),
                visibleResult.artifactContent(), authoritativeResult.media());
        Long durationMillis = execution.executionDurationMillis().isPresent()
                ? execution.executionDurationMillis().getAsLong() : null;
        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation, visible, app.workspaceRoot(), dependencies.presentationSecrets(), durationMillis);
        ToolPresentation presentation = completed.presentation();
        String content = completed.artifactContent();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TOOL_ARTIFACT_BYTES) {
            presentation = withoutArtifact(presentation);
            content = "";
            bytes = new byte[0];
        }
        List<ExtensionFact> facts = new ArrayList<>();
        facts.add(presentationFact(invocation.callId(), 1, toolState(authoritativeResult.outcome()),
                dependencies.presentationCodec().write(presentation)));
        if (!content.isEmpty()) {
            String artifactId = Objects.requireNonNull(presentation.artifactId(), "artifactId");
            facts.add(new ExtensionFact(TOOL_NAMESPACE, "artifact-content", FACT_SCHEMA,
                    JsonObjects.builder().putText("callId", invocation.callId())
                            .putText("artifactId", artifactId).putText("content", content)
                            .putText("sha256", sha256(bytes)).putNumber("byteLength", bytes.length).build()));
        }
        return List.copyOf(facts);
    }

    /**
     * 只测量真实 delegate 派发到结果到达的区间；同步抛错或未知恢复不伪造结算耗时。
     */
    private CompletionStage<ExecutionResult> executeMeasured(
            Invocation invocation, JaTool.ExecutionContext context, CancellationToken cancellationToken) {
        long startedAt = System.nanoTime();
        CompletionStage<JaTool.ToolResult> stage = Objects.requireNonNull(
                delegate.execute(invocation, context, cancellationToken), "tool execution stage");
        return stage.thenApply(result -> {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return toCoreResult(invocation, result, context).withExecutionDurationMillis(durationMillis);
        });
    }

    /**
     * 只接受 context factory 基於當前 frozen request 產生的精確調用與 workspace 身份。
     */
    private JaTool.ExecutionContext appContext(Invocation invocation, ExecutionContext context) {
        AgentTool.ExecutionContext core = Objects.requireNonNull(context, "context");
        JaTool.ExecutionContext application = Objects.requireNonNull(
                dependencies.contextFactory().create(Objects.requireNonNull(invocation, "invocation"), core),
                "application context");
        if (!application.identity().equals(core.identity())
            || !application.callId().equals(invocation.callId())
            || !application.callKey().equals(core.callKey())
            || application.accessMode() != dependencies.accessMode()) {
            throw new IllegalArgumentException("Ja Tool context does not match the active Core invocation");
        }
        return application;
    }

    /**
     * 只在能力声明可能改 Workspace 时申请持久跨 Task 写租约，NONE 仍不触发本地协调。
     */
    private boolean requiresWorkspaceLease(Invocation invocation) {
        return delegate.workspaceMutationMode(Objects.requireNonNull(invocation, "invocation"))
               != JaTool.WorkspaceMutationMode.NONE;
    }

    /**
     * 将 Ja 工具事实、mutation receipt 和 settled contributors 组合成同一核心结果事实集。
     */
    private ExecutionResult toCoreResult(Invocation invocation, JaTool.ToolResult result,
                                         JaTool.ExecutionContext context) {
        JaTool.ToolResult value = Objects.requireNonNull(result, "Ja Tool result");
        List<ExtensionFact> facts = new ArrayList<>(immutableFacts(value.extensionFacts(), "Ja tool facts"));
        rejectAdapterOwnedFacts(facts);
        value.mutationReceipt().ifPresent(receipt -> appendMutationReceipt(facts, context, receipt));
        value.mutationObservationFailure().ifPresent(failure -> facts.add(observationFailure(context, failure)));
        for (JaToolFactContributor contributor : dependencies.factContributors()) {
            List<ExtensionFact> contributorFacts = immutableFacts(
                    contributor.settled(invocation, context, value), "contributor settled facts");
            rejectAdapterOwnedFacts(contributorFacts);
            facts.addAll(contributorFacts);
        }
        return new ExecutionResult(new ToolResult(value.outcome(), value.content(),
                value.structuredContent(), value.errorCode(), value.artifactContent(), value.media()),
                immutableFacts(facts, "Ja result facts"));
    }

    /**
     * 异步完成后校验 fence；失败由回调关闭租约，成功转交的租约由 Store 结算后关闭，不能提前释放。
     */
    @SuppressWarnings("PMD.CloseResource")
    private <T> CompletionStage<T> withLease(JaTool.ExecutionContext context, CancellationToken parent,
                                             Function<CancellationToken, CompletionStage<T>> action,
                                             BiFunction<T, WorkspaceWriteLeaseCoordinator.Lease, LeaseDisposition<T>> transform,
                                             T preDispatchFailure) {
        WorkspaceWriteLeaseCoordinator.Lease lease;
        try {
            lease = dependencies.writeLeases().acquire(context, parent);
        } catch (WorkspaceWriteLeaseCoordinator.LeaseFailure failure) {
            if (preDispatchFailure != null) {
                return CompletableFuture.completedFuture(preDispatchFailure);
            }
            return CompletableFuture.failedFuture(failure);
        }
        CompletionStage<T> stage;
        try {
            stage = nonNullStage(action.apply(lease.cancellation()), "leased Tool stage");
        } catch (RuntimeException | Error failure) {
            closeWithSuppression(lease, failure);
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        stage.whenComplete((value, failure) -> {
            Throwable terminalFailure = unwrap(failure);
            T transformed = null;
            boolean transferred = false;
            try {
                if (terminalFailure != null) {
                    throw new CompletionException(terminalFailure);
                }
                lease.verifyOwnership();
                LeaseDisposition<T> disposition = Objects.requireNonNull(transform.apply(
                        Objects.requireNonNull(value, "leased result"), lease), "lease disposition");
                transformed = Objects.requireNonNull(disposition.value(), "transformed leased result");
                transferred = disposition.transferred();
            } catch (RuntimeException | Error mappingFailure) {
                terminalFailure = unwrap(mappingFailure);
            }
            if (!transferred) {
                try {
                    lease.close();
                } catch (RuntimeException | Error releaseFailure) {
                    if (terminalFailure == null) {
                        terminalFailure = releaseFailure;
                    }
                    else {
                        terminalFailure.addSuppressed(releaseFailure);
                    }
                }
            }
            if (terminalFailure == null) {
                result.complete(transformed);
            }
            else {
                result.completeExceptionally(terminalFailure);
            }
        });
        return result;
    }

    /**
     * 只在同一 Ja adapter 内暴露 lease；Core 将该对象作为不透明 SettlementResource 传递。
     *
     * @param lease 租约
     */
    private record WorkspaceSettlement(WorkspaceWriteLeaseCoordinator.Lease lease)
            implements SettlementResource {
        /**
         * 关闭仍委托给 coordinator 的幂等释放动作。
         */
        private WorkspaceSettlement {
            Objects.requireNonNull(lease, "lease");
        }

        /**
         * Tool 事实提交或未知状态持久挂起后释放跨 Task 写 claim。
         */
        @Override
        public void close() {
            lease.close();
        }
    }

    /**
     * 表明写租约由成功结果交给提交方继续持有，或由适配器在只读核验后立即释放。
     *
     * @param value 当前值
     * @param transferred 资源所有权是否已转移
     */
    private record LeaseDisposition<T>(T value,
                                       boolean transferred) {
        /**
         * 拒绝空结局，避免租约所有权在异步边界丢失。
         */
        private LeaseDisposition {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 请求级 adapter 借用原编解码器和写租约 owner，不能复制服务破坏调用身份；集合独立冻结。
     *
     * @param contextFactory 上下文工厂
     * @param accessMode 访问模式
     * @param bindingCodec 绑定编解码器
     * @param writeLeases 写入租约列表
     * @param presentationCodec 展示信息编解码器
     * @param presentationSecrets 展示信息密钥集合
     * @param factContributors 参与工具结果结算的事实贡献者
     * @param origin 来源分类
     * @param collaborationMode 协作模式
     */
    public record Dependencies(JaTool.ExecutionContextFactory contextFactory,
                               AccessMode accessMode,
                               JaToolBindingCodec bindingCodec,
                               WorkspaceWriteLeaseCoordinator writeLeases,
                               ToolPresentationSerialization presentationCodec,
                               List<String> presentationSecrets,
                               List<JaToolFactContributor> factContributors,
                               TurnOrigin origin,
                               CollaborationMode collaborationMode) {
        /**
         * 冻结依赖列表并拒绝重复 contributor，确保一条业务事实只有一个已注册 owner。
         */
        public Dependencies {
            Objects.requireNonNull(contextFactory, "contextFactory");
            Objects.requireNonNull(accessMode, "accessMode");
            Objects.requireNonNull(bindingCodec, "bindingCodec");
            Objects.requireNonNull(writeLeases, "writeLeases");
            Objects.requireNonNull(presentationCodec, "presentationCodec");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(collaborationMode, "collaborationMode");
            presentationSecrets = List.copyOf(Objects.requireNonNull(presentationSecrets, "presentationSecrets"));
            factContributors = List.copyOf(Objects.requireNonNull(factContributors, "factContributors"));
            if (presentationSecrets.stream().anyMatch(Objects::isNull)
                || factContributors.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Ja adapter dependencies contain null");
            }
            Set<String> ids = new HashSet<>();
            for (JaToolFactContributor contributor : factContributors) {
                String id = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks.identifier(
                        contributor.id(), "Ja tool fact contributor id");
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("duplicate Ja tool fact contributor: " + id);
                }
            }
        }
    }
}
