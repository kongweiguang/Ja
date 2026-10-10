// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Ja 展示与回执查询；执行状态写入由 AgentStore 同事务参与者统一负责。
 */
@Mapper
public interface AgentMapper {
    /**
     * 按持久化顺序读取 Thread 下全部 Turn，供权威快照组装。
     */
    List<PersistenceRecords.TurnRow> selectTurns(@Param("threadId") String threadId);

    /**
     * 最新页只投影页面所属、最新和队列头 Turn，避免长会话恢复时返回超出协议上限的全量元数据。
     */
    List<PersistenceRecords.TurnRow> selectSnapshotTurns(@Param("threadId") String threadId,
                                                         @Param("turnIds") List<String> turnIds,
                                                         @Param("hasTurnIds") boolean hasTurnIds);

    /**
     * PK 读取提交回执，重复请求不能依赖进程内生命周期判断是否已执行。
     */
    PersistenceRecords.ClientOperationRow selectClientOperation(@Param("operationKey") String operationKey);

    /**
     * 读取与一次入队同事务写入的身份，响应丢失时只查询不重发。
     */
    PersistenceRecords.InputOperationRow selectInputOperation(@Param("operationKey") String operationKey);

    /**
     * 按 V1 基线保证的全局 Turn 身份读取取消目标。
     */
    PersistenceRecords.TurnRow selectTurnById(@Param("turnId") String turnId);

    /**
     * 当前 admission 已插入 Turn 后，以存在性判断是否已有其它真实 Turn，避免历史累计扫描。
     */
    boolean hasOtherTurns(@Param("threadId") String threadId, @Param("turnId") String turnId);


    /**
     * 在同一事务以展示消息的精确 Core 引用读取准入正文，业务 repository 不自行执行 SQL 或复制正文。
     */
    PersistenceRecords.AdmissionUserRecordRow selectAdmissionUserMessage(@Param("threadId") String threadId,
                                                                        @Param("turnId") String turnId,
                                                                        @Param("ownerUserId") String ownerUserId);

    /**
     * 以 thread+turn+call+artifact 四元身份读取，防止枚举 artifactId 越权。
     */
    PersistenceRecords.ToolArtifactRow selectToolArtifact(PersistenceRecords.ToolArtifactKey values);

    /**
     * 仅按 Turn 统计 committed usage，供事务验收与诊断确认不存在部分提交。
     */
    int countUsageForTurn(@Param("turnId") String turnId);

    /**
     * 按真实消费顺序读取完整待处理队列，供 ACK、事件和 thread/read 共用。
     */
    List<PersistenceRecords.PendingInputRow> selectPendingInputs(@Param("turnId") String turnId);

}
