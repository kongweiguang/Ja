// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 执行与准入事实的只读句柄；普通恢复不创建新准入，因此其准入阶段明确为空。
 */
public final class AgentExecutionHandle {
    private final AgentIdentity identity;
    private final CompletableFuture<AgentExecutionOutcome> completion;
    private final CompletableFuture<Optional<AgentAdmissionReceipt>> admissionReceipt;

    /**
     * 封装完成与准入的只读阶段；Engine 持有其权威状态并负责调度，调用者不能直接改写它们。
     */
    public AgentExecutionHandle(AgentIdentity identity, CompletableFuture<AgentExecutionOutcome> completion,
                                CompletableFuture<Optional<AgentAdmissionReceipt>> admissionReceipt) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.completion = Objects.requireNonNull(completion, "completion");
        this.admissionReceipt = Objects.requireNonNull(admissionReceipt, "admissionReceipt");
    }

    /**
     * 返回后续查询、取消和重连所需的稳定执行身份。
     */
    public AgentIdentity identity() {
        return identity;
    }

    /**
     * 观察本轮执行的终态或持久挂起结果，不暴露修改完成状态的权限。
     */
    public CompletionStage<AgentExecutionOutcome> completion() {
        return completion.minimalCompletionStage();
    }

    /**
     * 观察原始准入水位；普通恢复以空值区分其没有创建新准入的生命周期。
     */
    public CompletionStage<Optional<AgentAdmissionReceipt>> admissionReceipt() {
        return admissionReceipt.minimalCompletionStage();
    }
}
