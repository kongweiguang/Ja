// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;


import java.util.Optional;

/**
 * 解析当前不可变的 provider、prompt、Skill、tools 和宿主事务参与者。
 * 每个请求单独冻结可执行能力，恢复时核验原绑定，避免复用进程内的旧配置或密钥租约。
 */
@FunctionalInterface
public interface AgentRequestRuntimeResolver {
    /**
     * 将恢复任务重新绑定到当前实现，并拒绝任何持久化绑定不一致的情况。
     * 无持久执行时接收空快照；有执行时必须按当前实现重建运行时并拒绝绑定不匹配。
     */
    AgentRequestRuntime resolve(AgentRuntimeRequest request, ExtensionRegistry.Snapshot baseExtensions,
                                Optional<AgentStore.ExecutionSnapshot> recoveredExecution);
}

