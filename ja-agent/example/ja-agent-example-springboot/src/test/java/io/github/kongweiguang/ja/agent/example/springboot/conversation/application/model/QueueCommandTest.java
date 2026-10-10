// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定 HTTP 队列边界与 Core 枚举解析之间的职责划分。 */
final class QueueCommandTest {
    /** ENQUEUE 的类型缺失或空白必须在 DTO 边界变为可修正的参数错误。 */
    @Test
    void enqueueRequiresNonblankKind() {
        assertThrows(IllegalArgumentException.class, () -> enqueue(null));
        assertThrows(IllegalArgumentException.class, () -> enqueue("   "));
    }

    /** DTO 只要求有类型；枚举闭集仍由 ChatService 的原有 valueOf 边界负责。 */
    @Test
    void leavesUnknownNonblankKindForServiceValidation() {
        QueueCommand command = enqueue("UNRECOGNIZED");

        assertEquals("UNRECOGNIZED", command.kind());
        assertThrows(IllegalArgumentException.class, () -> AgentInputCommand.Kind.valueOf(command.kind()));
    }

    /** 构造仅包含有效身份和正文，避免测试将无关输入完整性与 kind 约束混淆。 */
    private static QueueCommand enqueue(String kind) {
        return new QueueCommand("thr_queue_test", "turn_queue_test", "op_queue_test", "ENQUEUE",
                "input_queue_test", kind, 0, new InputContent("queued text", List.of(), List.of()));
    }
}
