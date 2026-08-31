// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import java.time.Duration;
import java.util.Objects;

/**
 * 在不可信 MCP 数据到达 Kernel 前应用的不可变资源预算。
 */
public record McpLimits(
        int maxPages,
        int maxTools,
        int maxCursorBytes,
        int maxSchemaBytes,
        int maxResultBytes,
        int maxMessageBytes,
        int maxStderrBytes,
        int outboundQueueCapacity,
        Duration startupTimeout,
        Duration requestTimeout,
        Duration closeTimeout) {
    public static final McpLimits DEFAULT = new McpLimits(
            32,
            256,
            4 * 1024,
            256 * 1024,
            1024 * 1024,
            4 * 1024 * 1024,
            256 * 1024,
            64,
            Duration.ofSeconds(15),
            Duration.ofSeconds(60),
            Duration.ofSeconds(5));

    /**
     * 在组合阶段拒绝不安全预算，防止传输因意外默认值变为无界。
     */
    public McpLimits {
        requireRange(maxPages, 1, 256, "mcp_page_limit_invalid");
        requireRange(maxTools, 1, 4096, "mcp_tool_limit_invalid");
        requireRange(maxCursorBytes, 16, 64 * 1024, "mcp_cursor_limit_invalid");
        requireRange(maxSchemaBytes, 1024, 4 * 1024 * 1024, "mcp_schema_limit_invalid");
        requireRange(maxResultBytes, 1024, 16 * 1024 * 1024, "mcp_result_limit_invalid");
        requireRange(maxMessageBytes, 1024, 16 * 1024 * 1024, "mcp_message_limit_invalid");
        requireRange(maxStderrBytes, 0, 4 * 1024 * 1024, "mcp_stderr_limit_invalid");
        requireRange(outboundQueueCapacity, 1, 4096, "mcp_queue_limit_invalid");
        startupTimeout = requireDuration(startupTimeout, "mcp_startup_timeout_invalid");
        requestTimeout = requireDuration(requestTimeout, "mcp_request_timeout_invalid");
        closeTimeout = requireDuration(closeTimeout, "mcp_close_timeout_invalid");
    }

    /**
     * 集中整数边界，使每项资源预算都以稳定代码失败关闭。
     */
    private static void requireRange(int value, int minimum, int maximum, String code) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(code);
        }
    }

    /**
     * 限制 Deadline，因为过长超时在运行语义上等同于无边界。
     */
    private static Duration requireDuration(Duration value, String code) {
        Objects.requireNonNull(value, code);
        if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(code);
        }
        return value;
    }
}
