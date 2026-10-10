// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.gateway;

import java.util.Objects;

/**
 * 在分发请求前，根据冻结的模型配置解析对应的 provider adapter。
 * providerId 必须精确落到一个实现，缺失或歧义应失败，不能静默切换到其他供应商。
 */
@FunctionalInterface
public interface ModelPortResolver {
    /**
     * 在边界校验解析结果，避免缺失 provider 被当作隐式空操作。
     * 同时校验 resolver、配置与解析结果，使缺少真实模型能力在派发前明确失败。
     */
    static ModelPort requireResolved(ModelPortResolver resolver, ModelPort.ModelConfiguration configuration) {
        return Objects.requireNonNull(Objects.requireNonNull(resolver, "resolver")
                .resolve(Objects.requireNonNull(configuration, "configuration")), "resolved model port");
    }

    /**
     * 为此模型配置选择准确的 adapter；发生失败时直接暴露，不切换到其他 provider。
     * 返回值代表该冻结配置唯一对应的端口，重试策略留给执行循环而不是跨供应商兜底。
     */
    ModelPort resolve(ModelPort.ModelConfiguration configuration);
}
