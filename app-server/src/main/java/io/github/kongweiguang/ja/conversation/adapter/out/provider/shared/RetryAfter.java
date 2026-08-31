// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.shared;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 解析受限 Retry-After 提示，不暴露 Header 或 Provider 专属文本。
 */
public final class RetryAfter {
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    /**
     * 仅保留静态 Policy，禁止任何可变解析状态跨越响应回调。
     */
    private RetryAfter() {
    }

    /**
     * 接受 RFC 秒数或 HTTP-date，钳制恶意值并拒绝畸形提示，使 Adapter 回退到自身
     * 有界 jitter 退避。
     */
    public static Duration parse(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) return null;
            return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
        } catch (NumberFormatException ignored) {
            // HTTP-date 是 Retry-After 文档定义的第二种形式。
        }
        try {
            Instant target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration remaining = Duration.between(Instant.now(), target);
            if (remaining.isNegative()) return Duration.ZERO;
            return remaining.compareTo(MAX_DELAY) > 0 ? MAX_DELAY : remaining;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
