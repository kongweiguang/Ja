// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

/**
 * Tool Schema 或参数非法时使用的有界本地失败，不携带第三方诊断及调用方数据。
 */
public final class ToolSchemaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * 禁用 cause 与可写堆栈，避免 Provider SDK、Schema 路径或参数正文进入 RPC 错误和日志。
     */
    public ToolSchemaException(String message) {
        super(message, null, false, false);
    }
}
