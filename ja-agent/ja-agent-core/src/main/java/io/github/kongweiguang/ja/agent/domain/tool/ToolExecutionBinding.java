// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.domain.tool;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Objects;

/**
 * 冻结工具调用时的执行授权；路由数据由工具适配器解释，Core 不定义其业务含义。
 *
 * @param toolName 工具名称
 * @param schemaFingerprint 结构约束指纹
 * @param catalogFingerprint 能力目录指纹
 * @param permissionFingerprint 权限快照指纹
 * @param route 路由
 */
public record ToolExecutionBinding(String toolName,
                                   String schemaFingerprint,
                                   String catalogFingerprint,
                                   String permissionFingerprint,
                                   JsonObject route) {
    /**
     * 校验稳定工具标识、固定格式指纹和非空路由快照，避免配置变化后沿用历史授权。
     */
    public ToolExecutionBinding {
        toolName = ContractChecks.identifier(toolName, "toolName");
        schemaFingerprint = fingerprint(schemaFingerprint);
        catalogFingerprint = fingerprint(catalogFingerprint);
        permissionFingerprint = fingerprint(permissionFingerprint);
        Objects.requireNonNull(route, "route");
    }

    /**
     * 仅接受由64个小写十六进制字符构成的指纹，避免把可变展示名称误当作恢复身份。
     */
    private static String fingerprint(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid tool binding fingerprint");
        }
        return value;
    }
}
