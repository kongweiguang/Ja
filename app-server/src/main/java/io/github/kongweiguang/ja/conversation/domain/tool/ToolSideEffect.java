// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.tool;

/**
 * Tool 对崩溃恢复与重试策略公开的副作用边界。
 */
public enum ToolSideEffect {
    /**
     * 不改变工作区或外部系统，可在约束内安全重试。
     */
    READ_ONLY,
    /**
     * 可能改变工作区或外部系统，失败后不得盲目重试。
     */
    EXTERNAL
}
