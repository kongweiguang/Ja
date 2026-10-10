// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.service;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskMailboxQueueInputSource;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;


import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 idle admission 只读取有限 FIFO 消息，并保留每条消息的稳定 Core 与 Mailbox 身份。 */
final class TaskMailboxQueueInputSourceTest {
    private static final JsonValueCodec JSON = new JacksonJsonValueCodec(new ObjectMapper());
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    /** 超过单次 Core 容量的余量留在 Mailbox，返回项仍与活动投递路径使用同一个 mapper。 */
    @Test
    void loadsOnlyRequestedFifoBatchWithoutClaimingRows() {
        List<TaskModels.MailboxMessage> pending = List.of(
                pending(4, "msg_first", "first", "阶段一"),
                pending(5, "msg_second", "second", "阶段二"));
        AtomicInteger requestedLimit = new AtomicInteger();
        TaskMailboxPort port = port((threadId, limit) -> {
            assertEquals("thr_target", threadId);
            requestedLimit.set(limit);
            return pending;
        });
        TaskMailboxQueueInputSource source = new TaskMailboxQueueInputSource(port, JSON);

        List<AgentInputCommand.Enqueue> actual = source.loadPendingInputs("thr_target", 12);

        assertEquals(TaskMailboxPort.MAX_QUEUE_BATCH, requestedLimit.get());
        assertEquals(pending.stream().map(message -> TaskMailboxQueueCommandMapper.toQueueCommand(message, JSON)).toList(), actual);
        assertEquals(List.of("msg_first", "msg_second"), pending.stream().map(
                TaskModels.MailboxMessage::messageId).toList());
    }

    /** Repository 返回超过限额的行表示读端违约，必须失败关闭而不能静默截断。 */
    @Test
    void rejectsMailboxSourceThatExceedsBoundedRead() {
        List<TaskModels.MailboxMessage> overLimit = java.util.stream.IntStream.range(0,
                TaskMailboxPort.MAX_QUEUE_BATCH + 1).mapToObj(index -> pending(
                index + 1, "msg_item_" + index, "item-" + index, "进度 " + index)).toList();
        TaskMailboxQueueInputSource source = new TaskMailboxQueueInputSource(port((threadId, limit) -> overLimit), JSON);

        assertThrows(IllegalStateException.class, () -> source.loadPendingInputs("thr_target", 1));
    }

    /** 错目标投递行属于仓储边界损坏，不能进入另一个会话的初始模型上下文。 */
    @Test
    void rejectsMailboxRowsForAnotherThread() {
        TaskMailboxQueueInputSource source = new TaskMailboxQueueInputSource(
                port((threadId, limit) -> List.of(new TaskModels.MailboxMessage(1, "msg_wrong_target",
                        "thr_sender", "thr_other", "发送方", "thr_sender", "turn_sender",
                        TaskModels.MailboxKind.MESSAGE,
                        new UserContent(List.of(new TextContent("不应串会话"))), "wrong-target-key",
                        TaskModels.MailboxState.PENDING, null, NOW, NOW, null))), JSON);

        assertThrows(IllegalStateException.class, () -> source.loadPendingInputs("thr_target", 8));
    }

    /** 只提供此用例实际需要的只读查询，任何意外状态变更都会立刻暴露。 */
    private static TaskMailboxPort port(PendingLookup lookup) {
        return new TaskMailboxPort() {
            /** 这些用例不测试幂等查询，未命中结果明确为空。 */
            @Override public Optional<TaskModels.MailboxMessage> findByIdempotency(
                    String senderThreadId, String operationKey) {
                return Optional.empty();
            }

            /** 测试观察唯一读取入口，确保 source 不要求独立 claim 或写入行为。 */
            @Override public List<TaskModels.MailboxMessage> listPending(String targetThreadId, int limit) {
                return lookup.find(targetThreadId, limit);
            }
        };
    }

    /** 构造带完整投递 provenance 的 PENDING MESSAGE，确保 mapper 使用持久身份。 */
    private static TaskModels.MailboxMessage pending(long sequence, String messageId, String key, String content) {
        return new TaskModels.MailboxMessage(sequence, messageId, "thr_root", "thr_sender", "发送方",
                "thr_target", "turn_sender", TaskModels.MailboxKind.MESSAGE,
                new UserContent(List.of(new TextContent(content))), key,
                TaskModels.MailboxState.PENDING, null, NOW, NOW, null);
    }

    /** 小型查询夹具保留目标与批次参数供断言。 */
    @FunctionalInterface
    private interface PendingLookup {
        /** 返回存储契约已经排序的有限待投递行。 */
        List<TaskModels.MailboxMessage> find(String targetThreadId, int limit);
    }
}



