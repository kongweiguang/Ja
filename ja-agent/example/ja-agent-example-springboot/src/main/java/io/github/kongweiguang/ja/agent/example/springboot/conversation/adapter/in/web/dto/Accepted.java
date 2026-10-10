// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnIdentity;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

/**
 * 返回后续观察需要的稳定身份，受理不表示任务完成。
 *
 * @param identity 对话与执行的稳定身份
 */
public record Accepted(TurnIdentity identity) {
    /**
     * 对外只返回 Ja 业务身份，Core 命名保持在服务适配内部。
     */
    public static Accepted from(AgentIdentity identity) {
        return new Accepted(TurnIdentity.from(identity));
    }
}
