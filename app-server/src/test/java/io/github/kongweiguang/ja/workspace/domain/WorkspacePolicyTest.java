// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 验证稳定身份和根目录展示名等纯领域边界。 */
final class WorkspacePolicyTest {
    private final WorkspacePolicy policy = new WorkspacePolicy();

    /** 等价规范路径必须得到同一 opaque ID，不同物理语义路径必须不同。 */
    @Test
    void derivesStableIdentityFromNormalizedRoot() {
        Path root = Path.of("workspace").toAbsolutePath().normalize();

        assertEquals(policy.workspaceId(root), policy.workspaceId(root.resolve("child").resolve("..")));
        assertNotEquals(policy.workspaceId(root), policy.workspaceId(root.resolve("other")));
    }

    /** 文件系统根没有 getFileName，展示名策略必须安全回退到根路径表示。 */
    @Test
    void fallsBackWhenFilesystemRootHasNoFileName() {
        Path root = Path.of("/").toAbsolutePath().normalize();

        assertNull(root.getFileName());
        assertEquals(root.toString(), policy.displayName(root, null));
    }

    /** 通用工作区固定受信任，项目工作区默认不加载项目配置。 */
    @Test
    void assignsTrustByDirectoryKind() {
        assertEquals(Workspace.Trust.TRUSTED,
                policy.initialTrust(WorkspaceDirectory.Kind.GENERAL));
        assertEquals(Workspace.Trust.UNTRUSTED,
                policy.initialTrust(WorkspaceDirectory.Kind.PROJECT));
    }
}
