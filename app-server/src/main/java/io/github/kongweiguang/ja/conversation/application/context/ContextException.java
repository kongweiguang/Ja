// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.context;

import java.util.Objects;

/**
 * 将预算、摘要和 Checkpoint 失败收敛为应用层稳定分类，避免上层解析异常文本。
 */
public final class ContextException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Code code;

    /**
     * 创建无底层原因的上下文失败，并强制调用方选择稳定错误分类。
     */
    public ContextException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 包装底层失败但保留稳定分类，使诊断信息不改变应用层分支语义。
     */
    public ContextException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 返回供恢复状态机判定重试策略的稳定错误分类。
     */
    public Code code() {
        return code;
    }

    /**
     * 上下文构建与压缩只向调用方暴露的稳定失败分类。
     */
    public enum Code {
        /**
         * Checkpoint 源 revision 已被并发提交推进。
         */
        CAS_CONFLICT,
        /**
         * 保留区与输出预算使上下文无法继续装配。
         */
        CONTEXT_LIMIT,
        /**
         * Provider 官方输入计量不可用；该失败发生时禁止发送模型请求。
         */
        TOKEN_COUNT_UNAVAILABLE,
        /**
         * 摘要模型或摘要结果未满足压缩契约。
         */
        SUMMARY_FAILURE,
        /**
         * 历史项、Checkpoint 或调用阶段违反状态约束。
         */
        INVALID_STATE
    }
}
