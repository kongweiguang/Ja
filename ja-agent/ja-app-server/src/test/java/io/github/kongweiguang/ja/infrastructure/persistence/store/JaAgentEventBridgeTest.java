// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionOption;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestion;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionQuestionType;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxQueueCommandMapper;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证队列消费的跨会话消息只从冻结来源元数据与已接受正文组装。 */
final class JaAgentEventBridgeTest {
    @TempDir
    private Path temporary;

    /** 使用真实 Mapper envelope 验证展示只取正文，同时保留发件快照；不能把模型封装泄漏到 Timeline。
     * @author kongweiguang
     */
    @Test
    void projectsThreadMessageFromFrozenMailboxMetadata() {
        AgentIdentity identity = identity();
        JaAgentEventBridge.MailboxMetadata mailbox = JaAgentEventBridge.MailboxMetadata.from(metadata(), identity);
        UserMessage user = new UserMessage("record_user",
                mailboxModelContent("MESSAGE", "thr_sender", "Sender title", "hello"));

        ThreadSnapshot.ThreadMessageItem item = mailbox.toThreadMessage(identity, user);

        assertEquals(JaMessageIds.toItemId("msg_mailbox"), item.itemId());
        assertEquals(Instant.parse("2026-09-26T12:00:00Z"), item.createdAt());
        assertEquals("turn_child", item.turnId());
        assertEquals("thr_sender", item.sourceThreadId());
        assertEquals("Sender title", item.sourceTitle());
        assertEquals("hello", item.content());
    }

    /** 来源或种类与已冻结 mailbox metadata 不符时必须拒绝，不能猜测或回退为原始 envelope。
     * @author kongweiguang
     */
    @Test
    void rejectsMailboxEnvelopeWithMismatchedSourceOrKind() {
        AgentIdentity identity = identity();
        JaAgentEventBridge.MailboxMetadata mailbox = JaAgentEventBridge.MailboxMetadata.from(metadata(), identity);
        List<UserMessage> mismatches = List.of(
                new UserMessage("record_wrong_kind",
                        mailboxModelContent("FINAL_ANSWER", "thr_sender", "Sender title", "hello")),
                new UserMessage("record_wrong_source",
                        mailboxModelContent("MESSAGE", "thr_other", "Sender title", "hello")));

        for (UserMessage mismatch : mismatches) {
            assertThrows(IllegalStateException.class, () -> mailbox.toThreadMessage(identity, mismatch));
        }
    }

    /** 拒绝把投递到其它 Thread 的队列命令投影进当前执行历史。 */
    @Test
    void rejectsMailboxMetadataForDifferentTargetThread() {
        Map<String, String> values = Map.of(
                "ja.mailbox.messageId", "msg_mailbox",
                "ja.mailbox.messageKind", "MESSAGE",
                "ja.mailbox.senderThreadId", "thr_sender",
                "ja.mailbox.targetThreadId", "thr_other",
                "ja.mailbox.senderTitle", "Sender title",
                "ja.mailbox.createdAt", "2026-09-26T12:00:00Z");

        assertThrows(IllegalStateException.class,
                () -> JaAgentEventBridge.MailboxMetadata.from(values, identity()));
    }

