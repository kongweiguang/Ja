// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import java.util.List;

/**
 * 仅投影公开文本与调用名称，不序列化供应商原生推理块。
 *
 * @param modelRequestId 一次真实模型请求的标识
 * @param text 文本内容
 * @param reasoning 推理
 * @param calls 工具调用列表
 */
public record MessageView(String modelRequestId,
                          String text,
                          String reasoning,
                          List<Call> calls) {
    /**
     * 响应序列化期间不允许外部修改调用列表。
     */
    public MessageView {
        calls = List.copyOf(calls);
    }

    /**
     * 稳定 callId 关联工具结果，不暴露内部执行绑定。
     *
     * @param callId 模型生成的工具调用标识
     * @param name 名称
     */
    public record Call(String callId,
                       String name) {
    }
}
