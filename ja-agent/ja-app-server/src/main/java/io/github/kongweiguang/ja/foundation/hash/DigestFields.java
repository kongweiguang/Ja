// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Ja 持久身份摘要共用的无歧义 UTF-8 字段编码。
 */
public final class DigestFields {
    private static final byte[] NULL_LENGTH = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

    /**
     * 纯静态编码规则没有可变状态，也不应被实例化。
     */
    private DigestFields() {
    }

    /**
     * 追加一个字段：null 使用保留的四字节哨兵，文本使用四字节大端 UTF-8 长度再写内容。
     * 该格式让多个字段可无歧义拼接，并保持已有 Ja 指纹的字节合同。
     */
    public static void appendUtf8(MessageDigest digest, String value) {
        MessageDigest target = Objects.requireNonNull(digest, "digest");
        if (value == null) {
            target.update(NULL_LENGTH);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        target.update((byte) (bytes.length >>> 24));
        target.update((byte) (bytes.length >>> 16));
        target.update((byte) (bytes.length >>> 8));
        target.update((byte) bytes.length);
        target.update(bytes);
    }
}
