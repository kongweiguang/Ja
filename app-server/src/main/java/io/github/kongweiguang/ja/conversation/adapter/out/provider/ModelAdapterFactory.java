// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider;

import io.github.kongweiguang.ja.conversation.adapter.out.provider.anthropic.AnthropicMessagesAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.openai.OpenAiChatCompletionsAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.openai.OpenAiResponsesAdapter;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.ModelTransport;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.summary.HttpSummaryModel;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.port.out.ModelEventSink;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;

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
        this.transport = new ModelTransport(clock);
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
