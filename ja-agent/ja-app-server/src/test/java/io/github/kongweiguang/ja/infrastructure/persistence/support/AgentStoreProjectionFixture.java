// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentContextMapper;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionDriver;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopPersistence;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.turn.TurnExecutionState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.test.ModelPortTestSupport;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentRecordIds;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.infrastructure.tool.JsonSchemaToolArgumentValidator;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionFacts;
import io.github.kongweiguang.ja.conversation.application.gateway.JaAdmissionTransactionParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAdmissionParticipantFactory;

import java.net.URI;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 以真实 Store、typed records/events 和 Ja admission participant 建立投影测试的统一执行路径。 */
public final class AgentStoreProjectionFixture implements AutoCloseable {
    private static final Duration LEASE_LIFETIME = Duration.ofHours(1);
    private final AgentStore store;
    private final AgentStore.ExecutionKey execution;
    private final AgentStore.Lease lease;
    private final ExecutionRecordCodec records;
    private final ExecutionEventCodec events;
    private final TurnExecutionStateCodec cursors;
    private final ExtensionFactRecordCodec extensionFacts;
    private final ExecutionControlCodec controls;
    private final TurnChangeSetCodec changeSets;
    private int commitOrdinal;

    /** fixture 仅持有调用方借入的 Store，不拥有 SQLite DataSource 生命周期。 */
    private AgentStoreProjectionFixture(AgentStore store, AgentStore.ExecutionKey execution,
                                        AgentStore.Lease lease, ExecutionRecordCodec records,
                                        ExecutionEventCodec events, TurnExecutionStateCodec cursors,
                                        ExtensionFactRecordCodec extensionFacts, ExecutionControlCodec controls,
                                        TurnChangeSetCodec changeSets) {
        this.store = Objects.requireNonNull(store, "store");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.lease = Objects.requireNonNull(lease, "lease");
        this.records = Objects.requireNonNull(records, "records");
        this.events = Objects.requireNonNull(events, "events");
        this.cursors = Objects.requireNonNull(cursors, "cursors");
        this.extensionFacts = Objects.requireNonNull(extensionFacts, "extensionFacts");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.changeSets = Objects.requireNonNull(changeSets, "changeSets");
    }

    /** 用真实准入 Driver 冻结绑定和游标，再以 Ja 两阶段参与者提交 Turn 锚点与 USER 投影。 */
    public static AgentStoreProjectionFixture open(AgentStore store, ObjectMapper mapper,
            AgentExecutionRequest request, AdmissionFacts jaFacts, ThreadPreferences preferences,
            long expectedThreadRevision) {
        return open(store, mapper, request, jaFacts, preferences, expectedThreadRevision, null);
    }

