// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;

import java.util.Objects;

/**
 * Conversation hidden Turn admission 的 Task 投影扩展，必须和 Turn 事实共用一个事务。
 */
public final class TaskContinuationPersistence {
    /**
     * 纯事务扩展禁止实例化。
     */
    private TaskContinuationPersistence() {
    }

    /**
     * Plan 执行 Turn 的 Core 事实完成通用投影后，按冻结 Task revision 原子重开状态并记录 Activity。
     */
    public static void activate(PersistenceMappers mapper, ObjectMapper objectMapper,
                                TaskModels.ContinuationAdmission admission) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        TaskModels.ContinuationAdmission value = Objects.requireNonNull(admission, "admission");
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(value.turnId());
        if (task == null || !task.taskThreadId().equals(value.taskThreadId())
            || task.taskRevision() != value.expectedTaskRevision()) {
            throw concurrent("Task continuation identity or revision changed");
        }
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(
                value.activityId(), task.rootThreadId(), task.taskThreadId(), task.taskThreadId(), value.turnId(),
                TaskModels.ActivityKind.RESUMED.name(),
                new TaskJsonCodec(objectMapper).write(value.activitySummary()), value.occurredAt().toString()));
        if (sequence == null) {
            throw concurrent("task continuation activity insert lost");
        }
        int changed = mapper.tasks().compareAndSetInternalProjection(new TaskRecords.ProjectionCas(
                task.taskThreadId(), value.expectedTaskRevision(), TaskModels.State.QUEUED.name(), sequence,
                value.activitySummary().get("text") instanceof io.github.kongweiguang.ja.agent.domain.json.JsonText(
                        String value1
                )
                        ? value1 : null,
                null, null, value.occurredAt().toString(), true));
        if (changed != 1) {
            throw concurrent("task continuation projection changed concurrently");
        }
        mapper.tasks().recomputeAncestorCounts(task.taskThreadId(), value.occurredAt().toString());
    }

    /**
     * 事务内 CAS 失败必须整体回滚，而不是以重复 Activity 掩盖并发丢失。
     */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }
}
