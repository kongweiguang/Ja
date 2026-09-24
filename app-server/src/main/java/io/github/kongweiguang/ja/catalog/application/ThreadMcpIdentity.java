// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** 会话 MCP 观测身份放在共享领域层，避免出站运行时依赖入站用例。 */
public final class ThreadMcpIdentity {
    /** 工具类不持有会话状态，所有输入均由调用方冻结。 */
    private ThreadMcpIdentity() { }

    /** 把影响 MCP 暴露的偏好编码为不泄露原值的稳定身份。 */
    public static String preferenceFingerprint(ThreadPreferences preferences) {
        Objects.requireNonNull(preferences, "preferences");
        StringBuilder canonical = new StringBuilder();
        append(canonical, preferences.providerId());
        append(canonical, preferences.modelId());
        append(canonical, preferences.reasoningLevel());
        append(canonical, preferences.accessMode().name());
        append(canonical, preferences.collaborationMode().name());
        return sha256(canonical.toString());
    }

    /** 只比较能力上限的规范摘要，不把上限正文投影到会话状态。 */
    public static String ceilingFingerprint(JsonObject ceiling) {
        return ceiling == null ? null : sha256(AgentTool.canonicalSchema(ceiling));
    }

    /** 长度前缀防止相邻字段拼接后碰撞。 */
    private static void append(StringBuilder target, String value) {
        if (value == null) target.append("-1:");
        else target.append(value.length()).append(':').append(value);
    }

    /** 使用固定 SHA-256，使不同边界比较的是同一种摘要。 */
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
