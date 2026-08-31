// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 跨 stdio Adapter 边界传递的稳定脱敏 JA-RPC 失败。
 */
public final class JaRpcException extends RuntimeException {
    private static final long MAX_RETRY_AFTER_MS = 3_600_000L;
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?:[a-z]:[\\\\/]|\\\\\\\\|file://|(?:^|\\s)/(?:[^/\\s]+/)+[^/\\s]*)");
    private static final Pattern URI = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|authorization|password|secret)\\s*[:=]");
    private static final Pattern LONG_HEX = Pattern.compile("(?i)(?:^|[^0-9a-f])[0-9a-f]{32,}(?:$|[^0-9a-f])");

    private final JaErrorCatalog error;
    private final String errorId;
    private final Long retryAfterMs;

    /**
     * 仅允许目录项构造公共错误，避免调用点自行组合 code/category/retryable；每个实例生成独立
     * 关联 ID，使日志关联不需要暴露原始异常、路径或请求体。
     */
    private JaRpcException(JaErrorCatalog error, String message, Long retryAfterMs) {
        super(requirePublicMessage(message), null, false, false);
        if (error == null) throw new IllegalArgumentException("error is required");
        if (retryAfterMs != null && (!error.retryable()
                                     || retryAfterMs < 1 || retryAfterMs > MAX_RETRY_AFTER_MS)) {
            throw new IllegalArgumentException("invalid retry delay");
        }
        this.error = error;
        this.errorId = "err_" + UUID.randomUUID().toString().replace("-", "");
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * 返回 JSON-RPC 数值应用错误码，但不暴露原始异常。
     */
    public int code() {
        return error.code();
    }

    /**
     * 返回 Rust 与 TypeScript 消费的稳定机器判别码。
     */
    public String errorCode() {
        return error.name();
    }

    /**
     * 返回由冻结目录决定的错误分类，调用方不得从展示消息推断。
     */
    public String category() {
        return error.category().wireName();
    }

    /**
     * 仅标记契约允许由调用方控制重试的操作。
     */
    public boolean retryable() {
        return error.retryable();
    }

    /**
     * 返回当前公共错误实例的安全不透明关联 ID。
     */
    public String errorId() {
        return errorId;
    }

    /**
     * 返回调用点显式提供的退避时间；未提供时不在 Wire 输出占位值。
     */
    public OptionalLong retryAfterMs() {
        return retryAfterMs == null ? OptionalLong.empty() : OptionalLong.of(retryAfterMs);
    }

    /**
     * 从冻结目录构造错误，禁止调用点自行组合冲突元组。
     */
    public static JaRpcException of(JaErrorCatalog error, String message) {
        return new JaRpcException(error, message, null);
    }

    /**
     * 仅为确有退避信息的可重试失败构造延迟，禁止按 retryable 推导默认值。
     */
    public static JaRpcException withRetryAfter(JaErrorCatalog error, String message, long retryAfterMs) {
        return new JaRpcException(error, message, retryAfterMs);
    }

    /**
     * 构造严格参数拒绝，错误消息不会反射调用方数据。
     */
    public static JaRpcException invalidParams() {
        return of(JaErrorCatalog.INVALID_PARAMS, "request parameters are invalid");
    }

    /**
     * 构造严格的未知方法错误，同时保持连接可继续使用。
     */
    public static JaRpcException methodNotFound() {
        return of(JaErrorCatalog.METHOD_NOT_FOUND, "method not found");
    }

    /**
     * 构造终止当前 stdio 代际的 fail-closed 帧错误。
     */
    public static JaRpcException invalidFrame() {
        return of(JaErrorCatalog.INVALID_FRAME, "invalid protocol frame");
    }

    /**
     * 拒绝可能包含主机路径、URI、凭据赋值或长 token 的公共 message。
     */
    private static String requirePublicMessage(String message) {
        if (message == null || message.isBlank() || message.length() > 512
            || message.chars().anyMatch(Character::isISOControl)
            || ABSOLUTE_PATH.matcher(message).find() || URI.matcher(message).find()
            || SECRET_ASSIGNMENT.matcher(message).find() || LONG_HEX.matcher(message).find()) {
            throw new IllegalArgumentException("invalid JA-RPC error message");
        }
        return message;
    }
}
