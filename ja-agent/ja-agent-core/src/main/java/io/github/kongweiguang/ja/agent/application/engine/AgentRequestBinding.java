// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.engine;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionBinding;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionRequest;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 首次准入的非秘密恢复元数据，避免重启后用新调用方属性改写旧任务语义。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param kind 类型分类
 * @param contextAttributes 上下文属性集合
 * @param history 历史消息
 * @param acceptedAt 受理时间
 * @param modelSelection 模型选择
 * @param runtimeBindingFingerprint 运行时绑定指纹
 * @param initialQueuedInputsRecordId 初始排队输入的记录标识
 * @param outputSchema 结构化终答 Schema（可选）
 */
public record AgentRequestBinding(AgentIdentity identity,
                                  AgentExecutionRequest.AdmissionKind kind,
                                  Map<String, String> contextAttributes,
                                  AgentExecutionRequest.HistorySelection history,
                                  Instant acceptedAt,
                                  ModelSelection modelSelection,
                                  String runtimeBindingFingerprint,
                                  String initialQueuedInputsRecordId,
                                  Optional<JsonObject> outputSchema) {
    /**
     * 冻结原始调用身份与历史路径，只保存模型的无密钥选择标识。
     */
    public AgentRequestBinding {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(kind, "kind");
        contextAttributes = freeze(contextAttributes);
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(modelSelection, "modelSelection");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
        initialQueuedInputsRecordId = ContractChecks.identifier(initialQueuedInputsRecordId,
                "initialQueuedInputsRecordId");
        if (runtimeBindingFingerprint == null || !runtimeBindingFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid runtime binding fingerprint");
        }
    }

    /**
     * 从已经通过准入指纹校验的请求与实际解析结果生成恢复绑定。
     */
    public static AgentRequestBinding from(AgentExecutionRequest request, AgentRequestRuntime runtime) {
        AgentExecutionRequest command = Objects.requireNonNull(request, "request");
        AgentRequestRuntime resolved = Objects.requireNonNull(runtime, "runtime");
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(command.identity().tenantId(), command.identity().threadId(),
                        command.identity().userId()),
                command.identity().turnId());
        return new AgentRequestBinding(command.identity(), command.kind(), command.contextAttributes(),
                command.history(), command.acceptedAt(), ModelSelection.from(resolved.modelConfiguration()),
                resolved.bindingFingerprint(), AgentInitialQueuedInputsCodec.recordId(execution,
                command.operation().operationKey()), command.outputSchema());
    }

    /**
     * 保留有界纯文本属性并复制为不可变快照，防止恢复时被调用方修改。
     */
    private static Map<String, String> freeze(Map<String, String> source) {
        Objects.requireNonNull(source, "contextAttributes");
        if (source.size() > 256) {
            throw new IllegalArgumentException("too many context attributes");
        }
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String name = ContractChecks.identifier(key, "context attribute key");
            String text = ContractChecks.text(value, "context attribute value", 16_384, true);
            if (copy.putIfAbsent(name, text) != null) {
                throw new IllegalArgumentException("duplicate context attribute");
            }
        });
        if (copy.entrySet().stream().mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum()
            > 65_536) {
            throw new IllegalArgumentException("context attributes exceed the request bound");
        }
        return Map.copyOf(copy);
    }

    /**
     * 重启后重新取得当前密钥时仍只允许绑定到原模型，不回放已保存的凭据。
     */
    public AgentRuntimeRequest runtimeRequest(CancellationToken cancellationToken) {
        AgentRuntimeRequest.Kind runtimeKind = switch (kind) {
            case USER_INPUT -> AgentRuntimeRequest.Kind.USER_INPUT;
            case INTERNAL -> AgentRuntimeRequest.Kind.INTERNAL;
            case COMPACTION -> AgentRuntimeRequest.Kind.COMPACTION;
        };
        return new AgentRuntimeRequest(runtimeKind, identity, java.util.Optional.empty(), contextAttributes,
                Objects.requireNonNull(cancellationToken, "cancellationToken"),
                java.util.Optional.of(modelSelection));
    }

    /**
     * 仅向唯一 Loop 恢复器提供原始历史选择，不复制输入正文或重建业务操作回执。
     */
    public AgentExecutionBinding executionBinding() {
        return new AgentExecutionBinding(identity, kind, history, contextAttributes, acceptedAt, outputSchema);
    }
}
