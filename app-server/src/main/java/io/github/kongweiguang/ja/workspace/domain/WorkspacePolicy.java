// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 集中定义稳定身份、展示名与通用工作区信任约束，避免各入站适配器重复决策。
 */
public final class WorkspacePolicy {
    private static final int IDENTITY_BYTES = 16;
    private static final int MAX_DISPLAY_NAME = 1_024;

    /**
     * 纯策略没有可变依赖，显式构造便于应用服务统一注入并在测试中复用。
     */
    public WorkspacePolicy() {
    }

    /**
     * 仅对已经验证的规范物理根目录做 SHA-256，保证重启后仍得到相同 opaque ID。
     */
    public String workspaceId(Path canonicalRoot) {
        Path root = Objects.requireNonNull(canonicalRoot, "canonicalRoot").toAbsolutePath().normalize();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(root.toString().getBytes(StandardCharsets.UTF_8));
            return "ws_" + HexFormat.of().formatHex(digest, 0, IDENTITY_BYTES);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("Java 25 must provide SHA-256", impossible);
        }
    }

    /**
     * 使用显式名称或目录末段；文件系统根没有末段时回退到完整根表示。
     */
    public String displayName(Path canonicalRoot, String requestedName) {
        Path root = Objects.requireNonNull(canonicalRoot, "canonicalRoot").toAbsolutePath().normalize();
        String value = requestedName;
        if (value == null) {
            Path fileName = root.getFileName();
            value = fileName == null ? root.toString() : fileName.toString();
        }
        if (value.isBlank() || value.length() > MAX_DISPLAY_NAME || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid workspace display name");
        }
        return value;
    }

    /**
     * 通用工作区固定为受信任状态，因为它从不加载项目覆盖层。
     */
    public Workspace.Trust initialTrust(WorkspaceDirectory.Kind kind) {
        Objects.requireNonNull(kind, "kind");
        return kind == WorkspaceDirectory.Kind.GENERAL
                ? Workspace.Trust.TRUSTED : Workspace.Trust.UNTRUSTED;
    }
}
