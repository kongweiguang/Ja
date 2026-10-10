// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证文本宿主只需提供模型、真实上下文窗口和持久化端口即可组装标准循环。 */
final class AgentEngineBuilderDefaultsTest {
    /** 缺省附件读取器必须保持禁用，Tool 投影缺省值不能阻止纯文本 Engine 构造。 */
    @Test
    void buildsTextOnlyEngineWithoutAttachmentOrProjectionOverrides() {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("toString")) {
                        return "builder-test-store";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "upstream_test",
                URI.create("https://example.invalid"), "secret_test", Duration.ofSeconds(1),
                Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 128, null));

        assertDoesNotThrow(() -> {
            try (AgentEngine ignored = new AgentEngineBuilder().store(store).model(model)
                    .contextBudget(new ContextBudget(32_768, 4_096, true)).build()) {
                // 构造验证不触发 Provider 请求或尝试读取附件。
            }
        });
    }

    /**
     * 标准模型配置与自定义解析器只能选择一个运行时 owner，冲突必须在扩展装配前拒绝。
     */
    @Test
    void rejectsCustomResolverBeforeExtensionAssemblyWhenStandardConfigurationIsPresent() {
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("store access must not begin before runtime validation");
                });
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration("provider_test", "model_test",
                "cfg_test", ModelPort.Api.OPENAI_RESPONSES, "upstream_test",
                URI.create("https://example.invalid"), "secret_test", Duration.ofSeconds(1),
                Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 128, null));
        AgentRequestRuntimeResolver resolver = (AgentRequestRuntimeResolver) Proxy.newProxyInstance(
                AgentRequestRuntimeResolver.class.getClassLoader(), new Class<?>[]{AgentRequestRuntimeResolver.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("custom runtime must not be resolved during conflicting assembly");
                });

        assertThrows(IllegalStateException.class, () -> new AgentEngineBuilder()
                .store(store)
                .model(model)
                .contextBudget(new ContextBudget(32_768, 4_096, true))
                .runtimeResolver(resolver)
                .extensions(ignored -> {
                    throw new AssertionError("extensions must not be created before runtime validation");
                })
                .build());
    }
}
