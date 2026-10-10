// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.MiddlewareStateRecordCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import io.github.kongweiguang.ja.agent.domain.execution.ControlRequest;
import io.github.kongweiguang.ja.agent.domain.execution.SuspendConsumption;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry.MiddlewareRegistration;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationRecord;

/**
 * 按一个已确认水位重建执行控制状态；不发起 IO，也不从当前业务配置补造历史。
 */
final class ExecutionStateView {
    private static final Set<String> FACT_TYPES = Set.of("agent.assistant", "agent.reasoning_summary",
            "agent.tool.message", "agent.tool.prepared", "agent.tool.started", "agent.tool.result",
            "agent.tool.recovery", "agent.tool.retry.intent", "agent.tool.recovery.verified",
            "agent.approval", "agent.usage");
    private final Map<String, AgentStore.StoredRecord> records = new LinkedHashMap<>();
    private final AgentStore.ExecutionKey executionKey;
    private final List<ExecutionFact> facts = new ArrayList<>();
    private final Map<ExecutionFact, AgentStore.StoredRecord> factRecords = new LinkedHashMap<>();
    private final Map<String, InteractionWait> waits = new LinkedHashMap<>();
    private final Map<String, AgentMiddleware.ExtensionStateView> middleware = new LinkedHashMap<>();
    private final Map<String, io.github.kongweiguang.ja.agent.domain.execution.ControlRequest> suspensions = new LinkedHashMap<>();
    private AgentInputQueue queue;
    private boolean cancellationRequested;

    /**
     * 只归并本执行的控制事实；精确退役队列快照失败关闭，会话历史由完整会话视图读取。
     * @author kongweiguang
     */
    ExecutionStateView(AgentLoopPersistence.Restored restored, AgentExecutionDriver.Services services,
                       List<ExtensionRegistry.MiddlewareRegistration> registrations) {
        this(restored, services, registrations, true);
    }

    /**
     * 正常恢复必须匹配全部状态 owner；只读取消视图不以缺失扩展阻止用户停止执行。
     */
    private ExecutionStateView(AgentLoopPersistence.Restored restored, AgentExecutionDriver.Services services,
                               List<ExtensionRegistry.MiddlewareRegistration> registrations,
                               boolean restoreMiddleware) {
        executionKey = restored.execution().key();
        queue = new AgentInputQueue(restored.execution().key().turnId(), 0, List.of());
        Map<String, ExtensionRegistry.MiddlewareRegistration> owners = new LinkedHashMap<>();
        for (MiddlewareRegistration registration : registrations) {
            owners.put(registration.namespace(), registration);
            middleware.put(registration.namespace(), new AgentMiddleware.ExtensionStateView(
                    registration.namespace(), 0, registration.stateSchemaVersion(), new JsonObject(Map.of())));
        }
        for (ConversationRecord row : restored.records()) {
            if (!row.execution().equals(restored.execution().key())) {
                continue;
            }
            AgentStore.StoredRecord record = stored(row);
            if (records.putIfAbsent(record.recordId(), record) != null) {
                throw corrupt("duplicate record identity");
            }
            if (FACT_TYPES.contains(record.recordType())) {
                ExecutionFact fact = services.records().decode(record);
                facts.add(fact);
                factRecords.put(fact, record);
            }
            switch (record.recordType()) {
                case "agent.input.queue.snapshot" ->
                        throw corrupt("input queue snapshot record is no longer supported");
                case "agent.input.queue.command" ->
                        queue = queue.apply(services.controls().decodeInputQueueCommand(record));
                case "agent.interaction.wait" -> {
                    InteractionWait wait = services.controls().decodeWait(record);
                    InteractionWait previous = waits.put(wait.interactionId(), wait);
                    if (previous != null
                        && wait.interactionRevision() != Math.addExact(previous.interactionRevision(), 1)) {
                        throw corrupt("wait revision is not contiguous");
                    }
                }
                case MiddlewareStateRecordCodec.RECORD_TYPE -> {
                    if (!restoreMiddleware) {
                        break;
                    }
                    JsonObject envelope = record.payload();
                    if (!(envelope.get("namespace") instanceof JsonText(String value))) {
                        throw corrupt("missing state owner");
                    }
                    MiddlewareRegistration registration = owners.get(value);
                    if (registration == null) {
                        throw corrupt("middleware owner is unavailable");
                    }
                    middleware.put(value, services.middlewareStates().decodeNext(
                            record, registration, middleware.get(value)));
                }
                case "agent.execution.cancel-request" -> {
                    services.controls().decodeCancelRequest(record);
                    cancellationRequested = true;
                }
                case "agent.execution.suspend-request" ->
                        suspensions.put(record.recordId(), services.controls().decodeSuspendRequest(record));
                case "agent.execution.suspend-consumed" -> {
                    SuspendConsumption consumption = services.controls().decodeSuspendConsumption(record);
                    ControlRequest request = suspensions.remove(consumption.suspendRequestRecordId());
                    if (request == null || !request.operationKey().equals(consumption.suspendOperationKey())) {
                        throw corrupt("suspend consumption does not match a pending request");
                    }
                }
                default -> { /* 其它事实由对应领域 codec 消费，不能混入控制状态。 */ }
            }
        }
    }

