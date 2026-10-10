// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.agent.domain.UserContent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 表示一次事务读取获得的 Thread 元数据与混合历史项页面。
 *
 * @param thread 对话
 * @param turns 执行轮次列表
 * @param items 条目列表
 * @param contextUsage 上下文用量
 * @param inputQueue 输入队列
 * @param nextCursor 读取下一页时使用的游标
 */
public record ThreadSnapshot(ThreadSummary thread,
                             List<Turn> turns,
                             List<Item> items,
                             ContextUsage contextUsage,
                             InputQueue inputQueue,
                             String nextCursor) {
    public static final int TEXT_PREVIEW_CODE_POINTS = 512;

    /**
     * 深层集合以不可变副本发布，保证事务结束后结果不会漂移。
     */
    public ThreadSnapshot {
        Objects.requireNonNull(thread, "thread");
        turns = List.copyOf(Objects.requireNonNull(turns, "turns"));
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }

    /**
     * 集中校验所有历史项共享的身份和时间约束。
     */
    private static void requireItem(String itemId, Instant createdAt) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /**
     * 对历史 opaque identity 复用 Wire 前缀约束，且异常不回显持久化原值。
     */
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

    /**
     * 截短历史正文时按 Unicode code point 保留边界，保证 snapshot 页大小不随完整回复增长。
     */
    public static String textPreview(String value) {
        String text = Objects.requireNonNull(value, "text");
        int codePoints = text.codePointCount(0, text.length());
        int end = codePoints <= TEXT_PREVIEW_CODE_POINTS
                ? text.length() : text.offsetByCodePoints(0, TEXT_PREVIEW_CODE_POINTS);
        return text.substring(0, end);
    }

    /**
     * 可见文本类型与 Wire 名称解耦。
     */
    public enum TextKind {
        /**
         * 模型提交的最终可见回复。
         */
        ASSISTANT_PROGRESS,
        /**
         * Provider 明确返回的公开 reasoning summary，不是隐藏推理正文。
         */
        REASONING_SUMMARY,
        /**
         * 由 Provider STOP 产生的独立答复；既包括终态回复，也包括消费下一条排队输入前的结算。
         */
        FINAL_ANSWER
    }

    /**
     * Tool 历史只保留一个规范调用项，最终状态由 ToolPresentation 表达。
     */
    public enum ToolKind {
        /**
         * 已准备并提交、随后可原位更新到终态的 Tool 调用。
         */
        TOOL_CALL
    }

    /**
     * 历史项闭集只包含当前公开合同允许回放的持久化事实。
     */
    public enum Origin {
        NATIVE,
        INHERITED
    }

    public sealed interface Item permits UserInputItem, ThreadMessageItem, TextItem, ToolItem, ApprovalItem, OriginItem {
        /**
         * 返回追加写入时生成的不透明条目身份。
         */
        String itemId();

        /**
         * 返回条目完成持久化的时间。
         */
        Instant createdAt();

        /**
         * 每个平坦历史项显式携带所属 Turn，禁止客户端按 Thread 合并推断。
         */
        String turnId();

        /** Snapshot 中的持久项必须显式标记来源；实时事件不经过此快照投影。 */
        default Origin origin() {
            return Origin.NATIVE;
        }
    }

    /**
     * 冻结历史只包装展示来源，不冒充本 Thread 的真实执行项。
     *
     * @param item 原始公开历史项
     */
    public record OriginItem(Item item) implements Item {
        /** 原始项身份、时间和 Turn 元数据保持不变，只有来源由快照 owner 明确指定。 */
        public OriginItem {
            Objects.requireNonNull(item, "item");
            if (item instanceof OriginItem) {
                throw new IllegalArgumentException("nested item origin is invalid");
            }
        }

        @Override
        public String itemId() {
            return item.itemId();
        }

        @Override
        public Instant createdAt() {
            return item.createdAt();
        }

        @Override
        public String turnId() {
            return item.turnId();
        }

        @Override
        public Origin origin() {
            return Origin.INHERITED;
        }
    }

    /**
     * Turn 元数据只表达 Operation 生命周期；模型事实必须从请求级 Usage Profile 读取。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param status 当前状态分类
     * @param requestedAt 请求时间
     * @param updatedAt 最后更新时间
     * @param completedAt 完成时间
     * @param errorCode 错误代码
     * @param changeSet 变更集合
     * @param mutationVersion 状态变更版本，用于并发提交校验
     * @param modelRound 模型调用轮次
     * @param sourceMessageId 来源消息标识
     */
    public record Turn(String turnId,
                       String status,
                       Instant requestedAt,
                       Instant updatedAt,
                       Instant completedAt,
                       String errorCode,
                       TurnChangeSet changeSet,
                       long mutationVersion,
                       int modelRound,
                       String sourceMessageId,
                       Origin origin) {
        /** 普通构造入口显式归类为本 Thread Turn，继承 Turn 必须选择对应来源值。 */
        public Turn(String turnId, String status, Instant requestedAt, Instant updatedAt, Instant completedAt,
                    String errorCode, TurnChangeSet changeSet, long mutationVersion, int modelRound,
                    String sourceMessageId) {
            this(turnId, status, requestedAt, updatedAt, completedAt, errorCode, changeSet,
                    mutationVersion, modelRound, sourceMessageId, Origin.NATIVE);
        }

        /**
         * 历史只公开稳定错误码，错误正文和 Provider 私有续传状态仍留在服务端。
         */
        public Turn {
            if (turnId == null || !turnId.matches("turn_[A-Za-z0-9][A-Za-z0-9._-]*")
                || turnId.length() > 128) {
                    throw new IllegalArgumentException("invalid turnId");
                }
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(origin, "origin");
            // v1 历史 Turn 未记录完整运行事实；null 比伪造 Provider/API 更诚实。
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{1,63}")) {
                throw new IllegalArgumentException("invalid errorCode");
            }
            if (mutationVersion < 0) {
                throw new IllegalArgumentException("invalid mutationVersion");
            }
            if (modelRound < 0) {
                throw new IllegalArgumentException("invalid modelRound");
            }
            if (sourceMessageId != null) {
                requireIdentifier(sourceMessageId, "item_", "sourceMessageId");
            }
        }

    }

    /**
     * 最近 Assistant Provider Usage 与可选最新本地完整输入估算组成恢复上下文指示器的权威来源。
     *
     * @param turnId 本次可恢复执行的稳定标识
     * @param request 请求
     * @param measuredAt 用量测量时间
     * @param estimatedContextTokens 估算的上下文 Token 数
     * @param estimatedContextMeasuredAt 上下文估算时间
     */
    public record ContextUsage(String turnId,
                               ProviderRequestUsage request,
                               Instant measuredAt,
                               Long estimatedContextTokens,
                               Instant estimatedContextMeasuredAt) {
        /**
         * 保留无本地估算的调用形状，通用历史读取不能凭空构造 Token 预算。
         */
        public ContextUsage(String turnId, ProviderRequestUsage request, Instant measuredAt) {
            this(turnId, request, measuredAt, null, null);
        }

        /**
         * Usage 必须能关联到快照中的真实 Turn；本地估算的 Token 与时间必须成对出现且只附着 UNKNOWN。
         */
        public ContextUsage {
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(measuredAt, "measuredAt");
            if ((estimatedContextTokens == null) != (estimatedContextMeasuredAt == null)
                || estimatedContextTokens != null && (estimatedContextTokens < 0
                                                      || estimatedContextTokens > 9_007_199_254_740_991L
                                                      || request.certainty() != ProviderRequestUsage.Certainty.UNKNOWN)) {
                throw new IllegalArgumentException("invalid local context estimate");
            }
        }
    }

    /**
     * 用户输入保留四类结构化 block；Renderer 不再从纯文本和附件旁表反推提交内容。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param content 内容
     * @param attachments 附件列表
     * @param totalCharacters 完整正文的 Unicode code point 数
     */
    public record UserInputItem(String itemId,
                                Instant createdAt,
                                String turnId,
                                UserContent content,
                                List<AttachmentSummary> attachments,
                                int totalCharacters) implements Item {
        /**
         * 消息身份、Turn 关联、附件顺序和正文预览必须来自同一 SQLite 快照；完整正文按需读取。
         */
        public UserInputItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(content, "content");
            attachments = List.copyOf(Objects.requireNonNull(attachments, "attachments"));
            if (!content.attachmentIds().equals(attachments.stream()
                    .map(AttachmentSummary::attachmentId).toList())) {
                throw new IllegalArgumentException("attachment summaries do not match content order");
            }
            int previewCharacters = content.text().codePointCount(0, content.text().length());
            if (totalCharacters < 0 || previewCharacters > TEXT_PREVIEW_CODE_POINTS
                || totalCharacters < previewCharacters) {
                throw new IllegalArgumentException("invalid user input preview length");
            }
        }

        /**
         * 截断状态由可见预览和完整字符数推导，不形成第二份持久状态。
         */
        public boolean truncated() {
            return content.text().codePointCount(0, content.text().length()) < totalCharacters;
        }
    }

    /**
     * 跨会话消息的独立可见投影；来源快照与正文分开保存，避免把传入消息伪装成当前用户输入。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param sourceThreadId 来源对话标识
     * @param sourceTitle 来源标题
     * @param content 有界正文预览
     * @param totalCharacters 完整正文的 Unicode code point 数
     */
    public record ThreadMessageItem(String itemId,
                                    Instant createdAt,
                                    String turnId,
                                    String sourceThreadId,
                                    String sourceTitle,
                                    String content,
                                    int totalCharacters) implements Item {
        /**
         * 消息保留发送时的 Thread 标识和标题快照；正文允许为空，完整 Mailbox 正文按需读取。
         */
        public ThreadMessageItem {
            requireIdentifier(itemId, "item_", "itemId");
            Objects.requireNonNull(createdAt, "createdAt");
            requireIdentifier(turnId, "turn_", "turnId");
            requireIdentifier(sourceThreadId, "thr_", "sourceThreadId");
            sourceTitle = boundedText(sourceTitle, "sourceTitle", 512, false);
            content = boundedText(content, "content", 1_048_576, true);
            int previewCharacters = content.codePointCount(0, content.length());
            if (totalCharacters < 0 || previewCharacters > TEXT_PREVIEW_CODE_POINTS
                || totalCharacters < previewCharacters) {
                throw new IllegalArgumentException("invalid thread message preview length");
            }
        }

        /**
         * 截断状态由可见预览和完整字符数推导，不形成第二份持久状态。
         */
        public boolean truncated() {
            return content.codePointCount(0, content.length()) < totalCharacters;
        }
    }

    /**
     * 表示用户输入或助手可见文本，不包含 Provider 私有推理。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param kind 类型分类
     * @param text 有界正文预览
     * @param totalCharacters 完整正文的 Unicode code point 数
     * @param modelRound 模型调用轮次
     */
    public record TextItem(String itemId,
                           Instant createdAt,
                           String turnId,
                           TextKind kind,
                           String text,
                           int totalCharacters,
                           Integer modelRound) implements Item {
        /**
         * 保留已经提交的有界可见预览；完整公开文本经 item 身份单独分页读取。
         */
        public TextItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            if (modelRound != null && modelRound < 1) {
                throw new IllegalArgumentException("invalid modelRound");
            }
            int previewCharacters = text.codePointCount(0, text.length());
            if (totalCharacters < 0 || previewCharacters > TEXT_PREVIEW_CODE_POINTS
                || totalCharacters < previewCharacters) {
                throw new IllegalArgumentException("invalid text item preview length");
            }
            if ((kind == TextKind.ASSISTANT_PROGRESS || kind == TextKind.REASONING_SUMMARY) == (modelRound == null)) {
                throw new IllegalArgumentException("message kind and modelRound must be paired");
            }
        }

        /**
         * 截断状态由可见预览和完整字符数推导，不形成第二份持久状态。
         */
        public boolean truncated() {
            return text.codePointCount(0, text.length()) < totalCharacters;
        }
    }

    /**
     * 表示一个 Tool 的最新安全展示事实；历史恢复按 callId 只返回这一项，避免把调用和结果重复显示。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param turnId 本次可恢复执行的稳定标识
     * @param kind 类型分类
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param presentation 展示信息
     * @param ordinal 当前项的顺序位置
     */
    public record ToolItem(String itemId,
                           Instant createdAt,
                           String turnId,
                           ToolKind kind,
                           String callId,
                           String toolName,
                           ToolPresentation presentation,
                           int ordinal) implements Item {
        /**
         * 拒绝不完整 Tool 事实，并保持调用顺序为非负整数。
         */
        public ToolItem {
            requireItem(itemId, createdAt);
            requireIdentifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(kind, "kind");
            callId = JaToolCallId.require(callId);
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(presentation, "presentation");
            if (ordinal < 0) {
                throw new IllegalArgumentException("invalid tool ordinal");
            }
        }
    }

    /**
     * 表示持久化的审批请求及其可选决策。
     *
     * @param itemId 条目标识
     * @param createdAt 创建时间
     * @param approvalId 审批标识
     * @param turnId 本次可恢复执行的稳定标识
     * @param callId 模型生成的工具调用标识
     * @param toolName 工具名称
     * @param reason 原因说明
     * @param decision 决策
     * @param expiresAt 过期时间
     */
    public record ApprovalItem(String itemId,
                               Instant createdAt,
                               String approvalId,
                               String turnId,
                               String callId,
                               String toolName,
                               String reason,
                               String decision,
                               Instant expiresAt) implements Item {
        /**
         * 保留审批关联与过期时间，decision 为空表示尚未解决。
         */
        public ApprovalItem {
            requireItem(itemId, createdAt);
            Objects.requireNonNull(approvalId, "approvalId");
            Objects.requireNonNull(turnId, "turnId");
            callId = JaToolCallId.require(callId);
            Objects.requireNonNull(toolName, "toolName");
            if (reason == null || reason.isBlank() || reason.length() > 2_048) {
                throw new IllegalArgumentException("invalid approval reason");
            }
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}

