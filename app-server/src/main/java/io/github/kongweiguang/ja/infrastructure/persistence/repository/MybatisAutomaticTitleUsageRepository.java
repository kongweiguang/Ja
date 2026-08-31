// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.ModelUsage;
import io.github.kongweiguang.ja.conversation.port.out.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/**
 * 自动标题额外模型调用的独立 SQLite ledger；Thread 唯一 claim 与 outcome CAS 均由数据库收口。
 */
public final class MybatisAutomaticTitleUsageRepository implements AutomaticTitleUsagePort {
    private final MybatisUnitOfWork transactions;

    /** 生产构造只复用 Solon 发布的唯一具名 session factory。 */
    public MybatisAutomaticTitleUsageRepository(SqlSessionFactory sessions) {
        transactions = new MybatisUnitOfWork(sessions);
    }

    /** 注入 transaction owner 的构造保持真实数据库语义，不在生产源码建立测试模式。 */
    public MybatisAutomaticTitleUsageRepository(SqlSessionFactory sessions,
                                                MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
    }

    /**
     * `thread_id UNIQUE` 是跨线程与重启的最终资格门；generation identity 冲突仍作为事务失败关闭。
     */
    @Override
    public ClaimResult claim(GenerationClaim claim) {
        Objects.requireNonNull(claim, "claim");
        int changed = transactions.required(mapper -> mapper.attachments().insertTitleGeneration(
                new AttachmentRecords.TitleGenerationInsert(
                        claim.generationId(), claim.threadId(), claim.turnId(), claim.providerId(),
                        claim.modelId(), claim.configGeneration(), claim.claimedAt().toString())));
        return changed == 1 ? ClaimResult.ACQUIRED : ClaimResult.ALREADY_EXISTS;
    }

    /**
     * 首次写入只允许 `null → terminal`；CAS 竞争后回读相同事实视为幂等，任何矛盾事实均失败关闭。
     */
    @Override
    public void recordModelOutcome(ModelOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        transactions.required(mapper -> {
            AttachmentRecords.TitleGenerationRow current =
                    mapper.attachments().selectTitleGeneration(outcome.generationId());
            if (current == null) throw notFound();
            if (current.result() == null) {
                ModelUsage usage = outcome.usage();
                int changed = mapper.attachments().completeTitleGeneration(
                        new AttachmentRecords.TitleGenerationOutcome(
                                outcome.generationId(), outcome.result().name(),
                                usage == null ? null : usage.inputTokens(),
                                usage == null ? null : usage.outputTokens(),
                                usage == null ? null : usage.totalTokens(),
                                outcome.failureCode(), outcome.completedAt().toString()));
                if (changed == 1) return null;
                current = mapper.attachments().selectTitleGeneration(outcome.generationId());
            }
            if (!sameOutcome(current, outcome)) throw conflict();
            return null;
        });
    }

    /** 完成时间保留首次提交值；幂等判定只比较 Provider 调用产生的终局事实。 */
    private static boolean sameOutcome(AttachmentRecords.TitleGenerationRow row, ModelOutcome outcome) {
        if (row == null || !outcome.result().name().equals(row.result())
            || !Objects.equals(outcome.failureCode(), row.failureCode())) {
            return false;
        }
        ModelUsage usage = outcome.usage();
        return usage == null
                ? row.inputTokens() == null && row.outputTokens() == null && row.totalTokens() == null
                : Objects.equals(usage.inputTokens(), row.inputTokens())
                  && Objects.equals(usage.outputTokens(), row.outputTokens())
                  && Objects.equals(usage.totalTokens(), row.totalTokens());
    }

    /** 缺失 claim 表示调用顺序损坏，不能静默补写一条无归属 usage。 */
    private static StorageException notFound() {
        return new StorageException(StorageException.Code.NOT_FOUND,
                "automatic title generation claim is unavailable");
    }

    /** 相同 generation 的第二个矛盾终态必须保留首个事实并拒绝覆盖。 */
    private static StorageException conflict() {
        return new StorageException(StorageException.Code.CAS_CONFLICT,
                "automatic title generation outcome conflicts");
    }
}
