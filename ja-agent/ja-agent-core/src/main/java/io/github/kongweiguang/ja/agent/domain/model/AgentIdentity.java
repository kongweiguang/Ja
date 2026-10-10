// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 核心端口共用的稳定租户、用户、Thread 与 Turn 身份。
 *
 * @param tenantId 租户标识，用于隔离不同租户的数据与执行
 * @param threadId 所属对话的稳定标识
 * @param turnId   本次可恢复执行的稳定标识
 * @param userId   发起执行的用户标识
 */
public record AgentIdentity(String tenantId,
                            String threadId,
                            String turnId,
                            String userId) {
    /**
     * 边界处要求全部身份维度，适配器不得猜测租户或执行归属。
     */
    public AgentIdentity {
        tenantId = ContractChecks.identifier(tenantId, "tenantId");
        threadId = ContractChecks.identifier(threadId, "threadId");
        turnId = ContractChecks.identifier(turnId, "turnId");
        userId = ContractChecks.identifier(userId, "userId");
    }

    /**
     * 基于完整执行身份生成稳定工具幂等键，使工具可在进程恢复后核验同一外部动作。
     */
    public String toolCallKey(String callId) {
        String call = ContractChecks.identifier(callId, "callId");
        String material = String.join("\0", tenantId, threadId, turnId, userId, call);
        return "call_" + sha256Hex(material);
    }

    /**
     * 域身份不能依赖共享工具包；直接使用 JDK 标准算法保持原有 UTF-8、全长小写 SHA-256 结果。
     */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