    /** 可选业务阶段沿用 Ja 两阶段顺序；核心投影仍由固定 fixture 负责，不允许手工插入执行表。 */
    public static AgentStoreProjectionFixture open(AgentStore store, ObjectMapper mapper,
            AgentExecutionRequest request, AdmissionFacts jaFacts, ThreadPreferences preferences,
            long expectedThreadRevision, JaAdmissionTransactionParticipant businessParticipant) {
        AgentStore requiredStore = Objects.requireNonNull(store, "store");
        ObjectMapper jsonMapper = Objects.requireNonNull(mapper, "mapper");
        AgentExecutionRequest command = Objects.requireNonNull(request, "request");
        AdmissionFacts facts = Objects.requireNonNull(jaFacts, "jaFacts");
        ThreadPreferences frozenPreferences = Objects.requireNonNull(preferences, "preferences");
        if (!command.identity().equals(facts.identity()) || !command.acceptedAt().equals(facts.acceptedAt())
                || expectedThreadRevision != facts.expectedThreadRevision()) {
            throw new IllegalArgumentException("request and Ja admission facts must describe one frozen admission");
        }

        Clock clock = Clock.fixed(command.acceptedAt(), ZoneOffset.UTC);
        JsonValueCodec json = new JacksonJsonValueCodec(jsonMapper);
        StorePayloadCodec records = new StorePayloadCodec(jsonMapper);
        TurnExecutionStateCodec cursors = new TurnExecutionStateCodec(jsonMapper);
        JacksonExecutionEventCodec events = new JacksonExecutionEventCodec(jsonMapper);
        ExtensionFactRecordCodec extensionFacts =
                new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();
        ControlPayloadCodec controls = new ControlPayloadCodec(jsonMapper);
        try (AgentRequestRuntime runtime = runtime(frozenPreferences)) {
            AgentRequestBindingCodec bindings = new AgentRequestBindingCodec();
            AgentExecutionDriver driver = new AgentExecutionDriver(requiredStore,
                    new AgentLoopPersistence(requiredStore, clock), records, cursors, events,
                    new AgentContextMapper(json, records), new JsonSchemaToolArgumentValidator(json),
                    controls, json, extensionFacts,
                    new io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec(json), clock, Duration.ofSeconds(30));
            AgentStore.Admission admission = driver.prepareAdmission(command, runtime,
                    bindings.encode(command, runtime), expectedThreadRevision);
            AgentStoreTransactionParticipant jaRows = new JaAdmissionParticipantFactory().create(facts);
            JaAdmissionTransactionParticipant staged = stagedAdmission(jaRows, businessParticipant);
            requiredStore.admit(admission, staged);
        }

        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(command.identity().tenantId(), command.identity().threadId(), "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation,
                command.identity().turnId());
        Instant leaseNow = Instant.now();
        AgentStore.Lease lease = requiredStore.claimLease(new AgentStore.LeaseRequest(conversation,
                "owner_projection_test", leaseNow, leaseNow.plus(LEASE_LIFETIME)));
        return new AgentStoreProjectionFixture(requiredStore, execution, lease, records, events, cursors,
                extensionFacts, controls, new TurnChangeSetCodec(jsonMapper));
    }

    /** 编码任意 Core execution fact，recordId 由完整执行身份与显式稳定键派生。 */
    public AgentStore.RecordWrite record(String recordType, String stableKey,
                                         ExecutionFact fact, Instant occurredAt) {
        return records.encode(recordId(recordType, stableKey), Objects.requireNonNull(fact, "fact"),
                Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    /** 编码带宿主 namespace 的扩展事实，保持其与普通 Core 事实相同的 Store 事务。 */
    public AgentStore.RecordWrite extensionFact(String recordType, String stableKey,
            String namespace, String type, int schemaVersion,
            io.github.kongweiguang.ja.agent.domain.json.JsonObject payload, Instant occurredAt) {
        return extensionFacts.encode(recordId(recordType, stableKey),
                new io.github.kongweiguang.ja.agent.application.gateway.AgentTool.ExtensionFact(
                namespace, type, schemaVersion, payload), occurredAt);
    }

    /** 仅供确认无工作区副作用的 SQL fixture 使用；成功终态必须显式保存“已知无修改”，不能省略快照事实。 */
    public AgentStore.RecordWrite knownEmptyChangeSet(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        return extensionFact("agent.extension.fact", "ja.turn:change-set:" + execution.turnId(),
                "ja.turn", "change-set", 1, changeSets.writePayload(TurnChangeSet.emptyComplete()), occurredAt);
    }

    /** 暴露精确记录身份，供 ModelCompleted/Terminal 等事件引用同事务内真实事实。 */
    public String recordId(String recordType, String stableKey) {
        return AgentRecordIds.derive(execution, Objects.requireNonNull(recordType, "recordType"),
                Objects.requireNonNull(stableKey, "stableKey"));
    }

    /** 以当前 Store 快照 CAS 提交事件、记录和 cursor，禁止测试绕过 lease 或 mutation version。 */
    public AgentStore.CommitReceipt commit(TurnState target, TurnExecutionState next,
            List<? extends ExecutionFact> facts, List<? extends ExecutionEvent> committedEvents, Instant occurredAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(facts, "facts");
        Objects.requireNonNull(committedEvents, "committedEvents");
        Instant at = Objects.requireNonNull(occurredAt, "occurredAt");
        List<AgentStore.RecordWrite> writes = new ArrayList<>(facts.size());
        int factOrdinal = 0;
        for (ExecutionFact fact : facts) {
            writes.add(records.encode(AgentRecordIds.derive(execution, "agent.test.fact",
                    "commit:" + commitOrdinal + ":fact:" + factOrdinal++), fact, at));
        }
        return commitRecords(target, next, writes, committedEvents, at);
    }

    /** 提交预编码的 input/control rows 与普通事实共用同一 Store CAS 和 Ja 参与者。 */
    public AgentStore.CommitReceipt commitRecords(TurnState target, TurnExecutionState next,
            List<AgentStore.RecordWrite> writes, List<? extends ExecutionEvent> committedEvents, Instant occurredAt) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(next, "next");
        List<AgentStore.RecordWrite> persistedRecords = List.copyOf(Objects.requireNonNull(writes, "writes"));
        Objects.requireNonNull(committedEvents, "committedEvents");
        Instant at = Objects.requireNonNull(occurredAt, "occurredAt");
        AgentStore.ConversationSnapshot conversation = store.loadConversation(execution.conversation())
                .orElseThrow(() -> new IllegalStateException("fixture conversation disappeared"));
        AgentStore.ExecutionSnapshot current = store.loadExecution(execution)
                .orElseThrow(() -> new IllegalStateException("fixture execution disappeared"));
        List<ExecutionEvent> values = new ArrayList<>();
        TurnState prior = TurnState.valueOf(current.state());
        if (prior != target) {
            values.add(new ExecutionEvent.StateChanged(prior, target));
        }
        values.addAll(committedEvents);
        List<AgentStore.EventWrite> eventWrites = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            eventWrites.add(events.encode(eventId("commit:" + commitOrdinal + ":event:" + index),
                    values.get(index), at));
        }
        String commitKey = "commit_projection_" + commitOrdinal++;
        AgentStore.CommitIdentity identity = new AgentStore.CommitIdentity(execution, commitKey,
                sha256(execution.turnId() + ":" + commitKey + ":" + target + ":" + at));
        return store.commit(new AgentStore.ExecutionCommit(execution, conversation.revision(), current.state(),
                current.mutationVersion(), lease, cursors.schemaVersion(), target.name(), cursors.write(next),
                persistedRecords, eventWrites, identity, Optional.empty(), at));
    }

