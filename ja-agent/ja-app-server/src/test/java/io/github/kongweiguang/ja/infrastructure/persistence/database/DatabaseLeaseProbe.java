// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import java.nio.file.Path;
import java.nio.file.Files;

/**
 * 只供跨职责持久化测试观察数据库 lease，不把生产实现提升为公共 API。
 */
public final class DatabaseLeaseProbe {
    /**
     * 以 {@link AutoCloseable} 暴露 lease 生命周期，调用方不能依赖包内实现类型。
     */
    public static AutoCloseable acquire(Path databasePath) {
        return DatabaseLease.acquire(databasePath);
    }

    /**
     * 由隔离 JVM 持有 lease 并以 readiness 文件通知测试进程，关闭 stdin 后正常释放租约。
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"--hold".equals(args[0])) {
            throw new IllegalArgumentException("expected --hold database ready-file");
        }
        try (AutoCloseable ignored = acquire(Path.of(args[1]))) {
            Files.writeString(Path.of(args[2]), "ready");
            System.in.read();
        }
    }

    /**
     * 测试桥接器没有实例状态，禁止构造以免被误当成 fixture owner。
     */
    private DatabaseLeaseProbe() {
    }
}
