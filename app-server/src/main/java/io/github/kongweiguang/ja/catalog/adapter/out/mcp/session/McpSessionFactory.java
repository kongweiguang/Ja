// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.session;

import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpServerDefinition;

import java.util.Map;

/**
 * 从当前请求已解析的私有定义创建可独立关闭的 MCP Session。
 */
@FunctionalInterface
public interface McpSessionFactory {
    /**
     * 在调用方不可变生命周期 Deadline 内打开传输，不重新读取配置。
     */
    McpSession open(McpServerDefinition definition, McpDeadline deadline);

    /**
     * 为支持动态目录的生产实现注入无阻塞失效回调；测试和纯探测实现可沿用二参数工厂。
     */
    default McpSession open(
            McpServerDefinition definition, McpDeadline deadline, Runnable toolsChanged) {
        return open(definition, deadline);
    }

    /**
     * Turn 私有目录把冻结的原生客户端环境一路带到 stdio 创建点；
     * 测试替身和非 stdio 探测仍可复用无环境重载，生产实现必须覆盖此入口。
     */
    default McpSession open(
            McpServerDefinition definition, McpDeadline deadline, Runnable toolsChanged,
            Map<String, String> hostEnvironment) {
        java.util.Objects.requireNonNull(hostEnvironment, "hostEnvironment");
        return open(definition, deadline, toolsChanged);
    }
}
