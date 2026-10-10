// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * Provider 发起的工具执行端口；宿主负责捕获业务依赖，Core 只传稳定身份和值对象。
 */
public interface AgentTool {
    /**
     * 返回当前 Java 运行时一致的 JSON Schema 身份摘要。
     */
    static String schemaFingerprint(ToolSpec spec) {
        return Hashing.sha256Hex(canonicalSchema(Objects.requireNonNull(spec, "spec").inputSchema()));
    }

    /**
     * 对 JSON Schema 采用对象键排序、数组保序与类型标记，保证跨进程摘要稳定。
     */
    static String canonicalSchema(JsonValue schema) {
        StringBuilder canonical = new StringBuilder();
        appendCanonicalJson(canonical, Objects.requireNonNull(schema, "schema"));
        return canonical.toString();
    }

    /**
     * 只接受小写 SHA-256，避免展示值或弱摘要被误用为恢复身份。
     */
    private static String fingerprint(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 递归规范对象键但保留数组次序，避免两个工具适配器产生不同目录指纹。
     */
    private static void appendCanonicalJson(StringBuilder target, JsonValue value) {
        switch (value) {
            case JsonObject object -> {
                target.append('o').append(object.members().size()).append('{');
                object.members().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> {
                            appendToken(target, 'k', entry.getKey());
                            appendCanonicalJson(target, entry.getValue());
                        });
                target.append('}');
            }
            case JsonArray array -> {
                target.append('a').append(array.values().size()).append('[');
                array.values().forEach(item -> appendCanonicalJson(target, item));
                target.append(']');
            }
            case JsonText text -> appendToken(target, 's', text.value());
            case JsonNumber number -> appendToken(target, 'm', number.value().toString());
            case JsonBoolean bool -> target.append(bool.value() ? "b1" : "b0");
            case JsonNull ignored -> target.append('z');
        }
    }

    /**
     * 长度前缀让相邻键值无歧义拼接，避免依赖 JSON 序列化器配置。
     */
    private static void appendToken(StringBuilder target, char type, String value) {
        target.append(type).append(value.length()).append(':').append(value);
    }

    /**
     * 返回声明给模型的不可变工具名称与参数 Schema。
     */
    ToolSpec spec();

    /**
     * 指明能力副作用类型，默认按外部副作用处理以避免未经声明的自动重试。
     */
    default ToolSideEffect sideEffect() {
        return ToolSideEffect.EXTERNAL;
    }

    /**
     * 允许动态能力按单次冻结调用收窄副作用声明。
     */
    default ToolSideEffect sideEffect(Invocation invocation) {
        return Objects.requireNonNull(sideEffect(), "sideEffect");
    }

    /**
     * 默认要求宿主审批；豁免必须由工具实现对自身能力作出明确声明。
     */
    default ApprovalRequirement approvalRequirement() {
        return ApprovalRequirement.REQUIRED;
    }

    /**
     * 为动态工具提供逐调用审批声明，同时保持策略由能力而非名称推导。
     */
    default ApprovalRequirement approvalRequirement(Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Objects.requireNonNull(approvalRequirement(), "approvalRequirement");
    }

    /**
     * 声明路由后才确定最终参数 Schema 的动态能力需要重验模型参数。
     */
    default boolean validatesResolvedArguments() {
        return false;
    }

    /**
     * 在持久化调用意图前返回可安全暴露给模型的确定性参数错误。
     */
    default Optional<InvocationValidationFailure> validationFailure(Invocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        return Optional.empty();
    }

    /**
     * 冻结一次调用的工具身份、Schema、目录与权限指纹。
     *
     * <p>目录和权限指纹必须由宿主按请求显式提供；默认路由为空，动态适配器可覆写 route，
     * Core 不从实现类名称、Ja 配置或路径猜测恢复授权。</p>
     */
    default ToolExecutionBinding executionBinding(Invocation invocation, ToolBindingContext context) {
        Invocation call = Objects.requireNonNull(invocation, "invocation");
        ToolBindingContext binding = Objects.requireNonNull(context, "context");
        ToolSpec descriptor = Objects.requireNonNull(spec(), "tool spec");
        if (!descriptor.name().equals(call.toolName())) {
            throw new IllegalArgumentException("Tool binding name mismatch");
        }
        return new ToolExecutionBinding(descriptor.name(), Hashing.sha256Hex(canonicalSchema(descriptor.inputSchema())),
                binding.catalogFingerprint(), binding.permissionFingerprint(), JsonObject.empty());
    }

