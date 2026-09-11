// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;

import java.util.Optional;

/** 读取 Thread 创建时冻结策略的窄端口；不暴露配置文件或 Thread 公共投影。 */
public interface SubagentPolicyRepository {
    /** 读取单个 Thread 的不可变策略；缺失表示数据库损坏而非跟随父任务。 */
    Optional<SubagentPolicy> find(String threadId);
}
