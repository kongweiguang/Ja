// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;

import java.time.Instant;
import java.util.Optional;

/**
 * 中间件状态快照的应用层存储合同；revision 连续性由恢复用例校验，JSON 细节由基础设施负责。
 */
public interface MiddlewareStateRecordCodec {
    /**
     * 中间件状态使用独立记录类型，避免与扩展事实投影相互覆盖。
     */
    String RECORD_TYPE = "agent.middleware.state";

    /**
     * 外层版本只描述固定信封，注册项自身的状态 schema 由 registration 独立声明。
     */
    int ENVELOPE_VERSION = 1;

    /**
     * 把非空 patch 编成下一个完整快照；净效果为空时返回 empty 并且不推进 revision。
     */
    Optional<AgentStore.RecordWrite> encodePatch(
            String recordId,
            ExtensionRegistry.MiddlewareRegistration registration,
            AgentMiddleware.ExtensionStateView current,
            AgentMiddleware.ExtensionStatePatch patch,
            Instant occurredAt);

    /**
     * 解码尚未分配 Store 序号的快照，供同事务参与者校验即将写入的状态。
     */
    AgentMiddleware.ExtensionStateView decode(AgentStore.RecordWrite record);

    /**
     * 解码紧随当前快照的记录，并由实现拒绝 namespace、schema 或 revision 不连续。
     */
    AgentMiddleware.ExtensionStateView decodeNext(
            AgentStore.StoredRecord record,
            ExtensionRegistry.MiddlewareRegistration registration,
            AgentMiddleware.ExtensionStateView current);
}
