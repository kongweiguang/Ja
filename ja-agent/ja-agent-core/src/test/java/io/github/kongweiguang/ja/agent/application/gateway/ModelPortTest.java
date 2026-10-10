// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定与 Provider 品牌无关的显式 Wire API 闭集。 */
final class ModelPortTest {
    /** 任意自定义 Provider 都可显式选择当前三种 API，名称不参与路由。 */
    @Test
    void customProvidersSupportExactlyTheCurrentExplicitApis() {
        Set<ModelPort.Api> accepted = EnumSet.noneOf(ModelPort.Api.class);
        for (ModelPort.Api api : ModelPort.Api.values()) {
            try {
                configuration(api);
                accepted.add(api);
            } catch (IllegalArgumentException ignored) {
                // 未显式列入通用 Provider 契约的新 API 必须保持拒绝。
            }
        }
        assertEquals(EnumSet.of(
                ModelPort.Api.ANTHROPIC_MESSAGES,
                ModelPort.Api.OPENAI_RESPONSES,
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS), accepted);
    }

    /** 冻结模型请求必须携带真实 Secret 和显式生成参数，防止 keyless 或默认值旁路进入 HTTP Adapter。 */
    @Test
    void rejectsMissingCredentialAndGenerationFallbacks() {
        ModelPort.ModelConfiguration valid = configuration(ModelPort.Api.OPENAI_RESPONSES);

        assertThrows(NullPointerException.class, () -> new ModelPort.ModelConfiguration(
                valid.providerId(), valid.modelId(), valid.configGeneration(), valid.api(), valid.model(),
                valid.baseUri(), null, valid.connectTimeout(), valid.requestTimeout(),
                valid.inputModalities(), valid.generation()));
        assertThrows(IllegalArgumentException.class, () -> new ModelPort.ModelConfiguration(
                valid.providerId(), valid.modelId(), valid.configGeneration(), valid.api(), valid.model(),
                valid.baseUri(), "", valid.connectTimeout(), valid.requestTimeout(),
                valid.inputModalities(), valid.generation()));
        assertThrows(NullPointerException.class, () -> new ModelPort.ModelConfiguration(
                valid.providerId(), valid.modelId(), valid.configGeneration(), valid.api(), valid.model(),
                valid.baseUri(), valid.apiKey(), valid.connectTimeout(), valid.requestTimeout(),
                valid.inputModalities(), null));
    }

    /** 用户自建网关可使用远程 IPv4/IPv6 HTTP；该纯构造测试不建立任何外部网络连接。 */
    @Test
    void acceptsRemoteHttpProviderEndpoints() {
        for (String endpoint : Set.of("http://203.0.113.7:8080/v1", "http://[2001:db8::7]:8080/v1")) {
            ModelPort.ModelConfiguration configured = new ModelPort.ModelConfiguration(
                    "provider_remote", "model_remote", "cfg_remote", ModelPort.Api.OPENAI_RESPONSES,
                    "remote-model", URI.create(endpoint), "test-secret", Duration.ofSeconds(1),
                    Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                    ModelPort.GenerationOptions.defaults());
            assertEquals(endpoint, configured.baseUri().toString());
        }
    }

    /** 非有限采样参数必须在请求抵达 Provider SPI 前拒绝，不发起任何模型副作用。 */
    @Test
    void rejectsNonFiniteSamplingValuesBeforeProviderDispatch() {
        AtomicInteger providerCalls = new AtomicInteger();
        ModelPort provider = new ModelPort() {
            /** 采样参数校验必须先于估算器，以免无效请求越过 Provider 准入边界。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(ModelPort.ModelRequest request,
                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                return new ModelPort.InputTokenEstimate(1, 1, "0".repeat(64));
            }

            /** 记录真实调度次数，以区分本地拒绝与 Provider 已执行。 */
            @Override
            public CompletableFuture<ModelPort.ModelOutcome> start(ModelPort.ModelRequest request,
                    io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink events,
                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                providerCalls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }
        };

        assertThrows(IllegalArgumentException.class, () -> dispatch(provider, Double.NaN, null));
        assertThrows(IllegalArgumentException.class, () -> dispatch(provider, null, Double.NaN));
        assertEquals(0, providerCalls.get());
    }

    /** 构造只用于配对校验的 loopback 快照，避免测试触发外部网络。 */
    private static ModelPort.ModelConfiguration configuration(ModelPort.Api api) {
        return new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test",
                api, "test-model",
                URI.create("http://127.0.0.1:1"), "test-secret",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
    }

    /** 构造完整无网络请求，验证配置校验失败会短路 Provider 调用。 */
    private static void dispatch(ModelPort provider, Double temperature, Double topP) {
        ModelPort.GenerationOptions generation = new ModelPort.GenerationOptions(temperature, topP, null, null);
        ModelPort.ModelConfiguration baseline = configuration(ModelPort.Api.OPENAI_RESPONSES);
        ModelPort.ModelConfiguration configured = new ModelPort.ModelConfiguration(
                baseline.providerId(), baseline.modelId(), baseline.configGeneration(), baseline.api(), baseline.model(),
                baseline.baseUri(), baseline.apiKey(), baseline.connectTimeout(), baseline.requestTimeout(),
                baseline.inputModalities(), generation);
        ModelPort.ModelRequest request = new ModelPort.ModelRequest(configured,
                new ModelPort.PromptPayload("", "prompt_revision"), List.of(), List.of(), null, 1);
        provider.start(request, event -> CompletableFuture.completedFuture(null),
                io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.none())
                .toCompletableFuture().join();
    }
}
