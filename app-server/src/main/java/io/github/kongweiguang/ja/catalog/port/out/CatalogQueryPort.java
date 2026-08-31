// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.port.out;

import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.util.concurrent.CompletionStage;

/**
 * 隔离 catalog application 与 MCP、配置投影及分页实现的出站端口。
 */
public interface CatalogQueryPort {
    /**
     * 从同一配置租约读取 Skill 页面，端口实现不得读取未冻结的配置。
     */
    CursorPage<SkillDescriptor> listSkills(ConfigurationGenerationPort.Lease generation,
                                           String cursor, int limit);

    /**
     * 从同一配置租约读取脱敏 MCP 页面，端口实现不得暴露 endpoint 或凭据。
     */
    CursorPage<McpServerDescriptor> listMcp(ConfigurationGenerationPort.Lease generation,
                                            String cursor, int limit);

    /**
     * 在有限生命周期内探测单个 MCP 服务，并返回脱敏状态。
     */
    CompletionStage<McpServerDescriptor> testMcp(ConfigurationGenerationPort.Lease generation,
                                                 String mcpId);

    /**
     * 从同一配置租约读取一个 MCP 的 Tool Schema 页面。
     */
    CursorPage<McpToolDescriptor> readMcpTools(
            ConfigurationGenerationPort.Lease generation, String mcpId, String cursor, int limit);
}
