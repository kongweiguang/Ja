// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.tool;

/**
 * Tool 调用在持久化和崩溃恢复中使用的生命周期闭集。
 */
public enum ToolState {
    /**
     * 已冻结参数和顺序，但尚未产生外部副作用。
     */
    PREPARED,
    /**
     * 已越过执行边界，恢复时必须结合副作用语义判断。
     */
    RUNNING,
    /**
     * Tool 已成功返回并持久化结果。
     */
    SUCCEEDED,
    /**
     * Tool 已失败并持久化安全错误。
     */
    FAILED,
    /**
     * Tool 在取消作用域内终止。
     */
    CANCELLED
}
