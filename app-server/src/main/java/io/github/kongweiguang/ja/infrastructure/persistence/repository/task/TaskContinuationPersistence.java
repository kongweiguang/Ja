// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;

import java.time.Instant;
import java.util.Objects;

/** Conversation hidden Turn admission 的 Task 投影扩展，必须和 Turn 事实共用一个事务。 */
public final class TaskContinuationPersistence {
    /**
     * 内部 Goal/Plan Turn 绕过普通 TaskCoordinator，因此在其 admission 事务内显式重排 Task。
     * admission 只表示已进入 FIFO，专用 CAS 不限制旧状态但不伪造 RUNNING，真实运行状态仍由
     * 后续 StateChanged 事件投影；当前 revision 继续防止终态或并发执行被静默覆盖。
     */
    public static void activate(PersistenceMappers mapper, ObjectMapper objectMapper,
                                String turnId, TurnOrigin origin, Instant occurredAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!origin.internal()) throw new IllegalArgumentException("Task continuation requires an internal origin");
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(turnId);
        if (task == null) return;
        String summary = switch (origin) {
            case GOAL_CONTINUATION -> "目标续跑已排队";
            case PLAN_EXECUTION -> "计划执行已排队";
            case USER, CHILD_TASK -> throw new IllegalArgumentException("Task continuation requires an internal origin");
        };
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(
                "activity_task_resumed_" + TaskTerminalPersistence.stableSuffix(turnId),
                task.rootThreadId(), task.taskThreadId(), task.taskThreadId(), turnId,
                TaskModels.ActivityKind.RESUMED.name(),
                new TaskJsonCodec(objectMapper).write(JsonObjects.builder().putText("text", summary).build()),
                occurredAt.toString()));
        if (sequence == null) throw concurrent("task continuation activity insert lost");
        int changed = mapper.tasks().compareAndSetInternalProjection(new TaskRecords.ProjectionCas(
                task.taskThreadId(), task.taskRevision(), TaskModels.State.QUEUED.name(), sequence,
                summary, null, null, occurredAt.toString(), true));
        if (changed != 1) throw concurrent("task continuation projection changed concurrently");
        mapper.tasks().recomputeAncestorCounts(task.taskThreadId(), occurredAt.toString());
    }

    /** 事务内 CAS 失败必须整体回滚，而不是以重复 Activity 掩盖并发丢失。 */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /** 纯事务扩展禁止实例化。 */
    private TaskContinuationPersistence() { }
}
