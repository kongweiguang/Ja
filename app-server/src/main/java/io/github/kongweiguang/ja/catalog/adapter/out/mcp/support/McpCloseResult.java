// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.support;

import java.util.List;

/**
 * 一次 MCP 传输或 Runtime 关闭尝试的脱敏不可变证据。
 */
public record McpCloseResult(List<String> failures) {
    /**
     * 只复制稳定本地失败代码，防止迟到清理修改已发布结果。
     */
    public McpCloseResult {
        failures = List.copyOf(failures);
    }

    /**
     * 表示传输在所属 Deadline 前成功关闭。
     */
    public static McpCloseResult success() {
        return new McpCloseResult(List.of());
    }

    /**
     * 表示一个稳定本地失败，不保留外部异常或消息。
     */
    public static McpCloseResult failure(String code) {
        return new McpCloseResult(List.of(code));
    }

    /**
     * 在每个清理所有权边界显式检查结果。
     */
    public boolean failed() {
        return !failures.isEmpty();
    }

    /**
     * 重建类型化外层错误，同时保留每个独立稳定失败代码。
     */
    public IllegalStateException asRuntimeFailure() {
        IllegalStateException failure = new IllegalStateException("mcp_runtime_close_failed");
        for (String code : failures) {
            failure.addSuppressed(new IllegalStateException(code));
        }
        return failure;
    }
}
