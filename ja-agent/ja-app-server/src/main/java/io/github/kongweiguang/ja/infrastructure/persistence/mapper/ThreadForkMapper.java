// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 持久 Thread fork 的独立 snapshot 和 operation receipt Mapper。
 */
@Mapper
public interface ThreadForkMapper {
    /**
     * 按独立 fork operation scope 回读精确响应。
     */
    ThreadForkRecords.OperationRow selectOperation(@Param("operationKey") String operationKey);

    /**
     * 创建 Thread 与冻结 seed 同事务写入操作回执。
     */
    int insertOperation(ThreadForkRecords.OperationInsert values);

    /**
     * 按 child 身份读取首轮上下文快照。
     */
    ThreadForkRecords.SeedRow selectSeed(@Param("threadId") String threadId);

    /**
     * 新 Thread 创建事务内写入有界、类型化上下文快照。
     */
    int insertSeed(ThreadForkRecords.SeedInsert values);

    /**
     * child 附件副本与来源元数据绑定同一冻结快照顺序。
     */
    int insertAttachment(ThreadForkRecords.AttachmentInsert values);

    /** 已继承历史按目标本地稳定序号分页，原来源时间戳不决定前缀顺序。 */
    java.util.List<ThreadForkRecords.HistoryItemRow> selectHistoryItems(
            @Param("threadId") String threadId,
            @Param("afterOrdinal") Long afterOrdinal,
            @Param("beforeOrdinal") Long beforeOrdinal,
            @Param("limit") int limit,
            @Param("descending") boolean descending);

    /** 精确定位继承的公开 item，fork 只接受其中结算的 FINAL_ANSWER。 */
    ThreadForkRecords.HistoryItemRow selectHistoryItem(@Param("threadId") String threadId,
                                                       @Param("itemId") String itemId);

    /** 读取冻结上下文中的一条 keyset 位置，用于辨别 inherited/native 游标。 */
    ThreadForkRecords.HistoryItemRow selectHistoryCursor(@Param("threadId") String threadId,
                                                         @Param("itemId") String itemId);

    /** 已继承的模型上下文始终由目标 Thread 所有，按稳定 ordinal 有界读取。 */
    java.util.List<ThreadForkRecords.ContextMessageRow> selectContextMessages(
            @Param("threadId") String threadId,
            @Param("throughOrdinal") Long throughOrdinal);

    /** 冻结一条公开历史项；调用方逐页写入，避免整段历史常驻内存。 */
    int insertHistoryItem(ThreadForkRecords.HistoryItemInsert values);

    /** 冻结一条 provider-neutral ContextMessage。 */
    int insertContextMessage(ThreadForkRecords.ContextMessageInsert values);

    /** 冻结前缀可见附件同 seed 引用合并去重；只读来源授权元数据。 */
    java.util.List<String> selectHistoryAttachmentIds(@Param("threadId") String threadId);

    /**
     * 在写入 child 前取得 SQLite 写锁并再次核对来源 revision，缩小冻结与创建之间的竞态窗口。
     */
    int claimSourceRevision(@Param("threadId") String threadId,
                            @Param("expectedRevision") long expectedRevision);
}
