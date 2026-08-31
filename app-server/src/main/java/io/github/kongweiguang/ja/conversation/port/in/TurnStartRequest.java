// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;

/**
 * 入站 Turn 启动意图；不包含 Provider、配置 Adapter 或已解析 Tool 等出站对象。
 */
public record TurnStartRequest(
        String threadId,
        String turnId,
        String workspaceId,
        Path workspaceRoot,
        String input,
        List<String> attachmentIds,
        String providerId,
        String modelId,
        String reasoningLevel,
        AccessMode accessMode,
        Duration deadline,
        long expectedThreadRevision,
        long initialTurnMutationVersion,
        Instant requestedAt) {

    /**
     * 在解析运行时前固定身份、路径和上限，避免出站 Resolver 接收未经约束的 Wire DTO。
     */
    public TurnStartRequest {
        threadId = identifier(threadId, "thr_", "threadId");
        turnId = identifier(turnId, "turn_", "turnId");
        workspaceId = identifier(workspaceId, "ws_", "workspaceId");
        workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        input = boundedText(input == null ? "" : input, "input", 4_000_000, true);
        attachmentIds = attachmentIds(attachmentIds);
        if (input.isBlank() && attachmentIds.isEmpty()) {
            throw new IllegalArgumentException("turn content must not be empty");
        }
        providerId = identifier(providerId, "provider_", "providerId");
        modelId = identifier(modelId, "model_", "modelId");
        if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
            throw new IllegalArgumentException("invalid reasoningLevel");
        }
        Objects.requireNonNull(accessMode, "accessMode");
        Objects.requireNonNull(deadline, "deadline");
        long deadlineMillis = deadline.toMillis();
        if (deadlineMillis < 1_000 || deadlineMillis > 86_400_000
            || deadlineMillis != deadline.toNanos() / 1_000_000) {
            throw new IllegalArgumentException("deadline is outside supported bounds");
        }
        if (expectedThreadRevision < 0 || initialTurnMutationVersion < 0) {
            throw new IllegalArgumentException("turn revisions must be non-negative");
        }
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** 返回独立附件 ID 快照，避免入站请求在准入后被调用方观察为可变集合。 */
    @Override
    public List<String> attachmentIds() {
        return List.copyOf(attachmentIds);
    }

    /**
     * 仅接受 JA-RPC v2 的稳定标识词汇，不提供旧前缀或别名。
     */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 限制非可信输入且不在异常中回显内容。
     */
    private static String boundedText(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || value.indexOf('\0') >= 0
            || (!allowEmpty && value.isBlank())) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** 冻结最多十个唯一受管附件 ID；具体 Workspace 与生命周期由 admission 事务校验。 */
    private static List<String> attachmentIds(List<String> values) {
        List<String> result = List.copyOf(Objects.requireNonNull(values, "attachmentIds"));
        if (result.size() > 10 || new HashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException("invalid attachmentIds");
        }
        for (String value : result) identifier(value, "att_", "attachmentId");
        return result;
    }
}
