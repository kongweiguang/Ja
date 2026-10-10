// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Ja 应用层的 Tool 端口；保存 Workspace、Plan 与审批语义，Core 只接收中性的调用和值对象。
 */
public interface JaTool {
    /**
     * 将适配器路由和本次安全声明固定为 Core 不透明 JSON。
     */
    private static JsonObject routeJson(ToolBindingDescriptor descriptor, ToolSideEffect sideEffect,
                                        WorkspaceMutationMode mutationMode, PlanAccess planAccess,
                                        ApprovalRequirement approvalRequirement) {
        JsonObject route = JsonObjects.builder().putNumber("schemaVersion", 1)
                .putText("routeKind", descriptor.routeKind().name())
                .putText("localName", descriptor.localName())
                .putText("serverId", descriptor.serverId())
                .putText("remoteName", descriptor.remoteName())
                .putText("schemaHash", descriptor.schemaHash())
                .putText("routeHash", descriptor.routeHash())
                .putText("sideEffect", sideEffect.name())
                .putText("workspaceMutationMode", mutationMode.name())
                .putText("planAccess", planAccess.name())
                .putText("approvalRequirement", approvalRequirement.name()).build();
        return route;
    }

    /**
     * 为 Builtin 和能力工具生成同一稳定本地路由身份。
     */
    static ToolBindingDescriptor builtinBindingDescriptor(
            ToolSpec spec, ToolSideEffect sideEffect, WorkspaceMutationMode mutationMode) {
        ToolSpec tool = Objects.requireNonNull(spec, "spec");
        ToolSideEffect effect = Objects.requireNonNull(sideEffect, "sideEffect");
        WorkspaceMutationMode mutation = Objects.requireNonNull(mutationMode, "mutationMode");
        String schemaHash = AgentTool.schemaFingerprint(tool);
        String routeHash = sha256(String.join("\0", "BUILTIN", "builtin", tool.name(), schemaHash,
                effect.name(), mutation.name()));
        return new ToolBindingDescriptor(RouteKind.BUILTIN, tool.name(), "builtin", tool.name(),
                schemaHash, routeHash);
    }

    /**
     * 只接受完整小写 SHA-256，避免恢复身份使用展示值或弱摘要。
     */
    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 共享内容和路由摘要所用的 JDK SHA-256 计算。
     */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 返回冻结给模型的工具名称与参数结构。
     */
    ToolSpec spec();

    /**
     * 未声明的工具按外部副作用处理，避免新能力静默扩大执行权限。
     */
    default ToolSideEffect sideEffect() {
        return ToolSideEffect.EXTERNAL;
    }

    /**
     * 允许一个固定入口根据已校验的 action 收窄副作用，不按工具名称推测。
     */
    default ToolSideEffect sideEffect(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Objects.requireNonNull(sideEffect(), "sideEffect");
    }

    /**
     * 默认无法证明不会改动 Workspace；只有实现能证明时才声明更窄范围。
     */
    default WorkspaceMutationMode workspaceMutationMode() {
        return WorkspaceMutationMode.UNOBSERVABLE;
    }

    /**
     * 允许动态入口按已解析的动作声明不同 Workspace 可观察性。
     */
    default WorkspaceMutationMode workspaceMutationMode(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Objects.requireNonNull(workspaceMutationMode(), "workspaceMutationMode");
    }

    /**
     * 默认不允许在 Plan 的只读规划阶段暴露，避免应用规则由 Tool 名称推断。
     */
    default PlanAccess planAccess() {
        return PlanAccess.DISALLOWED;
    }

    /**
     * 允许同一个入口依据已校验 action 声明只读或受信内部 Plan 能力。
     */
    default PlanAccess planAccess(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Objects.requireNonNull(planAccess(), "planAccess");
    }

    /**
     * 默认要求用户审批；受信内部操作必须由具体 Tool 显式声明。
     */
    default ApprovalRequirement approvalRequirement() {
        return ApprovalRequirement.USER_REQUIRED;
    }

