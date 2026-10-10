// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnIdentity;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;

import java.time.Instant;

/**
 * 状态以持久事实为准，公开修订号统一使用 Thread/Turn 命名。
 *
 * @param identity 对话与执行的稳定身份
 * @param state 当前状态
 * @param threadRevision 对话修订版本
 * @param turnMutationVersion 当前执行的状态变更版本
 * @param updatedAt 最后更新时间
 * @param resumeRequired 当前宿主无活动句柄，需显式恢复核实；不改变持久状态或授予副作用重试许可
 */
public record TurnStatusView(TurnIdentity identity,
                             String state,
                             long threadRevision,
                             long turnMutationVersion,
                             Instant updatedAt,
                             boolean resumeRequired) {
    /**
     * 分开投影持久状态和宿主观察提示，不把未知结果伪装成已完成或已挂起。
     */
    public static TurnStatusView from(AgentExecutionStatus status, boolean resumeRequired) {
        return new TurnStatusView(TurnIdentity.from(status.identity()), status.state(), status.threadRevision(),
                status.mutationVersion(), status.updatedAt(), resumeRequired);
    }
}
