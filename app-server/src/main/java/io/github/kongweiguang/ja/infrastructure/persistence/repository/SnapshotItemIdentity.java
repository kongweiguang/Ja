// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 历史快照的公开身份编码；重答入口必须用同一函数把 wire 引用映射回数据库候选。 */
final class SnapshotItemIdentity {
    /** 只提供一致的静态身份编码，不允许持有快照实例状态。 */
    private SnapshotItemIdentity() { }

    /** 类型前缀避免不同持久表复用主键时在 UI item namespace 内发生碰撞。 */
    static String of(String sourceKind, String sourceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceKind.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(sourceId.getBytes(StandardCharsets.UTF_8));
            return "item_" + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