    /**
     * 允许状态查询等固定动作明确免于外部副作用审批。
     */
    default ApprovalRequirement approvalRequirement(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Objects.requireNonNull(approvalRequirement(), "approvalRequirement");
    }

    /**
     * MCP 等动态路由可显式证明本地只读动作，供 Ja 的规划准入复核而非 Core 解释业务字段。
     */
    default boolean isTrustedLocalReadAction(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return false;
    }

    /**
     * 声明动态路由工具会在目标解析后重新校验参数 Schema。
     */
    default boolean validatesResolvedArguments() {
        return false;
    }

    /**
     * 在持久调用意图前返回可安全展示的确定性参数错误。
     */
    default Optional<InvocationValidationFailure> validationFailure(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Optional.empty();
    }

    /**
     * 为固定本地工具生成稳定绑定；动态路由应覆写此方法并冻结目标身份。
     */
    default ToolBindingDescriptor bindingDescriptor() {
        return builtinBindingDescriptor(spec(), sideEffect(), workspaceMutationMode());
    }

    /**
     * 允许 MCP 等动态入口将本次精确远端目标绑定到持久调用记录。
     */
    default ToolBindingDescriptor bindingDescriptor(AgentTool.Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return bindingDescriptor();
    }

    /**
     * 把 Ja 路由和授权语义写入 Core 可持久化的中性绑定，不让 Core 解析 Ja 字段。
     */
    default ToolExecutionBinding executionBinding(AgentTool.Invocation invocation,
                                                  AgentTool.ToolBindingContext context) {
        AgentTool.Invocation call = Objects.requireNonNull(invocation, "invocation");
        AgentTool.ToolBindingContext binding = Objects.requireNonNull(context, "context");
        ToolSpec toolSpec = Objects.requireNonNull(spec(), "tool spec");
        if (!toolSpec.name().equals(call.toolName())) {
            throw new IllegalArgumentException("Tool binding name mismatch");
        }
        ToolBindingDescriptor descriptor = Objects.requireNonNull(bindingDescriptor(call), "bindingDescriptor");
        JsonObject route = routeJson(descriptor, sideEffect(call), workspaceMutationMode(call),
                planAccess(call), approvalRequirement(call));
        return new ToolExecutionBinding(toolSpec.name(), descriptor.schemaHash(), binding.catalogFingerprint(),
                binding.permissionFingerprint(), route);
    }

