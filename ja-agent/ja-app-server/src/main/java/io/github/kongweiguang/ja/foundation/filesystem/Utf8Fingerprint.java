// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.filesystem;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 以固定 UTF-8 对内存文本或文件做分段计量和 SHA-256，避免为大正文创建第二份完整字节数组。
 *
 * @param byteLength 数据长度，单位为字节
 * @param sha256 内容的 SHA-256 摘要
 */
public record Utf8Fingerprint(long byteLength,
                              String sha256) {
    private static final int BUFFER_BYTES = 8 * 1024;
    private static final int WRITE_CHARACTERS = 64 * 1024;

    /**
     * 校验可持久化的字节长度与摘要形状，防止调用点传入不完整证据。
     */
    public Utf8Fingerprint {
        if (byteLength < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid UTF-8 fingerprint");
        }
    }

    /**
     * 编码期间由本方法持有计数流；Encoder 关闭写入端后仍可读取计数而不保留正文副本。
     */
    public static Utf8Fingerprint of(String value) {
        Objects.requireNonNull(value, "value");
        MessageDigest digest = sha256Digest();
        try (CountingOutputStream counted = new CountingOutputStream()) {
            // Encoder 包装流会先关闭计数器；外层 try-with-resources 明确保留该资源的创建者所有权。
            encodeText(value, counted, digest);
            return new Utf8Fingerprint(counted.count(), HexFormat.of().formatHex(digest.digest()));
        }
    }

    /**
     * 分段读取已发布文件，计算实际落盘字节摘要，不将文件正文读入内存。
     */
    public static Utf8Fingerprint of(Path path) throws IOException {
        Path file = Objects.requireNonNull(path, "path");
        MessageDigest digest = sha256Digest();
        long count = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                count = Math.addExact(count, read);
            }
        }
        return new Utf8Fingerprint(count, HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * 只计量文本 UTF-8 长度；显式关闭本地计数流以保持 Writer 与调用方的所有权边界一致。
     */
    public static long byteLength(String value) {
        Objects.requireNonNull(value, "value");
        try (CountingOutputStream counted = new CountingOutputStream()) {
            // 计数流没有外部资源，但与摘要路径采用相同的显式生命周期边界。
            encodeText(value, counted, null);
            return counted.count();
        }
    }

    /**
     * 以同一 UTF-8 Encoder 写入计数器，可选同时更新摘要，兼容 Java 的替代字符处理。
     */
    private static void encodeText(String value, CountingOutputStream counted, MessageDigest digest) {
        OutputStream output = digest == null ? counted : new DigestOutputStream(counted, digest);
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writeText(writer, value);
        } catch (IOException impossible) {
            throw new IllegalStateException("in-memory UTF-8 encoding failed", impossible);
        }
    }

    /**
     * 使用固定大小的字符片段交给 UTF-8 Encoder，避免 Writer 接收超大单次写入。
     */
    private static void writeText(Writer writer, String value) throws IOException {
        for (int offset = 0; offset < value.length(); ) {
            int end = Math.min(value.length(), offset + WRITE_CHARACTERS);
            if (end < value.length() && end > offset
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
                end--;
            }
            writer.write(value, offset, end - offset);
            offset = end;
        }
    }

    /**
     * 创建进程必备的 SHA-256；缺失算法属于运行时不变量损坏。
     */
    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 计量 Encoder 实际写出的 UTF-8 字节数，不缓存字节内容。
     */
    private static final class CountingOutputStream extends OutputStream {
        private long count;

        /**
         * 字节计数器不持有外部资源。
         */
        private CountingOutputStream() {
        }

        /**
         * 累加编码器写出的单字节。
         */
        @Override
        public void write(int value) {
            count = Math.addExact(count, 1L);
        }

        /**
         * 累加编码器的一段输出，同时验证数组边界。
         */
        @Override
        public void write(byte[] value, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, value.length);
            count = Math.addExact(count, length);
        }

        /**
         * 计数器不持有底层资源，关闭只结束调用方生命周期而无需执行清理工作。
         */
        @Override
        public void close() {
        }

        /**
         * 返回分段编码到目前为止的字节数。
         */
        private long count() {
            return count;
        }
    }
}
