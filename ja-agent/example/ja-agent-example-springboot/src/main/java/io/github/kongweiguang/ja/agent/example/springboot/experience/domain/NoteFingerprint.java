// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 统一生成审批、持久化和恢复共用的规范参数指纹。 */
public final class NoteFingerprint {
    /** 阻止工具类实例绕过统一的静态指纹入口。 */
    private NoteFingerprint() {
    }

    /**
     * 长度前缀消除字段分隔符歧义，并保留来源顺序，使所有副作用边界核验同一组参数。
     */
    public static String of(String title, String body, List<String> sourceAttachmentIds) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        List<String> sources = List.copyOf(Objects.requireNonNull(sourceAttachmentIds, "sourceAttachmentIds"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, title);
            update(digest, body);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(sources.size()).array());
            sources.forEach(source -> update(digest, source));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 编码每个字段的精确字节长度，避免拼接后出现内容边界碰撞。
     */
    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
