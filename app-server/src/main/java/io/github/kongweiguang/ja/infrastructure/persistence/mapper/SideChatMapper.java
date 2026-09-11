// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 临时侧聊标记、子树关闭闸门与启动孤儿清理的独立 SQL 边界。 */
@Mapper
public interface SideChatMapper {
    /** 新侧聊与 Thread 元数据必须在同一个写事务中登记，避免出现无标记的临时会话。 */
    int insertOpen(@Param("threadId") String threadId);

    /** 读取单个临时侧聊标记，OPEN/CLOSING 是唯一持久状态。 */
    TaskRecords.SideChatMarkerRow selectMarker(@Param("threadId") String threadId);

    /** 只返回仍有标记的临时侧聊，供服务端协调取消和恢复。 */
    List<TaskRecords.SideChatMarkerRow> selectMarkers();

    /** 沿 lineage 有界展开目标临时侧聊的完整子树，结果顺序稳定且不物化正文。 */
    List<String> selectSubtreeThreadIds(@Param("threadId") String threadId);

    /** 关闭前在一个 writer transaction 内把子树中所有临时标记切到 CLOSING。 */
    int markSubtreeClosing(@Param("threadId") String threadId);

    /** admission 只需判断目标及其临时侧聊祖先是否已进入 CLOSING。 */
    String selectClosingOwner(@Param("threadId") String threadId);

    /** 启动只删除没有对应 Thread 的孤儿标记，不触碰任何旧侧聊或其正文。 */
    int deleteOrphanMarkers();

    /** 关闭协调完成后只删除已切到 CLOSING 的根标记，重复清理保持幂等。 */
    int deleteClosedMarker(@Param("threadId") String threadId);
}
