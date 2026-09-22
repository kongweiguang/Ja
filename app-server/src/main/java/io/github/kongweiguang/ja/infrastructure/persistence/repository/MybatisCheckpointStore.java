// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.application.context.ContextPolicy;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Context checkpoint 的 append-only MyBatis 实现；CAS 锁定其来源 Thread revision。
 */
public final class MybatisCheckpointStore implements CheckpointStore {
    private final MybatisUnitOfWork transactions;
    private final PersistenceCodec codec;
    private final TurnExecutionStateCodec executions;

    /**
     * 生产 factory 来自官方 MyBatis-Solon plugin，与 ConversationRepository 共用同一 datasource/事务 owner。
     */
    public MybatisCheckpointStore(SqlSessionFactory sessions, ObjectMapper objectMapper) {
        transactions = new MybatisUnitOfWork(sessions);
        codec = new PersistenceCodec(objectMapper);
        executions = new TurnExecutionStateCodec(objectMapper);
    }

    /**
     * focused test 使用同一 Mapper/schema 验证真实 SQLite CAS。
     */
    public MybatisCheckpointStore(SqlSessionFactory sessions, ObjectMapper objectMapper,
                                  MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
        codec = new PersistenceCodec(objectMapper);
        executions = new TurnExecutionStateCodec(objectMapper);
    }

    /**
     * 同一事务读取 Thread revision 与最新派生 checkpoint，避免 source snapshot 撕裂。
     */
    @Override
    public Snapshot read(String threadId) {
        Objects.requireNonNull(threadId, "threadId");
        return transactions.required(mapper -> {
            Long threadRevisionValue = mapper.checkpoint().selectThreadRevision(threadId);
            if (threadRevisionValue == null) {
                throw new StorageException(StorageException.Code.NOT_FOUND, "thread was not found");
            }
            long threadRevision = threadRevisionValue;
            PersistenceRecords.CheckpointRow row = mapper.checkpoint().selectCheckpoint(threadId);
            if (row == null) return Snapshot.empty(threadId, threadRevision);
            ContextCheckpoint checkpoint = checkpoint(row);
            return new Snapshot(threadId, threadRevision, java.util.Optional.of(checkpoint));
        });
    }

