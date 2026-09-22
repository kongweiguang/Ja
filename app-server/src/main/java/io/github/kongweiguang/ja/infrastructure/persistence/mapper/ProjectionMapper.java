// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 稳定上下文投影阶段与选择的窄 Mapper；正文仍只存在于既有消息历史。 */
@Mapper
public interface ProjectionMapper {
    /** 读取 Thread 最新阶段，用于模型或 Tool binding 变化的单向切换。 */
    PersistenceRecords.ProjectionStageRow selectLatestProjectionStage(@Param("threadId") String threadId);

    /** 用不可变阶段条件重读已有阶段，避免恢复后重复分配同一 checkpoint/overflow 边界。 */
    PersistenceRecords.ProjectionStageRow selectProjectionStageIdentity(
            PersistenceRecords.ProjectionStageIdentity values);

    /** 读取已经提交的 Tool 结果选择，调用方不得从年龄或内容重新猜测。 */
    List<PersistenceRecords.ProjectionEntryRow> selectProjectionEntries(@Param("stageId") String stageId);

    /** 只分配当前 Thread 的下一个连续阶段号；事务 owner 负责并发失败转换。 */
    Long selectNextProjectionStageNumber(@Param("threadId") String threadId);

    /** 追加阶段边界，阶段本身不保存 Prompt、凭据或 Tool 正文。 */
    int insertProjectionStage(PersistenceRecords.ProjectionStageInsert values);

    /** 首次选择 append-only；相同 message/stage 不覆盖此前已发送的投影。 */
    int insertProjectionEntryIgnore(PersistenceRecords.ProjectionEntryInsert values);
}
