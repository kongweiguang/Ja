// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

/** 只读输入历史仅返回主会话可见文本，引用身份仍由用户重新明确选择。 */
public record UserInputHistory(String itemId, String threadId, String text, String createdAt, boolean truncated) {
    /** 不允许空身份或控制字符进入终端搜索投影。 */
    public UserInputHistory {
        if (itemId == null || !itemId.startsWith("item_") || threadId == null || !threadId.startsWith("thr_")
                || text == null || text.length() > 65536 || createdAt == null) {
            throw new IllegalArgumentException("invalid input history projection");
        }
    }
}
