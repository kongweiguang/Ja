// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.out;

import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 隔离 workspace application 与 SQLite、MyBatis、事务和游标编码实现。
 */
public interface WorkspaceRepository {
    /**
     * 幂等注册一个由应用层派生身份的规范目录，并返回持久化权威 revision。
     */
    Workspace register(Workspace.Registration registration);

    /**
     * 使用持久层稳定键集读取全局工作区页面。
     */
    CursorPage<Workspace> list(String cursor, int limit);

    /**
     * 按 opaque 身份读取工作区，不产生注册副作用。
     */
    Optional<Workspace> findById(String workspaceId);

    /**
     * 按规范根读取工作区，供并发打开与进程重启复用同一身份。
     */
    Optional<Workspace> findByRoot(Path canonicalRoot);

    /**
     * 更新持久化信任状态并返回新的权威 revision。
     */
    Workspace updateTrust(String workspaceId, Workspace.Trust trust);

    /**
     * 通过 revision CAS 删除注册元数据，不接触目录内容。
     */
    void unregister(String workspaceId, long expectedRevision);
}
