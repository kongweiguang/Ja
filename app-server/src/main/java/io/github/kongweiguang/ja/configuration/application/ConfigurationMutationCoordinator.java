// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.application;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 在进程内按配置文件身份串行化 CAS 读改写。
 *
 * <p>固定条带避免为动态工作区永久保存锁对象；不同路径发生哈希碰撞时只会降低并行度，
 * 不会改变 CAS 正确性。Ja app-server 是唯一文件 owner，因此该协调器明确只保证单进程内
 * 多服务实例的 CAS；另一个 app-server 必须由进程生命周期门禁拒绝启动。</p>
 */
public final class ConfigurationMutationCoordinator {
    private static final int STRIPE_COUNT = 64;
    private static final ReentrantLock[] STRIPES = createStripes();

    /**
     * 该类型只提供进程级协调能力，禁止创建无状态实例。
     */
    private ConfigurationMutationCoordinator() {
    }

    /**
     * 在规范化路径对应的同一条带内执行完整 CAS 临界区。
     *
     * <p>锁必须覆盖“读取当前版本、比较、生成新文档、原子发布”全过程；若只保护写入，
     * 两个服务实例仍可能同时接受同一个旧版本。</p>
     */
    public static <T> T execute(Path storagePath, Supplier<T> mutation) {
        Path identity = Objects.requireNonNull(storagePath, "storagePath")
                .toAbsolutePath().normalize();
        Objects.requireNonNull(mutation, "mutation");
        ReentrantLock lock = STRIPES[Math.floorMod(identity.hashCode(), STRIPE_COUNT)];
        lock.lock();
        try {
            return mutation.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 创建固定数量的公平锁，避免持续写入被高频读取方长期饿死。
     */
    private static ReentrantLock[] createStripes() {
        ReentrantLock[] stripes = new ReentrantLock[STRIPE_COUNT];
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock(true);
        }
        return stripes;
    }
}
