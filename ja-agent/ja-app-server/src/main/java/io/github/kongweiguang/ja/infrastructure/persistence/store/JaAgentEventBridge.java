// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.store;

import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventWatch;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentStreamSubscription;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionEvent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionAnswerSubmission;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelUsage;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEvent;
import io.github.kongweiguang.ja.agent.application.gateway.AgentEventListener;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.ContextCompactionEvent;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnProjectionFailure;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 将 Core 持久事件与短暂模型草稿分流投影到 Ja Turn 协议。
 */
public final class JaAgentEventBridge implements AgentEventListener, AgentExecutionEvents, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(JaAgentEventBridge.class);
    private static final int EVENT_PAGE_SIZE = 256;
    private static final int DRAFT_BATCH_SIZE = 128;
    private static final int RECORD_PAGE_SIZE = 256;
    private static final int MAX_RESUME_SINKS = 64;
    private static final int MAX_RESUME_TURNS_PER_SINK = 32;
    private static final long MAX_RECORDS_PER_WINDOW = 10_000;
    private static final JsonValueCodec MAILBOX_JSON = new JacksonJsonValueCodec();
    private static final Set<String> MAILBOX_ENVELOPE_FIELDS = Set.of(
            "kind", "messageKind", "sourceThreadId", "sourceTitle", "message");
    private final AgentStore store;
    private final ExecutionEventCodec events;
    private final ExecutionRecordCodec records;
    private final ExecutionControlCodec controls;
    private final ExtensionFactRecordCodec extensionFacts;
    private final ToolPresentationCodec presentations;
    private final TurnChangeSetCodec changes;
    private final ToolCallPreviewReadPort callPreviews;
    private final AttachmentUseCase attachments;
    private final AtomicReference<AgentEngine> engine = new AtomicReference<>();
    private final ConcurrentMap<AgentIdentity, Attachment> active = new ConcurrentHashMap<>();
    private final Object resumeLock = new Object();
    private final ReferenceQueue<ConversationTurnEventSink> collectedSinks = new ReferenceQueue<>();
    private final LinkedHashMap<SinkReference, LinkedHashMap<AgentIdentity, ResumeCheckpoint>> resumeCheckpoints =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Store 与 Ja 投影 codec 由组合根共享；桥只借用外部连接、附件服务和 Engine，不接管它们的关闭权。
     */
    public JaAgentEventBridge(AgentStore store, ExecutionEventCodec events, ExecutionRecordCodec records,
                              ExecutionControlCodec controls, ExtensionFactRecordCodec extensionFacts,
                              ToolPresentationCodec presentations, TurnChangeSetCodec changes,
                              ToolCallPreviewReadPort callPreviews, AttachmentUseCase attachments) {
        this.store = Objects.requireNonNull(store, "store");
        this.events = Objects.requireNonNull(events, "events");
        this.records = Objects.requireNonNull(records, "records");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.extensionFacts = Objects.requireNonNull(extensionFacts, "extensionFacts");
        this.presentations = Objects.requireNonNull(presentations, "presentations");
        this.changes = Objects.requireNonNull(changes, "changes");
        this.callPreviews = Objects.requireNonNull(callPreviews, "callPreviews");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
    }

    /**
     * 在通知宿主读取权威快照后保留原持久游标；只记录稳定错误码与最内层代码位置，不输出异常消息、SQL 或用户数据。
     * 错误 commit 未成功投影前不跳过或推进状态，且同一阻塞区间只告警一次。
     */
    private static void notifyProjectionFailure(Attachment attachment, Throwable failure, String location) {
        boolean notify = attachment.claimProjectionFailureNotification();
        attachment.failReplayWaiters(failure);
        if (!notify) {
            return;
        }
        Throwable origin = deepestCause(failure);
        StackTraceElement frame = firstFrame(origin);
        StorageException.Code storageCode = storageCode(failure);
        LOGGER.warn("{} failed type={} storageCode={} causeType={} missingMember={} origin={}:{}#{}",
                location, failure.getClass().getName(), storageCode == null ? "NONE" : storageCode.name(),
                origin.getClass().getName(), missingMember(failure), frame == null ? "unknown" : frame.getFileName(),
                frame == null ? -1 : frame.getLineNumber(), frame == null ? "unknown" : frame.getMethodName());
        try {
            awaitPublish(attachment.sink.publishProjectionFailure(new TurnProjectionFailure(
                    attachment.identity.threadId(), attachment.identity.turnId(),
                    TurnProjectionFailure.Reason.PROJECTION_FAILED)));
        } catch (RuntimeException notificationFailure) {
            LOGGER.warn("JaAgentEventBridge.publishProjectionFailure failed with {}",
                    notificationFailure.getClass().getName());
        }
    }

    /**
     * 最多沿八层 cause 取诊断位置，防止异常链过深或畸形 cause 环放大日志处理。
     */
    private static Throwable deepestCause(Throwable failure) {
        Throwable current = Objects.requireNonNull(failure, "failure");
        for (int depth = 0; depth < 8; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    /**
     * 跳过 JDK 反射内部帧，返回首个调用方位置；不读取普通异常文本、SQL 或调用参数。
     */
    private static StackTraceElement firstFrame(Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        for (StackTraceElement frame : frames) {
            String className = frame.getClassName();
            if (!className.startsWith("java.") && !className.startsWith("javax.")
                && !className.startsWith("jdk.") && !className.startsWith("sun.")) {
                    return frame;
                }
        }
        return frames.length == 0 ? null : frames[0];
    }

    /**
     * 只提取由 JDK Class#getConstructor0 生成的安全构造器签名，帮助定位缺失的静态映射契约。
     */
    private static String missingMember(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof NoSuchMethodException missing && fromConstructorLookup(current)) {
                String signature = missing.getMessage();
                if (signature != null && signature.length() <= 512
                    && signature.matches("[A-Za-z0-9_.$<>(),\\[\\] ]+")) {
                        return signature;
                    }
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return "NONE";
    }

    /**
     * 确认异常由反射构造器查找产生后才允许记录其 JVM 生成的成员签名。
     */
    private static boolean fromConstructorLookup(Throwable failure) {
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (frame.getClassName().equals("java.lang.Class")
                && frame.getMethodName().equals("getConstructor0")) {
                    return true;
                }
        }
        return false;
    }

    /**
     * 从有界异常链提取存储端口定义的稳定分类，用于定位失败阶段而不暴露适配器细节。
     */
    private static StorageException.Code storageCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (current instanceof StorageException storage) {
                return storage.code();
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return null;
    }

    /**
     * 每个帧被 sink 确认后立即保存续送位置，失败的当前帧保留为唯一重试起点。
     */
    static PendingDelivery publishPending(PendingDelivery start, java.util.function.Consumer<TurnEvent> publish,
                                          java.util.function.Consumer<PendingDelivery> checkpoint) {
        PendingDelivery delivery = Objects.requireNonNull(start, "start");
        java.util.function.Consumer<TurnEvent> sink = Objects.requireNonNull(publish, "publish");
        java.util.function.Consumer<PendingDelivery> persist = Objects.requireNonNull(checkpoint, "checkpoint");
        while (delivery.nextEventIndex() < delivery.events().size()) {
            sink.accept(delivery.events().get(delivery.nextEventIndex()));
            delivery = delivery.advance();
            persist.accept(delivery);
        }
        return delivery;
    }

    /**
     * 空投影对应普通 InteractionWait 或无 Tool calls 的最终助手轮次；统一用不可变列表表示可选事件。
     */
    private static List<TurnEvent> optionalEvent(TurnEvent event) {
        return event == null ? List.of() : List.of(event);
    }

    /**
     * 只为正文与公开摘要草稿提取请求身份；Reset 不携带展示内容，也不满足估算前置条件。
     */
    private static String draftRequestId(AgentStreamSubscription.PollResult poll) {
        if (poll == null || poll.event().isEmpty()) {
            return null;
        }
        return switch (poll.event().orElseThrow()) {
            case io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta value ->
                    value.context().modelRequestId();
            case io.github.kongweiguang.ja.agent.application.api.TurnEvent.ReasoningSummaryDelta value ->
                    value.context().modelRequestId();
            case io.github.kongweiguang.ja.agent.application.api.TurnEvent.Reset ignored -> null;
        };
    }

    /**
     * Usage profile 只从真实 Usage fact 与其完整 ModelRequestIdentity 属性构造。
     */
    private static ProviderRequestUsage providerUsage(ExecutionFacts.Usage usage) {
        JsonObject attributes = usage.profile().attributes();
        ProviderRequestProfile profile = new ProviderRequestProfile(usage.profile().providerId(),
                usage.profile().modelId(), usage.profile().api(), usage.profile().upstreamModel(),
                usage.profile().requestedReasoning(), usage.profile().effectiveReasoning(),
                AccessMode.valueOf(requiredText(attributes, "accessMode")),
                CollaborationMode.valueOf(requiredText(attributes, "collaborationMode")),
                requiredText(attributes, "configGeneration"), requiredText(attributes, "promptRevision"),
                requiredText(attributes, "toolCatalogRevision"), usage.profile().contextWindowTokens(),
                usage.profile().maxOutputTokens());
        ProviderRequestUsage.Purpose purpose = switch (usage.purpose()) {
            case ASSISTANT -> ProviderRequestUsage.Purpose.ASSISTANT;
            case SUMMARY -> ProviderRequestUsage.Purpose.SUMMARY;
        };
        ProviderRequestUsage.Certainty certainty = switch (usage.certainty()) {
            case KNOWN -> ProviderRequestUsage.Certainty.KNOWN;
            case UNKNOWN -> ProviderRequestUsage.Certainty.UNKNOWN;
        };
        ModelUsage modelUsage = usage.usage();
        return new ProviderRequestUsage(usage.modelRequestId(), usage.modelRequestOrdinal(), usage.modelRound(), purpose,
                certainty, profile, modelUsage);
    }

    /**
     * 校验同一持久化提交的所有事件共享完全一致的版本和记录水位。
     */
    private static boolean sameCommit(AgentStore.PersistedEvent left, AgentStore.PersistedEvent right) {
        return left.threadRevision() == right.threadRevision()
               && left.mutationVersion() == right.mutationVersion()
               && left.lastRecordSequence() == right.lastRecordSequence();
    }

    /**
     * 在事务组中查找指定类型；相同 transaction 不得重复发布同一种单例投影。
     */
    private static DecodedEvent find(List<DecodedEvent> events, Class<? extends ExecutionEvent> type) {
        List<DecodedEvent> matches = events.stream().filter(value -> type.isInstance(value.event())).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("transaction repeats a singleton event type");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * 找到指定控制动作的持久控制事件。
     */
    private static DecodedEvent findControl(List<DecodedEvent> events, ExecutionEvent.ControlAction action) {
        List<DecodedEvent> matches = events.stream().filter(value -> value.event() instanceof ExecutionEvent.ControlRecorded
                control && control.action() == action).toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("transaction repeats an execution control action");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * Provider 原生 tool call 身份不得重写，并拒绝同一模型消息重复 callId。
     */
    private static Map<String, ToolCallContent> toolCalls(ModelMessage message) {
        Map<String, ToolCallContent> values = new LinkedHashMap<>();
        for (ModelContent content : message.content()) {
            if (content instanceof ToolCallContent call && values.putIfAbsent(call.callId(), call) != null) {
                throw new IllegalStateException("assistant message repeats a native tool call id");
            }
        }
        return Map.copyOf(values);
    }

    /**
     * Approval 的 kind 从持久 wait 属性读取，未知或非文本字段拒绝被解释为普通等待。
     */
    private static String textAttribute(JsonObject object, String field) {
        return requiredText(object, field);
    }

    /**
     * 持久 JSON 属性必须是非空文本，避免用缺省值掩盖损坏记录。
     */
    private static String requiredText(JsonObject object, String field) {
        JsonValue value = object.members().get(field);
        if (!(value instanceof JsonText(String value1)) || value1.isBlank()) {
            throw new IllegalStateException("required execution attribute is missing");
        }
        return value1;
    }

    /**
     * Extension revision 必须是精确整数，不能截断 JSON decimal。
     */
    private static int requiredNumber(JsonObject object, String field) {
        JsonValue value = object.members().get(field);
        if (!(value instanceof JsonNumber(java.math.BigDecimal value1))) {
            throw new IllegalStateException("extension revision is invalid");
        }
        try {
            return value1.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalStateException("extension revision is invalid", invalid);
        }
    }

    /**
     * 把持久事务身份映射为 Ja 端可回读的 revision fence。
     */
    private static TurnEvent.Context context(AgentStore.PersistedEvent event) {
        return new TurnEvent.Context(projectedEventId(event.execution().conversation().tenantId(),
                event.execution().conversation().threadId(), event.execution().turnId(), event.eventId()),
                event.conversation().threadId(),
                event.execution().turnId(), event.threadRevision(), event.mutationVersion(),
                event.occurredAt());
    }

    /**
     * 同一 Core 事实派生多个公开帧时按固定投影类别分配稳定身份，避免客户端去重吞帧；事务水位保持不变。
     */
    private static TurnEvent.Context context(AgentStore.PersistedEvent event, String projectionKind) {
        String source = String.join("\0", "ja.turn.projection.v1", event.eventId(), projectionKind);
        return new TurnEvent.Context(projectedEventId(event.execution().conversation().tenantId(),
                event.execution().conversation().threadId(), event.execution().turnId(), source),
                event.conversation().threadId(), event.execution().turnId(), event.threadRevision(),
                event.mutationVersion(), event.occurredAt());
    }

    /**
     * 对完整租户/会话/执行/Core event 身份散列，保证 rec_/evt_ 同值源事件也不会映射冲突。
     */
    static String projectedEventId(String tenantId, String threadId, String turnId, String coreEventId) {
        String source = String.join("\0", "ja.turn.event.v1", Objects.requireNonNull(tenantId, "tenantId"),
                Objects.requireNonNull(threadId, "threadId"), Objects.requireNonNull(turnId,
                        "turnId"), Objects.requireNonNull(coreEventId, "coreEventId"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return "evt_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 转换单个提交后等待 sink 背压完成，保证同一 Turn 的公开事件不乱序。
     */
    private static void publishCommitted(TurnEventSink sink, AgentStore.PersistedEvent persisted,
                                         TurnEvent event, int modelRound) {
        if (sink instanceof ConversationTurnEventSink conversation) {
            conversation.observeCommittedTurn(persisted.conversation().threadId(),
                    persisted.execution().turnId(), persisted.threadRevision(),
                    persisted.mutationVersion(), modelRound, event, persisted.occurredAt());
        } else {
            sink.observeCommittedTurn(persisted.conversation().threadId(),
                    persisted.execution().turnId(), persisted.threadRevision(),
                    persisted.mutationVersion(), modelRound, event, persisted.occurredAt());
        }
        awaitPublish(sink.publish(event));
    }

    /**
     * 非模型事件不推进 modelRound observer；模型事件和队列结算保留真实轮次。
     */
    private static int committedModelRound(TurnEvent event, int current) {
        if (event instanceof TurnEvent.ModelStepCommitted value) {
            return value.modelRound();
        }
        if (event instanceof TurnEvent.InputConsumed value && value.assistantSettlement() != null) {
            return value.assistantSettlement().modelRound();
        }
        return current;
    }

    /**
     * 从独立投影线程等待 sink 完成，阻塞只隔离该消费者且不会回压模型/工具执行线程。
     */
    private static void awaitPublish(java.util.concurrent.CompletionStage<Void> published) {
        Objects.requireNonNull(published, "published").toCompletableFuture().join();
    }

    /**
     * Provider 状态映射为公开 Tool outcome 闭集，不从 content 或展示文案推断成败。
     */
    private static ToolOutcome outcome(ToolState state) {
        return switch (state) {
            case SUCCEEDED -> ToolOutcome.SUCCEEDED;
            case FAILED -> ToolOutcome.FAILED;
            case CANCELLED -> ToolOutcome.CANCELLED;
            case PREPARED, RUNNING -> throw new IllegalArgumentException("tool result is not terminal");
        };
    }

    /**
     * 只将已验证的真实 Core outcome 映射为 Ja presentation status。
     */
    private static ToolPresentation.Status presentationStatus(ToolState state) {
        return switch (state) {
            case SUCCEEDED -> ToolPresentation.Status.SUCCESS;
            case FAILED -> ToolPresentation.Status.ERROR;
            case CANCELLED -> ToolPresentation.Status.CANCELLED;
            case PREPARED, RUNNING -> throw new IllegalArgumentException("tool result is not terminal");
        };
    }

    /**
     * Ja Session/execution 对应 Thread/Turn；非此形状的 compaction 不接入 Turn stream。
     */
    private static boolean isJaTurn(AgentIdentity identity) {
        return identity.threadId().startsWith("thr_") && identity.turnId().startsWith("turn_");
    }

    /**
     * 将租户中立身份转为仅供 Store 精确查询的复合键。
     */
    private static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return AppServerIdentity.local().executionKey(identity);
    }

    /**
     * 从指定 hostMetadata 字段读取非空字符串，错误消息不回显宿主 payload。
     */
    private static String requiredMailboxText(Map<String, String> metadata, String field) {
        String value = metadata.get(field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("mailbox metadata is incomplete");
        }
        return value;
    }

    /**
     * Engine 在自身扩展列表已冻结后绑定，避免 listener 注册阶段形成循环依赖。
     */
    public void bindEngine(AgentEngine value) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("event bridge is closed");
            }
            if (!engine.compareAndSet(null, Objects.requireNonNull(value, "engine"))) {
                throw new IllegalStateException("event bridge engine is already bound");
            }
        }
    }

    /**
     * 固定稳定 listener 身份，重复注册由 Core 扩展目录直接拒绝。
     */
    @Override
    public String id() {
        return "ja.conversation.events";
    }

    /**
     * 只借用 active map 中的 Attachment 唤醒投影线程，不接管订阅句柄；投影线程或 attach 调用方负责关闭。
     * Core 的提交通知线程只做常数时间唤醒，不在此访问 Store、RPC 或 Sink。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void onEvent(AgentEvent event) {
        Objects.requireNonNull(event, "event");
        Attachment attachment = active.get(event.identity());
        if (attachment != null) {
            attachment.signalThrough(event.persistedEvent().sequence());
        }
    }

    /**
     * Core 在独立的有界 listener 线程调用此方法；只借用已有 Attachment 等待固定水位补读，
     * 订阅仍由投影 worker/attach 调用方关闭，避免通知回调误释放其他调用者正在使用的句柄。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public void onReplayRequired(AgentEventListener.ReplayRequired replay) {
        Objects.requireNonNull(replay, "replay");
        Attachment attachment = active.get(replay.identity());
        if (attachment == null) {
            return;
        }
        attachment.awaitReplay(replay.throughSequence());
    }

    /**
     * 先订阅临时流，再从持久游标零回放，保证首次准入事件不会与 listener 建立发生竞态；
     * Engine 由组合根借用，成功后 Attachment/worker 共同拥有临时流，失败分支立即关闭新流；登记与关闭
     * 在同一生命周期锁下串行化，避免 close 清空订阅表后新 worker 越过关停边界。
     * 既有 previous Attachment 仍归原订阅者所有；非 Turn 的 compaction 不创建 Ja Timeline 投影。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public AutoCloseable attach(AgentIdentity identity, ConversationTurnEventSink sink) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(sink, "sink");
        if (!isJaTurn(identity)) {
            return () -> {
        };
        }
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("event bridge is closed");
            }
            AgentEngine bound = requireEngine();
            ResumeCheckpoint checkpoint = resumeCheckpoint(sink, identity);
            AgentStreamSubscription stream = bound.subscribeStream(identity, 128);
            Attachment attachment = null;
            try {
                attachment = new Attachment(identity, sink, stream, checkpoint);
                Attachment previous = active.putIfAbsent(identity, attachment);
                if (previous != null) {
                    throw new IllegalStateException("execution already has an event projection subscriber");
                }
                attachment.registered = true;
                Attachment attached = attachment;
                attached.worker = Thread.ofVirtual().name("ja-agent-event-" + identity.turnId())
                        .start(() -> project(attached));
                return attached;
            } catch (RuntimeException | Error failure) {
                try {
                    if (attachment == null) {
                        stream.close();
                    }
                    else {
                        attachment.close();
                    }
                } catch (RuntimeException | Error closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }
        }
    }

    /**
     * 仅接受与 Store terminal 逐字段一致的 Engine 回执，并等当前 sink 完成 terminal 投影后返回；
     * active Attachment 由 attach 的调用方及其 worker 管理，本方法只借用 Future，不负责结束订阅。
     * Core 的取消原因保留在审计事实中；公开错误字段只属于 FAILED，比较时使用同一状态映射。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public TurnResult result(AgentIdentity identity,
                             io.github.kongweiguang.ja.agent.application.api.TurnResult coreResult) {
        Objects.requireNonNull(coreResult, "coreResult");
        Attachment attachment = active.get(Objects.requireNonNull(identity, "identity"));
        TurnResult projected;
        if (attachment == null) {
            projected = terminalResult(identity);
        } else {
            try {
                projected = attachment.terminalProjection.join();
            } catch (java.util.concurrent.CompletionException projectionFailure) {
                projected = terminalResult(identity);
            }
        }
        if (projected.state() != coreResult.state() || !projected.summary().equals(coreResult.summary())
            || !Objects.equals(projected.terminal().errorCode(),
                coreResult.state() == TurnState.FAILED ? coreResult.errorCode() : null)
            || !Objects.equals(projected.terminal().errorMessage(),
                coreResult.state() == TurnState.FAILED ? coreResult.errorMessage() : null)) {
            throw new IllegalStateException("Engine result differs from committed Ja terminal facts");
        }
        return projected;
    }

    /**
     * 取消竞争或本地 completion 丢失时从精确持久 terminal 及其 record watermark 重建结果。
     */
    @Override
    public TurnResult terminalResult(AgentIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!isJaTurn(identity)) {
            throw new IllegalArgumentException("identity is not a Ja Turn");
        }
        AgentStore.PersistedEvent terminal = findTerminal(identity);
        ExecutionEvent decoded = events.decode(terminal);
        if (!(decoded instanceof ExecutionEvent.Terminal value)) {
            throw new IllegalStateException("terminal event reference changed while reading Store");
        }
        return terminalResult(identity, terminal, value, readRecordWindow(executionKey(identity), 0,
                terminal.lastRecordSequence()));
    }

    /**
     * Engine 的关闭由组合根管理；桥仅关闭临时订阅和自身虚拟线程。
     */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            active.values().forEach(Attachment::detach);
            active.clear();
            synchronized (resumeLock) {
                resumeCheckpoints.clear();
                while (collectedSinks.poll() != null) { /* Drain collected connection keys on owner shutdown. */ }
            }
        }
    }

    /**
     * 同一 sink 恢复已暂停的 Turn 时延续其确认游标；弱身份键避免桥持有 RPC 连接对象。
     */
    private ResumeCheckpoint resumeCheckpoint(ConversationTurnEventSink sink, AgentIdentity identity) {
        synchronized (resumeLock) {
            drainCollectedSinks();
            Map<AgentIdentity, ResumeCheckpoint> byIdentity = resumeCheckpoints.get(new SinkReference(sink));
            return byIdentity == null ? null : byIdentity.get(identity);
        }
    }

    /**
     * 保存每个 sink 的有限最近游标； reducer 快照随游标存放以免中段重放丢失工具/队列身份。
     */
    private void rememberCheckpoint(ConversationTurnEventSink sink, AgentIdentity identity,
                                   ResumeCheckpoint checkpoint) {
        synchronized (resumeLock) {
            // 游标写入可能与 close 并发；在 owner 锁内复核，避免关停清空后又缓存连接状态。
            if (closed.get()) {
                return;
            }
            drainCollectedSinks();
            SinkReference lookup = new SinkReference(sink);
            LinkedHashMap<AgentIdentity, ResumeCheckpoint> byIdentity = resumeCheckpoints.get(lookup);
            if (byIdentity == null) {
                byIdentity = new LinkedHashMap<>(8, 0.75f, true);
                resumeCheckpoints.put(new SinkReference(sink, collectedSinks), byIdentity);
            }
            byIdentity.put(identity, checkpoint);
            while (byIdentity.size() > MAX_RESUME_TURNS_PER_SINK) {
                byIdentity.remove(byIdentity.keySet().iterator().next());
            }
            while (resumeCheckpoints.size() > MAX_RESUME_SINKS) {
                resumeCheckpoints.remove(resumeCheckpoints.keySet().iterator().next());
            }
        }
    }

    /**
     * Terminal 或显式 owner 关闭后丢弃该 Turn reducer，避免连接缓存保留已结束的历史状态。
     */
    private void forgetCheckpoint(ConversationTurnEventSink sink, AgentIdentity identity) {
        synchronized (resumeLock) {
            drainCollectedSinks();
            SinkReference lookup = new SinkReference(sink);
            Map<AgentIdentity, ResumeCheckpoint> byIdentity = resumeCheckpoints.get(lookup);
            if (byIdentity == null) {
                return;
            }
            byIdentity.remove(identity);
            if (byIdentity.isEmpty()) {
                resumeCheckpoints.remove(lookup);
            }
        }
    }

    /**
     * 释放已被 RPC owner 回收的 sink 键及其有界 reducer 快照。
     */
    private void drainCollectedSinks() {
        SinkReference collected;
        while ((collected = (SinkReference) collectedSinks.poll()) != null) {
            resumeCheckpoints.remove(collected);
        }
    }

    /**
     * 草稿按请求身份等待估算提交；连续排空有界批次后检查持久水位，避免逐片等待和无限流饿死终态。
     */
    private void project(Attachment attachment) {
        long eventCursor = attachment.eventCursor();
        long recordCursor = attachment.recordCursor();
        AgentStreamSubscription.PollResult pendingDraft = null;
        try (AgentEventWatch watch = requireEngine().watchEvents(attachment.identity, eventCursor)) {
            readLoop:
            while (!attachment.isClosed()) {
                long draftGeneration = attachment.signalGeneration();
                if (pendingDraft == null) {
                    try {
                        pendingDraft = attachment.stream.poll(Duration.ofMillis(10));
                    } catch (RuntimeException failure) {
                        if (attachment.isClosed()) {
                            continue readLoop;
                        }
                        attachment.terminalProjection.completeExceptionally(failure);
                        notifyProjectionFailure(attachment, failure, "JaAgentEventBridge.publishDraft");
                        attachment.awaitSignalAfter(draftGeneration);
                        continue;
                    }
                }
                String draftRequestId = draftRequestId(pendingDraft);
                boolean waitingForEstimate = draftRequestId != null
                        && !attachment.hasContextUsageEstimate(draftRequestId);
                boolean receivedDraft = !waitingForEstimate && pendingDraft.event().isPresent();
                if (!waitingForEstimate) {
                    AgentStreamSubscription.PollResult draft = pendingDraft;
                    pendingDraft = null;
                    try {
                        publishDraft(attachment, draft);
                        pendingDraft = drainAvailableDrafts(attachment, null);
                    } catch (RuntimeException failure) {
                        if (attachment.isClosed()) {
                            continue readLoop;
                        }
                        attachment.terminalProjection.completeExceptionally(failure);
                        notifyProjectionFailure(attachment, failure, "JaAgentEventBridge.publishDraft");
                        attachment.awaitSignalAfter(draftGeneration);
                        continue;
                    }
                }
                AgentEventPage page = requireEngine().readEvents(attachment.identity, eventCursor, EVENT_PAGE_SIZE);
                List<AgentStore.PersistedEvent> durable = page.events();
                int index = 0;
                boolean stopAfterEstimate = false;
                while (index < durable.size()) {
                    long drainGeneration = attachment.signalGeneration();
                    try {
                        pendingDraft = drainAvailableDrafts(attachment, pendingDraft);
                    } catch (RuntimeException failure) {
                        if (attachment.isClosed()) {
                            continue readLoop;
                        }
                        attachment.terminalProjection.completeExceptionally(failure);
                        notifyProjectionFailure(attachment, failure, "JaAgentEventBridge.publishDraft");
                        attachment.awaitSignalAfter(drainGeneration);
                        continue readLoop;
                    }
                    int end = index + 1;
                    AgentStore.PersistedEvent first = durable.get(index);
                    while (end < durable.size() && sameCommit(first, durable.get(end))) {
                        end++;
                    }
                    List<AgentStore.PersistedEvent> batch = durable.subList(index, end);
                    PendingDelivery delivery = attachment.pendingDelivery();
                    if (delivery == null) {
                        long watermark = first.lastRecordSequence();
                        if (watermark < recordCursor) {
                            throw new IllegalStateException("execution record watermark regressed");
                        }
                        List<AgentStore.StoredRecord> window = readRecordWindow(executionKey(attachment.identity),
                                recordCursor, watermark);
                        long retryGeneration = attachment.signalGeneration();
                        ProjectionState checkpoint = attachment.checkpoint();
                        List<TurnEvent> projected;
                        try {
                            projected = projectBatch(attachment, batch, window);
                        } catch (RuntimeException failure) {
                            attachment.restore(checkpoint);
                            if (attachment.isClosed()) {
                                continue readLoop;
                            }
                            attachment.terminalProjection.completeExceptionally(failure);
                            notifyProjectionFailure(attachment, failure, "JaAgentEventBridge.projectBatch");
                            attachment.awaitSignalAfter(retryGeneration);
                            continue readLoop;
                        }
                        if (projected.isEmpty()) {
                            synchronized (attachment.deliveryLock) {
                                if (attachment.isClosed()) {
                                    attachment.restore(checkpoint);
                                    continue readLoop;
                                }
                                recordCursor = watermark;
                                eventCursor = batch.getLast().sequence();
                                attachment.markProjected(eventCursor, recordCursor);
                            }
                            attachment.clearProjectionFailureAfterProgress();
                            index = end;
                            continue;
                        }
                        delivery = new PendingDelivery(first, watermark, batch.getLast().sequence(), projected, 0);
                    } else if (!sameCommit(delivery.commit(), first)
                               || delivery.endSequence() != batch.getLast().sequence()) {
                        throw new IllegalStateException("pending projection commit changed during retry");
                    }

                    long retryGeneration = attachment.signalGeneration();
                    try {
                        synchronized (attachment.deliveryLock) {
                            if (attachment.isClosed()) {
                                continue readLoop;
                            }
                            if (attachment.pendingDelivery() == null) {
                                attachment.pendingDelivery(delivery);
                            }
                            delivery = publishPending(delivery, event -> {
                                int nextModelRound = committedModelRound(event, attachment.modelRound);
                                publishCommitted(attachment.sink, first, event, nextModelRound);
                                if (event instanceof TurnEvent.ContextUsageEstimated estimate) {
                                    attachment.acknowledgeContextUsageEstimate(estimate.usage().modelRequestId());
                                }
                                attachment.modelRound = nextModelRound;
                                if (event instanceof TurnEvent.Terminal terminal) {
                                    attachment.terminalDelivered = true;
                                    attachment.terminalProjection.complete(new TurnResult(terminal.state(),
                                            terminal.summary(), terminal));
                                }
                            }, attachment::pendingDelivery);
                            recordCursor = delivery.recordWatermark();
                            eventCursor = delivery.endSequence();
                            attachment.markProjected(eventCursor, recordCursor);
                        }
                    } catch (RuntimeException failure) {
                        attachment.terminalProjection.completeExceptionally(failure);
                        notifyProjectionFailure(attachment, failure, "JaAgentEventBridge.publishCommitted");
                        attachment.awaitSignalAfter(retryGeneration);
                        continue readLoop;
                    }
                    attachment.clearProjectionFailureAfterProgress();
                    index = end;
                    if (delivery.events().stream().anyMatch(TurnEvent.ContextUsageEstimated.class::isInstance)) {
                        stopAfterEstimate = true;
                        break;
                    }
                }
                if (stopAfterEstimate) {
                    continue readLoop;
                }
                long pageCursor = page.nextSequence();
                if (pageCursor < eventCursor) {
                    throw new IllegalStateException("event page cursor regressed");
                }
                eventCursor = pageCursor;
                attachment.markProjected(eventCursor, recordCursor);
                long requiredThrough = attachment.replayTarget();
                if (page.events().isEmpty() && requiredThrough > eventCursor && !page.hasMore()) {
                    throw new IllegalStateException("required committed event range is unavailable for replay");
                }
                if (page.hasMore()) {
                    continue;
                }
                if (durable.isEmpty() && !receivedDraft) {
                    watch.awaitNext(eventCursor, Duration.ofMillis(requiredThrough > eventCursor ? 1 : 50));
                    attachment.wake.tryAcquire(1, 1, TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (!attachment.isClosed()) {
                attachment.terminalProjection.completeExceptionally(interrupted);
            }
            attachment.failReplayWaiters(interrupted);
        } catch (RuntimeException | Error failure) {
            attachment.terminalProjection.completeExceptionally(failure);
            attachment.failReplayWaiters(failure);
        } finally {
            attachment.workerExited = true;
            attachment.failReplayWaiters(new IllegalStateException("event projection worker stopped"));
            attachment.stream.close();
            if (active.remove(attachment.identity, attachment)) {
                attachment.persistCheckpoint();
            }
        }
    }

    /**
     * 在越过持久提交前排空已就绪草稿；每批限定帧数以保留持久水位检查机会，不以等待历史通知限流。
     */
    private AgentStreamSubscription.PollResult drainAvailableDrafts(
            Attachment attachment, AgentStreamSubscription.PollResult pendingDraft) throws InterruptedException {
        for (int drained = 0; drained < DRAFT_BATCH_SIZE && !attachment.isClosed(); drained++) {
            if (pendingDraft == null) {
                pendingDraft = attachment.stream.poll(Duration.ZERO);
                if (pendingDraft.state() != AgentStreamSubscription.State.OPEN || pendingDraft.event().isEmpty()) {
                    publishDraft(attachment, pendingDraft);
                    return null;
                }
            }
            String requestId = draftRequestId(pendingDraft);
            if (requestId != null && !attachment.hasContextUsageEstimate(requestId)) {
                return pendingDraft;
            }
            publishDraft(attachment, pendingDraft);
            pendingDraft = null;
        }
        return pendingDraft;
    }

    /**
     * 临时流使用连续公开序号；溢出显式请求权威重读并停止缺片草稿，持久提交仍继续补齐全文和终态。
     */
    private void publishDraft(Attachment attachment, AgentStreamSubscription.PollResult poll) {
        if (poll.state() == AgentStreamSubscription.State.RESYNC_REQUIRED) {
            attachment.stream.close();
            awaitPublish(attachment.sink.publishProjectionFailure(new TurnProjectionFailure(
                    attachment.identity.threadId(), attachment.identity.turnId(),
                    TurnProjectionFailure.Reason.PROJECTION_FAILED)));
            return;
        }
        poll.event().ifPresent(event -> {
            if (!attachment.identity.equals(event.context().identity())) {
                throw new IllegalStateException("temporary event identity changed");
            }
            TurnEvent projected = switch (event) {
                case io.github.kongweiguang.ja.agent.application.api.TurnEvent.TextDelta value ->
                        new TurnEvent.TextDelta(attachment.identity.turnId(),
                                attachment.nextDraftSequence(), value.text(), value.context().modelRequestId());
                case io.github.kongweiguang.ja.agent.application.api.TurnEvent.ReasoningSummaryDelta value ->
                        new TurnEvent.ReasoningSummaryDelta(
                                attachment.identity.turnId(), attachment.nextDraftSequence(), value.text(),
                                value.context().modelRequestId());
                case io.github.kongweiguang.ja.agent.application.api.TurnEvent.Reset ignored -> null;
            };
            if (projected != null) {
                awaitPublish(attachment.sink.publish(projected));
            }
        });
    }

    /**
     * 为同一次原子提交按持久事件顺序生成需要公开的 Ja 事件，并先更新固定 watermark 中的队列事实。
     * 消费提交以 InputAccepted 为通知入口，不要求 Core 另写仅用于编辑队列的控制事件；estimate 与 wait
     * 的主投影不能吞掉同一提交中先写入的 Turn 状态迁移。
     *
     * @author kongweiguang
     */
    private List<TurnEvent> projectBatch(Attachment attachment, List<AgentStore.PersistedEvent> batch,
                                         List<AgentStore.StoredRecord> window) {
        List<DecodedEvent> decoded = batch.stream().map(event ->
                new DecodedEvent(event, events.decode(event))).toList();
        updateInputQueue(attachment, window);
        projectContextCompactions(attachment, decoded);
        DecodedEvent terminal = find(decoded, ExecutionEvent.Terminal.class);
        if (terminal != null) {
            return List.of(terminalResult(attachment.identity, terminal.persisted(),
                (ExecutionEvent.Terminal) terminal.event(), window).terminal());
        }
        DecodedEvent contextUsage = find(decoded, ExecutionEvent.ContextUsageEstimated.class);
        if (contextUsage != null) {
            ExecutionEvent.ContextUsageEstimated estimate = (ExecutionEvent.ContextUsageEstimated) contextUsage.event();
            ExecutionFacts.Usage usage = requireFact(exactRecord(attachment.identity, estimate.usageRecordId(),
                    contextUsage.persisted().lastRecordSequence()), ExecutionFacts.Usage.class);
            if (usage.purpose() != ExecutionFacts.UsagePurpose.ASSISTANT
                || usage.certainty() != ExecutionFacts.UsageCertainty.UNKNOWN
                || usage.estimatedContextTokens() == null) {
                throw new IllegalStateException("ContextUsageEstimated does not reference an Assistant estimate");
            }
            if (attachment.hasContextUsageEstimate(usage.modelRequestId())) {
                throw new IllegalStateException("model request has more than one context estimate event");
            }
            TurnEvent estimateProjection = new TurnEvent.ContextUsageEstimated(context(contextUsage.persisted()),
                    providerUsage(usage), usage.estimatedContextTokens());
            return withStateTransition(decoded, contextUsage, estimateProjection);
        }
        DecodedEvent model = find(decoded, ExecutionEvent.ModelCompleted.class);
        if (model != null) {
            return optionalEvent(modelStep(attachment, model.persisted(),
                (ExecutionEvent.ModelCompleted) model.event()));
        }
        DecodedEvent toolCompleted = find(decoded, ExecutionEvent.ToolCompleted.class);
        if (toolCompleted != null) {
            return List.of(toolCompleted(attachment, toolCompleted.persisted(),
                (ExecutionEvent.ToolCompleted) toolCompleted.event(), window));
        }
        DecodedEvent recovery = find(decoded, ExecutionEvent.ToolRecoveryChanged.class);
        if (recovery != null) {
            return List.of(toolRecoveryChanged(recovery.persisted(),
                (ExecutionEvent.ToolRecoveryChanged) recovery.event()));
        }
        DecodedEvent waitRequested = find(decoded, ExecutionEvent.WaitRequested.class);
        if (waitRequested != null) {
            TurnEvent approval = approvalRequested(attachment, waitRequested.persisted(),
                    (ExecutionEvent.WaitRequested) waitRequested.event(), window);
            return withStateTransition(decoded, waitRequested, approval);
        }
        DecodedEvent waitSettled = findControl(decoded, ExecutionEvent.ControlAction.WAIT_RESOLVED);
        if (waitSettled != null) {
            return projectWaitSettlementBatch(decoded, waitSettled, window);
        }
        DecodedEvent toolStarted = find(decoded, ExecutionEvent.ToolStarted.class);
        if (toolStarted != null) {
            return List.of(toolStarted(attachment, toolStarted.persisted(),
                (ExecutionEvent.ToolStarted) toolStarted.event(), window));
        }
        DecodedEvent inputChanged = findControl(decoded, ExecutionEvent.ControlAction.INPUT_CHANGED);
        DecodedEvent inputAccepted = find(decoded, ExecutionEvent.InputAccepted.class);
        if (inputChanged != null || inputAccepted != null) {
            return inputQueueChanged(attachment,
                    inputChanged != null ? inputChanged.persisted() : inputAccepted.persisted(), decoded, window);
        }
        DecodedEvent retry = find(decoded, ExecutionEvent.RetryScheduled.class);
        if (retry != null) {
            return List.of(new TurnEvent.RetryStarted(context(retry.persisted()),
                Math.addExact(((ExecutionEvent.RetryScheduled) retry.event()).attempt(), 1)));
        }
        DecodedEvent state = find(decoded, ExecutionEvent.StateChanged.class);
        if (state != null) {
            ExecutionEvent.StateChanged value = (ExecutionEvent.StateChanged) state.event();
            return List.of(new TurnEvent.StateChanged(context(state.persisted()), value.from(), value.to()));
        }
        return List.of();
    }

    /**
     * Project automatic compression phases from the durable batch before selecting its primary Turn frame; manual
     * compaction uses a distinct non-Turn identity and remains owned by ManualContextCompactionService.
     *
     * @author kongweiguang
     */
    private void projectContextCompactions(Attachment attachment, List<DecodedEvent> decoded) {
        for (DecodedEvent value : decoded) {
            AgentStore.PersistedEvent persisted = value.persisted();
            if (value.event() instanceof ExecutionEvent.ContextCompactionStarted started) {
                automaticCompactionContext(attachment, persisted, started.compactionId(), started.sourceRevision(),
                        started.inputTokensBefore(), null).ifPresent(context ->
                        awaitPublish(attachment.sink.publish(new ContextCompactionEvent.Started(context))));
            } else if (value.event() instanceof ExecutionEvent.ContextCompacted compacted
                    && compacted.outcome() == ExecutionEvent.CompactionOutcome.COMPACTED) {
                automaticCompactionContext(attachment, persisted, compacted.compactionId(),
                        compacted.sourceRevision(), compacted.inputTokensBefore(), compacted.inputTokensAfter())
                        .ifPresent(context -> awaitPublish(attachment.sink.publish(
                                new ContextCompactionEvent.Compacted(context, compacted.checkpointId()))));
            } else if (value.event() instanceof ExecutionEvent.ContextCompactionFailed failed) {
                automaticCompactionContext(attachment, persisted, failed.compactionId(), failed.sourceRevision(),
                        failed.inputTokensBefore(), null).ifPresent(context ->
                        awaitPublish(attachment.sink.publish(new ContextCompactionEvent.Failed(context,
                                automaticCompactionErrorCode(failed.reasonCode())))));
            }
        }
    }

    /**
     * Resolve workspace only at the RPC sink boundary; durable Agent Store events intentionally own Thread/Turn
     * revisions but do not guess workspace routing. A sink without RPC ownership remains a deliberate no-op.
     *
     * @author kongweiguang
     */
    private static Optional<ContextCompactionEvent.Context> automaticCompactionContext(
            Attachment attachment, AgentStore.PersistedEvent persisted, String compactionId, long sourceRevision,
            Long inputTokensBefore, Long inputTokensAfter) {
        if (!persisted.execution().turnId().equals(attachment.identity.turnId())
                || !persisted.conversation().threadId().equals(attachment.identity.threadId())) {
            throw new IllegalStateException("compaction event identity changed during projection");
        }
        Optional<String> workspaceId = attachment.sink.workspaceId(attachment.identity);
        if (workspaceId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ContextCompactionEvent.Context(persisted.eventId(), workspaceId.orElseThrow(),
                persisted.conversation().threadId(), persisted.execution().turnId(), persisted.threadRevision(),
                persisted.occurredAt(), compactionId, ContextCompactionEvent.Trigger.AUTOMATIC, sourceRevision,
                inputTokensBefore, inputTokensAfter, ContextCompactionEvent.STRATEGY_VERSION,
                persisted.mutationVersion()));
    }

    /**
     * Keep summary failure projection stable and provider-neutral; unknown stored categories fail closed as
     * INVALID_STATE instead of exposing an exception message or inventing a new wire code.
     *
     * @author kongweiguang
     */
    private static ContextCompactionEvent.ErrorCode automaticCompactionErrorCode(String reasonCode) {
        return switch (Objects.requireNonNullElse(reasonCode, "")) {
            case "CAS_CONFLICT", "CONFLICT" -> ContextCompactionEvent.ErrorCode.CONFLICT;
            case "SUMMARY_FAILURE" -> ContextCompactionEvent.ErrorCode.SUMMARY_FAILURE;
            case "CONTEXT_LIMIT" -> ContextCompactionEvent.ErrorCode.CONTEXT_LIMIT;
            case "CANCELLED" -> ContextCompactionEvent.ErrorCode.CANCELLED;
            case "THREAD_BUSY" -> ContextCompactionEvent.ErrorCode.THREAD_BUSY;
            default -> ContextCompactionEvent.ErrorCode.INVALID_STATE;
        };
    }

    /**
     * 同一 durable commit 可能把 Turn 状态和 estimate/wait 一起提交；按事件序号同时投影，避免
     * 优先投影业务事件时静默丢失状态变化。普通澄清没有 ApprovalRequested，也仍须发布状态帧。
     *
     * @author kongweiguang
     */
    private static List<TurnEvent> withStateTransition(List<DecodedEvent> decoded, DecodedEvent projectionSource,
                                                       TurnEvent projection) {
        DecodedEvent state = find(decoded, ExecutionEvent.StateChanged.class);
        if (state == null) {
            return optionalEvent(projection);
        }
        ExecutionEvent.StateChanged value = (ExecutionEvent.StateChanged) state.event();
        TurnEvent.StateChanged stateProjection = new TurnEvent.StateChanged(context(state.persisted()),
                value.from(), value.to());
        if (projection == null) {
            return List.of(stateProjection);
        }
        return state.persisted().sequence() < projectionSource.persisted().sequence()
                ? List.of(stateProjection, projection) : List.of(projection, stateProjection);
    }

    /**
     * 等待结算和 Turn 状态可能同事务提交；分别投影并保留 durable event 顺序，避免关闭审批吞掉状态迁移。
     */
    private List<TurnEvent> projectWaitSettlementBatch(List<DecodedEvent> decoded, DecodedEvent waitSettled,
                                                       List<AgentStore.StoredRecord> window) {
        TurnEvent waitProjection = approvalWaitSettled(waitSettled.persisted(),
                (ExecutionEvent.ControlRecorded) waitSettled.event(), window);
        List<TurnEvent> result = new ArrayList<>();
        for (DecodedEvent value : decoded) {
            if (value == waitSettled) {
                if (waitProjection != null) {
                    result.add(waitProjection);
                }
            } else if (value.event() instanceof ExecutionEvent.StateChanged(TurnState from, TurnState v)
                       && !(waitProjection instanceof TurnEvent.ApprovalResolved)) {
                result.add(new TurnEvent.StateChanged(context(value.persisted()), from, v));
            }
        }
        return List.copyOf(result);
    }

    /**
     * ModelCompleted.messageRecordId 是 Store 记录 ID，消息 ID 独立投影；校验来源与整批提案后，
     * 仅对含 Tool calls 的模型轮次发送 ModelStepCommitted，纯文本结果交给同一执行的 Terminal 表达。
     */
    private TurnEvent.ModelStepCommitted modelStep(Attachment attachment, AgentStore.PersistedEvent persisted,
                                                   ExecutionEvent.ModelCompleted event) {
        AgentStore.StoredRecord messageRecord = exactRecord(attachment.identity, event.messageRecordId(),
                persisted.lastRecordSequence());
        ExecutionFacts.AssistantMessage message = requireFact(messageRecord, ExecutionFacts.AssistantMessage.class);
        String itemId = JaMessageIds.toItemId(message.messageId());
        if (!message.message().content().stream().filter(ToolCallContent.class::isInstance)
                .map(ToolCallContent.class::cast).map(ToolCallContent::callId).toList().equals(event.toolCallIds())) {
            throw new IllegalStateException("ModelCompleted differs from its assistant message fact");
        }
        List<ToolCallPreviewReadPort.ToolCallPreview> previews = ToolCallPreviewReadPort.requireCompleteBatch(
                callPreviews.read(executionKey(attachment.identity), event.messageRecordId(),
                        persisted.eventId(), persisted.sequence()));
        if (previews.size() != event.toolCallIds().size()) {
            throw new IllegalStateException("ModelCompleted preview batch is incomplete");
        }
        Map<String, ToolCallContent> modelCalls = toolCalls(message.message());
        List<TurnEvent.ToolCall> calls = new ArrayList<>(previews.size());
        for (ToolCallPreviewReadPort.ToolCallPreview preview : previews) {
            ToolCallContent source = modelCalls.get(preview.callId());
            if (source == null || !source.name().equals(preview.toolName())
                || preview.sourceEventSequence() != persisted.sequence()
                || !preview.sourceEventId().equals(persisted.eventId())) {
                throw new IllegalStateException("ModelCompleted preview identity does not match its source event");
            }
            ToolPresentation presentation = presentations.read(preview.presentationJson());
            if (presentation.status() != ToolPresentation.Status.PENDING) {
                throw new IllegalStateException("Model call preview is not a pending proposal");
            }
            attachment.toolCalls.put(preview.callId(), new ToolInfo(source.name(), preview.ordinal(), presentation,
                    false));
            calls.add(new TurnEvent.ToolCall(preview.callId(), source.name(), presentation, preview.ordinal()));
        }
        if (!event.toolCallIds().equals(calls.stream().map(TurnEvent.ToolCall::callId).toList())) {
            throw new IllegalStateException("Model call preview order differs from provider order");
        }
        ExecutionFacts.Usage usage = requireFact(exactRecord(attachment.identity, event.usageRecordId(),
                persisted.lastRecordSequence()), ExecutionFacts.Usage.class);
        if (usage.purpose() != ExecutionFacts.UsagePurpose.ASSISTANT
            || usage.modelRound() != event.modelRound()) {
            throw new IllegalStateException("ModelCompleted usage identity is inconsistent");
        }
        if (calls.isEmpty()) {
            return null;
        }
        return new TurnEvent.ModelStepCommitted(context(persisted), itemId,
                message.publicText(), message.reasoningSummary(), message.modelRound(), providerUsage(usage), calls);
    }

    /**
     * ToolStarted 校验同提交 Prepared 事实，或续跑前已由持久等待提交校验并冻结的同一调用事实。
     */
    private TurnEvent.ToolStarted toolStarted(Attachment attachment, AgentStore.PersistedEvent persisted,
                                              ExecutionEvent.ToolStarted event,
                                              List<AgentStore.StoredRecord> window) {
        ToolInfo proposal = attachment.toolCalls.get(event.callId());
        if (proposal == null || proposal.ordinal() != event.ordinal()
            || !proposal.toolName().equals(event.toolName())) {
            throw new IllegalStateException("ToolStarted has no matching provider call proposal");
        }
        ToolInfo prepared = freezePreparedCall(attachment, event.callId(), window);
        attachment.toolCalls.put(event.callId(), prepared);
        return new TurnEvent.ToolStarted(context(persisted), event.callId(), event.ordinal());
    }

    /**
     * ToolCompleted 只能使用脱敏 completion fact；未通过 Ja adapter 的拒绝使用安全 proposal 降级展示。
     */
    private TurnEvent.ToolBatchCommitted toolCompleted(Attachment attachment, AgentStore.PersistedEvent persisted,
                                                       ExecutionEvent.ToolCompleted event,
                                                       List<AgentStore.StoredRecord> window) {
        ToolInfo known = attachment.toolCalls.get(event.callId());
        if (known == null) {
            throw new IllegalStateException("ToolCompleted has no matching model call proposal");
        }
        List<PresentationFact> matching = presentationFacts(window, event.callId(), 1);
        ToolPresentation presentation;
        if (matching.size() == 1) {
            PresentationFact fact = matching.getFirst();
            String expectedState = event.state().name();
            if (!expectedState.equals(fact.state())) {
                throw new IllegalStateException("ToolCompleted presentation state differs from tool outcome");
            }
            presentation = presentations.read(fact.presentationJson());
            if (presentation.status() != presentationStatus(event.state())) {
                throw new IllegalStateException("ToolCompleted presentation status differs from tool outcome");
            }
        } else if (matching.isEmpty() && !known.prepared()) {
            presentation = ToolPresentationProjector.withStatus(known.presentation(), presentationStatus(event.state()));
        } else {
            throw new IllegalStateException("ToolCompleted has missing or duplicate presentation facts");
        }
        attachment.toolCalls.remove(event.callId());
        TurnEvent.ToolBatchResult result = new TurnEvent.ToolBatchResult(event.callId(), outcome(event.state()),
                presentation, known.ordinal(), event.errorCode());
        return new TurnEvent.ToolBatchCommitted(context(persisted), List.of(result));
    }

    /**
     * 恢复事件通过 event 引用精确解码 Core 裁决，不从 latest cursor 或 Ja 工具状态猜测。
     */
    private TurnEvent.ToolRecoveryChanged toolRecoveryChanged(AgentStore.PersistedEvent persisted,
                                                              ExecutionEvent.ToolRecoveryChanged event) {
        AgentStore.StoredRecord record = exactRecord(persisted.execution(), event.decisionRecordId(),
                persisted.lastRecordSequence());
        ToolRecoveryDecision decision = controls.decodeToolRecoveryDecision(record);
        if (!decision.recoveryId().equals(event.recoveryId()) || !decision.callId().equals(event.callId())
            || decision.revision() != event.revision()) {
            throw new IllegalStateException("tool recovery event differs from its decision record");
        }
        return new TurnEvent.ToolRecoveryChanged(context(persisted), decision.recoveryId(), decision.callId(),
                decision.revision(), decision.status(), decision.disposition());
    }

    /**
     * 等待从同水位 wait 与事实组装；审批携带冻结的安全展示，普通问题交由 InteractionService 投影。
     */
    private TurnEvent.ApprovalRequested approvalRequested(Attachment attachment,
                                                          AgentStore.PersistedEvent persisted,
                                                          ExecutionEvent.WaitRequested event,
                                                          List<AgentStore.StoredRecord> window) {
        InteractionWait wait = controls.decodeWait(exactRecord(persisted.execution(), event.requestRecordId(),
                persisted.lastRecordSequence()));
        if (!"approval".equals(textAttribute(wait.attributes(), "kind"))) {
            if (wait.toolCallId() != null) {
                freezePreparedCall(attachment, wait.toolCallId(), window);
            }
            return null;
        }
        ExecutionFacts.Approval approval = approvalFact(window, wait.interactionId(), null);
        if (!approval.callId().equals(wait.toolCallId()) || approval.decision() != null) {
            throw new IllegalStateException("approval wait differs from its pending approval fact");
        }
        ToolInfo info = attachment.toolCalls.get(approval.callId());
        if (info == null) {
            throw new IllegalStateException("approval wait has no model call identity");
        }
        String reason = wait.questions().getFirst().prompt();
        ToolInfo prepared = freezePreparedCall(attachment, approval.callId(), window);
        return new TurnEvent.ApprovalRequested(context(persisted), wait.interactionId(), approval.callId(),
                prepared.toolName(), prepared.presentation(), reason, approval.expiresAt());
    }

    /**
     * 等待前的 Prepared fact 已按该提交水位校验并冻结，可在续跑的新 watermark 中继续使用。
     */
    private ToolInfo freezePreparedCall(Attachment attachment, String callId,
                                        List<AgentStore.StoredRecord> window) {
        ToolInfo known = attachment.toolCalls.get(callId);
        if (known == null) {
            throw new IllegalStateException("prepared Tool call has no model proposal");
        }
        List<PresentationFact> facts = presentationFacts(window, callId, 0);
        ToolPresentation presentation;
        if (facts.size() == 1) {
            presentation = presentationFact(window, callId, 0, "PREPARED", true);
            if (known.prepared() && !known.presentation().equals(presentation)) {
                throw new IllegalStateException("prepared Tool presentation changed after it was frozen");
            }
        } else if (facts.isEmpty() && known.prepared()) {
            presentation = known.presentation();
        } else {
            throw new IllegalStateException("prepared Tool presentation fact is missing or duplicated");
        }
        ToolInfo frozen = new ToolInfo(known.toolName(), known.ordinal(), presentation, true);
        attachment.toolCalls.put(callId, frozen);
        return frozen;
    }

    /**
     * 结算事件精确指向同事务 wait 或答案提交记录；普通问题仍由 InteractionService 发布。
     */
    private TurnEvent approvalWaitSettled(AgentStore.PersistedEvent persisted,
                                          ExecutionEvent.ControlRecorded event,
                                          List<AgentStore.StoredRecord> window) {
        AgentStore.StoredRecord reference = exactRecord(persisted.execution(), event.controlRecordId(),
                persisted.lastRecordSequence());
        InteractionWait wait;
        if (event.reasonCode() == null) {
            InteractionAnswerSubmission submission = controls.decodeAnswerSubmission(reference);
            if (!submission.answerId().equals(event.controlRecordId())) {
                throw new IllegalStateException("wait settlement answer reference changed");
            }
            wait = findResolvedWait(window, submission.interactionId());
            if (wait.status() != InteractionStatus.ANSWERED
                || wait.interactionRevision() != submission.expectedInteractionRevision() + 1
                || !wait.answers().equals(submission.answers())) {
                throw new IllegalStateException("wait answer submission differs from its settled wait");
            }
        } else {
            InteractionStatus status = switch (event.reasonCode()) {
                case "CANCELLED" -> InteractionStatus.CANCELLED;
                case "SUPERSEDED" -> InteractionStatus.SUPERSEDED;
                case "EXPIRED" -> InteractionStatus.EXPIRED;
                default -> throw new IllegalStateException("wait settlement close reason is unsupported");
            };
            wait = controls.decodeWait(reference);
            if (wait.status() != status || !wait.answers().isEmpty()) {
                throw new IllegalStateException("wait close control differs from its persisted wait");
            }
        }
        if (!"approval".equals(textAttribute(wait.attributes(), "kind"))) {
            return null;
        }
        if (wait.status() != InteractionStatus.ANSWERED) {
            return new TurnEvent.ApprovalClosed(context(persisted), wait.interactionId(), wait.status());
        }
        ExecutionFacts.Approval approval = approvalFact(window, wait.interactionId(), null);
        if (approval.decision() == null || !Objects.equals(wait.toolCallId(), approval.callId())) {
            throw new IllegalStateException("resolved approval has no matching settled user decision");
        }
        return new TurnEvent.ApprovalResolved(context(persisted), wait.interactionId(), approval.decision());
    }

    /**
     * 队列命令与控制事件在同一 watermark 重放，输入内容始终按 record id 精确读取。
     * @author kongweiguang
     */
    private void updateInputQueue(Attachment attachment, List<AgentStore.StoredRecord> window) {
        attachment.inputQueueProjection.apply(window, controls);
    }

    /**
     * 消费消息只在本次提交窗口按 messageId 定位；排队与消费后的 recordId 独立，不能互相代替。
     * 精确读取原排队事实核对身份及完整正文，跨会话消息仍保留独立的 mailbox 展示语义。
     */
    private List<TurnEvent> inputQueueChanged(Attachment attachment, AgentStore.PersistedEvent persisted,
                                              List<DecodedEvent> decoded, List<AgentStore.StoredRecord> window) {
        List<AgentInputQueue.Command> commands = window.stream()
                .filter(record -> io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec
                        .INPUT_QUEUE_COMMAND_RECORD_TYPE.equals(record.recordType()))
                .map(controls::decodeInputQueueCommand).toList();
        List<AgentInputQueue.Command> consumed = commands.stream()
                .filter(command -> command.action() == AgentInputQueue.Action.CONSUME).toList();
        if (consumed.isEmpty()) {
            return List.of(new TurnEvent.InputQueueChanged(context(persisted),
                projectInputQueue(attachment, true, persisted.lastRecordSequence())));
        }
        List<DecodedEvent> accepted = decoded.stream()
                .filter(value -> value.event() instanceof ExecutionEvent.InputAccepted).toList();
        if (consumed.size() != 1 || accepted.size() != 1) {
            throw new IllegalStateException("queue consumption must bind one accepted input per commit");
        }
        AgentInputQueue.Command command = consumed.getFirst();
        ConsumedInput consumedInput = findQueueEntryBefore(attachment, command.inputId(), command.queueRevision());
        AgentInputQueue.Entry source = consumedInput.entry();
        DecodedEvent acceptedEvent = accepted.getFirst();
        ExecutionEvent.InputAccepted inputAccepted = (ExecutionEvent.InputAccepted) acceptedEvent.event();
        List<AgentStore.StoredRecord> acceptedUsers = window.stream()
                .filter(record -> "agent.user.message".equals(record.recordType()))
                .filter(record -> records.decodeUser(record).messageId().equals(inputAccepted.messageId())).toList();
        if (acceptedUsers.size() != 1) {
            throw new IllegalStateException("accepted input must bind one user message in its commit");
        }
        AgentStore.StoredRecord userRecord = acceptedUsers.getFirst();
        UserMessage user = records.decodeUser(userRecord);
        UserMessage queuedUser = records.decodeQueuedUser(exactRecord(attachment.identity, source.messageRecordId(),
                persisted.lastRecordSequence()));
        if (!queuedUser.messageId().equals(inputAccepted.messageId()) || !queuedUser.content().equals(user.content())) {
            throw new IllegalStateException("consumed queue message differs from accepted input");
        }
        InputQueue after = projectInputQueue(attachment, true, persisted.lastRecordSequence());
        if (source.kind() == AgentInputQueue.Kind.MESSAGE) {
            MailboxMetadata mailbox = Objects.requireNonNull(consumedInput.mailbox(), "mailbox input metadata");
            ThreadSnapshot.ThreadMessageItem message = mailbox.toThreadMessage(attachment.identity, user);
            return List.of(new TurnEvent.MessagesReceived(context(acceptedEvent.persisted(), "turn/messages_received"),
                    List.of(message)), new TurnEvent.InputQueueChanged(context(persisted, "turn/input-queue-changed"),
                    after));
        }
        InputQueue.QueuedInput queued = queuedInput(attachment.identity, source, persisted.lastRecordSequence());
        if (!queued.content().equals(user.content())) {
            throw new IllegalStateException("consumed queue content differs from accepted input");
        }
        TurnEvent.UserItem item = userItem(attachment.identity, userRecord, user);
        return List.of(new TurnEvent.InputConsumed(context(acceptedEvent.persisted()), queued, item, after, null));
    }

    /**
     * 从事件前的队列版本中取得被消费项，禁止用消费后的空队列反推原始输入。
     */
    private ConsumedInput findQueueEntryBefore(Attachment attachment, String inputId, long consumedRevision) {
        return attachment.inputQueueProjection.consume(inputId, consumedRevision);
    }

    /**
     * Ja queue DTO 从通用待处理事实投影，不读取工作区或按当前配置回退。
     */
    private InputQueue projectInputQueue(Attachment attachment, boolean accepting, long watermark) {
        List<InputQueue.QueuedInput> items = attachment.inputQueueProjection.queue().entries().stream()
                .filter(entry -> entry.kind() != AgentInputQueue.Kind.MESSAGE)
                .map(entry -> queuedInput(attachment.identity, entry, watermark)).toList();
        return new InputQueue(attachment.identity.turnId(), attachment.inputQueueProjection.queue().revision(),
                accepting, items);
    }

    /**
     * 从实际 queued user record 解码正文，并只读取已绑定 Thread 的附件展示元数据。
     */
    private InputQueue.QueuedInput queuedInput(AgentIdentity identity, AgentInputQueue.Entry entry, long watermark) {
        AgentStore.StoredRecord record = exactRecord(identity, entry.messageRecordId(), watermark);
        UserMessage message = records.decodeQueuedUser(record);
        List<AttachmentSummary> summaries = attachmentSummaries(identity.threadId(), message.content().attachmentIds());
        InputQueue.Kind kind = switch (entry.kind()) {
            case STEERING -> InputQueue.Kind.STEERING;
            case FOLLOW_UP -> InputQueue.Kind.FOLLOW_UP;
            case MESSAGE -> throw new IllegalArgumentException("mailbox messages use their own timeline event");
        };
        InputQueue.Status status = switch (entry.status()) {
            case PENDING -> InputQueue.Status.PENDING;
            case NEEDS_ATTENTION -> InputQueue.Status.NEEDS_ATTENTION;
        };
        InputQueue.Issue issue = entry.issue() == null ? null : new InputQueue.Issue(entry.issue().code(),
                entry.issue().message(), entry.issue().retryable());
        return new InputQueue.QueuedInput(entry.inputId(), identity.turnId(), message.content(), kind, summaries,
                status, issue, entry.inputRevision(), entry.createdAt());
    }

    /**
     * 将已接受输入 record 的时间、正文与附件绑定到同一公开 Timeline item。
     */
    private TurnEvent.UserItem userItem(AgentIdentity identity, AgentStore.StoredRecord record, UserMessage message) {
        return new TurnEvent.UserItem(JaMessageIds.toItemId(message.messageId()), record.occurredAt(),
                identity.turnId(), message.content(), attachmentSummaries(identity.threadId(),
                message.content().attachmentIds()));
    }

    /**
     * 附件为空时不触碰服务；受授权元数据只在此处裁剪为完整有序会话快照。
     */
    private List<AttachmentSummary> attachmentSummaries(String threadId, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentMetadata> metadata = attachments.readAuthorizedMetadata(threadId, ids);
        if (!ids.equals(metadata.stream().map(AttachmentMetadata::attachmentId).toList())) {
            throw new IllegalStateException("authorized attachment metadata differs from accepted input order");
        }
        return metadata.stream().map(value -> new AttachmentSummary(value.attachmentId(), value.displayName(),
                value.sizeBytes(), value.mediaKind().name().toLowerCase(Locale.ROOT), value.mediaType())).toList();
    }

    /**
     * 只投影终态水位内的引用；取消原因保留 Core 审计，公开错误仅用于 FAILED，未知 ChangeSet 不合成。
     */
    private TurnResult terminalResult(AgentIdentity identity, AgentStore.PersistedEvent persisted,
                                      ExecutionEvent.Terminal terminal, List<AgentStore.StoredRecord> window) {
        TurnEvent.FinalMessage finalMessage = null;
        if (terminal.messageRecordId() != null) {
            ExecutionFacts.AssistantMessage assistant = requireFact(exactRecord(identity, terminal.messageRecordId(),
                    persisted.lastRecordSequence()), ExecutionFacts.AssistantMessage.class);
            finalMessage = new TurnEvent.FinalMessage(JaMessageIds.toItemId(assistant.messageId()),
                    assistant.publicText());
        }
        ProviderRequestUsage usage = terminal.usageRecordId() == null ? null : providerUsage(
                requireFact(exactRecord(identity, terminal.usageRecordId(), persisted.lastRecordSequence()),
                        ExecutionFacts.Usage.class));
        List<AgentTool.ExtensionFact> matching = window.stream()
                .filter(record -> ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType()))
                .map(extensionFacts::decode)
                .filter(fact -> fact.namespace().equals("ja.turn") && fact.type().equals("change-set"))
                .toList();
        if (matching.size() > 1) {
            throw new IllegalStateException("terminal has duplicate ChangeSet facts");
        }
        TurnChangeSet changeSet = null;
        if (!matching.isEmpty()) {
            AgentTool.ExtensionFact changeFact = matching.getFirst();
            if (changeFact.schemaVersion() != 1) {
                throw new IllegalStateException("terminal ChangeSet extension fact is unsupported");
            }
            changeSet = changes.readPayload(changeFact.payload());
        } else if (terminal.state() == TurnState.COMPLETED) {
            throw new IllegalStateException("completed terminal ChangeSet extension fact is missing");
        }
        TurnEvent.Terminal event = new TurnEvent.Terminal(context(persisted), terminal.state(), terminal.summary(),
                terminal.state() == TurnState.FAILED ? terminal.errorCode() : null,
                terminal.state() == TurnState.FAILED ? terminal.errorMessage() : null,
                finalMessage, usage, changeSet);
        return new TurnResult(terminal.state(), terminal.summary(), event);
    }

    /**
     * 固定 Store 事件水位并分页读取唯一终态，避免取消竞争时依赖 Engine 内存状态或混入后续提交。
     */
    private AgentStore.PersistedEvent findTerminal(AgentIdentity identity) {
        AgentStore.ConversationKey conversation = executionKey(identity).conversation();
        AgentStore.ConversationSnapshot snapshot = store.loadConversation(conversation)
                .orElseThrow(() -> new IllegalStateException("execution conversation is missing from Store"));
        long watermark = snapshot.eventSequence();
        long cursor = 0;
        AgentStore.PersistedEvent result = null;
        while (true) {
            AgentStore.EventPage page = store.readEvents(AgentStore.EventQuery.withDefaultMaxBytes(
                    conversation, cursor, watermark, EVENT_PAGE_SIZE));
            long previousSequence = cursor;
            for (AgentStore.PersistedEvent persisted : page.events()) {
                if (!persisted.conversation().equals(conversation) || persisted.sequence() <= previousSequence
                    || persisted.sequence() > watermark) {
                    throw new IllegalStateException("store returned an event outside the captured sequence range");
                }
                previousSequence = persisted.sequence();
                if (persisted.execution().equals(executionKey(identity))
                    && events.decode(persisted) instanceof ExecutionEvent.Terminal) {
                    if (result != null) {
                        throw new IllegalStateException("execution has multiple terminal events");
                    }
                    result = persisted;
                }
            }
            long next = page.nextSequence();
            if (next < cursor || next > watermark || (page.hasMore() && next <= cursor)) {
                throw new IllegalStateException("terminal event page cursor is outside the captured range");
            }
            cursor = next;
            if (!page.hasMore()) {
                break;
            }
        }
        if (cursor != watermark) {
            throw new IllegalStateException("store event history does not reach the captured watermark");
        }
        if (result == null) {
            throw new IllegalStateException("execution has no committed terminal event");
        }
        return result;
    }

    /**
     * 从窗口内唯一 Ja presentation envelope 校验 callId、revision、状态与 payload。
     */
    private ToolPresentation presentationFact(List<AgentStore.StoredRecord> window, String callId,
                                              int revision, String expectedState, boolean required) {
        List<PresentationFact> matches = presentationFacts(window, callId, revision);
        if (matches.isEmpty() && !required) {
            return null;
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("tool presentation fact is missing or duplicated");
        }
        PresentationFact value = matches.getFirst();
        if (!expectedState.equals(value.state())) {
            throw new IllegalStateException("tool presentation fact state is inconsistent");
        }
        ToolPresentation result = presentations.read(value.presentationJson());
        if (revision == 0 && result.status() != ToolPresentation.Status.PENDING) {
            throw new IllegalStateException("prepared presentation is not pending");
        }
        return result;
    }

    /**
     * 仅扫描本次固定 commit 的新记录，绝不从未来 watermarks 补读工具展示信息。
     */
    private List<PresentationFact> presentationFacts(List<AgentStore.StoredRecord> window,
                                                     String callId, int revision) {
        List<PresentationFact> values = new ArrayList<>();
        for (AgentStore.StoredRecord record : window) {
            if (!ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            AgentTool.ExtensionFact fact = extensionFacts.decode(record);
            if (!fact.namespace().equals("ja.tool") || !fact.type().equals("presentation")) {
                continue;
            }
            if (fact.schemaVersion() != 1) {
                throw new IllegalStateException("tool presentation schema is unsupported");
            }
            JsonObject payload = fact.payload();
            if (!payload.members().keySet().equals(java.util.Set.of("callId", "revision", "state", "presentationJson"))) {
                throw new IllegalStateException("tool presentation payload fields are invalid");
            }
            if (requiredText(payload, "callId").equals(callId)
                && requiredNumber(payload, "revision") == revision) {
                values.add(new PresentationFact(requiredText(payload, "state"),
                        requiredText(payload, "presentationJson")));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Approval fact 必须唯一并与 interactionId 绑定，防止工具名或有效期从当前配置猜取。
     */
    private ExecutionFacts.Approval approvalFact(List<AgentStore.StoredRecord> window, String approvalId,
                                                 String callId) {
        List<ExecutionFacts.Approval> matches = window.stream().filter(record ->
                        "agent.approval".equals(record.recordType()))
                .map(record -> requireFact(record, ExecutionFacts.Approval.class))
                .filter(value -> value.approvalId().equals(approvalId)
                                 && (callId == null || value.callId().equals(callId))).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("approval fact is missing or duplicated");
        }
        return matches.getFirst();
    }

    /**
     * 在当前已提交 record 窗口中查找已回答的等待信封。
     */
    private InteractionWait findResolvedWait(List<AgentStore.StoredRecord> window, String interactionId) {
        List<InteractionWait> matches = window.stream().filter(record ->
                        io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec
                                .WAIT_RECORD_TYPE.equals(record.recordType()))
                .map(controls::decodeWait).filter(wait -> wait.interactionId().equals(interactionId)).toList();
        if (matches.size() != 1) {
            throw new IllegalStateException("resolved wait record is missing or duplicated");
        }
        return matches.getFirst();
    }

    /**
     * 读取已提交且序号不越过事件固定水位的精确执行记录。
     */
    private AgentStore.StoredRecord exactRecord(AgentIdentity identity, String id, long watermark) {
        return exactRecord(executionKey(identity), id, watermark);
    }

    /**
     * 读取已提交且序号不越过事件固定水位的精确执行记录。
     */
    private AgentStore.StoredRecord exactRecord(AgentStore.ExecutionKey execution, String id, long watermark) {
        AgentStore.StoredRecord record = store.readRecord(execution, id)
                .orElseThrow(() -> new IllegalStateException("referenced execution record is missing"));
        if (!record.recordId().equals(id) || record.sequence() > watermark) {
            throw new IllegalStateException("referenced execution record is outside the event watermark");
        }
        return record;
    }

    /**
     * 在事件声明的固定 record 区间内有界分页，不让之后的并发提交改变当前投影。
     */
    private List<AgentStore.StoredRecord> readRecordWindow(AgentStore.ExecutionKey execution,
                                                           long afterSequence, long throughSequence) {
        if (throughSequence < afterSequence) {
            throw new IllegalStateException("record watermark regressed");
        }
        if (throughSequence == afterSequence) {
            return List.of();
        }
        List<AgentStore.StoredRecord> result = new ArrayList<>();
        long cursor = afterSequence;
        while (cursor < throughSequence) {
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution, cursor,
                    throughSequence, RECORD_PAGE_SIZE));
            if (page.records().isEmpty()) {
                if (page.hasMore()) {
                    throw new IllegalStateException("record page made no progress");
                }
                break;
            }
            for (AgentStore.StoredRecord record : page.records()) {
                if (record.sequence() <= cursor || record.sequence() > throughSequence) {
                    throw new IllegalStateException("record page is outside its fixed watermark");
                }
                result.add(record);
                if (result.size() > MAX_RECORDS_PER_WINDOW) {
                    throw new IllegalStateException("event record window exceeds its bound");
                }
            }
            long next = page.records().getLast().sequence();
            if (next <= cursor) {
                throw new IllegalStateException("record page cursor did not advance");
            }
            cursor = next;
            if (!page.hasMore()) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * 获取一个严格类型的执行事实，损坏或类型错配都按持久状态错误处理。
     */
    private <T> T requireFact(AgentStore.StoredRecord record, Class<T> type) {
        Object value = records.decode(record);
        if (!type.isInstance(value)) {
            throw new IllegalStateException("referenced record has an unexpected fact type");
        }
        return type.cast(value);
    }

    /**
     * 返回唯一绑定 Engine，防止未完成组合装配时开始持久回放。
     */
    private AgentEngine requireEngine() {
        AgentEngine value = engine.get();
        if (value == null) {
            throw new IllegalStateException("event bridge engine is not bound");
        }
        return value;
    }

    /**
     * 一次持久事件及其严格解码领域值。
     *
     * @param persisted 持久化
     * @param event 事件
     */
    private record DecodedEvent(AgentStore.PersistedEvent persisted,
                                ExecutionEvent event) {
    }

    /**
     * 已读取的安全展示事实，不含任何原始调用参数或 Tool 结果。
     *
     * @param state 当前状态
     * @param presentationJson 展示信息 JSON 表示
     */
    private record PresentationFact(String state,
                                    String presentationJson) {
    }

    /**
     * 为一个 durable commit 保存可恢复的 Ja reducer 边界，避免失败重试沿用半应用状态。
     *
     * @param toolCalls 按调用标识索引的工具调用
     * @param inputQueue 输入队列
     * @param contextUsageEstimates 已提交的模型请求上下文估算标识
     * @param modelRound 模型调用轮次
     * @param draftSequence 草稿序号
     */
    private record ProjectionState(Map<String, ToolInfo> toolCalls,
                                   InputQueueProjection.Snapshot inputQueue,
                                   Set<String> contextUsageEstimates,
                                   int modelRound,
                                   long draftSequence) {
        /**
         * 快照只包含不可变值和冻结映射，不能与活动 reducer 共享可变对象。
         */
        private ProjectionState {
            toolCalls = Map.copyOf(Objects.requireNonNull(toolCalls, "toolCalls"));
            Objects.requireNonNull(inputQueue, "inputQueue");
            contextUsageEstimates = Set.copyOf(Objects.requireNonNull(contextUsageEstimates,
                    "contextUsageEstimates"));
            if (modelRound < 0) {
                throw new IllegalArgumentException("negative model round");
            }
            if (draftSequence < 0) {
                throw new IllegalArgumentException("negative draft sequence");
            }
        }
    }

    /**
     * 游标与 reducer 同步保存，保证中段恢复不会重复已确认帧或丢失后续 Tool/queue 关联。
     *
     * @param eventCursor 事件游标
     * @param recordCursor 记录游标
     * @param projection 投影
     * @param pendingDelivery 待投递的消息
     */
    private record ResumeCheckpoint(long eventCursor,
                                    long recordCursor,
                                    ProjectionState projection,
                                    PendingDelivery pendingDelivery) {
        /**
         * 只接受单调非负水位；partial delivery 仍保留原 commit 及下一未确认帧。
         */
        private ResumeCheckpoint {
            if (eventCursor < 0 || recordCursor < 0) {
                throw new IllegalArgumentException("negative resume cursor");
            }
            Objects.requireNonNull(projection, "projection");
            if (pendingDelivery != null && pendingDelivery.commit().sequence() <= eventCursor) {
                throw new IllegalArgumentException("pending commit is behind the resume cursor");
            }
        }
    }

    /**
     * 按对象身份比较 sink 的弱键，避免调用方自定义 equals 把不同 RPC 观察者合并。
     */
    private static final class SinkReference extends WeakReference<ConversationTurnEventSink> {
        private final int identityHash;

        /**
         * 查询键不注册 ReferenceQueue；仅实际缓存键参与回收通知。
         */
        private SinkReference(ConversationTurnEventSink sink) {
            super(Objects.requireNonNull(sink, "sink"));
            identityHash = System.identityHashCode(sink);
        }

        /**
         * 缓存键被回收时通知 owner 清除对应的 reducer 快照。
         */
        private SinkReference(ConversationTurnEventSink sink,
                              ReferenceQueue<ConversationTurnEventSink> queue) {
            super(Objects.requireNonNull(sink, "sink"), Objects.requireNonNull(queue, "queue"));
            identityHash = System.identityHashCode(sink);
        }

        /**
         * 固定对象身份哈希，使弱键查找不受 sink 实现的 equals/hashCode 影响。
         */
        @Override
        public int hashCode() {
            return identityHash;
        }

        /**
         * 活对象按引用相等；引用已回收时只允许同一个 Reference 实例匹配自身。
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SinkReference reference)) {
                return false;
            }
            ConversationTurnEventSink left = get();
            return left != null && left == reference.get();
        }
    }

    /**
     * 已解码的事务投影和下一待送帧；sink 中途失败时只重发未确认帧，不回滚或重复已送帧。
     *
     * @param commit 提交
     * @param recordWatermark 记录水位
     * @param endSequence 区间结束的顺序号
     * @param events 事件列表
     * @param nextEventIndex 下一事件在批次中的索引
     */
    record PendingDelivery(AgentStore.PersistedEvent commit,
                           long recordWatermark,
                           long endSequence,
                           List<TurnEvent> events,
                           int nextEventIndex) {
        /**
         * 把提交帧冻结并限制续送游标只能处于待送区间。
         */
        PendingDelivery {
            Objects.requireNonNull(commit, "commit");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            if (recordWatermark < commit.lastRecordSequence() || endSequence < commit.sequence()
                || events.isEmpty() || nextEventIndex < 0 || nextEventIndex > events.size()) {
                throw new IllegalArgumentException("invalid pending event delivery");
            }
        }

        /**
         * 仅在一个公开帧的 sink future 成功后推进，失败时保持该帧作为下一次投递起点。
         */
        private PendingDelivery advance() {
            return new PendingDelivery(commit, recordWatermark, endSequence, events, nextEventIndex + 1);
        }
    }

    /**
     * 只保存 proposal/prepared 的脱敏展示信息和 ordinal，真实副作用结果由持久事件负责。
     *
     * @param toolName 工具名称
     * @param ordinal 当前项的顺序位置
     * @param presentation 展示信息
     * @param prepared 是否已完成准备
     */
    private record ToolInfo(String toolName,
                            int ordinal,
                            ToolPresentation presentation,
                            boolean prepared) {
    }

    /**
     * 消费队列项时同时保留原始 Ja Mailbox metadata，避免从当前配置重建消息来源。
     *
     * @param entry 条目
     * @param mailbox 收件箱
     */
    record ConsumedInput(AgentInputQueue.Entry entry,
                         MailboxMetadata mailbox) {
        /**
         * 队列引用是必需事实；普通用户输入没有 mailbox 元数据，跨会话消息必须有。
         */
        ConsumedInput {
            Objects.requireNonNull(entry, "entry");
            if ((entry.kind() == AgentInputQueue.Kind.MESSAGE) == (mailbox == null)) {
                throw new IllegalArgumentException("queue kind and mailbox metadata disagree");
            }
        }
    }

    /**
     * 独立重放队列事实并保留未消费的 mailbox 来源，使分批断线补读与首轮准入共用同一 reducer。
     */
    static final class InputQueueProjection {
        private final AgentIdentity identity;
        private final Map<String, MailboxMetadata> mailboxInputs = new HashMap<>();
        private final Map<String, ConsumedInput> consumedInputs = new HashMap<>();
        private AgentInputQueue queue;

        /**
         * 从空队列开始，准入 event 的固定 watermark 会先补齐所有初始 ENQUEUE 命令。
         */
        InputQueueProjection(AgentIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
            queue = new AgentInputQueue(identity.turnId(), 0, List.of());
        }

        /**
         * 事件事实校验失败时恢复 reducer 的全部内存边界，重试只能从同一提交前状态开始。
         */
        Snapshot checkpoint() {
            return new Snapshot(queue, Map.copyOf(mailboxInputs), Map.copyOf(consumedInputs));
        }

        /**
         * 丢弃失败批次产生的任何局部改动，避免继续投影时把损坏半状态当成基线。
         */
        void restore(Snapshot snapshot) {
            Snapshot value = Objects.requireNonNull(snapshot, "snapshot");
            queue = value.queue();
            mailboxInputs.clear();
            mailboxInputs.putAll(value.mailboxInputs());
            consumedInputs.clear();
            consumedInputs.putAll(value.consumedInputs());
        }

        /**
         * 只应用 command record window；ENQUEUE 来源保留到 CONSUME，退役快照失败而不改写队列基线。
         * @author kongweiguang
         */
        void apply(List<AgentStore.StoredRecord> window, ExecutionControlCodec controls) {
            for (AgentStore.StoredRecord record : window) {
                if ("agent.input.queue.snapshot".equals(record.recordType())) {
                    throw new IllegalStateException("input queue snapshot record is no longer supported");
                }
                if (io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec
                        .INPUT_QUEUE_COMMAND_RECORD_TYPE.equals(record.recordType())) {
                    AgentInputQueue.Command command = controls.decodeInputQueueCommand(record);
                    if (command.action() == AgentInputQueue.Action.CONSUME) {
                        AgentInputQueue.Entry source = queue.entries().stream()
                                .filter(entry -> entry.inputId().equals(command.inputId())).findFirst()
                                .orElseThrow(() -> new IllegalStateException("consumed queue item is missing"));
                        MailboxMetadata mailbox = mailboxInputs.remove(command.inputId());
                        if (consumedInputs.putIfAbsent(command.inputId(), new ConsumedInput(source, mailbox)) != null) {
                            throw new IllegalStateException("queue input was consumed twice in one commit");
                        }
                    } else if (command.action() == AgentInputQueue.Action.ENQUEUE
                               && command.kind() == AgentInputQueue.Kind.MESSAGE) {
                        MailboxMetadata mailbox = MailboxMetadata.from(command.hostMetadata(), identity);
                        if (mailboxInputs.putIfAbsent(command.inputId(), mailbox) != null) {
                            throw new IllegalStateException("mailbox metadata identity is duplicated");
                        }
                    } else if (command.action() == AgentInputQueue.Action.DELETE) {
                        mailboxInputs.remove(command.inputId());
                    }
                    queue = queue.apply(command);
                }
            }
        }

        /**
         * 消费记录必须对应已重放的 queue revision，来源元数据不能从当前配置回退恢复。
         */
        ConsumedInput consume(String inputId, long consumedRevision) {
            if (queue.revision() != consumedRevision) {
                throw new IllegalStateException("queue consumption revision does not match replayed queue");
            }
            return Optional.ofNullable(consumedInputs.remove(inputId))
                    .orElseThrow(() -> new IllegalStateException("consumed queue item is missing"));
        }

        /**
         * 返回不可变领域队列快照，调用方只能投影而不能改写重放状态。
         */
        AgentInputQueue queue() {
            return queue;
        }

        /**
         * input queue reducer 的不可变重试点，不暴露其可变映射给其他投影路径。
         *
         * @param queue 队列
         * @param mailboxInputs 收件箱输入列表
         * @param consumedInputs 已消费输入列表
         */
        private record Snapshot(AgentInputQueue queue,
                                Map<String, MailboxMetadata> mailboxInputs,
                                Map<String, ConsumedInput> consumedInputs) {
            /**
             * 快照必须复制全部队列来源，以保证失败消费可完整回滚。
             */
            private Snapshot {
                Objects.requireNonNull(queue, "queue");
                mailboxInputs = Map.copyOf(Objects.requireNonNull(mailboxInputs, "mailboxInputs"));
                consumedInputs = Map.copyOf(Objects.requireNonNull(consumedInputs, "consumedInputs"));
            }
        }
    }

    /**
     * ENQUEUE 时冻结的来源快照只包含消息投影必需字段，不保留任意宿主元数据。
     *
     * @param messageId 消息标识
     * @param messageKind 已由持久 mailbox metadata 校验的消息种类
     * @param sourceThreadId 来源对话标识
     * @param targetThreadId 投递消息的目标对话标识
     * @param sourceTitle 来源标题
     * @param createdAt 创建时间
     */
    record MailboxMetadata(String messageId,
                           String messageKind,
                           String sourceThreadId,
                           String targetThreadId,
                           String sourceTitle,
                           Instant createdAt) {
        /**
         * 校验 Ja 生产者的最小来源集，来源标题与创建时间不能从后续 Thread 状态猜测。
         * @author kongweiguang
         */
        static MailboxMetadata from(Map<String, String> values, AgentIdentity identity) {
            Map<String, String> metadata = Map.copyOf(Objects.requireNonNull(values, "hostMetadata"));
            String messageId = requiredMailboxText(metadata, "ja.mailbox.messageId");
            if (!messageId.startsWith("msg_")) {
                throw new IllegalStateException("mailbox message id is invalid");
            }
            JaMessageIds.toItemId(messageId);
            String messageKind = requiredMailboxText(metadata, "ja.mailbox.messageKind");
            if (!messageKind.equals("MESSAGE") && !messageKind.equals("FINAL_ANSWER")) {
                throw new IllegalStateException("mailbox queue kind is not a deliverable message");
            }
            String sourceThreadId = requiredMailboxText(metadata, "ja.mailbox.senderThreadId");
            String targetThreadId = requiredMailboxText(metadata, "ja.mailbox.targetThreadId");
            if (!sourceThreadId.startsWith("thr_") || !targetThreadId.equals(identity.threadId())) {
                throw new IllegalStateException("mailbox message thread identity changed");
            }
            String sourceTitle = requiredMailboxText(metadata, "ja.mailbox.senderTitle");
            if (sourceTitle.length() > 512) {
                throw new IllegalStateException("mailbox source title is too long");
            }
            Instant createdAt;
            try {
                createdAt = Instant.parse(requiredMailboxText(metadata, "ja.mailbox.createdAt"));
            } catch (RuntimeException invalid) {
                throw new IllegalStateException("mailbox creation time is invalid", invalid);
            }
            return new MailboxMetadata(messageId, messageKind, sourceThreadId, targetThreadId, sourceTitle, createdAt);
        }

        /**
         * 只从唯一且与持久来源一致的当前封装投影消息正文，避免模型上下文封装泄漏到 Timeline。
         * @author kongweiguang
         */
        ThreadSnapshot.ThreadMessageItem toThreadMessage(AgentIdentity identity, UserMessage user) {
            if (!targetThreadId.equals(identity.threadId()) || !user.content().skillIds().isEmpty()) {
                throw new IllegalStateException("mailbox user message differs from its source metadata");
            }
            String fullContent = mailboxMessageBody(user);
            return new ThreadSnapshot.ThreadMessageItem(JaMessageIds.toItemId(messageId), createdAt,
                    identity.turnId(), sourceThreadId, sourceTitle, ThreadSnapshot.textPreview(fullContent),
                    fullContent.codePointCount(0, fullContent.length()));
        }

        /**
         * 模型输入保留完整 envelope；Timeline 仅接受 mapper 产生的单一严格 JSON 文本块并拒绝身份偏差。
         * @author kongweiguang
         */
        private String mailboxMessageBody(UserMessage user) {
            List<TextContent> textBlocks = user.content().blocks().stream()
                    .filter(TextContent.class::isInstance)
                    .map(TextContent.class::cast)
                    .toList();
            if (textBlocks.size() != 1) {
                throw new IllegalStateException("mailbox message envelope is invalid");
            }
            JsonObject envelope;
            try {
                envelope = MAILBOX_JSON.decodeObject(textBlocks.getFirst().text());
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException("mailbox message envelope is invalid", malformed);
            }
            if (!MAILBOX_ENVELOPE_FIELDS.equals(envelope.members().keySet())
                || !"external_thread_message".equals(envelopeText(envelope, "kind"))
                || !messageKind.equals(envelopeText(envelope, "messageKind"))
                || !sourceThreadId.equals(envelopeText(envelope, "sourceThreadId"))
                || !sourceTitle.equals(envelopeText(envelope, "sourceTitle"))) {
                throw new IllegalStateException("mailbox message envelope differs from its source metadata");
            }
            return envelopeText(envelope, "message");
        }

        /**
         * 只接纳文本型 envelope 字段，不做类型强转或格式回退，损坏正文不得进入展示投影。
         * @author kongweiguang
         */
        private static String envelopeText(JsonObject envelope, String field) {
            JsonValue value = envelope.get(field);
            if (!(value instanceof JsonText text)) {
                throw new IllegalStateException("mailbox message envelope contains an invalid field");
            }
            return text.value();
        }
    }

    /**
     * 单次订阅拥有独立游标、队列 reducer 和有限临时 token 缓存。
     */
    private final class Attachment implements AutoCloseable {
        private final AgentIdentity identity;
        private final ConversationTurnEventSink sink;
        private final AgentStreamSubscription stream;
        private final Semaphore wake = new Semaphore(0);
        private final AtomicBoolean detached = new AtomicBoolean();
        private final AtomicBoolean projectionFailureNotified = new AtomicBoolean();
        private final AtomicLong signalGeneration = new AtomicLong();
        private final Object replayLock = new Object();
        private final Object deliveryLock = new Object();
        private final NavigableMap<Long, List<CompletableFuture<Void>>> replayWaiters = new TreeMap<>();
        private final Map<String, ToolInfo> toolCalls = new HashMap<>();
        private final Set<String> contextUsageEstimates = new LinkedHashSet<>();
        private final InputQueueProjection inputQueueProjection;
        private final CompletableFuture<TurnResult> terminalProjection = new CompletableFuture<>();
        private volatile long eventCursor;
        private volatile long recordCursor;
        private long replayThrough;
        private volatile int modelRound;
        private long draftSequence;
        private PendingDelivery pendingDelivery;
        private volatile ResumeCheckpoint resumeState;
        private volatile boolean terminalDelivered;
        private boolean registered;
        private volatile Thread worker;
        private volatile boolean workerExited;

        /**
         * 新 sink 从零回放；同一 sink 的恢复从原子 reducer 检查点继续。
         */
        private Attachment(AgentIdentity identity, ConversationTurnEventSink sink, AgentStreamSubscription stream,
                           ResumeCheckpoint resume) {
            this.identity = identity;
            this.sink = sink;
            this.stream = stream;
            inputQueueProjection = new InputQueueProjection(identity);
            if (resume == null) {
                resumeState = new ResumeCheckpoint(0, 0, checkpoint(), null);
            } else {
                restore(resume.projection());
                eventCursor = resume.eventCursor();
                recordCursor = resume.recordCursor();
                pendingDelivery = resume.pendingDelivery();
                resumeState = resume;
            }
        }

        /**
         * 记录 Core 通知水位并唤醒 durable reader，retry 只由比失败 commit 更新的事实触发。
         */
        private void signalThrough(long sequence) {
            if (sequence < 0) {
                throw new IllegalArgumentException("negative event signal sequence");
            }
            signalGeneration.incrementAndGet();
            wake.release();
        }

        /**
         * 返回重试边界代数，旧 listener 通知不得让失败的 commit 忙循环重放。
         */
        private long signalGeneration() {
            return signalGeneration.get();
        }

        /**
         * 投影异常在同一个阻塞 commit 上只发布一次重同步提示，避免消费端立即陷入通知循环。
         */
        private boolean claimProjectionFailureNotification() {
            return projectionFailureNotified.compareAndSet(false, true);
        }

        /**
         * 当前 commit 成功后允许之后独立故障再通知，但相同失败 commit 不会重复打扰客户端。
         */
        private void clearProjectionFailureAfterProgress() {
            projectionFailureNotified.set(false);
        }

        /**
         * 投影失败不越过其事实；用同一有界语义的信号量等待，generation 检查消除旧许可导致的忙循环。
         */
        private void awaitSignalAfter(long failedGeneration) throws InterruptedException {
            while (!isClosed() && signalGeneration.get() <= failedGeneration) {
                wake.acquire();
            }
        }

        /**
         * 公开序号跨模型请求及重试连续；投递前保存，未知 sink 结果不得复用同一序号。
         */
        private long nextDraftSequence() {
            draftSequence = Math.addExact(draftSequence, 1);
            rememberCurrentCheckpoint();
            return draftSequence;
        }

        /**
         * 捕获当前提交前的全部 reducer 与公开流游标，projection 批次失败时可以原子回退。
         */
        private ProjectionState checkpoint() {
            return new ProjectionState(toolCalls, inputQueueProjection.checkpoint(), contextUsageEstimates,
                    modelRound, draftSequence);
        }

        /**
         * 回滚本次批次尚未对 sink 发布的 reducer 改动，重试使用同一 durable commit 边界。
         */
        private void restore(ProjectionState checkpoint) {
            ProjectionState value = Objects.requireNonNull(checkpoint, "checkpoint");
            toolCalls.clear();
            toolCalls.putAll(value.toolCalls());
            inputQueueProjection.restore(value.inputQueue());
            contextUsageEstimates.clear();
            contextUsageEstimates.addAll(value.contextUsageEstimates());
            modelRound = value.modelRound();
            draftSequence = value.draftSequence();
        }

        /**
         * 只有估算事件已进入确认投影检查点后，才允许同一 Provider 请求的临时正文展示。
         */
        private boolean hasContextUsageEstimate(String modelRequestId) {
            return contextUsageEstimates.contains(modelRequestId);
        }

        /**
         * 只有 sink 成功确认估算帧后才解锁临时 delta，并让恢复快照与公开确认位置一致。
         */
        private void acknowledgeContextUsageEstimate(String modelRequestId) {
            if (!contextUsageEstimates.add(modelRequestId)) {
                throw new IllegalStateException("model request context estimate was already acknowledged");
            }
        }

        /**
         * 将一个已投影提交挂起到当前订阅，直到所有公开帧得到 sink 确认。
         */
        private PendingDelivery pendingDelivery() {
            return pendingDelivery;
        }

        /**
         * 仅工作线程写入待投递提交；listener 线程只发送水位信号。
         */
        private void pendingDelivery(PendingDelivery delivery) {
            pendingDelivery = delivery;
            rememberCurrentCheckpoint();
        }

        /**
         * 显式重放可以重试同一失败水位，不要求新的业务提交，也不推进未确认游标。
         */
        private void awaitReplay(long throughSequence) {
            awaitReplay(throughSequence, false);
        }

        /**
         * 正常关闭不能重试已阻塞的投影；显式恢复允许重试，worker 退出始终拒绝新等待。
         */
        private void awaitReplay(long throughSequence, boolean rejectBlocked) {
            CompletableFuture<Void> replayed;
            synchronized (replayLock) {
                if (eventCursor >= throughSequence) {
                    return;
                }
                if (isClosed()) {
                    throw new IllegalStateException("event projection is closed");
                }
                if (workerExited || (rejectBlocked && projectionFailureNotified.get())) {
                    throw new IllegalStateException("event projection cannot confirm replay");
                }
                replayed = new CompletableFuture<>();
                replayWaiters.computeIfAbsent(throughSequence, ignored -> new ArrayList<>()).add(replayed);
                replayThrough = Math.max(replayThrough, throughSequence);
            }
            signalThrough(throughSequence);
            replayed.join();
        }

        /**
         * 仅在本页事件已发布后推进游标，并完成不高于新水位的重放请求。
         */
        private void markProjected(long sequence, long recordSequence) {
            List<CompletableFuture<Void>> completed = new ArrayList<>();
            synchronized (replayLock) {
                if (sequence < eventCursor || recordSequence < recordCursor) {
                    throw new IllegalStateException("event projection cursor regressed");
                }
                eventCursor = sequence;
                recordCursor = recordSequence;
                pendingDelivery = null;
                rememberCurrentCheckpoint();
                replayWaiters.headMap(sequence, true).values().forEach(completed::addAll);
                replayWaiters.headMap(sequence, true).clear();
                replayThrough = replayWaiters.isEmpty() ? 0 : replayWaiters.lastKey();
            }
            completed.forEach(future -> future.complete(null));
        }

        /**
         * 返回尚未投影的最高重放水位，驱动缺口期间的快速持久读取。
         */
        private long replayTarget() {
            synchronized (replayLock) {
                return replayThrough;
            }
        }

        /**
         * Store 或 Sink 读取失败时显式拒绝所有待重放确认，不能让 Core 误以为缺口已补齐。
         */
        private void failReplayWaiters(Throwable failure) {
            List<CompletableFuture<Void>> pending = new ArrayList<>();
            synchronized (replayLock) {
                replayWaiters.values().forEach(pending::addAll);
                replayWaiters.clear();
                replayThrough = 0;
            }
            pending.forEach(future -> future.completeExceptionally(failure));
        }

        /**
         * 正常结束先确认已提交水位；不持投递锁等待 sink，退出和 worker 自身清理直接 detach。
         */
        @Override
        public void close() {
            try {
                if (registered && worker != null && worker != Thread.currentThread() && !isClosed()) {
                    long watermark = store.loadConversation(executionKey(identity).conversation())
                            .orElseThrow(() -> new IllegalStateException("execution conversation is missing from Store"))
                            .eventSequence();
                    awaitReplay(watermark, true);
                }
            } finally {
                detach();
            }
        }

        /**
         * 失败与关机直接释放本订阅，不等待无法确认的投影，也不取消 Engine 执行。
         */
        private void detach() {
            synchronized (deliveryLock) {
                if (!detached.compareAndSet(false, true)) {
                    return;
                }
                try {
                    stream.close();
                } finally {
                    wake.release();
                    Thread thread = worker;
                    if (thread != null) {
                        thread.interrupt();
                    }
                    failReplayWaiters(new IllegalStateException("event projection was detached"));
                    if (registered) {
                        if (terminalDelivered) {
                            forgetCheckpoint(sink, identity);
                        }
                        else {
                            persistCheckpoint();
                        }
                    }
                    active.remove(identity, this);
                }
            }
        }

        /**
         * 供回放循环判断本地订阅生命周期。
         */
        private boolean isClosed() {
            return detached.get() || closed.get();
        }

        /**
         * 已确认帧的 reducer 快照不含 Sink/订阅句柄，可安全用于同一观察者后续恢复。
         */
        private void rememberCurrentCheckpoint() {
            resumeState = new ResumeCheckpoint(eventCursor, recordCursor, checkpoint(), pendingDelivery);
            JaAgentEventBridge.this.rememberCheckpoint(sink, identity, resumeState);
        }

        /**
         * Worker 退出时再提交一次最后确认位置，覆盖由显式 detach 触发的竞态退出。
         */
        private void persistCheckpoint() {
            if (terminalDelivered) {
                forgetCheckpoint(sink, identity);
            }
            else if (resumeState != null) {
                JaAgentEventBridge.this.rememberCheckpoint(sink, identity, resumeState);
            }
        }

        /**
         * 为新 worker 暴露已经确认的事件游标。
         */
        private long eventCursor() {
            return eventCursor;
        }

        /**
         * 为新 worker 暴露已读取的 Store record 水位。
         */
        private long recordCursor() {
            return recordCursor;
        }
    }
}

