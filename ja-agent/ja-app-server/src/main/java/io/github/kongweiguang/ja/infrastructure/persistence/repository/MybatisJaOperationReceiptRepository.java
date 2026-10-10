// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * 只读回执适配器；写路径由 JaAdmissionParticipantFactory/JaTransactionParticipant 同事务提交。
 */
public final class MybatisJaOperationReceiptRepository implements JaOperationReceiptRepository {
    private final MybatisUnitOfWork transactions;

    /**
     * 读取复用共享 Unit of Work；在 Store participant 作用域下会自动借用其正在提交的连接。
     */
    public MybatisJaOperationReceiptRepository(SqlSessionFactory sessions) {
        transactions = new MybatisUnitOfWork(Objects.requireNonNull(sessions, "sessions"));
    }

    /**
     * 测试注入事务 owner，但生产组合不创建新的连接或提交边界。
     */
    MybatisJaOperationReceiptRepository(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(Objects.requireNonNull(sessions, "sessions"),
                Objects.requireNonNull(owner, "owner"));
    }

    /**
     * 外部幂等回执限定固定 ID 语法，避免任意字符串进入查询和错误路径。
     */
    private static void requireOperationKey(String value) {
        if (value == null || !value.matches("op_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid client operation id");
        }
    }

    /**
     * 按固定客户端操作 ID读取其安全回执，未知或损坏记录都不能被解释成成功。
     */
    @Override
    public Optional<ClientOperationReceipt> readClientOperation(String operationKey) {
        requireOperationKey(operationKey);
        return transactions.required(mappers -> {
            PersistenceRecords.ClientOperationRow row = mappers.agent().selectClientOperation(operationKey);
            if (row == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ClientOperationReceipt(row.operationKey(), row.method(),
                        row.requestFingerprint(), row.threadId(), row.turnId(), row.threadRevision(),
                        row.queued(), row.approvalId(), row.decision()));
            } catch (IllegalArgumentException invalid) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "client operation receipt is invalid", invalid);
            }
        });
    }

    /**
     * 入队回执不读取正文；正文只由 Engine 按 inputId 和稳定记录水位从 AgentStore 解码。
     */
    @Override
    public Optional<InputReceipt> readInputOperation(String operationKey) {
        requireOperationKey(operationKey);
        return transactions.required(mappers -> {
            PersistenceRecords.InputOperationRow row = mappers.agent().selectInputOperation(operationKey);
            if (row == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new InputReceipt(row.operationKey(), row.requestFingerprint(),
                        row.threadId(), row.turnId(), row.inputId(), AgentInputQueue.Kind.valueOf(row.inputKind())));
            } catch (IllegalArgumentException invalid) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "input operation receipt is invalid", invalid);
            }
        });
    }
}
