// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.util.Objects;

/**
 * Provider 原生 Tool call identity 在 Ja 边界的校验规则；值本身始终原样保留。
 */
public final class JaToolCallId {
    private static final int MAX_LENGTH = 128;

    /**
     * 防止规则类被实例化，调用方共享同一静态校验边界。
     */
    private JaToolCallId() {
    }

    /**
     * 验证跨端共享的有界 ASCII opaque ID，不给供应商 ID 添加 Ja 前缀或重写内容。
     */
    public static String require(String value) {
        Objects.requireNonNull(value, "callId");
        if (value.length() > MAX_LENGTH || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("invalid callId");
        }
        return value;
    }
}
