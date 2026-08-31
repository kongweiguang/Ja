// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.conversation.application.context.ContextOrchestrator;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;

/**
 * 向 RPC 组合层暴露必须绑定 Turn 上下文的同一 Kernel Factory，且不承载业务逻辑。
 */
public final class ContextOrchestratorFactory {
    private final io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory delegate;

    /**
     * 复用 Kernel 所有的 Factory，防止 bootstrap 创建未绑定或回退 Context。
     */
    public ContextOrchestratorFactory(
            io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory delegate) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * 只从冻结的 Provider/Model 配置、Deadline 与取消绑定创建 Context 门面，禁止读取活动配置。
     */
    public ContextOrchestrator create(SummaryModel.TurnBinding binding) {
        return delegate.create(binding);
    }
}
