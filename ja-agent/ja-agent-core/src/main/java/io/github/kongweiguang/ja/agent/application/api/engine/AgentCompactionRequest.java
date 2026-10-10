// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 手动压缩作为独立持久执行准入，不伪造用户输入或借用无租约的 Checkpoint 写路径。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param operationKey 重复提交同一业务操作时复用的持久操作键
 * @param modelOverride 当前请求覆盖使用的模型配置（可选）
 * @param contextAttributes 上下文属性集合
 * @param history 历史消息
 * @param acceptedAt 受理时间
 */
public record AgentCompactionRequest(AgentIdentity identity,
                                     String operationKey,
                                     Optional<ModelPort.ModelConfiguration> modelOverride,
                                     Map<String, String> contextAttributes,
                                     AgentExecutionRequest.HistorySelection history,
                                     Instant acceptedAt) {
    /**
     * 冻结压缩实际可见历史和受理时刻，保证重试不会改变压缩来源分支。
     */
    public AgentCompactionRequest {
        Objects.requireNonNull(identity, "identity");
        if (!identity.turnId().matches("compaction_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("compaction identity must be derived for this operation");
        }
        operationKey = ContractChecks.identifier(operationKey, "operationKey");
        modelOverride = Objects.requireNonNull(modelOverride, "modelOverride");
        contextAttributes = AgentRequestAttributes.freeze(contextAttributes);
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
    }

    /**
     * 为没有历史分支和扩展属性的接入保留单一压缩调用构造器。
     */
    public AgentCompactionRequest(AgentIdentity identity, String operationKey) {
        this(identity, operationKey, Optional.empty(), Map.of(),
                AgentExecutionRequest.HistorySelection.currentConversation(), Instant.now());
    }

    /**
     * 以 Thread 身份和操作幂等键派生内部 Turn ID，避免把手动压缩伪装成可见用户 Turn。
     */
    public static AgentIdentity compactionIdentity(AgentIdentity conversationIdentity, String operationKey) {
        AgentIdentity conversation = Objects.requireNonNull(conversationIdentity, "conversationIdentity");
        String validatedOperationKey = ContractChecks.identifier(operationKey, "operationKey");
        String material = String.join("\0", conversation.tenantId(), conversation.threadId(),
                conversation.userId(), validatedOperationKey);
        String digest = Hashing.sha256Hex(material);
        return new AgentIdentity(conversation.tenantId(), conversation.threadId(), "compaction_" + digest,
                conversation.userId());
    }

    /**
     * 让 Ja 把线程修订时刻固定到同一 CAS 事务的准入时间。
     */
    public AgentCompactionRequest withAcceptedAt(Instant value) {
        return new AgentCompactionRequest(identity, operationKey, modelOverride, contextAttributes, history,
                Objects.requireNonNull(value, "acceptedAt"));
    }

    /**
     * 冻结显式模型覆盖，但由 Engine 仍绑定实际 runtime 和幂等准入指纹。
     */
    public AgentCompactionRequest withModel(ModelPort.ModelConfiguration value) {
        return new AgentCompactionRequest(identity, operationKey,
                Optional.of(Objects.requireNonNull(value, "model")), contextAttributes, history, acceptedAt);
    }

    /**
     * 固定一次压缩的精确历史路径与 CAS revision，不从当前 Ja 分支事后重算。
     */
    public AgentCompactionRequest withHistory(AgentExecutionRequest.HistorySelection value) {
        return new AgentCompactionRequest(identity, operationKey, modelOverride, contextAttributes,
                Objects.requireNonNull(value, "history"), acceptedAt);
    }

    /**
     * 为压缩运行时加入有界宿主数据，不把数据隐式解释为压缩 Prompt。
     */
    public AgentCompactionRequest withContextAttribute(String key, String value) {
        LinkedHashMap<String, String> updated = new LinkedHashMap<>(contextAttributes);
        updated.put(ContractChecks.identifier(key, "context attribute key"),
                ContractChecks.text(value, "context attribute value", 16_384, true));
        return new AgentCompactionRequest(identity, operationKey, modelOverride, updated, history, acceptedAt);
    }

}
