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
    private static final String FALLBACK_REPLY =
            "本轮未能完成：运行过程中发生了内部错误。Ja 已停止继续执行工具并保存失败状态，你可以重新编辑原问题后再试。";
    private static final Map<String, String> REPLIES = Map.ofEntries(
            Map.entry("MODEL_UNAVAILABLE",
                    "本轮未能完成：模型服务当前不可用。Ja 已保存失败状态且没有继续执行工具，你可以稍后重新编辑原问题后再试。"),
            Map.entry("MODEL_PROTOCOL_ERROR",
                    "本轮未能完成：模型响应格式有误或不完整。失败原因已保存，请重新编辑原问题后再试。"),
            Map.entry("BUDGET_EXCEEDED",
                    "本轮未能完成：运行已达到本轮资源上限。Ja 已停止继续执行工具并保存失败状态，你可以缩小任务范围后重新编辑原问题。"),
            Map.entry("REQUEST_DEADLINE_EXCEEDED",
                    "本轮未能完成：运行超过了本轮截止时间。Ja 已停止继续执行工具并保存失败状态，你可以稍后重新编辑原问题后再试。"),
            Map.entry("CONTEXT_LIMIT",
                    "本轮未能完成：当前对话上下文超过了可安全处理的范围。Ja 已保存失败状态，你可以压缩上下文或新建对话后重试。"),
            Map.entry("CONFLICT",
                    "本轮未能完成：对话状态在运行期间发生了冲突。Ja 已停止本轮并保存失败状态，请刷新对话后重新编辑原问题。"),
            Map.entry("INVALID_STATE",
                    "本轮未能完成：对话状态不允许继续安全运行。Ja 已停止继续执行工具并保存失败状态，请刷新对话后再试。"),
            Map.entry("THREAD_BUSY",
                    "本轮未能完成：当前对话仍有其他任务占用运行权。Ja 没有继续执行工具，请等待正在运行的任务结束后再试。"),
            Map.entry("APPROVAL_EXPIRED",
                    "本轮未能完成：工具授权已失效。Ja 没有继续执行该工具，请重新编辑原问题并在新的授权请求中确认。"),
            Map.entry("MCP_SERVER_UNAVAILABLE",
                    "本轮未能完成：MCP 服务当前不可用或未能安全关闭。Ja 已停止继续执行工具并保存失败状态，请检查 MCP 服务后再试。"),
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
