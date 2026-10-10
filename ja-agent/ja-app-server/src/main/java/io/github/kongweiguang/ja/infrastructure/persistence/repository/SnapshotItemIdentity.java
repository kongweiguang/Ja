// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 历史快照的公开身份编码；重答入口必须用同一函数把 wire 引用映射回数据库候选。
 */
final class SnapshotItemIdentity {
    /**
     * 只提供一致的静态身份编码，不允许持有快照实例状态。
     */
    private SnapshotItemIdentity() {
    }

    /**
     * 消息沿用实时事件和准入的身份；其它表保留类型前缀，避免历史读取破坏重答来源或产生跨表碰撞。
     */
    static String of(String sourceKind, String sourceId) {
        if ("message".equals(sourceKind)) {
            return JaMessageIds.toItemId(sourceId);
        }
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
