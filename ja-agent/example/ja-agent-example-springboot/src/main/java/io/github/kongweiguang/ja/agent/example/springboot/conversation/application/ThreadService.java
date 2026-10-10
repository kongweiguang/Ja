// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.conversation.application;

import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.example.springboot.agent.application.AgentCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.domain.ThreadMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.model.InputContentView;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.port.out.ThreadDirectory;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Supplier;
import java.util.UUID;

/**
 * 示例只持有会话列表元数据；正文、执行状态和恢复水位始终来自 Core Store。
 */
@Service
public final class ThreadService {
    private final ThreadDirectory directory;
    private final AgentStore store;
    private final AgentCatalog prompts;
    private final ExecutionRecordCodec codec;
    private final AttachmentService attachments;

    /**
     * 应用层只编排 Core Store 与目录端口，数据库表由目录适配器负责。
     */
    public ThreadService(ThreadDirectory directory, AgentStore store, AgentCatalog prompts,
                          ExecutionRecordCodec codec, AttachmentService attachments) {
        this.directory = directory;
        this.store = store;
        this.prompts = prompts;
        this.codec = codec;
        this.attachments = attachments;
    }

    /**
     * Engine 回调不接受 checked 异常；目录适配器须将 JDBC 故障转成带 cause 的 StoreParticipantException，供 Store 回滚。
     */
    public void onExecutionCommit(AgentStoreTransactionContext context) {
        String title = null;
        if (context.kind() == AgentStore.TransactionKind.ADMISSION) {
            for (AgentStore.RecordWrite row : context.records()) {
                if (!"agent.user.message".equals(row.recordType())) {
                    continue;
                }
                AgentStore.StoredRecord record = new AgentStore.StoredRecord(row.recordId(), row.recordType(),
                        row.payloadSchemaVersion(), 1, row.payload(), row.occurredAt());
                AgentStore.ConversationKey owner = context.execution().conversation();
                UserContent content = codec.decodeUser(record).content();
                String titleText = content.text();
                if (titleText.isBlank() && !content.attachmentIds().isEmpty()) {
                    CurrentActor actor = new CurrentActor(owner.tenantId(), owner.userId());
                    titleText = attachments.metadata(actor, owner.threadId(), content.attachmentIds())
                            .stream().findFirst().map(AttachmentMetadata::fileName).orElse(titleText);
                }
                title = ThreadMetadata.initialTitle(titleText);
                break;
            }
        }
        directory.onExecutionCommit(context, title);
    }

    /**
     * 创建键由客户端在不确定响应后复用；Store 身份固定包含服务端 owner，不能只靠租户与 Thread 推断用户。
     */
    public ThreadView create(CurrentActor actor, String operationKey, String agentId) {
        if (operationKey == null || !operationKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")) {
            throw new IllegalArgumentException("invalid operation key");
        }
        String selectedAgent = prompts.requireAgentId(agentId);
        ThreadMetadata existing = directory.findByCreateKey(actor, operationKey);
        if (existing != null) {
            existing.requireAgent(selectedAgent);
            return view(existing);
        }
        String id = "thr_" + UUID.randomUUID().toString().replace("-", "");
        AgentStore.ConversationKey key = conversation(actor, id);
        try {
            store.initializeConversation(new AgentStore.ConversationInitialization(key, Instant.now()),
                    context -> directory.insert(context, actor, id, operationKey, selectedAgent));
        } catch (RuntimeException failure) {
            ThreadMetadata raced;
            try {
                raced = directory.findByCreateKey(actor, operationKey);
            } catch (RuntimeException lookupFailure) {
                if (lookupFailure != failure) {
                    failure.addSuppressed(lookupFailure);
                }
                throw failure;
            }
            if (raced != null) {
                try {
                    raced.requireAgent(selectedAgent);
                } catch (IllegalStateException conflict) {
                    conflict.addSuppressed(failure);
                    throw conflict;
                }
                return view(raced);
            }
            throw failure;
        }
        return view(require(actor, id));
    }

