// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;

import java.util.Locale;

/**
 * 在不同 Handler 间共享无状态 Wire 投影规则。
 */
public final class RpcResults {
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
                .put("status", value.status().name().toLowerCase(Locale.ROOT)).put("revision", value.revision())
                .put("createdAt", value.createdAt().toString()).put("updatedAt", value.updatedAt().toString());
        if (value.preferences() == null) {
            result.putNull("preferences");
            return result;
        }
        ObjectNode preferences = result.putObject("preferences")
                .put("providerId", value.preferences().providerId())
                .put("modelId", value.preferences().modelId())
                .put("accessMode", value.preferences().accessMode().name().toLowerCase(Locale.ROOT))
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
        if (value.boundTurnId() == null) result.putNull("boundTurnId");
        else result.put("boundTurnId", value.boundTurnId());
        return result;
    }

    /**
     * 映射封闭的快照条目词汇表，不构造事件序列或重放记录。
     */
    public static ObjectNode snapshotItem(ObjectMapper mapper, ThreadSnapshot.Item item) {
        ObjectNode result = mapper.createObjectNode().put("itemId", item.itemId())
                .put("createdAt", item.createdAt().toString()).put("turnId", item.turnId());
        switch (item) {
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
            case ThreadSnapshot.AttachmentItem value -> result.put("kind", "attachment")
                    .put("attachmentId", value.attachmentId()).put("turnId", value.turnId())
                    .put("displayName", value.displayName()).put("sizeBytes", value.sizeBytes())
                    .put("mediaKind", value.mediaKind()).put("mediaType", value.mediaType())
                    .put("state", value.state());
        }
        return result;
    }

    /** 将最近一次 Provider Usage 映射为无请求正文、无凭据的恢复 DTO。 */
    public static ObjectNode contextUsage(ObjectMapper mapper, ThreadSnapshot.ContextUsage usage) {
        return mapper.createObjectNode().put("turnId", usage.turnId())
                .put("modelRound", usage.modelRound()).put("inputTokens", usage.inputTokens())
                .put("outputTokens", usage.outputTokens()).put("totalTokens", usage.totalTokens())
                .put("measuredAt", usage.measuredAt().toString());
    }

    /** 将冻结 Turn 映射为平坦历史 DTO；运行快照不含端点或凭据，失败只公开稳定错误码。 */
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
        if (turn.runtime() == null) {
            result.putNull("runtime");
        } else {
            var runtime = turn.runtime();
            ObjectNode runtimeNode = result.putObject("runtime")
                    .put("providerId", runtime.providerId()).put("modelId", runtime.modelId())
                    .put("provider", runtime.provider()).put("api", runtime.api())
                    .put("upstreamModel", runtime.upstreamModel())
                    .put("accessMode", runtime.accessMode().name().toLowerCase(Locale.ROOT))
                    .put("configGeneration", runtime.configGeneration());
            if (runtime.reasoningLevel() == null) runtimeNode.putNull("reasoningLevel");
            else runtimeNode.put("reasoningLevel", runtime.reasoningLevel());
        }
        return result;
    }

    /** 映射冻结 change set 摘要，精确 diff 只能通过受约束 reader 读取。 */
    public static ObjectNode changeSet(ObjectMapper mapper, TurnChangeSet value) {
        ObjectNode result = mapper.createObjectNode()
                .put("state", value.state().name().toLowerCase(Locale.ROOT));
        if (value.reason() != null) result.put("reason", value.reason());
        ArrayNode files = result.putArray("files");
        value.files().forEach(file -> {
            ObjectNode item = files.addObject().put("path", file.path())
                    .put("status", file.status().name().toLowerCase(Locale.ROOT))
                    .put("binary", file.binary()).put("truncated", file.truncated());
            if (file.oldPath() != null) item.put("oldPath", file.oldPath());
            if (file.additions() != null) item.put("additions", file.additions());
            if (file.deletions() != null) item.put("deletions", file.deletions());
        });
        var stats = value.stats();
        result.putObject("stats").put("files", stats.files()).put("additions", stats.additions())
                .put("deletions", stats.deletions()).put("binaryFiles", stats.binaryFiles())
                .put("truncated", stats.truncated());
        if (value.artifactId() != null) result.put("artifactId", value.artifactId());
        return result;
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
