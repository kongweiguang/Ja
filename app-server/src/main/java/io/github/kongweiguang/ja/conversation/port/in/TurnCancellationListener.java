// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.in;

/**
 * Turn 取消事实提交后的跨 bounded-context 通知；late binding 避免 conversation 反向依赖 Task。
 */
@FunctionalInterface
public interface TurnCancellationListener {
    /**
     * 仅在 SQLite 已接受首次取消声明后调用；监听方不得尝试回滚父 Turn 的权威取消事实。
     */
    void cancellationClaimed(String parentThreadId, String parentTurnId);

    /** 生产监听器绑定后恢复父取消事实留下的传播欠账；无 Task 运行时默认无需处理。 */
    default void reconcilePending() { }

    /** 未启用 Child Task 时保持普通 Turn 取消语义不变。 */
    static TurnCancellationListener noop() {
        return (parentThreadId, parentTurnId) -> { };
    }
}
