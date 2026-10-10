// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;

import java.util.List;

/**
 * 等待视图只携带回答所需字段与交互分类，策略内部状态不进入响应。
 *
 * @param interactionId 交互标识
 * @param interactionRevision 交互修订版本
 * @param status 当前状态分类
 * @param kind 等待类型
 * @param questions 问题列表
 */
public record WaitView(String interactionId,
                       long interactionRevision,
                       InteractionStatus status,
                       String kind,
                       List<InteractionQuestion> questions) {
    /**
     * 固定记录快照保持不可变，Core 会再次核对最新修订号。
     */
    public WaitView {
        questions = List.copyOf(questions);
    }
}
