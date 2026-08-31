// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 为 Skill 与 MCP 目录提供一致的、无分隔符歧义的版本摘要。
 */
public final class CatalogRevisionHasher {
    private final MessageDigest digest;

    /**
     * 以固定命名空间创建摘要，防止不同目录材料即使字节相同也复用版本标识。
     */
    public CatalogRevisionHasher(String namespace) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("sha256_unavailable", unavailable);
        }
        append(namespace);
    }

    /**
     * 使用字节长度前缀追加字段，避免字段内容伪造分隔符而产生相同摘要材料。
     */
    public CatalogRevisionHasher append(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
        return this;
    }

    /**
     * 以稳定小写十六进制结束摘要；实例结束后不得复用，以免隐式累积状态。
     */
    public String finish() {
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
