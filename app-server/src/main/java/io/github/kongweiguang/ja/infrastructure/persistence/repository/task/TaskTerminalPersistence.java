// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;

import java.util.Objects;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Conversation terminal transaction 的 Task 扩展；调用方必须传入当前同一 SqlSession 的
 * PersistenceMappers，并在 commit 后才发布 task/activity 与 mailbox-changed。
 */
public final class TaskTerminalPersistence {
    private static final int MAX_FINAL_ANSWER_CODE_POINTS = 120_000;
    private static final int MAX_SAFE_SUMMARY_CODE_POINTS = 512;

    /**
     * Conversation 唯一终态门获胜后，在同一 SqlSession 追加 Child Task 的 Activity、投影和父 Mailbox。
     * final assistant 没有公开文本时不构造空 UserContent，也不发送伪结果。
     */
    public static Optional<TaskRecords.TaskSummaryRow> settle(
            PersistenceMappers mapper, ObjectMapper objectMapper,
            ConversationRepository.TerminalCommit terminal) {
        Objects.requireNonNull(terminal, "terminal");
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(terminal.turnId());
        if (task == null) return Optional.empty();
        String visible = visibleText(terminal.finalMessage(), MAX_FINAL_ANSWER_CODE_POINTS);
        String safeSummary = visible.isBlank()
                ? bounded(terminal.summary(), MAX_SAFE_SUMMARY_CODE_POINTS)
                : bounded(visible, MAX_SAFE_SUMMARY_CODE_POINTS);
        if (safeSummary.isBlank()) safeSummary = terminal.state().name();
        String stableSuffix = stableSuffix(terminal.turnId());
        TaskModels.MailboxEnvelope answer = visible.isBlank() ? null : new TaskModels.MailboxEnvelope(
                "msg_task_final_" + stableSuffix, task.taskThreadId(), task.parentThreadId(),
                terminal.turnId(), TaskModels.MailboxKind.FINAL_ANSWER,
                new UserContent(List.of(new TextContent(visible))), terminalIdempotencyKey(terminal.turnId()),
                terminal.occurredAt());
        TaskModels.TerminalSettlement settlement = new TaskModels.TerminalSettlement(
                task.taskThreadId(), terminal.turnId(), terminalState(terminal.state()),
                "activity_task_terminal_" + stableSuffix,
                JsonObjects.builder().putText("text", safeSummary).build(), safeSummary,
                answer, terminal.occurredAt());
        return settle(mapper, objectMapper, settlement);
    }

