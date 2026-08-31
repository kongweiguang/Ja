// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Turn/Message/Tool/Approval/Usage 事实 Mapper。
 */
@Mapper
public interface AgentMapper {
    /**
     * 按 Thread 与 Turn 复合身份读取单个 Turn，避免调用方误用全局查询。
     */
    PersistenceRecords.TurnRow selectTurn(PersistenceRecords.TurnKey values);

    /**
     * 按持久化顺序读取 Thread 下全部 Turn，供权威快照组装。
     */
    List<PersistenceRecords.TurnRow> selectTurns(@Param("threadId") String threadId);

    /**
     * 按 V1 基线保证的全局 Turn 身份读取取消目标。
     */
    PersistenceRecords.TurnRow selectTurnById(@Param("turnId") String turnId);

    /**
     * 插入初始 QUEUED Turn；唯一约束负责拒绝身份复用。
     */
    int insertTurn(PersistenceRecords.TurnInsert values);

    /**
     * 以 mutation version 为门登记一次取消意图，重复竞争必须返回零行。
     */
    int claimCancellation(PersistenceRecords.CancellationClaim values);

    /**
     * 以 mutation version 比较并更新 Turn 状态和终态字段。
     */
    int compareAndSetTurn(PersistenceRecords.TurnCas values);

    /**
     * 取消声明后的 Tool batch 只推进 mutation version 和更新时间，不允许借机改写 Turn 状态。
     */
    int advanceCancellationToolBatch(PersistenceRecords.TurnAdvance values);

    /**
     * 在同一写事务中分配下一条消息序号，保持 Thread 内稳定顺序。
     */
    Long selectNextMessageOrdinal(@Param("threadId") String threadId);

    /**
     * 插入已经分配序号的消息，唯一约束阻止消息身份或序号重复。
     */
    int insertMessage(PersistenceRecords.MessageInsert values);

    /** 追加一条面向客户端的阶段化安全文本，不复用模型上下文 blocks。 */
    int insertTimelineMessage(PersistenceRecords.TimelineMessageInsert values);

    /**
     * 按序号读取 Thread 消息，避免依赖数据库未声明的自然顺序。
     */
    List<PersistenceRecords.MessageRow> selectMessages(@Param("threadId") String threadId);

    /**
     * 按 Turn 与调用身份读取 Tool 状态，供状态迁移校验。
     */
    PersistenceRecords.ToolRow selectTool(PersistenceRecords.ToolKey values);

    /**
     * 插入 PREPARED Tool 事实，调用序号和身份必须同时唯一。
     */
    int insertTool(PersistenceRecords.ToolInsert values);

    /**
     * 仅允许 PREPARED Tool 进入 RUNNING，受影响行数即为状态门结果。
     */
    int startTool(PersistenceRecords.ToolStart values);

    /**
     * 仅完成尚未终结的 Tool，避免重复结果覆盖首个事实。
     */
    int finishTool(PersistenceRecords.ToolFinish values);

    /** 审批等待或恢复时原子更新安全展示状态，内部 Tool state 仍由执行事实推进。 */
    int updateToolPresentation(PersistenceRecords.ToolPresentationUpdate values);

    /** 统计仍未完成的 Tool，成功终态必须以此证明不存在悬空执行。 */
    int countUnfinishedTools(@Param("turnId") String turnId);

    /** 失败或取消终态把所有未完成 Tool 一次性收敛，避免历史继续显示等待审批或运行中。 */
    int settleUnfinishedTools(PersistenceRecords.ToolSettlement values);

    /** 保存已经脱敏的完整 Tool 输出，物理路径和 raw result 永不进入该表。 */
    int insertToolArtifact(PersistenceRecords.ToolArtifactInsert values);

    /** 以 thread+turn+call+artifact 四元身份读取，防止枚举 artifactId 越权。 */
    PersistenceRecords.ToolArtifactRow selectToolArtifact(PersistenceRecords.ToolArtifactKey values);

    /**
     * 按审批与 Turn 身份读取审批事实，防止跨 Turn 关联。
     */
    PersistenceRecords.ApprovalRow selectApproval(PersistenceRecords.ApprovalKey values);

    /**
     * 插入待决审批及到期时间，唯一约束阻止重复请求。
     */
    int insertApproval(PersistenceRecords.ApprovalInsert values);

    /**
     * 仅在待决且未过期时写入审批决定，过期或竞争返回零行。
     */
    int resolveApproval(PersistenceRecords.ApprovalResolve values);

    /**
     * 插入模型轮次用量，唯一键保证重试不会重复计量。
     */
    int insertUsage(PersistenceRecords.UsageInsert values);

    /**
     * 仅按 Turn 统计 committed usage，供事务验收与诊断确认不存在部分提交。
     */
    int countUsageForTurn(@Param("turnId") String turnId);

    /** 插入一条独立 FIFO 输入，队列写入不推进 Turn revision。 */
    int insertPendingInput(PersistenceRecords.PendingInputInsert values);

    /** 按创建时间和稳定 ID 读取指定类型的首条待消费输入。 */
    PersistenceRecords.PendingInputRow selectPendingInput(PersistenceRecords.PendingInputQuery values);

    /** 只允许首个消费者把 PENDING 改为 CONSUMED。 */
    int consumePendingInput(PersistenceRecords.PendingInputConsume values);

    /** Turn 取消时一次性取消所有尚未消费的输入。 */
    int cancelPendingInputs(PersistenceRecords.PendingInputCancel values);

    /** 输入消费只推进 mutation version 和更新时间，不改变当前运行状态。 */
    int advanceInputConsumption(PersistenceRecords.InputAdvance values);
}
