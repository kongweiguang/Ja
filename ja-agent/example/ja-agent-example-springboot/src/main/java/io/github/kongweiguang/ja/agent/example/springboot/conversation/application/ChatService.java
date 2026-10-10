// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.RecoveryCommand;
import io.github.kongweiguang.ja.agent.domain.tool.ToolRecoveryDecision;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.InputContentView;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentAnswer;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentEventPage;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionStatus;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInput;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputCommand;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInputMutationReceipt;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.AnswerCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.CancelCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.ChatCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.MessageView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.QueueCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.QueueView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.RecoveryResolveCommand;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.WaitView;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.concurrent.CompletionStage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 对话用例只委托唯一 Core Engine，持久事实通过 Store 扩展读取。
 */
@Service
public class ChatService {
    private final AgentEngine engine;
    private final AgentStore store;
    private final ExecutionRecordCodec records;
    private final ExecutionControlCodec controls;
    private final AttachmentService attachments;
    private final AttachmentRequestBudget attachmentBudget;
    private final ExecutionObservation observations;

    /** 复用 Spring 单例、准入预检和句柄观察，不把本地连接状态当成持久执行结局。 */
    public ChatService(AgentEngine engine, AgentStore store, ExecutionRecordCodec records,
                       ExecutionControlCodec controls, AttachmentService attachments,
                       AttachmentRequestBudget attachmentBudget, ExecutionObservation observations) {
        this.engine = engine;
        this.store = store;
        this.records = records;
        this.controls = controls;
        this.attachments = attachments;
        this.attachmentBudget = attachmentBudget;
        this.observations = observations;
    }

    /** 将请求边界解析出的 actor 一次冻结进 Core identity，后续异步任务不访问登录上下文。 */
    public AgentIdentity identity(CurrentActor actor, String threadId, String turnId) {
        return new AgentIdentity(actor.tenantId(), threadId, turnId, actor.userId());
    }

    /**
     * 先对当前 checkpoint 后的资料总量作预检；观察完整句柄以识别持久结果未知后的人工恢复边界。
     */
    public void submit(AgentIdentity identity, ChatCommand command) {
        io.github.kongweiguang.ja.agent.domain.UserContent content = command.content().toUserContent();
        attachmentBudget.validateSubmission(identity, command.operationKey(), content);
        observations.observe(engine.submit(new AgentRequest(identity, content,
            command.operationKey(), Optional.empty(), Map.of())));
    }

    /**
     * 挂起保存原 Turn，异步成功只表示控制事实已提交，恢复仍由 Core 核验工具与上下文。
     */
    public CompletionStage<Void> pause(AgentIdentity identity, String operationKey) {
        query(identity);
        return engine.suspend(identity, operationKey, "USER_PAUSED");
    }

    /**
     * 压缩是单独执行，返回真实身份并观察句柄；异常结束不能留下无限等待的页面连接。
     */
    public AgentIdentity compact(AgentIdentity identity, String operationKey) {
        AgentIdentity compactIdentity = AgentCompactionRequest.compactionIdentity(identity, operationKey);
        observations.observe(engine.compact(new AgentCompactionRequest(compactIdentity, operationKey)));
        return compactIdentity;
    }

    /**
     * 直接投影 Core 的持久队列快照，条目版本与队列版本分开供客户端执行 CAS。
     */
    public QueueView queue(AgentIdentity identity) {
        query(identity);
        AgentInputQueue queue = engine.inspectInputQueue(identity);
        List<QueueDraft> drafts = queue.entries().stream().map(entry -> new QueueDraft(entry,
            records.decodeQueuedUser(readRecord(identity, entry.messageRecordId())).content())).toList();
        List<String> ids = drafts.stream().flatMap(draft -> draft.content().attachmentIds().stream()).toList();
        Map<String, AttachmentMetadata> metadata = metadataById(identity, ids);
        return new QueueView(identity.turnId(), queue.revision(), drafts.stream().map(draft ->
            new QueueView.Entry(draft.entry().inputId(), draft.entry().kind().name(), draft.entry().status().name(),
                draft.entry().inputRevision(), inputView(draft.content(), metadata))).toList());
    }