    /**
     * 原子插入 checkpoint、推进 Thread revision 并返回持久化 receipt。相同 source 已存在时
     * 返回非新增胜者，确保并发重试不会发布第二个事件或占用当前 Turn 身份。
     */
    @Override
    public CommittedCheckpoint commit(CommitRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            return transactions.required(mapper -> {
                Long currentRevision = mapper.checkpoint().selectThreadRevision(request.threadId());
                if (currentRevision == null) {
                    throw new StorageException(StorageException.Code.NOT_FOUND,
                            "thread was not found");
                }
                PersistenceRecords.CheckpointKey key = new PersistenceRecords.CheckpointKey(
                        request.threadId(), request.expectedThreadRevision());
                PersistenceRecords.CheckpointRow existing = mapper.checkpoint().selectCheckpointSource(key);
                if (existing != null) {
                    return CommittedCheckpoint.reused(checkpoint(existing), currentRevision);
                }
                if (currentRevision != request.expectedThreadRevision()) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                            "checkpoint source revision changed before commit");
                }
                ContextCheckpoint checkpoint = request.checkpoint();
                PersistenceRecords.CheckpointInsert row = checkpointParameters(request.threadId(), checkpoint);
                if (mapper.checkpoint().insertCheckpoint(row) != 1) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                            "checkpoint insert lost the source race");
                }
                Long turnMutationVersion = null;
                if (request.turnOperation().isPresent()) {
                    CheckpointStore.TurnOperation operation = request.turnOperation().orElseThrow();
                    PersistenceRecords.TurnExecutionWrite execution = new PersistenceRecords.TurnExecutionWrite(
                            operation.turnId(), io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState.SCHEMA_VERSION,
                            executions.write(operation.completedExecution()));
                    if (mapper.agent().replaceTurnExecution(execution) != 1) {
                        throw new StorageException(StorageException.Code.CAS_CONFLICT,
                                "checkpoint execution state changed before commit");
                    }
                    if (mapper.agent().compareAndSetTurn(new PersistenceRecords.TurnCas(
                            request.threadId(), operation.turnId(), "RUNNING",
                            operation.expectedTurnMutationVersion(), checkpoint.createdAt().toString(),
                            null, null, null, null)) != 1) {
                        throw new StorageException(StorageException.Code.CAS_CONFLICT,
                                "checkpoint Turn mutation changed before commit");
                    }
                    turnMutationVersion = operation.expectedTurnMutationVersion() + 1;
                }
                Long committedRevision = mapper.history().allocateThreadRevision(new PersistenceRecords.ThreadRevision(
                        request.threadId(), checkpoint.createdAt().toString()));
                if (committedRevision == null) {
                    throw new StorageException(StorageException.Code.NOT_FOUND,
                            "thread was not found");
                }
                return turnMutationVersion == null
                        ? CommittedCheckpoint.created(checkpoint, committedRevision)
                        : CommittedCheckpoint.created(checkpoint, committedRevision, turnMutationVersion);
            });
        } catch (StorageException failure) {
            if (failure.code() == StorageException.Code.CAS_CONFLICT) {
                throw new CheckpointStore.CommitConflict(failure.getMessage());
            }
            throw failure;
        }
    }

    /**
     * 复用最新绑定一致的阶段；模型或 Tool 目录改变时才追加边界，新增普通消息不会重算旧选择。
     */
    @Override
    public ProjectionSnapshot prepareProjection(ProjectionRequest request) {
        Objects.requireNonNull(request, "request");
        return transactions.required(mapper -> {
            PersistenceRecords.ProjectionStageRow latest = mapper.projections()
                    .selectLatestProjectionStage(request.threadId());
            if (latest == null) {
                return insertProjectionStage(mapper, request, ProjectionReason.INITIAL);
            }
            ProjectionReason reason = latest.modelBinding().equals(request.binding().modelBinding())
                    ? latest.toolBinding().equals(request.binding().toolBinding())
                            ? null : ProjectionReason.TOOL_BINDING
                    : ProjectionReason.MODEL_BINDING;
            return reason == null ? projectionSnapshot(mapper, latest)
                    : insertProjectionStage(mapper, request, reason);
        });
    }

    /**
     * checkpoint 与 overflow 以 source/binding/reason 作为幂等定位；强杀后会复用同一阶段，
     * 但正常阶段从不回写，以保持已发送前缀稳定。
     */
    @Override
    public ProjectionSnapshot advanceProjection(ProjectionRequest request, ProjectionReason reason) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(reason, "reason");
        if (reason != ProjectionReason.CHECKPOINT && reason != ProjectionReason.OVERFLOW) {
            throw new IllegalArgumentException("only checkpoint or overflow may advance projection");
        }
        return transactions.required(mapper -> {
            PersistenceRecords.ProjectionStageRow existing = mapper.projections()
                    .selectProjectionStageIdentity(new PersistenceRecords.ProjectionStageIdentity(
                            request.threadId(), request.sourceRevision(), reason.name(),
                            request.binding().modelBinding(), request.binding().toolBinding()));
            return existing == null ? insertProjectionStage(mapper, request, reason)
                    : projectionSnapshot(mapper, existing);
        });
    }

    /**
     * 只插入尚未选择的 message；随后回读阶段全量映射，确保并发重复提交不会把旧结果升级或降级。
     */
    @Override
    public ProjectionSnapshot persistProjection(ProjectionSnapshot snapshot,
                                                 Map<String, ContextPolicy.ToolProjection> selections) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(selections, "selections");
        return transactions.required(mapper -> {
            PersistenceRecords.ProjectionStageRow stage = mapper.projections()
                    .selectProjectionStageIdentity(new PersistenceRecords.ProjectionStageIdentity(
                            snapshot.threadId(), snapshot.sourceRevision(), snapshot.reason().name(),
                            snapshot.binding().modelBinding(), snapshot.binding().toolBinding()));
            if (stage == null || !stage.stageId().equals(snapshot.stageId())) {
                throw new StorageException(StorageException.Code.CAS_CONFLICT,
                        "projection stage changed before selection persistence");
            }
            for (Map.Entry<String, ContextPolicy.ToolProjection> selection : selections.entrySet()) {
                mapper.projections().insertProjectionEntryIgnore(new PersistenceRecords.ProjectionEntryInsert(
                        snapshot.stageId(), selection.getKey(), selection.getValue().name(),
                        stage.createdAt()));
            }
            return projectionSnapshot(mapper, stage);
        });
    }

    /**
     * 阶段号和行插入共用事务；唯一业务身份把强杀后的重试收敛为同一阶段。若另一个事务已经
     * 提交相同身份则回读其获胜行，而阶段号冲突但身份不同仍显式失败，不能误借别人的边界。
     */
    private ProjectionSnapshot insertProjectionStage(PersistenceMappers mapper, ProjectionRequest request,
                                                     ProjectionReason reason) {
        Long stageNumber = mapper.projections().selectNextProjectionStageNumber(request.threadId());
        if (stageNumber == null || stageNumber < 1) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "projection stage number is unavailable");
        }
        String stageId = "projection_" + UUID.randomUUID();
        PersistenceRecords.ProjectionStageInsert insert = new PersistenceRecords.ProjectionStageInsert(stageId,
                request.threadId(), stageNumber, request.sourceRevision(), reason.name(),
                request.binding().modelBinding(), request.binding().toolBinding(), request.occurredAt().toString());
        if (mapper.projections().insertProjectionStage(insert) != 1) {
            PersistenceRecords.ProjectionStageRow winner = mapper.projections()
                    .selectProjectionStageIdentity(projectionStageIdentity(request, reason));
            if (winner != null) return projectionSnapshot(mapper, winner);
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "projection stage number changed before insert");
        }
        return new ProjectionSnapshot(stageId, request.threadId(), stageNumber, request.sourceRevision(), reason,
                request.binding(), Map.of());
    }

    /**
     * 所有阶段重读使用相同的业务身份，避免 INSERT OR IGNORE 后根据最新行猜测获胜者并改变旧前缀。
     */
    private static PersistenceRecords.ProjectionStageIdentity projectionStageIdentity(
            ProjectionRequest request, ProjectionReason reason) {
        return new PersistenceRecords.ProjectionStageIdentity(request.threadId(), request.sourceRevision(),
                reason.name(), request.binding().modelBinding(), request.binding().toolBinding());
    }

    /** 把 Mapper 行还原为领域 snapshot，数据库异常词汇不会泄漏到上下文策略或 Provider 调用。 */
    private static ProjectionSnapshot projectionSnapshot(PersistenceMappers mapper,
                                                         PersistenceRecords.ProjectionStageRow stage) {
        Map<String, ContextPolicy.ToolProjection> selections = new LinkedHashMap<>();
        List<PersistenceRecords.ProjectionEntryRow> rows = mapper.projections()
                .selectProjectionEntries(stage.stageId());
        for (PersistenceRecords.ProjectionEntryRow row : rows) {
            try {
                selections.put(row.messageId(), ContextPolicy.ToolProjection.valueOf(row.projection()));
            } catch (IllegalArgumentException invalid) {
                throw new StorageException(StorageException.Code.INVALID_STATE, "invalid projection entry");
            }
        }
        return new ProjectionSnapshot(stage.stageId(), stage.threadId(), stage.stageNumber(),
                stage.sourceRevision(), ProjectionReason.valueOf(stage.reason()),
                new ProjectionBinding(stage.modelBinding(), stage.toolBinding()), selections);
    }

    /**
     * 将领域 checkpoint 统一转换为 Mapper 参数；该方法不执行数据库操作，事务范围和
     * CAS 判定只能由带回执的 commit 边界拥有。
     */
    private PersistenceRecords.CheckpointInsert checkpointParameters(
            String threadId, ContextCheckpoint checkpoint) {
        return new PersistenceRecords.CheckpointInsert(
                checkpoint.checkpointId(), threadId, checkpoint.sourceRevision(), checkpoint.throughOrdinal(),
                checkpoint.retainedFromOrdinal(), codec.writeRetainedSplit(checkpoint.retainedSplit()),
                codec.writeSummary(checkpoint.summary()), checkpoint.estimatedTokens(),
                checkpoint.envelopeFingerprint(), checkpoint.strategyVersion(),
                codec.writeCheckpointUsage(checkpoint.usage()), checkpoint.createdAt().toString());
    }

    /**
     * 解码不可变行，让读取、幂等重试和恢复共用同一个 codec 路径。
     */
    private ContextCheckpoint checkpoint(PersistenceRecords.CheckpointRow row) {
        return new ContextCheckpoint(row.checkpointId(), row.threadId(), row.throughOrdinal(),
                row.retainedFromOrdinal(), row.sourceRevision(), codec.readRetainedSplit(row.retainedSplitJson()),
                codec.readSummary(row.summaryJson()), row.estimatedTokens(), row.envelopeFingerprint(), row.strategyVersion(),
                codec.readCheckpointUsage(row.usageJson()), Instant.parse(row.createdAt()));
    }
}
