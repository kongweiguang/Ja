// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts.AssistantMessage;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Conversation terminal transaction 的 Task 扩展，只参加 JaAgentStore 当前连接上的终态提交。
 */
public final class TaskTerminalPersistence {
    private static final int TERMINAL_PREVIEW_CODE_POINTS = 4_096;

    /**
     * 纯事务扩展禁止实例化。
     */
    private TaskTerminalPersistence() {
    }

    /**
     * 把 Core 终态事实和精确引用的助手消息映射为 Task 扩展；只从同一提交的事实构造 FINAL_ANSWER，
     * 避免按“最近消息”猜结果或在 Tool/恢复提交中伪造用户可见答复。
     */
    static Optional<TaskRecords.TaskSummaryRow> settleFromExecution(
            PersistenceMappers mapper, ObjectMapper objectMapper, AgentStoreTransactionContext transaction,
            ExecutionEvent.Terminal terminal, Map<String, ExecutionFact> factsByRecordId) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(transaction, "transaction");
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(factsByRecordId, "factsByRecordId");
        String turnId = transaction.execution().turnId();
        if ((transaction.kind() != AgentStore.TransactionKind.COMMIT
             && transaction.kind() != AgentStore.TransactionKind.WAIT_SETTLEMENT)
            || !terminal.state().terminal() || !terminal.state().name().equals(transaction.state())) {
            throw invalidState("terminal event does not match the committed execution state");
        }
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(turnId);
        if (task == null) {
            return Optional.empty();
        }
        if (!task.taskThreadId().equals(transaction.execution().conversation().threadId())) {
            throw relation("terminal Turn belongs to a different Task Thread");
        }
        TaskModels.State state = TaskModels.State.valueOf(terminal.state().name());
        String summary = terminalSummary(terminal, factsByRecordId);
        JsonObject activitySummary = JsonObjects.builder().putText("text", summary).build();
        TaskModels.MailboxEnvelope answer = finalAnswer(task, turnId, terminal, factsByRecordId,
                transaction.occurredAt());
        TaskModels.TerminalSettlement settlement = new TaskModels.TerminalSettlement(
                task.taskThreadId(), turnId, state,
                "activity_task_terminal_" + stableSuffix(turnId), activitySummary, summary,
                answer, transaction.occurredAt());
        return settle(mapper, objectMapper, settlement);
    }

    /**
     * 非 Task Turn 安静返回 empty；只有附属 Subagent 的首个 projection CAS 胜者产生 FINAL_ANSWER。
     */
    static Optional<TaskRecords.TaskSummaryRow> settle(PersistenceMappers mapper,
                                                       ObjectMapper objectMapper,
                                                       TaskModels.TerminalSettlement settlement) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(settlement, "settlement");
        TaskMailboxPersistence.releaseBoundForTurn(mapper, settlement.turnId(), settlement.occurredAt());
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(settlement.turnId());
        if (task == null) {
            return Optional.empty();
        }
        if (!task.taskThreadId().equals(settlement.taskThreadId())) {
            throw relation("terminal Turn does not belong to Task");
        }
        if (terminal(task.taskState())) {
            return Optional.ofNullable(mapper.tasks().selectTaskSummary(task.taskThreadId()));
        }
        TaskJsonCodec json = new TaskJsonCodec(objectMapper);
        TaskModels.MailboxEnvelope answer = settlement.finalAnswer();
        if (answer != null) {
            if (!publishesFinalAnswer(task.taskKind())) {
                throw relation("SIDE_TASK cannot publish FINAL_ANSWER to parent Thread");
            }
            insertFinalAnswer(mapper, json, task, answer);
        }
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(
                settlement.activityId(), task.rootThreadId(), task.taskThreadId(), task.taskThreadId(),
                settlement.turnId(), activityKind(settlement.state()).name(),
                json.write(settlement.activitySummary()), settlement.occurredAt().toString()));
        if (sequence == null) {
            throw concurrent("terminal task activity insert lost");
        }
        String pendingState = mapper.tasks().selectNextNonTerminalTaskTurnState(
                task.taskThreadId(), settlement.turnId());
        TaskModels.State projectedState = pendingState == null
                ? settlement.state() : nonTerminalState(pendingState);
        String completedAt = pendingState == null ? settlement.occurredAt().toString() : null;
        int changed = mapper.tasks().compareAndSetProjection(new TaskRecords.ProjectionCas(
                task.taskThreadId(), task.taskRevision(), projectedState.name(), sequence,
                settlement.latestSafeSummary(), null, completedAt,
                settlement.occurredAt().toString(), true));
        if (changed != 1) {
            throw concurrent("terminal task projection changed concurrently");
        }
        mapper.tasks().recomputeAncestorCounts(task.taskThreadId(), settlement.occurredAt().toString());
        TaskRecords.TaskSummaryRow result = mapper.tasks().selectTaskSummary(task.taskThreadId());
        if (result == null) {
            throw invalidState("terminal task projection is unavailable");
        }
        return Optional.of(result);
    }

    /**
     * 只有 SUBAGENT 的终态需要回传父 Thread；SIDE_TASK 保持独立生命周期，不伪造父回复。
     */
    private static boolean publishesFinalAnswer(String taskKind) {
        return switch (TaskModels.Kind.valueOf(taskKind)) {
            case SIDE_TASK -> false;
            case SUBAGENT -> true;
        };
    }

    /**
     * 只有成功终态引用的精确助手消息才能形成 FINAL_ANSWER；SIDE_TASK 和无公开正文继续独立终止。
     */
    private static TaskModels.MailboxEnvelope finalAnswer(TaskRecords.TerminalTaskRow task, String turnId,
                                                          ExecutionEvent.Terminal terminal,
                                                          Map<String, ExecutionFact> factsByRecordId,
                                                          java.time.Instant occurredAt) {
        if (terminal.state() != TurnState.COMPLETED || !publishesFinalAnswer(task.taskKind())
            || terminal.messageRecordId() == null) {
                return null;
            }
        ExecutionFact fact = factsByRecordId.get(terminal.messageRecordId());
        if (!(fact instanceof AssistantMessage assistant)) {
            return null;
        }
        String body = terminal.summary().length() > assistant.publicText().length()
                ? terminal.summary() : assistant.publicText();
        if (body.isBlank()) {
            return null;
        }
        return new TaskModels.MailboxEnvelope("msg_task_final_" + stableSuffix(turnId),
                task.taskThreadId(), task.parentThreadId(), turnId, TaskModels.MailboxKind.FINAL_ANSWER,
                new UserContent(List.of(new TextContent(body))), terminalOperationKey(turnId), occurredAt);
    }

    /**
     * Task 活动只承载有界预览；完整回复仍由 Core 消息和 FINAL_ANSWER 保存，避免长正文使 Host 投影失效。
     */
    private static String terminalSummary(ExecutionEvent.Terminal terminal,
                                          Map<String, ExecutionFact> factsByRecordId) {
        if (!terminal.summary().isBlank()) {
            return terminalPreview(terminal.summary());
        }
        if (terminal.messageRecordId() != null
            && factsByRecordId.get(terminal.messageRecordId()) instanceof AssistantMessage assistant
            && !assistant.publicText().isBlank()) {
                return terminalPreview(assistant.publicText());
            }
        return switch (terminal.state()) {
            case COMPLETED -> "任务已完成";
            case FAILED -> "任务执行失败";
            case CANCELLED -> "任务已取消";
            default -> throw invalidState("terminal state is required");
        };
    }

    /**
     * 4096 Unicode 码点最多占 16KiB UTF-8；按码点切分保留补充平面字符，并满足跨端预览字节预算。
     */
    private static String terminalPreview(String text) {
        int codePoints = text.codePointCount(0, text.length());
        return codePoints <= TERMINAL_PREVIEW_CODE_POINTS ? text
                : text.substring(0, text.offsetByCodePoints(0, TERMINAL_PREVIEW_CODE_POINTS));
    }

    /**
     * FINAL_ANSWER 不受普通 Mailbox 容量丢弃；唯一键重试必须与首个事实逐字段一致。
     */
    private static void insertFinalAnswer(PersistenceMappers mapper, TaskJsonCodec json,
                                          TaskRecords.TerminalTaskRow task,
                                          TaskModels.MailboxEnvelope answer) {
        if (!answer.targetThreadId().equals(task.parentThreadId())) {
            throw relation("FINAL_ANSWER target is not the parent Thread");
        }
        TaskRecords.TaskRouteRow target = mapper.tasks().selectTaskRoute(answer.targetThreadId());
        if (target == null) {
            throw relation("FINAL_ANSWER parent Thread is unavailable");
        }
        String targetRoot = target.rootThreadId() == null ? target.threadId() : target.rootThreadId();
        if (!task.rootThreadId().equals(targetRoot)) {
            throw relation("FINAL_ANSWER crosses task roots");
        }
        String contentJson = json.writeContent(answer.content());
        TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                answer.senderThreadId(), answer.operationKey());
        if (existing != null) {
            if (!existing.messageId().equals(answer.messageId())
                || !existing.targetThreadId().equals(answer.targetThreadId())
                || !"FINAL_ANSWER".equals(existing.kind())
                || !existing.contentJson().equals(contentJson)) {
                throw concurrent("FINAL_ANSWER idempotency key has different content");
            }
            return;
        }
        Long inserted = mapper.tasks().insertMailbox(new TaskRecords.MailboxInsert(answer.messageId(),
                task.rootThreadId(), answer.senderThreadId(), answer.targetThreadId(), answer.causalTurnId(),
                answer.kind().name(), contentJson, answer.operationKey(), "PENDING", null,
                answer.createdAt().toString(), answer.createdAt().toString()));
        if (inserted == null) {
            throw concurrent("FINAL_ANSWER mailbox changed concurrently");
        }
    }

    /**
     * 终态状态与 Activity kind 保持一一对应，避免 FAILED 被展示为普通 COMPLETED。
     */
    private static TaskModels.ActivityKind activityKind(TaskModels.State state) {
        return switch (state) {
            case COMPLETED -> TaskModels.ActivityKind.COMPLETED;
            case FAILED -> TaskModels.ActivityKind.FAILED;
            case CANCELLED -> TaskModels.ActivityKind.CANCELLED;
            default -> throw new IllegalArgumentException("terminal state is required");
        };
    }

    /**
     * 多 Turn Task 的投影跟随最早未终态 Turn，禁止前一 Turn 完成时把已排队 Follow-up 隐藏为终态。
     */
    private static TaskModels.State nonTerminalState(String state) {
        return switch (TurnState.valueOf(state)) {
            case QUEUED -> TaskModels.State.QUEUED;
            case RUNNING -> TaskModels.State.RUNNING;
            case WAITING_APPROVAL -> TaskModels.State.WAITING_APPROVAL;
            case WAITING_INPUT -> TaskModels.State.WAITING_INPUT;
            case SUSPENDED -> TaskModels.State.SUSPENDED;
            case COMPLETED, FAILED, CANCELLED -> throw invalidState("next task Turn is already terminal");
        };
    }

    /**
     * Turn 身份哈希生成固定长度 Activity/Mailbox/幂等后缀，避免长合法 ID 越过下游上限。
     */
    static String stableSuffix(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /**
     * FINAL_ANSWER 重试复用同一终态身份，避免从成功的 Core 提交再次插入不同业务消息。
     */
    static String terminalOperationKey(String turnId) {
        return "task-terminal:" + stableSuffix(Objects.requireNonNull(turnId, "turnId"));
    }

    /**
     * 数据库状态仅在三个终态中视为已经结算。
     */
    private static boolean terminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    /**
     * 关系错误映射到 Task 领域稳定分类。
     */
    private static TaskRepositoryException relation(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID, message);
    }

    /**
     * CAS 失败要求上层重读，不以重试盲目复制 FINAL_ANSWER。
     */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /**
     * 同事务回读缺失表示持久化状态损坏。
     */
    private static TaskRepositoryException invalidState(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }
}
