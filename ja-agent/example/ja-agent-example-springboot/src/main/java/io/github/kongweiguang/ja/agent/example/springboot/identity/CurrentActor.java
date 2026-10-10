// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.identity;

import java.util.Objects;

/**
 * 已由服务端认证边界确定的租户和用户，不接受请求正文中的身份替代值。
 *
 * @param tenantId 当前租户标识
 * @param userId 当前用户标识
 */
public record CurrentActor(String tenantId,
                           String userId) {
    /** 限定宿主身份能安全进入 Core owner key 与 SQL 参数。 */
    public CurrentActor {
        tenantId = identityPart(tenantId, "tenantId");
        userId = identityPart(userId, "userId");
    }

    /** 拒绝空白和控制字符，避免 owner 身份在不同存储边界出现歧义。 */
    private static String identityPart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value;
    }
}
