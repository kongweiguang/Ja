// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.Objects;

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
