// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.test;

import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 将测试提供的启动行为适配到当前 ModelPort，并让 token 估算保持本地且确定。
 */
public final class ModelPortTestSupport {
    /** 测试适配器只提供静态构造入口，禁止无意义实例化。 */
    private ModelPortTestSupport() {
    }

    /**
     * 提供稳定的零 token 估算，让只验证请求启动行为的用例不必实现 Provider 估算逻辑。
     */
    public static ModelPort of(Starter starter) {
        Objects.requireNonNull(starter, "starter");
        return new ModelPort() {
            /** 本夹具不验证准入估算，因此不读取请求内容或引入模型分词逻辑。 */
            @Override
            public InputTokenEstimate estimateInputTokens(ModelRequest request, CancellationToken cancellationToken) {
                return new InputTokenEstimate(0, 0, "0".repeat(64));
            }

            /** 只委托用例显式提供的启动行为，不为测试夹具补造默认 Provider 行为。 */
            @Override
            public CompletionStage<ModelPort.ModelOutcome> start(ModelPort.ModelRequest request,
                    ModelEventSink eventSink, CancellationToken cancellationToken) {
                return starter.start(request, eventSink, cancellationToken);
            }
        };
    }

    /** 隔离测试夹具提供的单次模型启动行为。 */
    @FunctionalInterface
    public interface Starter {
        /** 执行用例定义的模型响应行为，保持 Provider 结果由测试显式控制。 */
        CompletionStage<ModelPort.ModelOutcome> start(ModelPort.ModelRequest request,
                ModelEventSink eventSink, CancellationToken cancellationToken);
    }
}
