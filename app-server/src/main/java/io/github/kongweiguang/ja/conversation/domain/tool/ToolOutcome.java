// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.tool;

/**
 * Tool 越过执行边界后可向领域提交的稳定结果。
 */
public enum ToolOutcome {
    /**
     * Tool 已完成并产生可供模型继续使用的结果。
     */
    SUCCEEDED,
    /**
     * Tool 已确认失败且没有未决执行。
     */
    FAILED,
    /**
     * Tool 在取消作用域内停止。
     */
    CANCELLED
}
