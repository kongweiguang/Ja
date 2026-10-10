// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

/**
 * 为执行循环提供可续租会话租约，避免把创建时的过期快照用于后续提交。
 */
public interface ExecutionLease {
    /**
     * 返回最近一次续租确认的租约快照，提交操作必须读取该最新值。
     */
    AgentStore.Lease current();

    /**
     * 在模型或工具派发前同步确认租约仍有效，并返回校验后的最新快照。
     */
    AgentStore.Lease assertDispatchAuthority();
}
