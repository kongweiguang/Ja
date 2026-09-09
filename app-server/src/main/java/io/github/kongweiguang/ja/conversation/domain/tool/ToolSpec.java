// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain.tool;

import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.validation.ContractChecks;

import java.util.Objects;

/**
 * Provider、权限策略与执行器共享的不可变 Tool 描述。
 */
public record ToolSpec(String name, String description, JsonObject inputSchema) {
    /**
     * 固定模型可见 Schema；执行顺序与审批属于内核，不允许 Tool 元数据替代权限决策。
     */
    public ToolSpec {
        name = ContractChecks.identifier(name, "name");
        description = ContractChecks.text(description, "description", 32_768, false);
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
