// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationData;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationScope;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 本次执行的模型选择必须来自 Java catalog，不能更改用户或 Thread 的默认值。 */
class RpcTurnRuntimeOverridesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonValueCodec jsonValueCodec = new TestJsonValueCodec();

    /** 跨 Provider 选择采用目标模型的默认推理等级，完整配置身份不能被展示别名冒充。 */
    @Test
    void selectsStableConfiguredModelWithoutWritingConfiguration() throws Exception {
        ThreadPreferences defaults = defaults();
        AtomicInteger reads = new AtomicInteger();
        ConfigurationUseCase configuration = configuration(reads, model("provider_other", "model_other", "high"));
        RpcTurnRuntimeOverrides overrides = parse("""
                {"runtimeOverrides":{"modelId":"model_other","accessMode":"full_access"}}
                """);
        ThreadPreferences selected = overrides.select(defaults, Path.of("C:/workspace"), configuration);
        assertEquals("provider_other", selected.providerId());
        assertEquals("model_other", selected.modelId());
        assertEquals("high", selected.reasoningLevel());
        assertEquals(AccessMode.FULL_ACCESS, selected.accessMode());
        assertEquals(AccessMode.APPROVAL_REQUIRED, defaults.accessMode());
        assertEquals("model_default", defaults.modelId());
        assertEquals(1, reads.get());
    }

    /** 输出约束不改变执行偏好；未知嵌套字段和伪造沙箱名称必须在准入前拒绝。 */
    @Test
    void schemaOnlyDoesNotOverridePreferencesAndRejectsUnknownFields() throws Exception {
        assertFalse(parse("{\"runtimeOverrides\":{\"outputSchema\":{\"type\":\"object\"}}}")
                .overridesPreferences());
        assertThrows(JaRpcException.class, () -> parse("{\"runtimeOverrides\":{\"sandbox\":\"workspace\"}}"));
        assertThrows(JaRpcException.class, () -> parse("{\"runtimeOverrides\":{\"accessMode\":\"sandbox\"}}"));
    }

    /**
     * 只在 Schema 真正出现时解析端口，避免普通 Turn 依赖此可选 codec 能力。
     */
    @Test
    void resolvesJsonValueCodecOnlyForOutputSchema() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        RpcTurnRuntimeOverrides.parse((ObjectNode) mapper.readTree("{}"), () -> {
            resolutions.incrementAndGet();
            return jsonValueCodec;
        });
        RpcTurnRuntimeOverrides.parse((ObjectNode) mapper.readTree("{\"runtimeOverrides\":{}}"), () -> {
            resolutions.incrementAndGet();
            return jsonValueCodec;
        });
        assertEquals(0, resolutions.get());

        RpcTurnRuntimeOverrides.parse((ObjectNode) mapper.readTree(
                "{\"runtimeOverrides\":{\"outputSchema\":{\"type\":\"object\"}}}"), () -> {
            resolutions.incrementAndGet();
            return jsonValueCodec;
        });
        assertEquals(1, resolutions.get());
    }

    /** 缺失及重复路由身份均失败关闭，不能随机挑选一个 Provider 执行。 */
    @Test
    void rejectsMissingAndAmbiguousModelIdentity() throws Exception {
        RpcTurnRuntimeOverrides overrides = parse("{\"runtimeOverrides\":{\"modelId\":\"model_other\"}}");
        assertThrows(JaRpcException.class, () -> overrides.select(defaults(), Path.of("C:/workspace"),
                configuration(new AtomicInteger(), model("provider_other", "model_missing", "high"))));
        assertThrows(JaRpcException.class, () -> overrides.select(defaults(), Path.of("C:/workspace"),
                configuration(new AtomicInteger(), model("provider_one", "model_other", "high"),
                        model("provider_two", "model_other", "low"))));
    }

    /** 只通过公开 Wire 结构解析并显式注入应用端口，测试不会依赖 Jackson 具体实现。 */
    private RpcTurnRuntimeOverrides parse(String json) throws Exception {
        return RpcTurnRuntimeOverrides.parse((ObjectNode) mapper.readTree(json), () -> jsonValueCodec);
    }

    /** 默认值保持独立不可变对象，使覆盖泄漏可以被直接发现。 */
    private static ThreadPreferences defaults() {
        return new ThreadPreferences("provider_default", "model_default", "medium",
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.MANUAL);
    }

    /** 夹具使用真实配置字段 model_id/provider_id，防止客户端字段假设导致选择失效。 */
    private static ConfigurationData.ObjectValue model(String providerId, String modelId, String reasoning) {
        return new ConfigurationData.ObjectValue(Map.of(
                "provider_id", new ConfigurationData.TextValue(providerId),
                "models", new ConfigurationData.ArrayValue(List.of(new ConfigurationData.ObjectValue(Map.of(
                        "model_id", new ConfigurationData.TextValue(modelId),
                        "default_reasoning_level", new ConfigurationData.TextValue(reasoning)))))));
    }

    /** 仅允许读取脱敏 catalog，任何配置写调用都会使测试失败。 */
    private static ConfigurationUseCase configuration(AtomicInteger reads, ConfigurationData.Value... providers) {
        ConfigurationData.Document document = new ConfigurationData.Document(Map.of(
                "providers", new ConfigurationData.ArrayValue(List.of(providers))));
        ConfigurationData.Layer user = new ConfigurationData.Layer(ConfigurationScope.USER, true, true,
                "user_version", ConfigurationData.LayerStatus.VALID, document);
        ConfigurationData.Layer project = new ConfigurationData.Layer(ConfigurationScope.PROJECT, false, true,
                "project_version", ConfigurationData.LayerStatus.MISSING, null);
        ConfigurationData.ReadResult result = new ConfigurationData.ReadResult(true, user, project, document,
                Map.of(), "credentials_version", List.of(), List.of(), Map.of());
        return (ConfigurationUseCase) Proxy.newProxyInstance(ConfigurationUseCase.class.getClassLoader(),
                new Class<?>[]{ConfigurationUseCase.class}, (proxy, method, arguments) -> {
                    if (!"read".equals(method.getName())) {
                        throw new AssertionError("run override must not mutate configuration");
                    }
                    reads.incrementAndGet();
                    return result;
                });
    }
}