    /**
     * 在持久调用意图前产生 Ja 业务事实；实现必须纯构造，副作用只能在 execute 中发生。
     */
    default List<AgentTool.ExtensionFact> preparedFacts(
            AgentTool.Invocation invocation, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在核心 Started 意图提交时追加 Ja 执行阶段事实；该钩子不可启动实际工具副作用。
     */
    default List<AgentTool.ExtensionFact> startedFacts(
            AgentTool.Invocation invocation, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在外部动作前给 Ja 提供精确恢复证据；证据由产生它的 Tool 自行解释。
     */
    default Optional<AgentTool.RecoveryEvidence> prepareRecoveryEvidence(
            AgentTool.Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return Optional.empty();
    }

    /**
     * 核实调用键对应的真实业务结果；无法证明时保持 Unknown，不重放副作用。
     */
    default CompletionStage<RecoveryResolution> reconcile(
            AgentTool.Invocation invocation, ExecutionContext context,
            Optional<AgentTool.RecoveryEvidence> evidence, CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return java.util.concurrent.CompletableFuture.completedFuture(
                new RecoveryResolution.Unknown("TOOL_OUTCOME_UNVERIFIABLE"));
    }

    /**
     * 执行一个 Ja 工具调用，返回结果及供 Adapter 原子保存的 Ja 命名空间事实。
     */
    CompletionStage<ToolResult> execute(
            AgentTool.Invocation invocation, ExecutionContext context, CancellationToken cancellationToken);

    /**
     * Ja 工具目录据此限制 Plan 规划阶段能看见和调用的应用能力。
     */
    enum PlanAccess {
        /**
         * 规划阶段不暴露此工具，避免模型借规划入口执行应用动作。
         */
        DISALLOWED,
        /**
         * 只允许读取规划所需信息，不得提交 Plan 或 Plan 状态变更。
         */
        READ_ONLY,
        /**
         * 允许受信 Ja 内部规划流程执行明确授权的状态变更。
         */
        INTERNAL_MUTATION
    }

    /**
     * 审批要求由 Ja 的策略和调用者身份执行，不从工具名推断。
     */
    enum ApprovalRequirement {
        /**
         * 执行前必须获得与本次冻结参数绑定的用户批准。
         */
        USER_REQUIRED,
        /**
         * 仅用于可验证的 Ja 内部动作，不能由外部调用者自选。
         */
        TRUSTED_INTERNAL
    }

    /**
     * Workspace 变更可观察性决定 Ja 能否从本次真实结果生成可靠 ChangeSet。
     */
    enum WorkspaceMutationMode {
        /**
         * 工具证明本次调用不会写入 Workspace。
         */
        NONE,
        /**
         * 工具会提供完整文本前后像，可核验后形成精确变更收据。
         */
        EXACT_TEXT,
        /**
         * 工具可能写 Workspace 但不能提供完整收据，Ja 必须按不完整变化处理。
         */
        UNOBSERVABLE
    }

    /**
     * 路由身份区分固定本地实现与请求内冻结的 MCP 远端目标。
     */
    enum RouteKind {
        /**
         * Ja 内置工具，目标身份由本地工具声明冻结。
         */
        BUILTIN,
        /**
         * MCP 工具，服务与远端名称必须在准备阶段固定。
         */
        MCP
    }

    /**
     * 写入后无法取得完整收据时保留真实失败原因，不生成不完整 ChangeSet。
     */
    enum MutationObservationFailure {
        /**
         * 目标路径不在本次冻结的 Workspace 边界内。
         */
        OUTSIDE_WORKSPACE,
        /**
         * 文件前后状态读取或一致性校验失败。
         */
        CAPTURE_FAILED
    }

    /**
     * 用实际 Turn 请求和数据库身份补全 Core 传来的每次调用身份。
     */
    @FunctionalInterface
    interface ExecutionContextFactory {
        /**
         * 只能把冻结的 Turn 请求与持久 Plan 执行身份拼合，不能从 Core attributes 猜测 Ja 配置。
         */
        ExecutionContext create(AgentTool.Invocation invocation, AgentTool.ExecutionContext coreContext);
    }

    /**
     * Ja 对恢复动作的闭集结论；Core 只收到映射后的中性类型。
     */
    sealed interface RecoveryResolution permits RecoveryResolution.Applied,
            RecoveryResolution.NotApplied, RecoveryResolution.Unknown {
        /**
         * 已执行结果可以重建，必须随结果一并带回扩展事实。
         *
         * @param result 处理结果
         * @param verificationFingerprint 副作用核验证据的指纹
         */
        record Applied(ToolResult result,
                       String verificationFingerprint) implements RecoveryResolution {
            /**
             * 核验指纹绑定恢复快照，防止无证据地声称外部动作已完成。
             */
            public Applied {
                Objects.requireNonNull(result, "result");
                verificationFingerprint = fingerprint(verificationFingerprint, "verificationFingerprint");
            }
        }

        /**
         * 只有工具明确核实未发生副作用时才允许 Core 安全重派。
         *
         * @param verificationFingerprint 副作用核验证据的指纹
         */
        record NotApplied(String verificationFingerprint) implements RecoveryResolution {
            /**
             * 固定未执行证据的身份，供恢复事务审计。
             */
            public NotApplied {
                verificationFingerprint = fingerprint(verificationFingerprint, "verificationFingerprint");
            }
        }

        /**
         * 无法核实时保持挂起，避免把未知状态转为重放。
         *
         * @param reasonCode 原因代码
         */
        record Unknown(String reasonCode) implements RecoveryResolution {
            /**
             * 只允许稳定错误码进入状态，远端异常正文留在宿主日志。
             */
            public Unknown {
                reasonCode = ContractChecks.identifier(reasonCode, "reasonCode");
            }
        }
    }

    /**
     * 跨业务工具目录计算摘要所需的静态描述与物化函数；它不是注册表，不能排序、调度或创建新能力。
     *
     * @param spec 配置规格
     * @param sideEffect 工具副作用分类
     * @param workspaceMutationMode 工作区状态变更模式
     * @param bindingDescriptor 绑定描述信息
     * @param planAccess Plan 访问约束
     * @param approvalRequirement 审批要求
     * @param binder 绑定器
     */
    record Definition(ToolSpec spec,
                      ToolSideEffect sideEffect,
                      WorkspaceMutationMode workspaceMutationMode,
                      ToolBindingDescriptor bindingDescriptor,
                      PlanAccess planAccess,
                      ApprovalRequirement approvalRequirement,
                      Function<JaToolCatalogIdentity, JaTool> binder) {
        /**
         * 校验目录描述完整并与本地路由名一致，避免摘要和稍后绑定使用不同身份。
         */
        public Definition {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(workspaceMutationMode, "workspaceMutationMode");
            Objects.requireNonNull(bindingDescriptor, "bindingDescriptor");
            Objects.requireNonNull(planAccess, "planAccess");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            Objects.requireNonNull(binder, "binder");
            if (!spec.name().equals(bindingDescriptor.localName())) {
                throw new IllegalArgumentException("Tool definition binding name mismatch");
            }
        }

        /**
         * 以已经固定的目录摘要物化单个业务工具，并核对安全元数据；可见性由 Core middleware 单独收窄。
         */
        public JaTool materialize(JaToolCatalogIdentity identity) {
            JaTool tool = Objects.requireNonNull(binder.apply(Objects.requireNonNull(identity, "identity")),
                    "bound Tool");
            AgentTool.Invocation invocation = new AgentTool.Invocation("call_" + "0".repeat(64), spec.name(),
                    JsonObjects.builder().build(), 0);
            if (!spec.equals(tool.spec())
                || sideEffect != tool.sideEffect(invocation)
                || workspaceMutationMode != tool.workspaceMutationMode(invocation)
                || planAccess != tool.planAccess(invocation)
                || approvalRequirement != tool.approvalRequirement(invocation)
                || !bindingDescriptor.equals(tool.bindingDescriptor(invocation))) {
                throw new IllegalStateException("Ja Tool definition changed during request binding");
            }
            return tool;
        }
    }

    /**
     * 冻结 Ja 请求级完整工具目录、可见子集与提示，供不同能力工厂共享同一安全校验。
     *
     * @param definitions 定义集合
     * @param visibleNames 可见名称集合
     * @param promptFragment 参与提示词组装的文本片段
     */
    record Selection(List<Definition> definitions,
                     Set<String> visibleNames,
                     String promptFragment) {
        /**
         * 复制调用方集合并校验唯一名称与可见子集，避免后续变更目录或暴露未定义工具。
         */
        public Selection {
            definitions = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
            visibleNames = Set.copyOf(Objects.requireNonNull(visibleNames, "visibleNames"));
            promptFragment = promptFragment == null ? "" : promptFragment;
            Set<String> names = definitions.stream().map(Definition::spec)
                    .map(ToolSpec::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (names.size() != definitions.size()) {
                throw new IllegalArgumentException("duplicate Ja Tool definition");
            }
            if (!names.containsAll(visibleNames)) {
                throw new IllegalArgumentException("visible Ja Tool is undefined");
            }
        }

        /**
         * 无 Turn 的请求使用不可变空目录，不伪造或读取工具身份。
         */
        public static Selection empty() {
            return new Selection(List.of(), Set.of(), "");
        }
    }

    /**
     * Ja 工具实际执行所需的应用上下文；Path 和业务身份仅留在 app-server，不写进 Core 状态。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param callId 模型生成的工具调用标识
     * @param callKey 按执行身份派生的工具幂等键
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param workspaceRoot 工作区根目录
     * @param accessMode 访问模式
     * @param configGeneration 冻结的配置代际标识
     * @param deadline 截止时间约束
     * @param workspaceId 工作区稳定标识
     * @param planRevisionId Plan 修订版本标识
     * @param runId 运行标识
     * @param origin 来源分类
     */
    record ExecutionContext(AgentIdentity identity,
                            String callId,
                            String callKey,
                            String threadId,
                            String turnId,
                            Path workspaceRoot,
                            AccessMode accessMode,
                            String configGeneration,
                            java.time.Instant deadline,
                            String workspaceId,
                            String planRevisionId,
                            String runId,
                            TurnOrigin origin) {
        /**
         * 调用键和重复的 Thread/Turn 字段必须指向同一身份；Plan 来源只接受完整冻结的 Run。
         */
        public ExecutionContext {
            Objects.requireNonNull(identity, "identity");
            callId = io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId.require(callId);
            if (!identity.toolCallKey(callId).equals(callKey))
                {
                    throw new IllegalArgumentException("call key identity mismatch");
                }
            threadId = ContractChecks.identifier(threadId, "threadId");
            turnId = ContractChecks.identifier(turnId, "turnId");
            if (!identity.threadId().equals(threadId) || !identity.turnId().equals(turnId)) {
                throw new IllegalArgumentException("tool execution identity mismatch");
            }
            workspaceRoot = ContractChecks.absolutePath(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(accessMode, "accessMode");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            Objects.requireNonNull(deadline, "deadline");
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            if (!workspaceId.startsWith("ws_")) {
                throw new IllegalArgumentException("invalid workspaceId");
            }
            if (planRevisionId != null) {
                planRevisionId = ContractChecks.identifier(planRevisionId, "planRevisionId");
            }
            if (runId != null) {
                runId = ContractChecks.identifier(runId, "runId");
            }
            origin = Objects.requireNonNull(origin, "origin");
            switch (origin) {
                case PLAN_EXECUTION -> {
                    if (runId == null || planRevisionId == null) {
                        throw new IllegalArgumentException("Plan execution identity is required");
                    }
                }
                case USER, USER_CONTINUATION, CHILD_TASK -> {
                    if (runId != null || planRevisionId != null) {
                        throw new IllegalArgumentException("ordinary Turn must not carry Plan Run identity");
                    }
                }
            }
        }
    }

    /**
     * 应用侧结果先保留收据和恢复事实，随后由适配器映射为同事务提交的 Ja 扩展事实。
     *
     * @param outcome 执行结果
     * @param content 内容
     * @param structuredContent 结构化结果内容（可选）
     * @param errorCode 错误代码
     * @param extensionFacts 扩展事实列表
     * @param mutationReceipt 状态变更回执（可选）
     * @param mutationObservationFailure 状态变更观察结果失败（可选）
     * @param artifactContent 需要独立持久化的完整产物正文（可选）
     * @param media 与成功结果绑定的受管媒体引用
     */
    record ToolResult(ToolOutcome outcome,
                      String content,
                      Optional<io.github.kongweiguang.ja.agent.domain.json.JsonValue> structuredContent,
                      String errorCode,
                      List<AgentTool.ExtensionFact> extensionFacts,
                      Optional<MutationReceipt> mutationReceipt,
                      Optional<MutationObservationFailure> mutationObservationFailure,
                      Optional<String> artifactContent,
                      List<ToolMediaReference> media) {
        /**
         * 普通 Tool 没有独立产物通道；Review 等完整报告通过显式 builder 与摘要分开保存。
         */
        public ToolResult(ToolOutcome outcome, String content,
                          Optional<io.github.kongweiguang.ja.agent.domain.json.JsonValue> structuredContent,
                          String errorCode, List<AgentTool.ExtensionFact> extensionFacts,
                          Optional<MutationReceipt> mutationReceipt,
                          Optional<MutationObservationFailure> mutationObservationFailure) {
            this(outcome, content, structuredContent, errorCode, extensionFacts, mutationReceipt,
                    mutationObservationFailure, Optional.empty(), List.of());
        }

        /**
         * 保留媒体能力引入前的显式产物构造入口；普通结果不会隐式取得图片引用。
         */
        public ToolResult(ToolOutcome outcome, String content,
                          Optional<io.github.kongweiguang.ja.agent.domain.json.JsonValue> structuredContent,
                          String errorCode, List<AgentTool.ExtensionFact> extensionFacts,
                          Optional<MutationReceipt> mutationReceipt,
                          Optional<MutationObservationFailure> mutationObservationFailure,
                          Optional<String> artifactContent) {
            this(outcome, content, structuredContent, errorCode, extensionFacts, mutationReceipt,
                    mutationObservationFailure, artifactContent, List.of());
        }

        /**
         * 冻结可持久化结果，确保副作用收据和失败观察不会同时声称同一动作状态。
         */
        public ToolResult {
            Objects.requireNonNull(outcome, "outcome");
            content = ContractChecks.text(content == null ? "" : content, "content", Integer.MAX_VALUE, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            if ((outcome == ToolOutcome.SUCCEEDED) != (errorCode == null)) {
                throw new IllegalArgumentException("tool outcome and error code are inconsistent");
            }
            if (errorCode != null) {
                errorCode = ContractChecks.identifier(errorCode, "errorCode");
            }
            extensionFacts = List.copyOf(Objects.requireNonNull(extensionFacts, "extensionFacts"));
            mutationReceipt = Objects.requireNonNull(mutationReceipt, "mutationReceipt");
            mutationObservationFailure = Objects.requireNonNull(mutationObservationFailure, "mutationObservationFailure");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            media = List.copyOf(Objects.requireNonNull(media, "media"));
            artifactContent.ifPresent(value -> ContractChecks.text(value, "artifactContent", Integer.MAX_VALUE, true));
            if (mutationReceipt.isPresent() && mutationObservationFailure.isPresent()) {
                throw new IllegalArgumentException("mutation result cannot contain receipt and failure");
            }
            if (media.size() > 10 || outcome != ToolOutcome.SUCCEEDED && !media.isEmpty()
                || media.stream().map(ToolMediaReference::attachmentId).distinct().count() != media.size()) {
                throw new IllegalArgumentException("invalid Ja Tool result media");
            }
            media.forEach(value -> Objects.requireNonNull(value, "media reference"));
        }

        /**
         * 无 app 侧收据的普通工具结果使用精简构造器，避免伪造扩展事实。
         */
        public ToolResult(ToolOutcome outcome, String content,
                          Optional<io.github.kongweiguang.ja.agent.domain.json.JsonValue> structuredContent,
                          String errorCode) {
            this(outcome, content, structuredContent, errorCode, List.of(), Optional.empty(), Optional.empty());
        }

        /**
         * 复制结果并保留模型可见摘要；完整文本仅由 JaAgentToolAdapter 写入 artifact 事实。
         */
        public ToolResult withArtifactContent(String value) {
            return new ToolResult(outcome, content, structuredContent, errorCode, extensionFacts,
                    mutationReceipt, mutationObservationFailure,
                    Optional.of(ContractChecks.text(value, "artifactContent", Integer.MAX_VALUE, true)), media);
        }

        /**
         * 将受管图片引用与同一个成功 Tool 结果冻结，避免媒体授权脱离对应的结果事实。
         */
        public ToolResult withMedia(List<ToolMediaReference> values) {
            return new ToolResult(outcome, content, structuredContent, errorCode, extensionFacts,
                    mutationReceipt, mutationObservationFailure, artifactContent, values);
        }

        /**
         * 日志只显示结果类别和长度，不暴露正文、路由或 Workspace 路径。
         */
        @Override
        public String toString() {
            return "JaToolResult[outcome=" + outcome + ", contentLength=" + content.length()
                   + ", structuredContent=" + structuredContent.isPresent()
                   + ", extensionFactCount=" + extensionFacts.size()
                   + ", artifactContent=" + artifactContent.isPresent()
                   + ", mediaCount=" + media.size()
                   + ", mutationReceipt=" + mutationReceipt.isPresent()
                   + ", mutationObservationFailure=" + mutationObservationFailure.isPresent()
                   + ", errorCode=" + errorCode + "]";
        }
    }

    /**
     * Provider 批次持久化的路由身份，MCP 目标字段不能由调用时工具名重算。
     *
     * @param routeKind 路由类型
     * @param localName 本地名称
     * @param serverId 服务端标识
     * @param remoteName 远程名称
     * @param schemaHash 数据结构摘要
     * @param routeHash 路由内容摘要
     */
    record ToolBindingDescriptor(RouteKind routeKind,
                                 String localName,
                                 String serverId,
                                 String remoteName,
                                 String schemaHash,
                                 String routeHash) {
        /**
         * 校验可跨重启比较的路由字段，拒绝弱 hash、控制字符和不完整远端目标。
         */
        public ToolBindingDescriptor {
            Objects.requireNonNull(routeKind, "routeKind");
            localName = ContractChecks.identifier(localName, "localName");
            serverId = bounded(serverId, "serverId", 256);
            remoteName = bounded(remoteName, "remoteName", 512);
            schemaHash = fingerprint(schemaHash, "schemaHash");
            routeHash = fingerprint(routeHash, "routeHash");
            if (routeKind == RouteKind.BUILTIN && !"builtin".equals(serverId)) {
                throw new IllegalArgumentException("Builtin binding requires stable server sentinel");
            }
        }

        /**
         * MCP 远端名称允许协议字符，但拒绝空白、控制字符和无界内容。
         */
        private static String bounded(String value, String field, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum
                || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        }
    }

    /**
     * 参数预检失败只带安全错误，不把未校验参数或异常内容送回模型。
     *
     * @param code 结果或诊断代码
     * @param message 消息或诊断说明
     */
    record InvocationValidationFailure(String code,
                                       String message) {
        /**
         * 固定错误码闭集外形与诊断长度，避免预检绕过执行结果边界。
         */
        public InvocationValidationFailure {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("invalid invocation failure code");
            }
            message = ContractChecks.text(message, "message", 1_024, false);
        }
    }

    /**
     * 精确工作区修改收据仅用于 Ja 侧投影，摘要校验后再进入同一执行事务。
     *
     * @param path 路径
     * @param beforeExists 变更前是否存在
     * @param afterExists 变更后是否存在
     * @param beforeText 变更前的文本
     * @param afterText 变更后的文本
     * @param beforeBytes 变更前内容的字节数
     * @param afterBytes 变更后内容的字节数
     * @param beforeSha256 变更前内容的 SHA-256 摘要
     * @param afterSha256 变更后内容的 SHA-256 摘要
     * @param confinedWorkspaceRoot 用于目录边界检查的工作区根目录
     * @param confinedRelativePath 通过目录边界检查的相对路径
     */
    record MutationReceipt(Path path,
                           boolean beforeExists,
                           boolean afterExists,
                           String beforeText,
                           String afterText,
                           long beforeBytes,
                           long afterBytes,
                           String beforeSha256,
                           String afterSha256,
                           Path confinedWorkspaceRoot,
                           String confinedRelativePath) {
        /**
         * 路径、UTF-8 长度、摘要与 confinement 必须彼此相符，防止 tracker 接受伪造变化。
         */
        public MutationReceipt {
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            beforeText = beforeText == null ? "" : beforeText;
            afterText = afterText == null ? "" : afterText;
            if (!beforeExists && (!beforeText.isEmpty() || beforeBytes != 0)) {
                throw new IllegalArgumentException("missing preimage must be empty");
            }
            if (!afterExists && (!afterText.isEmpty() || afterBytes != 0)) {
                throw new IllegalArgumentException("missing postimage must be empty");
            }
            if (beforeBytes != utf8Length(beforeText) || afterBytes != utf8Length(afterText)
                || !sha256(beforeText).equals(beforeSha256) || !sha256(afterText).equals(afterSha256)) {
                throw new IllegalArgumentException("mutation receipt integrity mismatch");
            }
            if ((confinedWorkspaceRoot == null) != (confinedRelativePath == null)) {
                throw new IllegalArgumentException("mutation receipt confinement mismatch");
            }
            if (confinedWorkspaceRoot != null) {
                confinedWorkspaceRoot = confinedWorkspaceRoot.toAbsolutePath().normalize();
                confinedRelativePath = confinedRelative(confinedRelativePath);
                if (!confinedWorkspaceRoot.resolve(confinedRelativePath).normalize().equals(path)) {
                    throw new IllegalArgumentException("mutation receipt path identity mismatch");
                }
            }
        }

        /**
         * 以固定 UTF-8 长度与 SHA-256 构造收据，调用方不重复实现完整性规则。
         */
        public static MutationReceipt of(Path path, boolean beforeExists, String beforeText,
                                         boolean afterExists, String afterText) {
            return create(path, beforeExists, beforeText, afterExists, afterText, null, null);
        }

        /**
         * 只有经过 WorkspaceBoundary 物理检查的调用点才能附加 confinement 证明。
         */
        public static MutationReceipt confined(Path workspaceRoot, String relativePath, Path path,
                                               boolean beforeExists, String beforeText,
                                               boolean afterExists, String afterText) {
            return create(path, beforeExists, beforeText, afterExists, afterText, workspaceRoot, relativePath);
        }

        /**
         * 两个工厂共用规范 UTF-8 摘要流程，防止有无 confinement 的收据校验不同。
         */
        private static MutationReceipt create(Path path, boolean beforeExists, String beforeText,
                                              boolean afterExists, String afterText,
                                              Path workspaceRoot, String relativePath) {
            String before = beforeText == null ? "" : beforeText;
            String after = afterText == null ? "" : afterText;
            return new MutationReceipt(path, beforeExists, afterExists, before, after,
                    utf8Length(before), utf8Length(after), sha256(before), sha256(after),
                    workspaceRoot, relativePath);
        }

        /**
         * 仅允许规范且可进入 ChangeSet 的相对路径，避免提交收据后才因控制字符阻断终态。
         */
        private static String confinedRelative(String value) {
            if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl)
                || value.indexOf('\\') >= 0 || value.startsWith("/")
                || value.matches("(?i)^[a-z]:.*")
                || java.util.Arrays.asList(value.split("/", -1)).contains("..")) {
                throw new IllegalArgumentException("invalid mutation receipt relative path");
            }
            return value;
        }

        /**
         * 固定 UTF-8 计量，避免 Windows 本机编码影响文件修改事实。
         */
        private static long utf8Length(String value) {
            return value.getBytes(StandardCharsets.UTF_8).length;
        }

        /**
         * 内容指纹使用 JDK 固定 SHA-256；原正文不会由 toString 输出。
         */
        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }

        /**
         * 默认日志只披露字节数，不泄露本地路径、文件正文或内容摘要。
         */
        @Override
        public String toString() {
            return "MutationReceipt[beforeExists=" + beforeExists + ", afterExists=" + afterExists
                   + ", beforeBytes=" + beforeBytes + ", afterBytes=" + afterBytes + "]";
        }
    }
}
