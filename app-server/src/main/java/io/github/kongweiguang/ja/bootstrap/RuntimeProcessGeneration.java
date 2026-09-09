// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;

import java.time.Instant;
import java.util.Objects;

/** 保存由唯一 SQLite owner 持久分配的进程代际，统一写租约持有与启动恢复的 fencing 边界。 */
public final class RuntimeProcessGeneration {
    private final long value;

    /**
     * 代际只能在 JaDatabase 已取得独占 lease 后由数据库 ledger 分配；分配与遗留 claim 废弃共享事务，
     * 避免时钟回拨、同毫秒启动或开发期残留代际碰撞留下伪当前 owner。
     */
    static RuntimeProcessGeneration allocate(MybatisTaskRepository repository, Instant occurredAt) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return new RuntimeProcessGeneration(repository.beginProcessGeneration(occurredAt));
    }

    /** AOT 只需要可分析的正数占位，运行分支不会把它绑定到 AgentLoop 或写入数据库。 */
    static RuntimeProcessGeneration aotPlaceholder() {
        return new RuntimeProcessGeneration(1L);
    }

    /** 返回本进程冻结代际；值在 Bean 生命周期内不可替换或重新分配。 */
    public long value() {
        return value;
    }

    /** 构造器只接受 Repository 或 AOT 工厂验证过的正数，阻止未分配身份进入运行时。 */
    private RuntimeProcessGeneration(long value) {
        if (value < 1) throw new IllegalArgumentException("invalid process generation");
        this.value = value;
    }
}
