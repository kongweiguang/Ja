// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.port.in;

import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;

import java.util.concurrent.CompletionStage;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

/**
 * 定义 Skill 与 MCP 查询用例；配置代际生命周期由 application 自己持有。
 */
public interface CatalogUseCase {
    /**
     * 从可选已打开工作区列出真实发现的 Skill；null 只读取通用来源且不启用项目扫描。
     */
    CursorPage<SkillDescriptor> listSkills(String workspaceId, String cursor, int limit);

    /**
     * 从当前通用配置代际列出已脱敏 MCP 状态。
     */
    CursorPage<McpServerDescriptor> listMcp(String cursor, int limit);

    /**
     * 在有限资源边界内测试一个 MCP 服务。
     */
    CompletionStage<McpServerDescriptor> testMcp(String mcpId);

    /** 对已保存模型执行一次无历史、无 Tool、无附件的严格限额真实请求。 */
    CompletionStage<ModelTestResult> testModel(
            String providerId, String modelId, CancellationToken cancellationToken);

    /**
     * 从当前通用配置代际读取 MCP Tool Schema 页面。
     */
    CursorPage<McpToolDescriptor> readMcpTools(String mcpId, String cursor, int limit);

    /** 只公开非敏感响应模型标识和单调耗时，不返回测试提示或模型回答。 */
    record ModelTestResult(String responseModel, long latencyMs) {
        /** 限制可见模型名与耗时，防止异常 Provider 数据扩大 Wire 响应。 */
        public ModelTestResult {
            if (responseModel == null || responseModel.isBlank() || responseModel.length() > 512
                    || latencyMs < 0 || latencyMs > 3_600_000) {
                throw new IllegalArgumentException("invalid model test result");
            }
        }
    }
}
