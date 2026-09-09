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

    /** 按 admission 顺序读取一个 Thread 的活动 Turn 与完整 execution 行。 */
    List<PersistenceRecords.RecoveryTurnRow> selectRecoveryTurns(String threadId);

    /**
     * 将启动时遗留的活动 Turn 收敛为稳定终态，并保留取消优先语义。
     */
    int suspendTurn(PersistenceRecords.RecoveryTurnCas values);

    /** 已提交取消在启动期直接赢得 CANCELLED 终态并清除游标。 */
    int cancelTurn(PersistenceRecords.RecoveryTurnCas values);

    /** 缺失或损坏游标稳定失败关闭，绝不从消息或 Tool 历史猜测位置。 */
    int failCorruptTurn(PersistenceRecords.RecoveryTurnCas values);

    /** 恢复调和后整体替换 execution JSON。 */
    int replaceExecution(PersistenceRecords.TurnExecutionWrite values);

    /** 终态恢复删除 execution 行。 */
    int deleteExecution(String turnId);

    /** 精确读取 Tool 游标当前 ordinal，不扫描历史推断位置。 */
    PersistenceRecords.ToolRow selectRecoveryTool(String turnId, int ordinal);

    /** 启动恢复关闭当前 batch 的未结算 Tool，禁止任何副作用分类在 Resume 时被重放。 */
    int failUnsettledTool(String turnId, String callId, String occurredAt);

    /** 为未执行或结果不确定的调用写入与 Assistant Tool Call 配对的唯一失败消息。 */
    int insertUnavailableToolMessage(PersistenceRecords.RecoveryToolMessage values);

    /** 启动时本地拒绝已过期审批，不产生任何外部唤醒。 */
    int denyExpiredApprovals(String turnId, String occurredAt);

    /** 终态恢复关闭全部待决审批和输入。 */
    int closePendingApprovals(String turnId, String occurredAt);

    /** 终态恢复取消尚未消费的 steering 与 follow-up，避免后续被错误复用。 */
    int cancelPendingInputs(String turnId, String occurredAt);

    /**
     * 为本次异常恢复的 Turn 写入显式 capture_failed，防止历史把缺失记录误解为零修改。
     */
    int insertRecoveredChangeSets(PersistenceRecords.RecoveryCommand values);

    /**
     * 当该 Thread 实际发生恢复时只推进一次 revision。
     */
    int advanceRecoveredThread(PersistenceRecords.RecoveryCommand values);
}
