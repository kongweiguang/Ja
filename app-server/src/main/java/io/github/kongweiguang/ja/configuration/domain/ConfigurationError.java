// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.domain;

import java.util.Objects;

/**
 * 配置域的稳定失败；仅携带脱敏分类和有界消息，不暴露文件、TOML、ACL 或 Secret。
 */
public final class ConfigurationError extends RuntimeException {
    /**
     * 配置用例与出站适配器共享的失败分类。
     */
    public enum Code {
        /**
         * 调用参数缺失或不满足配置域约束。
         */
        INVALID_ARGUMENT,

        /**
         * 工作区路径不能解析为真实绝对目录。
         */
        INVALID_CWD,

        /**
         * 权威文件读写或安全校验失败。
         */
        IO_FAILURE,

        /**
         * 配置文档语法或严格语义损坏。
         */
        CORRUPT_CONFIG,

        /**
         * 凭据文件语法、ACL 或身份校验损坏。
         */
        CORRUPT_AUTH,

        /**
         * 调用方提供的 CAS 版本已过期。
         */
        CAS_CONFLICT,

        /**
         * 项目配置操作缺少工作区信任。
         */
        UNTRUSTED_WORKSPACE,

        /**
         * 项目配置尝试扩大用户层权限或资源上限。
         */
        LIMIT_ESCALATION,

        /**
         * 配置文档包含不允许持久化的明文 Secret。
         */
        LITERAL_SECRET,

        /**
         * 文档字段、类型、标识或引用不符合严格 Schema。
         */
        INVALID_DOCUMENT,

        /**
         * 配置代际缺少请求指定的 Provider 或其下属 Model。
         */
        MISSING_PROVIDER_OR_MODEL,

        /**
         * 配置代际缺少 Provider 引用的凭据。
         */
        MISSING_CREDENTIAL,

        /**
         * 存储身份在受保护操作期间发生并发替换。
         */
        STORAGE_CONFLICT
    }

    private final Code code;

    /**
     * 固定稳定分类并清理消息，确保异常跨适配器边界时仍不泄露敏感细节。
     */
    public ConfigurationError(Code code, String message) {
        super(safeMessage(message));
        this.code = Objects.requireNonNull(code, "code");
    }

    /**
     * 丢弃原始 cause，仅保留分类和脱敏消息，避免异常链泄露本地路径或配置正文。
     */
    public ConfigurationError(Code code, String message, Throwable ignoredCause) {
        this(code, message);
    }

    /**
     * 返回可由入站适配器穷举映射的稳定失败分类。
     */
    public Code code() {
        return code;
    }

    /**
     * 清理常见敏感字段并限制长度，避免诊断消息成为数据外泄通道。
     */
    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) return "configuration operation failed";
        String normalized = message.replaceAll(
                "(?i)(api[_-]?key|token|secret|password|authorization|credential)[^,; ]*",
                "$1=<redacted>");
        return normalized.length() > 160 ? normalized.substring(0, 160) : normalized;
    }

    /**
     * 只输出稳定分类和已经清理的消息，不拼接 cause 或本地状态。
     */
    @Override
    public String toString() {
        return "ConfigurationError[code=" + code + ", message=" + getMessage() + "]";
    }
}
