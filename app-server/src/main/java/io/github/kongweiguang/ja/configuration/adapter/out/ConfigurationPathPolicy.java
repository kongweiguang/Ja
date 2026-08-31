// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out;

import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 集中约束配置路径身份，避免文件适配器和应用服务各自解释工作区路径。
 */
public final class ConfigurationPathPolicy {
    private final Path userConfigPath;
    private final Path credentialPath;
    private final Path trustPath;

    /**
     * 从唯一 Java home 派生受控文件，调用方不得单独注入任意目标路径。
     */
    public ConfigurationPathPolicy(Path homeDirectory) {
        Path home = Objects.requireNonNull(homeDirectory, "homeDirectory")
                .toAbsolutePath().normalize();
        this.userConfigPath = home.resolve("config.toml");
        this.credentialPath = home.resolve("auth.json");
        this.trustPath = home.resolve("trusted-workspaces.json");
    }

    /**
     * 只接受已存在目录的真实绝对路径，防止相对 cwd 或符号链接竞态改变工作区身份。
     */
    public Path canonicalWorkspace(Path workspace) throws IOException {
        if (workspace == null) {
            return null;
        }
        if (!workspace.isAbsolute()) {
            throw new IOException("workspace_identity_invalid");
        }
        Path real = workspace.toRealPath();
        if (!Files.isDirectory(real)) {
            throw new IOException("workspace_identity_invalid");
        }
        return real;
    }

    /**
     * 根据领域作用域解析唯一配置文件；PROJECT 必须携带已规范化工作区。
     */
    public Path configurationPath(ConfigurationScope scope, Path canonicalWorkspace) {
        Objects.requireNonNull(scope, "scope");
        if (scope == ConfigurationScope.USER) {
            return userConfigPath;
        }
        if (canonicalWorkspace == null) {
            throw new IllegalArgumentException("project_workspace_required");
        }
        return canonicalWorkspace.resolve(".ja").resolve("config.toml");
    }

    /**
     * 返回 Watcher 使用的用户配置路径，不允许外部传入替代位置。
     */
    public Path userConfigPath() {
        return userConfigPath;
    }

    /**
     * 返回凭据 CAS 协调所使用的权威文件身份。
     */
    public Path credentialPath() {
        return credentialPath;
    }

    /**
     * 返回工作区信任注册表的权威文件身份。
     */
    public Path trustPath() {
        return trustPath;
    }
}
