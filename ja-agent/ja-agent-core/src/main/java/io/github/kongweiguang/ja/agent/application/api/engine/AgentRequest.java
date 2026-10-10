// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 首次用户输入请求；稳定操作键支持响应丢失后的安全重试。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param input 输入内容
 * @param operationKey 重复提交同一业务操作时复用的持久操作键
 * @param modelOverride 当前请求覆盖使用的模型配置（可选）
 * @param contextAttributes 上下文属性集合
 * @param history 历史消息
 * @param acceptedAt 受理时间
 * @param initialQueuedInputs 初始排队输入
 * @param outputSchema 结构化终答 Schema（可选）
 * @param initialContextSeed 创建侧边任务时冻结的初始上下文（可选）
 */
public record AgentRequest(AgentIdentity identity,
                           UserContent input,
                           String operationKey,
                           Optional<ModelPort.ModelConfiguration> modelOverride,
                           Map<String, String> contextAttributes,
                           AgentExecutionRequest.HistorySelection history,
                           Instant acceptedAt,
                           List<AgentInputCommand.Enqueue> initialQueuedInputs,
                           Optional<JsonObject> outputSchema,
                           Optional<InitialContextSeed> initialContextSeed) {
    /**
     * 在准入前固定会话历史版本和业务受理时刻，避免 Ja 重问覆盖并发分支。
     */
    public AgentRequest {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(input, "input");
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        modelOverride = Objects.requireNonNull(modelOverride, "modelOverride");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        initialContextSeed = Objects.requireNonNull(initialContextSeed, "initialContextSeed");
        initialContextSeed.ifPresent(seed -> requireSeedTenant(identity, seed));
        initialQueuedInputs = AgentInputCommand.freezeInitialQueuedInputs(initialQueuedInputs);
        contextAttributes = AgentRequestAttributes.freeze(contextAttributes);
    }

    /**
     * 未提供冻结父上下文时显式采用空 seed，避免构造调用方重建其余请求语义。
     */
    public AgentRequest(AgentIdentity identity, UserContent input, String operationKey,
                        Optional<ModelPort.ModelConfiguration> modelOverride,
                        Map<String, String> contextAttributes,
                        AgentExecutionRequest.HistorySelection history, Instant acceptedAt,
                        List<AgentInputCommand.Enqueue> initialQueuedInputs) {
        this(identity, input, operationKey, modelOverride, contextAttributes, history, acceptedAt,
                initialQueuedInputs, Optional.empty(), Optional.empty());
    }

    /**
     * 为纯 Java 接入保留简洁构造方式；无分支选择时绑定当前会话历史和本地接收时刻。
     */
    public AgentRequest(AgentIdentity identity, UserContent input, String operationKey,
                        Optional<ModelPort.ModelConfiguration> modelOverride,
                        Map<String, String> contextAttributes) {
        this(identity, input, operationKey, modelOverride, contextAttributes,
                AgentExecutionRequest.HistorySelection.currentConversation(), Instant.now(), List.of(),
                Optional.empty(), Optional.empty());
    }

    /**
     * 允许宿主选择明确历史路径，同时由 Engine 记录它首次接收请求的时刻。
     */
    public AgentRequest(AgentIdentity identity, UserContent input, String operationKey,
                        Optional<ModelPort.ModelConfiguration> modelOverride,
                        Map<String, String> contextAttributes,
                        AgentExecutionRequest.HistorySelection history) {
        this(identity, input, operationKey, modelOverride, contextAttributes, history, Instant.now(), List.of(),
                Optional.empty(), Optional.empty());
    }

    /**
     * 创建纯文本请求，同时保留更丰富宿主直接构造结构化内容的能力。
     */
    public static AgentRequest text(AgentIdentity identity, String text, String operationKey) {
        Objects.requireNonNull(text, "text");
        return new AgentRequest(identity, new UserContent(List.of(new TextContent(text))), operationKey,
                Optional.empty(), Map.of());
    }

    /**
     * 请求构造时先拒绝跨租户背景，防止来源快照越过 Store 租户隔离。
     */
    private static void requireSeedTenant(AgentIdentity identity, InitialContextSeed seed) {
        if (!identity.tenantId().equals(seed.sourceIdentity().tenantId())) {
            throw new IllegalArgumentException("initial context seed belongs to another tenant");
        }
    }

    /**
     * 绑定显式模型快照，不改变操作身份和输入正文。
     */
    public AgentRequest withModel(ModelPort.ModelConfiguration model) {
        return new AgentRequest(identity, input, operationKey,
                Optional.of(Objects.requireNonNull(model, "model")), contextAttributes, history, acceptedAt,
                initialQueuedInputs, outputSchema, initialContextSeed);
    }

    /**
     * 绑定 Ja 当前分支及其 CAS 版本，重试同一操作时保持原接收时刻。
     */
    public AgentRequest withHistory(AgentExecutionRequest.HistorySelection selectedHistory) {
        return new AgentRequest(identity, input, operationKey, modelOverride, contextAttributes,
                Objects.requireNonNull(selectedHistory, "history"), acceptedAt, initialQueuedInputs, outputSchema,
                initialContextSeed);
    }

    /**
     * 让宿主把准入时刻与其同事务业务事实固定为同一时间点。
     */
    public AgentRequest withAcceptedAt(Instant value) {
        return new AgentRequest(identity, input, operationKey, modelOverride, contextAttributes, history,
                Objects.requireNonNull(value, "acceptedAt"), initialQueuedInputs, outputSchema, initialContextSeed);
    }

    /**
     * 加入 Skill 来源等宿主上下文，同时不要求 Core 调用方必须提供本地路径。
     */
    public AgentRequest withContextAttribute(String key, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(contextAttributes);
        updated.put(ContractChecks.identifier(key, "context attribute key"),
                ContractChecks.text(value, "context attribute value", 16_384, true));
        return new AgentRequest(identity, input, operationKey, modelOverride, updated, history, acceptedAt,
                initialQueuedInputs, outputSchema, initialContextSeed);
    }

    /**
     * 将显式 mailbox 批次附到首条用户请求，且不把队列正文合并进手输正文。
     */
    public AgentRequest withInitialQueuedInputs(List<AgentInputCommand.Enqueue> values) {
        return new AgentRequest(identity, input, operationKey, modelOverride, contextAttributes, history, acceptedAt,
                Objects.requireNonNull(values, "initialQueuedInputs"), outputSchema, initialContextSeed);
    }

    /**
     * 冻结来自同租户父上下文的背景历史，不把它并入当前用户输入正文。
     */
    public AgentRequest withInitialContextSeed(InitialContextSeed seed) {
        return new AgentRequest(identity, input, operationKey, modelOverride, contextAttributes, history, acceptedAt,
                initialQueuedInputs, outputSchema, Optional.of(Objects.requireNonNull(seed, "initialContextSeed")));
    }

    /**
     * 让自动化调用冻结本次终答约束，确保重试使用完全相同的 Schema。
     */
    public AgentRequest withOutputSchema(JsonObject schema) {
        return new AgentRequest(identity, input, operationKey, modelOverride, contextAttributes, history,
                acceptedAt, initialQueuedInputs, Optional.of(Objects.requireNonNull(schema, "outputSchema")),
                initialContextSeed);
    }
}
