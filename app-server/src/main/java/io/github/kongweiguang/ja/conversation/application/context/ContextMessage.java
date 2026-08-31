// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import io.github.kongweiguang.ja.conversation.application.context.compaction.ToolOutputProjector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Provider 中立的历史消息，保留稳定 ordinal、Turn 归属和结构化 Tool 配对信息。
 */
public record ContextMessage(
        String messageId,
        String turnId,
        long ordinal,
        Role role,
        List<Block> blocks,
        int estimatedTokens) {

    /**
     * 冻结消息块并拒绝空内容，使预算规划和 Checkpoint 重放始终面对不可变完整消息。
     */
    public ContextMessage {
        messageId = boundedIdentifier(messageId, "messageId", 256);
        turnId = boundedIdentifier(turnId, "turnId", 256);
        if (ordinal < 1 || role == null || estimatedTokens < 0) {
            throw new IllegalArgumentException("invalid context message");
        }
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("context message requires content");
        }
    }

    /**
     * 为纯文本历史提供窄构造入口，统一复用消息身份和预算校验。
     */
    public static ContextMessage text(String messageId, String turnId, long ordinal, Role role,
                                      String text, int estimatedTokens) {
        return new ContextMessage(messageId, turnId, ordinal, role,
                List.of(new TextBlock(text)), estimatedTokens);
    }

    /**
     * 标记消息是否参与 Tool 调用配对，供压缩策略保护调用与结果的原子保留。
     */
    public boolean hasToolCall() {
        return blocks.stream().anyMatch(block -> block instanceof ToolCallBlock);
    }

    /**
     * 标记消息是否携带 Tool 结果，决定提示投影是否需要执行有界收缩。
     */
    public boolean hasToolResult() {
        return blocks.stream().anyMatch(block -> block instanceof ToolResultBlock);
    }

    /**
     * 提取稳定调用身份，供策略验证重复调用和结果先后顺序。
     */
    public List<String> toolCallIds() {
        return blocks.stream().filter(ToolCallBlock.class::isInstance)
                .map(ToolCallBlock.class::cast).map(ToolCallBlock::callId).toList();
    }

    /**
     * 提取结果关联身份，供策略拒绝孤立、重复或先于调用的 Tool 结果。
     */
    public List<String> toolResultIds() {
        return blocks.stream().filter(ToolResultBlock.class::isInstance)
                .map(ToolResultBlock.class::cast).map(ToolResultBlock::callId).toList();
    }

    /**
     * 仅收缩发给模型的 Tool 结果投影，保留源消息和非结果块以维持历史事实不变。
     */
    public ContextMessage project(ToolOutputProjector projector) {
        Objects.requireNonNull(projector, "projector");
        if (!hasToolResult()) {
            return this;
        }
        List<Block> projected = blocks.stream().map(block -> {
            if (!(block instanceof ToolResultBlock result)) {
                return block;
            }
            ToolOutputProjector.Projection output = projector.project(result.output());
            return new ToolResultBlock(result.callId(), result.name(),
                    ToolOutput.prompt(output.promptText(), output.artifactReference(),
                            output.exitCode(), output.error()));
        }).toList();
        return new ContextMessage(messageId, turnId, ordinal, role, projected,
                projector.estimate(projected));
    }

    /**
     * 当最新单文本消息超过尾部预算时按 Unicode code point 切分，避免截断代理项。
     */
    public MessageSplit splitTextSuffix(int suffixCharacters) {
        if (suffixCharacters < 1) {
            throw new IllegalArgumentException("suffixCharacters must be positive");
        }
        if (blocks.size() != 1 || !(blocks.getFirst() instanceof TextBlock text)) {
            return new MessageSplit(this, this, false);
        }
        int codePoints = text.value().codePointCount(0, text.value().length());
        if (codePoints <= suffixCharacters) {
            return new MessageSplit(this, this, false);
        }
        int suffixStart = text.value().offsetByCodePoints(0, codePoints - suffixCharacters);
        String prefixText = text.value().substring(0, suffixStart);
        String suffixText = text.value().substring(suffixStart);
        int prefixTokens = Math.max(1, (prefixText.codePointCount(0, prefixText.length()) + 3) / 4);
        int suffixTokens = Math.max(1, (suffixText.codePointCount(0, suffixText.length()) + 3) / 4);
        ContextMessage prefix = new ContextMessage(derivedIdentifier(messageId, "prefix"), turnId, ordinal, role,
                List.of(new TextBlock(prefixText)), prefixTokens);
        ContextMessage suffix = new ContextMessage(derivedIdentifier(messageId, "suffix"), turnId, ordinal, role,
                List.of(new TextBlock(suffixText)), suffixTokens);
        return new MessageSplit(prefix, suffix, true);
    }

    /**
     * 为 split-turn 两侧生成可重现且有界的新身份，避免前缀与后缀冒用原消息 ID。
     */
    private static String derivedIdentifier(String source, String part) {
        String digest;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            digest = HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        String suffix = "-" + part + "-" + digest;
        int prefixLength = Math.min(source.length(), 256 - suffix.length());
        return source.substring(0, prefixLength) + suffix;
    }

    /**
     * 拒绝空白、控制换行和超长身份，保证消息与 Tool 关联键可安全持久化。
     */
    private static String boundedIdentifier(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
            || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 上下文消息角色与 Provider Wire 词汇解耦。
     */
    public enum Role {
        /**
         * 应用生成且优先于对话历史的系统指令。
         */
        SYSTEM,
        /**
         * 用户提交并持久化的输入。
         */
        USER,
        /**
         * 模型提交的文本或 Tool 调用。
         */
        ASSISTANT,
        /**
         * Tool 执行后回传给模型的结果。
         */
        TOOL
    }

    /**
     * 限定进入上下文预算与摘要流程的消息块闭集，避免 Adapter 私有结构泄漏。
     */
    public sealed interface Block permits AttachmentBlock, TextBlock, ToolCallBlock, ToolResultBlock {
    }

    /**
     * 在预算、压缩与 Provider 路由之间保留 opaque 附件身份；不携带路径、hash 或用户内容。
     */
    public record AttachmentBlock(String attachmentId) implements Block {
        /** 只接受受管附件 identity，防止通用资源标识绕过附件授权端口。 */
        public AttachmentBlock {
            attachmentId = boundedIdentifier(attachmentId, "attachmentId", 256);
            if (!attachmentId.startsWith("att_")) throw new IllegalArgumentException("invalid attachmentId");
        }
    }

    /**
     * 保存可参与 split-turn 的文本正文；空文本不构成有效历史事实。
     */
    public record TextBlock(String value) implements Block {
        /**
         * 对模型输入设置硬上限并拒绝 NUL，防止畸形历史绕过预算前置校验。
         */
        public TextBlock {
            if (value == null || value.isEmpty() || value.length() > 10_000_000
                || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid context text");
            }
        }
    }

    /**
     * 保存 Provider 中立的 Tool 调用身份、名称和原始参数，供结果配对与摘要使用。
     */
    public record ToolCallBlock(String callId, String name, String arguments) implements Block {
        /**
         * 限制调用元数据与参数大小，避免未受信模型输出无限进入历史。
         */
        public ToolCallBlock {
            callId = boundedIdentifier(callId, "callId", 256);
            name = boundedIdentifier(name, "toolName", 256);
            if (arguments == null || arguments.length() > 2_000_000 || arguments.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid Tool arguments");
            }
        }
    }

    /** 将 Tool 输出绑定到完整调用身份；当前基线不接纳缺失名称的旧运行时结果。 */
    public record ToolResultBlock(String callId, String name, ToolOutput output) implements Block {
        /**
         * 强制结果具有调用身份和输出载荷，使配对策略不依赖空对象语义。
         */
        public ToolResultBlock {
            callId = boundedIdentifier(callId, "callId", 256);
            name = boundedIdentifier(name, "toolName", 256);
            Objects.requireNonNull(output, "output");
        }
    }

    /**
     * 同时表达完整持久事实与有界提示投影，避免截断结果覆盖原始 Tool 输出。
     */
    public record ToolOutput(String content, String artifactReference, Integer exitCode,
                             String error, boolean promptProjection) {
        /**
         * 分别限制正文、产物引用和错误文本，控制 Tool 边界进入内存的最大规模。
         */
        public ToolOutput {
            if (content == null || content.length() > 20_000_000 || content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid Tool output");
            }
            if (artifactReference != null && (artifactReference.isBlank()
                                              || artifactReference.length() > 1_024 || artifactReference.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid Tool artifact reference");
            }
            if (error != null && (error.isBlank() || error.length() > 8_192 || error.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid Tool error");
            }
        }

        /**
         * 创建可持久化的完整 Tool 输出，明确标记其尚未经过提示收缩。
         */
        public static ToolOutput full(String content, String artifactReference, Integer exitCode, String error) {
            return new ToolOutput(content, artifactReference, exitCode, error, false);
        }

        /**
         * 创建仅供模型提示使用的投影，保留产物与退出信息但不替代完整事实。
         */
        static ToolOutput prompt(String content, String artifactReference, Integer exitCode, String error) {
            return new ToolOutput(content, artifactReference, exitCode, error, true);
        }
    }

    /**
     * 记录最新消息的摘要前缀和保留后缀，并显式区分无需切分的情况。
     */
    public record MessageSplit(ContextMessage prefix, ContextMessage suffix, boolean split) {
        /**
         * 保证切分两侧始终存在，使规划器可以统一处理切分与未切分路径。
         */
        public MessageSplit {
            Objects.requireNonNull(prefix, "prefix");
            Objects.requireNonNull(suffix, "suffix");
        }
    }
}
