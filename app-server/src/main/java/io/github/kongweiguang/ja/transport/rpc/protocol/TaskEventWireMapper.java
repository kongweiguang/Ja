// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.task.port.in.TaskEvent;

import java.util.Objects;

/** 将 Task 领域事件映射为 JA-RPC v1 严格闭集，连接级 sequence/generation 由 RpcSession 补齐。 */
public final class TaskEventWireMapper {
    private final ObjectMapper mapper;

    /** ObjectMapper 只负责创建 wire nodes，不参与 Task 状态所有权。 */
    public TaskEventWireMapper(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** sealed switch 保证新增领域事件若未同步协议会在编译期失败。 */
    public WireEvent map(TaskEvent event) {
        Objects.requireNonNull(event, "event");
        ObjectNode params = base(event.context());
        return switch (event) {
            case TaskEvent.Activity value -> {
                params.set("activity", RpcResults.taskActivity(mapper, value.activity()));
                params.set("task", RpcResults.task(mapper, value.task()));
                yield new WireEvent("task/activity", params);
            }
            case TaskEvent.Progress value -> {
                params.put("observationId", value.observationId())
                        .put("progressRevision", value.progressRevision())
                        .put("safeSummary", value.safeSummary());
                yield new WireEvent("task/progress", params);
            }
            case TaskEvent.MailboxChanged value -> {
                params.put("mailboxSequence", value.mailboxSequence()).put("unreadCount", value.unreadCount());
                yield new WireEvent("task/mailbox-changed", params);
            }
        };
    }

    /** Task 自有可恢复字段先写入，连接身份只允许 RpcSession 后置追加。 */
    private ObjectNode base(TaskEvent.Context context) {
        return mapper.createObjectNode().put("rootThreadId", context.rootThreadId())
                .put("taskThreadId", context.taskThreadId()).put("taskRevision", context.taskRevision())
                .put("occurredAt", context.occurredAt().toString());
    }

    /** 返回一次深拷贝安全的 method/params 对。 */
    public record WireEvent(String method, ObjectNode params) {
        /** 仅允许协议定义的三个 Task event method。 */
        public WireEvent {
            if (!("task/activity".equals(method) || "task/progress".equals(method)
                    || "task/mailbox-changed".equals(method))) {
                throw new IllegalArgumentException("invalid task event method");
            }
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /** 每次交给 writer 前复制，避免异步追加修改调用者持有节点。 */
        @Override public ObjectNode params() {
            return params.deepCopy();
        }
    }
}
