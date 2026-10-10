// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway.store;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.util.Objects;

/**
 * 应用持久身份合同的唯一 ID 推导入口；实现细节留在网关，避免用例层依赖具体存储适配器。
 */
public final class AgentRecordIds {
    /**
     * ID 推导没有实例状态，禁止构造以免引入与持久身份无关的生命周期。
     */
    private AgentRecordIds() {
        throw new AssertionError("no instances");
    }

    /**
     * 按租户、会话、执行、类型和稳定事实键计算 ID，使迁移与运行循环能安全重试同一事实。
     */
    public static String derive(AgentStore.ExecutionKey execution, String recordType, String stableFactKey) {
        AgentStore.ExecutionKey key = Objects.requireNonNull(execution, "execution");
        String type = ContractChecks.identifier(recordType, "recordType");
        String factKey = stableFactKey(stableFactKey);
        String canonical = String.join("\0", key.conversation().tenantId(), key.conversation().threadId(),
                key.turnId(), type, factKey);
        return "rec_" + Hashing.sha256Hex(canonical);
    }

    /**
     * 允许复合调用身份容纳调用 ID 与修订号，同时拒绝分隔符冲突和控制字符。
     */
    private static String stableFactKey(String value) {
        if (value == null || value.isBlank() || value.length() > 512 || value.indexOf('\0') >= 0
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid stableFactKey");
        }
        return value;
    }
}