    /**
     * 操作闭集由请求指定；返回持久队列回执，让调用方能读取权威版本而不依赖通配类型。
     */
    public CompletionStage<AgentInputMutationReceipt> mutate(AgentIdentity identity, QueueCommand command) {
        query(identity);
        AgentInputCommand value = switch (command.action()) {
            case "ENQUEUE" -> {
                AgentInputCommand.Enqueue enqueue = new AgentInputCommand.Enqueue(command.operationKey(), command.inputId(),
                    AgentInputCommand.Kind.valueOf(command.kind()), command.content().toUserContent(), Map.of());
                attachmentBudget.validateQueueMutation(identity, enqueue);
                yield enqueue;
            }
            case "UPDATE" -> {
                AgentInputCommand.Update update = new AgentInputCommand.Update(command.operationKey(), command.inputId(),
                    command.expectedInputRevision(), command.content().toUserContent(), Map.of());
                attachmentBudget.validateQueueMutation(identity, update);
                yield update;
            }
            case "DELETE" ->
                new AgentInputCommand.Delete(command.operationKey(), command.inputId(), command.expectedInputRevision(), Map.of());
            case "PRIORITIZE" ->
                new AgentInputCommand.Prioritize(command.operationKey(), command.inputId(), command.expectedInputRevision(), Map.of());
            default -> throw new IllegalArgumentException("unknown queue action");
        };
        return engine.mutateInput(identity, value);
    }

    /**
     * 已提交计量统一在此核验类型，展示和消息关联共用同一读取路径。
     */
    public ExecutionFacts.Usage usage(AgentIdentity identity, String recordId) {
        if (!(records.decode(readRecord(identity, recordId)) instanceof ExecutionFacts.Usage usage)) {
            throw new IllegalArgumentException("not usage");
        }
        return usage;
    }

    /**
     * 本地 Future 和 SSE 连接不替代数据库中的权威执行状态。
     */
    public AgentExecutionStatus query(AgentIdentity identity) {
        return engine.query(identity).orElseThrow(() -> new ChatNotFoundException("execution not found"));
    }

    /** 保持 Core 的原始状态，另行提示当前宿主没有观察到活动句柄时需要用户显式核实。 */
    public boolean requiresResume(AgentExecutionStatus status) {
        return observations.requiresResume(status);
    }

    /**
     * 回读指定等待快照并保留审批/澄清分类，回答时 Core 仍核对最新修订号。
     */
    public WaitView waitRequest(AgentIdentity identity, String recordId) {
        AgentStore.StoredRecord record = readRecord(identity, recordId);
        if (!record.recordType().equals(ExecutionControlCodec.WAIT_RECORD_TYPE)) {
            throw new IllegalArgumentException("record is not a wait");
        }
        InteractionWait wait = controls.decodeWait(record);
        return new WaitView(wait.interactionId(),
            wait.interactionRevision(),
            wait.status(), waitKind(wait),
            wait.questions());
    }

    /**
     * 等待分类是宿主与 Core 的持久化契约；缺失或未知值属于持久完整性故障，不能伪装成普通输入。
     */
    static String waitKind(InteractionWait wait) {
        if (wait.attributes().get("kind") instanceof JsonText kind) {
            if ("input".equals(kind.value()) || "approval".equals(kind.value())) {
                return kind.value();
            }
        }
        throw new AgentStore.StoreIntegrityException("invalid_interaction_wait_kind", null);
    }

    /**
     * 新输入与当前未消费队列共同预检；Core 仍在消费安全点按最新上下文复核实际请求。
     */
    public CompletionStage<Void> append(AgentIdentity identity, ChatCommand command) {
        query(identity);
        io.github.kongweiguang.ja.agent.domain.UserContent content = command.content().toUserContent();
        attachmentBudget.validateAppend(identity, command.operationKey(), content);
        return engine.appendInput(identity, new AgentInput(command.operationKey(),
            content));
    }

    /**
     * 答案与恢复分别持久提交，空值完成回执不隐式再次派发执行。
     */
    public CompletionStage<Void> answer(AgentIdentity identity, AnswerCommand command) {
        query(identity);
        return engine.answer(identity, new AgentAnswer(command.interactionId(), command.operationKey(),
            command.expectedInteractionRevision(), command.answers(), Map.of()));
    }

    /**
     * 取消只传播停止意图，空值完成回执表示控制提交，已发生的动作仍保留真实结果。
     */
    public CompletionStage<Void> cancel(AgentIdentity identity, CancelCommand command) {
        query(identity);
        return engine.cancel(identity, command.operationKey(), command.reason());
    }

