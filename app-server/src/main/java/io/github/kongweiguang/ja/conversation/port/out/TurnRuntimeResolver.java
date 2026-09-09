// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 从配置、Skill 元数据目录和 MCP 出站能力中组装一次请求或 Tool batch 的短租约。
 */
public interface TurnRuntimeResolver {
    /**
     * 一次性解析并持有同一配置代际的 Tool 与 MCP 能力，失败时不得返回部分租约。
     */
    RuntimeLease resolve(TurnRuntimeRequest request);

    /**
     * 在准入外预热工作区的非敏感 Schema，凭据仍只能在 Turn 租约内短时借用。
     */
    void prepareWorkspace(Path workspaceRoot);

    /** 默认模型选择只由配置 Owner 提供，conversation 不猜测 Provider 或 Model 身份。 */
    default Optional<DefaultModelSelection> defaultModelSelection(Path workspaceRoot) {
        return Optional.empty();
    }

    /**
     * 当前配置仍可读取，但 Provider、Model、已启用 Skill 名称或 Tool 能力已无法按原身份解析；
     * 该分类允许 Resume fail-closed，同时不吞掉 IO、事务和编程错误。
     */
    final class RuntimeMismatchException extends RuntimeException {
        /** 只暴露稳定脱敏消息；具体配置内容与路径不得跨 conversation 边界。 */
        public RuntimeMismatchException(String message) {
            super(message == null || message.isBlank() ? "request runtime is unavailable" : message);
        }
    }

    /** 配置默认值跨端口只暴露稳定选择器和公开能力。 */
    record DefaultModelSelection(String providerId, String modelId, String reasoningLevel) {
        /** 选择器必须完整成对；reasoning 仍可交由模型默认。 */
        public DefaultModelSelection {
            java.util.Objects.requireNonNull(providerId, "providerId");
            java.util.Objects.requireNonNull(modelId, "modelId");
            if (reasoningLevel != null && !reasoningLevel.matches("off|minimal|low|medium|high|xhigh|max")) {
                throw new IllegalArgumentException("invalid reasoningLevel");
            }
        }
    }
}
