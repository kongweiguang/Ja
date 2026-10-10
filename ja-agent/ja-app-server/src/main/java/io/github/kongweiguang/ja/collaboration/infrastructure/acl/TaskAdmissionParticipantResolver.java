// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.infrastructure.acl;

import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;

import java.util.Objects;
import java.util.Optional;

/**
 * 把已有 Task 的 USER 与 Plan 执行 admission 接入 Ja Store staged transaction。
 */
public final class TaskAdmissionParticipantResolver implements AdmissionParticipantResolver {
    private final TaskRepository tasks;
    private final ExecutionRecordCodec records;
    private final ExecutionCursorCodec cursors;

    /**
     * resolver 只查询 Task 快照并冻结 CAS revision，实际写入留在 admission 同连接事务内。
     */
    public TaskAdmissionParticipantResolver(TaskRepository tasks, ExecutionRecordCodec records,
                                            ExecutionCursorCodec cursors) {
        this.tasks = Objects.requireNonNull(tasks, "tasks");
        this.records = Objects.requireNonNull(records, "records");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
    }

    /**
     * Activity 使用 Turn 稳定后缀，事务重试仍落到同一审计身份。
     */
    private static String continuationActivityId(String turnId) {
        if (turnId == null || !turnId.startsWith("turn_") || turnId.length() <= "turn_".length()) {
            throw new IllegalArgumentException("invalid continuation Turn identity");
        }
        return "activity_task_resumed_" + turnId.substring("turn_".length());
    }

    /**
     * 冻结既有 Task revision 与首轮继承快照；普通 USER 与隐藏续跑不混淆消息来源。
     */
    @Override
    public Optional<JaAdmissionTransactionParticipant> resolve(AdmissionFacts facts) {
        AdmissionFacts admission = Objects.requireNonNull(facts, "facts");
        if (admission.newThread().isPresent()) {
            return Optional.empty();
        }
        if (admission.origin() != TurnOrigin.USER
            && admission.origin() != TurnOrigin.PLAN_EXECUTION) {
                return Optional.empty();
            }
        Optional<TaskModels.Summary> task = tasks.findTask(admission.identity().threadId());
        if (task.isEmpty()) {
            return Optional.empty();
        }
        long expectedRevision = task.orElseThrow().projection().revision();
        if (admission.origin() == TurnOrigin.USER) {
            TaskAdmissionTransactionParticipant.UserTurnDraft draft =
                    new TaskAdmissionTransactionParticipant.UserTurnDraft(expectedRevision,
                            tasks.findInitialContextSeedForFirstTurn(admission.identity().threadId()));
            return Optional.of(TaskAdmissionTransactionParticipant.forUserTurn(
                    tasks, admission, draft, records, cursors));
        }
        String summary = "计划执行已排队";
        TaskAdmissionTransactionParticipant.ContinuationDraft draft =
                new TaskAdmissionTransactionParticipant.ContinuationDraft(expectedRevision,
                        continuationActivityId(admission.identity().turnId()),
                        JsonObjects.builder().putText("text", summary).build(),
                        tasks.findInitialContextSeedForFirstTurn(admission.identity().threadId()));
        return Optional.of(TaskAdmissionTransactionParticipant.forContinuation(
                tasks, admission, draft, records, cursors));
    }
}


