// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 当前宿主只观察执行句柄；持久业务状态和接管资格仍由 Core Store 与租约决定。 */
@Service
public final class ExecutionObservation {
    private final Map<AgentIdentity, Object> active = new ConcurrentHashMap<>();

    /**
     * 仅保留活动代际，不保存异常或业务正文；旧句柄的迟到清理不能删掉用户刚恢复的新执行。
     */
    public void observe(AgentExecutionHandle handle) {
        Objects.requireNonNull(handle, "handle");
        Object generation = new Object();
        active.put(handle.identity(), generation);
        handle.completion().whenComplete((outcome, failure) -> active.remove(handle.identity(), generation));
    }

    /**
     * 结果未知或宿主重启时不把 RUNNING 伪装成持久挂起；用户显式恢复后由 Core 对账并核验租约。
     * 其它实例可能仍持有执行权，因此该提示不授权直接重试副作用。
     */
    public boolean requiresResume(AgentExecutionStatus status) {
        Objects.requireNonNull(status, "status");
        return (TurnState.RUNNING.name().equals(status.state()) || TurnState.QUEUED.name().equals(status.state()))
                && !active.containsKey(status.identity());
    }
}
