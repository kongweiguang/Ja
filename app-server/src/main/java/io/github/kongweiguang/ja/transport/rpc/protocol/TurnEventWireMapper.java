// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;

import java.util.Locale;
import java.util.Objects;

/**
 * 将 Agent 事件映射为严格通知载荷，不暴露 Provider 私有字段或 Secret。
 */
public final class TurnEventWireMapper {
    private final ObjectMapper mapper;
    private final String serverInstanceId;
    private final ToolPresentationWireMapper presentations;

    /**
     * 固定当前进程实例标识，避免重启后的事件与上一代实例混淆。
     */
    public TurnEventWireMapper(ObjectMapper mapper, String serverInstanceId) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        presentations = new ToolPresentationWireMapper(mapper);
        this.serverInstanceId = requireId(serverInstanceId, "srv_");
    }

    /**
     * 只转换领域载荷；连接级 sequence、generation 和工作区关联由 RpcSession 统一补全。
     */
    public WireEvent map(TurnEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof TurnEvent.TextDelta value) {
            return new WireEvent("assistant/text-delta", mapper.createObjectNode()
                    .put("turnId", value.turnId()).put("streamSeq", value.streamSeq()).put("text", value.text()));
        }
        if (event instanceof TurnEvent.ReasoningSummaryDelta value) {
            return new WireEvent("assistant/reasoning-summary-delta", mapper.createObjectNode()
                    .put("turnId", value.turnId()).put("streamSeq", value.streamSeq()).put("text", value.text()));
        }
        ObjectNode params = common(event.context());
        String method;
        switch (event) {
            case TurnEvent.StateChanged value -> {
                method = "turn/state-changed";
                params.put("from", wire(value.from()));
                params.put("to", wire(value.to()));
            }
            case TurnEvent.ModelStepCommitted value -> {
                method = "assistant/model-step-committed";
                params.put("messageId", value.messageId());
                params.put("text", value.text());
                if (value.reasoningSummary() != null) params.put("reasoningSummary", value.reasoningSummary());
                params.put("modelRound", value.modelRound());
                params.set("usage", RpcResults.requestUsage(mapper, value.usage(), value.context().occurredAt()));
                ArrayNode calls = params.putArray("toolCalls");
                value.toolCalls().forEach(call -> {
                    ObjectNode item = calls.addObject();
                    item.put("callId", call.callId()).put("toolName", call.toolName());
                    item.set("presentation", presentations.map(call.presentation()));
                    item.put("ordinal", call.ordinal());
                });
            }
            case TurnEvent.ToolStarted value -> {
                method = "tool/started";
                params.put("callId", value.callId());
                params.put("ordinal", value.ordinal());
            }
            case TurnEvent.ToolBatchCommitted value -> {
                method = "tool/batch-committed";
                ArrayNode results = params.putArray("results");
                value.results().forEach(result -> {
                    ObjectNode item = results.addObject();
                    item.put("callId", result.callId()).put("outcome", wire(result.outcome()))
                            .put("ordinal", result.ordinal())
                            .set("presentation", presentations.map(result.presentation()));
                    if (result.errorCode() != null) item.put("errorCode", result.errorCode());
                });
            }
            case TurnEvent.ApprovalRequested value -> {
                method = "approval/requested";
                params.put("approvalId", value.approvalId());
                params.put("callId", value.callId());
                params.put("toolName", value.toolName());
                params.put("reason", value.reason());
                params.put("expiresAt", value.expiresAt().toString());
                params.put("from", "running").put("to", "waiting_approval");
            }
            case TurnEvent.ApprovalResolved value -> {
                method = "approval/resolved";
                params.put("approvalId", value.approvalId());
                params.put("decision", wire(value.decision()));
                params.put("from", "waiting_approval").put("to", "running");
            }
            case TurnEvent.InputQueueChanged value -> {
                method = "turn/input-queue-changed";
                params.remove("threadRevision");
                params.set("inputQueue", RpcResults.inputQueue(mapper, value.inputQueue()));
            }
            case TurnEvent.InputConsumed value -> {
                method = "turn/input-consumed";
                params.set("input", RpcResults.queuedInput(mapper, value.input()));
                ObjectNode userItem = params.putObject("userItem").put("itemId", value.userItem().itemId())
                        .put("createdAt", value.userItem().createdAt().toString())
                        .put("turnId", value.userItem().turnId()).put("kind", "user_input");
                userItem.set("content", RpcResults.userContent(mapper, value.userItem().content()));
                userItem.set("attachments", RpcResults.attachmentSummaries(mapper,
                        value.userItem().attachments()));
                params.set("inputQueue", RpcResults.inputQueue(mapper, value.inputQueue()));
                if (value.assistantSettlement() != null) {
                    TurnEvent.AssistantSettlement settlement = value.assistantSettlement();
                    ObjectNode assistant = params.putObject("assistantSettlement")
                            .put("messageId", settlement.messageId()).put("text", settlement.text())
                            .put("modelRound", settlement.modelRound());
                    assistant.set("usage", RpcResults.requestUsage(
                            mapper, settlement.usage(), value.context().occurredAt()));
                    if (settlement.reasoningSummary() != null) {
                        assistant.put("reasoningSummary", settlement.reasoningSummary());
                    }
                }
            }
            case TurnEvent.Terminal value -> {
                method = "turn/terminal";
                params.put("state", wire(value.state()));
                params.put("summary", value.summary());
                if (value.errorCode() != null) params.put("errorCode", value.errorCode());
                if (value.errorMessage() != null) params.put("errorMessage", value.errorMessage());
                if (value.finalMessage() != null) {
                    params.putObject("finalMessage").put("messageId", value.finalMessage().messageId())
                            .put("text", value.finalMessage().text());
                }
                if (value.usage() != null) {
                    params.set("usage", RpcResults.requestUsage(
                            mapper, value.usage(), value.context().occurredAt()));
                }
                params.set("changeSet", RpcResults.changeSet(mapper, value.changeSet()));
            }
            case TurnEvent.TextDelta ignored ->
                    throw new IllegalStateException("draft events must be handled before durable mapping");
            case TurnEvent.ReasoningSummaryDelta ignored ->
                    throw new IllegalStateException("draft events must be handled before durable mapping");
        }
        return new WireEvent(method, params);
    }

    /**
     * 写入持久事件自带的关联字段，连接级元数据稍后在 RpcSession 中统一分配。
     */
    private ObjectNode common(TurnEvent.Context context) {
        Objects.requireNonNull(context, "context");
        return mapper.createObjectNode().put("serverInstanceId", serverInstanceId)
                .put("eventId", context.eventId()).put("threadId", context.threadId()).put("turnId", context.turnId())
                .put("threadRevision", context.threadRevision()).put("occurredAt", context.occurredAt().toString());
    }

    /**
     * 将 Java 枚举转换为协议规定的小写词汇，包括 waiting_approval。
     */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 将 Tool 结果转换为公开协议值，不暴露实现异常类型。
     */
    private static String wire(ToolOutcome value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 将审批结果转换为 approve、deny 协议词汇。
     */
    private static String wire(io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision value) {
        return switch (value) {
            case APPROVE -> "approve";
            case DENY -> "deny";
        };
    }

    /**
     * 在复制到通知前校验进程实例标识，禁止非法值进入异步写队列。
     */
    private static String requireId(String value, String prefix) {
        if (value == null || !value.startsWith(prefix) || value.length() > 100
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid server instance id");
        }
        return value;
    }

    /**
     * 不可变 Wire 对防止异步写队列观察到调用方后续修改的 Jackson 节点。
     */
    public record WireEvent(String method, ObjectNode params) {
        /**
         * 深拷贝参数，因为 stdout 异步所有权可能超过 mapper 调用周期。
         */
        public WireEvent {
            Objects.requireNonNull(method, "method");
            params = Objects.requireNonNull(params, "params").deepCopy();
        }

        /**
         * 每个写入所有者取得独立节点，防止连接元数据补全改变基础事件模板。
         */
        @Override
        public ObjectNode params() {
            return params.deepCopy();
        }
    }
}
