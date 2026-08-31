// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.InstructionScopeMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** SQLite/MyBatis scope 仓储；容量判定与插入共享一个 Unit of Work。 */
public final class MybatisInstructionScopeRepository implements InstructionScopeRepository {
    private static final int MAX_SCOPES = 256;
    private final MybatisUnitOfWork transactions;

    /** 生产构造器复用 Solon transaction owner，不在仓储内自行提交。 */
    public MybatisInstructionScopeRepository(SqlSessionFactory sessions) {
        transactions = new MybatisUnitOfWork(sessions);
    }

    /** focused test 仅替换事务 owner，SQL、Mapper 和真实 SQLite 均与生产一致。 */
    public MybatisInstructionScopeRepository(SqlSessionFactory sessions, MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /** 复制 Mapper 结果，禁止 transaction-scoped 对象逃逸。 */
    @Override
    public List<String> list(String threadId) {
        requireThreadId(threadId);
        return transactions.required(mappers -> List.copyOf(mappers.instructionScopes().selectScopes(threadId)));
    }

    /** 先处理幂等命中，再执行 256 上限，避免满容量时拒绝已有 scope 的安全重放。 */
    @Override
    public Registration register(String threadId, String relativeDirectory, Instant discoveredAt) {
        requireThreadId(threadId);
        requireRelativeDirectory(relativeDirectory);
        Objects.requireNonNull(discoveredAt, "discoveredAt");
        return transactions.required(mappers -> {
            InstructionScopeMapper mapper = mappers.instructionScopes();
            PersistenceRecords.ScopeInsert insert = new PersistenceRecords.ScopeInsert(
                    threadId, relativeDirectory, discoveredAt.toString());
            int inserted = mapper.insertScopeWithinLimit(insert);
            if (inserted == 1) return Registration.REGISTERED;
            if (mapper.countScope(new PersistenceRecords.ScopeKey(threadId, relativeDirectory)) != 0) {
                return Registration.ALREADY_PRESENT;
            }
            if (mapper.countScopes(threadId) >= MAX_SCOPES) return Registration.LIMIT_REACHED;
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "instruction scope insert lost without a persisted winner or capacity result");
        });
    }

    /** Thread 身份必须进入既有数据库外键空间，禁止空值产生无归属 scope。 */
    private static void requireThreadId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("threadId is required");
        }
    }

    /** 仓储只接受已由文件系统边界规范化的 `/` 相对目录。 */
    private static void requireRelativeDirectory(String directory) {
        if (directory == null || directory.isBlank() || directory.equals(".")
                || directory.startsWith("/") || directory.contains(":")
                || directory.startsWith("../") || directory.contains("\\")
                || directory.equals("..") || directory.contains("/../")
                || directory.contains("/./") || directory.startsWith("./")
                || directory.contains("//") || directory.endsWith("/")) {
            throw new IllegalArgumentException("relative instruction directory is invalid");
        }
    }
}
