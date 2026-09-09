// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** 启动恢复的 Task 投影扩展；调用方已在同一事务内完成对应 Turn 状态调和。 */
public final class TaskRecoveryPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRecoveryPersistence.class);
    /**
     * 非 Child Turn 安静返回；Child 只从非终态投影收敛为 SUSPENDED，并使用确定性 Activity 身份，
     * 使恢复事务回滚重试仍竞争同一追加事实。
     */
    public static Optional<TaskRecords.TaskSummaryRow> reconcileSuspended(
            PersistenceMappers mapper, ObjectMapper objectMapper, String turnId, Instant occurredAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(turnId);
        if (task == null) return Optional.empty();
        if (terminal(task.taskState())) {
            return Optional.ofNullable(mapper.tasks().selectTaskSummary(task.taskThreadId()));
        }
        String summary = "任务因进程中断已暂停，等待显式恢复";
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(
                "activity_task_recovery_" + TaskTerminalPersistence.stableSuffix(turnId),
                task.rootThreadId(), task.taskThreadId(), task.taskThreadId(), turnId,
                TaskModels.ActivityKind.SUSPENDED.name(),
                new TaskJsonCodec(objectMapper).write(JsonObjects.builder().putText("text", summary).build()),
                occurredAt.toString()));
        if (sequence == null) throw concurrent("task recovery activity insert lost");
        int changed = mapper.tasks().compareAndSetProjection(new TaskRecords.ProjectionCas(
                task.taskThreadId(), task.taskRevision(), TaskModels.State.SUSPENDED.name(), sequence,
                summary, null, null, occurredAt.toString(), true));
        if (changed != 1) throw concurrent("task recovery projection changed concurrently");
        mapper.tasks().recomputeAncestorCounts(task.taskThreadId(), occurredAt.toString());
        TaskRecords.TaskSummaryRow result = mapper.tasks().selectTaskSummary(task.taskThreadId());
        if (result == null) throw invalidState("task recovery projection is unavailable");
        LOGGER.info("event=task_recovery_suspended suspended_count=1");
        return Optional.of(result);
    }

    /**
     * 启动恢复已将 Turn CAS 到 FAILED/CANCELLED 后，以同一事务把 Child projection 和 Activity
     * 收敛到相同终态；不生成 FINAL_ANSWER，因为恢复路径没有新的模型公开结果。
     */
    public static Optional<TaskRecords.TaskSummaryRow> reconcileTerminal(
            PersistenceMappers mapper, ObjectMapper objectMapper, String turnId,
            TurnState state, Instant occurredAt) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(occurredAt, "occurredAt");
        TaskMailboxPersistence.releaseBoundForTurn(mapper, turnId, occurredAt);
        TaskModels.State taskState = switch (state) {
            case FAILED -> TaskModels.State.FAILED;
            case CANCELLED -> TaskModels.State.CANCELLED;
            default -> throw new IllegalArgumentException("recovery terminal state must be FAILED or CANCELLED");
        };
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(turnId);
        if (task == null) return Optional.empty();
        String summary = taskState == TaskModels.State.CANCELLED
                ? "任务在启动恢复期间按已提交取消意图终止"
                : "任务因恢复状态损坏已失败关闭";
        TaskModels.TerminalSettlement settlement = new TaskModels.TerminalSettlement(
                task.taskThreadId(), turnId, taskState,
                "activity_task_recovery_terminal_" + TaskTerminalPersistence.stableSuffix(turnId),
                JsonObjects.builder().putText("text", summary).build(), summary, null, occurredAt);
        return TaskTerminalPersistence.settle(mapper, objectMapper, settlement);
    }

    /** 三个 Task 终态不允许被启动恢复回写为 SUSPENDED。 */
    private static boolean terminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    /** 恢复 CAS 竞争要求整批事务回滚并重读，不在 mapper 层盲重试。 */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /** 同事务回读缺失表示 projection 已损坏。 */
    private static TaskRepositoryException invalidState(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }

    /** 纯事务扩展禁止实例化。 */
    private TaskRecoveryPersistence() { }
}
