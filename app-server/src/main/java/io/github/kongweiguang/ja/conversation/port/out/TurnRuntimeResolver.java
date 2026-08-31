// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 从配置、Skill 和 MCP 出站能力中冻结一个 Turn 的运行时快照。
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
