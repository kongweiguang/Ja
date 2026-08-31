// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.recovery;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 在 RPC admission 开放前，将崩溃遗留的 active Turn 和 RUNNING Tool 收敛为权威状态。
 */
public final class StartupRecoveryService {
    private final MybatisUnitOfWork transactions;
    private final Clock clock;

    /**
     * 与主 store 共用官方 MyBatis-Solon factory，恢复本身也是一个事务。
     */
    public StartupRecoveryService(SqlSessionFactory sessions, Clock clock) {
        transactions = new MybatisUnitOfWork(sessions);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * focused test 通过真实 SQLite transaction 验证恢复原子性。
     */
    public StartupRecoveryService(SqlSessionFactory sessions, Clock clock,
                                  MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 每个受影响 Thread 只推进一次 revision，Turn terminal 与 Tool 的内部不确定状态同事务可见。
     */
    public RecoveryResult recover() {
        Instant occurredAt = clock.instant();
        return transactions.required(mapper -> {
            int turns = 0;
            int tools = 0;
            int changeSets = 0;
            List<String> threads = mapper.recovery().selectRecoveryThreadIds();
            for (String threadId : threads) {
                PersistenceRecords.RecoveryCommand command =
                        new PersistenceRecords.RecoveryCommand(threadId, occurredAt.toString());
                turns += mapper.recovery().recoverActiveTurns(command);
                changeSets += mapper.recovery().insertRecoveredChangeSets(command);
                tools += mapper.recovery().recoverRunningTools(command);
                if (mapper.recovery().advanceRecoveredThread(command) != 1) {
                    throw new StorageException(StorageException.Code.TRANSACTION,
                            "startup recovery lost its thread owner");
                }
            }
            return new RecoveryResult(threads.size(), turns, tools, changeSets, occurredAt);
        });
    }

    /**
     * 汇总一次启动恢复实际收敛的数量与统一时间，便于幂等验收和诊断。
     */
    public record RecoveryResult(int threads, int turns, int tools, int changeSets, Instant occurredAt) {
    }
}
