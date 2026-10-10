// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 安全点运行时解析输入，不暴露应用 Loop 命令或持久化密钥。
 *
 * @param kind 类型分类
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param modelOverride 当前请求覆盖使用的模型配置（可选）
 * @param contextAttributes 上下文属性集合
 * @param cancellationToken 取消控制令牌
 * @param requiredModelSelection 必需模型选择（可选）
 */
public record AgentRuntimeRequest(Kind kind,
                                  AgentIdentity identity,
                                  Optional<ModelPort.ModelConfiguration> modelOverride,
                                  Map<String, String> contextAttributes,
                                  CancellationToken cancellationToken,
                                  Optional<ModelSelection> requiredModelSelection) {
    /**
     * 冻结仅含数据的宿主属性，并把密钥配置限制在单次运行时解析中。
     */
    public AgentRuntimeRequest {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        requiredModelSelection = Objects.requireNonNull(requiredModelSelection, "requiredModelSelection");
        requiredModelSelection.ifPresent(value -> Objects.requireNonNull(value, "requiredModelSelection value"));
        modelOverride = Objects.requireNonNull(modelOverride, "modelOverride");
        Objects.requireNonNull(modelOverride, "modelOverride").ifPresent(Objects::requireNonNull);
        Objects.requireNonNull(contextAttributes, "contextAttributes");
        if (contextAttributes.size() > 256) {
            throw new IllegalArgumentException("too many runtime context attributes");
        }
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        contextAttributes.forEach((key, value) -> {
            String safeKey = ContractChecks.identifier(key, "runtime context attribute key");
            String safeValue = ContractChecks.text(value, "runtime context attribute value", 16_384, true);
            if (attributes.putIfAbsent(safeKey, safeValue) != null) {
                throw new IllegalArgumentException("duplicate runtime context attribute");
            }
        });
        if (attributes.entrySet().stream()
                    .mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum() > 65_536) {
            throw new IllegalArgumentException("runtime context attributes exceed the request bound");
        }
        contextAttributes = Map.copyOf(attributes);
    }

    /**
     * 为不可取消的解析验证提供简写；正式 Engine 请求必须传入其实际取消令牌。
     */
    public AgentRuntimeRequest(Kind kind, AgentIdentity identity,
                               Optional<ModelPort.ModelConfiguration> modelOverride,
                               Map<String, String> contextAttributes, CancellationToken cancellationToken) {
        this(kind, identity, modelOverride, contextAttributes, cancellationToken, Optional.empty());
    }

    /**
     * 为不涉及宿主副作用的同步解析测试提供明确不可取消请求。
     */
    public AgentRuntimeRequest(Kind kind, AgentIdentity identity,
                               Optional<ModelPort.ModelConfiguration> modelOverride, Map<String, String> contextAttributes) {
        this(kind, identity, modelOverride, contextAttributes, CancellationToken.none(), Optional.empty());
    }

    /**
     * 请求来源影响宿主事务投影，恢复时从原准入绑定复用而不重新猜测。
     */
    public enum Kind {
        /**
         * 新的可见用户消息准入。
         */
        USER_INPUT,
        /**
         * 宿主生成的内部续跑，不新增可见用户消息。
         */
        INTERNAL,
        /**
         * 独立会话压缩执行，不建立普通 Turn 投影。
         */
        COMPACTION
    }
}
