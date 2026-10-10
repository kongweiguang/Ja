// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOwnerProjection;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjection;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 读取 Ja 自有的会话偏好与 Turn 状态，不接管执行事实和恢复游标。
 */
public final class MybatisConversationProjectionRepository implements ConversationProjectionRepository {
    private final MybatisUnitOfWork transactions;

    /**
     * 生产查询复用 Solon 事务边界，保证 Thread 与 Turn 来自同一个 SQLite 读取快照。
     */
    public MybatisConversationProjectionRepository(SqlSessionFactory sessions) {
        this.transactions = new MybatisUnitOfWork(sessions);
    }

    /**
     * 测试可显式注入 SQLite 事务 owner，生产组合仍使用 Solon 提供的事务边界。
     */
    public MybatisConversationProjectionRepository(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        this.transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /**
     * 投影路径来源身份，使 continue/reask 在不读旧消息表时仍能验证历史切点。
     */
    private static TurnProjection turnProjection(PersistenceRecords.TurnRow turn) {
        Objects.requireNonNull(turn, "turn");
        try {
            return new TurnProjection(requireText(turn.turnId(), "turn_id"),
                    TurnState.valueOf(requireText(turn.state(), "state")), turn.mutationVersion(),
                    turn.currentPath(), turn.sourceMessageId());
        } catch (IllegalArgumentException invalidProjection) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "turn projection contains invalid persisted values", invalidProjection);
        }
    }

    /**
     * 拒绝缺失的必需持久字段，但错误信息不回显数据库中的原始值。
     */
    private static String requireText(String value, String column) {
        if (value == null || value.isBlank()) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "missing " + column);
        }
        return value;
    }

    /**
     * 同一 Unit of Work 读取配置与全部 Turn 状态；消息历史和执行恢复仍由各自唯一 owner 提供，
     * 避免这个只读端口重新成为第二份循环存储。
     */
    @Override
    public Optional<ThreadProjection> readThread(String threadId) {
        if (threadId == null || !threadId.startsWith("thr_")) {
            throw new IllegalArgumentException("invalid threadId");
        }
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(threadId);
            if (thread == null) {
                return Optional.empty();
            }
            List<TurnProjection> turns = mapper.agent().selectTurns(threadId).stream()
                    .map(MybatisConversationProjectionRepository::turnProjection)
                    .toList();
            try {
                return Optional.of(new ThreadProjection(threadId,
                        requireText(thread.workspaceId(), "workspace_id"),
                        PersistenceRowProjections.threadPreferences(thread), thread.revision(), turns));
            } catch (IllegalArgumentException invalidProjection) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "thread projection contains invalid persisted values", invalidProjection);
            }
        });
    }

    /**
     * 用唯一 turn_id 索引定位所有者；Turn 与 Thread 配置必须来自同一 SQLite 读取快照。
     */
    @Override
    public Optional<TurnOwnerProjection> readTurn(String turnId) {
        if (turnId == null || !turnId.startsWith("turn_")) {
            throw new IllegalArgumentException("invalid turnId");
        }
        return transactions.required(mapper -> {
            PersistenceRecords.TurnRow turn = mapper.agent().selectTurnById(turnId);
            if (turn == null) {
                return Optional.empty();
            }
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(turn.threadId());
            if (thread == null || !turn.threadId().equals(thread.threadId())
                || turn.threadRevision() == null || turn.threadRevision() != thread.revision()) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "Turn owner projection is inconsistent");
            }
            try {
                return Optional.of(new TurnOwnerProjection(turn.threadId(),
                        requireText(thread.workspaceId(), "workspace_id"),
                        PersistenceRowProjections.threadPreferences(thread), turn.threadRevision(),
                        turnProjection(turn)));
            } catch (IllegalArgumentException invalidProjection) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "Turn owner projection contains invalid persisted values", invalidProjection);
            }
        });
    }
}
