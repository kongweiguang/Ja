// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.conversation;

import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定输入命令顺序和安全点选择规则，防止优先级调整破坏单项版本。 */
final class AgentInputQueueTest {
    private final Instant now = Instant.parse("2026-09-26T02:00:00Z");

    /** 恢复重放保留每次revision，且Follow-up只在模型停止边界可消费。 */
    @Test
    void commandReducerPreservesOrderAndSafePointSemantics() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "input_1",
                        AgentInputQueue.Kind.FOLLOW_UP, 0, 1, "record_1"));
        assertEquals(1, queue.revision());
        assertEquals(1, queue.entries().getFirst().inputRevision());
        assertEquals(false, queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED).isPresent());
        assertEquals("input_1", queue.next(AgentInputQueue.SafePoint.MODEL_STOPPED).orElseThrow().inputId());

        queue = queue.apply(command("cmd_2", AgentInputQueue.Action.ENQUEUE, "input_2",
                AgentInputQueue.Kind.STEERING, 0, 2, "record_2"));
        assertEquals("input_2", queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED).orElseThrow().inputId());
        queue = queue.apply(command("cmd_3", AgentInputQueue.Action.PRIORITIZE, "input_1", null, 1, 3, null));
        assertEquals(2, queue.entries().stream().filter(entry -> entry.inputId().equals("input_1"))
                .findFirst().orElseThrow().inputRevision());
        assertEquals("input_2", queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED).orElseThrow().inputId());
        queue = queue.apply(command("cmd_4", AgentInputQueue.Action.CONSUME, "input_2", null, 1, 4, null));
        assertEquals("input_1", queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED).orElseThrow().inputId());
    }

    /** PRIORITIZE 将 Follow-up 提升为 Steering，并按点击顺序排在已有优先输入之后。 */
    @Test
    void prioritizedFollowUpsKeepClickOrder() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "first", AgentInputQueue.Kind.FOLLOW_UP,
                        0, 1, "record_1"))
                .apply(command("cmd_2", AgentInputQueue.Action.ENQUEUE, "second", AgentInputQueue.Kind.FOLLOW_UP,
                        0, 2, "record_2"))
                .apply(command("cmd_3", AgentInputQueue.Action.ENQUEUE, "third", AgentInputQueue.Kind.FOLLOW_UP,
                        0, 3, "record_3"))
                .apply(command("cmd_4", AgentInputQueue.Action.PRIORITIZE, "first", null, 1, 4, null))
                .apply(command("cmd_5", AgentInputQueue.Action.PRIORITIZE, "second", null, 1, 5, null));

        assertEquals(List.of("first", "second", "third"),
                queue.entries().stream().map(AgentInputQueue.Entry::inputId).toList());
        assertEquals(AgentInputQueue.Kind.STEERING, queue.entries().get(0).kind());
        assertEquals(AgentInputQueue.Kind.STEERING, queue.entries().get(1).kind());
        assertEquals("first", queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED).orElseThrow().inputId());
    }

    /** 宿主 MESSAGE 在所有模型安全点可消费，并与 STEERING 按入队顺序保持先后。 */
    @Test
    void messageUsesNextSafePointAndKeepsImmediateQueueOrder() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "message_1",
                        AgentInputQueue.Kind.MESSAGE, 0, 1, "record_1"))
                .apply(command("cmd_2", AgentInputQueue.Action.ENQUEUE, "steering_2",
                        AgentInputQueue.Kind.STEERING, 0, 2, "record_2"));

        assertEquals("message_1", queue.next(AgentInputQueue.SafePoint.TOOL_BATCH_COMPLETED)
                .orElseThrow().inputId());
        queue = queue.apply(command("cmd_3", AgentInputQueue.Action.CONSUME, "message_1", null, 1, 3, null));
        assertEquals("steering_2", queue.next(AgentInputQueue.SafePoint.MODEL_COMPLETED)
                .orElseThrow().inputId());
    }

    /** 模型停止时较早的 attention Follow-up 必须挂起消费，不能被后续 Steering 越过。 */
    @Test
    void attentionFollowUpBlocksLaterSteeringAtModelStop() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "blocked_follow_up",
                        AgentInputQueue.Kind.FOLLOW_UP, 0, 1, "record_1"))
                .apply(attentionCommand("cmd_2", "blocked_follow_up", 1, 2))
                .apply(command("cmd_3", AgentInputQueue.Action.ENQUEUE, "later_steering",
                        AgentInputQueue.Kind.STEERING, 0, 3, "record_2"));

        assertEquals("blocked_follow_up", queue.next(AgentInputQueue.SafePoint.MODEL_STOPPED)
                .orElseThrow().inputId());
        assertTrue(queue.requiresAttentionAt(AgentInputQueue.SafePoint.MODEL_STOPPED));
        assertEquals(List.of("blocked_follow_up", "later_steering"),
                queue.entries().stream().map(AgentInputQueue.Entry::inputId).toList());
    }

    /** MESSAGE 的来源与顺序不可被用户优先级操作改写成技能替换型 Steering。 */
    @Test
    void messageCannotBePrioritized() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "message_1",
                        AgentInputQueue.Kind.MESSAGE, 0, 1, "record_1"));

        assertThrows(IllegalStateException.class, () -> queue.apply(command("cmd_2",
                AgentInputQueue.Action.PRIORITIZE, "message_1", null, 1, 2, null)));
        assertEquals(1, queue.revision());
        assertEquals(AgentInputQueue.Kind.MESSAGE, queue.entries().getFirst().kind());
    }

    /** 消息来源不拥有技能替换权，避免正文存档与当前用户技能选择产生双重语义。 */
    @Test
    void messageEnqueueRejectsSkillSelection() {
        UserContent content = new UserContent(List.of(new SkillReferenceContent("user:skill"),
                new TextContent("message body")));

        assertThrows(IllegalArgumentException.class, () -> new AgentInputCommand.Enqueue(
                "operation_1", "input_1", AgentInputCommand.Kind.MESSAGE, content, java.util.Map.of()));
    }

    /** 超过容量时抛出专用类型，宿主可稳定映射而不解析冲突错误文本。 */
    @Test
    void fullQueueReportsTypedCapacityFailure() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of());
        for (int index = 1; index <= AgentInputQueue.MAX_ENTRIES; index++) {
            queue = queue.apply(command("cmd_" + index, AgentInputQueue.Action.ENQUEUE,
                    "input_" + index, AgentInputQueue.Kind.MESSAGE, 0, index, "record_" + index));
        }

        AgentInputQueue full = queue;
        assertThrows(AgentInputQueue.QueueFullException.class, () -> full.apply(command("cmd_overflow",
                AgentInputQueue.Action.ENQUEUE, "input_overflow", AgentInputQueue.Kind.MESSAGE,
                0, AgentInputQueue.MAX_ENTRIES + 1, "record_overflow")));
    }

    /** 陈旧同项 revision 拒绝，且不能推进队列修订或改变 FIFO 内容。 */
    @Test
    void staleItemRevisionDoesNotChangeQueue() {
        AgentInputQueue queue = new AgentInputQueue("turn_1", 0, List.of())
                .apply(command("cmd_1", AgentInputQueue.Action.ENQUEUE, "input_1",
                        AgentInputQueue.Kind.STEERING, 0, 1, "record_1"));
        assertThrows(IllegalStateException.class, () -> queue.apply(command("cmd_2",
                AgentInputQueue.Action.DELETE, "input_1", null, 2, 2, null)));
        assertEquals(1, queue.revision());
        assertEquals(1, queue.entries().size());
    }

    /** 构造精确命令，测试不绕过 record 自身的字段一致性约束。 */
    private AgentInputQueue.Command command(String id, AgentInputQueue.Action action, String inputId,
                                             AgentInputQueue.Kind kind, long expectedInputRevision,
                                             long queueRevision, String messageRecordId) {
        return new AgentInputQueue.Command(id, action, inputId, kind, expectedInputRevision,
                queueRevision, messageRecordId, null, now, java.util.Map.of());
    }

    /** 为修复边界提供合法的 attention 状态，确保最终候选仍可由消费端决定挂起。 */
    private AgentInputQueue.Command attentionCommand(String id, String inputId,
                                                      long expectedInputRevision, long queueRevision) {
        return new AgentInputQueue.Command(id, AgentInputQueue.Action.NEEDS_ATTENTION, inputId, null,
                expectedInputRevision, queueRevision, null,
                new AgentInputQueue.Issue("ATTACHMENT_UNAVAILABLE", "请修正附件后重试", true),
                now, java.util.Map.of());
    }
}
