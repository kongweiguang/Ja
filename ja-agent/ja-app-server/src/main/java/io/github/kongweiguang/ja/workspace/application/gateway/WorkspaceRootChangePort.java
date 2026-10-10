// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.gateway;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 把进程内项目根变更提示交给 Java Prompt owner，不将工作目录事实转移给客户端。
 */
public interface WorkspaceRootChangePort {
    /**
     * 按 Thread 已消费的 revision 合并未读根变更，避免漏掉连续重绑的最终路径。
     */
    Optional<Change> pending(String workspaceId, String threadId);

    /**
     * 仅在完整模型请求意图已持久化后消费该 Thread 已看见的根变更。
     */
    void consume(String workspaceId, String threadId, long revision);

    /**
     * 提供不携带进程历史的隔离实现；生产组合根必须连接 WorkspaceService。
     */
    static WorkspaceRootChangePort empty() {
        return new WorkspaceRootChangePort() {
            /**
             * 隔离 Prompt fixture 没有真实 Workspace history。
             */
            @Override
            public Optional<Change> pending(String workspaceId, String threadId) {
                return Optional.empty();
            }

            /**
             * 隔离 Prompt fixture 不持有生产 Thread 消费游标。
             */
            @Override
            public void consume(String workspaceId, String threadId, long revision) {
                // 隔离测试没有真实 Workspace 生命周期。
            }
        };
    }

    /**
     * 一个 Thread 尚未收到的合并通知；进程重启后不恢复该事实，只由当前 cwd 建立新基线。
     *
     * @param revision 最新已合并的 Workspace revision
     * @param oldRoot 尚未提示区间的起始根目录
     * @param newRoot 尚未提示区间的最终根目录
     */
    record Change(long revision,
                  Path oldRoot,
                  Path newRoot) {
        /**
         * 规范化绝对路径并拒绝伪造的非正向重绑通知。
         */
        public Change {
            if (revision < 1) {
                throw new IllegalArgumentException("invalid workspace root change revision");
            }
            oldRoot = Objects.requireNonNull(oldRoot, "oldRoot").toAbsolutePath().normalize();
            newRoot = Objects.requireNonNull(newRoot, "newRoot").toAbsolutePath().normalize();
            if (oldRoot.equals(newRoot)) {
                throw new IllegalArgumentException("workspace root change must change the root");
            }
        }
    }
}
