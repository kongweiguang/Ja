// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.model;

import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.infrastructure.model.anthropic.AnthropicMessagesAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiChatCompletionsAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.openai.OpenAiResponsesAdapter;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelCatalogClient;
import io.github.kongweiguang.ja.agent.infrastructure.model.shared.ModelTransport;
import io.github.kongweiguang.ja.agent.infrastructure.model.summary.HttpSummaryModel;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 穷举原生 Adapter/Summary 实现并持有单代共享 OkHttp 传输的 Factory。
 */
public final class ModelAdapterFactory implements ModelPort, SummaryModel.Factory, DeadlineCloseable {
    private final ModelTransport transport;
    private final Clock clock;

    /**
     * 生产使用 UTC Clock，同时保留显式注入以支持确定性组合测试。
     */
    public ModelAdapterFactory() {
        this(Clock.systemUTC());
    }

    /**
     * 组合根持有的 Clock 校验通过后才创建唯一传输。
     */
    public ModelAdapterFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transport = new ModelTransport();
    }

    /**
     * 在共享传输上为冻结 Provider/API 组合精确创建一个 Adapter。
     */
    public ModelAdapter create(ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return switch (configuration.api()) {
            case ANTHROPIC_MESSAGES -> new AnthropicMessagesAdapter(configuration, transport);
            case OPENAI_RESPONSES -> new OpenAiResponsesAdapter(configuration, transport);
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatCompletionsAdapter(configuration, transport);
        };
    }

    /**
     * 由实际选中的 Codec 发布原生附件闭集，application 不依据 Provider 名称猜测 wire 能力。
     */
    @Override
    public ModelPort.NativeAttachmentSupport nativeAttachmentSupport(ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        return switch (configuration.api()) {
            case ANTHROPIC_MESSAGES -> AnthropicMessagesAdapter.nativeAttachmentSupport();
            case OPENAI_RESPONSES -> OpenAiResponsesAdapter.nativeAttachmentSupport();
            case OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsAdapter.nativeAttachmentSupport();
        };
    }

    /**
     * 模型显式声明与实际 Codec 能力必须同时成立，避免仅凭协议类型向用户承诺不受支持的 Schema。
     */
    @Override
    public boolean supportsOutputSchema(ModelPort.ModelConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.supportsOutputSchema()) {
            return false;
        }
        return switch (configuration.api()) {
            case ANTHROPIC_MESSAGES, OPENAI_RESPONSES, OPENAI_CHAT_COMPLETIONS -> true;
        };
    }

    /**
     * 模型目录读取复用唯一共享传输及其取消、超时与关闭门禁；Factory 不把 API Key 返回给调用方，
     * 只把受限的模型标识投影交给 catalog 应用层。
     */
    @Override
    public CompletionStage<ModelPort.ModelDiscoveryResult> discoverModels(
            ModelPort.ModelDiscoveryRequest request, CancellationToken cancellationToken) {
        return ModelCatalogClient.discover(transport, Objects.requireNonNull(request, "request"),
                Objects.requireNonNull(cancellationToken, "cancellationToken"));
    }

    /**
     * 将请求作用域 HTTP Summary Adapter 绑定到本次请求精确解析的配置代际。
     */
    @Override
    public SummaryModel bind(SummaryModel.TurnBinding binding) {
        return new HttpSummaryModel(Objects.requireNonNull(binding, "binding"), transport, clock);
    }

    /**
     * 创建请求作用域 Codec Adapter，但共享 OkHttp 生命周期仍由本 Factory 持有。完成后关闭
     * Adapter 只释放请求级取消状态，不关闭后续 Turn 仍需使用的 Dispatcher 或连接池。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public InputTokenEstimate estimateInputTokens(
            ModelRequest request, CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        try (ModelAdapter adapter = create(request.configuration())) {
            return adapter.estimateInputTokens(request, cancellationToken);
        }
    }

    /**
     * 创建请求作用域 Codec Adapter，但共享 OkHttp 生命周期仍由本 Factory 持有。完成后关闭
     * Adapter 只释放请求级取消状态，不关闭后续 Turn 仍需使用的 Dispatcher 或连接池。异步完成回调
     * 是资源的真实 owner，因此这里对无法识别 CompletionStage 生命周期的静态规则作精确豁免。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public CompletionStage<ModelOutcome> start(ModelRequest request, ModelEventSink eventSink,
                                               CancellationToken cancellationToken) {
        Objects.requireNonNull(request, "request");
        ModelAdapter adapter = create(request.configuration());
        try {
            return adapter.start(request, eventSink, cancellationToken)
                    .whenComplete((ignored, failure) -> adapter.close());
        } catch (RuntimeException failure) {
            adapter.close();
            throw failure;
        }
    }

    /**
     * 关闭共享 Dispatcher、连接池、Deadline Scheduler 和全部活动 Call。
     */
    @Override
    public void close() {
        transport.close();
    }

    /**
     * 关闭共享 Provider 传输时沿用进程级绝对 Deadline。
     */
    @Override
    public void closeAt(long shutdownDeadlineNanos) {
        transport.closeAt(shutdownDeadlineNanos);
    }
}
