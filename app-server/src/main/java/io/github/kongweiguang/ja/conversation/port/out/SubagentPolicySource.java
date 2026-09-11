// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;

/**
 * 提供当前全局设置的窄端口；Thread 创建只读取一次，后续运行不重新读取全局配置。
 */
@FunctionalInterface
public interface SubagentPolicySource {
    /** 返回已完成校验的当前全局策略，不携带配置文件或 Secret。 */
    SubagentPolicy current();
}
