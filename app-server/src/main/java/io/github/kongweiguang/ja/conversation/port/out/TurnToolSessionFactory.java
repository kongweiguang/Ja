// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;

/**
 * 为单个 Turn 延迟创建并绑定同一代际的 Tool 会话。
 */
@FunctionalInterface
public interface TurnToolSessionFactory {
    /**
     * 使用 Turn 取消令牌打开独占会话；失败时不得返回部分资源。
     */
    Session open(CancellationToken cancellationToken);

    /**
     * 持有冻结 Tool 集合及其底层 MCP 连接的唯一释放权。
     */
    interface Session extends AutoCloseable {
        /**
         * 返回会话创建时冻结的 Tool 集合，关闭后不得继续访问。
         */
        List<AgentTool> tools();

        /**
         * 幂等释放会话资源，使完成、取消和异常路径可以竞争关闭。
         */
        @Override
        void close();
    }
}
