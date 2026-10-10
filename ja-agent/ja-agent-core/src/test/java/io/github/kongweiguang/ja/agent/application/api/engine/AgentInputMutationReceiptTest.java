// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** 输入回执沿用命令的身份规则，避免公开 DTO 表达不可执行的队列条目。 */
final class AgentInputMutationReceiptTest {
    /** 回执身份与命令输入键共享同一标识边界，调用方才能安全关联 ACK 与原请求。 */
    @Test
    void rejectsInputIdsOutsideMutationCommandContract() {
        assertThrows(IllegalArgumentException.class, () -> new AgentInputMutationReceipt("input invalid", 1));
    }
}
