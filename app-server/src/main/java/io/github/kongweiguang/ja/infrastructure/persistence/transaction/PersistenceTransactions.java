// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.transaction;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository.InputQueueException;

import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranPolicy;
import org.noear.solon.data.tran.TranUtils;

import java.lang.annotation.Annotation;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从 Repository callback contract 到 Solon transaction manager 的窄桥接。
 *
 *
 * <p>MyBatis 使用 {@code SolonManagedTransactionFactory}；每个 Unit of Work 在此包装后，
 * 即使聚焦测试直接使用 Repository 而不经 Solon proxy，commit/rollback 仍发生在 callback 边界。</p>
 */
final class PersistenceTransactions {
    private static final Transaction REQUIRED = new RequiredTransaction();

    /**
     * 在 required Solon transaction 中执行 callback 并保留返回值。
     */
    static <T> T required(CheckedSupplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        try {
            TranUtils.execute(REQUIRED, () -> result.set(supplier.get()));
            return result.get();
        } catch (StorageException failure) {
            throw failure;
        } catch (InputQueueException failure) {
            // 队列容量、接收门和条目 CAS 是公开业务失败，事务桥不得把它们降级成不透明存储错误。
            throw failure;
        } catch (Throwable failure) {
            throw new StorageException(StorageException.Code.TRANSACTION,
                    "Solon transaction failed", failure);
        }
    }

    /**
     * 将 checked callback 失败约束在 Repository 稳定异常面内。
     */
    @FunctionalInterface
    interface CheckedSupplier<T> {
        /**
         * 计算一个 transaction 结果。
         */
        T get() throws Exception;
    }

    /**
     * 未经过 Solon AOP proxy 时使用的 runtime transaction metadata。
     */
    private static final class RequiredTransaction implements Transaction {
        /**
         * 返回 required 传播策略，使嵌套工作共享同一 SQLite connection。
         */
        @Override
        public TranPolicy policy() {
            return TranPolicy.required;
        }

        /**
         * 保持 datasource 定义的 SQLite 默认 isolation。
         */
        @Override
        public org.noear.solon.data.tran.TranIsolation isolation() {
            return org.noear.solon.data.tran.TranIsolation.unspecified;
        }

        /**
         * Repository callback 是读写单元。
         */
        @Override
        public boolean readOnly() {
            return false;
        }

        /**
         * 避免从 infrastructure metadata 传播面向用户的消息。
         */
        @Override
        public String message() {
            return "ja-persistence";
        }

        /**
         * 满足 synthetic transaction metadata 的 Annotation runtime contract。
         */
        @Override
        public Class<? extends Annotation> annotationType() {
            return Transaction.class;
        }
    }

    /**
     * 静态桥接器禁止实例化，避免产生没有事务上下文意义的对象。
     */
    private PersistenceTransactions() {
    }
}