    /** 真实普通输入中的 envelope-like JSON 必须作为用户原文保留，且队列消费和回放都不触发 mailbox 解包。
     * @author kongweiguang
     */
    @Test
    void projectsConsumedFollowUpFromAcceptedEventWithIndependentRecordAndMessageIds() throws Exception {
        AgentIdentity identity = identity();
        String ordinaryJson = "{\"kind\":\"external_thread_message\",\"message\":\"leave unchanged\"}";
        UserContent content = new UserContent(List.of(
                new ResourceReferenceContent("workspace", "src/追问.java", "file"), new TextContent(ordinaryJson)));
        UserMessage message = new UserMessage("msg_follow_up", content);
        AgentStore store = inputConsumptionStore(AgentInputQueue.Kind.FOLLOW_UP, message, message);
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch consumed = new CountDownLatch(1);
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(event -> {
            delivered.add(event);
            if (event instanceof TurnEvent.InputConsumed) {
                consumed.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by durable projection tests");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);

        try (engine; bridge) {
            try (AutoCloseable first = bridge.attach(identity, sink)) {
                assertTrue(consumed.await(3, TimeUnit.SECONDS), "accepted follow-up was not projected");
                bridge.onReplayRequired(new io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener
                        .ReplayRequired(identity, 0, 2, "late_input_notification"));
            }
            try (AutoCloseable resumed = bridge.attach(identity, sink)) {
                bridge.onReplayRequired(new io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener
                        .ReplayRequired(identity, 0, 2, "duplicate_input_notification"));
            }

            assertEquals(2, delivered.size());
            assertTrue(delivered.getFirst() instanceof TurnEvent.InputQueueChanged);
            TurnEvent.InputConsumed input = (TurnEvent.InputConsumed) delivered.getLast();
            assertEquals("input_follow_up", input.input().inputId());
            assertEquals(content, input.input().content());
            assertEquals(content, input.userItem().content());
            assertEquals(ordinaryJson, input.userItem().content().text());
            assertEquals(JaMessageIds.toItemId(message.messageId()), input.userItem().itemId());
            assertEquals(Instant.parse("2026-10-03T00:00:04Z"), input.userItem().createdAt());
            assertEquals(2, input.inputQueue().revision());
            assertEquals(List.of(), input.inputQueue().items());
            assertTrue(input.inputQueue().accepting());
        }
    }

    /** MESSAGE 消费只展示真实 mailbox 正文（即使正文自身是 JSON），不展示模型上下文 envelope。
     * @author kongweiguang
     */
    @Test
    void projectsAcceptedMailboxAsDistinctStableMessageAndQueueEvents() throws Exception {
        AgentIdentity identity = identity();
        String originalMessage = "{\"kind\":\"source-json\",\"payload\":{\"body\":\"hello\"}}";
        UserMessage message = new UserMessage("msg_accepted_mailbox",
                mailboxModelContent("MESSAGE", "thr_sender", "Sender title", originalMessage));
        AgentStore store = inputConsumptionStore(AgentInputQueue.Kind.MESSAGE, message, message);
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch projected = new CountDownLatch(3);
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(event -> {
            delivered.add(event);
            projected.countDown();
            return CompletableFuture.completedFuture(null);
        });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by durable projection tests");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);

        try (engine; bridge) {
            try (AutoCloseable first = bridge.attach(identity, sink)) {
                assertTrue(projected.await(3, TimeUnit.SECONDS), "mailbox consumption did not publish both frames");
            }
            assertEquals(3, delivered.size());
            assertFalse(delivered.stream().anyMatch(TurnEvent.InputConsumed.class::isInstance));
            TurnEvent.MessagesReceived received = (TurnEvent.MessagesReceived) delivered.get(1);
            TurnEvent.InputQueueChanged queue = (TurnEvent.InputQueueChanged) delivered.get(2);
            assertEquals(JaMessageIds.toItemId("msg_mailbox"), received.items().getFirst().itemId());
            assertEquals("thr_sender", received.items().getFirst().sourceThreadId());
            assertEquals(originalMessage, received.items().getFirst().content());
            assertNotEquals(received.context().eventId(), queue.context().eventId());
            assertEquals(received.context().threadRevision(), queue.context().threadRevision());
            assertEquals(received.context().turnMutationVersion(), queue.context().turnMutationVersion());
            assertEquals(received.context().occurredAt(), queue.context().occurredAt());
            assertEquals(2, queue.inputQueue().revision());
            assertEquals(List.of(), queue.inputQueue().items());

            List<TurnEvent> replayed = new CopyOnWriteArrayList<>();
            CountDownLatch fullReplay = new CountDownLatch(3);
            try (AutoCloseable replay = bridge.attach(identity, ConversationTurnEventSink.turnEventsOnly(event -> {
                replayed.add(event);
                fullReplay.countDown();
                return CompletableFuture.completedFuture(null);
            }))) {
                assertTrue(fullReplay.await(3, TimeUnit.SECONDS), "new sink did not replay the mailbox transaction");
            }
            assertEquals(delivered, replayed);
        }
    }

    /** 相同正文不能掩盖错误消息身份，相同身份也不能掩盖被篡改的资源材料；坏引用只触发真实投影阻塞。 */
    @Test
    void rejectsAcceptedInputWhoseQueuedIdentityOrContentDiffers() throws Exception {
        UserContent content = new UserContent(List.of(
                new ResourceReferenceContent("workspace", "src/original.java", "file"), new TextContent("check file")));
        UserMessage accepted = new UserMessage("msg_accepted", content);
        List<UserMessage> invalidSources = List.of(new UserMessage("msg_other", content),
                new UserMessage(accepted.messageId(), new UserContent(List.of(
                        new ResourceReferenceContent("workspace", "src/other.java", "file"), new TextContent("check file")))));
        for (UserMessage queued : invalidSources) {
            AgentStore store = inputConsumptionStore(AgentInputQueue.Kind.FOLLOW_UP, queued, accepted);
            JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
            List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch failed = new CountDownLatch(1);
            TurnEventSink sink = new TurnEventSink() {
                /** 收集已确认帧，坏消费不能向 Timeline 发布局部成功。 */
                @Override
                public CompletionStage<Void> publish(TurnEvent event) {
                    delivered.add(event);
                    return CompletableFuture.completedFuture(null);
                }

                /** 持久事实不匹配必须通知客户端重同步，而非跳过这次消费。 */
                @Override
                public CompletionStage<Void> publishProjectionFailure(TurnProjectionFailure failure) {
                    failed.countDown();
                    return CompletableFuture.completedFuture(null);
                }
            };
            AgentEngine engine = new AgentEngineBuilder().store(store)
                    .runtimeResolver((request, extensions, recovered) -> {
                        throw new IllegalStateException("runtime is not used by durable projection tests");
                    }).build();
            JaAgentEventBridge bridge = eventBridge(store, codec);
            bridge.bindEngine(engine);
            try (engine; bridge) {
                bridge.attach(identity(), ConversationTurnEventSink.turnEventsOnly(sink));
                assertTrue(failed.await(3, TimeUnit.SECONDS), "invalid consumed input was not rejected");
                assertEquals(1, delivered.size());
                assertTrue(delivered.getFirst() instanceof TurnEvent.InputQueueChanged);
            }
        }
    }

    /** 独立类型不能合并，仍须在一批内排空；估算闸门让突发入队确定发生而不依赖线程速度。 */
    @Test
    void drainsBurstWithoutWaitingForDurableEventsBetweenEachDelta() throws Exception {
        AgentIdentity identity = identity();
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        AtomicInteger emptyPageReads = new AtomicInteger();
        AgentStore store = draftStore(codec, false, emptyPageReads);
        CompletableFuture<Void> allowEstimate = new CompletableFuture<>();
        CountDownLatch estimateEntered = new CountDownLatch(1);
        CountDownLatch deltas = new CountDownLatch(96);
        AtomicInteger readsAtLastDelta = new AtomicInteger(-1);
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(event -> {
            delivered.add(event);
            if (event instanceof TurnEvent.ContextUsageEstimated) {
                estimateEntered.countDown();
                return allowEstimate;
            }
            if (event instanceof TurnEvent.TextDelta || event instanceof TurnEvent.ReasoningSummaryDelta) {
                if (deltas.getCount() == 1) {
                    readsAtLastDelta.set(emptyPageReads.get());
                }
                deltas.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection test does not invoke a model");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);
        try (engine; bridge) {
            try (AutoCloseable attachment = bridge.attach(identity, sink)) {
                try {
                    assertTrue(estimateEntered.await(3, TimeUnit.SECONDS));
                    AgentStreamSubscription stream = attachmentStream(bridge, identity);
                    for (int sequence = 1; sequence <= 96; sequence++) {
                        stream.publish(burstDelta(identity, sequence));
                    }
                    assertEquals(AgentStreamSubscription.State.OPEN, stream.state());
                    allowEstimate.complete(null);
                    assertTrue(deltas.await(3, TimeUnit.SECONDS), "burst was paced by empty durable pages");
                    assertTrue(readsAtLastDelta.get() < 8, "projection read durable history for each tiny delta");
                    assertEquals(97, delivered.size());
                    for (int index = 1; index <= 96; index++) {
                        assertEquals(index % 2 == 0 ? TurnEvent.TextDelta.class : TurnEvent.ReasoningSummaryDelta.class,
                                delivered.get(index).getClass());
                    }
                } finally {
                    allowEstimate.complete(null);
                }
            }
        }
    }

    /** 真正超载必须通知一次并继续持久投影；不能将草稿断口静默保留或伪造成业务执行失败。 */
    @Test
    void reportsDraftOverflowOnceAndContinuesDurableProjection() throws Exception {
        AgentIdentity identity = identity();
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore store = draftStore(codec, true, new AtomicInteger());
        CompletableFuture<Void> allowEstimate = new CompletableFuture<>();
        CountDownLatch estimateEntered = new CountDownLatch(1);
        CountDownLatch durableDelivered = new CountDownLatch(1);
        List<String> delivered = new CopyOnWriteArrayList<>();
        TurnEventSink turnSink = new TurnEventSink() {
            /** 持久估算先确认，随后状态帧必须越过草稿故障正常到达。 */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                if (event instanceof TurnEvent.ContextUsageEstimated) {
                    estimateEntered.countDown();
                    return allowEstimate;
                }
                delivered.add(event.getClass().getSimpleName());
                if (event instanceof TurnEvent.StateChanged) {
                    durableDelivered.countDown();
                }
                return CompletableFuture.completedFuture(null);
            }

            /** 使用已有恢复契约并核对完整身份，不新增一套跨端状态或协议枚举。 */
            @Override
            public CompletionStage<Void> publishProjectionFailure(TurnProjectionFailure failure) {
                assertEquals(identity.threadId(), failure.threadId());
                assertEquals(identity.turnId(), failure.turnId());
                assertEquals(TurnProjectionFailure.Reason.PROJECTION_FAILED, failure.reason());
                delivered.add("resync");
                return CompletableFuture.completedFuture(null);
            }
        };
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection test does not invoke a model");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);
        try (engine; bridge) {
            try (AutoCloseable attachment = bridge.attach(identity, ConversationTurnEventSink.turnEventsOnly(turnSink))) {
                try {
                    assertTrue(estimateEntered.await(3, TimeUnit.SECONDS));
                    AgentStreamSubscription stream = attachmentStream(bridge, identity);
                    for (int sequence = 1; sequence <= 130; sequence++) {
                        stream.publish(burstDelta(identity, sequence));
                    }
                    assertEquals(AgentStreamSubscription.State.RESYNC_REQUIRED, stream.state());
                    allowEstimate.complete(null);
                    assertTrue(durableDelivered.await(3, TimeUnit.SECONDS));
                    assertEquals(List.of("resync", "StateChanged"), delivered);
                    assertEquals(AgentStreamSubscription.State.CLOSED, stream.state());
                } finally {
                    allowEstimate.complete(null);
                }
            }
        }
    }

    /** 交错类型刻意禁止订阅合并，验证 Bridge 的消费节奏而非只验证上游批量优化。 */
    private static io.github.kongweiguang.ja.agent.application.api.TurnEvent burstDelta(
            AgentIdentity identity, long sequence) {
        io.github.kongweiguang.ja.agent.application.api.TurnEvent.Context context =
                new io.github.kongweiguang.ja.agent.application.api.TurnEvent.Context(
                identity, "modelreq_burst", sequence, Instant.EPOCH);
        return sequence % 2 == 0
                ? new io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta(context, "正文😀")
                : new io.github.kongweiguang.ja.agent.application.api.TurnEvent.ReasoningSummaryDelta(context, "摘要");
    }

    /** 保留真实 usage 编码与估算水位，仅为投影调度统计空页，避免模型或网络影响队列回归。 */
    private static AgentStore draftStore(JacksonExecutionEventCodec codec, boolean includeLaterState,
                                          AtomicInteger emptyPageReads) {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        Instant occurredAt = Instant.EPOCH;
        StorePayloadCodec records = new StorePayloadCodec(new ObjectMapper());
        AgentStore.RecordWrite usage = records.encode("rec_usage_burst",
                usage("modelreq_burst", 1, 1, 24_000), occurredAt);
        List<AgentStore.PersistedEvent> events = new ArrayList<>();
        events.add(persistedEvent(codec, conversation, identity, 1, 1, "evt_burst_estimate",
                new ExecutionEvent.ContextUsageEstimated(usage.recordId()), occurredAt));
        if (includeLaterState) {
            events.add(persistedEvent(codec, conversation, identity, 2, 1, "evt_burst_state",
                    new ExecutionEvent.StateChanged(TurnState.RUNNING, TurnState.SUSPENDED), occurredAt));
        }
        AgentStore delegate = eventStoreWithRecords(conversation, events, List.of(storedRecord(usage, 1)),
                occurredAt, "unused", new CountDownLatch(0), new CountDownLatch(0));
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (result instanceof AgentStore.EventPage page && page.events().isEmpty()) {
                            emptyPageReads.incrementAndGet();
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    /** 持久估算先于同请求草稿发布，上一轮迟到尾片也不得被下一轮估算越过；同 sink 恢复保留该门槛。 */
    @Test
    void ordersFastDraftsAfterTheirEstimateAndBeforeTheNextRequestEstimate() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-09-29T00:00:00Z");
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        StorePayloadCodec recordCodec = new StorePayloadCodec(new ObjectMapper());
        AgentStore.RecordWrite firstUsage = recordCodec.encode("rec_usage_round_one",
                usage("modelreq_round_one", 1, 1, 24_000), occurredAt);
        AgentStore.RecordWrite firstMessage = recordCodec.encode("rec_message_round_one",
                new ExecutionFacts.AssistantMessage("msg_round_one",
                        new ModelMessage(ModelRole.ASSISTANT, List.of(new TextContent("first round"))),
                        "first round", null, 1), occurredAt);
        AgentStore.RecordWrite secondUsage = recordCodec.encode("rec_usage_round_two",
                usage("modelreq_round_two", 2, 2, 31_000), occurredAt.plusSeconds(1));
        List<AgentStore.StoredRecord> records = List.of(
                storedRecord(firstUsage, 1), storedRecord(firstMessage, 2), storedRecord(secondUsage, 3));
        List<AgentStore.PersistedEvent> events = List.of(
                persistedEvent(eventCodec, conversation, identity, 1, 1, "evt_estimate_round_one",
                        new ExecutionEvent.ContextUsageEstimated(firstUsage.recordId()), occurredAt),
                persistedEvent(eventCodec, conversation, identity, 2, 2, "evt_model_round_one",
                        new ExecutionEvent.ModelCompleted(firstMessage.recordId(), 1, List.of(),
                                firstUsage.recordId()), occurredAt),
                persistedEvent(eventCodec, conversation, identity, 3, 3, "evt_estimate_round_two",
                        new ExecutionEvent.ContextUsageEstimated(secondUsage.recordId()), occurredAt.plusSeconds(1)));
        CountDownLatch modelMessageRead = new CountDownLatch(1);
        CountDownLatch allowModelMessageRead = new CountDownLatch(1);
        AgentStore store = eventStoreWithRecords(conversation, events, records, occurredAt,
                firstMessage.recordId(), modelMessageRead, allowModelMessageRead);
        CopyOnWriteArrayList<String> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch firstEstimate = new CountDownLatch(1);
        CountDownLatch secondEstimate = new CountDownLatch(1);
        CountDownLatch priorRoundTail = new CountDownLatch(1);
        CountDownLatch resumedRoundDelta = new CountDownLatch(1);
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(event -> {
            if (event instanceof TurnEvent.ContextUsageEstimated estimate) {
                String requestId = estimate.usage().modelRequestId();
                delivered.add("estimate:" + requestId);
                if (requestId.equals("modelreq_round_one")) {
                    firstEstimate.countDown();
                } else if (requestId.equals("modelreq_round_two")) {
                    secondEstimate.countDown();
                }
            } else if (event instanceof TurnEvent.TextDelta delta) {
                delivered.add("delta:" + delta.modelRequestId());
                if (delta.modelRequestId().equals("modelreq_round_one")) {
                    priorRoundTail.countDown();
                } else if (delta.modelRequestId().equals("modelreq_round_two")) {
                    resumedRoundDelta.countDown();
                }
            }
            return CompletableFuture.completedFuture(null);
        });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by durable projection tests");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec);
        bridge.bindEngine(engine);

        try (engine; bridge) {
            try (AutoCloseable firstAttachment = bridge.attach(identity, sink)) {
                try {
                    assertTrue(firstEstimate.await(3, TimeUnit.SECONDS), "first request estimate was not projected");
                    assertTrue(modelMessageRead.await(3, TimeUnit.SECONDS),
                            "first round completion did not read its persisted message");
                    AgentStreamSubscription stream = attachmentStream(bridge, identity);
                    stream.publish(new io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta(
                            new io.github.kongweiguang.ja.agent.application.api.TurnEvent.Context(identity,
                                    "modelreq_round_one", 1, occurredAt), "late first-round tail"));
                } finally {
                    allowModelMessageRead.countDown();
                }
                assertTrue(priorRoundTail.await(3, TimeUnit.SECONDS), "previous round tail delta was not projected");
                assertTrue(secondEstimate.await(3, TimeUnit.SECONDS), "second request estimate was not projected");
                assertEquals(List.of("estimate:modelreq_round_one", "delta:modelreq_round_one",
                        "estimate:modelreq_round_two"), delivered);
            }

            try (AutoCloseable resumedAttachment = bridge.attach(identity, sink)) {
                AgentStreamSubscription stream = attachmentStream(bridge, identity);
                stream.publish(new io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta(
                        new io.github.kongweiguang.ja.agent.application.api.TurnEvent.Context(identity,
                                "modelreq_round_two", 1, occurredAt.plusSeconds(1)), "resumed second round"));
                assertTrue(resumedRoundDelta.await(3, TimeUnit.SECONDS),
                        "restored estimate checkpoint did not release the second request delta");
                assertEquals(List.of("estimate:modelreq_round_one", "delta:modelreq_round_one",
                        "estimate:modelreq_round_two", "delta:modelreq_round_two"), delivered);
            }
        } finally {
            allowModelMessageRead.countDown();
        }
    }

    /** 首次 ModelStep 会把 QUEUED→RUNNING 与 ContextUsageEstimated 原子提交，Timeline 必须按 durable 序号收到两帧。
     * @author kongweiguang
     */
    @Test
    void projectsFirstModelDispatchStateBeforeContextEstimate() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-10-06T00:00:00Z");
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        StorePayloadCodec recordCodec = new StorePayloadCodec(new ObjectMapper());
        AgentStore.RecordWrite usageRecord = recordCodec.encode("record_dispatch_usage",
                usage("modelreq_dispatch", 1, 1, 24_000), occurredAt);
        List<AgentStore.PersistedEvent> events = List.of(
                persistedCommitEvent(eventCodec, conversation, identity, 1, 1, 1, 1, "event_dispatch_state",
                        new ExecutionEvent.StateChanged(TurnState.QUEUED, TurnState.RUNNING), occurredAt),
                persistedCommitEvent(eventCodec, conversation, identity, 2, 1, 1, 1, "event_dispatch_estimate",
                        new ExecutionEvent.ContextUsageEstimated(usageRecord.recordId()), occurredAt));
        AgentStore store = eventStoreWithRecords(conversation, events, List.of(storedRecord(usageRecord, 1)),
                occurredAt, "", new CountDownLatch(0), new CountDownLatch(0));
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch projected = new CountDownLatch(2);
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(event -> {
            delivered.add(event);
            projected.countDown();
            return CompletableFuture.completedFuture(null);
        });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection fixture never executes a Provider request");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec);
        bridge.bindEngine(engine);

        try (engine; bridge; AutoCloseable ignored = bridge.attach(identity, sink)) {
            assertTrue(projected.await(3, TimeUnit.SECONDS), "dispatch commit did not project both durable events");
        }

        assertEquals(List.of(TurnEvent.StateChanged.class, TurnEvent.ContextUsageEstimated.class),
                delivered.stream().map(Object::getClass).toList());
        TurnEvent.StateChanged state = (TurnEvent.StateChanged) delivered.getFirst();
        assertEquals(TurnState.QUEUED, state.from());
        assertEquals(TurnState.RUNNING, state.to());
        assertEquals("modelreq_dispatch", ((TurnEvent.ContextUsageEstimated) delivered.getLast())
                .usage().modelRequestId());
    }

    /** 准入 mutation v1 已消费 record watermark 3 后，state-only mutation v2 即使水位相同也必须送达。
     * @author kongweiguang
     */
    @Test
    void projectsDispatchStateAfterAdmissionAtSameRecordWatermark() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-10-06T00:00:00Z");
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        StorePayloadCodec recordCodec = new StorePayloadCodec(new ObjectMapper());
        UserContent content = new UserContent(List.of(new TextContent("开始执行")));
        List<AgentStore.StoredRecord> records = List.of(
                storedRecord(recordCodec.encodeUser("record_dispatch_input", "message_dispatch_input",
                        content, occurredAt), 1),
                storedRecord(recordCodec.encode("record_dispatch_context_one",
                        usage("modelreq_dispatch_context_one", 1, 1, 1_000), occurredAt), 2),
                storedRecord(recordCodec.encode("record_dispatch_context_two",
                        usage("modelreq_dispatch_context_two", 2, 1, 2_000), occurredAt), 3));
        List<AgentStore.PersistedEvent> events = List.of(
                persistedCommitEvent(eventCodec, conversation, identity, 1, 1, 1, 3,
                        "event_dispatch_admitted",
                        new ExecutionEvent.Admitted("USER_INPUT", "message_dispatch_input", null), occurredAt),
                persistedCommitEvent(eventCodec, conversation, identity, 2, 2, 2, 3,
                        "event_dispatch_state",
                        new ExecutionEvent.StateChanged(TurnState.QUEUED, TurnState.RUNNING), occurredAt));
        AgentStore store = eventStoreWithRecords(conversation, events, records, occurredAt, "",
                new CountDownLatch(0), new CountDownLatch(0));
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch projected = new CountDownLatch(1);
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection fixture never executes a Provider request");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec);
        bridge.bindEngine(engine);

        try (engine; bridge; AutoCloseable ignored = bridge.attach(identity,
                ConversationTurnEventSink.turnEventsOnly(event -> {
                    delivered.add(event);
                    projected.countDown();
                    return CompletableFuture.completedFuture(null);
                }))) {
            assertTrue(projected.await(3, TimeUnit.SECONDS),
                    "state-only mutation at the admitted record watermark was not projected");
        }

        assertEquals(1, delivered.size(), "admission has no Timeline frame; only the state event is projected");
        TurnEvent.StateChanged state = (TurnEvent.StateChanged) delivered.getFirst();
        assertEquals(JaAgentEventBridge.projectedEventId(identity.tenantId(), identity.threadId(), identity.turnId(),
                "event_dispatch_state"), state.context().eventId());
        assertEquals(TurnState.QUEUED, state.from());
        assertEquals(TurnState.RUNNING, state.to());
    }

    /** 普通澄清的 wait 投影为空，仍须暴露同事务 WAITING_INPUT 迁移，而不能将其随 null 一起吞掉。
     * @author kongweiguang
     */
    @Test
    void projectsClarificationStateWhenWaitHasNoApprovalProjection() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-10-06T00:00:00Z");
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        InteractionWait wait = wait("interaction_clarification", null, "clarification", occurredAt);
        AgentStore.RecordWrite waitRecord = controls.encodeWait("record_clarification", wait, occurredAt);
        List<AgentStore.PersistedEvent> events = List.of(
                persistedCommitEvent(eventCodec, conversation, identity, 1, 1, 1, 1, "event_clarification_state",
                        new ExecutionEvent.StateChanged(TurnState.RUNNING, TurnState.WAITING_INPUT), occurredAt),
                persistedCommitEvent(eventCodec, conversation, identity, 2, 1, 1, 1, "event_clarification_wait",
                        new ExecutionEvent.WaitRequested(wait.interactionId(), "clarification", waitRecord.recordId()),
                        occurredAt));
        AgentStore store = eventStoreWithRecords(conversation, events, List.of(storedRecord(waitRecord, 1)),
                occurredAt, "", new CountDownLatch(0), new CountDownLatch(0));
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch projected = new CountDownLatch(1);
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection fixture never executes a Provider request");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec);
        bridge.bindEngine(engine);

        try (engine; bridge; AutoCloseable ignored = bridge.attach(identity,
                ConversationTurnEventSink.turnEventsOnly(event -> {
                    delivered.add(event);
                    projected.countDown();
                    return CompletableFuture.completedFuture(null);
                }))) {
            assertTrue(projected.await(3, TimeUnit.SECONDS), "clarification state transition was discarded");
        }

        assertEquals(1, delivered.size());
        TurnEvent.StateChanged state = (TurnEvent.StateChanged) delivered.getFirst();
        assertEquals(TurnState.RUNNING, state.from());
        assertEquals(TurnState.WAITING_INPUT, state.to());
    }

    /** 审批 wait 的状态与卡片都来自同一持久批次；状态帧必须先到，Task 侧才可只提交一次审批 Activity。
     * @author kongweiguang
     */
    @Test
    void ordersApprovalStateBeforeApprovalRequested() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-10-06T00:00:00Z");
        Instant expiresAt = occurredAt.plusSeconds(300);
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        StorePayloadCodec recordCodec = new StorePayloadCodec(new ObjectMapper());
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        ToolPresentation presentation = pendingPresentation();
        String callId = "call_approval_projection";
        ModelMessage assistantMessage = new ModelMessage(ModelRole.ASSISTANT,
                List.of(new TextContent("需要审批"), new ToolCallContent(callId, "exec_command",
                        io.github.kongweiguang.ja.agent.domain.json.JsonObject.empty())));
        AgentStore.RecordWrite assistantRecord = recordCodec.encode("record_approval_assistant",
                new ExecutionFacts.AssistantMessage("message_approval", assistantMessage, "需要审批", null, 1),
                occurredAt);
        AgentStore.RecordWrite usageRecord = recordCodec.encode("record_approval_usage",
                usage("modelreq_approval", 1, 1, 24_000), occurredAt);
        AgentStore.PersistedEvent modelEvent = persistedCommitEvent(eventCodec, conversation, identity, 1, 1, 1, 2,
                "event_approval_model", new ExecutionEvent.ModelCompleted(assistantRecord.recordId(), 1,
                        List.of(callId), usageRecord.recordId()), occurredAt);
        ToolPresentationCodec presentationCodec = new ToolPresentationCodec(new ObjectMapper());
        AgentStore.RecordWrite preparedPresentation = new ExtensionFactRecordCodec().encode(
                "record_approval_presentation", new AgentTool.ExtensionFact("ja.tool", "presentation", 1,
                        JsonObjects.builder().putText("callId", callId).putNumber("revision", 0)
                                .putText("state", "PREPARED")
                                .putText("presentationJson", presentationCodec.write(presentation)).build()),
                occurredAt.plusSeconds(1));
        AgentStore.PersistedEvent startedEvent = persistedCommitEvent(eventCodec, conversation, identity, 2, 2, 2,
                3, "event_approval_tool_started",
                new ExecutionEvent.ToolStarted(callId, "batch_approval", "exec_command", 0), occurredAt.plusSeconds(1));
        InteractionWait wait = wait("appr_approval_projection", callId, "approval", occurredAt.plusSeconds(2));
        AgentStore.RecordWrite waitRecord = controls.encodeWait("record_approval_wait", wait, occurredAt.plusSeconds(2));
        AgentStore.RecordWrite approvalRecord = recordCodec.encode("record_approval_fact",
                new ExecutionFacts.Approval(wait.interactionId(), callId, null, expiresAt), occurredAt.plusSeconds(2));
        AgentStore.PersistedEvent stateEvent = persistedCommitEvent(eventCodec, conversation, identity, 3, 3, 3, 5,
                "event_approval_state",
                new ExecutionEvent.StateChanged(TurnState.RUNNING, TurnState.WAITING_APPROVAL), occurredAt.plusSeconds(2));
        AgentStore.PersistedEvent waitEvent = persistedCommitEvent(eventCodec, conversation, identity, 4, 3, 3, 5,
                "event_approval_wait", new ExecutionEvent.WaitRequested(wait.interactionId(), "approval",
                        waitRecord.recordId()), occurredAt.plusSeconds(2));
        List<AgentStore.PersistedEvent> events = List.of(modelEvent, startedEvent, stateEvent, waitEvent);
        List<AgentStore.StoredRecord> records = List.of(storedRecord(assistantRecord, 1), storedRecord(usageRecord, 2),
                storedRecord(preparedPresentation, 3), storedRecord(waitRecord, 4), storedRecord(approvalRecord, 5));
        AgentStore store = eventStoreWithRecords(conversation, events, records, occurredAt, "",
                new CountDownLatch(0), new CountDownLatch(0));
        List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
        CountDownLatch projected = new CountDownLatch(4);
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("projection fixture never executes a Provider request");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec,
                (execution, messageId, eventId, sequence) -> List.of(new ToolCallPreviewReadPort.ToolCallPreview(
                        callId, 0, "exec_command", presentationCodec.write(presentation), eventId, sequence)));
        bridge.bindEngine(engine);

        try (engine; bridge; AutoCloseable ignored = bridge.attach(identity,
                ConversationTurnEventSink.turnEventsOnly(event -> {
                    delivered.add(event);
                    projected.countDown();
                    return CompletableFuture.completedFuture(null);
                }))) {
            assertTrue(projected.await(3, TimeUnit.SECONDS), "approval commit was not completely projected");
        }

        assertEquals(4, delivered.size());
        assertTrue(delivered.get(0) instanceof TurnEvent.ModelStepCommitted);
        assertTrue(delivered.get(1) instanceof TurnEvent.ToolStarted);
        assertTrue(delivered.get(2) instanceof TurnEvent.StateChanged);
        assertTrue(delivered.get(3) instanceof TurnEvent.ApprovalRequested);
        TurnEvent.StateChanged state = (TurnEvent.StateChanged) delivered.get(2);
        assertEquals(TurnState.RUNNING, state.from());
        assertEquals(TurnState.WAITING_APPROVAL, state.to());
        assertEquals(wait.interactionId(), ((TurnEvent.ApprovalRequested) delivered.get(3)).approvalId());
    }

    /**
     * 首轮准入命令与后续消费分属两个 watermark，增量重放仍保留原始 mailbox 来源；夹具复用真实 Mapper 封装以锁定当前协议。
     * @author kongweiguang
     */
    @Test
    void replaysAdmittedMailboxBeforeConsumingFromMiddleWatermark() {
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        JaAgentEventBridge.InputQueueProjection projection =
                new JaAgentEventBridge.InputQueueProjection(identity());
        Instant admittedAt = Instant.parse("2026-09-26T12:00:00Z");
        AgentInputQueue.Command enqueue = new AgentInputQueue.Command("command_enqueue", AgentInputQueue.Action.ENQUEUE,
                "input_mailbox", AgentInputQueue.Kind.MESSAGE, 0, 1, "record_queued", null, admittedAt, metadata());

        projection.apply(List.of(storedCommand(controls, enqueue, 1)), controls);

        assertEquals(1, projection.queue().revision());
        assertEquals("input_mailbox", projection.queue().entries().getFirst().inputId());
        assertEquals(AgentInputQueue.Kind.MESSAGE, projection.queue().entries().getFirst().kind());

        AgentInputQueue.Command consume = new AgentInputQueue.Command("command_consume", AgentInputQueue.Action.CONSUME,
                "input_mailbox", null, 1, 2, null, null, admittedAt.plusSeconds(4), Map.of());
        AgentStore.StoredRecord consumeRecord = storedCommand(controls, consume, 3);
        assertThrows(IllegalStateException.class,
                () -> new JaAgentEventBridge.InputQueueProjection(identity()).apply(List.of(consumeRecord), controls));
        projection.apply(List.of(consumeRecord), controls);
        JaAgentEventBridge.ConsumedInput input = projection.consume("input_mailbox", 2);
        UserMessage accepted = new UserMessage("record_accepted",
                mailboxModelContent("MESSAGE", "thr_sender", "Sender title", "hello from another thread"));

        ThreadSnapshot.ThreadMessageItem item = input.mailbox().toThreadMessage(identity(), accepted);

        assertEquals("record_queued", input.entry().messageRecordId());
        assertEquals(JaMessageIds.toItemId("msg_mailbox"), item.itemId());
        assertEquals("thr_sender", item.sourceThreadId());
        assertEquals("Sender title", item.sourceTitle());
        assertEquals("hello from another thread", item.content());
        assertEquals(List.of(), projection.queue().entries());
    }

    /** Ja 投影只识别当前 command；退役快照失败关闭且不扩大为全 record 类型白名单。
     * @author kongweiguang
     */
    @Test
    void rejectsRetiredQueueSnapshotAndIgnoresCustomStoreRecords() {
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        JaAgentEventBridge.InputQueueProjection projection =
                new JaAgentEventBridge.InputQueueProjection(identity());
        Instant occurredAt = Instant.parse("2026-09-26T12:00:00Z");
        AgentStore.StoredRecord extension = new AgentStore.StoredRecord("record_vendor_extension",
                "vendor.custom.fact", 1, 1, JsonObjects.builder().build(), occurredAt);

        projection.apply(List.of(extension), controls);
        assertEquals(0, projection.queue().revision());

        AgentStore.StoredRecord retired = new AgentStore.StoredRecord("record_retired_queue_snapshot",
                "agent.input.queue.snapshot", 1, 2, JsonObjects.builder().build(), occurredAt);
        assertThrows(IllegalStateException.class, () -> projection.apply(List.of(retired), controls));
        assertEquals(0, projection.queue().revision());
    }

    /** sink 只确认此前帧时保存精确续送位置，重试不能重发已确认帧或跳过未确认帧。 */
    @Test
    void resumesAfterConfirmedPrefixWhenFollowingSinkFrameFails() {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        AgentStore.PersistedEvent commit = new AgentStore.PersistedEvent(conversation, execution,
                "evt_commit", "agent.execution.state.changed", 1, 1, 1, 1, 0,
                JsonObjects.builder().build(), Instant.EPOCH);
        TurnEvent.Context firstContext = new TurnEvent.Context("evt_first", identity.threadId(),
                identity.turnId(), 1, 0, Instant.EPOCH);
        TurnEvent.Context secondContext = new TurnEvent.Context("evt_second", identity.threadId(),
                identity.turnId(), 1, 0, Instant.EPOCH);
        JaAgentEventBridge.PendingDelivery start = new JaAgentEventBridge.PendingDelivery(commit, 0, 1,
                List.of(new TurnEvent.StateChanged(firstContext, TurnState.QUEUED, TurnState.RUNNING),
                        new TurnEvent.RetryStarted(secondContext, 2)), 0);
        AtomicReference<JaAgentEventBridge.PendingDelivery> checkpoint = new AtomicReference<>(start);
        List<String> attempted = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> JaAgentEventBridge.publishPending(start, event -> {
            attempted.add(event.context().eventId());
            if (event.context().eventId().equals("evt_second")) {
                throw new IllegalStateException("sink unavailable");
            }
        }, checkpoint::set));

        assertEquals(1, checkpoint.get().nextEventIndex());
        JaAgentEventBridge.PendingDelivery complete = JaAgentEventBridge.publishPending(checkpoint.get(),
                event -> attempted.add(event.context().eventId()), checkpoint::set);

        assertEquals(List.of("evt_first", "evt_second", "evt_second"), attempted);
        assertEquals(2, complete.nextEventIndex());
    }