    /**
     * 恢复原执行并观察新句柄，已提交工具结果由 Core 核验后复用，未知副作用仍等待明确裁决。
     */
    public void resume(AgentIdentity identity) {
        query(identity);
        observations.observe(engine.resume(identity));
    }

    /**
     * 游标是会话全局水位，过滤当前执行后允许序号不连续。
     */
    public AgentEventPage events(AgentIdentity identity, long cursor, int limit) {
        query(identity);
        return engine.readEvents(identity, cursor, limit);
    }

    /** 只返回 Core 当前仍允许处理的 pending recovery，不把结果未知解释成可自动重放。 */
    public RecoveryList recoveries(AgentIdentity identity) {
        query(identity);
        AgentStore.ExecutionSnapshot execution = loadExecution(identity);
        Map<String, ToolRecoveryDecision> latest = latestRecoveries(identity, execution.lastRecordSequence());
        List<RecoveryView> pending = latest.values().stream()
                .filter(item -> item.status() == ToolRecoveryDecision.Status.PENDING)
                .map(item -> new RecoveryView(item.recoveryId(), item.callId(), item.revision(), item.status().name(),
                        item.disposition() == null ? null : item.disposition().name(), item.attempt(),
                        item.toolResultRecordId(), item.updatedAt(), List.of("RETRY", "SKIP")))
                .toList();
        return new RecoveryList(identity.threadId(), identity.turnId(), pending);
    }

    /** 原样交给 Core 处理 revision CAS 与 operation receipt，响应丢失后的相同命令可幂等回读。 */
    public CompletionStage<Void> resolveRecovery(AgentIdentity identity, RecoveryResolveCommand command) {
        RecoveryCommand.Decision decision = RecoveryCommand.Decision.valueOf(command.decision());
        return engine.resolveToolRecovery(identity, command.operationKey(), command.recoveryId(), command.callId(),
                command.expectedRecoveryRevision(), decision, authorizationId(identity));
    }

