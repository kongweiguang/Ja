// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 有序的四个执行拦截点；用不同输入输出类型分开请求调整、工具准入、结果投影与终态决策。
 */
public interface AgentMiddleware {
    /**
     * 返回稳定的中间件标识，注册表据此在 Engine 创建前拒绝重复策略。
     */
    String id();

    /**
     * 声明持久状态结构版本；Registry 只读取一次以冻结恢复身份，升级不兼容时不会静默清空。
     */
    default int stateSchemaVersion() {
        return 1;
    }

    /**
     * 为本次请求追加指令或上下文并收窄工具可见集；历史保持只读，避免扩展伪造已发生事实。
     */
    default ExtensionDecision<RequestPatch> beforeRequest(RequestContext context) {
        Objects.requireNonNull(context, "context");
        return ExtensionDecision.unchanged(RequestPatch.unchanged(), context.extensionState());
    }

    /**
     * 在工具副作用前拒绝、挂起或调整参数；调整结果仍须由内核重校验、重授权并持久化。
     */
    default ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
        Objects.requireNonNull(context, "context");
        return ExtensionDecision.unchanged(
                ToolDecision.proceed(context.invocation().arguments()), context.extensionState());
    }

    /**
     * 只调整模型可见的结果文本；真实成功或失败仍由工具结果记录持有，扩展不能改写副作用事实。
     */
    default ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
        Objects.requireNonNull(context, "context");
        return ExtensionDecision.unchanged(
                new ToolResultProjection(context.content(), context.structuredContent(), context.artifactContent()),
                context.extensionState());
    }

    /**
     * 完成候选的 statePatch 随 Continue/Await/Complete 提交；其 facts 仅在所有中间件都接受 Complete 时持久化。
     */
    default ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext context) {
        Objects.requireNonNull(context, "context");
        return ExtensionDecision.unchanged(CompletionDecision.complete(), context.extensionState());
    }

    /**
     * 封闭工具决策集合；内核必须显式处理继续、拒绝和等待三种不同副作用边界。
     */
    sealed interface ToolDecision permits ToolProceed, ToolReject, ToolWait {
        /**
         * 使用调整后的参数继续，但内核仍需重新执行 Schema 校验和授权后才可保存调用意图。
         */
        static ToolDecision proceed(JsonObject arguments) {
            return new ToolProceed(arguments);
        }

        /**
         * 在工具尚未执行时返回结构化拒绝，供模型纠正参数或改选能力。
         */
        static ToolDecision reject(String code, String message) {
            return new ToolReject(code, message);
        }

        /**
         * 要求持久化等待问题并挂起执行，由宿主补充输入后从安全点恢复。
         */
        static ToolDecision waitFor(WaitRequest request) {
            return new ToolWait(request);
        }
    }

    /**
     * 封闭终态决策集合；所有选择都在唯一终态提交前由内核落实。
     */
    sealed interface CompletionDecision permits Complete, Continue, Await {
        /**
         * 接受当前结果并进入终态事务提交。
         */
        static CompletionDecision complete() {
            return new Complete();
        }

        /**
         * 为同一执行追加持久指令并继续模型循环，不创建新的用户任务。
         */
        static CompletionDecision continueWith(String instruction) {
            return new Continue(instruction);
        }

        /**
         * 暂缓终态提交并持久化等待请求，回答后沿同一执行游标恢复。
         */
        static CompletionDecision await(WaitRequest request) {
            return new Await(request);
        }
    }

    /**
     * Engine 将状态与 facts 按命名空间原子提交；完成候选 facts 仅在全部策略接受 Complete 时生效。
     *
     * @param decision 决策
     * @param statePatch 状态增量修改
     * @param facts 事实列表
     */
    record ExtensionDecision<T>(T decision,
                                ExtensionStatePatch statePatch,
                                List<MiddlewareFact> facts) {
        /**
         * 冻结回调产物，防止异步提交前调用方继续改写状态或事实列表。
         */
        public ExtensionDecision {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(statePatch, "statePatch");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            if (facts.size() > 256) {
                throw new IllegalArgumentException("too many middleware facts");
            }
            facts.forEach(fact -> Objects.requireNonNull(fact, "middleware fact"));
        }

        /**
         * 为无持久改动的策略返回带版本校验的空 patch，仍统一经过 Engine 的提交检查。
         */
        public static <T> ExtensionDecision<T> unchanged(T decision, ExtensionStateView current) {
            return new ExtensionDecision<>(decision, ExtensionStatePatch.unchanged(current), List.of());
        }
    }

    /**
     * 当前策略命名空间的持久状态快照；只读 JSON 值与单调版本可安全传入每个拦截点。
     *
     * @param namespace 命名空间
     * @param revision 当前修订版本
     * @param schemaVersion 数据结构版本
     * @param value 当前值
     */
    record ExtensionStateView(String namespace,
                              long revision,
                              int schemaVersion,
                              JsonObject value) {
        /**
         * 拒绝负修订或未知 schema，使持久恢复不从缺失/过期状态推测一个空状态。
         */
        public ExtensionStateView {
            namespace = ContractChecks.identifier(namespace, "middleware namespace");
            if (revision < 0) {
                throw new IllegalArgumentException("negative middleware state revision");
            }
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("invalid middleware state schema version");
            }
            Objects.requireNonNull(value, "value");
            if (value.members().size() > ExtensionStatePatch.MAX_KEYS_PER_OPERATION) {
                throw new IllegalArgumentException("middleware state exceeds key limit");
            }
        }

        /**
         * 为尚无执行事实的命名空间构造版本零快照，恢复时只在确实没有历史状态记录时调用。
         */
        public static ExtensionStateView empty(String namespace, int schemaVersion) {
            return new ExtensionStateView(namespace, 0, schemaVersion, JsonObject.empty());
        }
    }

    /**
     * 单一命名空间的受版本保护顶层 patch；不同中间件不能通过 patch 写入彼此状态。
     *
     * @param expectedRevision 提交时预期的修订版本
     * @param put 写入项
     * @param remove 移除项
     */
    record ExtensionStatePatch(long expectedRevision,
                               Map<String, JsonValue> put,
                               Set<String> remove) {
        private static final int MAX_KEYS_PER_OPERATION = 1_024;

        /**
         * 复制并校验成员更新集合，禁止同一键同时写入和删除或使用 Java null。
         */
        public ExtensionStatePatch {
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("negative expected middleware state revision");
            }
            Objects.requireNonNull(put, "put");
            Objects.requireNonNull(remove, "remove");
            if (put.size() > MAX_KEYS_PER_OPERATION || remove.size() > MAX_KEYS_PER_OPERATION) {
                throw new IllegalArgumentException("middleware state patch exceeds key limit");
            }
            LinkedHashMap<String, JsonValue> checkedPut = new LinkedHashMap<>();
            put.forEach((key, value) -> checkedPut.put(stateKey(key), Objects.requireNonNull(value, "state value")));
            LinkedHashSet<String> checkedRemove = new LinkedHashSet<>();
            remove.forEach(key -> checkedRemove.add(stateKey(key)));
            if (checkedPut.keySet().stream().anyMatch(checkedRemove::contains)) {
                throw new IllegalArgumentException("middleware state patch writes and removes the same key");
            }
            put = Collections.unmodifiableMap(checkedPut);
            remove = Collections.unmodifiableSet(checkedRemove);
        }

        /**
         * 构造不改变值的 patch，但仍固定基础 revision 供提交端检查并拒绝过期策略结果。
         */
        public static ExtensionStatePatch unchanged(ExtensionStateView current) {
            return new ExtensionStatePatch(Objects.requireNonNull(current, "current").revision(), Map.of(), Set.of());
        }

        /**
         * 计算最小顶层差异，避免相同状态重复写入恢复日志并无谓增加版本。
         */
        public static ExtensionStatePatch replace(ExtensionStateView current, JsonObject replacement) {
            ExtensionStateView snapshot = Objects.requireNonNull(current, "current");
            JsonObject next = Objects.requireNonNull(replacement, "replacement");
            LinkedHashMap<String, JsonValue> put = new LinkedHashMap<>();
            next.members().forEach((key, value) -> {
                if (!Objects.equals(snapshot.value().get(key), value)) {
                    put.put(key, value);
                }
            });
            LinkedHashSet<String> remove = new LinkedHashSet<>(snapshot.value().members().keySet());
            remove.removeAll(next.members().keySet());
            return new ExtensionStatePatch(snapshot.revision(), put, remove);
        }

        /**
         * 校验持久 JSON 对象字段名，避免空键以及超长键进入存储 schema。
         */
        private static String stateKey(String value) {
            return ContractChecks.text(value, "middleware state key", 256, false);
        }

        /**
         * 只对与快照同 revision 的命名空间状态应用 patch，调用者须把结果纳入同一次 Store 提交。
         */
        public JsonObject apply(ExtensionStateView current) {
            ExtensionStateView snapshot = Objects.requireNonNull(current, "current");
            if (snapshot.revision() != expectedRevision) {
                throw new IllegalStateException("stale middleware state patch");
            }
            if (!isEmpty() && snapshot.revision() == Long.MAX_VALUE) {
                throw new IllegalStateException("middleware state revision is exhausted");
            }
            LinkedHashMap<String, JsonValue> next = new LinkedHashMap<>(snapshot.value().members());
            remove.forEach(next::remove);
            next.putAll(put);
            if (next.size() > MAX_KEYS_PER_OPERATION) {
                throw new IllegalArgumentException("middleware state exceeds key limit");
            }
            return new JsonObject(next);
        }

        /**
         * 两组操作都为空时无需持久化新状态记录或推进 namespace revision。
         */
        public boolean isEmpty() {
            return put.isEmpty() && remove.isEmpty();
        }
    }

    /**
     * 中间件生成的同事务事实；namespace 由 Registry 绑定，策略不能冒充其它扩展的事实归属。
     *
     * @param type 类型标识
     * @param schemaVersion 数据结构版本
     * @param payload 负载
     */
    record MiddlewareFact(String type,
                          int schemaVersion,
                          JsonObject payload) {
        /**
         * 限定事实类型与版本，并固定不可变对象 payload，确保重启后可由同一 owner 解码。
         */
        public MiddlewareFact {
            type = ContractChecks.identifier(type, "middleware fact type");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("invalid middleware fact schema version");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 本次请求的只读快照；扩展新增内容单独返回，避免把请求级修改混入已持久化历史。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param systemPrompt 系统提示词
     * @param history 历史消息
     * @param registeredTools 注册工具列表
     * @param extensionState 扩展状态
     */
    record RequestContext(AgentIdentity identity,
                          String systemPrompt,
                          List<ModelMessage> history,
                          List<ToolSpec> registeredTools,
                          ExtensionStateView extensionState) {
        /**
         * 固定身份、提示词、历史与注册工具，确保各中间件观察同一请求版本且不能共享可变列表。
         */
        public RequestContext {
            Objects.requireNonNull(identity, "identity");
            systemPrompt = ContractChecks.text(systemPrompt, "systemPrompt", 2_000_000, true);
            history = ContractChecks.immutableList(history, "history");
            registeredTools = ContractChecks.immutableList(registeredTools, "registeredTools");
            Objects.requireNonNull(extensionState, "extensionState");
        }
    }

    /**
     * 仅表达追加内容和工具收窄；可见工具最终仍与冻结注册表取交集，不能借扩展新增权限。
     *
     * @param instructionSuffix 指令后缀
     * @param contextAdditions 上下文补充内容
     * @param visibleToolNames 可见工具名称集合（可选）
     */
    record RequestPatch(String instructionSuffix,
                        List<ModelMessage> contextAdditions,
                        Optional<Set<String>> visibleToolNames) {
        /**
         * 只接受不含 Tool 调用或结果的 USER 上下文，并复制集合以阻止扩展伪造历史执行记录。
         */
        public RequestPatch {
            instructionSuffix = ContractChecks.text(instructionSuffix, "instructionSuffix", 2_000_000, true);
            contextAdditions = ContractChecks.immutableList(contextAdditions, "contextAdditions");
            if (contextAdditions.stream().anyMatch(message -> message.role() != ModelRole.USER
                                                              || message.content().stream().anyMatch(content -> content instanceof ToolCallContent
                                                                                                                || content instanceof ToolResultContent))) {
                throw new IllegalArgumentException("middleware context additions must be user messages without tool facts");
            }
            visibleToolNames = Objects.requireNonNull(visibleToolNames, "visibleToolNames")
                    .map(names -> {
                        Set<String> checked = new java.util.LinkedHashSet<>();
                        for (String name : names) {
                            String value = ContractChecks.identifier(name, "visible tool name");
                            if (!checked.add(value)) {
                                throw new IllegalArgumentException("duplicate visible tool name");
                            }
                        }
                        return Set.copyOf(checked);
                    });
        }

        /**
         * 用空 Optional 表示不限制工具；空集合则明确表示本次不向模型暴露任何工具。
         */
        public static RequestPatch unchanged() {
            return new RequestPatch("", List.of(), Optional.empty());
        }
    }

    /**
     * 工具准入使用冻结的执行身份、调用参数和副作用分类，保证策略针对即将执行的同一调用。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param execution 执行
     * @param invocation 调用
     * @param sideEffect 工具副作用分类
     * @param approvalRequirement 审批要求
     * @param extensionState 扩展状态
     */
    record ToolContext(AgentIdentity identity,
                       AgentTool.ExecutionContext execution,
                       AgentTool.Invocation invocation,
                       ToolSideEffect sideEffect,
                       AgentTool.ApprovalRequirement approvalRequirement,
                       ExtensionStateView extensionState) {
        /**
         * 拒绝缺失策略输入，使后续授权链不必对空身份或空调用作猜测。
         */
        public ToolContext {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(execution, "execution");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(sideEffect, "sideEffect");
            Objects.requireNonNull(approvalRequirement, "approvalRequirement");
            Objects.requireNonNull(extensionState, "extensionState");
        }
    }

    /**
     * 表示允许进入后续处理的参数；此值不是已授权或已执行的凭据。
     *
     * @param arguments 工具调用参数
     */
    record ToolProceed(JsonObject arguments) implements ToolDecision {
        /**
         * 禁止空参数绕过内核的二次校验与授权步骤。
         */
        public ToolProceed {
            Objects.requireNonNull(arguments, "arguments");
        }
    }

    /**
     * 可持久化并返回模型的拒绝信息；字段有界且不应携带原始凭据或敏感参数。
     *
     * @param code 结果或诊断代码
     * @param message 消息或诊断说明
     */
    record ToolReject(String code,
                      String message) implements ToolDecision {
        /**
         * 限制错误标识与消息长度，避免扩展把异常正文写成无界执行事实。
         */
        public ToolReject {
            code = ContractChecks.identifier(code, "code");
            message = ContractChecks.text(message, "message", 65_536, false);
        }
    }

    /**
     * 等待只描述可恢复控制状态，不代表执行了外部业务动作。
     *
     * @param request 请求
     */
    record ToolWait(WaitRequest request) implements ToolDecision {
        /**
         * 等待必须携带明确请求，内核才能把问题与原执行游标一起持久化。
         */
        public ToolWait {
            Objects.requireNonNull(request, "request");
        }
    }

    /**
     * 供宿主呈现和回答的稳定等待信封；payload 保持业务不透明，由宿主定义具体解释。
     *
     * @param kind 类型分类
     * @param prompt 提示词
     * @param payload 负载
     */
    record WaitRequest(String kind,
                       String prompt,
                       JsonObject payload) {
        /**
         * 校验跨项目可复用的标识与提示词边界，不把业务字段语义塞入 Core。
         */
        public WaitRequest {
            kind = ContractChecks.identifier(kind, "kind");
            prompt = ContractChecks.text(prompt, "prompt", 65_536, false);
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 同时提供真实工具结局与待投影内容，让策略可见事实而不能替换事实。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param invocation 调用
     * @param outcome 执行结果
     * @param executionStarted 是否已进入持久化工具派发阶段
     * @param content 内容
     * @param structuredContent 结构化结果内容（可选）
     * @param artifactContent 需要独立持久化的产物正文（可选），与模型可见摘要分开保存
     * @param extensionFacts 扩展事实列表
     * @param extensionState 扩展状态
     */
    record ToolResultContext(AgentIdentity identity,
                             AgentTool.Invocation invocation,
                             ToolOutcome outcome,
                             boolean executionStarted,
                             String content,
                             Optional<JsonValue> structuredContent,
                             Optional<String> artifactContent,
                             List<AgentTool.ExtensionFact> extensionFacts,
                             ExtensionStateView extensionState) {
        /**
         * 未提供独立 Artifact 时保持该通道为空，避免把面向模型的展示正文误持久化为 Artifact。
         */
        public ToolResultContext(AgentIdentity identity, AgentTool.Invocation invocation, ToolOutcome outcome,
                                 boolean executionStarted, String content, Optional<JsonValue> structuredContent,
                                 List<AgentTool.ExtensionFact> extensionFacts, ExtensionStateView extensionState) {
            this(identity, invocation, outcome, executionStarted, content, structuredContent, Optional.empty(),
                    extensionFacts, extensionState);
        }

        /**
         * 固定 Started 边界与真实结局，区分准入拒绝和可能已发生副作用的工具结果。
         */
        public ToolResultContext {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(invocation, "invocation");
            Objects.requireNonNull(outcome, "outcome");
            content = ContractChecks.text(content, "content", Integer.MAX_VALUE, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            artifactContent.ifPresent(value -> ContractChecks.text(value, "artifactContent", Integer.MAX_VALUE, true));
            extensionFacts = List.copyOf(Objects.requireNonNull(extensionFacts, "extensionFacts"));
            extensionFacts.forEach(fact -> Objects.requireNonNull(fact, "extension fact"));
            Objects.requireNonNull(extensionState, "extensionState");
        }
    }

    /**
     * 只包含回给模型的内容字段，不含真实结局，防止投影扩展把失败伪装成成功。
     *
     * @param content 内容
     * @param structuredContent 结构化结果内容（可选）
     * @param artifactContent 独立产物正文投影（可选），不以展示正文代替
     */
    record ToolResultProjection(String content,
                                Optional<JsonValue> structuredContent,
                                Optional<String> artifactContent) {
        /**
         * 展示内容投影未带独立 Artifact 时保留空通道，避免将投影正文当作 Artifact。
         */
        public ToolResultProjection(String content, Optional<JsonValue> structuredContent) {
            this(content, structuredContent, Optional.empty());
        }

        /**
         * 校验投影内容；持久化仍使用单独保存的原始 Tool 结局。
         */
        public ToolResultProjection {
            content = ContractChecks.text(content, "content", Integer.MAX_VALUE, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            artifactContent.ifPresent(value -> ContractChecks.text(value, "artifactContent", Integer.MAX_VALUE, true));
        }
    }

    /**
     * 提交前的完成候选信息；摘要尚未成为终态事实，副作用不确定性只由持久恢复事实判定。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param summary 摘要
     * @param hasPendingWait 是否仍有待处理的等待项
     * @param hasUnverifiedToolEffects 是否仍有未经核验的工具副作用
     * @param extensionState 扩展状态
     */
    record CompletionContext(AgentIdentity identity,
                             String summary,
                             boolean hasPendingWait,
                             boolean hasUnverifiedToolEffects,
                             ExtensionStateView extensionState) {
        /**
         * 固定完成身份和有界摘要，避免中间件根据文本猜测外部副作用是否已经核实。
         */
        public CompletionContext {
            Objects.requireNonNull(identity, "identity");
            summary = ContractChecks.text(summary, "summary", 2_000_000, true);
            Objects.requireNonNull(extensionState, "extensionState");
        }
    }

    /**
     * 明确接受终态；一旦提交，中间件不再有修改执行结果的机会。
     */
    record Complete() implements CompletionDecision {
    }

    /**
     * 表示在现有 Agent 循环中继续；指令作为持久上下文输入而非隐藏的临时调用。
     *
     * @param instruction 指令
     */
    record Continue(String instruction) implements CompletionDecision {
        /**
         * 拒绝空指令，避免无可见原因地再发起一次模型请求。
         */
        public Continue {
            instruction = ContractChecks.text(instruction, "instruction", 65_536, false);
        }
    }

    /**
     * 以与工具等待相同的持久协议暂停收尾，保持回答与恢复位置可审计。
     *
     * @param request 请求
     */
    record Await(WaitRequest request) implements CompletionDecision {
        /**
         * 必须先具备可存储的等待请求，内核才会挂起执行。
         */
        public Await {
            Objects.requireNonNull(request, "request");
        }
    }
}