    /** 显式重放必须推进唤醒代数；相同持久水位重试时不依赖新提交通知。 */
    @Test
    void explicitReplayRetriesFailedFrameWithoutANewDurableEvent() throws Exception {
        AgentIdentity identity = identity();
        Instant occurredAt = Instant.parse("2026-09-26T12:00:00Z");
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        JacksonExecutionEventCodec eventCodec = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore.EventWrite stateWrite = eventCodec.encode("evt_state_1",
                new ExecutionEvent.StateChanged(TurnState.QUEUED, TurnState.RUNNING), occurredAt);
        AgentStore.PersistedEvent persisted = new AgentStore.PersistedEvent(conversation, execution,
                stateWrite.eventId(), stateWrite.eventType(), stateWrite.payloadSchemaVersion(), 1, 1, 1, 0,
                stateWrite.payload(), occurredAt);
        AtomicInteger initialWatermarkReads = new AtomicInteger();
        AgentStore store = eventStore(conversation, List.of(persisted), occurredAt, initialWatermarkReads);
        AtomicInteger attempts = new AtomicInteger();
        List<String> attemptedFrames = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch firstAttemptFailed = new CountDownLatch(1);
        CountDownLatch replayed = new CountDownLatch(1);
        TurnEventSink events = new TurnEventSink() {
            /** 首次投递明确失败，随后确认同一帧，用于验证显式恢复而不是新增业务提交。 */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                attemptedFrames.add(event.context().eventId());
                if (attempts.incrementAndGet() == 1) {
                    return CompletableFuture.failedFuture(new IllegalStateException("temporary sink failure"));
                }
                replayed.countDown();
                return CompletableFuture.completedFuture(null);
            }

            /** 等待 bridge 已完成失败处理后再请求恢复，避免回调抛错前的竞态被当作重试开始。 */
            @Override
            public CompletionStage<Void> publishProjectionFailure(TurnProjectionFailure failure) {
                firstAttemptFailed.countDown();
                return CompletableFuture.completedFuture(null);
            }
        };
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by event replay");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, eventCodec);
        bridge.bindEngine(engine);
        try (engine; bridge; AutoCloseable attachment = bridge.attach(identity,
                ConversationTurnEventSink.turnEventsOnly(events))) {
            assertTrue(firstAttemptFailed.await(3, TimeUnit.SECONDS), "initial sink failure was not observed");
            CompletableFuture<Void> requestedReplay = CompletableFuture.runAsync(() -> bridge.onReplayRequired(
                    new io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener.ReplayRequired(
                            identity, 0, 1, "sink_failure")));

            requestedReplay.get(3, TimeUnit.SECONDS);

            assertTrue(replayed.await(1, TimeUnit.SECONDS), "explicit replay did not publish the pending frame");
            String projectedId = JaAgentEventBridge.projectedEventId(identity.tenantId(), identity.threadId(),
                    identity.turnId(), stateWrite.eventId());
            assertEquals(List.of(projectedId, projectedId), attemptedFrames);
            assertEquals(2, initialWatermarkReads.get(), "replay must reread the same durable event watermark");
        }
    }

    /** 同 sink 续跑只接收未见事件，新 sink 则从 durable cursor 0 完整重放。 */
    @Test
    void resumesSameSinkAtConfirmedCursorWhileNewSinkReplaysFromZero() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        Instant occurredAt = Instant.parse("2026-09-26T12:00:00Z");
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        MutableEventStore mutableStore = mutableEventStore(conversation, occurredAt);
        mutableStore.append().accept(event(codec, conversation, identity, 1, TurnState.QUEUED, TurnState.RUNNING,
                occurredAt));
        AgentEngine engine = new AgentEngineBuilder().store(mutableStore.store())
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by event replay");
                }).build();
        JaAgentEventBridge bridge = eventBridge(mutableStore.store(), codec);
        bridge.bindEngine(engine);
        List<String> sameSinkFrames = new CopyOnWriteArrayList<>();
        CountDownLatch resumedFrame = new CountDownLatch(1);
        ConversationTurnEventSink sameSink = ConversationTurnEventSink.turnEventsOnly(event -> {
            sameSinkFrames.add(event.context().eventId());
            if (sameSinkFrames.size() == 2) {
                resumedFrame.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });

        try (engine; bridge) {
            try (AutoCloseable first = bridge.attach(identity, sameSink)) {
                awaitFrameCount(sameSinkFrames, 1);
            }
            mutableStore.append().accept(event(codec, conversation, identity, 2, TurnState.RUNNING, TurnState.SUSPENDED,
                    occurredAt.plusSeconds(1)));
            try (AutoCloseable resumed = bridge.attach(identity, sameSink)) {
                assertTrue(resumedFrame.await(3, TimeUnit.SECONDS), "same sink did not receive the next event");
                assertEquals(List.of(
                        JaAgentEventBridge.projectedEventId(identity.tenantId(), identity.threadId(),
                                identity.turnId(), "evt_state_1"),
                        JaAgentEventBridge.projectedEventId(identity.tenantId(), identity.threadId(),
                                identity.turnId(), "evt_state_2")), sameSinkFrames);
            }

            List<String> newSinkFrames = new CopyOnWriteArrayList<>();
            CountDownLatch fullReplay = new CountDownLatch(2);
            ConversationTurnEventSink newSink = ConversationTurnEventSink.turnEventsOnly(event -> {
                newSinkFrames.add(event.context().eventId());
                fullReplay.countDown();
                return CompletableFuture.completedFuture(null);
            });
            try (AutoCloseable replay = bridge.attach(identity, newSink)) {
                assertTrue(fullReplay.await(3, TimeUnit.SECONDS), "new sink did not replay the durable history");
                assertEquals(sameSinkFrames, newSinkFrames);
            }
        }
    }

    /**
     * 验证自动压缩的持久阶段沿用 Thread sink，并保留 Store 提交的 revision 水位。
     *
     * @author kongweiguang
     */
    @Test
    void projectsAutomaticCompactionLifecycleFromDurableEvents() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        Instant time = Instant.parse("2026-10-15T00:00:00Z");
        List<AgentStore.PersistedEvent> persisted = List.of(
                contextCompactionEvent(codec, conversation, identity, 1, 11, 2,
                        new ExecutionEvent.ContextCompactionStarted("cmp_auto_success", 10, 1_200), time),
                contextCompactionEvent(codec, conversation, identity, 2, 12, 3,
                        new ExecutionEvent.ContextCompacted("cmp_auto_success",
                                ExecutionEvent.CompactionOutcome.COMPACTED, "checkpoint_auto_success",
                                "record_auto_success", 10, 12, 1_200, 320), time.plusSeconds(1)),
                contextCompactionEvent(codec, conversation, identity, 3, 13, 4,
                        new ExecutionEvent.ContextCompactionStarted("cmp_auto_failure", 12, 900),
                        time.plusSeconds(2)),
                contextCompactionEvent(codec, conversation, identity, 4, 14, 5,
                        new ExecutionEvent.ContextCompactionFailed("cmp_auto_failure", 12, 900,
                                "SUMMARY_FAILURE"), time.plusSeconds(3)));
        AgentStore store = eventStore(conversation, persisted, time, new AtomicInteger());
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by durable projection tests");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);
        List<ContextCompactionEvent> delivered = new CopyOnWriteArrayList<>();
        ConversationTurnEventSink sink = new ConversationTurnEventSink() {
            /** fixture 只记录普通 Turn 帧，不伪造模型输出。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 将持久压缩阶段写入既有 ContextCompactionEvent 通道。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(ContextCompactionEvent event) {
                delivered.add(event);
                return CompletableFuture.completedFuture(null);
            }

            /** 使用与真实 RpcSession 相同的身份 owner 解析 Workspace。
             * @author kongweiguang
             */
            @Override
            public Optional<String> workspaceId(AgentIdentity eventIdentity) {
                assertEquals(identity, eventIdentity);
                return Optional.of("ws_compaction_bridge");
            }

            /** fixture 不发布 Thread metadata。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(
                    io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }
        };

        try (engine; bridge; AutoCloseable attachment = bridge.attach(identity, sink)) {
            awaitCompactionEventCount(delivered, 4);
            assertTrue(delivered.get(0) instanceof ContextCompactionEvent.Started);
            assertTrue(delivered.get(1) instanceof ContextCompactionEvent.Compacted);
            assertTrue(delivered.get(2) instanceof ContextCompactionEvent.Started);
            assertTrue(delivered.get(3) instanceof ContextCompactionEvent.Failed);
            assertEquals("ws_compaction_bridge", delivered.get(0).context().workspaceId());
            assertEquals("thr_target", delivered.get(0).context().threadId());
            assertEquals("turn_child", delivered.get(0).context().turnId());
            assertEquals(ContextCompactionEvent.Trigger.AUTOMATIC, delivered.get(0).context().trigger());
            assertEquals(11L, delivered.get(0).context().threadRevision());
            assertEquals(12L, delivered.get(1).context().threadRevision());
            assertEquals(ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE,
                    ((ContextCompactionEvent.Failed) delivered.get(3)).errorCode());
        }
    }

    /**
     * 验证手动压缩仍只由其服务负责，不创建自动 Turn 事件附件。
     *
     * @author kongweiguang
     */
    @Test
    void doesNotBridgeManualCompactionIdentityAsAutomaticTurnEvent() throws Exception {
        AgentIdentity conversationIdentity = identity();
        AgentIdentity manualIdentity = AgentCompactionRequest.compactionIdentity(conversationIdentity,
                "manual_bridge_test");
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                conversationIdentity.tenantId(), conversationIdentity.threadId(), conversationIdentity.userId());
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore.PersistedEvent started = contextCompactionEvent(codec, conversation, manualIdentity, 1, 11, 2,
                new ExecutionEvent.ContextCompactionStarted("cmp_manual_bridge", 10, 1_200), Instant.EPOCH);
        AgentStore store = eventStore(conversation, List.of(started), Instant.EPOCH, new AtomicInteger());
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by manual identity gate test");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);
        AtomicInteger workspaceLookups = new AtomicInteger();
        AtomicInteger contextEvents = new AtomicInteger();
        ConversationTurnEventSink sink = new ConversationTurnEventSink() {
            /** 手动身份必须在读取持久 Turn 帧之前被拒绝。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(TurnEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            /** 此身份只能由 ManualContextCompactionService 发布。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(ContextCompactionEvent event) {
                contextEvents.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            /** 若发生 Workspace 查询，即表示自动事件桥错误尝试投影了手动事件。
             * @author kongweiguang
             */
            @Override
            public Optional<String> workspaceId(AgentIdentity ignored) {
                workspaceLookups.incrementAndGet();
                return Optional.of("ws_compaction_bridge");
            }

            /** fixture 不发布 Thread metadata。
             * @author kongweiguang
             */
            @Override
            public CompletionStage<Void> publish(
                    io.github.kongweiguang.ja.conversation.domain.model.ThreadMetadataEvent event) {
                return CompletableFuture.completedFuture(null);
            }
        };

        try (engine; bridge; AutoCloseable attachment = bridge.attach(manualIdentity, sink)) {
            assertFalse(activeAttachments(bridge).containsKey(manualIdentity));
            assertEquals(0, workspaceLookups.get());
            assertEquals(0, contextEvents.get());
        }
    }

    /** Child Timeline 可先于 Core Conversation 创建完成 attachment；本地 ModelPort 不联网，首个 durable 状态仍须唯一投影。
     * Fixture 持有 SQLite 临时文件，必须等借用 Store 的投影线程实际退出，而不能把 detach 的停止请求当作资源释放。
     * @author kongweiguang
     */
    @Test
    void attachesBeforeCoreAdmissionAndProjectsFirstDurableStateExactlyOnce() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId());
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        try (SQLiteAgentStore store = SQLiteAgentStore.open(temporary.resolve("attach-before-admission.sqlite"))) {
            JaAgentEventBridge bridge = eventBridge(store, codec);
            AgentEngine engine = new AgentEngineBuilder().store(store)
                    .runtimeResolver((request, recoveredExtensions, recovered) -> {
                        ModelPort.ModelConfiguration configuration = new ModelPort.ModelConfiguration(
                                "provider_test", "model_test", "cfg_test", ModelPort.Api.OPENAI_RESPONSES,
                                "offline-test-model", URI.create("https://example.invalid"), "not-a-real-secret",
                                Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of(ModelPort.InputModality.TEXT),
                                new ModelPort.GenerationOptions(null, null, 32, null));
                        ModelPort model = new ModelPort() {
                            /** 本地估算固定且不触发网络请求。 */
                            @Override public InputTokenEstimate estimateInputTokens(ModelRequest modelRequest,
                                                                                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                                return new InputTokenEstimate(1, 1, "a".repeat(64));
                            }

                            /** 以纯内存成功响应结束 Turn，让测试完整观察状态后不依赖 Provider。 */
                            @Override public CompletionStage<ModelOutcome> start(ModelRequest modelRequest,
                                    io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink eventSink,
                                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                                cancellation.throwIfCancellationRequested();
                                return CompletableFuture.failedFuture(
                                        new IllegalStateException("offline fixture prevents any Provider call"));
                            }
                        };
                        SummaryModel.Factory summaries = binding -> new SummaryModel() {
                            /** 本测试不应触发压缩摘要。 */
                            @Override public ModelPort.InputTokenEstimate estimateInputTokens(SummaryPrompt prompt) {
                                return new ModelPort.InputTokenEstimate(1, 1, "b".repeat(64));
                            }

                            /** 任何意外压缩都快速失败，不访问外部模型。 */
                            @Override public Result summarize(SummaryPrompt prompt) {
                                throw new AssertionError("offline fixture must not summarize context");
                            }
                        };
                        return new AgentRequestRuntime(configuration, model, summaries, recoveredExtensions,
                                new FixedAgentPromptSession(ContextBudget.capabilities(100_000, 8_192, true)),
                                "c".repeat(64), AgentStoreTransactionParticipant.NONE,
                                attachment -> { throw new AssertionError("text fixture has no attachments"); },
                                new ToolProjectionLimits(64_000, 16_000), Map.of(
                                        "accessMode", AccessMode.FULL_ACCESS.name(),
                                        "collaborationMode", CollaborationMode.DEFAULT.name()), () -> { });
                    })
                    .extensions(registry -> registry.registerEventListener(bridge))
                    .build();
            CountDownLatch watchRegisteredBeforeAdmission = new CountDownLatch(1);
            AtomicReference<Boolean> conversationWasAbsentAtWatch = new AtomicReference<>();
            AtomicReference<Thread> projectionWorker = new AtomicReference<>();
            AgentEngine observingEngine = (AgentEngine) Proxy.newProxyInstance(
                    AgentEngine.class.getClassLoader(), new Class<?>[]{AgentEngine.class}, (proxy, method, args) -> {
                        if (method.getName().equals("watchEvents")) {
                            projectionWorker.set(Thread.currentThread());
                        }
                        try {
                            Object result = method.invoke(engine, args);
                            if (method.getName().equals("watchEvents")) {
                                conversationWasAbsentAtWatch.set(store.loadConversation(conversation).isEmpty());
                                watchRegisteredBeforeAdmission.countDown();
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            bridge.bindEngine(observingEngine);
            List<TurnEvent> delivered = new CopyOnWriteArrayList<>();
            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch terminal = new CountDownLatch(1);
            TurnEventSink timeline = new TurnEventSink() {
                /** 记录真实 SQLite 事件投影；无模型实现确保测试不会访问 Provider。 */
                @Override public CompletionStage<Void> publish(TurnEvent event) {
                    delivered.add(event);
                    if (event instanceof TurnEvent.StateChanged state && state.from() == TurnState.QUEUED
                            && state.to() == TurnState.RUNNING) {
                        firstRunning.countDown();
                    }
                    if (event instanceof TurnEvent.Terminal) {
                        terminal.countDown();
                    }
                    return CompletableFuture.completedFuture(null);
                }
            };
            try (AutoCloseable projectionExited = () -> awaitProjectionWorkerExit(projectionWorker);
                    engine; bridge; AutoCloseable attachment = bridge.attach(identity,
                    ConversationTurnEventSink.turnEventsOnly(timeline))) {
                assertTrue(store.loadConversation(conversation).isEmpty(),
                        "new Child Thread must have no Core Conversation before first admission");
                assertTrue(watchRegisteredBeforeAdmission.await(3, TimeUnit.SECONDS),
                        "Core must allow a durable watch to be established before Conversation admission");
                assertEquals(Boolean.TRUE, conversationWasAbsentAtWatch.get());

                engine.submit(AgentRequest.text(identity, "attachment race fixture", "op_attach_before_admission"))
                        .completion().toCompletableFuture().get(10, TimeUnit.SECONDS);

                assertTrue(firstRunning.await(3, TimeUnit.SECONDS), "first committed RUNNING state was not projected");
                assertTrue(terminal.await(3, TimeUnit.SECONDS), "test runtime failure did not reach durable terminal");
                long initialRunningStates = delivered.stream()
                        .filter(TurnEvent.StateChanged.class::isInstance)
                        .map(TurnEvent.StateChanged.class::cast)
                        .filter(state -> state.from() == TurnState.QUEUED && state.to() == TurnState.RUNNING)
                        .count();
                assertEquals(1, initialRunningStates, "the first durable state must be delivered exactly once");
            }
        }
    }

    /** 正常关闭只解除订阅并中断 worker；等待真实线程退出后才能释放本 fixture 的 SQLite 文件所有权。
     * @author kongweiguang
     */
    private static void awaitProjectionWorkerExit(AtomicReference<Thread> projectionWorker) throws InterruptedException {
        Thread worker = projectionWorker.get();
        if (worker != null) {
            assertTrue(worker.join(Duration.ofSeconds(3)),
                    "closed projection worker must exit before SQLite temporary directory cleanup");
        }
    }

    /** 关闭发生在 durable reader 尚未发布时也必须等待，不能靠投递锁偶然覆盖审批关闭竞态。 */
    @Test
    void closeDrainsCommittedWatermarkAndWaitsForSinkAcknowledgement() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        AgentStore base = eventStore(conversation, List.of(event(codec, conversation, identity, 1,
                TurnState.RUNNING, TurnState.SUSPENDED, Instant.EPOCH)), Instant.EPOCH, new AtomicInteger());
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("readEvents")) {
                        reading.countDown();
                        assertTrue(allowRead.await(3, TimeUnit.SECONDS), "reader was not released");
                    }
                    return method.invoke(base, arguments);
                });
        AgentEngine engine = new AgentEngineBuilder().store(store)
                .runtimeResolver((request, extensions, recovered) -> {
                    throw new IllegalStateException("runtime is not used by event replay");
                }).build();
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.bindEngine(engine);
        try (engine; bridge; AutoCloseable subscription = bridge.attach(identity,
                ConversationTurnEventSink.turnEventsOnly(value -> {
                    published.countDown();
                    return acknowledged;
                }))) {
            try {
                assertTrue(reading.await(3, TimeUnit.SECONDS));
                CompletableFuture<Void> closed = CompletableFuture.runAsync(() -> {
                    closing.countDown();
                    try {
                        subscription.close();
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                });
                assertTrue(closing.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
                allowRead.countDown();
                assertTrue(published.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
                acknowledged.complete(null);
                closed.get(3, TimeUnit.SECONDS);
            } finally {
                allowRead.countDown();
                acknowledged.complete(null);
            }
        }
    }

    /** 关停后 attach 必须先拒绝，不能因 Engine 未绑定而触发订阅创建或留下后台 worker。 */
    @Test
    void rejectsAttachAfterBridgeClose() {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore store = eventStore(conversation, List.of(event(codec, conversation, identity, 1,
                TurnState.RUNNING, TurnState.SUSPENDED, Instant.EPOCH)), Instant.EPOCH, new AtomicInteger());
        JaAgentEventBridge bridge = eventBridge(store, codec);
        bridge.close();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> bridge.attach(identity, ConversationTurnEventSink.noop()));

        assertEquals("event bridge is closed", failure.getMessage());
    }

    /** 缓存 writer 已排队等待锁时触发关停，确保缓存 owner 不会在清理后重新写入 checkpoint。 */
    @Test
    void closeDoesNotRetainCheckpointWriterAlreadyWaitingForCacheLock() throws Exception {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore store = eventStore(conversation, List.of(), Instant.EPOCH, new AtomicInteger());
        JaAgentEventBridge bridge = eventBridge(store, codec);
        Field lockField = JaAgentEventBridge.class.getDeclaredField("resumeLock");
        lockField.setAccessible(true);
        Object resumeLock = lockField.get(bridge);
        Field checkpointsField = JaAgentEventBridge.class.getDeclaredField("resumeCheckpoints");
        checkpointsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<?, ?> checkpoints = (Map<?, ?>) checkpointsField.get(bridge);
        Class<?> checkpointType = Class.forName(JaAgentEventBridge.class.getName() + "$ResumeCheckpoint");
        Method remember = JaAgentEventBridge.class.getDeclaredMethod("rememberCheckpoint",
                ConversationTurnEventSink.class, AgentIdentity.class, checkpointType);
        remember.setAccessible(true);
        ConversationTurnEventSink sink = ConversationTurnEventSink.noop();
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer;
        try {
            synchronized (resumeLock) {
                writer = Thread.ofVirtual().start(() -> {
                    started.countDown();
                    try {
                        remember.invoke(bridge, sink, identity, null);
                    } catch (ReflectiveOperationException invalid) {
                        failure.set(invalid);
                    }
                });
                assertTrue(started.await(3, TimeUnit.SECONDS));
                assertTrue(awaitBlocked(writer), "checkpoint writer did not wait for the cache lock");
                bridge.close();
                assertEquals(0, checkpoints.size());
            }
            writer.join(TimeUnit.SECONDS.toMillis(3));
            assertFalse(writer.isAlive(), "checkpoint writer did not finish after the cache lock was released");
            assertNull(failure.get());
            assertEquals(0, checkpoints.size());
        } finally {
            bridge.close();
        }
    }

    /** Core 的取消原因仍可审计，但公开 CANCELLED 事件及结果比对必须遵守不带错误字段的契约。 */
    @Test
    void cancellationAuditErrorsRemainInCoreWhilePublicTerminalHasNoErrors() {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), "user_local");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        JacksonExecutionEventCodec codec = new JacksonExecutionEventCodec(new ObjectMapper());
        ExecutionEvent.Terminal terminal = new ExecutionEvent.Terminal(TurnState.CANCELLED, "cancelled",
                "USER_CANCELLED", "cancelled by user", null, null);
        AgentStore.EventWrite write = codec.encode("evt_cancelled", terminal, Instant.EPOCH);
        AgentStore.PersistedEvent persisted = new AgentStore.PersistedEvent(conversation, execution,
                write.eventId(), write.eventType(), write.payloadSchemaVersion(), 1, 1, 1, 0,
                write.payload(), Instant.EPOCH);
        AgentStore store = eventStore(conversation, List.of(persisted), Instant.EPOCH, new AtomicInteger());
        try (JaAgentEventBridge bridge = eventBridge(store, codec)) {
            TurnResult projected = bridge.result(identity, new io.github.kongweiguang.ja.agent.application.api.TurnResult(
                    terminal.state(), terminal.summary(), terminal.errorCode(), terminal.errorMessage()));
            assertEquals(TurnState.CANCELLED, projected.state());
            assertNull(projected.terminal().errorCode());
            assertNull(projected.terminal().errorMessage());
            assertEquals(terminal, codec.decode(persisted));
        }
    }

    /** 用完整来源身份哈希公开 event id，避免 Core rec_ 与 evt_ 命名空间折叠成同一 JA 身份。 */
    @Test
    void projectsDifferentCoreEventNamespacesWithoutIdentityCollision() {
        String durable = JaAgentEventBridge.projectedEventId("tenant_test", "thr_target", "turn_child",
                "rec_collision");
        String event = JaAgentEventBridge.projectedEventId("tenant_test", "thr_target", "turn_child",
                "evt_collision");

        assertTrue(durable.startsWith("evt_"));
        assertNotEquals(durable, event);
        assertEquals(durable, JaAgentEventBridge.projectedEventId("tenant_test", "thr_target", "turn_child",
                "rec_collision"));
    }

    /** 使用应用本地 owner 身份验证正常投影；跨 owner 拒绝行为由独立用例覆盖。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("ja.local", "thr_target", "turn_child", "user_local");
    }

    /** 构造只包含可展示画像的 UNKNOWN Assistant 用量，验证本地估算与计费事实分开持久。 */
    private static ExecutionFacts.Usage usage(String modelRequestId, int ordinal, int round, long estimatedTokens) {
        ExecutionFacts.ModelRequestIdentity profile = new ExecutionFacts.ModelRequestIdentity(
                "provider_test", "model_test", "openai_responses", "test-model", "medium", "medium",
                100_000, 8_192, JsonObjects.builder()
                        .putText("accessMode", AccessMode.FULL_ACCESS.name())
                        .putText("collaborationMode", CollaborationMode.DEFAULT.name())
                        .putText("configGeneration", "cfg_context_usage_test")
                        .putText("promptRevision", "prompt_context_usage_test")
                        .putText("toolCatalogRevision", "b".repeat(64)).build());
        return new ExecutionFacts.Usage(modelRequestId, null, round, ordinal,
                ExecutionFacts.UsagePurpose.ASSISTANT, ExecutionFacts.UsageCertainty.UNKNOWN,
                profile, estimatedTokens);
    }

    /** 持久事实序号由测试事务显式分配，与关联事件的固定 watermark 对齐。 */
    private static AgentStore.StoredRecord storedRecord(AgentStore.RecordWrite record, long sequence) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                sequence, record.payload(), record.occurredAt());
    }

    /** 复现先入队再消费的两个真实提交；消费只写 InputAccepted，排队记录、消息和 USER 记录身份刻意不同。 */
    private static AgentStore inputConsumptionStore(AgentInputQueue.Kind kind, UserMessage queued, UserMessage accepted) {
        AgentIdentity identity = identity();
        AgentStore.ConversationKey conversation = new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(),
                identity.userId());
        Instant enqueuedAt = Instant.parse("2026-10-03T00:00:00Z");
        Instant consumedAt = enqueuedAt.plusSeconds(4);
        StorePayloadCodec records = new StorePayloadCodec(new ObjectMapper());
        ControlPayloadCodec controls = new ControlPayloadCodec(new ObjectMapper());
        JacksonExecutionEventCodec events = new JacksonExecutionEventCodec(new ObjectMapper());
        AgentStore.RecordWrite queuedRecord = records.encodeQueuedUser("rec_input_queued", queued.messageId(),
                queued.content(), enqueuedAt);
        AgentInputQueue.Command enqueue = new AgentInputQueue.Command("cmd_input_enqueue", AgentInputQueue.Action.ENQUEUE,
                "input_follow_up", kind, 0, 1, queuedRecord.recordId(), null, enqueuedAt,
                kind == AgentInputQueue.Kind.MESSAGE ? metadata() : Map.of());
        AgentStore.RecordWrite userRecord = records.encodeUser("rec_input_consumed", accepted.messageId(),
                accepted.content(), consumedAt);
        AgentInputQueue.Command consume = new AgentInputQueue.Command("cmd_input_consume", AgentInputQueue.Action.CONSUME,
                enqueue.inputId(), null, 1, 2, null, null, consumedAt, Map.of());
        return eventStoreWithRecords(conversation, List.of(
                persistedEvent(events, conversation, identity, 1, 2, "evt_input_enqueued",
                        new ExecutionEvent.ControlRecorded(ExecutionEvent.ControlAction.INPUT_CHANGED,
                                enqueue.commandId(), "op_input_enqueue", null), enqueuedAt),
                persistedEvent(events, conversation, identity, 2, 4, "evt_input_accepted",
                        new ExecutionEvent.InputAccepted(accepted.messageId()), consumedAt)),
                List.of(storedRecord(queuedRecord, 1), storedCommand(controls, enqueue, 2),
                        storedRecord(userRecord, 3), storedCommand(controls, consume, 4)),
                enqueuedAt, "", new CountDownLatch(0), new CountDownLatch(0));
    }

    /** 编码带准确 Store 水位的持久事件，回归不绕过版本化事件 codec。 */
    private static AgentStore.PersistedEvent persistedEvent(JacksonExecutionEventCodec codec,
                                                             AgentStore.ConversationKey conversation,
                                                             AgentIdentity identity, long sequence,
                                                             long recordWatermark, String eventId,
                                                             ExecutionEvent event, Instant occurredAt) {
        AgentStore.EventWrite encoded = codec.encode(eventId, event, occurredAt);
        return new AgentStore.PersistedEvent(conversation,
                new AgentStore.ExecutionKey(conversation, identity.turnId()), encoded.eventId(), encoded.eventType(),
                encoded.payloadSchemaVersion(), sequence, sequence, sequence, recordWatermark,
                encoded.payload(), occurredAt);
    }

    /** 保留同一提交内不同持久序号及共享 mutation fence，精确复现 Core 原子写入的多事件批次。
     * @author kongweiguang
     */
    private static AgentStore.PersistedEvent persistedCommitEvent(JacksonExecutionEventCodec codec,
                                                                    AgentStore.ConversationKey conversation,
                                                                    AgentIdentity identity, long sequence,
                                                                    long threadRevision, long mutationVersion,
                                                                    long recordWatermark, String eventId,
                                                                    ExecutionEvent event, Instant occurredAt) {
        AgentStore.EventWrite encoded = codec.encode(eventId, event, occurredAt);
        return new AgentStore.PersistedEvent(conversation,
                new AgentStore.ExecutionKey(conversation, identity.turnId()), encoded.eventId(), encoded.eventType(),
                encoded.payloadSchemaVersion(), sequence, threadRevision, mutationVersion, recordWatermark,
                encoded.payload(), occurredAt);
    }

    /** 只构造两类回归共用的 pending wait；普通问题与审批的差异保持在真实 kind/question 契约中。
     * @author kongweiguang
     */
    private static InteractionWait wait(String interactionId, String callId, String kind, Instant occurredAt) {
        JsonObjectBuilder attributes = JsonObjects.builder().putText("kind", kind);
        List<InteractionOption> options = List.of();
        InteractionQuestionType type = InteractionQuestionType.TEXT;
        boolean allowFreeText = true;
        String prompt = "请补充必要信息。";
        if (kind.equals("approval")) {
            attributes.putText("expiresAt", occurredAt.plusSeconds(300).toString());
            options = List.of(new InteractionOption("option_allow", "允许", "允许此次工具调用", true),
                    new InteractionOption("option_deny", "拒绝", "拒绝此次工具调用", false));
            type = InteractionQuestionType.SINGLE;
            allowFreeText = false;
            prompt = "请确认是否允许执行此次工具调用。";
        }
        InteractionQuestion question = new InteractionQuestion("question_" + kind, prompt, type, options,
                true, allowFreeText);
        return new InteractionWait(interactionId, callId, "operation_" + interactionId, attributes.build(),
                List.of(question), List.of(), InteractionStatus.PENDING, 0, occurredAt, occurredAt);
    }

    /** Bridge 的确定性事件回归不构造模型提案；需校验真实 Tool call 时由用例注入同源 preview 读取器。
     * @author kongweiguang
     */
    private static ToolPresentation pendingPresentation() {
        return new ToolPresentation(ToolPresentation.Kind.SHELL, "执行命令", ToolPresentation.Status.PENDING,
                null, null, null, List.of(), List.of(), null, null, null, null, null, null, false, null);
    }

    /** 读取真实编码的 usage/message 事实，并在第一轮完成消息读取处提供确定性调度闸门。 */
    private static AgentStore eventStoreWithRecords(AgentStore.ConversationKey conversation,
                                                     List<AgentStore.PersistedEvent> events,
                                                     List<AgentStore.StoredRecord> records,
                                                     Instant createdAt, String blockedRecordId,
                                                     CountDownLatch blockedRead, CountDownLatch allowBlockedRead) {
        AgentStore.PersistedEvent last = events.getLast();
        AgentStore.ConversationSnapshot snapshot = new AgentStore.ConversationSnapshot(conversation,
                last.threadRevision(), last.sequence(), last.sequence(), createdAt, last.occurredAt(),
                last.lastRecordSequence());
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadConversation" -> Optional.of(snapshot);
                    case "readEvents" -> eventPage(events, (AgentStore.EventQuery) arguments[0], new AtomicInteger());
                    case "readRecords" -> {
                        AgentStore.RecordQuery query = (AgentStore.RecordQuery) arguments[0];
                        List<AgentStore.StoredRecord> page = records.stream()
                                .filter(record -> record.sequence() > query.afterSequence()
                                        && record.sequence() <= query.throughSequence())
                                .limit(query.limit()).toList();
                        long next = page.isEmpty() ? query.afterSequence() : page.getLast().sequence();
                        boolean hasMore = records.stream().anyMatch(record -> record.sequence() > next
                                && record.sequence() <= query.throughSequence());
                        yield new AgentStore.RecordPage(page, next, hasMore);
                    }
                    case "readRecord" -> {
                        String recordId = (String) arguments[1];
                        if (blockedRecordId.equals(recordId)) {
                            blockedRead.countDown();
                            try {
                                if (!allowBlockedRead.await(3, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("test record read gate timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("test record read was interrupted", interrupted);
                            }
                        }
                        yield records.stream().filter(record -> record.recordId().equals(recordId)).findFirst();
                    }
                    case "close" -> null;
                    case "toString" -> "ContextEstimateEventStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException("unexpected AgentStore method: "
                            + method.getName());
                });
    }

    /** 测试只向 Bridge 自己持有的真实 Core 订阅注入受控临时事件，不扩展生产 API。 */
    private static AgentStreamSubscription attachmentStream(JaAgentEventBridge bridge, AgentIdentity identity)
            throws ReflectiveOperationException {
        Field activeField = JaAgentEventBridge.class.getDeclaredField("active");
        activeField.setAccessible(true);
        Object attachment = ((Map<?, ?>) activeField.get(bridge)).get(identity);
        if (attachment == null) {
            throw new IllegalStateException("event projection attachment is not active");
        }
        Field streamField = attachment.getClass().getDeclaredField("stream");
        streamField.setAccessible(true);
        return (AgentStreamSubscription) streamField.get(attachment);
    }

    /** 使用生产 mapper 生成唯一模型 envelope，避免测试复制一份会漂移的协议 JSON。
     * @author kongweiguang
     */
    private static UserContent mailboxModelContent(String messageKind, String sourceThreadId,
                                                   String sourceTitle, String message) {
        Instant createdAt = Instant.parse("2026-09-26T12:00:00Z");
        TaskModels.MailboxMessage mailbox = new TaskModels.MailboxMessage(1, "msg_mailbox", "thr_root",
                sourceThreadId, sourceTitle, "thr_target", null, TaskModels.MailboxKind.valueOf(messageKind),
                new UserContent(List.of(new TextContent(message))), "op_mailbox", TaskModels.MailboxState.PENDING,
                null, createdAt, createdAt, null);
        return TaskMailboxQueueCommandMapper.toQueueCommand(mailbox, new JacksonJsonValueCodec()).content();
    }

    /** 保留 TaskMailboxQueueCommandMapper 当前要求的最小来源事实集合。 */
    private static Map<String, String> metadata() {
        return Map.of(
                "ja.mailbox.messageId", "msg_mailbox",
                "ja.mailbox.messageKind", "MESSAGE",
                "ja.mailbox.senderThreadId", "thr_sender",
                "ja.mailbox.targetThreadId", "thr_target",
                "ja.mailbox.senderTitle", "Sender title",
                "ja.mailbox.createdAt", "2026-09-26T12:00:00Z");
    }

    /** 将协议编码的队列命令包成 Store 事实，确保回归测试使用真实 command codec。 */
    private static AgentStore.StoredRecord storedCommand(ControlPayloadCodec controls,
                                                          AgentInputQueue.Command command, long sequence) {
        AgentStore.RecordWrite encoded = controls.encodeInputQueueCommand(command.commandId(), command,
                command.occurredAt());
        return new AgentStore.StoredRecord(encoded.recordId(), encoded.recordType(), encoded.payloadSchemaVersion(),
                sequence, encoded.payload(), encoded.occurredAt());
    }

    /** 为事件桥回归提供固定持久游标；只支持测试实际需要的只读方法，其他访问显式失败。 */
    private static AgentStore eventStore(AgentStore.ConversationKey conversation,
                                         List<AgentStore.PersistedEvent> events, Instant occurredAt,
                                         AtomicInteger initialWatermarkReads) {
        long eventSequence = events.isEmpty() ? 0 : events.getLast().sequence();
        AgentStore.ConversationSnapshot snapshot = new AgentStore.ConversationSnapshot(conversation,
                1, eventSequence, 1, occurredAt, occurredAt, 0);
        return (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadConversation" -> Optional.of(snapshot);
                    case "readEvents" -> eventPage(events, (AgentStore.EventQuery) arguments[0],
                            initialWatermarkReads);
                    case "readRecords" -> new AgentStore.RecordPage(List.of(),
                            ((AgentStore.RecordQuery) arguments[0]).afterSequence(), false);
                    case "readRecord" -> Optional.empty();
                    case "close" -> null;
                    case "toString" -> "FixedEventStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException("unexpected AgentStore method: "
                            + method.getName());
                });
    }

    /** 为断线续跑用例提供可追加的真实事件页和同步更新的会话水位。 */
    private static MutableEventStore mutableEventStore(AgentStore.ConversationKey conversation, Instant createdAt) {
        List<AgentStore.PersistedEvent> events = new CopyOnWriteArrayList<>();
        AtomicReference<AgentStore.ConversationSnapshot> snapshot = new AtomicReference<>(
                new AgentStore.ConversationSnapshot(conversation, 0, 0, 0, createdAt, createdAt, 0));
        AgentStore store = (AgentStore) Proxy.newProxyInstance(AgentStore.class.getClassLoader(),
                new Class<?>[]{AgentStore.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "loadConversation" -> Optional.of(snapshot.get());
                    case "readEvents" -> eventPage(List.copyOf(events), (AgentStore.EventQuery) arguments[0],
                            new AtomicInteger());
                    case "readRecords" -> new AgentStore.RecordPage(List.of(),
                            ((AgentStore.RecordQuery) arguments[0]).afterSequence(), false);
                    case "readRecord" -> Optional.empty();
                    case "close" -> null;
                    case "toString" -> "MutableEventStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException("unexpected AgentStore method: "
                            + method.getName());
                });
        Consumer<AgentStore.PersistedEvent> append = event -> {
            events.add(event);
            snapshot.set(new AgentStore.ConversationSnapshot(conversation, event.threadRevision(),
                    event.sequence(), event.sequence(), createdAt, event.occurredAt(),
                    event.lastRecordSequence()));
        };
        return new MutableEventStore(store, append);
    }

    /** 构造与 Engine Store 查询契约一致的单条状态事件。 */
    private static AgentStore.PersistedEvent event(JacksonExecutionEventCodec codec,
                                                    AgentStore.ConversationKey conversation,
                                                    AgentIdentity identity, long sequence,
                                                    TurnState from, TurnState to, Instant occurredAt) {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        AgentStore.EventWrite write = codec.encode("evt_state_" + sequence,
                new ExecutionEvent.StateChanged(from, to), occurredAt);
        return new AgentStore.PersistedEvent(conversation, execution, write.eventId(), write.eventType(),
                write.payloadSchemaVersion(), sequence, sequence, sequence, 0, write.payload(), occurredAt);
    }

    /**
     * 使用生产事件 codec 编码压缩阶段，并保留每个持久事件的 revision 水位。
     *
     * @author kongweiguang
     */
    private static AgentStore.PersistedEvent contextCompactionEvent(JacksonExecutionEventCodec codec,
            AgentStore.ConversationKey conversation, AgentIdentity identity, long sequence, long threadRevision,
            long mutationVersion, ExecutionEvent event, Instant occurredAt) {
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(conversation, identity.turnId());
        AgentStore.EventWrite write = codec.encode("evt_compaction_" + sequence, event, occurredAt);
        return new AgentStore.PersistedEvent(conversation, execution, write.eventId(), write.eventType(),
                write.payloadSchemaVersion(), sequence, threadRevision, mutationVersion, sequence,
                write.payload(), occurredAt);
    }

    /**
     * 读取事件桥的活动附件映射，确认非 Turn 的手动身份在开始回放前已被拒绝。
     *
     * @author kongweiguang
     */
    private static Map<?, ?> activeAttachments(JaAgentEventBridge bridge) throws ReflectiveOperationException {
        Field activeField = JaAgentEventBridge.class.getDeclaredField("active");
        activeField.setAccessible(true);
        return (Map<?, ?>) activeField.get(bridge);
    }

    /** 等待检查线程已进入 monitor 竞争，再由测试线程确定性地执行生命周期交错。 */
    private static boolean awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(1);
        }
        return thread.getState() == Thread.State.BLOCKED;
    }

    /** 等待异步 bridge 投影到达指定帧数；超时使订阅竞态明确失败，不扩大后台等待。 */
    private static void awaitFrameCount(List<String> frames, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (frames.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, frames.size(), "durable frame was not projected");
    }

    /**
     * 在固定的异步投影期限内等待压缩通知，超时即让回归明确失败。
     *
     * @author kongweiguang
     */
    private static void awaitCompactionEventCount(List<ContextCompactionEvent> events, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (events.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, events.size(), "durable compaction phase was not projected");
    }

    /**
     * 可变 store 仅开放追加事件，避免测试从 bridge 外部改写持久游标。
     *
     * @param store 存储端口
     * @param append 追加持久化事件的回调
     */
    private record MutableEventStore(AgentStore store,
                                     Consumer<AgentStore.PersistedEvent> append) {
        /** fixture 的两个入口必须真实可用。 */
        private MutableEventStore {
            java.util.Objects.requireNonNull(store, "store");
            java.util.Objects.requireNonNull(append, "append");
        }
    }

    /** 固定复读同一水位，不让测试用新增事件意外唤醒失败的投影 worker。 */
    private static AgentStore.EventPage eventPage(List<AgentStore.PersistedEvent> events,
                                                  AgentStore.EventQuery query,
                                                  AtomicInteger initialWatermarkReads) {
        if (query.afterSequence() == 0) {
            initialWatermarkReads.incrementAndGet();
        }
        List<AgentStore.PersistedEvent> page = events.stream()
                .filter(event -> event.sequence() > query.afterSequence()
                        && event.sequence() <= query.throughSequence())
                .toList();
        long nextSequence = page.isEmpty() ? query.afterSequence() : page.getLast().sequence();
        return new AgentStore.EventPage(page, nextSequence, false);
    }

    /** 构造只读投影使用的默认桥接依赖；本测试没有消息正文、工具或附件读取。
     * @author kongweiguang
     */
    private static JaAgentEventBridge eventBridge(AgentStore store, JacksonExecutionEventCodec eventCodec) {
        return eventBridge(store, eventCodec, (execution, messageRecordId, eventId, eventSequence) -> List.of());
    }

    /** 仅需要验证原生 Tool proposal 的用例注入固定预览事实，其余存储和附件边界保持一致。
     * @author kongweiguang
     */
    private static JaAgentEventBridge eventBridge(AgentStore store, JacksonExecutionEventCodec eventCodec,
                                                   ToolCallPreviewReadPort callPreviews) {
        ObjectMapper mapper = new ObjectMapper();
        return new JaAgentEventBridge(store, eventCodec, new StorePayloadCodec(mapper),
                new ControlPayloadCodec(mapper),
                new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec(),
                new ToolPresentationCodec(mapper), new TurnChangeSetCodec(mapper),
                callPreviews, unusedAttachments());
    }

    /** 无附件输入的 StateChanged 测试不得访问任何附件操作或物理资源。 */
    private static AttachmentUseCase unusedAttachments() {
        return new AttachmentUseCase() {
            /** 投影重放不创建附件草稿；误触导入会越过测试的只读边界。 */
            @Override
            public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata importDraft(
                    ImportRequest request) { throw new UnsupportedOperationException(); }

            /** 状态回放不创建图片附件；导入属于独立准入流程，错误调用必须失败关闭。 */
            @Override
            public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata importMcpImage(
                    String workspaceId, String displayName, String mediaType, byte[] content) {
                throw new UnsupportedOperationException();
            }

            /** 投影重放不改变附件生命周期；误触删除必须立刻暴露。 */
            @Override
            public io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata discard(
                    String attachmentId, Instant discardedAt) { throw new UnsupportedOperationException(); }

            /** 当前状态事件不含附件正文，读取调用说明投影边界被意外扩大。 */
            @Override
            public ReadResult read(ReadRequest request) { throw new UnsupportedOperationException(); }

            /** 固定事件没有附件 ID；拒绝任何隐式附件授权查询。 */
            @Override
            public List<io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata> readAuthorizedMetadata(
                    String threadId, List<String> attachmentIds) { throw new UnsupportedOperationException(); }

            /** 回放测试不拥有附件清理生命周期，避免产生无关存储副作用。 */
            @Override
            public void collectGarbage() { throw new UnsupportedOperationException(); }
        };
    }
}


