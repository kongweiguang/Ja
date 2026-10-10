// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.startup;

import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.SideChatPersistence;
import org.apache.ibatis.session.SqlSessionFactory;

import java.util.Objects;

/**
 * 进程启动时只执行本地数据库维护，不读取或派发可恢复的 Agent 执行。
 */
public final class StartupMaintenanceService {
    private final boolean initialized;

    /**
     * 只在明确的初始化流程结束后构造已就绪实例。
     */
    private StartupMaintenanceService(boolean initialized) {
        this.initialized = initialized;
    }

    /**
     * AOT 元数据构建只发布类型信息，不能打开数据库或清理实际用户数据。
     */
    public static StartupMaintenanceService metadataOnly() {
        return new StartupMaintenanceService(false);
    }

    /**
     * 绑定 WAL 生命周期并清理临时侧聊；Agent 执行状态仍由用户显式调用 Engine.resume 恢复。
     */
    public static StartupMaintenanceService initialize(JaDatabase database, SqlSessionFactory sessions) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sessions, "sessions");
        return initialize(database, sessions, new SideChatPersistence(sessions));
    }

    /**
     * 把 SideChatPersistence 作为明确的出站依赖注入，生产与真实 SQLite 验证共用同一初始化顺序。
     */
    static StartupMaintenanceService initialize(JaDatabase database, SqlSessionFactory sessions,
                                                SideChatPersistence sideChats) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(sideChats, "sideChats");
        database.bindWalCheckpoint(sessions);
        try (sideChats) {
            sideChats.purgeRecoveredSideChats();
        }
        return new StartupMaintenanceService(true);
    }

    /**
     * 依赖启动清理的运行服务必须拒绝 AOT 占位对象。
     */
    public void requireInitialized() {
        if (!initialized) {
            throw new IllegalStateException("startup maintenance has not run");
        }
    }
}