    /** 以 checkpoint 事实和精确终结事件共用执行 CAS，投影测试不绕过 Engine 的 Store 提交边界。 */
    public AgentStore.CommitReceipt commitCheckpoint(TurnExecutionState next,
            CheckpointStore.ContextCheckpoint checkpoint, long inputTokensBefore, Instant occurredAt) {
        CheckpointStore.ContextCheckpoint value = Objects.requireNonNull(checkpoint, "checkpoint");
        AgentStore.ConversationSnapshot conversation = conversationSnapshot();
        if (!conversation.key().threadId().equals(value.threadId())
                || conversation.revision() != value.sourceRevision()) {
            throw new IllegalArgumentException("checkpoint source must match the current conversation revision");
        }
        StoreBackedCheckpointStore checkpointStore = new StoreBackedCheckpointStore(store, records);
        AgentStore.RecordWrite record = checkpointStore.stageCheckpoint(execution,
                checkpointStore.read(conversation), value, occurredAt);
        String compactionId = "cmp_" + sha256("projection:" + value.checkpointId()).substring(0, 24);
        ExecutionEvent.ContextCompacted committed = new ExecutionEvent.ContextCompacted(compactionId,
                ExecutionEvent.CompactionOutcome.COMPACTED, value.checkpointId(), record.recordId(),
                value.sourceRevision(), Math.addExact(conversation.revision(), 1), inputTokensBefore,
                value.estimatedTokens());
        return commitRecords(TurnState.RUNNING, next, List.of(record), List.of(committed), occurredAt);
    }

    /** 固定最新 conversation revision 供 checkpoint 源 CAS 使用，禁止按业务时间猜测写入版本。 */
    public AgentStore.ConversationSnapshot conversationSnapshot() {
        return store.loadConversation(execution.conversation())
                .orElseThrow(() -> new IllegalStateException("fixture conversation disappeared"));
    }

