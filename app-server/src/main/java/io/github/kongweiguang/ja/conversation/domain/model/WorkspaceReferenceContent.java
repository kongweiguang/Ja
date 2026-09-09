// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/** 当前 Thread Workspace 内一个尚未预加载的文件或目录引用。 */
public record WorkspaceReferenceContent(String workspaceId, String relativePath,
                                        Kind kind) implements UserContentBlock {
    /**
     * 这里只固定 Wire 形状；canonical containment、链接和最终类型必须由 Workspace owner
     * 在消息准入及实际消费前重新验证，不能在 conversation domain 复制文件系统策略。
     */
    public WorkspaceReferenceContent {
        workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        if (!workspaceId.startsWith("ws_")) throw new IllegalArgumentException("invalid workspaceId");
        relativePath = ContractChecks.text(relativePath, "relativePath", 4_096, false).replace('\\', '/');
        if (relativePath.startsWith("/") || relativePath.matches("^[A-Za-z]:.*")
                || relativePath.equals("..") || relativePath.startsWith("../")
                || relativePath.contains("/../") || relativePath.endsWith("/..")) {
            throw new IllegalArgumentException("invalid relativePath");
        }
        Objects.requireNonNull(kind, "kind");
    }

    /** 文件与目录必须显式区分，消费时不根据扩展名猜测。 */
    public enum Kind {
        /** 指向单个未预读正文的 Workspace 文件。 */
        FILE,
        /** 指向未展开子项的 Workspace 目录。 */
        DIRECTORY
    }
}
