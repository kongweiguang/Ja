// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.catalog.port.in.ThreadMcpUseCase;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.out.McpGateway;


import org.junit.jupiter.api.Test;

/** 固定会话 MCP 观测的状态边界，避免排队 Turn 借用旧目录。 */
final class ThreadMcpServicePolicyTest {
    private static final String SERVER_ID = "mcp_sample";

    /** 新排队 Turn 不得把旧 Provider 观测继承为本轮可用状态。 */
    @Test
    void activeSourceRequiresCurrentTurnIdentityAndActiveStatus() {
        assertTrue(ThreadMcpService.isActiveObservation(TurnState.RUNNING, "turn_current", "turn_current"));
        assertTrue(ThreadMcpService.isActiveObservation(
                TurnState.WAITING_APPROVAL, "turn_current", "turn_current"));
        assertFalse(ThreadMcpService.isActiveObservation(TurnState.RUNNING, "turn_new", "turn_previous"));
        assertFalse(ThreadMcpService.isActiveObservation(TurnState.QUEUED, "turn_current", "turn_current"));
    }

    /** 服务名称沿用配置契约，包括多行名称和完整 512 字符上限。 */
    @Test
    void serverNameMatchesConfigurationLengthAndTextRules() {
        String multiline = "x".repeat(510) + "\r\n";
        assertEquals(multiline, new ThreadMcpUseCase.Server(SERVER_ID, multiline, ThreadMcpUseCase.Scope.GLOBAL,
                ThreadMcpUseCase.State.AVAILABLE, 0, null).name());
        assertThrows(IllegalArgumentException.class, () -> new ThreadMcpUseCase.Server(SERVER_ID,
                "x".repeat(513), ThreadMcpUseCase.Scope.GLOBAL, ThreadMcpUseCase.State.AVAILABLE, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new McpGateway.McpServerStatus(
                SERVER_ID, "bad\0name", "available", 0, null));
        assertEquals(multiline, new McpGateway.McpServerStatus(SERVER_ID, multiline, "available", 0, null)
                .name());
    }

}
