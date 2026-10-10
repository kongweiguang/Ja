// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.conversation.ContextInstruction;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 宿主驱动的隐藏续跑命令；指令和历史选择具名传递，不伪装成用户消息或上下文属性。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param instruction 指令
 * @param operationKey 重复提交同一业务操作时复用的持久操作键
 * @param modelOverride 当前请求覆盖使用的模型配置（可选）
 * @param contextAttributes 上下文属性集合
 * @param history 历史消息
 * @param acceptedAt 受理时间
 * @param initialQueuedInputs 初始排队输入
 * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
 */
public record AgentInternalRequest(AgentIdentity identity,
                                   ContextInstruction instruction,
                                   String operationKey,
                                   Optional<ModelPort.ModelConfiguration> modelOverride,
                                   Map<String, String> contextAttributes,
                                   AgentExecutionRequest.HistorySelection history,
                                   Instant acceptedAt,
                                   List<AgentInputCommand.Enqueue> initialQueuedInputs,
                                   Optional<InitialContextSeed> initialContextSeed) {
    /**
     * 在 Engine 准入前冻结隐藏指令、历史和只能以纯 MESSAGE 投递的有界首批输入。
     */
    public AgentInternalRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(instruction, "instruction");
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        modelOverride = Objects.requireNonNull(modelOverride, "modelOverride");
        contextAttributes = AgentRequestAttributes.freeze(contextAttributes);
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        initialQueuedInputs = AgentInputCommand.freezeInitialQueuedInputs(initialQueuedInputs);
        if (initialQueuedInputs.stream().anyMatch(input -> input.kind() != AgentInputCommand.Kind.MESSAGE)) {
            throw new IllegalArgumentException("internal admission only accepts MESSAGE inputs");
        }
        initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        initialContextSeed.ifPresent(seed -> {
            if (!identity.tenantId().equals(seed.sourceIdentity().tenantId())) {
                throw new IllegalArgumentException("initial context seed belongs to another tenant");
            }
        });
    }

    /**
     * 未附带冻结父上下文时显式使用空 seed，避免调用方复制运行时上下文到指令正文。
     */
    public AgentInternalRequest(AgentIdentity identity, ContextInstruction instruction, String operationKey,
                                Optional<ModelPort.ModelConfiguration> modelOverride,
                                Map<String, String> contextAttributes,
                                AgentExecutionRequest.HistorySelection history, Instant acceptedAt) {
        this(identity, instruction, operationKey, modelOverride, contextAttributes, history, acceptedAt,
                List.of(), Optional.empty());
    }

    /**
     * 保留显式 Seed 的调用形式；新增 mailbox 内容必须另行通过具名副本方法传入。
     */
    public AgentInternalRequest(AgentIdentity identity, ContextInstruction instruction, String operationKey,
                                Optional<ModelPort.ModelConfiguration> modelOverride,
                                Map<String, String> contextAttributes,
                                AgentExecutionRequest.HistorySelection history, Instant acceptedAt,
                                Optional<InitialContextSeed> initialContextSeed) {
        this(identity, instruction, operationKey, modelOverride, contextAttributes, history, acceptedAt,
                List.of(), initialContextSeed);
    }

    /**
     * 在不改动指令、历史路径或准入时刻的前提下选择本次显式模型。
     */
    public AgentInternalRequest withModel(ModelPort.ModelConfiguration model) {
        return new AgentInternalRequest(identity, instruction, operationKey,
                Optional.of(Objects.requireNonNull(model, "model")), contextAttributes, history, acceptedAt,
                initialQueuedInputs, initialContextSeed);
    }

    /**
     * 以不可变副本加入一个有界宿主属性，不把它解释为 Prompt 指令或执行控制字段。
     */
    public AgentInternalRequest withContextAttribute(String key, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(contextAttributes);
        updated.put(ContractChecks.identifier(key, "context attribute key"),
                ContractChecks.text(value, "context attribute value", 16_384, true));
        return new AgentInternalRequest(identity, instruction, operationKey, modelOverride,
                updated, history, acceptedAt, initialQueuedInputs, initialContextSeed);
    }

    /**
     * 将来源已由宿主固定的有限 MESSAGE 批次绑定到同一个内部 admission。
     */
    public AgentInternalRequest withInitialQueuedInputs(List<AgentInputCommand.Enqueue> values) {
        return new AgentInternalRequest(identity, instruction, operationKey, modelOverride, contextAttributes,
                history, acceptedAt, Objects.requireNonNull(values, "initialQueuedInputs"), initialContextSeed);
    }

    /**
     * 冻结来源可追溯的背景历史，不混入本次内部执行指令。
     */
    public AgentInternalRequest withInitialContextSeed(InitialContextSeed seed) {
        return new AgentInternalRequest(identity, instruction, operationKey, modelOverride, contextAttributes,
                history, acceptedAt, initialQueuedInputs,
                Optional.of(Objects.requireNonNull(seed, "initialContextSeed")));
    }
}
