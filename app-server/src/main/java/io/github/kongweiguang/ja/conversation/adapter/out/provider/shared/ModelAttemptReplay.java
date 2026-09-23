// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import io.github.kongweiguang.ja.conversation.port.out.ModelPort;

import java.util.Objects;

/**
 * 记录已经送达下游的安全文本前缀，令截断流重试时只发布尚未送达的尾部。
 *
 * <p>Tool、原生 reasoning block 和 Usage 都被视为不可安全回放的结构化事实；一旦这类事件
 * 进入下游，调用方必须结束当前请求，避免把未知副作用或 opaque 状态重复交给模型/执行器。</p>
 */
final class ModelAttemptReplay {
    private static final int MAX_REPLAY_TEXT = 4_000_000;

    private final StringBuilder acceptedText = new StringBuilder();
    private final StringBuilder acceptedReasoning = new StringBuilder();
    private int textCursor;
    private int reasoningCursor;
    private boolean replayable = true;
    private boolean unsafeStructuredEvent;

    /**
     * 开始一次新的重试尝试；游标从已送达前缀的开头重新匹配，而账本正文继续保留。
     */
    synchronized void beginAttempt(boolean replaying) {
        if (!replaying) return;
        textCursor = 0;
        reasoningCursor = 0;
    }

    /**
     * 为重试尝试准备要送达的事件；完全匹配既有前缀的增量返回 null，避免 UI 重复显示。
     */
    synchronized ModelPort.ModelEvent prepare(ModelPort.ModelEvent event, boolean replaying) {
        Objects.requireNonNull(event, "event");
        if (!replaying) return event;
        if (event instanceof ModelPort.TextDelta text) {
            return replayText(text, acceptedText);
        }
        if (event instanceof ModelPort.ReasoningSummaryDelta reasoning) {
            return replayReasoning(reasoning, acceptedReasoning);
        }
        if (!prefixConsumed()) {
            throw new ReplayMismatchException();
        }
        return event;
    }

    /**
     * 在重试尝试返回正常终局前确认两个公开通道都完整跨过旧账本；首次尝试没有回放前缀，跳过检查。
     */
    synchronized void verifyComplete(boolean replaying) {
        if (replaying && !prefixConsumed()) {
            throw new ReplayMismatchException();
        }
    }

    /**
     * 在 sink 成功接纳后提交回放账本；失败事件不会被记录为已经送达的事实。
     */
    synchronized void accepted(ModelPort.ModelEvent event, boolean replaying) {
        Objects.requireNonNull(event, "event");
        if (event instanceof ModelPort.TextDelta text) {
            if (replaying) {
                appendReplaySuffix(acceptedText, text.text(), textCursor);
                textCursor += text.text().length();
            } else {
                appendBounded(acceptedText, text.text());
            }
            return;
        }
        if (event instanceof ModelPort.ReasoningSummaryDelta reasoning) {
            if (replaying) {
                appendReplaySuffix(acceptedReasoning, reasoning.text(), reasoningCursor);
                reasoningCursor += reasoning.text().length();
            } else {
                appendBounded(acceptedReasoning, reasoning.text());
            }
            return;
        }
        /* 这些结构化事件一旦送达，重试无法证明幂等；保守停止后续自动重放。 */
        unsafeStructuredEvent = true;
        replayable = false;
    }

    /**
     * 判断当前已送达内容是否仍可在没有 Tool 副作用的前提下安全重试。
     */
    synchronized boolean canRetryAfterSemantic() {
        return replayable && !unsafeStructuredEvent;
    }

    /** 判断重试游标是否已经消费了此前送达的全部公开文本和摘要。 */
    private boolean prefixConsumed() {
        return textCursor == acceptedText.length() && reasoningCursor == acceptedReasoning.length();
    }

    /**
     * 按字符前缀跳过已经送达的正文，允许 Provider 在每次重试中重新分块。
     */
    private ModelPort.ModelEvent replayText(ModelPort.TextDelta event, StringBuilder accepted) {
        String value = event.text();
        int cursor = textCursor;
        int common = commonPrefixLength(value, accepted, cursor);
        if (common < Math.min(value.length(), accepted.length() - cursor)
                && value.charAt(common) != accepted.charAt(cursor + common)) {
            throw new ReplayMismatchException();
        }
        textCursor += common;
        if (common == value.length()) return null;
        String suffix = value.substring(common);
        return new ModelPort.TextDelta(suffix);
    }

    /**
     * 按字符前缀跳过已经送达的公开 reasoning 摘要，隐藏 reasoning 永远不进入该账本。
     */
    private ModelPort.ModelEvent replayReasoning(
            ModelPort.ReasoningSummaryDelta event, StringBuilder accepted) {
        String value = event.text();
        int cursor = reasoningCursor;
        int common = commonPrefixLength(value, accepted, cursor);
        if (common < Math.min(value.length(), accepted.length() - cursor)
                && value.charAt(common) != accepted.charAt(cursor + common)) {
            throw new ReplayMismatchException();
        }
        reasoningCursor += common;
        if (common == value.length()) return null;
        String suffix = value.substring(common);
        return new ModelPort.ReasoningSummaryDelta(suffix);
    }

    /**
     * 返回当前增量与既有下游正文的最长安全公共前缀长度。
     */
    private static int commonPrefixLength(String value, StringBuilder accepted, int cursor) {
        int common = 0;
        while (common < value.length() && cursor + common < accepted.length()
                && value.charAt(common) == accepted.charAt(cursor + common)) {
            common++;
        }
        return common;
    }

    /**
     * 对回放账本使用独立上限，避免恶意或失控 Provider 把重试状态变成无界内存。
     */
    private void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_REPLAY_TEXT - target.length();
        if (remaining <= 0) {
            replayable = false;
            return;
        }
        int end = Math.min(remaining, value.length());
        target.append(value, 0, end);
        if (end < value.length()) replayable = false;
    }

    /**
     * 仅把当前重试新越过账本末端的内容写入账本，避免连续失败时重复追加已经接纳的 suffix。
     */
    private void appendReplaySuffix(StringBuilder target, String value, int cursor) {
        if (cursor >= target.length()) {
            appendBounded(target, value);
            return;
        }
        int alreadyRecorded = target.length() - cursor;
        if (alreadyRecorded >= value.length()) return;
        appendBounded(target, value.substring(alreadyRecorded));
    }

    /**
     * 重试响应不再复述此前已经发布的正文时，立即终止本次重试而不猜测模型意图。
     */
    static final class ReplayMismatchException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** 固定脱敏消息，避免把 Provider 正文带入异常链。 */
        private ReplayMismatchException() {
            super("provider retry did not preserve the accepted response prefix");
        }
    }
}
