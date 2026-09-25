// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * 向应用层提供 Provider 中立的流式模型调用出站 SPI。
 */
public interface ModelPort {
    /**
     * 返回实际 Codec 可编码的原生附件闭集；默认值故意为空，使测试替身和未知实现安全退回 Tool。
     */
    default NativeAttachmentSupport nativeAttachmentSupport(ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return NativeAttachmentSupport.none();
    }

    /**
     * 对完整冻结 Provider envelope 做纯本地保守估算；实现不得发起网络请求或进入发送熔断器。
     */
    default InputTokenEstimate estimateInputTokens(
            ModelRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        throw new UnsupportedOperationException("local input token estimation is not implemented");
    }

    /**
     * 从已保存 Provider 的凭据和端点读取上游模型目录；默认实现刻意失败关闭，避免测试替身或
     * 未支持的运行时把目录读取伪装成成功。
     */
    default CompletionStage<ModelDiscoveryResult> discoverModels(
            ModelDiscoveryRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        cancellationToken.throwIfCancellationRequested();
        throw new UnsupportedOperationException("upstream model discovery is not implemented");
    }

    /**
     * 让普通模型调用和目录读取共享同一网络时限，避免任一入口放宽连接资源上限。该方法保持接口静态
     * 工具而非嵌套 record 的 private helper，使两类冻结请求都能显式复用同一约束，且静态分析可
     * 沿字节码调用关系验证这一点。
     */
    static Duration boundedTimeout(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(name + " must be in (0, 1h]");
        }
        return value;
    }

    /**
     * 统一 Provider Base URL 的安全闭集，防止模型调用和目录读取对 scheme、凭据或路径附属片段形成
     * 不同解释；调用方仍可保留反向代理路径，具体端点只能由 Adapter 派生。
     */
    static URI requireHttpBaseUri(URI value) {
        Objects.requireNonNull(value, "baseUri");
        if (!value.isAbsolute() || value.getHost() == null || value.getHost().isBlank()
            || value.getUserInfo() != null || value.getQuery() != null || value.getFragment() != null
            || !("https".equalsIgnoreCase(value.getScheme()) || "http".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalArgumentException("baseUri must be HTTP or HTTPS");
        }
        return value;
    }

    /**
     * 启动一次有界模型响应，并通过 Sink 顺序发布规范事件。
     */
    CompletionStage<ModelOutcome> start(
            ModelRequest request,
            ModelEventSink eventSink,
            CancellationToken cancellationToken);

    /**
     * 将本地保守准入估计与冻结请求指纹绑定；多模态和自定义模型不保证 tokenizer 严格上界，
     * 更不能把该值当作响应内 Provider reported usage。
     */
    record InputTokenEstimate(long conservativeUpperBound, String fingerprint) {
        /** 拒绝负估算和非 SHA-256 指纹，避免跨 envelope 复用预算证据。 */
        public InputTokenEstimate {
            if (conservativeUpperBound < 0 || fingerprint == null
                || !fingerprint.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid local input token estimate");
            }
        }
    }

    /**
     * Provider 请求、响应或容量门禁无法完成当前模型调用时使用的稳定应用边界异常。
     *
     * <p>具体 Adapter 可以保留内部受限诊断子类型，但 Agent Loop 只依赖这一 Provider 中立
     * 分类，避免把外部服务故障误报为 Ja 内部错误。</p>
     */
    class ModelUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String terminalErrorCode;

        /**
         * 保留脱敏根因供受管日志分类，同时固定公开消息且不捕获本地堆栈。
         */
        public ModelUnavailableException(String message, Throwable cause) {
            this(message, cause, "MODEL_UNAVAILABLE");
        }

        /**
         * 允许 Provider adapter 在不泄漏 wire 细节的前提下区分瞬时不可用、响应损坏、空闲超时与
         * 上游拒绝；Agent Loop 只消费这些稳定的 Provider 中立终态码来决定有界重试和最终文案。
         */
        protected ModelUnavailableException(String message, Throwable cause, String terminalErrorCode) {
            super(message, cause, false, false);
            if (terminalErrorCode == null || !java.util.Set.of("MODEL_UNAVAILABLE", "MODEL_PROTOCOL_ERROR",
                    "MODEL_UPSTREAM_REJECTED",
                    "MODEL_STREAM_INVALID", "MODEL_IDLE_TIMEOUT").contains(terminalErrorCode)) {
                throw new IllegalArgumentException("invalid model terminal error code");
            }
            this.terminalErrorCode = terminalErrorCode;
        }

        /** 返回可安全持久化和展示的稳定终态分类，不暴露 Provider 专属诊断。 */
        public final String terminalErrorCode() {
            return terminalErrorCode;
        }

        /** Provider 可传递受限的退避提示，Session 无需依赖任一具体协议适配器。 */
        public java.util.Optional<java.time.Duration> retryAfterHint() {
            return java.util.Optional.empty();
        }
    }

    /**
     * Provider Adapter 必须实现的明确 Wire API。
     */
    enum Api {
        /**
         * 采用 Anthropic Messages 原生对话协议。
         */
        ANTHROPIC_MESSAGES,
        /**
         * 采用 OpenAI Responses 原生响应协议。
         */
        OPENAI_RESPONSES,
        /**
         * 采用 OpenAI Chat Completions 流式对话协议。
         */
        OPENAI_CHAT_COMPLETIONS
    }

    /** 模型配置声明的输入模态；Provider Codec 能力仍是独立且必须同时满足的门。 */
    enum InputModality {
        /** 文本始终走普通消息内容，不经过原生附件编码。 */
        TEXT,
        /** 图片仅表示模型声明支持，仍需 Provider Codec 的媒体闭集复核。 */
        IMAGE,
        /** PDF 独立授权文档载荷，防止其它二进制借模态声明直传。 */
        PDF
    }

    /**
     * 单次 Provider 请求解析出的 Provider、凭据引用结果、端点与生成参数。
     */
    record ModelConfiguration(
            String providerId,
            String modelId,
            String configGeneration,
            Api api,
            String model,
            URI baseUri,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            Set<InputModality> inputModalities,
            GenerationOptions generation) {
        /**
         * Provider 可连接用户明确配置的任意 HTTP(S) 主机；仍拒绝内嵌凭据和查询片段，
         * 使 API Key 只能沿专用凭据通道传递而非藏在 Base URL 中。
         */
        public ModelConfiguration {
            providerId = ContractChecks.identifier(providerId, "providerId");
            modelId = ContractChecks.identifier(modelId, "modelId");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            Objects.requireNonNull(api, "api");
            model = ContractChecks.text(model, "model", 512, false);
            baseUri = ModelPort.requireHttpBaseUri(baseUri);
            apiKey = ContractChecks.text(apiKey, "apiKey", 8_192, false);
            if (apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("apiKey contains control characters");
            }
            connectTimeout = ModelPort.boundedTimeout(connectTimeout, "connectTimeout");
            requestTimeout = ModelPort.boundedTimeout(requestTimeout, "requestTimeout");
            inputModalities = Set.copyOf(Objects.requireNonNull(inputModalities, "inputModalities"));
            if (!inputModalities.contains(InputModality.TEXT) || inputModalities.size() > InputModality.values().length) {
                throw new IllegalArgumentException("model input modalities must contain text");
            }
            Objects.requireNonNull(generation, "generation");
        }

        /**
         * 对日志隐藏凭据，同时保留排查配置代际所需的非敏感字段。
         */
        @Override
        public String toString() {
            return "ModelConfiguration[providerId=" + providerId + ", modelId=" + modelId
                   + ", configGeneration=" + configGeneration
                   + ", api=" + api + ", model=" + model + ", baseUri="
                   + baseUri + ", apiKey=<redacted>, connectTimeout=" + connectTimeout
                   + ", requestTimeout=" + requestTimeout + ", inputModalities=" + inputModalities
                   + ", generation=" + generation + "]";
        }

    }

    /**
     * 一次模型目录读取所需的最小冻结 Provider 信封。它不借用 {@link ModelConfiguration}，因为
     * 目录读取在模型尚未被保存前就有意义，强行构造虚假 modelId 会模糊配置所有权。
     */
    record ModelDiscoveryRequest(
            String providerId,
            String configGeneration,
            Api api,
            URI baseUri,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout) {
        /**
         * 与正式调用使用同一 URL、Secret 和超时边界，使发现结果只来自当前配置代际，且不会把
         * 凭据藏进 URL 或放宽网络资源上限。
         */
        public ModelDiscoveryRequest {
            providerId = ContractChecks.identifier(providerId, "providerId");
            configGeneration = ContractChecks.configurationGeneration(configGeneration);
            Objects.requireNonNull(api, "api");
            baseUri = ModelPort.requireHttpBaseUri(baseUri);
            apiKey = ContractChecks.text(apiKey, "apiKey", 8_192, false);
            if (apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("apiKey contains control characters");
            }
            connectTimeout = ModelPort.boundedTimeout(connectTimeout, "connectTimeout");
            requestTimeout = ModelPort.boundedTimeout(requestTimeout, "requestTimeout");
        }

        /**
         * 目录读取日志只保留可关联的非敏感身份，防止 record 默认输出泄露 API Key。
         */
        @Override
        public String toString() {
            return "ModelDiscoveryRequest[providerId=" + providerId + ", configGeneration="
                    + configGeneration + ", api=" + api + ", baseUri=" + baseUri
                    + ", apiKey=<redacted>, connectTimeout=" + connectTimeout
                    + ", requestTimeout=" + requestTimeout + "]";
        }

    }

    /**
     * 上游目录的最小投影；结果只含模型标识和截断事实，不携带厂商对象、能力推断或任意元数据。
     */
    record ModelDiscoveryResult(List<String> items, boolean truncated) {
        /**
         * 固定单次返回上限并拒绝控制字符、重复项与超长标识，让结果可以安全地进入 Rust/React
         * 的受限 IPC 投影，且前端无需相信上游未经校验的字段。
         */
        public ModelDiscoveryResult {
            items = List.copyOf(ContractChecks.boundedDistinctTextList(
                    items, "model discovery result", 200, 512));
        }
    }

    /** 单条 Codec 原生附件规则，媒体类型与大小均必须在编码前精确匹配。 */
    record NativeAttachmentRule(NativeAttachmentContent.Kind kind, Set<String> mediaTypes, long maxBytes) {
        /** 冻结媒体闭集并把单载荷上限限制在受管读取端口允许的 50 MiB 内。 */
        public NativeAttachmentRule {
            Objects.requireNonNull(kind, "kind");
            mediaTypes = Set.copyOf(Objects.requireNonNull(mediaTypes, "mediaTypes"));
            if (mediaTypes.isEmpty() || mediaTypes.stream().anyMatch(value -> value == null || value.isBlank())
                    || maxBytes < 1 || maxBytes > 50L * 1024 * 1024) {
                throw new IllegalArgumentException("invalid native attachment rule");
            }
        }

        /** 只有种类、精确媒体类型与大小同时满足时，Codec 才声明可编码。 */
        public boolean supports(NativeAttachmentContent.Kind candidateKind, String mediaType, long sizeBytes) {
            return kind == candidateKind && mediaTypes.contains(mediaType)
                    && sizeBytes > 0 && sizeBytes <= maxBytes;
        }
    }

    /** Codec 对原生图片/PDF 的显式能力快照；空规则代表必须走 read_attachment。 */
    record NativeAttachmentSupport(List<NativeAttachmentRule> rules, long maxTotalBytes) {
        /** 冻结规则并拒绝同一种类重复声明，且总量不能超过受管附件产品上限。 */
        public NativeAttachmentSupport {
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            if (rules.stream().map(NativeAttachmentRule::kind).distinct().count() != rules.size()
                    || maxTotalBytes < 0 || maxTotalBytes > 250L * 1024 * 1024
                    || rules.isEmpty() != (maxTotalBytes == 0)) {
                throw new IllegalArgumentException("duplicate native attachment rule");
            }
        }

        /** 未实现原生块的 Codec 使用空能力并安全回退。 */
        public static NativeAttachmentSupport none() {
            return new NativeAttachmentSupport(List.of(), 0);
        }

        /** 返回匹配规则的读取上限；空值表示 Codec 不应看到附件字节。 */
        public java.util.OptionalLong maxBytes(
                NativeAttachmentContent.Kind kind, String mediaType, long sizeBytes) {
            return rules.stream().filter(rule -> rule.supports(kind, mediaType, sizeBytes))
                    .mapToLong(NativeAttachmentRule::maxBytes).findFirst();
        }
    }

    /**
     * Provider 共有且可选的生成参数，不承载厂商私有兼容字段。
     */
    record GenerationOptions(Double temperature, Double topP, Integer maxOutputTokens, String reasoningLevel) {
        /**
         * 按各 Provider 共同可表达的交集限制参数，避免 Adapter 静默截断。
         */
        public GenerationOptions {
            if (temperature != null && (temperature < 0 || temperature > 2)
                || topP != null && (topP <= 0 || topP > 1)
                || maxOutputTokens != null && (maxOutputTokens < 1 || maxOutputTokens > 1_000_000)
                || reasoningLevel != null
                    && (!reasoningLevel.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
                        || reasoningLevel.chars().anyMatch(Character::isISOControl))) {
                throw new IllegalArgumentException("invalid generation options");
            }
        }

        /**
         * 返回全部由 Provider 默认值决定的生成选项。
         */
        public static GenerationOptions defaults() {
            return new GenerationOptions(null, null, null, null);
        }
    }

    /**
     * 将完整动态 System Prompt 及其 revision 绑定为一次不可变提示信封。
     */
    record PromptPayload(String systemPrompt, String revision) {
        /**
         * 限制 Provider 原生 system，并用稳定 revision 关联续传状态；这里不写死 Ja Persona，
         * 以免摘要等内部模型请求被迫伪装成 Agent 请求。空字符串表示不发送系统提示，供纯用户消息探测使用。
         */
        public PromptPayload {
            systemPrompt = ContractChecks.text(systemPrompt, "systemPrompt", 4_000_000, true);
            revision = ContractChecks.identifier(revision, "prompt revision");
        }

        /**
         * 日志只暴露可关联的 revision，避免工作区规则、摘要或 Skill 正文经 record 默认输出泄漏。
         */
        @Override
        public String toString() {
            return "PromptPayload[systemPrompt=<redacted>, revision=" + revision + "]";
        }
    }

    /**
     * Provider 单次请求的超时所有权；长时 Agent Turn 在会话层持有取消和恢复，
     * 有界内部调用仍使用逐请求硬超时。
     */
    enum RequestDeadlinePolicy {
        /** Adapter 将 requestTimeout 用作调用绝对上限。 */
        CALL_BOUNDED,
        /** 调用方持有绝对 Deadline，Adapter 只执行响应空闲超时。 */
        TURN_MANAGED
    }

    /**
     * 一次模型轮次的冻结配置、提示信封、持久上下文、Tool 目录与续传状态。
     */
    record ModelRequest(
            ModelConfiguration configuration,
            PromptPayload prompt,
            List<ModelMessage> messages,
            List<ToolSpec> tools,
            Continuation continuation,
            int round,
            RequestDeadlinePolicy deadlinePolicy) {
        /**
         * 默认内部调用只发送一次并使用请求硬上限；持续恢复由会话层建立新请求。
         */
        public ModelRequest(ModelConfiguration configuration, PromptPayload prompt,
                            List<ModelMessage> messages, List<ToolSpec> tools,
                            Continuation continuation, int round) {
            this(configuration, prompt, messages, tools, continuation, round,
                    RequestDeadlinePolicy.CALL_BOUNDED);
        }

        /**
         * 复制消息和 Tool 列表并限制轮次，防止流式调用期间请求内容漂移。
         */
        public ModelRequest {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(prompt, "prompt");
            messages = ContractChecks.immutableList(messages, "messages");
            tools = ContractChecks.immutableList(tools, "tools");
            if (round < 1) {
                throw new IllegalArgumentException("round must be positive");
            }
            Objects.requireNonNull(deadlinePolicy, "deadlinePolicy");
        }
    }

    /**
     * Provider 原生续传协议及不透明状态；应用层不得解析其内容。
     */
    record Continuation(String protocol, String opaqueState) {
        /**
         * 对续传协议和不透明状态施加边界，避免无界响应污染下一轮请求。
         */
        public Continuation {
            protocol = ContractChecks.identifier(protocol, "protocol");
            opaqueState = ContractChecks.text(opaqueState, "opaqueState", 4_000_000, false);
        }

        /**
         * 对日志隐藏 Provider 恢复状态，避免响应标识或签名材料泄漏。
         */
        @Override
        public String toString() {
            return "Continuation[protocol=" + protocol + ", opaqueState=<redacted>]";
        }
    }

    /**
     * Provider 流式响应归一化后的事件闭集。
     */
    sealed interface ModelEvent permits TextDelta, ReasoningSummaryDelta, ReasoningBlockReady,
            ReasoningBlockReplaced, ToolCallReady, UsageEvent {
    }

    /**
     * 可立即展示但尚未代表持久提交的 assistant 文本增量。
     */
    record TextDelta(String text) implements ModelEvent {
        /**
         * 拒绝空增量并限制单帧大小，背压和批处理由应用层负责。
         */
        public TextDelta {
            text = ContractChecks.text(text, "text", 1_000_000, false);
        }
    }

    /**
     * Provider 允许公开的推理摘要增量，不包含隐藏推理原文。
     */
    record ReasoningSummaryDelta(String text) implements ModelEvent {
        /**
         * 仅允许有界非空摘要片段，禁止把未公开推理通道混入事件。
         */
        public ReasoningSummaryDelta {
            text = ContractChecks.text(text, "text", 1_000_000, false);
        }
    }

    /**
     * Provider 已完整关闭的原生 reasoning block，仅供 Java 历史和同身份后续请求使用。
     */
    record ReasoningBlockReady(ReasoningContent content) implements ModelEvent {
        /**
         * 要求状态机只发布完整块，避免签名或 encrypted_content 在流失败后进入历史。
         */
        public ReasoningBlockReady {
            Objects.requireNonNull(content, "content");
        }

        /**
         * 隐藏事件中携带的 opaque 原文，阻止受控日志意外打印 Provider 私有状态。
         */
        @Override
        public String toString() {
            return "ReasoningBlockReady[content=" + content + "]";
        }
    }

    /**
     * 用终态确认的完整原生块替换已提前关闭的同一 reasoning 块，不产生新的可见消息。
     */
    record ReasoningBlockReplaced(ReasoningContent previous, ReasoningContent replacement)
            implements ModelEvent {
        /**
         * replacement 只允许携带非空完整块；具体身份和历史唯一性由 AgentRound 校验。
         */
        public ReasoningBlockReplaced {
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(replacement, "replacement");
        }
    }

    /**
     * Provider 已完整组装并通过结构校验的单个 Tool 调用。
     */
    record ToolCallReady(String callId, String name, JsonObject arguments, int ordinal)
            implements ModelEvent {
        /**
         * 固化调用参数与原始顺序，Agent Loop 可在并发执行后确定性归并。
         */
        public ToolCallReady {
            callId = ContractChecks.identifier(callId, "callId");
            name = ContractChecks.identifier(name, "name");
            Objects.requireNonNull(arguments, "arguments");
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("tool ordinal is outside turn limits");
            }
        }
    }

    /**
     * Provider 流中报告的权威累计用量。
     */
    record UsageEvent(ModelUsage usage) implements ModelEvent {
        /**
         * 要求完整用量对象，避免把缺失计量误记为零。
         */
        public UsageEvent {
            Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * 流结束后的原因、下一轮续传状态与最终权威用量。
     */
    record ModelOutcome(FinishReason finishReason, Continuation continuation, ModelUsage usage) {
        /**
         * 完成原因必须明确；续传状态和用量是否存在由具体原因与 Adapter 约束。
         */
        public ModelOutcome {
            Objects.requireNonNull(finishReason, "finishReason");
        }
    }

    /**
     * 模型完成一次流式响应的规范原因。
     */
    enum FinishReason {
        /**
         * 模型自然结束且没有待执行 Tool。
         */
        STOP,
        /**
         * 模型请求执行一个或多个 Tool。
         */
        TOOL_CALLS,
        /**
         * 输出达到配置上限，结果可能不完整。
         */
        MAX_OUTPUT_TOKENS
    }

    /**
     * Provider 明确报告上下文窗口溢出时使用的稳定异常类别。
     */
    final class ContextOverflowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * 保留技术原因供边界内诊断，但固定公开消息且不捕获本地堆栈。
         */
        public ContextOverflowException(Throwable cause) {
            super("model context limit exceeded", cause, false, false);
        }
    }
}
