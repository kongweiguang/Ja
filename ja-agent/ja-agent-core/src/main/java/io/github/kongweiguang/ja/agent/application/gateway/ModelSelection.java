// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 恢复时用于重新取得当前凭据的非秘密模型选择身份。
 *
 * @param providerId 模型供应商标识
 * @param modelId 模型标识
 * @param api 模型 API 协议
 * @param upstreamModel 上游模型
 */
public record ModelSelection(String providerId,
                             String modelId,
                             ModelPort.Api api,
                             String upstreamModel) {
    /**
     * 不绑定配置代际或密钥，使凭据轮换不改变用户选定的模型。
     */
    public ModelSelection {
        providerId = ContractChecks.identifier(providerId, "providerId");
        modelId = ContractChecks.identifier(modelId, "modelId");
        Objects.requireNonNull(api, "api");
        upstreamModel = ContractChecks.text(upstreamModel, "upstreamModel", 512, false);
    }

    /**
     * 只从已解析配置抽取无密钥路由字段，不持久化 endpoint、generation 或凭据。
     */
    public static ModelSelection from(ModelPort.ModelConfiguration configuration) {
        ModelPort.ModelConfiguration model = Objects.requireNonNull(configuration, "configuration");
        return new ModelSelection(model.providerId(), model.modelId(), model.api(), model.model());
    }

    /**
     * 拒绝恢复时悄然切到另一个供应商、协议或上游模型。
     */
    public boolean matches(ModelPort.ModelConfiguration configuration) {
        return equals(from(configuration));
    }
}
