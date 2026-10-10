// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.infrastructure.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Core 持久事件的显式版本协议；不使用反射多态，也不接受宽松字段兼容或未知类型。
 */
public final class JacksonExecutionEventCodec implements io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec {
    private final ObjectMapper mapper;

    /**
     * 复制宿主 JSON 配置并隐藏异常中的源文本，字段、枚举和版本仍完全由本协议控制。
     */
    public JacksonExecutionEventCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .disable(com.fasterxml.jackson.core.JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION);
    }

    /**
     * 保存有序引用数组，不将集合重排后改变实际模型或工具次序。
     */
    private static void strings(ObjectNode node, String field, List<String> values) {
        ArrayNode array = node.putArray(field);
        values.forEach(array::add);
    }

    /**
     * 完整字段集合是版本协议的一部分，nullable 字段也必须显式写 null。
     */
    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            throw corrupt();
        }
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of(names))) {
            throw corrupt();
        }
    }

    /**
     * 文本不做类型转换，防止数字身份被悄悄强转字符串。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw corrupt();
        }
        return value.textValue();
    }

    /**
     * 枚举值必须精确属于当前协议；丢弃 Enum.valueOf 的原文异常，避免损坏载荷进入诊断链。
     */
    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> enumType) {
        String value = text(node, field);
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException ignored) {
            throw corrupt();
        }
    }

    /**
     * 空值是协议中明确的缺省，不将缺失字段或其它 JSON 类型解释为空。
     */
    private static String nullable(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw corrupt();
        }
        return value.isNull() ? null : text(node, field);
    }

    /**
     * 保留整数精度，拒绝小数与溢出以免恢复到错误的工具序号。
     */
    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corrupt();
        }
        return value.intValue();
    }

    /**
     * 退避时长使用毫秒长整型，不能经浮点转换丢失审计数值。
     */
    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corrupt();
        }
        return value.longValue();
    }

    /**
     * 数组元素必须都是身份字符串，重复验证交给领域事件维持单一规则。
     */
    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw corrupt();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw corrupt();
            }
            result.add(item.textValue());
        }
        return List.copyOf(result);
    }

    /**
     * 损坏事实统一报告存储错误，不把未知事件按成功忽略。
     */
    private static StorageException corrupt() {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid execution event payload");
    }

    /**
     * 保留事件 JSON 解码根因供本地诊断，不把损坏载荷放入稳定错误文本。
     */
    private static StorageException corrupt(Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, "invalid execution event payload", cause);
    }

    /**
     * 编码引用型事件，Store 在同一事务内分配顺序并保存其引用的事实。
     */
    @Override
    public AgentStore.EventWrite encode(String eventId, ExecutionEvent event, Instant occurredAt) {
        ObjectNode node = mapper.createObjectNode();
        String type = switch (Objects.requireNonNull(event, "event")) {
            case ExecutionEvent.Admitted value -> {
                node.put("kind", value.kind()).put("inputMessageId", value.inputMessageId())
                        .put("instructionMessageId", value.instructionMessageId());
                yield "agent.execution.admitted";
            }
            case ExecutionEvent.StateChanged value -> {
                node.put("from", value.from().name()).put("to", value.to().name());
                yield "agent.execution.state";
            }
            case ExecutionEvent.ModelCompleted value -> {
                node.put("messageRecordId", value.messageRecordId()).put("modelRound", value.modelRound())
                        .put("usageRecordId", value.usageRecordId());
                strings(node, "toolCallIds", value.toolCallIds());
                yield "agent.model.completed";
            }
            case ExecutionEvent.AssistantSettled value -> {
                node.put("messageRecordId", value.messageRecordId());
                yield "agent.assistant.settled";
            }
            case ExecutionEvent.ToolStarted value -> {
                node.put("callId", value.callId()).put("batchId", value.batchId())
                        .put("toolName", value.toolName()).put("ordinal", value.ordinal());
                yield "agent.tool.started";
            }
            case ExecutionEvent.ToolCompleted value -> {
                node.put("callId", value.callId()).put("state", value.state().name())
                        .put("resultRecordId", value.resultRecordId()).put("toolMessageRecordId", value.toolMessageRecordId())
                        .put("errorCode", value.errorCode());
                yield "agent.tool.completed";
            }
            case ExecutionEvent.ToolRecoveryChanged value -> {
                node.put("recoveryId", value.recoveryId()).put("callId", value.callId())
                        .put("revision", value.revision()).put("decisionRecordId", value.decisionRecordId());
                yield "agent.tool.recovery.changed";
            }
            case ExecutionEvent.WaitRequested value -> {
                node.put("interactionId", value.interactionId()).put("kind", value.kind()).put("requestRecordId", value.requestRecordId());
                yield "agent.wait.requested";
            }
            case ExecutionEvent.WaitResolved value -> {
                node.put("interactionId", value.interactionId());
                strings(node, "answerIds", value.answerIds());
                yield "agent.wait.resolved";
            }
            case ExecutionEvent.InputAccepted value -> {
                node.put("messageId", value.messageId());
                yield "agent.input.accepted";
            }
            case ExecutionEvent.Terminal value -> {
                node.put("state", value.state().name()).put("summary", value.summary())
                        .put("errorCode", value.errorCode()).put("errorMessage", value.errorMessage())
                        .put("messageRecordId", value.messageRecordId()).put("usageRecordId", value.usageRecordId());
                yield "agent.execution.terminal";
            }
            case ExecutionEvent.RetryScheduled value -> {
                node.put("modelRequestId", value.modelRequestId()).put("attempt", value.attempt())
                        .put("delayMillis", value.delayMillis()).put("reasonCode", value.reasonCode());
                yield "agent.model.retry";
            }
            case ExecutionEvent.ContextUsageEstimated value -> {
                node.put("usageRecordId", value.usageRecordId());
                yield "agent.context.usage.estimated";
            }
            case ExecutionEvent.ContextCompactionStarted value -> {
                node.put("compactionId", value.compactionId()).put("sourceRevision", value.sourceRevision())
                        .put("inputTokensBefore", value.inputTokensBefore());
                yield "agent.context.compaction.started";
            }
            case ExecutionEvent.ContextCompacted value -> {
                node.put("compactionId", value.compactionId()).put("outcome", value.outcome().name())
                        .put("checkpointId", value.checkpointId())
                        .put("checkpointRecordId", value.checkpointRecordId())
                        .put("sourceRevision", value.sourceRevision()).put("threadRevision", value.threadRevision())
                        .put("inputTokensBefore", value.inputTokensBefore()).put("inputTokensAfter", value.inputTokensAfter());
                yield "agent.context.compaction.completed";
            }
            case ExecutionEvent.ContextCompactionFailed value -> {
                node.put("compactionId", value.compactionId()).put("sourceRevision", value.sourceRevision())
                        .put("inputTokensBefore", value.inputTokensBefore()).put("reasonCode", value.reasonCode());
                yield "agent.context.compaction.failed";
            }
            case ExecutionEvent.ControlRecorded value -> {
                node.put("action", value.action().name()).put("controlRecordId", value.controlRecordId())
                        .put("operationKey", value.operationKey()).put("reasonCode", value.reasonCode());
                yield "agent.execution.control";
            }
        };
        if (!(JacksonJsonValues.fromNode(node) instanceof JsonObject payload)) {
            throw new IllegalStateException("event payload must be a JSON object");
        }
        return new AgentStore.EventWrite(eventId, type, 1, payload, occurredAt);
    }

    /**
     * 仅解析 Store 已提交的事件；草稿流不经过此编解码器。
     */
    @Override
    public ExecutionEvent decode(AgentStore.PersistedEvent event) {
        Objects.requireNonNull(event, "event");
        return decode(event.eventType(), event.payloadSchemaVersion(),
                new JacksonJsonValueCodec(mapper).encode(event.payload()));
    }

    /**
     * 严格拒绝损坏、重复键和未知版本，错误中不回显可能含敏感信息的原始载荷。
     */
    public ExecutionEvent decode(String type, int version, String json) {
        if (version != 1) {
            throw corrupt();
        }
        try {
            JsonNode node = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
            return switch (type) {
                case "agent.execution.admitted" -> {
                    fields(node, "kind", "inputMessageId", "instructionMessageId");
                    yield new ExecutionEvent.Admitted(text(node, "kind"), nullable(node, "inputMessageId"), nullable(node, "instructionMessageId"));
                }
                case "agent.execution.state" -> {
                    fields(node, "from", "to");
                    yield new ExecutionEvent.StateChanged(enumValue(node, "from", TurnState.class),
                            enumValue(node, "to", TurnState.class));
                }
                case "agent.model.completed" -> {
                    fields(node, "messageRecordId", "modelRound", "toolCallIds", "usageRecordId");
                    yield new ExecutionEvent.ModelCompleted(text(node, "messageRecordId"), integer(node, "modelRound"),
                            strings(node, "toolCallIds"), text(node, "usageRecordId"));
                }
                case "agent.assistant.settled" -> {
                    fields(node, "messageRecordId");
                    yield new ExecutionEvent.AssistantSettled(text(node, "messageRecordId"));
                }
                case "agent.tool.started" -> {
                    fields(node, "callId", "batchId", "toolName", "ordinal");
                    yield new ExecutionEvent.ToolStarted(text(node, "callId"), text(node, "batchId"), text(node, "toolName"), integer(node, "ordinal"));
                }
                case "agent.tool.completed" -> {
                    fields(node, "callId", "state", "resultRecordId", "toolMessageRecordId", "errorCode");
                    yield new ExecutionEvent.ToolCompleted(text(node, "callId"), enumValue(node, "state", ToolState.class),
                            text(node, "resultRecordId"), text(node, "toolMessageRecordId"), nullable(node, "errorCode"));
                }
                case "agent.tool.recovery.changed" -> {
                    fields(node, "recoveryId", "callId", "revision", "decisionRecordId");
                    yield new ExecutionEvent.ToolRecoveryChanged(text(node, "recoveryId"), text(node, "callId"),
                            number(node, "revision"), text(node, "decisionRecordId"));
                }
                case "agent.wait.requested" -> {
                    fields(node, "interactionId", "kind", "requestRecordId");
                    yield new ExecutionEvent.WaitRequested(text(node, "interactionId"), text(node, "kind"), text(node, "requestRecordId"));
                }
                case "agent.wait.resolved" -> {
                    fields(node, "interactionId", "answerIds");
                    yield new ExecutionEvent.WaitResolved(text(node, "interactionId"), strings(node, "answerIds"));
                }
                case "agent.input.accepted" -> {
                    fields(node, "messageId");
                    yield new ExecutionEvent.InputAccepted(text(node, "messageId"));
                }
                case "agent.execution.terminal" -> {
                    fields(node, "state", "summary", "errorCode", "errorMessage", "messageRecordId", "usageRecordId");
                    yield new ExecutionEvent.Terminal(enumValue(node, "state", TurnState.class), text(node, "summary"),
                            nullable(node, "errorCode"), nullable(node, "errorMessage"),
                            nullable(node, "messageRecordId"), nullable(node, "usageRecordId"));
                }
                case "agent.model.retry" -> {
                    fields(node, "modelRequestId", "attempt", "delayMillis", "reasonCode");
                    yield new ExecutionEvent.RetryScheduled(text(node, "modelRequestId"), integer(node, "attempt"),
                            number(node, "delayMillis"), text(node, "reasonCode"));
                }
                case "agent.context.usage.estimated" -> {
                    fields(node, "usageRecordId");
                    yield new ExecutionEvent.ContextUsageEstimated(text(node, "usageRecordId"));
                }
                case "agent.context.compaction.started" -> {
                    fields(node, "compactionId", "sourceRevision", "inputTokensBefore");
                    yield new ExecutionEvent.ContextCompactionStarted(text(node, "compactionId"),
                            number(node, "sourceRevision"), number(node, "inputTokensBefore"));
                }
                case "agent.context.compaction.completed" -> {
                    fields(node, "compactionId", "outcome", "checkpointId", "checkpointRecordId", "sourceRevision",
                            "threadRevision", "inputTokensBefore", "inputTokensAfter");
                    yield new ExecutionEvent.ContextCompacted(text(node, "compactionId"),
                            enumValue(node, "outcome", ExecutionEvent.CompactionOutcome.class),
                            nullable(node, "checkpointId"), nullable(node, "checkpointRecordId"),
                            number(node, "sourceRevision"),
                            number(node, "threadRevision"), number(node, "inputTokensBefore"),
                            number(node, "inputTokensAfter"));
                }
                case "agent.context.compaction.failed" -> {
                    fields(node, "compactionId", "sourceRevision", "inputTokensBefore", "reasonCode");
                    yield new ExecutionEvent.ContextCompactionFailed(text(node, "compactionId"),
                            number(node, "sourceRevision"), number(node, "inputTokensBefore"), text(node, "reasonCode"));
                }
                case "agent.execution.control" -> {
                    fields(node, "action", "controlRecordId", "operationKey", "reasonCode");
                    yield new ExecutionEvent.ControlRecorded(enumValue(node, "action", ExecutionEvent.ControlAction.class),
                            text(node, "controlRecordId"), text(node, "operationKey"), nullable(node, "reasonCode"));
                }
                default -> throw corrupt();
            };
        } catch (java.io.IOException | RuntimeException failure) {
            throw corrupt(failure);
        }
    }
}