    /** 模型每轮及恢复均从冻结的 owner identity 读取 Thread 角色，不访问请求上下文。 */
    public String agentId(AgentIdentity identity) {
        return require(new CurrentActor(identity.tenantId(), identity.userId()), identity.threadId()).agentId();
    }

    /**
     * 列表只读有界元数据，并在应用边界以 Locale.ROOT 规范化搜索词。
     */
    public List<ThreadView> list(CurrentActor actor, boolean archived, String search, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 100_000 || search != null && search.length() > 128) {
            throw new IllegalArgumentException("invalid list page");
        }
        String normalizedSearch = search == null ? null : search.trim().toLowerCase(Locale.ROOT);
        return directory.list(actor, archived, normalizedSearch, limit, offset).stream().map(ThreadService::view).toList();
    }

    /**
     * 修改列表元数据采用 Core 会话修订 CAS 与本表版本 CAS，避免执行和浏览器并发写入失序。
     */
    public synchronized ThreadView update(CurrentActor actor, String threadId, long expectedVersion, String title, Boolean archived) {
        ThreadMetadata current = require(actor, threadId);
        String activeTurn = Boolean.TRUE.equals(archived) && !current.archived() ? latestActiveTurn(actor, threadId) : null;
        ThreadMetadata.Change change = current.change(expectedVersion, title, archived, activeTurn);
        AgentStore.ConversationKey key = conversation(actor, threadId);
        AgentStore.ConversationSnapshot snapshot = store.loadConversation(key).orElseThrow(() -> new ChatNotFoundException("thread not found"));
        store.reviseConversation(new AgentStore.ConversationRevision(key, snapshot.revision(), Instant.now()),
                context -> directory.update(context, actor, threadId, expectedVersion, change));
        return view(require(actor, threadId));
    }

    /**
     * 固定记录水位分页并携带 owner 身份，明确跳过内部事实和排队中尚未消费的输入。
     */
    public HistoryPage history(CurrentActor actor, String threadId, long afterSequence, Long throughSequence, int limit) {
        requireOwned(actor, threadId);
        if (limit < 1 || limit > 256 || afterSequence < 0) {
            throw new IllegalArgumentException("invalid history page");
        }
        AgentStore.ConversationKey key = conversation(actor, threadId);
        AgentStore.ConversationSnapshot snapshot = store.loadConversation(key).orElseThrow(() -> new ChatNotFoundException("thread not found"));
        long through = throughSequence == null ? snapshot.recordSequence() : throughSequence;
        if (through < afterSequence || through > snapshot.recordSequence()) {
            throw new IllegalArgumentException("invalid history watermark");
        }
        AgentStore.ConversationRecordPage page = store.readConversationRecords(
                AgentStore.ConversationRecordQuery.withDefaultMaxBytes(key, afterSequence, through, limit));
        ArrayList<ThreadService.HistoryItem> items = new ArrayList<HistoryItem>();
        Map<String, UserContent> userContent = new java.util.HashMap<>();
        List<String> attachmentIds = new ArrayList<>();
        for (AgentStore.ConversationRecord row : page.records()) {
            String turnId = row.execution().turnId();
            AgentStore.StoredRecord record = new AgentStore.StoredRecord(row.recordId(), row.recordType(),
                    row.payloadSchemaVersion(), row.sequence(), row.payload(), row.occurredAt());
            switch (row.recordType()) {
                case "agent.user.message" -> {
                    UserContent content = codec.decodeUser(record).content();
                    userContent.put(row.recordId(), content);
                    attachmentIds.addAll(content.attachmentIds());
                    items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "user", null, null, null, null, null));
                }
                case "agent.assistant" -> {
                    ExecutionFacts.AssistantMessage message = (ExecutionFacts.AssistantMessage) codec.decode(record);
                    items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "assistant", message.publicText(), message.reasoningSummary(), null, null, null));
                    message.message().content().stream().filter(ToolCallContent.class::isInstance).map(ToolCallContent.class::cast)
                            .forEach(call -> items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "tool", null, null, call.callId(), call.name(), "PENDING")));
                }
                case "agent.tool.result" -> {
                    ExecutionFacts.ToolResult result = (ExecutionFacts.ToolResult) codec.decode(record);
                    items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "tool-result", result.content(), null, result.callId(), null, result.state().name()));
                }
                case "agent.usage" -> items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "usage", null, null, null, null, null));
                case ExecutionControlCodec.WAIT_RECORD_TYPE -> items.add(new HistoryItem(row.sequence(), row.recordId(), turnId, "wait", null, null, null, null, null));
                default -> { }
            }
        }
        Map<String, AttachmentMetadata> metadata = metadataById(actor, threadId, attachmentIds);
        List<HistoryItem> withContent = items.stream().map(item -> {
            UserContent content = userContent.get(item.recordId());
            return content == null ? item : item.withContent(inputView(content, metadata));
        }).toList();
        return new HistoryPage(withContent, page.nextSequence(), through, page.hasMore(), latestTurn(actor, threadId));
    }

    /**
     * 提交准入按会话读取最新执行，防止同一 Thread 并发开放两个交互 Turn。
     */
    private void checkSubmit(CurrentActor actor, String threadId, String turnId) {
        require(actor, threadId).requireAdmittable(turnId, latestActiveTurn(actor, threadId));
    }

    /**
     * 单进程演示把状态检查与 Core 准入串行化；跨进程仍由 Store 的 CAS 守住提交。
     */
    public synchronized <T> T admit(CurrentActor actor, String threadId, String turnId, Supplier<T> operation) {
        checkSubmit(actor, threadId, turnId);
        return operation.get();
    }

    /**
     * 只读取最近一次执行的公开状态，分页水位来自 Store 快照。
     */
    private TurnView latestTurn(CurrentActor actor, String threadId) {
        AgentStore.ConversationKey key = conversation(actor, threadId);
        AgentStore.ConversationSnapshot snapshot = store.loadConversation(key).orElseThrow(() -> new ChatNotFoundException("thread not found"));
        if (snapshot.latestExecutionSequence() == 0) {
            return null;
        }
        AgentStore.ExecutionPage page = store.readExecutions(key, snapshot.latestExecutionSequence() - 1, snapshot.latestExecutionSequence(), 1);
        if (page.executions().isEmpty()) {
            return null;
        }
        AgentStore.ExecutionSnapshot execution = page.executions().getFirst();
        return new TurnView(execution.key().turnId(), execution.state(), execution.updatedAt());
    }

    /**
     * 所有非终态都拒绝归档和第二个新 Turn，避免等待/暂停期间丢失操作入口。
     */
    private String latestActiveTurn(CurrentActor actor, String threadId) {
        ThreadService.TurnView latest = latestTurn(actor, threadId);
        return latest != null && ThreadMetadata.isActive(latest.state()) ? latest.turnId() : null;
    }

    /**
     * 元数据读按固定 demo 用户与租户约束，不依赖客户端传入身份。
     */
    private ThreadMetadata require(CurrentActor actor, String threadId) {
        if (threadId == null || !threadId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}"))
            {
                throw new IllegalArgumentException("invalid thread id");
            }
        ThreadMetadata metadata = directory.find(actor, threadId);
        if (metadata == null) {
            throw new ChatNotFoundException("thread not found");
        }
        return metadata;
    }

    /** 验证请求 actor 对 Thread 有归属权；跨 owner 与不存在统一返回 not found。 */
    public void requireOwned(CurrentActor actor, String threadId) {
        require(actor, threadId);
    }

    /** 从不可变 actor 构造 Core 会话键，避免 owner 身份由 Thread ID 推断。 */
    private static AgentStore.ConversationKey conversation(CurrentActor actor, String threadId) {
        return new AgentStore.ConversationKey(actor.tenantId(), threadId, actor.userId());
    }

    /** 为用户历史批次一次读取附件元数据，避免目录分页产生逐项数据库查询。 */
    private Map<String, AttachmentMetadata> metadataById(CurrentActor actor, String threadId, List<String> ids) {
        List<String> unique = List.copyOf(new LinkedHashSet<>(ids));
        if (unique.isEmpty()) {
            return Map.of();
        }
        return attachments.metadata(actor, threadId, unique).stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(AttachmentMetadata::id, value -> value));
    }

    /** 通过已批量读取的元数据恢复内容视图，不读取或复制附件字节。 */
    private static InputContentView inputView(UserContent content, Map<String, AttachmentMetadata> metadata) {
        List<AttachmentMetadata> referenced = content.attachmentIds().stream().map(id -> {
            AttachmentMetadata item = metadata.get(id);
            if (item == null) {
                throw new ChatNotFoundException("attachment metadata not found");
            }
            return item;
        }).toList();
        return new InputContentView(content.text(), content.skillIds(), content.attachmentIds(), referenced);
    }

    /**
     * 唯一键允许网络结果未知时安全回读原会话。
     */
    private static ThreadView view(ThreadMetadata metadata) {
        return new ThreadView(metadata.threadId(), metadata.agentId(), metadata.title(), metadata.archived(),
                metadata.version(), metadata.createdAt(), metadata.updatedAt());
    }

    /**
     * Thread 视图只暴露浏览器管理会话所需元数据，不泄露宿主持久化细节。
     *
     * @param threadId 所属对话的稳定标识
     * @param agentId 创建时固定的 Agent 定义
     * @param title 显示标题
     * @param archived 是否已归档
     * @param version 版本
     * @param createdAt 创建时间
     * @param updatedAt 最后更新时间
     */
    public record ThreadView(String threadId,
                             String agentId,
                             String title,
                             boolean archived,
                             long version,
                             Instant createdAt,
                             Instant updatedAt) { }

    /**
     * Turn 视图使用独立 turnId，避免客户端把模型请求或 RPC 请求误认为业务轮次。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param state 当前状态
     * @param updatedAt 最后更新时间
     */
    public record TurnView(String turnId,
                           String state,
                           Instant updatedAt) { }

    /**
     * 历史项保留持久记录、Turn 与 Tool call 三类身份，防止跨生命周期复用同一 ID。
     *
     * @param sequence 当前记录的顺序号
     * @param recordId 记录标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param kind 类型分类
     * @param text 文本内容
     * @param reasoning 推理
     * @param callId 模型生成的工具调用标识
     * @param name 名称
     * @param state 当前状态
     * @param content 用户输入的完整文本、Skill 与附件引用投影
     */
    public record HistoryItem(long sequence,
                              String recordId,
                              String turnId,
                              String kind,
                              String text,
                              String reasoning,
                              String callId,
                              String name,
                              String state,
                              InputContentView content) {
        /** 老的非用户事件保持紧凑构造；只有用户输入携带完整内容投影。 */
        public HistoryItem(long sequence, String recordId, String turnId, String kind, String text,
                           String reasoning, String callId, String name, String state) {
            this(sequence, recordId, turnId, kind, text, reasoning, callId, name, state, null);
        }

        /** 用附加元数据补全用户输入项，其余历史类型保持原投影。 */
        private HistoryItem withContent(InputContentView value) {
            return new HistoryItem(sequence, recordId, turnId, kind, text, reasoning, callId, name, state, value);
        }
    }

    /**
     * 历史页回传固定水位与最新 Turn 摘要，供客户端稳定翻页并恢复进行中状态。
     *
     * @param items 条目列表
     * @param nextSequence 下一序号
     * @param throughSequence 当前快照覆盖到的顺序号
     * @param hasMore 是否还有后续分页数据
     * @param latestTurn 最近执行轮次
     */
    public record HistoryPage(List<HistoryItem> items,
                              long nextSequence,
                              long throughSequence,
                              boolean hasMore,
                              TurnView latestTurn) {
        /** 切断返回页与历史投影临时列表的别名，防止序列化前被后续代码改写。 */
        public HistoryPage {
            items = List.copyOf(items);
        }
    }
}