    /** 用稳定短标识记录裁决身份，避免租户或用户标识长度扩大 Core authorization ID。 */
    private static String authorizationId(AgentIdentity identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(identity.tenantId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(identity.userId().getBytes(StandardCharsets.UTF_8));
            return "actor:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 按固定 execution owner 读取快照，避免从客户端字段构造不同 tenant/user 的 Store key。 */
    private AgentStore.ExecutionSnapshot loadExecution(AgentIdentity identity) {
        AgentStore.ExecutionKey key = executionKey(identity);
        return store.loadExecution(key).orElseThrow(() -> new ChatNotFoundException("execution not found"));
    }

    /** 只扫描当前执行的固定记录水位，按 recoveryId 取最新版本。 */
    private Map<String, ToolRecoveryDecision> latestRecoveries(AgentIdentity identity, long throughSequence) {
        AgentStore.ExecutionKey execution = executionKey(identity);
        Map<String, ToolRecoveryDecision> latest = new HashMap<>();
        long cursor = 0;
        while (cursor < throughSequence) {
            AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(
                    execution, cursor, throughSequence, 1_000));
            for (AgentStore.StoredRecord record : page.records()) {
                if (ExecutionControlCodec.TOOL_RECOVERY_RECORD_TYPE.equals(record.recordType())) {
                    ToolRecoveryDecision decision = controls.decodeToolRecoveryDecision(record);
                    ToolRecoveryDecision previous = latest.get(decision.recoveryId());
                    if (previous == null || decision.revision() > previous.revision()) {
                        latest.put(decision.recoveryId(), decision);
                    }
                }
            }
            if (!page.hasMore() || page.nextSequence() <= cursor) {
                break;
            }
            cursor = page.nextSequence();
        }
        return latest;
    }

    /** 把冻结身份映射为 Core execution key，确保记录回读 owner-scoped。 */
    private static AgentStore.ExecutionKey executionKey(AgentIdentity identity) {
        return new AgentStore.ExecutionKey(new AgentStore.ConversationKey(identity.tenantId(),
                identity.threadId(), identity.userId()), identity.turnId());
    }

    /** 读取历史附件元数据时先去重，保留附件顺序由原始 UserContent 决定。 */
    private Map<String, AttachmentMetadata> metadataById(AgentIdentity identity, List<String> ids) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(ids));
        if (unique.isEmpty()) {
            return Map.of();
        }
        return attachments.metadata(new CurrentActor(identity.tenantId(), identity.userId()), identity.threadId(), unique)
                .stream().collect(Collectors.toUnmodifiableMap(AttachmentMetadata::id, value -> value));
    }

    /** 按持久附件 ID 恢复内容顺序，并拒绝缺失元数据掩盖 Store 与附件表不一致。 */
    private static InputContentView inputView(io.github.kongweiguang.ja.agent.domain.UserContent content,
                                              Map<String, AttachmentMetadata> metadata) {
        List<AttachmentMetadata> related = content.attachmentIds().stream().map(id -> {
            AttachmentMetadata value = metadata.get(id);
            if (value == null) {
                throw new ChatNotFoundException("attachment metadata not found");
            }
            return value;
        }).toList();
        return new InputContentView(content.text(), content.skillIds(), content.attachmentIds(), related);
    }

    /** 保留队列条目与解码后的原生内容，附件元数据在批量读取后再投影。
     *
     * @param entry Core 已持久化的队列条目身份和版本
     * @param content 已按 Core 规则解码的输入内容
     */
    private record QueueDraft(AgentInputQueue.Entry entry,
                              io.github.kongweiguang.ja.agent.domain.UserContent content) { }

    /** recovery 查询返回当前可操作版本及 Core 认可的用户裁决闭集。
     *
     * @param recoveryId 待核实副作用的恢复项身份
     * @param callId 产生该恢复项的原始工具调用身份
     * @param revision 用户裁决必须匹配的当前版本
     * @param status Core 持久化的恢复状态
     * @param disposition 已核实的 RETRY 或 SKIP 处置
     * @param attempt 当前已记录的核实尝试次数
     * @param toolResultRecordId 已提交工具结果的记录身份
     * @param updatedAt 恢复状态最近一次持久更新时间
     * @param allowedActions Core 当前允许的显式用户操作
     */
    public record RecoveryView(String recoveryId,
                               String callId,
                               long revision,
                               String status,
                               String disposition,
                               int attempt,
                               String toolResultRecordId,
                               java.time.Instant updatedAt,
                               List<String> allowedActions) {
        /** 响应字段和动作列表必须保持快照稳定。 */
        public RecoveryView {
            allowedActions = List.copyOf(allowedActions);
        }
    }

    /** recovery 查询与目标 Turn 固定绑定。
     *
     * @param threadId 恢复项所属的 Thread 标识
     * @param turnId 恢复项所属的原始 Turn 标识
     * @param recoveries 当前 Turn 的待核实恢复项快照
     */
    public record RecoveryList(String threadId,
                               String turnId,
                               List<RecoveryView> recoveries) {
        /** 返回值必须与当前读取快照解耦。 */
        public RecoveryList {
            recoveries = List.copyOf(recoveries);
        }
    }

    /**
     * 公开投影不能携带隐藏推理或原生供应商内容。
     */
    public MessageView message(AgentIdentity identity, String recordId, String usageRecordId) {
        return switch (records.decode(readRecord(identity, recordId))) {
            case ExecutionFacts.AssistantMessage assistant -> new MessageView(
                modelRequestId(identity, usageRecordId, assistant.modelRound()), assistant.publicText(), assistant.reasoningSummary(),
                assistant.message().content().stream().filter(ToolCallContent.class::isInstance)
                    .map(ToolCallContent.class::cast).map(call -> new MessageView.Call(call.callId(), call.name())).toList());
            case ExecutionFacts.ToolResult result -> new MessageView(null, result.content(), null, List.of());
            default -> throw new IllegalArgumentException("record is not a public message");
        };
    }

    /**
     * 通过已提交 Usage 关联精确请求，避免把迟到草稿重复显示为正文。
     */
    private String modelRequestId(AgentIdentity identity, String usageRecordId, int round) {
        if (usageRecordId == null) {
            return null;
        }
        ExecutionFacts.Usage usage = usage(identity, usageRecordId);
        if (usage.modelRound() != round) {
            throw new IllegalArgumentException("usage does not match message");
        }
        return usage.modelRequestId();
    }

    /**
     * 只读取指定 owner 下的执行记录，不扫描其它会话或全量历史。
     */
    private AgentStore.StoredRecord readRecord(AgentIdentity identity, String recordId) {
        query(identity);
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(new AgentStore.ConversationKey(
                identity.tenantId(), identity.threadId(), identity.userId()), identity.turnId());
        return store.readRecord(key, recordId).orElseThrow(() -> new ChatNotFoundException("record not found"));
    }
}

