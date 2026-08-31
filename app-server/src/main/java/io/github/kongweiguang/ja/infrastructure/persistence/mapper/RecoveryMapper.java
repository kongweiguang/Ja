// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 启动恢复批次专用 Mapper，避免运行期服务持有恢复 SQL。
 */
@Mapper
public interface RecoveryMapper {
    /**
     * 列出存在活动 Turn 的 Thread，恢复过程据此逐 Thread 串行收敛。
     */
    List<String> selectRecoveryThreadIds();

    /**
     * 将启动时遗留的活动 Turn 收敛为稳定终态，并保留取消优先语义。
     */
    int recoverActiveTurns(PersistenceRecords.RecoveryCommand values);

    /**
     * 为本次异常恢复的 Turn 写入显式 capture_failed，防止历史把缺失记录误解为零修改。
     */
    int insertRecoveredChangeSets(PersistenceRecords.RecoveryCommand values);

    /**
     * 仅把不确定副作用保留为内部 UNKNOWN，同时公开 presentation 收敛为稳定 error。
     */
    int recoverRunningTools(PersistenceRecords.RecoveryCommand values);

    /**
     * 当该 Thread 实际发生恢复时只推进一次 revision。
     */
    int advanceRecoveredThread(PersistenceRecords.RecoveryCommand values);
}
