// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.concurrent;

import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * 向阻塞 IO、Provider 与 Tool 传播只读取消意图，不暴露取消协调器所有权。
 */
public interface CancellationToken {
    /**
     * 查询调用方是否已经发布取消；实现必须提供跨线程可见性。
     */
    boolean isCancellationRequested();

    /**
     * 返回经过安全化的取消原因，不得包含请求正文、路径或凭据。
     */
    Optional<String> reason();

    /**
     * 注册至多一次的取消回调，返回值用于及时解除长期资源引用。
     */
    Registration onCancellation(Runnable callback);

    /**
     * 在同步边界快速失败，并保留标准 CancellationException 语义。
     */
    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException(reason().orElse("turn cancelled"));
        }
    }

    /**
     * 为明确不可取消的内部操作提供无状态令牌，禁止把 null 当作取消语义。
     */
    static CancellationToken none() {
        return new CancellationToken() {
            /** 固定返回未取消，匿名实现不持有任何运行时状态。 */
            @Override
            public boolean isCancellationRequested() {
                return false;
            }

            /** 无取消事实时返回空值，避免伪造默认原因。 */
            @Override
            public Optional<String> reason() {
                return Optional.empty();
            }

            /** 不保留回调引用，防止不可取消操作产生无意义生命周期负担。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return Registration.noop();
            }
        };
    }

    /**
     * 取消回调注册的唯一解除句柄。
     */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        /**
         * 幂等解除回调，使请求完成后不再持有外部资源。
         */
        @Override
        void close();

        /**
         * 为未注册回调的分支提供可统一关闭的空句柄。
         */
        static Registration noop() {
            return () -> {
            };
        }
    }
}
