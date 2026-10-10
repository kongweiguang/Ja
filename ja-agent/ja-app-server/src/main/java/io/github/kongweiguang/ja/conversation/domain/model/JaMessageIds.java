// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 将 Core 的不透明消息身份投影为 Ja 时间线协议要求的稳定 item 身份。
 */
public final class JaMessageIds {
    private static final String ITEM_PREFIX = "item_";
    private static final int MAX_SOURCE_LENGTH = 256;

    /**
     * 该类型仅提供稳定的静态投影规则，不应作为具有运行状态的对象创建。
     */
    private JaMessageIds() {
    }

    /**
     * 保留数据库中既有合法 item 身份；其他 Core 身份按完整 UTF-8 值散列，避免改变通用事实或制造非法 UI ID。
     */
    public static String toItemId(String genericId) {
        Objects.requireNonNull(genericId, "genericId");
        if (genericId.isBlank() || genericId.length() > MAX_SOURCE_LENGTH || genericId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid generic message identity");
        }
        if (isItemId(genericId)) {
            return genericId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(genericId.getBytes(StandardCharsets.UTF_8));
            return ITEM_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 只原样保留符合现有 JA-RPC item 语法的历史身份，其他 item 前缀值按通用 ID 处理。
     */
    private static boolean isItemId(String value) {
        return value.startsWith(ITEM_PREFIX) && value.length() <= 108
               && value.substring(ITEM_PREFIX.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }
}
