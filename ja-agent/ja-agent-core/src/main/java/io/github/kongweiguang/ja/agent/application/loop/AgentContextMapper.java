// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.context.ContextException;
import io.github.kongweiguang.ja.agent.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.agent.application.context.ModelContinuation;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.RetainedSplit;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.conversation.UserMessage;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelContent;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.NativeAttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.NativeToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolCallContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.prompt.AgentPromptSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在持久化消息、上下文预算模型与 Provider 请求之间转换，避免 Wire 或存储表示进入 Agent Loop。
 */
public final class AgentContextMapper {
    private final JsonValueCodec argumentsCodec;
    private final ExecutionRecordCodec records;

    /**
     * 固定结构化参数编解码协议，保证 Tool 调用写入历史后仍能按同一语义回放。
     */
    public AgentContextMapper(JsonValueCodec argumentsCodec, ExecutionRecordCodec records) {
        this.argumentsCodec = Objects.requireNonNull(argumentsCodec, "argumentsCodec");
        this.records = Objects.requireNonNull(records, "records");
    }

    /**
     * 明确验证宿主选择，不默默忽略不存在或跨 Thread 的 Turn 身份。
     */
    private static Set<String> selection(AgentLoopPersistence.ConversationHistory history, Set<String> requested) {
        Objects.requireNonNull(history, "history");
        Set<String> selected = Set.copyOf(Objects.requireNonNull(requested, "visibleTurnIds"));
        Set<String> available = history.executions().stream().map(execution -> execution.key().turnId())
                .collect(java.util.stream.Collectors.toSet());
        if (!available.containsAll(selected))
            {
                throw new IllegalArgumentException("history contains an unknown Turn");
            }
        return selected;
    }

