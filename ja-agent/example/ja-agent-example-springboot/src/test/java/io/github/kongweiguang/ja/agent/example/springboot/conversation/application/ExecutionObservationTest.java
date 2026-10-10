// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 宿主观察必须与持久状态分开，且旧执行的迟到完成不能吞掉新恢复句柄。 */
class ExecutionObservationTest {
    private static final AgentIdentity IDENTITY = new AgentIdentity("tenant_demo", "thread_observation", "turn_observation", "user_demo");

    /** 异常清理和重启丢失句柄只提示核实，不伪造持久终态，也不影响另一个 Thread。 */
    @Test
    void observesFailureWithoutChangingDurableState() {
        ExecutionObservation observation = new ExecutionObservation();
        AgentExecutionStatus running = status(IDENTITY, "RUNNING");
        assertTrue(observation.requiresResume(running));
        CompletableFuture<AgentExecutionOutcome> completion = new CompletableFuture<>();
        observation.observe(handle(completion));
        assertFalse(observation.requiresResume(running));
        assertTrue(observation.requiresResume(status(new AgentIdentity("tenant_demo", "thread_other", "turn_other", "user_demo"), "RUNNING")));
        completion.completeExceptionally(new IllegalStateException("commit result unknown"));
        assertTrue(observation.requiresResume(running));
        assertFalse(observation.requiresResume(status(IDENTITY, "COMPLETED")));
        assertFalse(observation.requiresResume(status(IDENTITY, "SUSPENDED")));
    }

    /** 用户刚恢复后旧句柄再完成时，只能清理自己那一代，不能让页面误报新执行丢失。 */
    @Test
    void lateCompletionCannotClearResumedExecution() {
        ExecutionObservation observation = new ExecutionObservation();
        CompletableFuture<AgentExecutionOutcome> previous = new CompletableFuture<>();
        CompletableFuture<AgentExecutionOutcome> resumed = new CompletableFuture<>();
        observation.observe(handle(previous));
        observation.observe(handle(resumed));
        previous.completeExceptionally(new IllegalStateException("old runner failed"));
        assertFalse(observation.requiresResume(status(IDENTITY, "RUNNING")));
        resumed.completeExceptionally(new IllegalStateException("new runner failed"));
        assertTrue(observation.requiresResume(status(IDENTITY, "RUNNING")));
    }

    /** 测试沿公共只读句柄观察完成，不借用 Core 内部运行登记。 */
    private static AgentExecutionHandle handle(CompletableFuture<AgentExecutionOutcome> completion) {
        return new AgentExecutionHandle(IDENTITY, completion, CompletableFuture.completedFuture(Optional.empty()));
    }

    /** 只构造持久状态快照，宿主提示不得改写其中的 state 或修订水位。 */
    private static AgentExecutionStatus status(AgentIdentity identity, String state) {
        return new AgentExecutionStatus(identity, state, 1, 1, Instant.EPOCH);
    }
}
