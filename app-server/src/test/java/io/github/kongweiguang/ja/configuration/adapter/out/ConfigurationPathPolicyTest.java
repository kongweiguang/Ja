// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证配置路径身份只由 Java home 和真实工作区目录派生。 */
final class ConfigurationPathPolicyTest {
    @TempDir
    Path temporaryRoot;

    /** 验证 general workspace 保留 null 身份，PROJECT 不得把它解释成进程 cwd。 */
    @Test
    void generalWorkspaceCannotResolveProjectConfiguration() throws IOException {
        ConfigurationPathPolicy policy = new ConfigurationPathPolicy(temporaryRoot.resolve("home"));
        assertNull(policy.canonicalWorkspace(null));
        assertEquals(temporaryRoot.resolve("home").resolve("config.toml").toAbsolutePath().normalize(),
                policy.configurationPath(ConfigurationScope.USER, null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.configurationPath(ConfigurationScope.PROJECT, null));
    }

    /** 验证相对路径和普通文件都不能成为工作区身份，防止 cwd 与文件类型被隐式兼容。 */
    @Test
    void workspaceIdentityRequiresExistingAbsoluteDirectory() throws IOException {
        ConfigurationPathPolicy policy = new ConfigurationPathPolicy(temporaryRoot.resolve("home"));
        assertThrows(IOException.class, () -> policy.canonicalWorkspace(Path.of("relative")));
        Path file = Files.writeString(temporaryRoot.resolve("workspace.txt"), "not-a-directory");
        assertThrows(IOException.class, () -> policy.canonicalWorkspace(file));

        Path workspace = Files.createDirectory(temporaryRoot.resolve("workspace"));
        Path canonical = policy.canonicalWorkspace(workspace);
        assertEquals(canonical.resolve(".ja").resolve("config.toml"),
                policy.configurationPath(ConfigurationScope.PROJECT, canonical));
    }
}
