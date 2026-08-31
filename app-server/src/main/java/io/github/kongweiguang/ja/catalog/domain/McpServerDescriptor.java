// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.domain;

import java.util.Objects;

/**
 * 表示已脱敏的 MCP 服务健康投影。
 */
public record McpServerDescriptor(String mcpId, String name, String transport, String status,
                                  int toolCount) {
    /**
     * 禁止 endpoint、认证和启动参数进入公开目录值。
     */
    public McpServerDescriptor {
        Objects.requireNonNull(mcpId, "mcpId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(status, "status");
        if (toolCount < 0) throw new IllegalArgumentException("invalid MCP tool count");
    }
}
