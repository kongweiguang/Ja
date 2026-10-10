// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 在不同 Handler 间共享无状态 Wire 投影规则。
 */
public final class RpcResults {
    private static final int TASK_CONTEXT_PREVIEW_ITEMS = 24;
    private static final int TASK_CONTEXT_PREVIEW_ITEM_CODE_POINTS = 512;
    private static final int TASK_CONTEXT_PREVIEW_TOTAL_CODE_POINTS = 4_096;
    private static final int TASK_CONTEXT_PREVIEW_ATTACHMENTS = 10;
    private static final int TASK_CONTEXT_SUMMARY_MAX_CHARS = 32_768;
    private static final String TASK_CONTEXT_SUMMARY_TRUNCATION = "\n…（摘要预览已截断）";

    /**
     * 禁止实例化无状态投影类型，避免 Handler 私自持有领域状态。
     */
    private RpcResults() {
    }

    /**
     * 映射 Workspace 投影，不暴露契约之外的持久化字段。
     */
    public static ObjectNode workspace(ObjectMapper mapper, Workspace value) {
        return mapper.createObjectNode().put("workspaceId", value.workspaceId())
                .put("root", value.root().toString()).put("displayName", value.displayName())
                .put("trust", value.trust().name().toLowerCase(Locale.ROOT))
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("revision", value.revision());
    }

    /**
     * 映射 Thread 投影与下一轮偏好，不把 Provider 端点或凭据带入 Wire。
     */
    public static ObjectNode thread(ObjectMapper mapper, ThreadSummary value) {
        ObjectNode result = mapper.createObjectNode().put("threadId", value.threadId())
                .put("workspaceId", value.workspaceId()).put("title", value.title())
                .put("workspaceKind", value.workspaceKind())
                .put("status", value.status().name().toLowerCase(Locale.ROOT)).put("pinned", value.pinned())
                .put("latestTurnSeen", value.latestTurnSeen())
                .put("revision", value.revision())
                .put("createdAt", value.createdAt().toString()).put("updatedAt", value.updatedAt().toString());
        if (value.latestTurnStatus() == null) {
            result.putNull("latestTurnStatus");
        }
        else {
            result.put("latestTurnStatus", TurnStateWire.value(value.latestTurnStatus()));
        }
        ObjectNode preferences = result.putObject("preferences")
                .put("providerId", value.preferences().providerId())
                .put("modelId", value.preferences().modelId())
                .put("accessMode", value.preferences().accessMode().name().toLowerCase(Locale.ROOT))
                .put("collaborationMode", value.preferences().collaborationMode().name().toLowerCase(Locale.ROOT))
                .put("titleSource", value.preferences().titleSource().name().toLowerCase(Locale.ROOT));
        if (value.preferences().reasoningLevel() == null) {
            preferences.putNull("reasoningLevel");
        }
        else {
            preferences.put("reasoningLevel", value.preferences().reasoningLevel());
        }
        return result;
    }

