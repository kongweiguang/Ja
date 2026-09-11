// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.port.out.SubagentPolicySource;

import java.util.Objects;

/**
 * 将配置 Owner 的全局代际快照投影为 Thread 创建所需的窄策略端口。
 *
 * <p>每次读取都短暂持有 general 配置代际，确保策略来自同一份 CAS/Watcher 视图；
 * 不缓存设置，也不把 workspace overlay 引入全局子智能体策略。</p>
 */
public final class ConfigurationSubagentPolicySource implements SubagentPolicySource {
    private final ConfigurationGenerationUseCase configurations;

    /** 固定唯一配置读取 Owner，避免会话创建绕过配置应用服务读取文件。 */
    public ConfigurationSubagentPolicySource(ConfigurationGenerationUseCase configurations) {
        this.configurations = Objects.requireNonNull(configurations, "configurations");
    }

    /** 读取并立即释放全局配置代际，只返回可持久化的开关和成对模型引用。 */
    @Override
    public SubagentPolicy current() {
        try (ConfigurationGenerationLease lease = configurations.acquire(null)) {
            ConfigurationGenerationSnapshot.SubagentPolicy policy = lease.view().subagentPolicy();
            return new SubagentPolicy(policy.enabled(), policy.providerId().orElse(null),
                    policy.modelId().orElse(null), policy.reasoningLevel().map(Enum::name)
                            .map(value -> value.toLowerCase(java.util.Locale.ROOT)).orElse(null));
        }
    }
}
