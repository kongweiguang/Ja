// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.shared;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 提供跨重启稳定的 UTF-8 SHA-256 摘要，避免各调用点自行选择字符集或截断方式。
 */
public final class Hashing {
    /**
     * 禁止实例化无状态摘要工具类，确保所有调用都显式使用固定的编码和摘要策略。
     */
    private Hashing() {
    }

    /**
     * 对完整文本计算小写十六进制 SHA-256；固定编码保证 Windows 默认字符集不会改变身份。
     *
     * @param value 要摘要的文本
     * @return 64 个字符的小写摘要
     */
    public static String sha256Hex(String value) {
        Objects.requireNonNull(value, "value");
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 对已经按调用方协议编码的字节计算完整 SHA-256，避免二次字符集转换破坏二进制 framing。
     *
     * @param value 已编码字节
     * @return 64 个字符的小写摘要
     */
    public static String sha256HexBytes(byte[] value) {
        return HexFormat.of().formatHex(digest(Objects.requireNonNull(value, "value")));
    }

    /**
     * 返回摘要的前若干字节，供需要短显示标识的调用点显式表达截断取舍。
     *
     * @param value 要摘要的文本
     * @param byteCount 保留的摘要字节数，必须在 1 到 32 之间
     * @return 小写十六进制截断摘要
     */
    public static String sha256HexPrefix(String value, int byteCount) {
        Objects.requireNonNull(value, "value");
        if (byteCount <= 0 || byteCount > 32) {
            throw new IllegalArgumentException("byteCount must be between 1 and 32");
        }
        byte[] bytes = digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes, 0, byteCount);
    }

    /**
     * 使用每个字段的 UTF-8 字节长度进行 framing，防止拼接字段产生边界碰撞。
     *
     * @param values 有序字段，不能包含 null
     * @return framed 字段的 SHA-256 摘要
     */
    public static String sha256FramedHex(String... values) {
        Objects.requireNonNull(values, "values");
        StringBuilder framed = new StringBuilder();
        for (String value : values) {
            Objects.requireNonNull(value, "value");
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            framed.append(encoded.length).append(':').append(value).append(';');
        }
        return sha256Hex(framed.toString());
    }

    /**
     * 创建 SHA-256 摘要实例；JDK 必须提供该算法，缺失时立即失败而不降级到不稳定摘要。
     *
     * @param value UTF-8 编码后的输入
     * @return 摘要字节
     */
    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
