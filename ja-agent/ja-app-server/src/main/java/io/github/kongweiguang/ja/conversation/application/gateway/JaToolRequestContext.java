// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 固定一次 Ja Tool 工厂读取所需的业务请求视图，不承担注册、排序或生命周期调度。
 *
 * @param turn 执行轮次
 * @param preferences 偏好配置
 * @param configGeneration 冻结的配置代际标识
 * @param clarificationEnabled 是否启用澄清交互
 * @param deadline 截止时间约束
 * @param taskKind 任务类型（可选）
 */
public record JaToolRequestContext(TurnRuntimeRequest turn,
                                   ThreadPreferences preferences,
                                   String configGeneration,
                                   boolean clarificationEnabled,
                                   Instant deadline,
                                   Optional<TaskCapabilityCeilingPort.Kind> taskKind) {
    /**
     * 冻结解析阶段取得的偏好、配置代际和任务身份，保证工具绑定不跨请求复用。
     */
    public JaToolRequestContext {
        Objects.requireNonNull(turn, "turn");
        Objects.requireNonNull(preferences, "preferences");
        configGeneration = io.github.kongweiguang.ja.agent.domain.validation.ContractChecks
                .configurationGeneration(configGeneration);
        Objects.requireNonNull(deadline, "deadline");
        taskKind = Objects.requireNonNull(taskKind, "taskKind");
    }
}
