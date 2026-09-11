// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.interaction.InteractionResumeState;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.InteractionRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.TaskRecoveryPersistence;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** SQLite Interaction adapter；状态列做 CAS，题目/答案 JSON 仅作为不可变快照载荷。 */
public final class MybatisInteractionRepository implements InteractionRepository {
    private static final TypeReference<List<io.github.kongweiguang.ja.conversation.domain.interaction.InteractionQuestion>> QUESTIONS = new TypeReference<>() { };
    private static final TypeReference<List<InteractionAnswer>> ANSWERS = new TypeReference<>() { };
    private final MybatisUnitOfWork transactions;
    private final ObjectMapper mapper;

    /** 生产事务复用 MyBatis-Solon owner，不在 Repository 内自行提交连接。 */
    public MybatisInteractionRepository(SqlSessionFactory sessions, ObjectMapper mapper) {
        this.transactions = new MybatisUnitOfWork(sessions);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 测试使用真实 SQLite 与显式 transaction owner，仍执行同一 SQL/CAS 路径。 */
    public MybatisInteractionRepository(SqlSessionFactory sessions, ObjectMapper mapper,
                                        MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** 在一个 SQLite 快照内读取请求、草稿和关联 Turn 恢复状态，避免跨事务拼接。 */
    @Override
    public Optional<InteractionSnapshot> read(String threadId, String requestId) {
        Objects.requireNonNull(threadId, "threadId");
        return transactions.required(mappers -> {
            InteractionMapper interactions = mappers.interactions();
            PersistenceRecords.InteractionRow row = requestId == null
                    ? interactions.selectActiveInteraction(threadId)
                    : interactions.selectInteraction(new PersistenceRecords.InteractionKey(threadId, requestId));
            if (requestId != null && row == null) return Optional.empty();
            PersistenceRecords.InteractionDraftRow draft = row == null ? null
                    : interactions.selectDraft(new PersistenceRecords.InteractionKey(threadId, row.requestId()));
            Long currentSequence = interactions.selectCurrentEventSequence(threadId);
            long sequence = currentSequence == null ? 0L : currentSequence;
            Optional<InteractionRequest> request = Optional.ofNullable(row).map(this::decode);
            String turnState = row == null ? null : interactions.selectTurnState(row.turnId());
            return Optional.of(new InteractionSnapshot(threadId, sequence, request,
                    Optional.ofNullable(draft).map(this::decodeDraft), InteractionResumeState.from(request, turnState)));
        });
    }

    /** 只返回仍待回答的活动请求，已回答但等待恢复的请求由 read 保留给 UI 对账。 */
    @Override
    public Optional<InteractionRequest> findActive(String threadId) {
        return read(threadId, null).flatMap(InteractionSnapshot::request)
                .filter(value -> value.status() == InteractionStatus.PENDING);
    }

    /** 以状态 CAS 关闭请求，并在同一事务中取消挂起 Turn 的恢复资格。 */
    @Override
    public InteractionRequest close(String threadId, String requestId, long expectedRevision,
                                    InteractionStatus status, String idempotencyKey, Instant occurredAt) {
        return transactions.required(mappers -> {
            InteractionMapper interactions = mappers.interactions();
            PersistenceRecords.InteractionRow current = interactions.selectInteraction(
                    new PersistenceRecords.InteractionKey(threadId, requestId));
            if (current == null) throw notFound();
            if (current.idempotencyKey().equals(idempotencyKey) && !"PENDING".equals(current.status())) return decode(current);
            /* 问题取消/替代必须与挂起 Turn 的终态收口共用事务，迟到答案不能留下可恢复游标。 */
            if (status == InteractionStatus.CANCELLED || status == InteractionStatus.SUPERSEDED) {
                cancelSuspendedTurn(mappers, current, occurredAt);
            }
            if (interactions.closeInteraction(new PersistenceRecords.InteractionCloseCas(threadId, requestId,
                    expectedRevision, status.name(), idempotencyKey, occurredAt.toString())) != 1) throw conflict();
            if (interactions.insertEvent(new PersistenceRecords.InteractionEventInsert(threadId, requestId,
                    current.revision() + 1, status == InteractionStatus.CANCELLED
                            ? InteractionEvent.Kind.CANCELLED.name() : InteractionEvent.Kind.SUPERSEDED.name(),
                    occurredAt.toString())) != 1) throw conflict();
            return decode(requireRow(interactions.selectInteraction(
                    new PersistenceRecords.InteractionKey(threadId, requestId))));
        });
    }

    /**
     * 在 Interaction close 同一 SQLite 事务内关闭 SUSPENDED Turn 及其附属资源；不删除已产生的工作区文件。
     */
    private void cancelSuspendedTurn(PersistenceMappers mappers, PersistenceRecords.InteractionRow interaction,
                                     Instant occurredAt) {
        PersistenceRecords.ResumeTurnRow turn = mappers.agent().selectResumeTurn(interaction.turnId());
        if (turn == null || !interaction.threadId().equals(turn.threadId())) throw conflict();
        settleCancelledToolMessages(mappers, interaction, occurredAt);
        if (mappers.agent().cancelSuspendedTurn(new PersistenceRecords.ResumeTurnCas(
                turn.turnId(), turn.threadId(), turn.threadRevision(), turn.turnMutationVersion(),
                occurredAt.toString())) != 1) throw conflict();
        if (mappers.history().compareAndSetThread(new PersistenceRecords.ThreadRevisionCas(
                turn.threadId(), turn.threadRevision(), occurredAt.toString())) != 1) throw conflict();
        mappers.attachments().discardTurnPendingInputAttachments(turn.turnId(), occurredAt.toString());
        mappers.attachments().deleteTurnPendingInputAttachments(turn.turnId());
        mappers.agent().cancelPendingInputs(new PersistenceRecords.PendingInputCancel(turn.turnId(), occurredAt.toString()));
        mappers.agent().closePendingApprovals(turn.turnId(), occurredAt.toString());
        if (mappers.agent().deleteTurnExecution(turn.turnId()) != 1) throw conflict();
        TaskRecoveryPersistence.reconcileTerminal(mappers, mapper, turn.turnId(), TurnState.CANCELLED, occurredAt);
    }

    /** 取消问题也要结算原批次所有未决调用；只改 Turn 状态会让下一轮读到无结果的模型工具调用。 */
    private void settleCancelledToolMessages(PersistenceMappers mappers,
                                             PersistenceRecords.InteractionRow interaction, Instant occurredAt) {
        List<String> calls = mappers.agent().selectUnfinishedToolCallIds(interaction.turnId());
        var codec = new io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec(mapper);
        String content = "The user cancelled this interaction. No answer was supplied and no further tool was executed.";
        for (String callId : calls) {
            var message = new io.github.kongweiguang.ja.conversation.domain.model.ModelMessage(
                    io.github.kongweiguang.ja.conversation.domain.model.ModelRole.TOOL,
                    List.of(new io.github.kongweiguang.ja.conversation.domain.model.ToolResultContent(callId, content, true)));
            if (mappers.agent().insertMessage(new PersistenceRecords.MessageInsert(
                    "item_" + java.util.UUID.randomUUID(), interaction.threadId(), interaction.turnId(),
                    mappers.agent().selectNextMessageOrdinal(interaction.threadId()), "TOOL",
                    codec.writeMessage(message), occurredAt.toString())) != 1) throw conflict();
        }
        if (mappers.agent().settleUnfinishedTools(new PersistenceRecords.ToolSettlement(
                interaction.turnId(), "CANCELLED", "cancelled", content, occurredAt.toString())) != calls.size()) {
            throw conflict();
        }
    }

    /** 以草稿 revision CAS 保存用户编辑，响应失败时不覆盖服务端新版本。 */
    @Override
    public InteractionDraft saveDraft(InteractionDraft draft, long expectedRevision, String idempotencyKey) {
        return transactions.required(mappers -> {
            if (mappers.interactions().saveDraft(new PersistenceRecords.InteractionDraftCas(
                    draft.threadId(), draft.requestId(), expectedRevision, encode(draft.answers()), draft.page(),
                    draft.collapsed(), idempotencyKey, draft.updatedAt().toString())) != 1) throw conflict();
            PersistenceRecords.InteractionDraftRow saved = mappers.interactions().selectDraft(
                    new PersistenceRecords.InteractionKey(draft.threadId(), draft.requestId()));
            InteractionDraft decoded = decodeDraft(saved);
            if (mappers.interactions().insertEvent(new PersistenceRecords.InteractionEventInsert(draft.threadId(),
                    draft.requestId(), decoded.revision(), InteractionEvent.Kind.DRAFT_CHANGED.name(),
                    draft.updatedAt().toString())) != 1) throw conflict();
            return decoded;
        });
    }

    /** 读取序列增量并解码为领域事件，客户端负责检测缺口后重新 read。 */
    @Override
    public List<InteractionEvent> events(String threadId, long afterSequence) {
        return transactions.required(mappers -> mappers.interactions().selectEvents(threadId, afterSequence).stream()
                .map(this::decodeEvent).toList());
    }

    /** 将持久 JSON 严格解码为领域请求，任何损坏都 fail closed。 */
    private InteractionRequest decode(PersistenceRecords.InteractionRow row) {
        try {
            return new InteractionRequest(row.requestId(), row.threadId(), row.turnId(), row.toolCallId(),
                    row.planRevisionId(), row.runId(), row.goalId(), row.idempotencyKey(),
                    mapper.readValue(row.questionsJson(), QUESTIONS), enumValue(row.status()),
                    mapper.readValue(row.answersJson(), ANSWERS), row.revision(), Instant.parse(row.createdAt()),
                    Instant.parse(row.updatedAt()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid interaction persistence", failure);
        }
    }

    /** 将草稿 JSON 解码为有界领域答案，拒绝隐式默认值。 */
    private InteractionDraft decodeDraft(PersistenceRecords.InteractionDraftRow row) {
        try {
            return new InteractionDraft(row.threadId(), row.requestId(), mapper.readValue(row.answersJson(), ANSWERS),
                    row.page(), row.collapsed(), row.idempotencyKey(), row.revision(), Instant.parse(row.updatedAt()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid interaction draft persistence", failure);
        }
    }

    /** 解码数据库分配的事件序列，保持观察顺序和状态身份不变。 */
    private InteractionEvent decodeEvent(PersistenceRecords.InteractionEventRow row) {
        return new InteractionEvent(row.threadId(), row.requestId(), row.requestRevision(), row.eventSequence(),
                InteractionEvent.Kind.valueOf(row.kind()), Instant.parse(row.occurredAt()));
    }

    /** 统一编码领域载荷，编码失败转换为稳定存储错误。 */
    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new StorageException(StorageException.Code.IO, "cannot encode interaction", failure); }
    }

    /** 将持久状态转换为闭集枚举，未知值不得降级为任意默认状态。 */
    private static InteractionStatus enumValue(String value) { return InteractionStatus.valueOf(value); }
    /** CAS 后再次读取必须存在，否则说明事务事实已损坏。 */
    private static PersistenceRecords.InteractionRow requireRow(PersistenceRecords.InteractionRow row) {
        if (row == null) throw new StorageException(StorageException.Code.INVALID_STATE, "interaction disappeared after CAS");
        return row;
    }
    /** 将资源缺失映射为稳定存储分类，Transport 再负责外部错误码映射。 */
    private static StorageException notFound() { return new StorageException(StorageException.Code.NOT_FOUND, "interaction not found"); }
    /** 将 CAS 竞争映射为可重读冲突，保留调用方本地草稿。 */
    private static StorageException conflict() { return new StorageException(StorageException.Code.CAS_CONFLICT, "interaction revision conflict"); }
}
