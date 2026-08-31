// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import java.nio.file.Path;

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
     * 测试桥接器没有实例状态，禁止构造以免被误当成 fixture owner。
     */
    private DatabaseLeaseProbe() {
    }
}
