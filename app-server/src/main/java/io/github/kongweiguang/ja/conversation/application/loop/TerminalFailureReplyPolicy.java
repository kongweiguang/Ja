// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.turn.TurnExecutionState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Objects;

/**
 * 为失败 Turn 生成确定、无敏感信息的终结回复，使已接纳请求即使无法完成也能留下可持久化的产品反馈。
 */
public final class TerminalFailureReplyPolicy {
    private static final String FALLBACK_REPLY = "发生内部错误。";
    private static final Map<String, String> REPLIES = Map.ofEntries(
            Map.entry("MODEL_UNAVAILABLE", "模型服务暂时不可用。"),
            Map.entry("MODEL_PROTOCOL_ERROR", "模型响应格式有误或不完整。"),
            Map.entry("BUDGET_EXCEEDED", "已达到本轮资源上限。"),
            Map.entry("REQUEST_DEADLINE_EXCEEDED", "本次执行超时。"),
            Map.entry("CONTEXT_LIMIT", "当前对话上下文过长。"),
            Map.entry("SUMMARY_FAILURE", "对话摘要生成失败。"),
            Map.entry("CONFLICT", "会话状态已变化。"),
            Map.entry("INVALID_STATE", "当前会话状态无法继续执行。"),
            Map.entry("THREAD_BUSY", "当前对话仍在执行其他任务。"),
            Map.entry("APPROVAL_EXPIRED", "工具授权已失效。"),
            Map.entry("MCP_SERVER_UNAVAILABLE", "MCP 服务暂时不可用。"),
            Map.entry("INTERNAL_ERROR", FALLBACK_REPLY));

    /**
     * 只依据稳定错误码选择固定正文，刻意不拼接异常、路径、Tool 参数或 Provider 返回内容以避免泄漏。
     */
    public String replyFor(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) return FALLBACK_REPLY;
        return REPLIES.getOrDefault(errorCode, FALLBACK_REPLY);
    }

    /**
     * 优先复用已提交 Provider intent 的消息身份；应急路径没有 intent 时从 Turn 身份派生稳定 ID，
     * 让常规 Loop 与外层服务兜底共享同一幂等边界。
     */
    public String messageIdFor(String turnId, TurnExecutionState execution) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(execution, "execution");
        if (execution instanceof TurnExecutionState.ProviderPending pending) return pending.messageId();
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(turnId.getBytes(StandardCharsets.UTF_8));
            return "item_failure_" + java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
