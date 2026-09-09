// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Turn 待处理输入的权威快照；items 的顺序就是下一次安全点的真实消费顺序。
 */
public record InputQueue(String turnId, long revision, boolean accepting, List<QueuedInput> items) {
    /** 队列投影必须深冻结，避免事务结束后调用方改变已发布顺序。 */
    public InputQueue {
        turnId = identifier(turnId, "turn_", "turnId");
        if (revision < 0) throw new IllegalArgumentException("invalid input queue revision");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        String owningTurnId = turnId;
        if (items.size() > 8 || items.stream().anyMatch(item -> !owningTurnId.equals(item.turnId()))) {
            throw new IllegalArgumentException("invalid input queue items");
        }
    }

    /** 一条尚未消费的输入；条目 revision 只由有效编辑或优先级变更推进。 */
    public record QueuedInput(String inputId, String turnId, UserContent content, Kind kind,
                              List<AttachmentSummary> attachments,
                              Status status, Issue issue, long inputRevision, Instant createdAt) {
        /**
         * 内容使用与首轮相同的结构化值；needs_attention 必须携带稳定问题，普通待消费项不得
         * 留下陈旧错误，否则 FIFO 是否可以继续会在客户端和 App Server 间产生分歧。
         */
        public QueuedInput {
            inputId = identifier(inputId, "input_", "inputId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(kind, "kind");
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
            if (!content.attachmentIds().equals(attachments.stream()
                    .map(AttachmentSummary::attachmentId).toList())) {
                throw new IllegalArgumentException("queued attachment summaries do not match content order");
            }
            Objects.requireNonNull(status, "status");
            if ((status == Status.NEEDS_ATTENTION) != (issue != null)) {
                throw new IllegalArgumentException("queued input status and issue must be paired");
            }
            if (inputRevision < 1) throw new IllegalArgumentException("invalid input revision");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** Wire 只保留当前设计的两种消费语义，不提供旧模式别名。 */
    public enum Kind {
        /** 当前模型及整批 Tool 完成后的下一个安全点优先消费。 */
        STEERING,
        /** 没有 Steering 时在 STOP 边界按原始入队顺序消费。 */
        FOLLOW_UP
    }

    /** FIFO head 只区分可消费与需要用户修复两态，数据库解决状态不泄漏到公开投影。 */
    public enum Status {
        /** 引用当前有效，可在到达消费边界时再次校验。 */
        PENDING,
        /** 消费期校验失败，保持原 FIFO 位置等待用户编辑、删除或重试。 */
        NEEDS_ATTENTION
    }

    /** 队列修复提示不携带内部异常、路径或 RPC errorId。 */
    public record Issue(String errorCode, String message, boolean retryable) {
        /** 只接受本能力定义的稳定错误闭集，避免 UI 被任意存储文本驱动。 */
        public Issue {
            if (errorCode == null || !errorCode.matches(
                    "WORKSPACE_REFERENCE_INVALID|SKILL_UNAVAILABLE|SKILL_LOAD_FAILED|CONTENT_TOO_LARGE|ATTACHMENT_UNAVAILABLE")) {
                throw new IllegalArgumentException("invalid queued input issue code");
            }
            if (message == null || message.isBlank() || message.length() > 512
                    || message.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid queued input issue message");
            }
        }
    }

    /** 集中校验 opaque identity，异常不得回显持久化原值。 */
    private static String identifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
