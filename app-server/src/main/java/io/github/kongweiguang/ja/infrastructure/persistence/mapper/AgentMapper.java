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

    /** 当前 admission 已插入 Turn 后，以存在性判断是否已有其它真实 Turn，避免历史累计扫描。 */
    boolean hasOtherTurns(@Param("threadId") String threadId, @Param("turnId") String turnId);

    /** admission 在 Turn 可见前同时写入初始 READY 游标。 */
    int insertTurnExecution(PersistenceRecords.TurnExecutionWrite values);

    /** 内部 Turn 在同一 admission 事务冻结不可见结构化上下文，禁止首轮前崩溃导致信息丢失。 */
    int insertInternalTurnContext(PersistenceRecords.InternalTurnContextInsert values);

    /** 事务内读取当前完整游标，供恢复与提交 CAS 校验共享同一权威状态。 */
    PersistenceRecords.TurnExecutionRow selectTurnExecution(@Param("turnId") String turnId);

    /** 联表读取 SUSPENDED Resume 投影，避免服务层拼接多个可能漂移的快照。 */
    PersistenceRecords.ResumeTurnRow selectResumeTurn(@Param("turnId") String turnId);

    /** 只允许同 Thread 最早非终态 Turn 从 SUSPENDED 回到 QUEUED。 */
    int resumeSuspendedTurn(PersistenceRecords.ResumeTurnCas values);

    /** SUSPENDED Turn 无内存 owner，取消直接进入终态并删除 execution。 */
    int cancelSuspendedTurn(PersistenceRecords.ResumeTurnCas values);

    /** Plan pause 的取消收口保留 execution cursor，并清除取消 fence 供显式 Resume 使用。 */
    int suspendCancelledTurn(PersistenceRecords.ResumeTurnCas values);

    /** 仅判断指定 Thread 是否存在 SUSPENDED head，供新执行准入 fail fast。 */
    int countSuspendedTurns(@Param("threadId") String threadId);

    /** 每次非终态 CAS 都整体替换游标，不提供字段级 patch。 */
    int replaceTurnExecution(PersistenceRecords.TurnExecutionWrite values);

    /** 终态事务删除唯一执行游标，历史 Turn 不再被误判为可恢复。 */
    int deleteTurnExecution(@Param("turnId") String turnId);

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

    /** 只取 admission 对应的可见 USER_INPUT，排除同 Turn 内先注入的继承 USER 历史。 */
    PersistenceRecords.MessageRow selectAdmissionUserMessage(@Param("threadId") String threadId,
                                                             @Param("turnId") String turnId);

    /**
     * 按 Turn 与调用身份读取 Tool 状态，供状态迁移校验。
     */
    PersistenceRecords.ToolRow selectTool(PersistenceRecords.ToolKey values);

    /**
     * 插入 PREPARED Tool 事实，调用序号和身份必须同时唯一。
     */
    int insertTool(PersistenceRecords.ToolInsert values);

    /** 与 PREPARED Tool 同事务插入精确路由绑定；数据库触发器禁止后续修改。 */
    int insertToolBinding(PersistenceRecords.ToolBindingInsert values);

    /** 恢复按 Turn/callId 复合身份读取唯一绑定。 */
    PersistenceRecords.ToolBindingRow selectToolBinding(PersistenceRecords.ToolKey values);

    /**
     * 仅允许 PREPARED Tool 进入 RUNNING，并在同一语句把安全展示切为 running；
     * 受影响行数同时是执行边界与 snapshot 投影的状态门结果。
     */
    int startTool(PersistenceRecords.ToolStart values);

    /**
     * 仅完成尚未终结的 Tool，避免重复结果覆盖首个事实。
     */
    int finishTool(PersistenceRecords.ToolFinish values);

    /** 审批等待或恢复时原子更新安全展示状态，内部 Tool state 仍由执行事实推进。 */
    int updateToolPresentation(PersistenceRecords.ToolPresentationUpdate values);

    /** 决策提交时把 Tool 安全投影切回运行态。 */
    int markApprovalToolRunning(PersistenceRecords.ToolApprovalStatusUpdate values);

    /** 统计仍未完成的 Tool，成功终态必须以此证明不存在悬空执行。 */
    int countUnfinishedTools(@Param("turnId") String turnId);

    /** 取消挂起交互前按原顺序补齐模型 ToolResult，不允许悬空调用污染下一轮上下文。 */
    List<String> selectUnfinishedToolCallIds(@Param("turnId") String turnId);

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

    /** 按 Tool 调用读取最近审批，恢复必须复用该 ID。 */
    PersistenceRecords.ToolApprovalRow selectToolApproval(PersistenceRecords.ToolKey values);

    /** 读取外部响应所属 Turn 的状态门和 CAS 版本。 */
    PersistenceRecords.ApprovalDecisionRow selectApprovalDecision(@Param("approvalId") String approvalId);

    /**
     * 插入待决审批及到期时间，唯一约束阻止重复请求。
     */
    int insertApproval(PersistenceRecords.ApprovalInsert values);

    /**
     * 仅在待决且未过期时写入审批决定，过期或竞争返回零行。
     */
    int resolveApproval(PersistenceRecords.ApprovalResolve values);

    /** 终态事务本地拒绝所有未决审批，迟到响应不能唤醒工作。 */
    int closePendingApprovals(@Param("turnId") String turnId, @Param("occurredAt") String occurredAt);

    /**
     * 插入模型轮次用量，唯一键保证重试不会重复计量。
     */
    int insertUsage(PersistenceRecords.UsageInsert values);

    /** 只允许匹配同一请求身份、用途和 Profile 的 UNKNOWN 行升级。 */
    int settleUsage(PersistenceRecords.UsageSettlement values);

    /**
     * 仅按 Turn 统计 committed usage，供事务验收与诊断确认不存在部分提交。
     */
    int countUsageForTurn(@Param("turnId") String turnId);

    /** 插入一条独立 FIFO 输入，队列写入不推进 Turn revision。 */
    int insertPendingInput(PersistenceRecords.PendingInputInsert values);

    /** 按显式持久插入序列读取指定类型的首条待消费输入，时钟碰撞或回拨不能改变 FIFO。 */
    PersistenceRecords.PendingInputRow selectPendingInput(PersistenceRecords.PendingInputQuery values);

    /** 按真实消费顺序读取完整待处理队列，供 ACK、事件和 thread/read 共用。 */
    List<PersistenceRecords.PendingInputRow> selectPendingInputs(@Param("turnId") String turnId);

    /** 绑定 Turn 读取单条待处理输入，编辑和删除不能跨 Turn 命中。 */
    PersistenceRecords.PendingInputRow selectPendingInputById(PersistenceRecords.PendingInputKey values);

    /** 统计数量与 UTF-8 字节预算，所有容量判断必须发生在同一 SQLite 写事务。 */
    PersistenceRecords.PendingInputStats selectPendingInputStats(@Param("turnId") String turnId);

    /** 分配下一条点击优先序；已有 Steering 与后续点击共享一个单调空间。 */
    Long selectNextInputPriority(@Param("turnId") String turnId);

    /** 把普通输入提升为 Steering，并以条目 revision 作为唯一 CAS。 */
    int prioritizePendingInput(PersistenceRecords.PendingInputMutation values);

    /** 编辑正文只推进条目 revision，不改变 FIFO 或点击优先序。 */
    int updatePendingInput(PersistenceRecords.PendingInputMutation values);

    /** 只把精确 revision 的待消费条目标为 needs_attention，并推进条目 revision。 */
    int markPendingInputNeedsAttention(PersistenceRecords.PendingInputAttention values);

    /** 删除尚未消费的条目并记录统一解决时间。 */
    int deletePendingInput(PersistenceRecords.PendingInputMutation values);

    /** 只允许首个消费者把 PENDING 改为 CONSUMED。 */
    int consumePendingInput(PersistenceRecords.PendingInputConsume values);

    /** Turn 取消时一次性取消所有尚未消费的输入。 */
    int cancelPendingInputs(PersistenceRecords.PendingInputCancel values);

    /** 每次有效队列变化推进一次 queue revision。 */
    int advanceInputQueue(PersistenceRecords.InputQueueAdvance values);

    /** STOP 空队列原子关闭输入门，阻止检查后再次入队。 */
    int closeInputQueue(PersistenceRecords.InputQueueAdvance values);

    /** 输入消费只推进 mutation version 和更新时间，不改变当前运行状态。 */
    int advanceInputConsumption(PersistenceRecords.InputAdvance values);
}