    /**
     * 无运行时取消只读取内核事实，保留扩展原记录但不加载、执行或重写已不可用的扩展状态。
     */
    static ExecutionStateView forCancellation(AgentLoopPersistence.Restored restored,
                                              AgentExecutionDriver.Services services) {
        return new ExecutionStateView(restored, services, List.of(), false);
    }

    /**
     * 转换仅移除会话外壳，完整保留存储身份、版本和顺序。
     */
    static AgentStore.StoredRecord stored(AgentStore.ConversationRecord row) {
        return new AgentStore.StoredRecord(row.recordId(), row.recordType(), row.payloadSchemaVersion(),
                row.sequence(), row.payload(), row.occurredAt());
    }

    /**
     * 错误消息不包含历史正文或扩展状态，宿主按执行身份查诊断。
     */
    private static AgentStore.StoreIntegrityException corrupt(String message) {
        return new AgentStore.StoreIntegrityException(message, null);
    }

    /**
     * 只返回固定水位内最后一个匹配事实，后续提交必须重新建立视图。
     */
    <T extends ExecutionFact> Optional<T> latest(Class<T> type, java.util.function.Predicate<T> predicate) {
        for (int index = facts.size() - 1; index >= 0; index--) {
            if (type.isInstance(facts.get(index))) {
                T value = type.cast(facts.get(index));
                if (predicate.test(value)) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 记录引用必须存在，不用空消息掩盖损坏的队列或恢复事实。
     */
    AgentStore.StoredRecord record(String id) {
        AgentStore.StoredRecord value = records.get(id);
        if (value == null) {
            throw corrupt("referenced record is missing");
        }
        return value;
    }

    /**
     * 从该模型请求随 ProviderPending 原子提交的严格快照中恢复摘要引用与工具可见性。
     */
    ExecutionRecordCodec.ModelCapabilities modelCapabilities(String messageId,
                                                             AgentExecutionDriver.Services services) {
        java.util.Objects.requireNonNull(services, "services");
        if (messageId == null || messageId.isBlank()) {
            throw corrupt("model message identity is missing");
        }
        String recordId = AgentRecordIds.derive(executionKey,
                ExecutionRecordCodec.MODEL_CAPABILITIES_RECORD_TYPE, messageId);
        AgentStore.StoredRecord record = record(recordId);
        ExecutionRecordCodec.ModelCapabilities capabilities =
                services.records().decodeModelCapabilities(record, executionKey);
        if (!messageId.equals(capabilities.messageId())) {
            throw corrupt("model capability snapshot is bound to another message");
        }
        return capabilities;
    }

    /**
     * 控制阶段只读最后一个同类型记录；先前记录仍保留在存储作为完整审计。
     */
    Optional<AgentStore.StoredRecord> latestRecord(String type) {
        return records.values().stream().filter(record -> record.recordType().equals(type)).reduce((previous, next) -> next);
    }

    /**
     * 返回单次安全点冻结的有序队列。
     */
    AgentInputQueue queue() {
        return queue;
    }

    /**
     * 中间件只能观察不可变状态，更新必须编码进同一执行提交。
     */
    Map<String, AgentMiddleware.ExtensionStateView> middleware() {
        return Map.copyOf(middleware);
    }

    /**
     * 保留等待的最终状态，回答后的恢复不重新提出同一个问题。
     */
    List<InteractionWait> waits() {
        return List.copyOf(waits.values());
    }

    /**
     * 取消是持久控制事实，进程重启不能忘记用户已取消的请求。
     */
    boolean cancellationRequested() {
        return cancellationRequested;
    }

    /**
     * 暂停由唯一持租约执行者在安全点结算，控制提交不能改写模型或工具游标。
     */
    boolean suspensionRequested() {
        return !suspensions.isEmpty();
    }

    /**
     * 已越过派发边界但无结果的动作不能因取消而假称已结算，必须保留挂起核验资格。
     */
    boolean hasUnsettledTool() {
        return facts.stream().filter(ExecutionFacts.ToolStarted.class::isInstance)
                .map(ExecutionFacts.ToolStarted.class::cast)
                .anyMatch(started -> latest(ExecutionFacts.ToolResult.class,
                        result -> result.callId().equals(started.callId())).isEmpty());
    }

    /**
     * 终态等关联事件引用真实持久行身份，不重新派生键或按最新数据库头猜测来源。
     */
    AgentStore.StoredRecord recordFor(ExecutionFact fact) {
        AgentStore.StoredRecord record = factRecords.get(fact);
        if (record == null) {
            throw corrupt("execution fact has no persisted source record");
        }
        return record;
    }
}
