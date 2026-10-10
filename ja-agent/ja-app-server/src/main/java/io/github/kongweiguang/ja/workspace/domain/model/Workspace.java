// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 表示已经持久化并可由宿主重新绑定原生能力的工作区。
 *
 * @param workspaceId 工作区稳定标识
 * @param root 根
 * @param displayName 面向用户的显示名称
 * @param trust 信任状态
 * @param kind 类型分类
 * @param revision 当前修订版本
 */
public record Workspace(String workspaceId,
                        Path root,
                        String displayName,
                        Trust trust,
                        Kind kind,
                        long revision) {
    /**
     * 规范化根目录并在进入应用端口前拒绝无效身份与版本。
     */
    public Workspace {
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        validateFields(workspaceId, displayName, trust, kind);
        if (revision < 0) {
            throw new IllegalArgumentException("invalid workspace revision");
        }
    }

    /**
     * 实体与注册命令共享相同身份约束，避免两个持久化入口随演进出现不同的信任边界。
     */
    private static void validateFields(
            String workspaceId, String displayName, Trust trust, Kind kind) {
        identifier(workspaceId);
        bounded(displayName, "displayName", 1_024);
        Objects.requireNonNull(trust, "trust");
        Objects.requireNonNull(kind, "kind");

    }

    /**
     * 集中校验工作区 ID，避免 transport 校验规则渗入领域模型。
     */
    private static String identifier(String value) {
        if (value == null || !value.matches("ws_[A-Za-z0-9_-]{1,97}")) {
            throw new IllegalArgumentException("invalid workspace identity");
        }
        return value;
    }

    /**
     * 对持久化展示文本设置稳定上限，避免任一 adapter 采用不同截断策略。
     */
    private static String bounded(String value, String field, int maximum) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * Workspace 的持久类型决定目录如何重开以及是否允许项目配置覆盖。
     */
    public enum Kind {
        /**
         * 用户明确打开的项目根，可以启用项目级配置覆盖。
         */
        PROJECT,
        /**
         * 由 Ja 按主会话身份创建的独立工作目录。
         */
        SESSION
    }

    /**
     * 工作区信任状态只允许协议与配置共同支持的闭集。
     */
    public enum Trust {
        /**
         * 允许读取并应用项目级配置。
         */
        TRUSTED,
        /**
         * 只使用用户级配置，忽略项目配置。
         */
        UNTRUSTED
    }

    /**
     * 工作区注册命令只携带宿主验证后的物理根目录，不负责创建或修改目录。
     *
     * @param workspaceId 工作区稳定标识
     * @param root 根
     * @param displayName 面向用户的显示名称
     * @param trust 信任状态
     * @param kind 类型分类
     * @param occurredAt 事件发生时间
     */
    public record Registration(String workspaceId,
                               Path root,
                               String displayName,
                               Trust trust,
                               Kind kind,
                               Instant occurredAt) {
        /**
         * 冻结注册事实，确保持久化适配器收到的路径和时间不可再被调用方替换。
         */
        public Registration {
            root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
            validateFields(workspaceId, displayName, trust, kind);
            Objects.requireNonNull(occurredAt, "occurredAt");
        }
    }
}
