// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationData;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcParams;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 本次执行的显式选择只生成准入快照，不改写 Thread 偏好或配置文件。
 *
 * @param modelId 本次执行覆盖的稳定模型标识，未设置时沿用 Thread 默认值
 * @param accessMode 本次执行覆盖的访问模式，未设置时沿用 Thread 默认值
 * @param outputSchema 本次执行要求的终答 Schema（可选）
 */
record RpcTurnRuntimeOverrides(String modelId,
                               AccessMode accessMode,
                               Optional<JsonObject> outputSchema) {
    /**
     * 严格解析嵌套闭集，空对象保持普通执行语义；仅解析 Schema 时才取得应用 codec，
     * 使常规 Turn 不依赖可选的结构化终答能力。
     */
    static RpcTurnRuntimeOverrides parse(ObjectNode params, Supplier<JsonValueCodec> codecSupplier) {
        Objects.requireNonNull(codecSupplier, "codecSupplier");
        if (!params.has("runtimeOverrides")) {
            return new RpcTurnRuntimeOverrides(null, null, Optional.empty());
        }
        ObjectNode overrides = RpcParams.object(params, "runtimeOverrides");
        RpcParams.requireOnly(overrides, "modelId", "accessMode", "outputSchema");
        String selectedModel = overrides.has("modelId")
                ? RpcParams.identifier(overrides, "modelId", "model_", 100) : null;
        AccessMode selectedAccess = overrides.has("accessMode")
                ? switch (RpcParams.text(overrides, "accessMode", 32, false)) {
                    case "approval_required" -> AccessMode.APPROVAL_REQUIRED;
                    case "full_access" -> AccessMode.FULL_ACCESS;
                    default -> throw JaRpcException.invalidParams();
                } : null;
        Optional<JsonObject> schema = Optional.empty();
        if (overrides.has("outputSchema")) {
            ObjectNode value = RpcParams.object(overrides, "outputSchema");
            JsonValueCodec codec = Objects.requireNonNull(codecSupplier.get(), "jsonValueCodec");
            schema = Optional.of((JsonObject) codec.decode(value.toString()));
        }
        return new RpcTurnRuntimeOverrides(selectedModel, selectedAccess, schema);
    }

    /** 只有影响模型或权限的显式选择才解除“必须等于 Thread 默认值”的校验。 */
    boolean overridesPreferences() {
        return modelId != null || accessMode != null;
    }

    /** 从 Java 配置 Owner 的脱敏目录解析稳定模型 ID，拒绝缺失或跨 Provider 歧义。 */
    ThreadPreferences select(ThreadPreferences defaults, Path workspaceRoot, ConfigurationUseCase configurations) {
        String providerId = defaults.providerId();
        String selectedModel = defaults.modelId();
        String reasoning = defaults.reasoningLevel();
        if (modelId != null) {
            ConfigurationData.Value providers = configurations.read(workspaceRoot).effective().properties().get("providers");
            String foundProvider = null;
            String foundReasoning = null;
            if (providers instanceof ConfigurationData.ArrayValue entries) {
                for (ConfigurationData.Value entry : entries.values()) {
                    if (!(entry instanceof ConfigurationData.ObjectValue provider)
                        || !(provider.properties().get("models") instanceof ConfigurationData.ArrayValue models)) {
                        continue;
                    }
                    for (ConfigurationData.Value candidate : models.values()) {
                        if (candidate instanceof ConfigurationData.ObjectValue model
                            && modelId.equals(text(model, "model_id"))) {
                            if (foundProvider != null) {
                                throw JaRpcException.invalidParams();
                            }
                            foundProvider = text(provider, "provider_id");
                            foundReasoning = text(model, "default_reasoning_level");
                        }
                    }
                }
            }
            if (foundProvider == null) {
                throw JaRpcException.invalidParams();
            }
            providerId = RpcParams.identifier(foundProvider, "provider_", 100);
            selectedModel = modelId;
            reasoning = Objects.equals(defaults.modelId(), modelId) ? reasoning : foundReasoning;
        }
        return new ThreadPreferences(providerId, selectedModel, reasoning,
                accessMode == null ? defaults.accessMode() : accessMode,
                defaults.collaborationMode(), defaults.titleSource());
    }

    /** 读取配置中的文本而不把 null、布尔或其它标量强制转换为路由身份。 */
    private static String text(ConfigurationData.ObjectValue value, String key) {
        return value.properties().get(key) instanceof ConfigurationData.TextValue text ? text.value() : null;
    }
}
