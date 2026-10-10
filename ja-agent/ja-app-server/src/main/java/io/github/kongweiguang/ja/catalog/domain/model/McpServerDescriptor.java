// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.domain.model;

import java.util.Objects;

/**
 * 表示已脱敏的 MCP 服务健康投影。
 *
 * @param mcpId MCP 标识
 * @param name 名称
 * @param scope 适用范围
 * @param transport 传输
 * @param status 当前状态分类
 * @param toolCount 工具数量
 */
public record McpServerDescriptor(String mcpId,
                                  String name,
                                  String scope,
                                  String transport,
                                  String status,
                                  int toolCount) {
    /**
     * 禁止 endpoint、认证和启动参数进入公开目录值。
     */
    public McpServerDescriptor {
        Objects.requireNonNull(mcpId, "mcpId");
        Objects.requireNonNull(name, "name");
        if (!"global".equals(scope) && !"project".equals(scope)) {
            throw new IllegalArgumentException("invalid MCP scope");
        }
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(status, "status");
        if (toolCount < 0) {
            throw new IllegalArgumentException("invalid MCP tool count");
        }
    }
}