    /**
     * 映射闭集 MCP 状态，只输出会话观测字段并省略未知值以维持真实零工具语义。
     */
    public static ObjectNode threadMcp(ObjectMapper mapper, ThreadMcpUseCase.ReadResult value) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(value, "value");
        if (value.servers().size() > 200 || (value.catalogRevision() != null
                                             && !value.catalogRevision().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))) {
            throw new IllegalStateException("invalid sanitized Thread MCP status");
        }
        ObjectNode result = mapper.createObjectNode()
                .put("threadId", value.threadId())
                .put("source", mcpSource(value.source()));
        ArrayNode notices = result.putArray("notices");
        value.notices().forEach(notice -> notices.add(mcpNotice(notice)));
        if (value.catalogRevision() != null) {
            result.put("catalogRevision", value.catalogRevision());
        }
        if (value.observedAt() != null) {
            result.put("observedAt", value.observedAt().toString());
        }
        ArrayNode servers = result.putArray("servers");
        value.servers().forEach(server -> {
            requireMcpServerWireFields(server);
            ObjectNode projected = servers.addObject()
                    .put("serverId", server.serverId())
                    .put("name", server.name())
                    .put("scope", mcpScope(server.scope()))
                    .put("state", mcpState(server.state()));
            if (server.toolCount() != null) {
                projected.put("toolCount", server.toolCount());
            }
            if (server.reasonCode() != null) {
                projected.put("reasonCode", server.reasonCode());
            }
        });
        return result;
    }

    /**
     * 在 Java 生产端执行 TypeScript 响应上限，避免异常运行时状态越过 JA-RPC。
     */
    private static void requireMcpServerWireFields(ThreadMcpUseCase.Server server) {
        String name = server.name();
        if (!server.serverId().matches("mcp_[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
            || name.isBlank() || name.length() > 512
            || name.indexOf('\0') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
            || (server.toolCount() != null && server.toolCount() > 2_000)) {
            throw new IllegalStateException("invalid sanitized Thread MCP server");
        }
    }

    /**
     * 固定来源词汇与 TypeScript schema 同步，新增领域枚举需先完成跨端合同变更。
     */
    private static String mcpSource(ThreadMcpUseCase.Source source) {
        return switch (source) {
            case ACTIVE -> "active";
            case LAST_OBSERVED -> "last_observed";
            case UNCHECKED -> "unchecked";
            case STALE -> "stale";
        };
    }

    /**
     * 来源与提示均以显式闭集输出，避免未来内部错误码无意进入 UI。
     */
    private static String mcpScope(ThreadMcpUseCase.Scope scope) {
        return switch (scope) {
            case GLOBAL -> "global";
            case PROJECT -> "project";
        };
    }

    /**
     * 多条提示可并存，但每条只能来自受控枚举，不能把原始配置诊断透传。
     */
    private static String mcpNotice(ThreadMcpUseCase.Notice notice) {
        return switch (notice) {
            case CONFIGURATION_CHANGED -> "configuration_changed";
            case PROJECT_UNTRUSTED -> "project_untrusted";
            case PROJECT_CONFIG_ERROR -> "project_config_error";
        };
    }

    /**
     * 固定服务状态词汇，避免把未知状态或设置页状态直接映射为会话可用性。
     */
    private static String mcpState(ThreadMcpUseCase.State state) {
        return switch (state) {
            case AVAILABLE -> "available";
            case UNAVAILABLE -> "unavailable";
            case DISABLED -> "disabled";
            case NOT_DISCOVERED -> "not_discovered";
            case NOT_EXPOSED -> "not_exposed";
            case STALE -> "stale";
        };
    }

    /**
     * 投影附件公开元数据；内容 hash、ingress token 与物理路径永不进入 Wire。
     */
    public static ObjectNode attachment(ObjectMapper mapper, AttachmentMetadata value) {
        ObjectNode result = mapper.createObjectNode().put("attachmentId", value.attachmentId())
                .put("workspaceId", value.workspaceId()).put("displayName", value.displayName())
                .put("sizeBytes", value.sizeBytes())
                .put("mediaKind", value.mediaKind().name().toLowerCase(Locale.ROOT))
                .put("mediaType", value.mediaType())
                .put("state", value.status().name().toLowerCase(Locale.ROOT))
                .put("createdAt", value.createdAt().toString())
                .put("expiresAt", value.expiresAt().toString());
        if (value.boundMessageId() == null) {
            result.putNull("boundMessageId");
        }
        else {
            result.put("boundMessageId", value.boundMessageId());
        }
        if (value.boundThreadId() == null) {
            result.putNull("boundThreadId");
        }
        else {
            result.put("boundThreadId", value.boundThreadId());
        }
        return result;
    }

    /**
     * 映射队列全量投影；数组顺序就是 App Server 已提交的真实消费顺序。
     */
    public static ObjectNode inputQueue(ObjectMapper mapper, InputQueue value) {
        ObjectNode result = mapper.createObjectNode().put("turnId", value.turnId())
                .put("revision", value.revision()).put("accepting", value.accepting());
        ArrayNode items = result.putArray("items");
        value.items().forEach(item -> items.add(queuedInput(mapper, item)));
        return result;
    }

    /**
     * 映射单条待处理输入，不暴露内部 sequence 或状态列。
     */
    public static ObjectNode queuedInput(ObjectMapper mapper, InputQueue.QueuedInput value) {
        ObjectNode result = mapper.createObjectNode().put("inputId", value.inputId())
                .put("turnId", value.turnId()).put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("status", value.status().name().toLowerCase(Locale.ROOT))
                .put("inputRevision", value.inputRevision()).put("createdAt", value.createdAt().toString());
        result.set("content", userContent(mapper, value.content()));
        result.set("attachments", attachmentSummaries(mapper, value.attachments()));
        if (value.issue() == null) {
            result.putNull("issue");
        }
        else {
            result.putObject("issue").put("errorCode", value.issue().errorCode())
                .put("message", value.issue().message()).put("retryable", value.issue().retryable());
        }
        return result;
    }

    /**
     * Core 内容块是 sealed 闭集；保留全文映射供请求输入使用，快照和事件必须走有界预览方法。
     */
    public static ArrayNode userContent(ObjectMapper mapper, UserContent content) {
        return userContent(mapper, content, Integer.MAX_VALUE);
    }

    /**
     * 将公开 USER 正文限制为快照预算，同时保留顺序固定的资源、Skill 与附件身份。
     */
    public static void userContentPreview(ObjectMapper mapper, ObjectNode target, UserContent content) {
        String text = content.text();
        userContentPreview(mapper, target, content, text.codePointCount(0, text.length()));
    }

    /**
     * 复用持久层权威正文总量；快照内容本身已经是预览，不能从它反推完整长度。
     */
    public static void userContentPreview(ObjectMapper mapper, ObjectNode target,
                                          UserContent content, int totalCharacters) {
        String text = content.text();
        ArrayNode preview = userContent(mapper, content, ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS);
        int previewCharacters = Math.min(text.codePointCount(0, text.length()),
                ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS);
        if (totalCharacters < previewCharacters) {
            throw new IllegalStateException("user input preview metadata is inconsistent");
        }
        target.set("content", preview);
        target.put("totalCharacters", totalCharacters).put("truncated", previewCharacters < totalCharacters);
    }

    /**
     * 将可见正文和 code-point 总量成对投影，避免截断预览被误认成完整内容。
     */
    public static void textPreview(ObjectNode target, String field, String text) {
        textPreview(target, field, "totalCharacters", "truncated", text);
    }

    /**
     * 使用持久层的原文总量投影快照页；预览只显示可见部分，不能被当作完整长度。
     */
    public static void textPreview(ObjectNode target, String field, String text, int totalCharacters) {
        textPreview(target, field, "totalCharacters", "truncated", text, totalCharacters);
    }

    /**
     * 对并列展示的多个正文使用专属元数据字段，避免长度信息覆盖相邻文本。
     */
    public static void textPreview(ObjectNode target, String field,
                                   String totalCharactersField, String truncatedField, String text) {
        int totalCharacters = text.codePointCount(0, text.length());
        textPreview(target, field, totalCharactersField, truncatedField, text, totalCharacters);
    }

    /**
     * 统一校验并投影预览和权威长度，防止 wire metadata 与领域快照发生漂移。
     */
    private static void textPreview(ObjectNode target, String field,
                                    String totalCharactersField, String truncatedField,
                                    String text, int totalCharacters) {
        String preview = previewText(text);
        int visibleCharacters = preview.codePointCount(0, preview.length());
        if (totalCharacters < visibleCharacters) {
            throw new IllegalStateException("text preview metadata is inconsistent");
        }
        target.put(field, preview).put(totalCharactersField, totalCharacters)
                .put(truncatedField, visibleCharacters < totalCharacters);
    }

    /**
     * 只有 wire 正文节点需要限制；附件、Skill 和 Workspace 引用保持原有精确身份。
     */
    private static ArrayNode userContent(ObjectMapper mapper, UserContent content, int textLimit) {
        ArrayNode result = mapper.createArrayNode();
        for (UserContentBlock block : content.blocks()) {
            ObjectNode item = result.addObject();
            switch (block) {
                case TextContent value -> item.put("type", "text")
                        .put("text", previewText(value.text(), textLimit));
                case AttachmentContent value -> item.put("type", "attachment")
                        .put("attachmentId", value.attachmentId());
                case ResourceReferenceContent value -> {
                    if (!value.sourceId().matches("ws_[A-Za-z0-9._-]{1,124}")
                        || value.resourceId().length() > 4_096
                        || !("file".equals(value.kind()) || "directory".equals(value.kind()))) {
                        throw new IllegalStateException("Ja cannot project a non-workspace resource reference");
                    }
                    item.put("type", "workspace_reference").put("workspaceId", value.sourceId())
                            .put("relativePath", value.resourceId()).put("kind", value.kind());
                }
                case SkillReferenceContent value -> item.put("type", "skill_reference")
                        .put("skillId", value.skillId());
            }
        }
        return result;
    }

    /**
     * 按 code point 截取，避免把补充平面字符拆成无效代理项。
     */
    private static String previewText(String text) {
        return previewText(text, ThreadSnapshot.TEXT_PREVIEW_CODE_POINTS);
    }

    /**
     * 按 code point 截取，完整长度由相邻元数据保留而不复制全文。
     */
    private static String previewText(String text, int maximumCodePoints) {
        int totalCharacters = text.codePointCount(0, text.length());
        if (totalCharacters <= maximumCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maximumCodePoints));
    }

    /**
     * 映射封闭的快照条目词汇表；大正文只保留有界预览和总量，完整文本按 itemId 读取。
     */
    public static ObjectNode snapshotItem(ObjectMapper mapper, ThreadSnapshot.Item item) {
        ThreadSnapshot.Item unwrapped = item instanceof ThreadSnapshot.OriginItem inherited
                ? inherited.item() : item;
        ObjectNode result = mapper.createObjectNode().put("itemId", item.itemId())
                .put("createdAt", item.createdAt().toString()).put("turnId", item.turnId())
                .put("origin", item.origin().name().toLowerCase(Locale.ROOT));
        switch (unwrapped) {
            case ThreadSnapshot.UserInputItem value -> {
                result.put("kind", "user_input");
                userContentPreview(mapper, result, value.content(), value.totalCharacters());
                if (value.totalCharacters() != result.path("totalCharacters").intValue()
                    || value.truncated() != result.path("truncated").booleanValue()) {
                    throw new IllegalStateException("user input preview metadata is inconsistent");
                }
                result.set("attachments", attachmentSummaries(mapper, value.attachments()));
            }
            case ThreadSnapshot.ThreadMessageItem value -> {
                result.put("kind", "thread_message").put("sourceThreadId", value.sourceThreadId())
                        .put("sourceTitle", value.sourceTitle());
                textPreview(result, "content", value.content(), value.totalCharacters());
                if (value.totalCharacters() != result.path("totalCharacters").intValue()
                    || value.truncated() != result.path("truncated").booleanValue()) {
                    throw new IllegalStateException("thread message preview metadata is inconsistent");
                }
            }
            case ThreadSnapshot.TextItem value -> {
                result.put("kind", value.kind().name().toLowerCase(Locale.ROOT));
                textPreview(result, "text", value.text(), value.totalCharacters());
                if (value.totalCharacters() != result.path("totalCharacters").intValue()
                    || value.truncated() != result.path("truncated").booleanValue()) {
                    throw new IllegalStateException("text item preview metadata is inconsistent");
                }
                if (value.modelRound() != null) {
                    result.put("modelRound", value.modelRound());
                }
            }
            case ThreadSnapshot.ToolItem value -> {
                result.put("kind", value.kind().name().toLowerCase(Locale.ROOT));
                result.put("callId", value.callId()).put("toolName", value.toolName())
                        .put("ordinal", value.ordinal())
                        .set("presentation", new ToolPresentationWireMapper(mapper).map(value.presentation()));
            }
            case ThreadSnapshot.ApprovalItem value -> {
                result.put("kind", "approval").put("approvalId", value.approvalId())
                        .put("turnId", value.turnId()).put("callId", value.callId())
                        .put("toolName", value.toolName()).put("reason", value.reason())
                        .put("expiresAt", value.expiresAt().toString());
                if (value.decision() == null) {
                    result.putNull("decision");
                }
                else {
                    result.put("decision", value.decision().toLowerCase(Locale.ROOT));
                }
            }
            case ThreadSnapshot.OriginItem ignored -> throw new IllegalStateException(
                    "snapshot item origin wrapper was not unwrapped");
        }
        return result;
    }

    /**
     * 附件摘要只随所属 USER Message 或队列项投影，数组顺序必须与 content block 一致。
     */
    static ArrayNode attachmentSummaries(ObjectMapper mapper,
                                         List<AttachmentSummary> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.addObject().put("attachmentId", value.attachmentId())
                .put("displayName", value.displayName()).put("sizeBytes", value.sizeBytes())
                .put("mediaKind", value.mediaKind()).put("mediaType", value.mediaType()));
        return result;
    }

    /**
     * 将 Provider 请求计量与最新本地完整 envelope 估算分开投影，恢复时不把本地值伪装成实测。
     */
    public static ObjectNode contextUsage(ObjectMapper mapper, ThreadSnapshot.ContextUsage usage) {
        ObjectNode result = requestUsage(mapper, usage.request(), usage.measuredAt())
                .put("turnId", usage.turnId());
        if (usage.estimatedContextTokens() == null) {
            result.putNull("contextEstimate");
        }
        else {
            result.putObject("contextEstimate")
                .put("certainty", "estimated")
                .put("source", "local")
                .put("usedTokens", usage.estimatedContextTokens())
                .put("limitTokens", usage.request().profile().contextWindowTokens())
                .put("measuredAt", usage.estimatedContextMeasuredAt().toString());
        }
        return result;
    }

    /**
     * 汇总账本只返回计量和覆盖范围，不包含模型配置、请求正文或缓存实现细节；前端通过计数
     * 判断某项是否为部分样本，而不能把缺失 Provider 字段解释为零。
     */
    public static ObjectNode threadUsageSummary(ObjectMapper mapper, String threadId,
                                                io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary value) {
        return mapper.createObjectNode().put("threadId", threadId).put("snapshotRevision", value.snapshotRevision())
                .put("requestCount", value.requestCount()).put("measuredRequestCount", value.measuredRequestCount())
                .put("newInputRequestCount", value.newInputRequestCount()).put("newInputTokens", value.newInputTokens())
                .put("outputRequestCount", value.outputRequestCount()).put("outputTokens", value.outputTokens())
                .put("totalRequestCount", value.totalRequestCount()).put("totalTokens", value.totalTokens())
                .put("cacheReadRequestCount", value.cacheReadRequestCount()).put("cacheReadTokens", value.cacheReadTokens())
                .put("cacheWriteRequestCount", value.cacheWriteRequestCount()).put("cacheWriteTokens", value.cacheWriteTokens())
                .put("cacheCompleteRequestCount", value.cacheCompleteRequestCount())
                .put("cacheCompleteInputTokens", value.cacheCompleteInputTokens())
                .put("cacheCompleteReadTokens", value.cacheCompleteReadTokens());
    }

    /**
     * 请求级 Usage 在事件与 thread/read 中共享唯一判别联合，UNKNOWN 仅保留真实的计量不可得语义。
     */
    static ObjectNode requestUsage(ObjectMapper mapper, ProviderRequestUsage value, java.time.Instant measuredAt) {
        ObjectNode result = mapper.createObjectNode().put("modelRequestId", value.modelRequestId())
                .put("modelRequestOrdinal", value.modelRequestOrdinal()).put("modelRound", value.modelRound())
                .put("purpose", value.purpose().name().toLowerCase(Locale.ROOT))
                .put("certainty", value.certainty().name().toLowerCase(Locale.ROOT))
                .put("measuredAt", measuredAt.toString());
        result.set("profile", providerRequestProfile(mapper, value.profile()));
        if (value.usage() == null) {
            result.putNull("inputTokens").putNull("outputTokens").putNull("totalTokens");
        } else {
            result.put("inputTokens", value.usage().inputTokens())
                    .put("outputTokens", value.usage().outputTokens())
                    .put("totalTokens", value.usage().totalTokens());
        }
        return result;
    }

    /**
     * Profile 只投影无密钥请求事实；nullable reasoning 必须显式保留。
     */
    private static ObjectNode providerRequestProfile(ObjectMapper mapper, ProviderRequestProfile profile) {
        ObjectNode result = mapper.createObjectNode().put("providerId", profile.providerId())
                .put("modelId", profile.modelId()).put("api", profile.api())
                .put("upstreamModel", profile.upstreamModel())
                .put("accessMode", profile.accessMode().name().toLowerCase(Locale.ROOT))
                .put("collaborationMode", profile.collaborationMode().name().toLowerCase(Locale.ROOT))
                .put("configGeneration", profile.configGeneration())
                .put("promptRevision", profile.promptRevision())
                .put("toolCatalogRevision", profile.toolCatalogRevision())
                .put("contextWindowTokens", profile.contextWindowTokens())
                .put("maxOutputTokens", profile.maxOutputTokens());
        nullable(result, "requestedReasoning", profile.requestedReasoning());
        nullable(result, "effectiveReasoning", profile.effectiveReasoning());
        return result;
    }

    /**
     * Turn 历史只公开 Operation 生命周期；模型身份统一由请求级 Usage Profile 提供。
     */
    public static ObjectNode snapshotTurn(ObjectMapper mapper, ThreadSnapshot.Turn turn) {
        ObjectNode result = mapper.createObjectNode().put("turnId", turn.turnId())
                .put("status", turn.status()).put("origin", turn.origin().name().toLowerCase(Locale.ROOT))
                .put("requestedAt", turn.requestedAt().toString())
                .put("updatedAt", turn.updatedAt().toString());
        if (turn.completedAt() == null) {
            result.putNull("completedAt");
        }
        else {
            result.put("completedAt", turn.completedAt().toString());
        }
        if (turn.errorCode() == null) {
            result.putNull("errorCode");
        }
        else {
            result.put("errorCode", turn.errorCode());
        }
        if (turn.changeSet() == null) {
            result.putNull("changeSet");
        }
        else {
            result.set("changeSet", changeSet(mapper, turn.changeSet()));
        }
        if (turn.sourceMessageId() == null) {
            result.putNull("sourceMessageId");
        }
        else {
            result.put("sourceMessageId", turn.sourceMessageId());
        }
        return result;
    }

    /**
     * 将 Java 活动流基线投影为稳定 nullable Wire 结构；segment kind 使用显式 camelCase，
     * 防止前端把公开 reasoning summary 与私有 Provider reasoning 混为一谈。
     */
    public static ObjectNode liveStream(ObjectMapper mapper, String turnId, long streamSeq,
                                        List<LiveStreamSegment> streamSegments) {
        ObjectNode result = mapper.createObjectNode().put("turnId", turnId)
                .put("streamSeq", streamSeq);
        ArrayNode segmentNodes = result.putArray("segments");
        streamSegments.forEach(segment -> segmentNodes.addObject()
                .put("kind", segment.kind())
                .put("segmentStartSeq", segment.segmentStartSeq())
                .put("streamSeq", segment.streamSeq())
                .put("text", segment.text())
                .put("occurredAt", segment.occurredAt().toString()));
        return result;
    }

    /**
     * Task Summary 只投影 lineage 与常量级 projection，不物化 Child Timeline。
     */
    public static ObjectNode task(ObjectMapper mapper, TaskModels.Summary value) {
        TaskModels.Lineage lineage = value.lineage();
        TaskModels.Projection projection = value.projection();
        ObjectNode result = mapper.createObjectNode().put("taskThreadId", lineage.taskThreadId())
                .put("parentThreadId", lineage.parentThreadId()).put("rootThreadId", lineage.rootThreadId())
                .put("taskName", lineage.taskName()).put("depth", lineage.depth())
                .put("taskKind", lineage.kind().name().toLowerCase(Locale.ROOT))
                .put("lifecycle", lineage.lifecycle().name().toLowerCase(Locale.ROOT))
                .put("state", projection.state().name().toLowerCase(Locale.ROOT))
                .put("revision", projection.revision())
                .put("latestActivitySequence", projection.latestActivitySequence())
                .put("unreadCount", projection.unreadCount())
                .put("descendantCount", projection.descendantCount())
                .put("runningDescendantCount", projection.runningDescendantCount())
                .put("needsAttentionCount", projection.needsAttentionCount())
                .put("updatedAt", projection.updatedAt().toString());
        nullable(result, "originTurnId", lineage.originTurnId());
        nullable(result, "latestSafeSummary", projection.latestSafeSummary());
        nullable(result, "startedAt", projection.startedAt());
        nullable(result, "completedAt", projection.completedAt());
        return result;
    }

    /**
     * Activity summary 直接转换不可变 JsonValue，且已由领域限制为安全低频投影。
     */
    public static ObjectNode taskActivity(ObjectMapper mapper, TaskModels.Activity value) {
        ObjectNode result = mapper.createObjectNode().put("activitySequence", value.sequence())
                .put("activityId", value.activityId()).put("rootThreadId", value.rootThreadId())
                .put("taskThreadId", value.taskThreadId())
                .put("actorThreadId", value.actorThreadId())
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("createdAt", value.createdAt().toString());
        nullable(result, "causalTurnId", value.causalTurnId());
        result.set("summary", JacksonJsonValues.toNode(mapper, value.summary()));
        return result;
    }

    /**
     * Mailbox 正文复用规范 UserContent 投影，幂等键刻意不回传给 UI。
     */
    public static ObjectNode taskMailbox(ObjectMapper mapper, TaskModels.MailboxMessage value) {
        ObjectNode result = mapper.createObjectNode().put("mailboxSequence", value.sequence())
                .put("messageId", value.messageId()).put("senderThreadId", value.senderThreadId())
                .put("targetThreadId", value.targetThreadId())
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("state", value.state().name().toLowerCase(Locale.ROOT))
                .put("createdAt", value.createdAt().toString())
                .put("updatedAt", value.updatedAt().toString());
        nullable(result, "causalTurnId", value.causalTurnId());
        nullable(result, "boundTurnId", value.boundTurnId());
        nullable(result, "consumedAt", value.consumedAt());
        result.set("content", userContent(mapper, value.content()));
        return result;
    }

    /**
     * Seed 只公开安全预览而不返回完整冻结 JSON；固定条目和 code-point 预算同时约束
     * WebView 物化成本，并阻断 Tool 参数、权限上限和隐藏推理越过 RPC 边界。
     */
    public static ObjectNode taskSeed(ObjectMapper mapper, TaskModels.ContextSeed value) {
        ObjectNode result = mapper.createObjectNode().put("contextSeedId", value.contextSeedId())
                .put("parentRevision", value.parentRevision())
                .put("inheritanceMode", value.inheritanceMode().name().toLowerCase(Locale.ROOT))
                .put("fingerprint", value.fingerprint()).put("createdAt", value.createdAt().toString());
        InitialContextSeed seed = value.initialContextSeed();
        if (value.inheritanceMode() == TaskModels.InheritanceMode.BRIEF_ONLY) {
            if (value.taskBrief() == null || seed != null) {
                throw new IllegalStateException("brief-only task seed has an invalid context projection");
            }
            result.set("taskBrief", userContent(mapper, value.taskBrief()));
            result.putNull("inheritedContextSummary");
            result.putArray("inheritedContextPreview");
        } else {
            if (value.taskBrief() != null || (seed != null
                                              && !seed.sourceIdentity().threadId().equals(value.parentThreadId()))) {
                throw new IllegalStateException("effective task seed has an invalid frozen context");
            }
            result.putNull("taskBrief");
            result.put("inheritedContextSummary", inheritedContextSummary(value.parentRevision(), seed));
            result.set("inheritedContextPreview", inheritedContextPreview(mapper, seed));
        }
        return result;
    }

    /**
     * 只展示 Core typed seed 中的用户/助手文本与受管附件引用；Tool、reasoning 和系统块留在模型历史边界内。
     */
    private static ArrayNode inheritedContextPreview(ObjectMapper mapper, InitialContextSeed seed) {
        ArrayNode result = mapper.createArrayNode();
        if (seed == null) {
            return result;
        }
        int remainingCodePoints = TASK_CONTEXT_PREVIEW_TOTAL_CODE_POINTS;
        for (ContextMessage message : seed.messages()) {
            if (result.size() >= TASK_CONTEXT_PREVIEW_ITEMS || remainingCodePoints == 0) {
                break;
            }
            String publicRole = publicContextRole(message.role());
            if (publicRole == null) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            ArrayList<String> attachmentIds = new ArrayList<>();
            int itemLimit = Math.min(TASK_CONTEXT_PREVIEW_ITEM_CODE_POINTS, remainingCodePoints);
            for (ContextMessage.Block block : message.blocks()) {
                if (block instanceof ContextMessage.TextBlock(String value)) {
                    appendPreviewText(text, value, itemLimit);
                } else if (block instanceof ContextMessage.AttachmentBlock(String attachmentId)
                           && attachmentIds.size() < TASK_CONTEXT_PREVIEW_ATTACHMENTS
                           && validAttachmentId(attachmentId)) {
                    attachmentIds.add(attachmentId);
                }
            }
            String previewText = text.length() == 0 ? null : text.toString();
            if (previewText == null && attachmentIds.isEmpty()) {
                continue;
            }
            ObjectNode item = result.addObject().put("role", publicRole);
            if (previewText == null) {
                item.putNull("text");
            }
            else {
                item.put("text", previewText);
                remainingCodePoints -= previewText.codePointCount(0, previewText.length());
            }
            ArrayNode attachments = item.putArray("attachmentIds");
            attachmentIds.forEach(attachments::add);
        }
        return result;
    }

    /**
     * Core 角色显式映射到现有 JA-RPC 预览闭集，避免把非对话事实降级成聊天正文。
     */
    private static String publicContextRole(ContextMessage.Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM, TOOL -> null;
        };
    }

    /**
     * 多块文本仅在预览预算内拼接，避免先复制完整的大段父历史再截断。
     */
    private static void appendPreviewText(StringBuilder target, String value, int maximumCodePoints) {
        int used = target.codePointCount(0, target.length());
        if (used >= maximumCodePoints) {
            return;
        }
        if (target.length() != 0) {
            target.append('\n');
            used++;
        }
        if (used < maximumCodePoints) {
            target.append(boundedContextText(value, maximumCodePoints - used));
        }
    }

    /**
     * Core 摘要事实进入原有摘要字段，不透传完整内部 DTO、退休事实或未受限 JSON。
     */
    private static String inheritedContextSummary(long parentRevision, InitialContextSeed seed) {
        if (seed == null) {
            return "父任务 revision " + parentRevision + " 没有可继承的上下文。";
        }
        StringBuilder result = new StringBuilder("继承自主任务 revision ").append(parentRevision);
        SummaryDocument summary = seed.summary().orElse(null);
        if (summary == null) {
            return result.toString();
        }
        result.append("\n冻结摘要：");
        int contentLimit = TASK_CONTEXT_SUMMARY_MAX_CHARS - TASK_CONTEXT_SUMMARY_TRUNCATION.length();
        boolean truncated = appendSummarySection(result, "目标", summary.goals(), contentLimit)
                            || appendSummarySection(result, "约束", summary.constraints(), contentLimit)
                            || appendSummarySection(result, "已完成", summary.completedProgress(), contentLimit)
                            || appendSummarySection(result, "正在进行", summary.currentProgress(), contentLimit)
                            || appendSummarySection(result, "阻塞", summary.blockers(), contentLimit)
                            || appendSummarySection(result, "决策", summary.decisions(), contentLimit)
                            || appendSummarySection(result, "下一步", summary.nextSteps(), contentLimit)
                            || appendSummarySection(result, "关键事实", summary.criticalFacts(), contentLimit)
                            || appendSummarySection(result, "文件", summary.files(), contentLimit)
                            || appendSummarySection(result, "未完成副作用或审批", summary.pendingEffects(), contentLimit);
        if (truncated) {
            result.append(TASK_CONTEXT_SUMMARY_TRUNCATION);
        }
        return result.toString();
    }

    /**
     * 逐分区渲染当前有效事实，摘要超出协议上限时由调用方追加明确截断提示。
     */
    private static boolean appendSummarySection(StringBuilder target, String heading,
                                                List<SummaryDocument.Fact> facts, int maximumChars) {
        if (facts.isEmpty()) {
            return false;
        }
        if (!appendBoundedSummaryText(target, "\n" + heading + "：\n", maximumChars)) {
            return true;
        }
        for (SummaryDocument.Fact fact : facts) {
            if (!appendBoundedSummaryText(target, "- " + fact.text() + "\n", maximumChars)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在 UTF-16 wire 字符上限内截断摘要，同时只在 Unicode code point 边界写入。
     */
    private static boolean appendBoundedSummaryText(StringBuilder target, String value, int maximumChars) {
        int remaining = maximumChars - target.length();
        if (value.length() <= remaining) {
            target.append(value);
            return true;
        }
        if (remaining <= 0) {
            return false;
        }
        int maximumCodePoints = Math.min(value.codePointCount(0, value.length()), remaining);
        int low = 0;
        int high = maximumCodePoints;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (value.offsetByCodePoints(0, middle) <= remaining) {
                low = middle;
            }
            else {
                high = middle - 1;
            }
        }
        if (low > 0) {
            target.append(value, 0, value.offsetByCodePoints(0, low));
        }
        return false;
    }

    /**
     * 附件引用只接受正式持久 ID，避免把畸形 block 的其它标识误投影为可访问资源。
     */
    private static boolean validAttachmentId(String value) {
        return value != null && value.matches("att_[A-Za-z0-9][A-Za-z0-9._-]{0,123}");
    }

    /**
     * 以 Unicode code point 截断而不是 UTF-16 char，并在预览上限处停止扫描；空文本归一为 null。
     */
    private static String boundedContextText(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty() || maximumCodePoints < 1) {
            return null;
        }
        int end = 0;
        for (int copied = 0; copied < maximumCodePoints && end < value.length(); copied++) {
            end += Character.charCount(value.codePointAt(end));
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    /**
     * 可空值统一显式写 null，防止 TS strict schema 区分 missing 后拒绝。
     */
    private static void nullable(ObjectNode target, String field, Object value) {
        if (value == null) {
            target.putNull(field);
        }
        else {
            target.put(field, value.toString());
        }
    }

    /**
     * 映射冻结 change set 摘要，精确 diff 只能通过受约束 reader 读取。
     */
    public static ObjectNode changeSet(ObjectMapper mapper, TurnChangeSet value) {
        ObjectNode result = mapper.createObjectNode()
                .put("state", value.state().name().toLowerCase(Locale.ROOT));
        ArrayNode reasons = result.putArray("incompleteReasons");
        value.incompleteReasons().stream().sorted().forEach(reason ->
                reasons.add(reason.name().toLowerCase(Locale.ROOT)));
        ArrayNode files = result.putArray("files");
        value.files().forEach(file -> fileChange(files.addObject(), file));
        TurnChangeSet.Stats stats = value.stats();
        result.putObject("stats").put("files", stats.files()).put("additions", stats.additions())
                .put("deletions", stats.deletions()).put("binaryFiles", stats.binaryFiles())
                .put("truncated", stats.truncated());
        if (value.artifactId() != null) {
            result.put("artifactId", value.artifactId());
        }
        return result;
    }

    /**
     * 将单个文本修改写入调用方提供的节点，使历史结果和活动预览共享完全相同的字段闭集。
     */
    public static void fileChange(ObjectNode target, TurnChangeSet.FileChange file) {
        target.put("path", file.path())
                .put("status", file.status().name().toLowerCase(Locale.ROOT))
                .put("additions", file.additions()).put("deletions", file.deletions())
                .put("binary", file.binary()).put("truncated", file.truncated());
    }

    /**
     * 映射键集游标，不用空字符串表示缺省值。
     */
    public static void cursor(ObjectNode result, String cursor) {
        if (cursor == null) {
            result.putNull("nextCursor");
        }
        else {
            result.put("nextCursor", cursor);
        }
    }

    /**
     * 协议层只接收已经由 runtime 校验过的公开片段，避免 Wire 反向依赖连接级缓存实现。
     *
     * @param kind 类型分类
     * @param segmentStartSeq 片段起点序号
     * @param streamSeq 临时流事件序号
     * @param text 文本内容
     * @param occurredAt 事件发生时间
     */
    public record LiveStreamSegment(String kind,
                                    long segmentStartSeq,
                                    long streamSeq,
                                    String text,
                                    Instant occurredAt) {
    }
}
