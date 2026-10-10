// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.service;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentCompactionRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentInternalRequest;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest.HistorySelection;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.codec.ReviewContextCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Ja 身份与配置选择放入宿主命名空间；路径、凭据和原生环境不进入 Core 请求。
 */
public final class JaTurnRequestMapper {
    /**
     * 无实例状态，避免把一次请求的数据保存在跨会话共享 Mapper 中。
     */
    private JaTurnRequestMapper() {
    }

    /**
     * Ja 单机应用使用显式本地用户；该宿主声明不是认证凭据，业务 Turn 身份保持原值。
     */
    public static AgentIdentity identity(String threadId, String turnId) {
        return AppServerIdentity.local().execution(threadId, turnId);
    }

    /**
     * 手动压缩派生不可见的内部 Turn identity，并冻结 Ja 偏好、路径 CAS 与受理时刻。
     */
    public static AgentCompactionRequest compaction(String threadId, String workspaceId,
                                                    ThreadPreferences preferences, String operationKey,
                                                    HistorySelection history, long expectedThreadRevision,
                                                    Instant acceptedAt) {
        Objects.requireNonNull(preferences, "preferences");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if (history.mode() != HistorySelection.Mode.EXPLICIT
            || !Objects.equals(history.expectedThreadRevision(), expectedThreadRevision)) {
            throw new IllegalArgumentException("manual compaction requires a matching explicit history revision");
        }
        Map<String, String> attributes = attributes(workspaceId, TurnOrigin.USER, preferences.providerId(),
                preferences.modelId(), preferences.reasoningLevel(), preferences.accessMode().name(),
                preferences.collaborationMode().name(), expectedThreadRevision, acceptedAt,
                Map.of("ja.method", "thread/compact"));
        AgentIdentity identity = AgentCompactionRequest.compactionIdentity(
                AppServerIdentity.local().execution(threadId, "compaction-seed"), operationKey);
        return new AgentCompactionRequest(identity, operationKey, Optional.empty(), attributes, history, acceptedAt);
    }

    /**
     * 公开请求保留 Schema、Review 快照身份和运行级覆盖事实；Child 冻结背景仍受 tenant 边界约束。
     */
    public static AgentRequest user(TurnStartRequest request, UserContent validatedContent, TurnOrigin origin,
                                    HistorySelection history, Map<String, String> admissionAttributes) {
        Objects.requireNonNull(request, "request");
        if (origin.internal()) {
            throw new IllegalArgumentException("user origin required");
        }
        if (request.initialContextSeed().isPresent() && origin != TurnOrigin.CHILD_TASK) {
            throw new IllegalArgumentException("initial context seed is only valid for child task turns");
        }
        LinkedHashMap<String, String> additions = new LinkedHashMap<>(admissionAttributes);
        request.reviewContext().ifPresent(context -> addReservedAttribute(additions,
                ReviewContextCodec.SNAPSHOT_ATTACHMENT_ID, context.snapshotAttachmentId()));
        if (request.runtimeOverridden()) {
            addReservedAttribute(additions, "ja.runtimeOverridden", "true");
        }
        Map<String, String> attributes = attributes(request.workspaceId(), origin, request.providerId(),
                request.modelId(), request.reasoningLevel(), request.accessMode().name(),
                request.collaborationMode().name(), request.expectedThreadRevision(), request.requestedAt(),
                additions);
        AgentRequest mapped = new AgentRequest(identity(request.threadId(), request.turnId()), validatedContent,
                operation(request.turnId(), attributes), Optional.empty(), attributes, history, request.requestedAt(),
                List.of());
        mapped = request.outputSchema().map(mapped::withOutputSchema).orElse(mapped);
        return request.initialContextSeed().map(mapped::withInitialContextSeed).orElse(mapped);
    }

    /**
     * Review 与运行级覆盖字段属于 Ja 运行时保留空间，不能由一般准入 metadata 改写。
     */
    private static void addReservedAttribute(Map<String, String> additions, String key, String value) {
        if (additions.putIfAbsent(key, Objects.requireNonNull(value, "attribute")) != null) {
            throw new IllegalArgumentException("reserved Ja admission attribute is already present");
        }
    }

    /**
     * 隐藏续跑使用具名指令，不能生成虚假的用户消息或把指令塞入普通属性。
     */
    public static AgentInternalRequest internal(InternalTurnStartRequest request, String instruction,
                                                HistorySelection history, Map<String, String> admissionAttributes) {
        Objects.requireNonNull(request, "request");
        Map<String, String> attributes = attributes(request.workspaceId(), request.origin(), request.providerId(),
                request.modelId(), request.reasoningLevel(), request.accessMode().name(),
                request.collaborationMode().name(), request.expectedThreadRevision(), request.requestedAt(),
                admissionAttributes);
        AgentIdentity identity = identity(request.threadId(), request.turnId());
        return new AgentInternalRequest(identity,
                new ContextInstruction("item_" + digest(identity.turnId()), instruction),
                operation(request.turnId(), attributes), Optional.empty(), attributes, history, request.requestedAt());
    }

    /**
     * 固定基础字段并拒绝业务附加字段覆盖，避免参与者和 Resolver 解释出不同来源。
     */
    private static Map<String, String> attributes(String workspaceId, TurnOrigin origin, String providerId,
                                                  String modelId, String reasoningLevel, String accessMode,
                                                  String collaborationMode, long revision, Instant acceptedAt,
                                                  Map<String, String> additions) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("ja.workspaceId", workspaceId);
        result.put("ja.origin", origin.name());
        result.put("ja.providerId", providerId);
        result.put("ja.modelId", modelId);
        if (reasoningLevel != null) {
            result.put("ja.reasoningLevel", reasoningLevel);
        }
        result.put("ja.accessMode", accessMode);
        result.put("ja.collaborationMode", collaborationMode);
        result.put("ja.expectedThreadRevision", Long.toString(revision));
        result.put("ja.acceptedAt", acceptedAt.toString());
        Objects.requireNonNull(additions, "admissionAttributes").forEach((key, value) -> {
            if (!key.startsWith("ja.") || result.putIfAbsent(key, Objects.requireNonNull(value, "attribute")) != null) {
                throw new IllegalArgumentException("invalid Ja admission attribute");
            }
        });
        return Map.copyOf(result);
    }

    /**
     * 外部重试沿用客户端提交身份，内部请求使用已经冻结的 Turn 身份。
     */
    private static String operation(String turnId, Map<String, String> attributes) {
        return attributes.getOrDefault("ja.operationKey", "ja-admit-" + turnId);
    }

    /**
     * 指令身份由执行身份确定，重试和进程恢复不会重新分配历史消息键。
     */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
