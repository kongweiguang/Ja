// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 表示一次事务读取获得的 Thread 元数据与混合历史项页面。
 */
public record ThreadSnapshot(ThreadSummary thread, List<Turn> turns, List<Item> items,
                             ContextUsage contextUsage, InputQueue inputQueue, String nextCursor) {
    /**
     * 深层集合以不可变副本发布，保证事务结束后结果不会漂移。
     */
    public ThreadSnapshot {
        Objects.requireNonNull(thread, "thread");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /** Turn 元数据只表达 Operation 生命周期；模型事实必须从请求级 Usage Profile 读取。 */
    public record Turn(String turnId, String status,
                       Instant requestedAt, Instant updatedAt, Instant completedAt, String errorCode,
                       TurnChangeSet changeSet) {
        /** 历史只公开稳定错误码，错误正文和 Provider 私有续传状态仍留在服务端。 */
        public Turn {
            if (turnId == null || !turnId.matches("turn_[A-Za-z0-9][A-Za-z0-9._-]*")
                || turnId.length() > 128) throw new IllegalArgumentException("invalid turnId");
            Objects.requireNonNull(status, "status");
            // v1 历史 Turn 未记录完整运行事实；null 比伪造 Provider/API 更诚实。
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("invalid errorCode");
            }
        }
    }

    /**
     * 最近一次已提交模型轮次的 Provider Usage；它是恢复上下文指示器的权威事实，不是字符估算。
     */
    public record ContextUsage(String turnId, ProviderRequestUsage request, Instant measuredAt) {
        /**
         * Usage 必须能关联到快照中的真实 Turn，且总量不得小于输入与输出之和。
         */
        public ContextUsage {
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(measuredAt, "measuredAt");
        }
    }

    /**
     * 历史项闭集只包含当前公开合同允许回放的持久化事实。
     */
    public sealed interface Item permits UserInputItem, ThreadMessageItem, TextItem, ToolItem, ApprovalItem {
        /**
         * 返回追加写入时生成的不透明条目身份。
         */
        String itemId();

        /**
         * 返回条目完成持久化的时间。
         */
        Instant createdAt();

        /** 每个平坦历史项显式携带所属 Turn，禁止客户端按 Thread 合并推断。 */
        String turnId();
    }

    /** 用户输入保留四类结构化 block；Renderer 不再从纯文本和附件旁表反推提交内容。 */
    public record UserInputItem(String itemId, Instant createdAt, String turnId,
                                UserContent content, List<AttachmentSummary> attachments) implements Item {
        /** 消息身份、Turn 关联和规范 content 必须来自同一 SQLite 快照。 */
        public UserInputItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(content, "content");
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
            if (!content.attachmentIds().equals(attachments.stream()
                    .map(AttachmentSummary::attachmentId).toList())) {
                throw new IllegalArgumentException("attachment summaries do not match content order");
            }
        }
    }

    /**
     * 跨会话消息的独立可见投影；来源快照与正文分开保存，避免把传入消息伪装成当前用户输入。
     */
    public record ThreadMessageItem(String itemId, Instant createdAt, String turnId,
                                    String sourceThreadId, String sourceTitle, String content) implements Item {
        /**
         * 消息必须保留发送时的 Thread 标识和标题快照；正文允许为空以覆盖纯资源消息。
         */
        public ThreadMessageItem {
            requireIdentifier(itemId, "item_", "itemId");
            Objects.requireNonNull(createdAt, "createdAt");
            requireIdentifier(turnId, "turn_", "turnId");
            requireIdentifier(sourceThreadId, "thr_", "sourceThreadId");
            sourceTitle = boundedText(sourceTitle, "sourceTitle", 512, false);
            content = boundedText(content, "content", 1_048_576, true);
        }
    }

    /**
     * 表示用户输入或助手可见文本，不包含 Provider 私有推理。
     */
    public record TextItem(String itemId, Instant createdAt, String turnId,
                           TextKind kind, String text, Integer modelRound) implements Item {
        /**
         * 保留已经提交的可见文本，并拒绝缺失的类型与时间。
         */
        public TextItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if (modelRound != null && (modelRound < 1 || modelRound > 128)) {
                throw new IllegalArgumentException("invalid modelRound");
            }
            if ((kind == TextKind.ASSISTANT_PROGRESS || kind == TextKind.REASONING_SUMMARY)
                != (modelRound != null)) {
                throw new IllegalArgumentException("message kind and modelRound must be paired");
            }
        }
    }

    /**
     * 表示一个 Tool 的最新安全展示事实；历史恢复按 callId 只返回这一项，避免把调用和结果重复显示。
     */
    public record ToolItem(String itemId, Instant createdAt, String turnId, ToolKind kind, String callId,
                           String toolName, ToolPresentation presentation, int ordinal) implements Item {
        /**
         * 拒绝不完整 Tool 事实，并保持调用顺序为非负整数。
         */
        public ToolItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(presentation, "presentation");
            if (ordinal < 0) throw new IllegalArgumentException("invalid tool ordinal");
        }
    }

    /**
     * 表示持久化的审批请求及其可选决策。
     */
    public record ApprovalItem(String itemId, Instant createdAt, String approvalId, String turnId,
                               String callId, String toolName, String reason,
                               String decision, Instant expiresAt) implements Item {
        /**
         * 保留审批关联与过期时间，decision 为空表示尚未解决。
         */
        public ApprovalItem {
            requireItem(itemId, createdAt);
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(callId, "callId");
            Objects.requireNonNull(toolName, "toolName");
            if (reason == null || reason.isBlank() || reason.length() > 2_048) {
                throw new IllegalArgumentException("invalid approval reason");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /**
     * 可见文本类型与 Wire 名称解耦。
     */
    public enum TextKind {
        /**
         * 模型提交的最终可见回复。
         */
        ASSISTANT_PROGRESS,
        /** Provider 明确返回的公开 reasoning summary，不是隐藏推理正文。 */
        REASONING_SUMMARY,
        /** 由 Provider STOP 产生的独立答复；既包括终态回复，也包括消费下一条排队输入前的结算。 */
        FINAL_ANSWER
    }

    /** Tool 历史只保留一个规范调用项，最终状态由 ToolPresentation 表达。 */
    public enum ToolKind {
        /** 已准备并提交、随后可原位更新到终态的 Tool 调用。 */
        TOOL_CALL
    }

    /**
     * 集中校验所有历史项共享的身份和时间约束。
     */
    private static void requireItem(String itemId, Instant createdAt) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 对历史 opaque identity 复用 Wire 前缀约束，且异常不回显持久化原值。 */
    private static void requireIdentifier(String value, String prefix, String field) {
        if (value == null || !value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }

    /**
     * 在历史投影边界限制来源标题和消息正文，防止损坏或无界 Mailbox 数据进入快照。
     */
    private static String boundedText(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || value.indexOf('\0') >= 0
                || (!allowEmpty && value.isBlank())) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
