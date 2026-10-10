// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.TurnCommand;

/** 固定查询某个 Turn 的待裁决工具恢复项。
 *
 * @param threadId 恢复项所属的 Thread 标识
 * @param turnId 需要查询恢复项的原始 Turn 标识
 */
public record RecoveryQuery(String threadId,
                            String turnId) {
    /** 在 HTTP 边界校验 Thread/Turn 标识结构。 */
    public RecoveryQuery {
        new TurnCommand(threadId, turnId);
    }
}
