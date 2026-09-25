// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.in;

import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * 工作区唯一入站用例；RPC 只负责 Wire DTO 与这些纯 Java 命令之间的映射。
 */
public interface WorkspaceUseCase {
    /**
     * 校验真实目录、派生稳定身份、持久化并绑定一个项目工作区。
     */
    Workspace openWorkspace(OpenWorkspace command);

    /** Java 为新主 Thread 创建并登记唯一会话目录；同 Thread 重试只允许复用空目录。 */
    Workspace createSessionWorkspace(String threadId);

    /** Thread 持久化失败时只注销刚建且仍无引用的 SESSION 记录，不删除其空目录。 */
    void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision);

    /** 只按数据库登记身份重开 SESSION 或 LEGACY_SHARED，不允许客户端路径升级为身份。 */
    Workspace openRegisteredWorkspace(String workspaceId);

    /**
     * 使用稳定键集游标列出持久化工作区。
     */
    CursorPage<Workspace> listWorkspaces(String cursor, int limit);

    /** 可选 kind 由持久层先过滤再分页，避免大批 SESSION 抢占项目目录的首屏窗口。 */
    default CursorPage<Workspace> listWorkspaces(String cursor, int limit, Workspace.Kind kind) {
        if (kind != null) throw new UnsupportedOperationException("typed workspace listing is unavailable");
        return listWorkspaces(cursor, limit);
    }

    /**
     * 按持久化身份读取工作区，不隐式绑定文件能力。
     */
    Optional<Workspace> readWorkspace(String workspaceId);

    /**
     * 读取已经通过本进程物理目录校验的工作区。
     */
    Workspace requireOpenWorkspace(String workspaceId);

    /** 设置编辑可验证已登记项目而不打开会话工作区或分配长寿命原生资源。 */
    default Workspace requireSettingsWorkspace(String workspaceId) {
        return requireOpenWorkspace(workspaceId);
    }

    /**
     * 更新权威信任状态，并同步项目配置边界与预热状态。
     */
    Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust);

    /**
     * 通过 revision CAS 注销元数据和进程内目录绑定，但不删除任何用户文件。
     */
    void unregisterWorkspace(String workspaceId, long expectedRevision);

    /**
     * 对当前已打开的项目工作区重新执行可失败的最佳努力预热。
     */
    void refreshPreparedWorkspaces();

    /**
     * 只比较规范化路径，用于路由判断且不得产生文件系统副作用。
     */
    boolean isLegacySharedWorkspace(Path root);

    /**
     * 入站打开命令只承载业务输入，不携带 JSON、传输别名或宿主生成的 workspaceId。
     */
    record OpenWorkspace(Path root, String displayName) {
        /**
         * 保留可选展示名，同时尽早拒绝缺失路径，目录真实性由出站端口确认。
         */
        public OpenWorkspace {
            Objects.requireNonNull(root, "root");
        }
    }
}
