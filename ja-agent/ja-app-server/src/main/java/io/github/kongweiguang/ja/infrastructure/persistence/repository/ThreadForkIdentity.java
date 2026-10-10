// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import io.github.kongweiguang.ja.conversation.domain.model.JaToolCallId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 为每个 child 的只读历史建立独立展示身份，同时让重复读取和多代 fork 可稳定重映射。
 */
final class ThreadForkIdentity {
    /**
     * 投影器无状态；禁止实例化可避免调用方误以为存在需跨请求维护的映射生命周期。
     *
     * @author kongweiguang
     */
    private ThreadForkIdentity() {
    }

    /**
     * item 和全局 keyed timeline 共用的展示 ID 以 child namespace 隔离，避免源会话与 child 相互覆盖。
     */
    static String itemId(String childThreadId, String sourceItemId) {
        return "item_" + digest(childThreadId, sourceItemId);
    }

    /**
     * 展示 Turn 仍须在同代所有历史项间共享映射，但不得复用来源 Thread 的全局 Turn key。
     */
    static String turnId(String childThreadId, String sourceTurnId) {
        return "turn_" + digest(childThreadId, sourceTurnId);
    }

    /**
     * Provider call identity 虽由 Thread 和 Turn 联合存储，继承投影仍隔离以免展示关联撞上原审批。
     */
    static String callId(String childThreadId, String sourceTurnId, String sourceCallId) {
        return JaToolCallId.require("fork_" + digest(childThreadId, sourceTurnId, sourceCallId));
    }

    /**
     * Approval ID 是跨线程全局 key；冻结副本生成稳定 child-local 标识但不复制审批控制权。
     */
    static String approvalId(String childThreadId, String sourceApprovalId) {
        return "approval_" + digest(childThreadId, sourceApprovalId);
    }

    /**
     * 对有序身份元组散列而非拼接原值，防止分隔符、超长或不透明 ID 造成映射歧义。
     */
    private static String digest(String... values) {
        Objects.requireNonNull(values, "values");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value, "identity component").getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
