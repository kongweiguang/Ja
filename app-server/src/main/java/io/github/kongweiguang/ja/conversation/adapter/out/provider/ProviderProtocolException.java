// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider;

import java.time.Duration;
import java.util.Optional;

/**
 * 主动排除响应正文、凭据和不透明推理状态的受限 Provider 异常。
 */
public final class ProviderProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean retryable;
    private final Duration retryAfter;

    /**
     * 保持诊断稳定且脱敏，同时保留提交前重试门禁所需标记。
     */
    public ProviderProtocolException(String code, String message, boolean retryable) {
        this(code, message, retryable, null, null);
    }

    /**
     * 原始 cause 仅用于本地异常链，公开消息始终受限。
     */
    public ProviderProtocolException(String code, String message, boolean retryable, Throwable cause) {
        this(code, message, retryable, cause, null);
    }

    /**
     * 仅捕获受限 Retry-After 时长，Header 和原始响应数据绝不外泄。
     */
    public ProviderProtocolException(String code, String message, boolean retryable, Duration retryAfter) {
        this(code, message, retryable, null, retryAfter);
    }

    /**
     * 为传输 cause 和服务端退避提示初始化脱敏故障状态。
     */
    private ProviderProtocolException(String code, String message, boolean retryable,
                                      Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.code = requireSafeCode(code);
        this.retryable = retryable;
        if (retryAfter != null && (retryAfter.isNegative() || retryAfter.compareTo(Duration.ofSeconds(60)) > 0)) {
            throw new IllegalArgumentException("retryAfter is outside the transport bound");
        }
        this.retryAfter = retryAfter;
    }

    /**
     * 返回机器稳定类别，且不暴露 Provider 响应内容。
     */
    public String code() {
        return code;
    }

    /**
     * 使重试策略可区分瞬时传输故障和畸形 Provider 输出。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 返回服务端延迟提示，且不暴露原始 Header 值。
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * 拒绝攻击者可控 code，使日志可安全索引该值。
     */
    private static String requireSafeCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("provider error code must be a safe identifier");
        }
        return value;
    }
}