    /** 非 Child Turn 安静返回 empty；Child terminal 只允许首个 projection CAS 胜者产生 FINAL_ANSWER。 */
    static Optional<TaskRecords.TaskSummaryRow> settle(PersistenceMappers mapper,
                                                       ObjectMapper objectMapper,
                                                       TaskModels.TerminalSettlement settlement) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(settlement, "settlement");
        TaskMailboxPersistence.releaseBoundForTurn(mapper, settlement.turnId(), settlement.occurredAt());
        TaskRecords.TerminalTaskRow task = mapper.tasks().selectTaskByTurn(settlement.turnId());
        if (task == null) return Optional.empty();
        if (!task.taskThreadId().equals(settlement.taskThreadId())) {
            throw relation("terminal Turn does not belong to Task");
        }
        if (terminal(task.taskState())) {
            return Optional.ofNullable(mapper.tasks().selectTaskSummary(task.taskThreadId()));
        }
        TaskJsonCodec json = new TaskJsonCodec(objectMapper);
        TaskModels.MailboxEnvelope answer = settlement.finalAnswer();
        if (answer != null) insertFinalAnswer(mapper, json, task, answer);
        Long sequence = mapper.tasks().insertActivity(new TaskRecords.ActivityInsert(
                settlement.activityId(), task.rootThreadId(), task.taskThreadId(), task.taskThreadId(),
                settlement.turnId(), activityKind(settlement.state()).name(),
                json.write(settlement.activitySummary()), settlement.occurredAt().toString()));
        if (sequence == null) throw concurrent("terminal task activity insert lost");
        String pendingState = mapper.tasks().selectNextNonTerminalTaskTurnState(
                task.taskThreadId(), settlement.turnId());
        TaskModels.State projectedState = pendingState == null
                ? settlement.state() : nonTerminalState(pendingState);
        String completedAt = pendingState == null ? settlement.occurredAt().toString() : null;
        int changed = mapper.tasks().compareAndSetProjection(new TaskRecords.ProjectionCas(
                task.taskThreadId(), task.taskRevision(), projectedState.name(), sequence,
                settlement.latestSafeSummary(), null, completedAt,
                settlement.occurredAt().toString(), true));
        if (changed != 1) throw concurrent("terminal task projection changed concurrently");
        mapper.tasks().recomputeAncestorCounts(task.taskThreadId(), settlement.occurredAt().toString());
        TaskRecords.TaskSummaryRow result = mapper.tasks().selectTaskSummary(task.taskThreadId());
        if (result == null) throw invalidState("terminal task projection is unavailable");
        return Optional.of(result);
    }

    /** FINAL_ANSWER 不受普通 Mailbox 容量丢弃；唯一键重试必须与首个事实逐字段一致。 */
    private static void insertFinalAnswer(PersistenceMappers mapper, TaskJsonCodec json,
                                          TaskRecords.TerminalTaskRow task,
                                          TaskModels.MailboxEnvelope answer) {
        if (!answer.targetThreadId().equals(task.parentThreadId())) {
            throw relation("FINAL_ANSWER target is not the parent Thread");
        }
        TaskRecords.TaskRouteRow target = mapper.tasks().selectTaskRoute(answer.targetThreadId());
        if (target == null) throw relation("FINAL_ANSWER parent Thread is unavailable");
        String targetRoot = target.rootThreadId() == null ? target.threadId() : target.rootThreadId();
        if (!task.rootThreadId().equals(targetRoot)) throw relation("FINAL_ANSWER crosses task roots");
        String contentJson = json.writeContent(answer.content());
        TaskRecords.MailboxRow existing = mapper.tasks().selectMailboxByIdempotency(
                answer.senderThreadId(), answer.idempotencyKey());
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
                answer.kind().name(), contentJson, answer.idempotencyKey(), "PENDING", null,
                answer.createdAt().toString(), answer.createdAt().toString()));
        if (inserted == null) throw concurrent("FINAL_ANSWER mailbox changed concurrently");
    }

    /** 终态状态与 Activity kind 保持一一对应，避免 FAILED 被展示为普通 COMPLETED。 */
    private static TaskModels.ActivityKind activityKind(TaskModels.State state) {
        return switch (state) {
            case COMPLETED -> TaskModels.ActivityKind.COMPLETED;
            case FAILED -> TaskModels.ActivityKind.FAILED;
            case CANCELLED -> TaskModels.ActivityKind.CANCELLED;
            default -> throw new IllegalArgumentException("terminal state is required");
        };
    }

    /** Conversation 与 Task 使用同名终态，但仍显式映射以拒绝未来新增的非终态。 */
    private static TaskModels.State terminalState(TurnState state) {
        return switch (state) {
            case COMPLETED -> TaskModels.State.COMPLETED;
            case FAILED -> TaskModels.State.FAILED;
            case CANCELLED -> TaskModels.State.CANCELLED;
            default -> throw new IllegalArgumentException("terminal state is required");
        };
    }

    /** 多 Turn Task 的投影跟随最早未终态 Turn，禁止前一 Turn 完成时把已排队 Follow-up 隐藏为终态。 */
    private static TaskModels.State nonTerminalState(String state) {
        return switch (TurnState.valueOf(state)) {
            case QUEUED -> TaskModels.State.QUEUED;
            case RUNNING -> TaskModels.State.RUNNING;
            case WAITING_APPROVAL -> TaskModels.State.WAITING_APPROVAL;
            case SUSPENDED -> TaskModels.State.SUSPENDED;
            case COMPLETED, FAILED, CANCELLED -> throw invalidState("next task Turn is already terminal");
        };
    }

    /** 只抽取公开 TextContent，并按 Unicode code point 截断，避免拆断代理对。 */
    private static String visibleText(ModelMessage message, int maximumCodePoints) {
        if (message == null) return "";
        String text = message.content().stream().filter(TextContent.class::isInstance)
                .map(TextContent.class::cast).map(TextContent::text).reduce("", String::concat);
        return bounded(text, maximumCodePoints);
    }

    /** code point 上限同时约束父 Mailbox 与列表安全摘要，不依赖 UTF-16 char 数猜测。 */
    private static String bounded(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty()) return "";
        int count = value.codePointCount(0, value.length());
        return count <= maximumCodePoints ? value
                : value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    /** Turn 身份哈希生成固定长度 Activity/Mailbox/幂等后缀，避免长合法 ID 越过下游上限。 */
    static String stableSuffix(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    /** 测试和终态写入共享确定性键，保证进程重试仍竞争同一 FINAL_ANSWER 事实。 */
    static String terminalIdempotencyKey(String turnId) {
        return "terminal:" + stableSuffix(turnId);
    }

    /** 数据库状态仅在三个终态中视为已经结算。 */
    private static boolean terminal(String state) {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state);
    }

    /** 关系错误映射到 Task 领域稳定分类。 */
    private static TaskRepositoryException relation(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.RELATION_INVALID, message);
    }

    /** CAS 失败要求上层重读，不以重试盲目复制 FINAL_ANSWER。 */
    private static TaskRepositoryException concurrent(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.CAS_CONFLICT, message);
    }

    /** 同事务回读缺失表示持久化状态损坏。 */
    private static TaskRepositoryException invalidState(String message) {
        return new TaskRepositoryException(TaskRepositoryException.Code.INVALID_STATE, message);
    }

    /** 纯事务扩展禁止实例化。 */
    private TaskTerminalPersistence() { }
}
