// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;

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
     * 多个领域枚举共用小写 wire 规则，避免审批和恢复事件误走 ToolOutcome 专用转换。
     */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
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
     * 只转换领域载荷；公开正文采用与快照相同的有限预览，完整消息按 itemId 单独读取。
     * 未知 ChangeSet 保持显式 null，Live 通知移除快照专属 origin，连接级元数据由 RpcSession 补全。
     */
    public WireEvent map(TurnEvent event) {
        Objects.requireNonNull(event, "event");
        if (event instanceof TurnEvent.TextDelta delta) {
            return new WireEvent("assistant/text-delta", mapper.createObjectNode()
                    .put("turnId", delta.turnId()).put("streamSeq", delta.streamSeq())
                    .put("text", delta.text()));
        }
        if (event instanceof TurnEvent.ReasoningSummaryDelta delta) {
            return new WireEvent("assistant/reasoning-summary-delta", mapper.createObjectNode()
                    .put("turnId", delta.turnId()).put("streamSeq", delta.streamSeq())
                    .put("text", delta.text()));
        }
        ObjectNode params = common(event.context());
        String method;
        switch (event) {
            case TurnEvent.RetryStarted value -> {
                method = "turn/retry-started";
                params.put("attempt", value.attempt());
            }
            case TurnEvent.StateChanged value -> {
                method = "turn/state-changed";
                params.put("from", TurnStateWire.value(value.from()));
                params.put("to", TurnStateWire.value(value.to()));
            }
            case TurnEvent.ModelStepCommitted value -> {
                method = "assistant/model-step-committed";
                params.put("messageId", value.messageId());
                RpcResults.textPreview(params, "text", value.text());
                if (value.reasoningSummary() != null) {
                    RpcResults.textPreview(params, "reasoningSummary", "reasoningSummaryTotalCharacters",
                            "reasoningSummaryTruncated", value.reasoningSummary());
                }
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
            case TurnEvent.ContextUsageEstimated value -> {
                method = "assistant/context-estimated";
                ObjectNode usage = RpcResults.requestUsage(mapper, value.usage(), value.context().occurredAt());
                usage.putObject("contextEstimate")
                        .put("certainty", "estimated")
                        .put("source", "local")
                        .put("usedTokens", value.estimatedTokens())
                        .put("limitTokens", value.usage().profile().contextWindowTokens())
                        .put("measuredAt", value.context().occurredAt().toString());
                params.set("usage", usage);
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
                    if (result.errorCode() != null) {
                        item.put("errorCode", result.errorCode());
                    }
                });
            }
            case TurnEvent.ApprovalRequested value -> {
                method = "approval/requested";
                params.put("approvalId", value.approvalId());
                params.put("callId", value.callId());
                params.put("toolName", value.toolName());
                params.set("presentation", presentations.map(value.presentation()));
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
            case TurnEvent.ApprovalClosed value -> {
                method = "approval/closed";
                params.put("approvalId", value.approvalId()).put("reason", wire(value.reason()));
            }
            case TurnEvent.ToolRecoveryChanged value -> {
                method = "tool/recovery-changed";
                params.put("recoveryId", value.recoveryId()).put("callId", value.callId())
                        .put("revision", value.revision()).put("status", wire(value.status()));
                if (value.disposition() != null) {
                    params.put("disposition", wire(value.disposition()));
                }
                params.put("requiresUserAction", value.status() == ToolRecoveryDecision.Status.PENDING
                                                 && value.disposition() == null);
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
                RpcResults.userContentPreview(mapper, userItem, value.userItem().content());
                userItem.set("attachments", RpcResults.attachmentSummaries(mapper,
                        value.userItem().attachments()));
                params.set("inputQueue", RpcResults.inputQueue(mapper, value.inputQueue()));
                if (value.assistantSettlement() != null) {
                    TurnEvent.AssistantSettlement settlement = value.assistantSettlement();
                    ObjectNode assistant = params.putObject("assistantSettlement")
                            .put("messageId", settlement.messageId()).put("modelRound", settlement.modelRound());
                    RpcResults.textPreview(assistant, "text", settlement.text());
                    assistant.set("usage", RpcResults.requestUsage(
                            mapper, settlement.usage(), value.context().occurredAt()));
                    if (settlement.reasoningSummary() != null) {
                        RpcResults.textPreview(assistant, "reasoningSummary",
                                "reasoningSummaryTotalCharacters", "reasoningSummaryTruncated",
                                settlement.reasoningSummary());
                    }
                }
            }
            case TurnEvent.MessagesReceived value -> {
                method = "turn/messages_received";
                ArrayNode items = params.putArray("items");
                value.items().forEach(item -> {
                    ObjectNode liveItem = RpcResults.snapshotItem(mapper, item);
                    liveItem.remove("origin");
                    items.add(liveItem);
                });
            }
            case TurnEvent.ExtensionEvent ignored ->
                    throw new IllegalStateException("extension events are not supported by JA-RPC v2");
            case TurnEvent.Terminal value -> {
                method = "turn/terminal";
                params.put("state", TurnStateWire.value(value.state()));
                RpcResults.textPreview(params, "summary", "summaryTotalCharacters", "summaryTruncated",
                        value.summary());
                if (value.errorCode() != null) {
                    params.put("errorCode", value.errorCode());
                }
                if (value.errorMessage() != null) {
                    params.put("errorMessage", value.errorMessage());
                }
                if (value.finalMessage() != null) {
                    ObjectNode message = params.putObject("finalMessage")
                            .put("messageId", value.finalMessage().messageId());
                    RpcResults.textPreview(message, "text", value.finalMessage().text());
                }
                if (value.usage() != null) {
                    params.set("usage", RpcResults.requestUsage(
                            mapper, value.usage(), value.context().occurredAt()));
                }
                if (value.changeSet() == null) {
                    params.putNull("changeSet");
                }
                else {
                    params.set("changeSet", RpcResults.changeSet(mapper, value.changeSet()));
                }
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
     * 不可变 Wire 对防止异步写队列观察到调用方后续修改的 Jackson 节点。
     *
     * @param method 方法
     * @param params 参数
     */
    public record WireEvent(String method,
                            ObjectNode params) {
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
