// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

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

    /** 构造只用于配对校验的 loopback 快照，避免测试触发外部网络。 */
    private static ModelPort.ModelConfiguration configuration(ModelPort.Api api) {
        return new ModelPort.ModelConfiguration(
                "provider_test", "model_test", "cfg_test",
                api, "test-model",
                URI.create("http://127.0.0.1:1"), "test-secret",
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
    }
}
