// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswer;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionDraft;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionRequest;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionResumeState;
import io.github.kongweiguang.ja.conversation.domain.model.interaction.InteractionSnapshot;
import io.github.kongweiguang.ja.conversation.application.gateway.InteractionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InteractionMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite Interaction adapter；状态列做 CAS，题目/答案 JSON 仅作为不可变快照载荷。
 */
public final class MybatisInteractionRepository implements InteractionRepository {
    private static final TypeReference<List<io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion>> QUESTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<InteractionAnswer>> ANSWERS = new TypeReference<>() {
    };
    private final MybatisUnitOfWork transactions;
    private final ObjectMapper mapper;

    /**
     * 生产事务复用 MyBatis-Solon owner，不在 Repository 内自行提交连接。
     */
    public MybatisInteractionRepository(SqlSessionFactory sessions, ObjectMapper mapper) {
        this.transactions = new MybatisUnitOfWork(sessions);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 测试使用真实 SQLite 与显式 transaction owner，仍执行同一 SQL/CAS 路径。
     */
    public MybatisInteractionRepository(SqlSessionFactory sessions, ObjectMapper mapper,
                                        MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 将持久状态转换为闭集枚举，未知值不得降级为任意默认状态。
     */
    private static InteractionStatus enumValue(String value) {
        return InteractionStatus.valueOf(value);
    }

    /**
     * 将 CAS 竞争映射为可重读冲突，保留调用方本地草稿。
     */
    private static StorageException conflict() {
        return new StorageException(StorageException.Code.CAS_CONFLICT, "interaction revision conflict");
    }

    /**
     * 在一个 SQLite 快照内读取请求、草稿和关联 Turn 恢复状态，避免跨事务拼接。
     */
    @Override
    public Optional<InteractionSnapshot> read(String threadId, String interactionId) {
        Objects.requireNonNull(threadId, "threadId");
        return transactions.required(mappers -> {
            InteractionMapper interactions = mappers.interactions();
            PersistenceRecords.InteractionRow row = interactionId == null
                    ? interactions.selectActiveInteraction(threadId)
                    : interactions.selectInteraction(new PersistenceRecords.InteractionKey(threadId, interactionId));
            if (interactionId != null && row == null) {
                return Optional.empty();
            }
            PersistenceRecords.InteractionDraftRow draft = row == null ? null
                    : interactions.selectDraft(new PersistenceRecords.InteractionKey(threadId, row.interactionId()));
            Long currentSequence = interactions.selectCurrentEventSequence(threadId);
            long sequence = currentSequence == null ? 0L : currentSequence;
            Optional<InteractionRequest> request = Optional.ofNullable(row).map(this::decode);
            String turnState = row == null ? null : interactions.selectTurnState(row.turnId());
            return Optional.of(new InteractionSnapshot(threadId, sequence, request,
                    Optional.ofNullable(draft).map(this::decodeDraft), InteractionResumeState.from(request, turnState)));
        });
    }

    /**
     * 只返回仍待回答的活动请求，已回答但等待恢复的请求由 read 保留给 UI 对账。
     */
    @Override
    public Optional<InteractionRequest> findActive(String threadId) {
        return read(threadId, null).flatMap(InteractionSnapshot::request)
                .filter(value -> value.status() == InteractionStatus.PENDING);
    }

    /**
     * 以 draftRevision CAS 保存用户编辑，响应失败时不覆盖服务端新版本。
     */
    @Override
    public InteractionDraft saveDraft(InteractionDraft draft, long expectedDraftRevision, String operationKey) {
        return transactions.required(mappers -> {
            if (mappers.interactions().saveDraft(new PersistenceRecords.InteractionDraftCas(
                    draft.threadId(), draft.interactionId(), expectedDraftRevision, encode(draft.answers()), draft.page(),
                    draft.collapsed(), operationKey, draft.updatedAt().toString())) != 1) {
                        throw conflict();
                    }
            PersistenceRecords.InteractionDraftRow saved = mappers.interactions().selectDraft(
                    new PersistenceRecords.InteractionKey(draft.threadId(), draft.interactionId()));
            InteractionDraft decoded = decodeDraft(saved);
            if (mappers.interactions().insertEvent(new PersistenceRecords.InteractionEventInsert(draft.threadId(),
                    draft.interactionId(), decoded.draftRevision(), InteractionEvent.Kind.DRAFT_CHANGED.name(),
                    draft.updatedAt().toString())) != 1) {
                        throw conflict();
                    }
            return decoded;
        });
    }

    /**
     * 读取序列增量并解码为领域事件，客户端负责检测缺口后重新 read。
     */
    @Override
    public List<InteractionEvent> events(String threadId, long afterSequence) {
        return transactions.required(mappers -> mappers.interactions().selectEvents(threadId, afterSequence).stream()
                .map(this::decodeEvent).toList());
    }

    /**
     * 将持久 JSON 严格解码为领域请求，任何损坏都 fail closed。
     */
    private InteractionRequest decode(PersistenceRecords.InteractionRow row) {
        try {
            return new InteractionRequest(row.interactionId(), row.threadId(), row.turnId(), row.toolCallId(),
                    row.planRevisionId(), row.runId(), row.operationKey(),
                    mapper.readValue(row.questionsJson(), QUESTIONS), enumValue(row.status()),
                    mapper.readValue(row.answersJson(), ANSWERS), row.interactionRevision(), Instant.parse(row.createdAt()),
                    Instant.parse(row.updatedAt()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid interaction persistence", failure);
        }
    }

    /**
     * 将草稿 JSON 解码为有界领域答案，拒绝隐式默认值。
     */
    private InteractionDraft decodeDraft(PersistenceRecords.InteractionDraftRow row) {
        try {
            return new InteractionDraft(row.threadId(), row.interactionId(), mapper.readValue(row.answersJson(), ANSWERS),
                    row.page(), row.collapsed(), row.operationKey(), row.draftRevision(), Instant.parse(row.updatedAt()));
        } catch (JsonProcessingException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid interaction draft persistence", failure);
        }
    }

    /**
     * 解码数据库分配的事件序列，保持观察顺序和状态身份不变。
     */
    private InteractionEvent decodeEvent(PersistenceRecords.InteractionEventRow row) {
        return new InteractionEvent(row.threadId(), row.interactionId(), row.interactionRevision(), row.eventSequence(),
                InteractionEvent.Kind.valueOf(row.kind()), Instant.parse(row.occurredAt()));
    }

    /**
     * 统一编码领域载荷，编码失败转换为稳定存储错误。
     */
    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new StorageException(StorageException.Code.IO, "cannot encode interaction", failure);
        }
    }
}
