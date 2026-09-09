// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.task.domain.TaskModels;

import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

/**
 * 在不同 Handler 间共享无状态 Wire 投影规则。
 */
public final class RpcResults {
    private static final int TASK_CONTEXT_PREVIEW_ITEMS = 24;
    private static final int TASK_CONTEXT_PREVIEW_ITEM_CODE_POINTS = 512;
    private static final int TASK_CONTEXT_PREVIEW_TOTAL_CODE_POINTS = 4_096;
    private static final int TASK_CONTEXT_PREVIEW_ATTACHMENTS = 10;
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
                .put("trust", value.trust().name().toLowerCase(Locale.ROOT)).put("revision", value.revision());
    }

    /**
     * 映射 Thread 投影与下一轮偏好，不把 Provider 端点或凭据带入 Wire。
     */
    public static ObjectNode thread(ObjectMapper mapper, ThreadSummary value) {
        ObjectNode result = mapper.createObjectNode().put("threadId", value.threadId())
                .put("workspaceId", value.workspaceId()).put("title", value.title())
                .put("status", value.status().name().toLowerCase(Locale.ROOT)).put("pinned", value.pinned())
                .put("latestTurnSeen", value.latestTurnSeen())
                .put("revision", value.revision())
                .put("createdAt", value.createdAt().toString()).put("updatedAt", value.updatedAt().toString());
        if (value.latestTurnStatus() == null) result.putNull("latestTurnStatus");
        else result.put("latestTurnStatus", value.latestTurnStatus().name().toLowerCase(Locale.ROOT));
        if (value.activeGoalId() == null) result.putNull("activeGoalId");
        else result.put("activeGoalId", value.activeGoalId());
        ObjectNode preferences = result.putObject("preferences")
                .put("providerId", value.preferences().providerId())
                .put("modelId", value.preferences().modelId())
                .put("accessMode", value.preferences().accessMode().name().toLowerCase(Locale.ROOT))
                .put("collaborationMode", value.preferences().collaborationMode().name().toLowerCase(Locale.ROOT))
                .put("titleSource", value.preferences().titleSource().name().toLowerCase(Locale.ROOT));
        if (value.preferences().reasoningLevel() == null) preferences.putNull("reasoningLevel");
        else preferences.put("reasoningLevel", value.preferences().reasoningLevel());
        return result;
    }

    /** 投影附件公开元数据；内容 hash、ingress token 与物理路径永不进入 Wire。 */
    public static ObjectNode attachment(ObjectMapper mapper, AttachmentMetadata value) {
        ObjectNode result = mapper.createObjectNode().put("attachmentId", value.attachmentId())
                .put("workspaceId", value.workspaceId()).put("displayName", value.displayName())
                .put("sizeBytes", value.sizeBytes())
                .put("mediaKind", value.mediaKind().name().toLowerCase(Locale.ROOT))
                .put("mediaType", value.mediaType())
                .put("state", value.status().name().toLowerCase(Locale.ROOT))
                .put("createdAt", value.createdAt().toString())
                .put("expiresAt", value.expiresAt().toString());
        if (value.boundMessageId() == null) result.putNull("boundMessageId");
        else result.put("boundMessageId", value.boundMessageId());
        return result;
    }

    /** 映射队列全量投影；数组顺序就是 App Server 已提交的真实消费顺序。 */
    public static ObjectNode inputQueue(ObjectMapper mapper, InputQueue value) {
        ObjectNode result = mapper.createObjectNode().put("turnId", value.turnId())
                .put("revision", value.revision()).put("accepting", value.accepting());
        ArrayNode items = result.putArray("items");
        value.items().forEach(item -> items.add(queuedInput(mapper, item)));
        return result;
    }

    /** 映射单条待处理输入，不暴露内部 sequence 或状态列。 */
    public static ObjectNode queuedInput(ObjectMapper mapper, InputQueue.QueuedInput value) {
        ObjectNode result = mapper.createObjectNode().put("inputId", value.inputId())
                .put("turnId", value.turnId()).put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("status", value.status().name().toLowerCase(Locale.ROOT))
                .put("inputRevision", value.inputRevision()).put("createdAt", value.createdAt().toString());
        result.set("content", userContent(mapper, value.content()));
        result.set("attachments", attachmentSummaries(mapper, value.attachments()));
        if (value.issue() == null) result.putNull("issue");
        else result.putObject("issue").put("errorCode", value.issue().errorCode())
                .put("message", value.issue().message()).put("retryable", value.issue().retryable());
        return result;
    }

    /** 单一内容投影供 turn、队列、事件和 thread/read 复用，避免四处维护判别联合。 */
    public static ArrayNode userContent(ObjectMapper mapper, UserContent content) {
        ArrayNode result = mapper.createArrayNode();
        for (UserContentBlock block : content.blocks()) {
            ObjectNode item = result.addObject();
            if (block instanceof TextContent text) {
                item.put("type", "text").put("text", text.text());
            } else if (block instanceof AttachmentContent attachment) {
                item.put("type", "attachment").put("attachmentId", attachment.attachmentId());
            } else if (block instanceof WorkspaceReferenceContent reference) {
                item.put("type", "workspace_reference").put("workspaceId", reference.workspaceId())
                        .put("relativePath", reference.relativePath())
                        .put("kind", reference.kind().name().toLowerCase(Locale.ROOT));
            } else if (block instanceof SkillReferenceContent reference) {
                item.put("type", "skill_reference").put("skillId", reference.skillId());
            }
        }
        return result;
    }

    /**
     * 映射封闭的快照条目词汇表，不构造事件序列或重放记录。
     */
    public static ObjectNode snapshotItem(ObjectMapper mapper, ThreadSnapshot.Item item) {
        ObjectNode result = mapper.createObjectNode().put("itemId", item.itemId())
                .put("createdAt", item.createdAt().toString()).put("turnId", item.turnId());
        switch (item) {
            case ThreadSnapshot.UserInputItem value -> {
                result.put("kind", "user_input");
                result.set("content", userContent(mapper, value.content()));
                result.set("attachments", attachmentSummaries(mapper, value.attachments()));
            }
            case ThreadSnapshot.TextItem value -> {
                result.put("kind", value.kind().name().toLowerCase(Locale.ROOT));
                result.put("text", value.text());
                if (value.modelRound() != null) result.put("modelRound", value.modelRound());
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
                if (value.decision() == null) result.putNull("decision");
                else result.put("decision", value.decision().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** 附件摘要只随所属 USER Message 或队列项投影，数组顺序必须与 content block 一致。 */
    static ArrayNode attachmentSummaries(ObjectMapper mapper,
            List<AttachmentSummary> values) {
        ArrayNode result = mapper.createArrayNode();
        values.forEach(value -> result.addObject().put("attachmentId", value.attachmentId())
                .put("displayName", value.displayName()).put("sizeBytes", value.sizeBytes())
                .put("mediaKind", value.mediaKind()).put("mediaType", value.mediaType()));
        return result;
    }

    /** 将最近一次 Provider Usage 映射为无请求正文、无凭据的恢复 DTO。 */
    public static ObjectNode contextUsage(ObjectMapper mapper, ThreadSnapshot.ContextUsage usage) {
        return requestUsage(mapper, usage.request(), usage.measuredAt()).put("turnId", usage.turnId());
    }

    /** 请求级 Usage 在事件与 thread/read 中共享唯一判别联合，UNKNOWN 仅保留真实的计量不可得语义。 */
    static ObjectNode requestUsage(ObjectMapper mapper, ProviderRequestUsage value, java.time.Instant measuredAt) {
        ObjectNode result = mapper.createObjectNode().put("requestId", value.requestId())
                .put("requestOrdinal", value.requestOrdinal()).put("modelRound", value.modelRound())
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

    /** Profile 只投影无密钥请求事实；nullable reasoning 必须显式保留。 */
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

    /** Turn 历史只公开 Operation 生命周期；模型身份统一由请求级 Usage Profile 提供。 */
    public static ObjectNode snapshotTurn(ObjectMapper mapper, ThreadSnapshot.Turn turn) {
        ObjectNode result = mapper.createObjectNode().put("turnId", turn.turnId())
                .put("status", turn.status()).put("requestedAt", turn.requestedAt().toString())
                .put("updatedAt", turn.updatedAt().toString());
        if (turn.completedAt() == null) result.putNull("completedAt");
        else result.put("completedAt", turn.completedAt().toString());
        if (turn.errorCode() == null) result.putNull("errorCode");
        else result.put("errorCode", turn.errorCode());
        if (turn.changeSet() == null) result.putNull("changeSet");
        else result.set("changeSet", changeSet(mapper, turn.changeSet()));
        return result;
    }

    /** Task Summary 只投影 lineage 与常量级 projection，不物化 Child Timeline。 */
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

    /** Activity summary 直接转换不可变 JsonValue，且已由领域限制为安全低频投影。 */
    public static ObjectNode taskActivity(ObjectMapper mapper, TaskModels.Activity value) {
        ObjectNode result = mapper.createObjectNode().put("activitySequence", value.sequence())
                .put("activityId", value.activityId()).put("taskThreadId", value.taskThreadId())
                .put("actorThreadId", value.actorThreadId())
                .put("kind", value.kind().name().toLowerCase(Locale.ROOT))
                .put("createdAt", value.createdAt().toString());
        nullable(result, "causalTurnId", value.causalTurnId());
        result.set("summary", JacksonJsonValues.toNode(mapper, value.summary()));
        return result;
    }

    /** Mailbox 正文复用规范 UserContent 投影，幂等键刻意不回传给 UI。 */
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
        result.set("taskBrief", userContent(mapper, value.taskBrief()));
        if (value.effectiveContext() == null) {
            result.putNull("inheritedContextSummary");
            result.putArray("inheritedContextPreview");
        } else {
            result.put("inheritedContextSummary", "继承自主任务 revision " + value.parentRevision());
            result.set("inheritedContextPreview", inheritedContextPreview(mapper, value.effectiveContext()));
        }
        return result;
    }

    /**
     * 只遍历仓储冻结的 messages 判别结构；任何未知角色、未知 block 或畸形成员均跳过，
     * 不能通过宽松字符串化把内部 JSON 带到用户可见详情。
     */
    private static ArrayNode inheritedContextPreview(ObjectMapper mapper, JsonObject effectiveContext) {
        ArrayNode result = mapper.createArrayNode();
        if (!(effectiveContext.get("messages") instanceof JsonArray messages)) return result;
        int remainingCodePoints = TASK_CONTEXT_PREVIEW_TOTAL_CODE_POINTS;
        for (JsonValue candidate : messages.values()) {
            if (result.size() >= TASK_CONTEXT_PREVIEW_ITEMS || remainingCodePoints == 0) break;
            if (!(candidate instanceof JsonObject message)
                    || !(message.get("role") instanceof JsonText role)
                    || !(message.get("blocks") instanceof JsonArray blocks)) continue;
            String publicRole = publicContextRole(role.value());
            if (publicRole == null) continue;
            ArrayList<String> textParts = new ArrayList<>();
            ArrayList<String> attachmentIds = new ArrayList<>();
            for (JsonValue rawBlock : blocks.values()) {
                if (!(rawBlock instanceof JsonObject block)
                        || !(block.get("kind") instanceof JsonText kind)) continue;
                if ("text".equals(kind.value()) && block.get("text") instanceof JsonText text) {
                    textParts.add(text.value());
                } else if ("attachment".equals(kind.value())
                        && attachmentIds.size() < TASK_CONTEXT_PREVIEW_ATTACHMENTS
                        && block.get("attachmentId") instanceof JsonText attachmentId
                        && validAttachmentId(attachmentId.value())) {
                    attachmentIds.add(attachmentId.value());
                }
            }
            String text = boundedContextText(String.join("\n", textParts),
                    Math.min(TASK_CONTEXT_PREVIEW_ITEM_CODE_POINTS, remainingCodePoints));
            if (text == null && attachmentIds.isEmpty()) continue;
            ObjectNode item = result.addObject().put("role", publicRole);
            if (text == null) item.putNull("text");
            else {
                item.put("text", text);
                remainingCodePoints -= text.codePointCount(0, text.length());
            }
            ArrayNode attachments = item.putArray("attachmentIds");
            attachmentIds.forEach(attachments::add);
        }
        return result;
    }

    /** 仅 USER/ASSISTANT 是产品可见历史；System、Tool 与未知角色没有降级映射。 */
    private static String publicContextRole(String role) {
        return switch (role) {
            case "USER" -> "user";
            case "ASSISTANT" -> "assistant";
            default -> null;
        };
    }

    /** 附件引用只接受正式持久 ID，避免把畸形 block 的其它标识误投影为可访问资源。 */
    private static boolean validAttachmentId(String value) {
        return value != null && value.matches("att_[A-Za-z0-9][A-Za-z0-9._-]{0,123}");
    }

    /**
     * 以 Unicode code point 截断而不是 UTF-16 char，保证 emoji 等代理对不会被切成非法文本；
     * 空文本归一为 null，使客户端可准确判断条目是否仅含附件。
     */
    private static String boundedContextText(String value, int maximumCodePoints) {
        if (value == null || value.isEmpty() || maximumCodePoints < 1) return null;
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumCodePoints) return value;
        return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
    }

    /** 可空值统一显式写 null，防止 TS strict schema 区分 missing 后拒绝。 */
    private static void nullable(ObjectNode target, String field, Object value) {
        if (value == null) target.putNull(field);
        else target.put(field, value.toString());
    }

    /** 映射冻结 change set 摘要，精确 diff 只能通过受约束 reader 读取。 */
    public static ObjectNode changeSet(ObjectMapper mapper, TurnChangeSet value) {
        ObjectNode result = mapper.createObjectNode()
                .put("state", value.state().name().toLowerCase(Locale.ROOT));
        ArrayNode reasons = result.putArray("incompleteReasons");
        value.incompleteReasons().stream().sorted().forEach(reason ->
                reasons.add(reason.name().toLowerCase(Locale.ROOT)));
        ArrayNode files = result.putArray("files");
        value.files().forEach(file -> fileChange(files.addObject(), file));
        var stats = value.stats();
        result.putObject("stats").put("files", stats.files()).put("additions", stats.additions())
                .put("deletions", stats.deletions()).put("binaryFiles", stats.binaryFiles())
                .put("truncated", stats.truncated());
        if (value.artifactId() != null) result.put("artifactId", value.artifactId());
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
        if (cursor == null) result.putNull("nextCursor");
        else result.put("nextCursor", cursor);
    }

    /**
     * 使用持有全部子 ObjectNode 的 Mapper 构造数组。
     */
    public static ArrayNode array(ObjectMapper mapper) {
        return mapper.createArrayNode();
    }
}
