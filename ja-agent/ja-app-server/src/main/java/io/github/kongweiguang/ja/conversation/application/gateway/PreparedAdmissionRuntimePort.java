// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.Optional;

/** 跨域消费者只读取准入时冻结的运行时快照，不依赖注册表实现或获得注册、释放权限。 */
@FunctionalInterface
public interface PreparedAdmissionRuntimePort {
    /** 完整执行身份必须精确匹配；未登记代表没有准入快照，不能由另一 Turn 的临时事实补齐。 */
    Optional<TaskCapabilityCeilingPort.RuntimeSnapshot> read(AgentIdentity identity);
}
