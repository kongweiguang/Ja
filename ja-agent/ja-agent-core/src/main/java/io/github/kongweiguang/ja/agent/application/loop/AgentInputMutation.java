// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.conversation.AgentInputQueue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 队列变更意图不携带存储分配的队列修订，避免调用方伪造重放顺序。
 *
 * @param action 动作
 * @param inputId 输入标识
 * @param expectedInputRevision 预期输入修订版本
 * @param kind 类型分类
 * @param body 正文（可选）
 * @param issue 问题
 * @param hostMetadata 宿主元数据
 */
public record AgentInputMutation(AgentInputQueue.Action action,
                                 String inputId,
                                 long expectedInputRevision,
                                 AgentInputQueue.Kind kind,
                                 Optional<UserContent> body,
                                 AgentInputQueue.Issue issue,
                                 Map<String, String> hostMetadata) {
    /**
     * 输入正文仅对入队和更新必填，修订号由读取当前队列的 Store service 分配。
     */
    public AgentInputMutation {
        Objects.requireNonNull(action, "action");
        if (inputId == null || inputId.isBlank()) {
            throw new IllegalArgumentException("inputId is required");
        }
        if (expectedInputRevision < 0) {
            throw new IllegalArgumentException("negative input revision");
        }
        body = Objects.requireNonNull(body, "body");
        hostMetadata = immutableHostMetadata(hostMetadata);
        boolean writesBody = action == AgentInputQueue.Action.ENQUEUE || action == AgentInputQueue.Action.UPDATE;
        if (writesBody != body.isPresent()) {
            throw new IllegalArgumentException("input body does not match action");
        }
        if ((action == AgentInputQueue.Action.ENQUEUE) == (kind == null)) {
            throw new IllegalArgumentException("input kind does not match action");
        }
        if ((action == AgentInputQueue.Action.NEEDS_ATTENTION) == (issue == null)) {
            throw new IllegalArgumentException("input issue does not match action");
        }
        if (action == AgentInputQueue.Action.ENQUEUE && expectedInputRevision != 0) {
            throw new IllegalArgumentException("new input revision must be zero");
        }
        if (action != AgentInputQueue.Action.ENQUEUE && expectedInputRevision == 0) {
            throw new IllegalArgumentException("existing input revision is required");
        }
    }

    /**
     * 冻结宿主幂等关联字段，使同一控制提交可由业务事务参与者投影回执。
     */
    private static Map<String, String> immutableHostMetadata(Map<String, String> values) {
        Map<String, String> copy = Map.copyOf(Objects.requireNonNull(values, "hostMetadata"));
        if (copy.size() > 32 || copy.entrySet().stream().anyMatch(entry ->
                !entry.getKey().matches("[A-Za-z][A-Za-z0-9._-]{0,127}")
                || entry.getValue().length() > 16_384
                || entry.getValue().chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("invalid host metadata");
        }
        return copy;
    }
}
