// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;

import java.util.Locale;
import java.util.Objects;

/** 把统一压缩生命周期映射为 Provider 中立的严格 Thread 级通知。 */
public final class ContextCompactionEventWireMapper {
    private final ObjectMapper mapper;
    private final String serverInstanceId;

    /** 固定当前进程身份，避免 caller 在每种阶段重复拼公共字段。 */
    public ContextCompactionEventWireMapper(ObjectMapper mapper, String serverInstanceId) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.serverInstanceId = Objects.requireNonNull(serverInstanceId, "serverInstanceId");
    }

    /** 严格穷举三阶段，并让 nullable Token 与 turnId 显式进入 JSON。 */
    public WireEvent map(ContextCompactionEvent event) {
        Objects.requireNonNull(event, "event");
        ContextCompactionEvent.Context context = event.context();
        ObjectNode params = mapper.createObjectNode().put("serverInstanceId", serverInstanceId)
                .put("eventId", context.eventId()).put("workspaceId", context.workspaceId())
                .put("threadId", context.threadId()).put("turnId", context.turnId())
                .put("threadRevision", context.threadRevision()).put("occurredAt", context.occurredAt().toString())
                .put("compactionId", context.compactionId())
                .put("trigger", context.trigger().name().toLowerCase(Locale.ROOT))
                .put("sourceRevision", context.sourceRevision())
                .put("strategyVersion", context.strategyVersion());
        if (context.inputTokensBefore() == null) params.putNull("inputTokensBefore");
        else params.put("inputTokensBefore", context.inputTokensBefore());
        if (context.inputTokensAfter() == null) params.putNull("inputTokensAfter");
        else params.put("inputTokensAfter", context.inputTokensAfter());
        String method;
        if (event instanceof ContextCompactionEvent.Started) {
            method = "context/compaction-started";
        } else if (event instanceof ContextCompactionEvent.Compacted compacted) {
            method = "context/compacted";
            params.put("checkpointId", compacted.checkpointId());
        } else if (event instanceof ContextCompactionEvent.Failed failed) {
            method = "context/compaction-failed";
            params.put("errorCode", failed.errorCode().name());
        } else {
            throw new IllegalStateException("unknown context compaction event");
        }
        return new WireEvent(method, params);
    }

    /** 冻结通知与参数，异步 writer 不得观察调用方节点变更。 */
    public record WireEvent(String method, ObjectNode params) {
        /** 深拷贝可变 Jackson 节点以转移异步所有权。 */
        public WireEvent {
            Objects.requireNonNull(method, "method");
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /** 每个写入 owner 获得独立节点，连接元数据不会污染基础事件。 */
        @Override
        public ObjectNode params() {
            return params.deepCopy();
        }
    }
}