    /**
     * 在调用意图提交前生成不含执行副作用的宿主展示事实；默认空列表让 Core 不承担任何界面格式。
     */
    default List<ExtensionFact> preparedFacts(Invocation invocation, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在 Started 意图提交前生成执行阶段宿主事实；仅用于观测状态推进，不执行外部动作。
     */
    default List<ExtensionFact> startedFacts(Invocation invocation, ExecutionContext context) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        return List.of();
    }

    /**
     * 在结果中间件完成投影后生成宿主事实；Core 结果携带真实耗时，恢复核验的未知耗时保持缺失。
     */
    default List<ExtensionFact> resultFacts(Invocation invocation, ExecutionContext context,
                                            ExecutionResult executionResult,
                                            AgentMiddleware.ToolResultProjection visibleResult) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(executionResult, "executionResult");
        Objects.requireNonNull(visibleResult, "visibleResult");
        return List.of();
    }

    /**
     * 在外部副作用前构造可持久恢复的最小证据；证据保持不透明，避免 Core 解释宿主数据。
     */
    default Optional<RecoveryEvidence> prepareRecoveryEvidence(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return Optional.empty();
    }

    /**
     * 核实崩溃前已启动调用的真实外部结果；默认明确报告无法核实，Core 因而挂起而不会重放。
     */
    default CompletionStage<RecoveryResolution> reconcile(
            Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
            CancellationToken cancellationToken) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        return java.util.concurrent.CompletableFuture.completedFuture(
                new RecoveryResolution.Unknown("TOOL_OUTCOME_UNVERIFIABLE"));
    }

    /**
     * 在稳定身份与取消令牌约束下执行一次工具调用，并返回需同事务保存的扩展事实。
     */
    CompletionStage<ExecutionResult> execute(
            Invocation invocation, ExecutionContext context, CancellationToken cancellationToken);

    /**
     * 未执行核验后复用其持有资源，避免安全证明与下一次派发之间出现竞态窗口。
     */
    default CompletionStage<ExecutionResult> executeAfterNotApplied(
            Invocation invocation, ExecutionContext context, CancellationToken cancellation,
            SettlementResource settlementResource) {
        SettlementResource inherited = Objects.requireNonNull(settlementResource, "settlementResource");
        return Objects.requireNonNull(execute(invocation, context, cancellation), "tool execution stage")
                .thenApply(result -> Objects.requireNonNull(result, "tool execution result")
                        .withSettlementResource(inherited));
    }

    /**
     * 已有恢复租约时继续核验，防止为再次确认而与自己持有的持久 claim 死锁。
     */
    default CompletionStage<RecoveryResolution> reconcileWithSettlement(
            Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
            CancellationToken cancellation, SettlementResource settlementResource) {
        Objects.requireNonNull(settlementResource, "settlementResource");
        return Objects.requireNonNull(reconcile(invocation, context, evidence, cancellation),
                "tool recovery stage");
    }

    /**
     * Tool 的通用审批要求，具体用户身份与权限判定由宿主策略决定。
     */
    enum ApprovalRequirement {
        /**
         * 只有宿主明确给出可恢复审批等待策略后才允许派发。
         */
        REQUIRED,
        /**
         * 工具实现显式声明不需要用户审批，不由名称或副作用类型推断。
         */
        NOT_REQUIRED
    }

    /**
     * 结果事务必须显式释放的进程内资源句柄；它不会序列化到游标或扩展事实。
     */
    @FunctionalInterface
    interface SettlementResource extends AutoCloseable {
        /**
         * 普通工具无跨事务资源，仍使用同一显式关闭路径。
         */
        static SettlementResource none() {
            return () -> {
            };
        }

        /**
         * 包装业务资源，避免异步完成与终态清理竞争时重复释放底层租约。
         */
        static SettlementResource once(AutoCloseable resource) {
            AutoCloseable value = Objects.requireNonNull(resource, "resource");
            java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                try {
                    value.close();
                } catch (RuntimeException | Error failure) {
                    throw failure;
                } catch (Exception failure) {
                    throw new IllegalStateException("Tool settlement resource release failed", failure);
                }
            };
        }

        /**
         * 合并恢复核验与结果执行持有的资源，任一释放失败也继续释放另一项。
         */
        static SettlementResource combine(AutoCloseable first, AutoCloseable second) {
            AutoCloseable left = once(first);
            AutoCloseable right = once(second);
            return once(() -> {
                Throwable failure = null;
                try {
                    left.close();
                } catch (Throwable closeFailure) {
                    failure = closeFailure;
                }
                try {
                    right.close();
                } catch (Throwable closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    }
                    else if (failure != closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (failure instanceof Error error) {
                    throw error;
                }
                if (failure != null) {
                    throw new IllegalStateException("Tool settlement resource release failed", failure);
                }
            });
        }

        /**
         * 幂等释放暂持的跨副作用协调资源。
         */
        @Override
        void close();
    }

    /**
     * 恢复核验的闭集结果；只有明确未执行才允许写入 retry intent 后重派。
     */
    sealed interface RecoveryResolution permits RecoveryResolution.Applied,
            RecoveryResolution.NotApplied, RecoveryResolution.Unknown {
        /**
         * 外部动作已完成且可重建结果及宿主事实，二者将与恢复决定在同一提交中结算。
         *
         * @param result 处理结果
         * @param verificationFingerprint 副作用核验证据的指纹
         */
        record Applied(ExecutionResult result,
                       String verificationFingerprint) implements RecoveryResolution {
            /**
             * 绑定真实工具结果与核验快照，避免成功分支没有可回放内容。
             */
            public Applied {
                Objects.requireNonNull(result, "result");
                verificationFingerprint = fingerprint(verificationFingerprint, "verificationFingerprint");
            }
        }

        /**
         * 只有宿主按 callKey 核实副作用未发生后，Loop 才能持久化重试意图并重新调用。
         *
         * @param verificationFingerprint 副作用核验证据的指纹
         * @param settlementResource 与结果结算一同释放的资源
         */
        record NotApplied(String verificationFingerprint,
                          SettlementResource settlementResource)
                implements RecoveryResolution {
            /**
             * 普通工具无需跨越核验与重派持有额外资源。
             */
            public NotApplied(String verificationFingerprint) {
                this(verificationFingerprint, SettlementResource.none());
            }

            /**
             * 未执行证明必须有稳定指纹，供审计和恢复时校验。
             */
            public NotApplied {
                verificationFingerprint = fingerprint(verificationFingerprint, "verificationFingerprint");
                Objects.requireNonNull(settlementResource, "settlementResource");
            }
        }

        /**
         * 结果无法核实；Core 必须挂起而不能把不确定性转换成自动重试。
         *
         * @param reasonCode 原因代码
         * @param settlementResource 与结果结算一同释放的资源
         */
        record Unknown(String reasonCode,
                       SettlementResource settlementResource) implements RecoveryResolution {
            /**
             * 普通工具没有需要跨越挂起提交保留的瞬时协调资源。
             */
            public Unknown(String reasonCode) {
                this(reasonCode, SettlementResource.none());
            }

            /**
             * 仅允许稳定代码进入持久状态，原始异常和远端正文保留在宿主诊断中。
             */
            public Unknown {
                reasonCode = ContractChecks.identifier(reasonCode, "reasonCode");
                Objects.requireNonNull(settlementResource, "settlementResource");
            }
        }
    }

    /**
     * 一次模型发起的调用及其在完整批次中的位置。
     *
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param arguments 工具调用参数
     * @param ordinal 当前项的顺序位置
     * @param preflightFailureCode 预检失败代码
     */
    record Invocation(String callId,
                      String toolName,
                      JsonObject arguments,
                      int ordinal,
                      String preflightFailureCode) {
        /**
         * 构造正常解析成功的模型调用。
         */
        public Invocation(String callId, String toolName, JsonObject arguments, int ordinal) {
            this(callId, toolName, arguments, ordinal, null);
        }

        /**
         * 仅保留闭集预检错误，截断和参数损坏的调用都不得越过副作用边界。
         */
        public Invocation {
            callId = ContractChecks.identifier(callId, "callId");
            toolName = ContractChecks.identifier(toolName, "toolName");
            Objects.requireNonNull(arguments, "arguments");
            if (preflightFailureCode != null
                && !Set.of("TOOL_ARGUMENTS_INVALID", "TOOL_CALL_TRUNCATED").contains(preflightFailureCode)) {
                throw new IllegalArgumentException("unsupported Tool preflight failure");
            }
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("tool ordinal is outside the supported batch bound");
            }
        }
    }

    /**
     * 工具绑定所需的请求级明确身份；不接受从运行时对象身份推测权限。
     *
     * @param catalogFingerprint 能力目录指纹
     * @param permissionFingerprint 权限快照指纹
     */
    record ToolBindingContext(String catalogFingerprint,
                              String permissionFingerprint) {
        /**
         * 两个策略指纹都必须是稳定 SHA-256，配置变化后才能可靠拒绝旧调用绑定。
         */
        public ToolBindingContext {
            catalogFingerprint = fingerprint(catalogFingerprint, "catalogFingerprint");
            permissionFingerprint = fingerprint(permissionFingerprint, "permissionFingerprint");
        }
    }

    /**
     * 工具接收最小执行身份和有界数据属性，业务服务由宿主预先捕获而不进入持久上下文。
     *
     * @param identity 包含租户、对话、执行与用户的完整身份
     * @param callKey 按执行身份派生的工具幂等键
     * @param attributes 属性集合
     */
    record ExecutionContext(AgentIdentity identity,
                            String callKey,
                            Map<String, String> attributes) {
        /**
         * 校验幂等调用键并冻结属性快照，保证一轮运行中上下文不随宿主变更。
         */
        public ExecutionContext {
            Objects.requireNonNull(identity, "identity");
            if (callKey == null || !callKey.matches("call_[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid tool call key");
            }
            attributes = freezeAttributes(attributes);
        }

        /**
         * 限制上下文为不可变有界数据，不允许传入 Service、文件路径或可变对象引用。
         */
        private static Map<String, String> freezeAttributes(Map<String, String> source) {
            Objects.requireNonNull(source, "attributes");
            if (source.size() > 256) {
                throw new IllegalArgumentException("too many tool context attributes");
            }
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            source.forEach((key, value) -> {
                String safeKey = ContractChecks.identifier(key, "tool context attribute key");
                String safeValue = ContractChecks.text(value, "tool context attribute value", 16_384, true);
                if (values.putIfAbsent(safeKey, safeValue) != null) {
                    throw new IllegalArgumentException("duplicate tool context attribute");
                }
            });
            if (values.entrySet().stream()
                        .mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum() > 65_536) {
                throw new IllegalArgumentException("tool context attributes exceed the request bound");
            }
            return Map.copyOf(values);
        }
    }

    /**
     * Tool Adapter 输出给模型的结果只包含无句柄的数据，不承担 Ja 文件变更投影。
     *
     * @param outcome 执行结果
     * @param content 内容
     * @param structuredContent 结构化结果内容（可选）
     * @param errorCode 错误代码
     * @param artifactContent 需要完整保存的产物正文（可选）
     * @param media 与成功结果绑定的受管媒体引用
     */
    record ToolResult(ToolOutcome outcome,
                      String content,
                      Optional<JsonValue> structuredContent,
                      String errorCode,
                      Optional<String> artifactContent,
                      List<ToolMediaReference> media) {
        /**
         * 普通 Tool 保持短结果契约；完整产物只有明确声明后才进入 Ja artifact 存储。
         */
        public ToolResult(ToolOutcome outcome, String content,
                          Optional<JsonValue> structuredContent, String errorCode) {
            this(outcome, content, structuredContent, errorCode, Optional.empty(), List.of());
        }

        /**
         * 完整文本产物保持既有调用面；媒体仍须显式通过类型化引用传递。
         */
        public ToolResult(ToolOutcome outcome, String content, Optional<JsonValue> structuredContent,
                          String errorCode, Optional<String> artifactContent) {
            this(outcome, content, structuredContent, errorCode, artifactContent, List.of());
        }

        /**
         * 结果类别与稳定错误码必须匹配，避免恢复或模型上下文伪造成功状态。
         */
        public ToolResult {
            Objects.requireNonNull(outcome, "outcome");
            content = ContractChecks.text(content == null ? "" : content, "content", Integer.MAX_VALUE, true);
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            artifactContent = Objects.requireNonNull(artifactContent, "artifactContent");
            media = List.copyOf(Objects.requireNonNull(media, "media"));
            artifactContent.ifPresent(value -> ContractChecks.text(value, "artifactContent", Integer.MAX_VALUE, true));
            if ((outcome == ToolOutcome.SUCCEEDED) != (errorCode == null)) {
                throw new IllegalArgumentException("tool outcome and error code are inconsistent");
            }
            if (errorCode != null) {
                errorCode = ContractChecks.identifier(errorCode, "errorCode");
            }
            if (media.size() > 10 || outcome != ToolOutcome.SUCCEEDED && !media.isEmpty()
                || media.stream().map(ToolMediaReference::attachmentId).distinct().count() != media.size()) {
                throw new IllegalArgumentException("invalid tool result media");
            }
            media.forEach(value -> Objects.requireNonNull(value, "media reference"));
        }

        /**
         * 从失败阶段向模型提供稳定错误，不暴露异常对象或内部副作用收据。
         */
        public static ToolResult success(String content) {
            return new ToolResult(ToolOutcome.SUCCEEDED, content, Optional.empty(), null);
        }

        /**
         * 成功结果附加已受管的 opaque 图片引用；原有摘要和文本产物保持不变。
         */
        public ToolResult withMedia(List<ToolMediaReference> values) {
            return new ToolResult(outcome, content, structuredContent, errorCode, artifactContent, values);
        }

        /**
         * 只输出长度和结构化内容存在性，避免日志记录工具正文或敏感载荷。
         */
        @Override
        public String toString() {
            return "ToolResult[outcome=" + outcome + ", contentLength=" + content.length()
                   + ", structuredContent=" + structuredContent.isPresent()
                   + ", artifactContent=" + artifactContent.isPresent() + ", mediaCount=" + media.size()
                   + ", errorCode=" + errorCode + "]";
        }
    }

    /**
     * 将真实工具结果、宿主事实和可选实测耗时组合，供 Core 在一个 AgentStore 提交中原子保存。
     *
     * @param result 处理结果
     * @param facts 事实列表
     * @param settlementResource 与结果结算一同释放的资源
     * @param executionDurationMillis 实际工具执行耗时，单位为毫秒（可选）
     */
    record ExecutionResult(ToolResult result,
                           List<ExtensionFact> facts,
                           SettlementResource settlementResource,
                           OptionalLong executionDurationMillis) implements AutoCloseable {
        /**
         * 普通工具没有跨事务资源或可用实测耗时时保持最小构造形式。
         */
        public ExecutionResult(ToolResult result, List<ExtensionFact> facts) {
            this(result, facts, SettlementResource.none(), OptionalLong.empty());
        }

        /**
         * 将工具持有的结算资源转交给结果生命周期；未提供实测值时不根据提交时间推算耗时。
         */
        public ExecutionResult(ToolResult result, List<ExtensionFact> facts,
                               SettlementResource settlementResource) {
            this(result, facts, settlementResource, OptionalLong.empty());
        }

        /**
         * 校验结果事实、资源句柄和非负耗时，避免恢复路径伪造或补默认时长。
         */
        public ExecutionResult {
            Objects.requireNonNull(result, "result");
            facts = List.copyOf(Objects.requireNonNull(facts, "facts"));
            facts.forEach(value -> Objects.requireNonNull(value, "extension fact"));
            settlementResource = SettlementResource.once(Objects.requireNonNull(
                    settlementResource, "settlementResource"));
            executionDurationMillis = Objects.requireNonNull(executionDurationMillis, "executionDurationMillis");
            if (executionDurationMillis.isPresent() && executionDurationMillis.getAsLong() < 0) {
                throw new IllegalArgumentException("executionDurationMillis must be non-negative");
            }
        }

        /**
         * 无宿主投影事实的工具使用空列表，仍走同一原子结果契约。
         */
        public static ExecutionResult of(ToolResult result) {
            return new ExecutionResult(result, List.of());
        }

        /**
         * 关闭非持久化执行资源；包装器使完成、取消和失败路径并发收尾保持幂等。
         */
        @Override
        public void close() {
            settlementResource.close();
        }

        /**
         * 保留已实测耗时与结果事实，只把执行租约移交给结果事务拥有者。
         */
        public ExecutionResult withSettlementResource(AutoCloseable resource) {
            return new ExecutionResult(result, facts, SettlementResource.combine(settlementResource, resource),
                    executionDurationMillis);
        }

        /**
         * 为已结束的真实执行附加单调时钟测得的耗时，恢复核验不得调用此方法合成旧耗时。
         */
        public ExecutionResult withExecutionDurationMillis(long durationMillis) {
            return new ExecutionResult(result, facts, settlementResource, OptionalLong.of(durationMillis));
        }
    }

    /**
     * 由宿主适配器产生的命名空间记录；agent 前缀专供核心事实，不能被扩展覆盖。
     *
     * @param namespace 命名空间
     * @param type 类型标识
     * @param schemaVersion 数据结构版本
     * @param payload 负载
     */
    record ExtensionFact(String namespace,
                         String type,
                         int schemaVersion,
                         JsonObject payload) {
        /**
         * 只校验稳定包络，业务 payload 保持不透明并由对应 TransactionParticipant 解释。
         */
        public ExtensionFact {
            namespace = ContractChecks.identifier(namespace, "extension fact namespace");
            if (namespace.equals("agent") || namespace.startsWith("agent.")) {
                throw new IllegalArgumentException("extension fact namespace is reserved");
            }
            type = ContractChecks.identifier(type, "extension fact type");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("invalid extension fact schema version");
            }
            Objects.requireNonNull(payload, "payload");
        }
    }

    /**
     * 工具提交给恢复协调器的不透明、版本化证据。
     *
     * @param typeId 类型标识
     * @param schemaVersion 数据结构版本
     * @param payload 负载
     * @param fingerprint 当前内容或配置的校验指纹
     */
    record RecoveryEvidence(String typeId,
                            int schemaVersion,
                            JsonObject payload,
                            String fingerprint) {
        /**
         * 证据保留稳定类型与摘要，具体 payload 只由产生它的宿主工具解释。
         */
        public RecoveryEvidence {
            typeId = ContractChecks.identifier(typeId, "typeId");
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("invalid recovery schema version");
            }
            Objects.requireNonNull(payload, "payload");
            fingerprint = AgentTool.fingerprint(fingerprint, "fingerprint");
        }
    }

    /**
     * 参数预检失败的安全投影，不携带原始参数或异常文本。
     *
     * @param code 结果或诊断代码
     * @param message 消息或诊断说明
     */
    record InvocationValidationFailure(String code,
                                       String message) {
        /**
         * 固定稳定错误码和有界诊断，防止预检绕过 Tool 结果边界泄漏内部异常。
         */
        public InvocationValidationFailure {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("invalid invocation failure code");
            }
            message = ContractChecks.text(message, "message", 1_024, false);
        }
    }
}
