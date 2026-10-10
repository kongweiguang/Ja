// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId;

import java.util.List;
import java.util.Objects;

/**
 * 读取与单次 ModelCompleted 事务绑定的 Ja 安全展示提案，不把提案误作工具准入事实。
 */
public interface ToolCallPreviewReadPort {
    /**
     * 一次读取必须是完整批次；重复 callId 或序号会破坏 Provider 原生调用顺序。
     */
    static List<ToolCallPreview> requireCompleteBatch(List<ToolCallPreview> values) {
        List<ToolCallPreview> previews = List.copyOf(Objects.requireNonNull(values, "previews"));
        if (previews.size() > 1_024
            || previews.stream().map(ToolCallPreview::callId).distinct().count() != previews.size()
            || previews.stream().map(ToolCallPreview::ordinal).distinct().count() != previews.size()) {
            throw new IllegalStateException("invalid tool call preview batch");
        }
        for (int index = 0; index < previews.size(); index++) {
            if (previews.get(index).ordinal() != index) {
                throw new IllegalStateException("tool call preview order is incomplete");
            }
        }
        return previews;
    }

    /**
     * 以执行、Assistant 记录及持久事件三重身份读取提案；调用方传入的事件序号是重连水位的一部分，
     * 实现必须只返回同一原子提交内保存的完整有序批次，不能查询“最新”或补读后续 Tool 状态。
     */
    List<ToolCallPreview> read(AgentStore.ExecutionKey execution, String assistantMessageRecordId,
                               String eventId, long eventSequence);

    /**
     * 单个原生 Tool call 的非授权展示事实及其来源 watermark。
     *
     * @param callId 模型生成的工具调用标识
     * @param ordinal 当前项的顺序位置
     * @param toolName 工具名称
     * @param presentationJson 展示信息 JSON 表示
     * @param sourceEventId 来源事件标识
     * @param sourceEventSequence 来源事件序号
     */
    record ToolCallPreview(String callId,
                           int ordinal,
                           String toolName,
                           String presentationJson,
                           String sourceEventId,
                           long sourceEventSequence) {
        /**
         * 校验展示身份和顺序，并保留来源事件水位供桥接层再次核对。
         */
        public ToolCallPreview {
            callId = JaToolCallId.require(callId);
            if (ordinal < 0 || ordinal > 1_023) {
                throw new IllegalArgumentException("invalid tool call ordinal");
            }
            toolName = ContractChecks.identifier(toolName, "toolName");
            presentationJson = ContractChecks.text(presentationJson, "presentationJson", 65_536, false);
            sourceEventId = ContractChecks.identifier(sourceEventId, "sourceEventId");
            if (sourceEventSequence < 1) {
                throw new IllegalArgumentException("invalid tool call preview event sequence");
            }
        }
    }
}