    /** 返回通用执行身份，测试可据此精确查询事件与记录而无需 Ja 前缀映射。 */
    public AgentStore.ExecutionKey execution() {
        return execution;
    }

    /** 命令 record 使用与执行循环一致的严格 codec，避免测试手写控制载荷 JSON。 */
    public ExecutionControlCodec controls() {
        return controls;
    }

    /** 使用持久事件 codec 生成供 commitRecords 共事务提交的协议记录。 */
    public AgentStore.EventWrite event(String stableKey, ExecutionEvent event, Instant occurredAt) {
        return events.encode(eventId(stableKey),
                Objects.requireNonNull(event, "event"), Objects.requireNonNull(occurredAt, "occurredAt"));
    }

    /** 按生产事件协议保留 evt_ 身份，避免 Store 事实键被误当成公开事件 ID。 */
    private String eventId(String stableKey) {
        String recordId = AgentRecordIds.derive(execution, "agent.event",
                Objects.requireNonNull(stableKey, "stableKey"));
        return "evt_" + recordId.substring("rec_".length());
    }

    /** 关闭仅释放此 fixture 申请的执行权，不接管调用方传入 Store 的生命周期。 */
    @Override
    public void close() {
        store.releaseLease(lease);
    }

    /** 构造只供 admission 读取 revision 的无 Provider runtime；任何意外模型或附件调用都会失败。 */
    private static AgentRequestRuntime runtime(ThreadPreferences preferences) {
        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                preferences.providerId(), preferences.modelId(), "cfg_projection_test",
                ModelPort.Api.OPENAI_RESPONSES, preferences.modelId(), URI.create("http://127.0.0.1:1"),
                "projection-test-secret", Duration.ofSeconds(1), Duration.ofSeconds(1),
                Set.of(ModelPort.InputModality.TEXT), ModelPort.GenerationOptions.defaults());
        ModelPort model = ModelPortTestSupport.of((request, sink, cancellation) -> CompletableFuture.failedFuture(
                new AssertionError("projection fixture must not call a model provider")));
        AgentPromptSession prompt = (AgentPromptSession) Proxy.newProxyInstance(
                AgentPromptSession.class.getClassLoader(), new Class<?>[]{AgentPromptSession.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "currentRevision" -> "prompt_projection_test";
                    case "activeSkillReferences" -> List.of();
                    case "toString" -> "projection prompt fixture";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError("unexpected prompt call: " + method.getName());
                });
        return new AgentRequestRuntime(configuration, model,
                binding -> { throw new AssertionError("projection fixture must not create a summary model"); },
                new ExtensionRegistry().freeze(), prompt, "a".repeat(64), AgentStoreTransactionParticipant.NONE,
                request -> { throw new AssertionError("projection fixture must not read attachments"); },
                new ToolProjectionLimits(512, 512),
                Map.of("accessMode", preferences.accessMode().name(),
                        "collaborationMode", preferences.collaborationMode().name(),
                        "configGeneration", configuration.configGeneration()), () -> { });
    }

    /** 把宿主 Ja 行创建与额外业务阶段安排在 Core 投影之前后，避免单测偷用多个数据库提交。 */
    private static JaAdmissionTransactionParticipant stagedAdmission(
            AgentStoreTransactionParticipant jaRows, JaAdmissionTransactionParticipant business) {
        return new JaAdmissionTransactionParticipant() {
            /** 在 Core projection 前创建 Thread/Turn 锚点，便于投影器以真实外键进行 CAS。 */
            @Override
            public void beforeProjection(AgentStoreTransactionContext transaction) throws SQLException {
                jaRows.apply(transaction);
                if (business != null) {
                    business.beforeProjection(transaction);
                }
            }

            /** Core USER/Turn 行写入后才应用依赖其消息身份的业务事实。 */
            @Override
            public void afterProjection(AgentStoreTransactionContext transaction) throws SQLException {
                if (business != null) {
                    business.afterProjection(transaction);
                }
            }
        };
    }

    /** 构造稳定 SHA-256 identity，避免测试提交指纹受随机 UUID 或系统编码影响。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}



