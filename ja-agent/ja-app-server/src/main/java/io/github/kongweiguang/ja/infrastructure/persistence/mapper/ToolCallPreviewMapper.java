// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 只读已提交模型调用提案；写入由 Store participant 使用同一 JDBC 事务完成。
 */
@Mapper
public interface ToolCallPreviewMapper {
    /**
     * 查询一个精确 ModelCompleted 来源下的完整提案批次，且不读取后续工具准备状态。
     */
    List<ToolCallPreviewRow> selectBatch(ToolCallPreviewKey key);

    /**
     * 插入不含原始参数的模型提案行；主键冲突必须使对应 Store 提交整体回滚。
     */
    int insert(ToolCallPreviewInsert value);

    /**
     * 精确限定 Thread、Execution、Assistant 记录与事件水位的来源键。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param assistantMessageRecordId 助手消息记录标识
     * @param eventId 事件标识
     * @param throughEventSequence 当前快照覆盖到的事件序号
     */
    record ToolCallPreviewKey(String threadId,
                              String turnId,
                              String assistantMessageRecordId,
                              String eventId,
                              long throughEventSequence) {
    }

    /**
     * MyBatis 只读行映射显式列出来源与安全展示，不暴露通用 Map。
     *
     * @param callId 模型生成的工具调用标识
     * @param ordinal 当前项的顺序位置
     * @param toolName 工具名称
     * @param presentationJson 展示信息 JSON 表示
     * @param sourceEventId 来源事件标识
     * @param sourceEventSequence 来源事件序号
     */
    record ToolCallPreviewRow(String callId,
                              int ordinal,
                              String toolName,
                              String presentationJson,
                              String sourceEventId,
                              long sourceEventSequence) {
    }

    /**
     * 同事务写入值保留完整复合身份和数据库分配前的持久事件序号。
     *
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param assistantMessageRecordId 助手消息记录标识
     * @param eventId 事件标识
     * @param eventSequence 事件序号
     * @param callId 模型生成的工具调用标识
     * @param ordinal 当前项的顺序位置
     * @param toolName 工具名称
     * @param presentationJson 展示信息 JSON 表示
     * @param createdAt 创建时间
     */
    record ToolCallPreviewInsert(String threadId,
                                 String turnId,
                                 String assistantMessageRecordId,
                                 String eventId,
                                 long eventSequence,
                                 String callId,
                                 int ordinal,
                                 String toolName,
                                 String presentationJson,
                                 String createdAt) {
    }
}
