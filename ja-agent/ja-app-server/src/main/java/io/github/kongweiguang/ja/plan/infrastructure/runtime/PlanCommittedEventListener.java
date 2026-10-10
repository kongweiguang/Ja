// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.plan.infrastructure.runtime;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.plan.application.service.PlanCommittedPublisher;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepository;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 从已提交事务的精确记录窗口识别 Plan 变更，再只读发布对应聚合事件。
 */
public final class PlanCommittedEventListener implements AgentEventListener {
    private static final String JA_TENANT = "ja.local";
    private static final String PLAN_NAMESPACE = "ja.plan";
    private static final int PAGE_SIZE = 256;

    private final AgentStore store;
    private final ExtensionFactRecordCodec extensionFacts;
    private final PlanRepository plans;
    private final PlanCommittedPublisher publisher;

    /**
     * Listener 只消费 Store 已提交的增量事实；Store、Repository 和 publisher 都由组合根注入。
     */
    public PlanCommittedEventListener(AgentStore store, ExtensionFactRecordCodec extensionFacts,
                                      PlanRepository plans, PlanCommittedPublisher publisher) {
        this.store = Objects.requireNonNull(store, "store");
        this.extensionFacts = Objects.requireNonNull(extensionFacts, "extensionFacts");
        this.plans = Objects.requireNonNull(plans, "plans");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * actor 四元组必须与 Core listener 收到的同一个执行身份完全相同。
     */
    private static void verifyActor(JsonObject actor, AgentIdentity expected) {
        if (!expected.tenantId().equals(text(actor, "tenantId"))
            || !expected.threadId().equals(text(actor, "threadId"))
            || !expected.turnId().equals(text(actor, "turnId"))
            || !expected.userId().equals(text(actor, "userId"))) {
            throw new IllegalStateException("Plan committed fact actor changed");
        }
    }

    /**
     * 嵌套身份必须显式编码为 JSON object；结构错误交给监听器诊断报告。
     */
    private static JsonObject object(JsonObject value, String field) {
        if (value.get(field) instanceof JsonObject nested) {
            return nested;
        }
        throw new IllegalStateException("Plan committed fact object is invalid");
    }

    /**
     * 稳定身份只接受非空字符串，不把数字、布尔值或 null 强制转换。
     */
    private static String text(JsonObject value, String field) {
        if (value.get(field) instanceof JsonText(String value1) && !value1.isBlank()) {
            return value1;
        }
        throw new IllegalStateException("Plan committed fact text is invalid");
    }

    /**
     * 可空聚合关联保留显式 JSON null，并与缺失 schema 字段区分。
     */
    private static String nullableText(JsonObject value, String field) {
        JsonValue candidate = value.get(field);
        if (candidate == JsonNull.INSTANCE) {
            return null;
        }
        if (candidate instanceof JsonText(String value1) && !value1.isBlank()) {
            return value1;
        }
        throw new IllegalStateException("Plan committed fact nullable text is invalid");
    }

    /**
     * 只有明确存在的可选聚合身份才进入发布集合。
     */
    private static void add(Set<String> target, String value) {
        if (value != null) {
            target.add(value);
        }
    }

    /**
     * 返回订阅身份；重复发布由 Plan 自身的持久事件水位去重。
     */
    @Override
    public String id() {
        return "ja.plan.committed-events";
    }

    /**
     * 回调只读精确事务记录，不参与工具结果或业务状态提交；发布去重由 Plan 持久事件水位承担。
     */
    @Override
    public void onEvent(AgentEvent event) {
        AgentEvent committed = Objects.requireNonNull(event, "event");
        AgentIdentity actor = committed.identity();
        if (!JA_TENANT.equals(actor.tenantId())) {
            return;
        }
        ensureNotInterrupted();
        for (String planId : changedPlanIds(committed.persistedEvent(), actor)) {
            publisher.publishPlanCommitted(planId);
        }
    }

    /**
     * 固定回放上界并扫描会话级事件序号；其他 execution 只推进全局游标，不触发目标执行的投影。
     */
    @Override
    public void onReplayRequired(AgentEventListener.ReplayRequired replay) {
        AgentEventListener.ReplayRequired request = Objects.requireNonNull(replay, "replay");
        AgentIdentity identity = request.identity();
        if (!JA_TENANT.equals(identity.tenantId())
            || !AppServerIdentity.local().userId().equals(identity.userId())) {
            return;
        }
        AgentStore.ExecutionKey execution = AppServerIdentity.local().executionKey(identity);
        AgentStore.ConversationKey conversation = execution.conversation();
        long cursor = request.afterSequence();
        while (cursor < request.throughSequence()) {
            ensureNotInterrupted();
            AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                    conversation, cursor, request.throughSequence(), PAGE_SIZE));
            if (page.events().isEmpty()) {
                throw new IllegalStateException("required committed event range is unavailable for replay");
            }
            if (page.events().size() > PAGE_SIZE) {
                throw new IllegalStateException("committed event page exceeded its bound");
            }
            long pageCursor = cursor;
            for (AgentStore.PersistedEvent persisted : page.events()) {
                ensureNotInterrupted();
                if (!conversation.equals(persisted.conversation())
                    || persisted.sequence() <= pageCursor || persisted.sequence() > request.throughSequence()) {
                    throw new IllegalStateException("committed event page is invalid");
                }
                pageCursor = persisted.sequence();
                if (execution.equals(persisted.execution())) {
                    onEvent(new AgentEvent(identity, persisted));
                }
            }
            if (page.nextSequence() != pageCursor) {
                throw new IllegalStateException("committed event page cursor is invalid");
            }
            if (page.hasMore()) {
                if (pageCursor <= cursor) {
                    throw new IllegalStateException("committed event page cursor did not advance");
                }
                if (pageCursor >= request.throughSequence()) {
                    throw new IllegalStateException("committed event page exceeds its captured watermark");
                }
                cursor = pageCursor;
            } else {
                if (pageCursor != request.throughSequence()) {
                    throw new IllegalStateException("required committed event range is incomplete");
                }
                cursor = pageCursor;
            }
        }
    }

    /**
     * 按固定事件记录水位分页归集事实；逐页校验游标可防止损坏 Store 造成漏读或无限循环。
     */
    private Set<String> changedPlanIds(AgentStore.PersistedEvent event, AgentIdentity actor) {
        long after = previousRecordSequence(event);
        long through = event.lastRecordSequence();
        if (through < after) {
            throw new IllegalStateException("committed record watermark moved backwards");
        }
        Set<String> planIds = new LinkedHashSet<>();
        while (after < through) {
            ensureNotInterrupted();
            AgentStore.ConversationRecordPage page = store.readConversationRecords(
                    AgentStore.ConversationRecordQuery.withDefaultMaxBytes(event.conversation(), after, through, PAGE_SIZE));
            if (page.records().isEmpty()) {
                throw new IllegalStateException("required committed record range is unavailable");
            }
            if (page.records().size() > PAGE_SIZE) {
                throw new IllegalStateException("committed record page exceeded its bound");
            }
            long pageCursor = after;
            for (AgentStore.ConversationRecord record : page.records()) {
                ensureNotInterrupted();
                if (!event.conversation().equals(record.conversation())
                    || record.sequence() <= pageCursor || record.sequence() > through) {
                    throw new IllegalStateException("committed record page is invalid");
                }
                pageCursor = record.sequence();
                if (!record.execution().equals(event.execution())
                    || !ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                    continue;
                }
                AgentStore.StoredRecord stored = new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                        record.payloadSchemaVersion(), record.sequence(), record.payload(), record.occurredAt());
                io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExtensionFact fact = extensionFacts.decode(stored);
                if (PLAN_NAMESPACE.equals(fact.namespace())) {
                    collectTargets(fact.type(), fact.payload(), actor, planIds);
                }
            }
            if (page.nextSequence() != pageCursor) {
                throw new IllegalStateException("committed record page cursor is invalid");
            }
            if (page.hasMore()) {
                if (pageCursor <= after) {
                    throw new IllegalStateException("committed record page cursor did not advance");
                }
                if (pageCursor >= through) {
                    throw new IllegalStateException("committed record page exceeds its captured watermark");
                }
                after = pageCursor;
            } else {
                if (pageCursor != through) {
                    throw new IllegalStateException("required committed record range is incomplete");
                }
                after = pageCursor;
            }
        }
        return planIds;
    }

    /**
     * 分页期间保留中断标记并退出，避免关闭或取消后继续扫描持久事件与记录。
     */
    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Plan committed event scan was interrupted");
        }
    }

    /**
     * 读取紧邻事件的 record watermark，只有上一提交边界之后的记录才属于本次事务。
     */
    private long previousRecordSequence(AgentStore.PersistedEvent event) {
        if (event.sequence() == 1) {
            return 0;
        }
        AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                event.conversation(), event.sequence() - 2, event.sequence() - 1, 1));
        if (page.events().size() != 1 || page.events().getFirst().sequence() != event.sequence() - 1
            || page.nextSequence() != event.sequence() - 1 || page.hasMore()) {
            throw new IllegalStateException("previous committed event watermark is unavailable");
        }
        return page.events().getFirst().lastRecordSequence();
    }

    /**
     * 只接受已注册的 Plan fact 类型，并把 event actor 与冻结身份交叉核对。
     */
    private void collectTargets(String type, JsonObject payload, AgentIdentity eventActor,
                                Set<String> planIds) {
        switch (type) {
            case "tool-command" -> {
                JsonObject actor = object(payload, "actor");
                verifyActor(actor, eventActor);
                JsonObject binding = object(payload, "binding");
                String plan = nullableText(binding, "planId");
                add(planIds, plan);
            }
            case "tool-attempt" -> {
                verifyActor(payload, eventActor);
                String turnId = text(payload, "turnId");
                if (!eventActor.turnId().equals(turnId)) {
                    throw new IllegalStateException("Plan attempt belongs to a different Turn");
                }
                TurnOrigin origin = TurnOrigin.valueOf(text(payload, "origin"));
                PlanRepository.InternalTurnBinding binding = plans.findInternalTurnBinding(turnId)
                        .orElseThrow(() -> new IllegalStateException("Plan attempt binding is unavailable"));
                if (!binding.origin().equals(origin.name())) {
                    throw new IllegalStateException("Plan attempt origin changed after commit");
                }
                add(planIds, binding.planId());
            }
            default -> {
            }
        }
    }
}