    /**
     * 父身份必须属于同一租户 Thread，且具体 Turn 已包含在冻结来源快照中。
     */
    private static void requireSourceIdentity(AgentLoopPersistence.ConversationHistory history,
                                              InitialContextSeed.SourceIdentity source,
                                              String currentTurnId) {
        AgentStore.ConversationKey key = history.conversation().key();
        boolean sourceTurnExists = history.executions().stream()
                .anyMatch(execution -> execution.key().turnId().equals(source.turnId()));
        if (!key.tenantId().equals(source.tenantId()) || !key.threadId().equals(source.threadId())
            || !sourceTurnExists) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed source identity does not match its conversation history");
        }
        if (!source.turnId().equals(currentTurnId)) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed source is not the frozen parent Turn");
        }
    }

    /**
     * child 的身份必须精确指向该固定 Thread，不能让 mapper 跨租户或跨 Thread 读取 seed。
     */
    private static void requireChildIdentity(AgentLoopPersistence.ConversationHistory history,
                                             AgentIdentity childIdentity) {
        AgentStore.ConversationKey key = history.conversation().key();
        if (!key.tenantId().equals(childIdentity.tenantId())
            || !key.threadId().equals(childIdentity.threadId())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed was requested for another child conversation");
        }
    }

    /**
     * checkpoint 边界裁切父有效尾部，先恢复保留 split 再使用策略相同的 inclusive 下界。
     */
    private static List<ContextMessage> applyCheckpointTail(List<ContextMessage> source,
                                                            CheckpointStore.ContextCheckpoint checkpoint) {
        List<ContextMessage> normalized = new ArrayList<>(source.size());
        Optional<RetainedSplit> split = checkpoint.retainedSplit();
        boolean matched = split.isEmpty();
        for (ContextMessage message : source) {
            if (split.isPresent() && message.messageId().equals(split.orElseThrow().sourceMessageId())) {
                ContextMessage retained = split.orElseThrow().retainedMessage();
                if (matched || message.ordinal() != retained.ordinal()
                    || !message.turnId().equals(retained.turnId()) || message.role() != retained.role()) {
                    throw new ContextException(ContextException.Code.INVALID_STATE,
                            "source retained split does not match its frozen parent history");
                }
                normalized.add(retained);
                matched = true;
            } else {
                normalized.add(message);
            }
        }
        if (!matched) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "source retained split message is unavailable");
        }
        return normalized.stream().filter(message -> message.ordinal() >= checkpoint.retainedFromOrdinal()).toList();
    }

    /**
     * 只冻结 Tool 调用与结果闭合的完整前缀，避免把当前 spawn 调用自身作为历史交给 child。
     */
    private static List<ContextMessage> completeToolPrefix(List<ContextMessage> source) {
        Map<String, Integer> pendingCalls = new java.util.LinkedHashMap<>();
        for (int index = 0; index < source.size(); index++) {
            ContextMessage message = source.get(index);
            for (String callId : message.toolCallIds()) {
                Integer prior = pendingCalls.putIfAbsent(callId, index);
                if (prior != null) {
                    return List.copyOf(source.subList(0, prior));
                }
            }
            for (String callId : message.toolResultIds()) {
                pendingCalls.remove(callId);
            }
        }
        if (pendingCalls.isEmpty()) {
            return List.copyOf(source);
        }
        int firstIncomplete = pendingCalls.values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        return List.copyOf(source.subList(0, firstIncomplete));
    }

    /**
     * 将会话记录的固定序号和版本复制到严格 typed decoder，不伪造或重分配 Store 水位。
     */
    private static AgentStore.StoredRecord storedRecord(AgentStore.ConversationRecord record) {
        return new AgentStore.StoredRecord(record.recordId(), record.recordType(), record.payloadSchemaVersion(),
                record.sequence(), record.payload(), record.occurredAt());
    }

    /**
     * 从不可变消息与调用身份派生线程内 artifact URI；读取端仍必须重新验证 Thread 可见性。
     */
    private static String artifactUri(String messageId, String callId) {
        return "agent-artifact://tool-result/" + messageId + "/" + callId;
    }

    /**
     * 只接受协议中可公开展示的文本字段；redacted_thinking/data 等 opaque 字段不进入降级路径。
     */
    private static String directReasoningText(JsonObject object, String wireField) {
        if (!Set.of("thinking", "reasoning_content", "reasoning", "reasoning_text").contains(wireField)) {
            return "";
        }
        JsonValue value = object.get(wireField);
        return value instanceof JsonText(String value1) ? value1 : "";
    }

    /**
     * Responses 的公开摘要由多个 summary_text part 组成，按 Provider 顺序用空行恢复段落边界。
     */
    private static String responsesSummaryText(JsonValue value) {
        if (!(value instanceof JsonArray(List<JsonValue> values))) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (JsonValue member : values) {
            if (!(member instanceof JsonObject part)
                || !(part.get("type") instanceof JsonText(String value2))
                || !"summary_text".equals(value2)) {
                continue;
            }
            if (!(part.get("text") instanceof JsonText(String value1)) || value1.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n\n");
            }
            result.append(value1);
        }
        return result.toString();
    }

    /**
     * 跨模型公开摘要按已有文本块大小分段，保留完整原文且不制造单个巨型块。
     */
    private static void addPublicReasoningBlocks(List<ModelContent> blocks, String value) {
        final int blockCharacters = 262_144;
        for (int start = 0; start < value.length(); ) {
            int end = Math.min(value.length(), start + blockCharacters);
            if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
                    end--;
                }
            blocks.add(new TextContent(value.substring(start, end)));
            start = end;
        }
    }

    /**
     * 模型模态与Codec双门及实际请求总量决定路由；宿主明确要求原生资料时，拒绝引用降级以守住消费竞争边界。
     */
    private static ModelContent routeAttachment(
            ContextMessage.AttachmentBlock attachment,
            ModelPort.ModelConfiguration configuration,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            NativeAttachmentBudget nativeBudget) {
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(attachments, "attachments");
        boolean modelMayAcceptNative = configuration.inputModalities().contains(ModelPort.InputModality.IMAGE)
                                       || configuration.inputModalities().contains(ModelPort.InputModality.PDF);
        if (!modelMayAcceptNative || nativeSupport.rules().isEmpty()) {
            return unavailableAttachment(attachment.attachmentId(), attachments, ContextException.Code.INVALID_STATE,
                    "The selected model API does not support native attachments.");
        }
        ManagedAttachmentReader.Descriptor descriptor = attachments.inspect(attachment.attachmentId(), threadId);
        NativeAttachmentContent.Kind kind = switch (descriptor.mediaKind()) {
            case "image" -> NativeAttachmentContent.Kind.IMAGE;
            case "pdf" -> NativeAttachmentContent.Kind.PDF;
            default -> null;
        };
        if (kind == null || !configuration.inputModalities().contains(
                kind == NativeAttachmentContent.Kind.IMAGE
                        ? ModelPort.InputModality.IMAGE : ModelPort.InputModality.PDF)) {
            return unavailableAttachment(attachment.attachmentId(), attachments, ContextException.Code.INVALID_STATE,
                    "The selected model does not support this native attachment format.");
        }
        java.util.OptionalLong maxBytes = nativeSupport.maxBytes(kind, descriptor.mediaType(), descriptor.sizeBytes());
        if (maxBytes.isEmpty() || !nativeBudget.canReserve(descriptor.sizeBytes())) {
            return unavailableAttachment(attachment.attachmentId(), attachments, ContextException.Code.CONTEXT_LIMIT,
                    "Native attachments exceed the selected model request limit. Compact the conversation before retrying.");
        }
        ManagedAttachmentReader.NativeReadResult nativeRead = attachments.readNative(
                new ManagedAttachmentReader.NativeReadRequest(
                        attachment.attachmentId(), threadId, maxBytes.orElseThrow()));
        ManagedAttachmentReader.Descriptor resolved = nativeRead.descriptor();
        if (!descriptor.equals(resolved)) {
            throw new IllegalStateException("managed attachment descriptor changed during native routing");
        }
        nativeBudget.reserve(descriptor.sizeBytes());
        return new NativeAttachmentContent(descriptor.attachmentId(), kind, descriptor.displayName(),
                descriptor.mediaType(), descriptor.sizeBytes(), nativeRead.base64Data());
    }

    /**
     * 统一生成安全 fallback，使不支持的媒体不会被伪装成已原生理解。
     */
    private static String attachmentManifest(String attachmentId) {
        return "Attachment " + attachmentId + " is available through the read_attachment tool.";
    }

    /**
     * 原生资料宿主在实际已规划请求上再次核验；读取工具宿主保留显式引用，不能默默跨越所选宿主的内容语义。
     */
    private static ModelContent unavailableAttachment(String attachmentId, ManagedAttachmentReader attachments,
                                                       ContextException.Code code, String explanation) {
        if (!attachments.allowsReferenceFallback()) {
            throw new ContextException(code, explanation);
        }
        return new TextContent(attachmentManifest(attachmentId));
    }

    /**
     * 只在模型与 Codec 都声明支持时读取工具图片；不支持时明确告诉模型图像正文没有发送。
     */
    private static ModelContent routeToolResult(
            ToolResultContent result,
            ModelPort.ModelConfiguration configuration,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            NativeAttachmentBudget nativeBudget) {
        if (result.media().isEmpty()) {
            return result;
        }
        boolean canReadImages = nativeSupport.supportsToolResultImages()
                && configuration.inputModalities().contains(ModelPort.InputModality.IMAGE);
        if (!canReadImages) {
            return new ToolResultContent(result.callId(), result.content() + toolImageLimitNotice(result.media()),
                    result.error());
        }
        List<NativeAttachmentContent> images = new ArrayList<>();
        StringBuilder content = new StringBuilder(result.content());
        for (ToolMediaReference reference : result.media()) {
            java.util.OptionalLong maxBytes = nativeSupport.maxBytes(NativeAttachmentContent.Kind.IMAGE,
                    reference.mediaType(), reference.sizeBytes());
            if (maxBytes.isEmpty() || !nativeBudget.canReserve(reference.sizeBytes())) {
                content.append(toolImageLimitNotice(List.of(reference)));
                continue;
            }
            ManagedAttachmentReader.NativeReadResult nativeRead = attachments.readNative(
                    new ManagedAttachmentReader.NativeReadRequest(reference.attachmentId(), threadId,
                            maxBytes.orElseThrow()));
            ManagedAttachmentReader.Descriptor descriptor = nativeRead.descriptor();
            if (!reference.attachmentId().equals(descriptor.attachmentId())
                || !reference.displayName().equals(descriptor.displayName())
                || reference.sizeBytes() != descriptor.sizeBytes()
                || !reference.mediaType().equals(descriptor.mediaType())
                || !"image".equals(descriptor.mediaKind())) {
                throw new IllegalStateException("tool image descriptor changed during native routing");
            }
            nativeBudget.reserve(reference.sizeBytes());
            images.add(new NativeAttachmentContent(reference.attachmentId(), NativeAttachmentContent.Kind.IMAGE,
                    reference.displayName(), reference.mediaType(), reference.sizeBytes(), nativeRead.base64Data()));
        }
        return images.isEmpty()
                ? new ToolResultContent(result.callId(), content.toString(), result.error())
                : new NativeToolResultContent(result.callId(), content.toString(), result.error(), images);
    }

    /**
     * 为确实未传输的媒体输出稳定说明，避免模型误以为它已经观察过图像像素。
     */
    private static String toolImageLimitNotice(List<ToolMediaReference> media) {
        return media.stream().map(reference -> "\n[Image not sent to this model: "
                + reference.displayName() + "; the active API or request budget does not support it.]")
                .collect(java.util.stream.Collectors.joining());
    }

    /**
     * 资源引用只把来源、资源 ID 和种类作为提示线索；正文未预加载，读取必须由真实宿主 Tool 决策。
     */
    private static String resourceReferenceManifest(ResourceReferenceContent reference) {
        return "Resource reference [" + reference.kind() + "] " + reference.sourceId() + ":"
               + reference.resourceId() + " (content not preloaded).";
    }

    /**
     * 将可持久化消息角色映射到上下文角色，显式限制历史中允许出现的角色集合。
     */
    private static ContextMessage.Role contextRole(ModelRole role) {
        return switch (role) {
            case USER -> ContextMessage.Role.USER;
            case ASSISTANT -> ContextMessage.Role.ASSISTANT;
            case TOOL -> ContextMessage.Role.TOOL;
        };
    }

    /**
     * 将上下文角色映射回模型角色；SYSTEM 必须进入 instructions，不能伪装为普通消息。
     */
    private static ModelRole modelRole(ContextMessage.Role role) {
        return switch (role) {
            case USER -> ModelRole.USER;
            case ASSISTANT -> ModelRole.ASSISTANT;
            case TOOL -> ModelRole.TOOL;
            case SYSTEM -> throw new ContextException(ContextException.Code.INVALID_STATE,
                    "SYSTEM context must be rendered as instructions");
        };
    }

    /**
     * 当前执行只看所选历史中已准入的前序任务，不把后续排队输入提前送给模型。
     */
    public List<ContextMessage> fromSnapshot(AgentLoopPersistence.ConversationHistory history,
                                             Set<String> visibleTurnIds, String currentTurnId) {
        Objects.requireNonNull(history, "history");
        Set<String> selected = selection(history, visibleTurnIds);
        LinkedHashSet<String> throughCurrent = new LinkedHashSet<>();
        boolean found = false;
        for (AgentStore.ExecutionSnapshot execution : history.executions()) {
            if (!selected.contains(execution.key().turnId())) {
                continue;
            }
            throughCurrent.add(execution.key().turnId());
            if (execution.key().turnId().equals(currentTurnId)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("current execution is absent from selected history");
        }
        return mapHistory(history, throughCurrent);
    }

    /**
     * 手动压缩只接受已终结的有效路径；显示投影和失败审计不属于模型消息记录。
     */
    public List<ContextMessage> fromCompleteSnapshot(AgentLoopPersistence.ConversationHistory history,
                                                     Set<String> visibleTurnIds) {
        Set<String> selected = selection(history, visibleTurnIds);
        if (history.executions().stream().anyMatch(execution -> selected.contains(execution.key().turnId())
                                                                && !io.github.kongweiguang.ja.agent.domain.turn.TurnState.valueOf(execution.state()).terminal())) {
            throw new ContextException(ContextException.Code.CAS_CONFLICT, "conversation has an active execution");
        }
        return mapHistory(history, selected);
    }

    /**
     * 从固定父历史和同水位 checkpoint 冻结 child 的有效背景；checkpoint 只裁切父尾部，
     * sourceOrdinal 仍是来源范围，不会冒充 child 的压缩 throughOrdinal。
     */
    public Optional<InitialContextSeed> freezeInitialContextSeed(
            AgentLoopPersistence.ConversationHistory history,
            Set<String> visibleTurnIds,
            String currentTurnId,
            InitialContextSeed.SourceIdentity sourceIdentity,
            Optional<CheckpointStore.ContextCheckpoint> sourceCheckpoint) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        sourceCheckpoint = Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
        requireSourceIdentity(history, sourceIdentity, currentTurnId);
        List<ContextMessage> sourceMessages = fromSnapshot(history, visibleTurnIds, currentTurnId);
        long sourceOrdinal = sourceMessages.stream().mapToLong(ContextMessage::ordinal).max().orElse(0);
        Optional<SummaryDocument> summary = sourceCheckpoint.map(CheckpointStore.ContextCheckpoint::summary)
                .filter(value -> !value.hasNoFacts());

        List<ContextMessage> effectiveMessages = sourceMessages;
        if (sourceCheckpoint.isPresent()) {
            CheckpointStore.ContextCheckpoint checkpoint = sourceCheckpoint.orElseThrow();
            if (!checkpoint.threadId().equals(history.conversation().key().threadId())
                || checkpoint.sourceRevision() > history.conversation().revision()
                || checkpoint.throughOrdinal() > sourceOrdinal
                || checkpoint.retainedFromOrdinal() > sourceOrdinal) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "source checkpoint does not match the frozen parent history");
            }
            effectiveMessages = applyCheckpointTail(sourceMessages, checkpoint);
        }
        effectiveMessages = completeToolPrefix(effectiveMessages);
        if (effectiveMessages.isEmpty() && summary.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new InitialContextSeed(sourceIdentity, sourceOrdinal, effectiveMessages, summary));
    }

    /**
     * Freezes an already item-truncated neutral history; only a checkpoint whose covered and retained ordinals fit
     * inside that exact prefix may summarize it, preventing a later checkpoint from leaking future conversation.
     */
    public InitialContextSeed freezeInitialContextSeedThroughMessages(
            List<ContextMessage> sourceMessages,
            InitialContextSeed.SourceIdentity sourceIdentity,
            Optional<CheckpointStore.ContextCheckpoint> sourceCheckpoint) {
        Objects.requireNonNull(sourceMessages, "sourceMessages");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        sourceCheckpoint = Objects.requireNonNull(sourceCheckpoint, "sourceCheckpoint");
        List<ContextMessage> ordered = List.copyOf(sourceMessages);
        if (ordered.isEmpty()) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "selected reply has no reconstructable model history");
        }
        long sourceOrdinal = ordered.getLast().ordinal();
        Optional<SummaryDocument> summary = Optional.empty();
        List<ContextMessage> effective = ordered;
        if (sourceCheckpoint.isPresent()) {
            CheckpointStore.ContextCheckpoint checkpoint = sourceCheckpoint.orElseThrow();
            if (!checkpoint.threadId().equals(sourceIdentity.threadId())
                || checkpoint.throughOrdinal() > sourceOrdinal
                || checkpoint.retainedFromOrdinal() > sourceOrdinal) {
                throw new ContextException(ContextException.Code.INVALID_STATE,
                        "source checkpoint is outside the selected reply boundary");
            }
            summary = Optional.of(checkpoint.summary()).filter(value -> !value.hasNoFacts());
            effective = applyCheckpointTail(ordered, checkpoint);
        }
        effective = completeToolPrefix(effective);
        if (effective.isEmpty() && summary.isEmpty()) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "selected reply has no effective model context");
        }
        return new InitialContextSeed(sourceIdentity, sourceOrdinal, effective, summary);
    }

    /**
     * 读取会话级冻结 Seed，不随 reask 的 selected execution path 被错误过滤。
     */
    public Optional<InitialContextSeed> initialContextSeed(
            AgentLoopPersistence.ConversationHistory history, AgentIdentity childIdentity) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(childIdentity, "childIdentity");
        requireChildIdentity(history, childIdentity);
        return decodeInitialContextSeed(history);
    }

    /**
     * 新模型请求只继承仍未被 child checkpoint 吸收的父摘要；seed 消息始终单独映射一次。
     */
    public Optional<SummaryDocument> seedSummary(
            AgentLoopPersistence.ConversationHistory history, AgentIdentity childIdentity) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(childIdentity, "childIdentity");
        requireChildIdentity(history, childIdentity);
        if (history.records().stream().anyMatch(record -> "agent.context.checkpoint".equals(record.recordType()))) {
            return Optional.empty();
        }
        return decodeInitialContextSeed(history).flatMap(InitialContextSeed::summary);
    }

    /**
     * 按准入次序及事务记录序列还原消息，审计、用量和业务展示记录不会冒充模型上下文。
     */
    private List<ContextMessage> mapHistory(AgentLoopPersistence.ConversationHistory history, Set<String> selected) {
        List<ContextMessage> result = new ArrayList<>();
        Map<String, String> toolNames = new java.util.HashMap<>();
        Set<String> messageIds = new java.util.HashSet<>();
        Optional<InitialContextSeed> seed = decodeInitialContextSeed(history);
        long ordinal = seed.map(value -> Math.addExact(value.sourceOrdinal(), 1)).orElse(1L);
        if (seed.isPresent()) {
            for (ContextMessage message : seed.orElseThrow().messages()) {
                if (!messageIds.add(message.messageId())) {
                    throw new ContextException(ContextException.Code.INVALID_STATE,
                            "seed message identity conflicts with child execution history");
                }
                result.add(message);
                message.toolCallIds().forEach(callId -> toolNames.put(callId,
                        message.blocks().stream().filter(ContextMessage.ToolCallBlock.class::isInstance)
                                .map(ContextMessage.ToolCallBlock.class::cast)
                                .filter(call -> call.callId().equals(callId)).findFirst().orElseThrow().name()));
            }
        }
        Map<AgentStore.ExecutionKey, List<AgentStore.ConversationRecord>> byExecution = new java.util.HashMap<>();
        for (AgentStore.ConversationRecord record : history.records()) {
            byExecution.computeIfAbsent(record.execution(), ignored -> new ArrayList<>()).add(record);
        }
        for (AgentStore.ExecutionSnapshot execution : history.executions()) {
            String turnId = execution.key().turnId();
            if (!selected.contains(turnId)) {
                continue;
            }
            for (AgentStore.ConversationRecord record : byExecution.getOrDefault(execution.key(), List.of())) {
                AgentStore.StoredRecord stored = new AgentStore.StoredRecord(record.recordId(), record.recordType(),
                        record.payloadSchemaVersion(), record.sequence(), record.payload(), record.occurredAt());
                HistoryMessage message = switch (record.recordType()) {
                    case "agent.user.message" -> {
                        UserMessage user = records.decodeUser(stored);
                        yield new HistoryMessage(user.messageId(), turnId,
                                new ModelMessage(ModelRole.USER, new ArrayList<ModelContent>(user.content().blocks())));
                    }
                    case "agent.assistant" -> {
                        ExecutionFacts.AssistantMessage assistant = (ExecutionFacts.AssistantMessage) records.decode(stored);
                        yield new HistoryMessage(assistant.messageId(), turnId, assistant.message());
                    }
                    case "agent.tool.message" -> {
                        ExecutionFacts.ToolMessage tool = (ExecutionFacts.ToolMessage) records.decode(stored);
                        yield new HistoryMessage(tool.messageId(), turnId, tool.message());
                    }
                    case "agent.context.instruction" -> {
                        ContextInstruction instruction = records.decodeInstruction(stored);
                        yield new HistoryMessage(instruction.messageId(), turnId,
                                new ModelMessage(ModelRole.USER, List.of(new TextContent(instruction.text()))));
                    }
                    default -> null;
                };
                if (message != null) {
                    if (!messageIds.add(message.messageId()))
                        {
                            throw new IllegalStateException("duplicate historical message identity");
                        }
                    result.add(fromStoredMessage(message, ordinal++, toolNames));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * 解码最多一条首准入 Seed，并验证记录确实处于 child 对话第一次执行的开头。
     */
    private Optional<InitialContextSeed> decodeInitialContextSeed(
            AgentLoopPersistence.ConversationHistory history) {
        List<AgentStore.ConversationRecord> seedRecords = history.records().stream()
                .filter(record -> ExecutionRecordCodec.INITIAL_CONTEXT_SEED_RECORD_TYPE.equals(record.recordType()))
                .toList();
        if (seedRecords.isEmpty()) {
            return Optional.empty();
        }
        if (seedRecords.size() != 1 || history.executions().isEmpty()) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "conversation has an ambiguous initial context seed");
        }
        AgentStore.ExecutionSnapshot firstExecution = history.executions().getFirst();
        AgentStore.ConversationRecord seedRecord = seedRecords.getFirst();
        if (!seedRecord.execution().equals(firstExecution.key())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed is not owned by the first child execution");
        }
        long firstRecordSequence = history.records().stream()
                .filter(record -> record.execution().equals(firstExecution.key()))
                .mapToLong(AgentStore.ConversationRecord::sequence).min().orElse(Long.MAX_VALUE);
        if (seedRecord.sequence() != firstRecordSequence) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed is not the first child execution record");
        }
        InitialContextSeed seed;
        try {
            seed = records.decodeInitialContextSeed(storedRecord(seedRecord));
        } catch (RuntimeException failure) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed is corrupt", failure);
        }
        AgentStore.ConversationKey child = history.conversation().key();
        if (!child.tenantId().equals(seed.sourceIdentity().tenantId())
            || child.threadId().equals(seed.sourceIdentity().threadId())) {
            throw new ContextException(ContextException.Code.INVALID_STATE,
                    "initial context seed source is outside the child tenant boundary");
        }
        return Optional.of(seed);
    }

    /**
     * 把动态 System snapshot 与预算裁剪后的持久上下文组装成不可变 Provider 请求；summary
     * 已由 Prompt Session 放入 System，禁止再次生成普通 Message。工具声明从同一份保留历史派生，
     * 因而压缩估算、实际发送和重启恢复不会分别维护容易失步的 MCP 激活集合。
     */
    public ModelPort.ModelRequest toModelRequest(
            ContextOrchestrator.PreparedPrompt prompt,
            ModelPort.ModelConfiguration configuration,
            AgentPromptSnapshot snapshot,
            List<AgentTool> tools,
            ModelPort.Continuation continuation,
            int round,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport) {
        return toModelRequest(prompt, configuration, snapshot, tools, continuation, round, threadId,
                attachments, nativeSupport, Optional.empty());
    }

    /**
     * 将本次冻结 Schema 与同一预算过的消息一起构造，避免计量与实际请求使用不同终答格式。
     */
    public ModelPort.ModelRequest toModelRequest(
            ContextOrchestrator.PreparedPrompt prompt,
            ModelPort.ModelConfiguration configuration,
            AgentPromptSnapshot snapshot,
            List<AgentTool> tools,
            ModelPort.Continuation continuation,
            int round,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            Optional<JsonObject> outputSchema) {
        Objects.requireNonNull(outputSchema, "outputSchema");
        NativeAttachmentBudget nativeBudget = new NativeAttachmentBudget(
                Objects.requireNonNull(nativeSupport, "nativeSupport").maxTotalBytes());
        return new ModelPort.ModelRequest(
                configuration,
                new ModelPort.PromptPayload(snapshot.systemPrompt(), snapshot.revision()),
                prompt.messages().stream().map(message -> toModelMessage(message, configuration,
                                threadId, attachments, nativeSupport, nativeBudget))
                        .filter(Objects::nonNull).toList(),
                tools.stream().map(AgentTool::spec).toList(),
                continuation,
                round,
                ModelPort.RequestDeadlinePolicy.CALL_BOUNDED,
                null,
                outputSchema);
    }

    /**
     * 执行目录保留当前 Provider 请求的完整声明，MCP 内部网关自行管理远端发现。
     */
    public List<AgentTool> toolCatalog(List<AgentTool> tools) {
        return List.copyOf(Objects.requireNonNull(tools, "tools"));
    }

    /**
     * 将 Provider 可选续传令牌收敛为应用层值，阻止空引用在上下文编排中传播。
     */
    Optional<ModelContinuation> continuation(ModelPort.Continuation continuation) {
        return continuation == null
                ? Optional.empty()
                : Optional.of(new ModelContinuation(continuation.protocol(), continuation.opaqueState()));
    }

    /**
     * 按消息块重建上下文并估算预算；孤立 Tool result 会拒绝恢复，以免向模型伪造调用关系。
     */
    private ContextMessage fromStoredMessage(
            HistoryMessage stored, long promptOrdinal, Map<String, String> toolNames) {
        List<ContextMessage.Block> blocks = new ArrayList<>();
        int characters = 0;
        for (ModelContent content : stored.message().content()) {
            if (content instanceof AttachmentContent(String attachmentId)) {
                String manifest = attachmentManifest(attachmentId);
                blocks.add(new ContextMessage.AttachmentBlock(attachmentId));
                characters = Math.addExact(characters, manifest.length());
            } else if (content instanceof TextContent(String text1)) {
                blocks.add(new ContextMessage.TextBlock(text1));
                characters = Math.addExact(characters, text1.length());
            } else if (content instanceof ReasoningContent reasoning) {
                /* opaque 原文参与同身份请求的预算，但不进入摘要文本或普通日志。 */
                blocks.add(new ContextMessage.ReasoningBlock(reasoning));
                characters = Math.addExact(characters, reasoning.nativeJson().length());
            } else if (content instanceof ResourceReferenceContent reference) {
                String manifest = resourceReferenceManifest(reference);
                blocks.add(new ContextMessage.TextBlock(manifest));
                characters = Math.addExact(characters, manifest.length());
            } else if (content instanceof SkillReferenceContent) {
                // Skill 正文只进入本条消息的动态 System；用户历史保留引用但不能把 ID 伪装成提示词。
            } else if (content instanceof ToolCallContent call) {
                String arguments = writeArguments(call.arguments());
                blocks.add(new ContextMessage.ToolCallBlock(call.callId(), call.name(), arguments));
                toolNames.put(call.callId(), call.name());
                characters = Math.addExact(characters, arguments.length());
            } else if (content instanceof ToolResultContent result) {
                String toolName = toolNames.get(result.callId());
                if (toolName == null) {
                    throw new ContextException(ContextException.Code.INVALID_STATE, "Tool result has no prepared call");
                }
                String promptContent = result.content() + imageManifest(result.media());
                blocks.add(new ContextMessage.ToolResultBlock(result.callId(), toolName,
                        ContextMessage.ToolOutput.full(promptContent,
                                artifactUri(stored.messageId(), result.callId()), null,
                                result.error() ? "tool_error" : null, result.media())));
                characters = Math.addExact(characters, promptContent.length());
            }
        }
        int estimatedTokens = Math.max(1, (characters + 3) / 4);
        return new ContextMessage(
                stored.messageId(),
                stored.turnId(),
                promptOrdinal,
                contextRole(stored.message().role()),
                blocks,
                estimatedTokens);
    }

    /**
     * 把应用层上下文块还原为 Provider 领域消息，保持原有块顺序和 Tool 错误语义。
     */
    private ModelMessage toModelMessage(
            ContextMessage message,
            ModelPort.ModelConfiguration configuration,
            String threadId,
            ManagedAttachmentReader attachments,
            ModelPort.NativeAttachmentSupport nativeSupport,
            NativeAttachmentBudget nativeBudget) {
        List<ModelContent> blocks = new ArrayList<>();
        String endpointFingerprint = ReasoningContent.endpointFingerprint(configuration.baseUri());
        for (ContextMessage.Block block : message.blocks()) {
            if (block instanceof ContextMessage.TextBlock(String value)) {
                blocks.add(new TextContent(value));
            } else if (block instanceof ContextMessage.AttachmentBlock attachment) {
                blocks.add(routeAttachment(attachment, configuration, threadId, attachments,
                        nativeSupport, nativeBudget));
            } else if (block instanceof ContextMessage.ToolCallBlock(String callId, String name, String arguments)) {
                blocks.add(new ToolCallContent(callId, name, readArguments(arguments)));
            } else if (block instanceof ContextMessage.ToolResultBlock result) {
                blocks.add(routeToolResult(new ToolResultContent(result.callId(), result.output().content(),
                                result.output().error() != null, result.output().media()),
                        configuration, threadId, attachments, nativeSupport, nativeBudget));
            } else if (block instanceof ContextMessage.ReasoningBlock(ReasoningContent content)) {
                if (content.matches(configuration.providerId(), configuration.modelId(),
                        configuration.api().name().toLowerCase(Locale.ROOT), configuration.model(),
                        endpointFingerprint)) {
                    blocks.add(content);
                } else {
                    /*
                     * Provider 签名/encrypted_content 只能同身份回放；跨身份仍保留 Provider 明确公开的
                     * thinking/summary 文本，避免切换模型时上下文出现静默断层。解码失败或 redacted
                     * 块没有公开文本时安全丢弃，绝不把 opaque JSON 当普通提示词发送。
                     */
                    String publicReasoning = crossProviderReasoningText(content);
                    if (publicReasoning != null) {
                        addPublicReasoningBlocks(blocks, publicReasoning);
                    }
                }
            }
        }
        /* 模型或端点切换时丢弃不匹配的 opaque 块，绝不把签名材料发送给新身份。 */
        return blocks.isEmpty() ? null : new ModelMessage(modelRole(message.role()), blocks);
    }

    /**
     * 图片名称作为文本摘要与媒体事实同时保留，压缩时即使像素不能继续发送也不会丢失来源线索。
     */
    private static String imageManifest(List<ToolMediaReference> media) {
        return media.stream().map(reference -> "\n[Tool image: " + reference.displayName() + "]")
                .collect(java.util.stream.Collectors.joining());
    }

    /**
     * 从 Provider 原生块提取已经公开给调用方的 thinking/summary 文本，供跨身份 assistant 历史降级。
     * 该路径只读取明确的文本字段和 Responses summary_text，永远不读取 signature、data 或
     * encrypted_content；异常按“没有可安全降级的文本”处理，避免损坏历史扩大为请求失败。
     */
    private String crossProviderReasoningText(ReasoningContent reasoning) {
        try {
            JsonValue decoded = argumentsCodec.decode(reasoning.nativeJson());
            if (!(decoded instanceof JsonObject object)) {
                return null;
            }
            String direct = directReasoningText(object, reasoning.wireField());
            if (!direct.isBlank()) {
                return direct;
            }
            String summary = responsesSummaryText(object.get("summary"));
            return summary.isBlank() ? null : summary;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 使用唯一编解码端口序列化参数，避免各 Provider 或存储适配器产生不同 JSON 形态。
     */
    private String writeArguments(JsonObject arguments) {
        return Objects.requireNonNull(argumentsCodec.encode(arguments), "encoded arguments");
    }

    /**
     * 解码历史中的 Tool 参数并冻结结果，防止模型请求提交后被调用方继续修改。
     */
    private JsonObject readArguments(String arguments) {
        return Objects.requireNonNull(argumentsCodec.decodeObject(arguments), "decoded arguments");
    }

    /**
     * 复制续传协议与不透明状态，保持应用层不解析 Provider 私有恢复令牌。
     */
    ModelPort.Continuation toModelContinuation(ModelContinuation continuation) {
        return new ModelPort.Continuation(continuation.protocol(), continuation.opaqueState());
    }

    /**
     * 中立消息视图仅用于内容映射，不复制持久状态或依赖 Ja 的 UI/Workspace 聚合。
     *
     * @param messageId 消息标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param message 消息或诊断说明
     */
    private record HistoryMessage(String messageId,
                                  String turnId,
                                  ModelMessage message) {
    }

    /**
     * 单个冻结请求共享 Codec 总量预算，超过时按附件顺序稳定回退到 Tool。
     */
    private static final class NativeAttachmentBudget {
        private final long maximum;
        private long used;

        /**
         * 总量来自 Codec 能力快照，application 不能自行扩大。
         */
        private NativeAttachmentBudget(long maximum) {
            this.maximum = maximum;
        }

        /**
         * 使用减法判断避免大值相加回绕。
         */
        private boolean canReserve(long sizeBytes) {
            return sizeBytes > 0 && used <= maximum - sizeBytes;
        }

        /**
         * 只在完整读取与描述复核成功后计入，失败不得污染后续路由决定。
         */
        private void reserve(long sizeBytes) {
            if (!canReserve(sizeBytes)) {
                throw new IllegalStateException("native attachment budget exceeded");
            }
            used += sizeBytes;
        }
    }
}
