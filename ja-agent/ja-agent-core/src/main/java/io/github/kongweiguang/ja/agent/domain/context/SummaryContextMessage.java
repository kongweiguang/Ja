// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.context;

import java.util.List;
import java.util.Objects;

/**
 * 为摘要模型提供不依赖 Context 应用服务的冻结历史投影。
 *
 * @param messageId 消息标识
 * @param turnId 本次可恢复执行的稳定标识
 * @param ordinal 当前项的顺序位置
 * @param role 角色
 * @param blocks 内容块
 */
public record SummaryContextMessage(String messageId,
                                    String turnId,
                                    long ordinal,
                                    Role role,
                                    List<Block> blocks) {
    /**
     * 保留摘要所需语义块，并拒绝空消息，避免 Provider 适配器猜测内容缺失。
     */
    public SummaryContextMessage {
        messageId = identifier(messageId, "messageId");
        turnId = identifier(turnId, "turnId");
        if (ordinal < 1) {
            throw new IllegalArgumentException("invalid message ordinal");
        }
        Objects.requireNonNull(role, "role");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        if (blocks.isEmpty() || blocks.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("summary message requires content blocks");
        }
    }

    /**
     * 按摘要历史所需最小字符规则校验身份，不泄漏应用专属命名空间。
     */
    private static String identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256
            || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 摘要模型使用稳定角色枚举，不暴露 Provider 消息类型。
     */
    public enum Role {
        /**
         * 定义摘要任务约束的 System 指令。
         */
        SYSTEM,
        /**
         * 纳入历史摘要的用户消息。
         */
        USER,
        /**
         * 纳入历史摘要的助手消息。
         */
        ASSISTANT,
        /**
         * 与来源调用配对保留的工具结果。
         */
        TOOL
    }

    /**
     * 摘要提示中允许出现的内容块闭集。
     */
    public sealed interface Block permits Text, Attachment, Reasoning, ToolCall, ToolResult {
    }

    /**
     * 用户或助手可见文本证据。
     *
     * @param value 文本值
     */
    public record Text(String value) implements Block {
        /**
         * 拒绝空文本与 NUL，保持摘要编码和审计内容稳定。
         */
        public Text {
            if (value == null || value.isEmpty() || value.length() > 10_000_000 || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid summary text");
            }
        }
    }

    /**
     * 受管附件身份，不携带本地路径或文件正文。
     *
     * @param attachmentId 附件标识
     */
    public record Attachment(String attachmentId) implements Block {
        /**
         * 确认仅传递 opaque attachment identity。
         */
        public Attachment {
            attachmentId = identifier(attachmentId, "attachmentId");
            // 附件标识由宿主分配；通用上下文不得限定 Ja 专属前缀。
        }
    }

    /**
     * 原生 reasoning 的身份摘要；不含签名、encrypted payload 或正文。
     *
     * @param providerId 模型供应商标识
     * @param modelId 模型标识
     * @param api 模型 API 协议
     * @param upstreamModel 上游模型
     * @param wireField 协议字段名
     */
    public record Reasoning(String providerId,
                            String modelId,
                            String api,
                            String upstreamModel,
                            String wireField) implements Block {
        /**
         * 固定非空模型身份，避免摘要扩展接触 opaque reasoning 内容。
         */
        public Reasoning {
            providerId = identifier(providerId, "providerId");
            modelId = identifier(modelId, "modelId");
            api = identifier(api, "api");
            upstreamModel = identifier(upstreamModel, "upstreamModel");
            wireField = identifier(wireField, "wireField");
        }
    }

    /**
     * 保留工具调用配对身份和已校验参数文本供摘要解释执行过程。
     *
     * @param callId 模型生成的工具调用标识
     * @param name 名称
     * @param arguments 工具调用参数
     */
    public record ToolCall(String callId,
                           String name,
                           String arguments) implements Block {
        /**
         * 限制标识和参数体积，防止历史投影越过模型请求边界。
         */
        public ToolCall {
            callId = identifier(callId, "callId");
            name = identifier(name, "toolName");
            if (arguments == null || arguments.length() > 2_000_000 || arguments.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid Tool arguments");
            }
        }
    }

    /**
     * 工具事实保留完整可见结果与其投影标记，不改变副作用结果语义。
     *
     * @param callId 模型生成的工具调用标识
     * @param name 名称
     * @param content 内容
     * @param artifactReference 产物引用
     * @param exitCode 进程退出码
     * @param error 错误信息
     * @param promptProjection 是否用于提示词投影
     */
    public record ToolResult(String callId,
                             String name,
                             String content,
                             String artifactReference,
                             Integer exitCode,
                             String error,
                             boolean promptProjection) implements Block {
        /**
         * 校验输出边界，确保摘要适配器收到的是已接受的工具结果。
         */
        public ToolResult {
            callId = identifier(callId, "callId");
            name = identifier(name, "toolName");
            if (content == null || content.length() > 20_000_000 || content.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid Tool output");
            }
            if (artifactReference != null && (artifactReference.isBlank() || artifactReference.length() > 1_024
                                              || artifactReference.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid artifactReference");
            }
            if (error != null && (error.isBlank() || error.length() > 8_192 || error.indexOf('\0') >= 0)) {
                throw new IllegalArgumentException("invalid Tool error");
            }
        }
    }
}
