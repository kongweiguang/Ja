// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 保存中立的首次请求语义，以便重启时按原历史选择恢复执行。
 *
 * @param identity 包含租户、对话、执行与用户的完整身份
 * @param kind 类型分类
 * @param history 历史消息
 * @param contextAttributes 上下文属性集合
 * @param acceptedAt 受理时间
 * @param outputSchema 结构化终答 Schema（可选）
 */
public record AgentExecutionBinding(AgentIdentity identity,
                                    AgentExecutionRequest.AdmissionKind kind,
                                    AgentExecutionRequest.HistorySelection history,
                                    Map<String, String> contextAttributes,
                                    Instant acceptedAt,
                                    Optional<JsonObject> outputSchema) {
    /**
     * 历史选择与上下文属性单独绑定，不复制用户正文或执行游标。
     */
    public AgentExecutionBinding {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(history, "history");
        contextAttributes = freeze(contextAttributes);
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        outputSchema = Objects.requireNonNull(outputSchema, "outputSchema");
    }

    /**
     * 再校验恢复属性的通用大小边界，使此类型不依赖 Engine 私有解码器信任链。
     */
    private static Map<String, String> freeze(Map<String, String> source) {
        Objects.requireNonNull(source, "contextAttributes");
        if (source.size() > 256) {
            throw new IllegalArgumentException("too many context attributes");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String safeKey = ContractChecks.identifier(key, "context attribute key");
            String safeValue = ContractChecks.text(value, "context attribute value", 16_384, true);
            if (copy.putIfAbsent(safeKey, safeValue) != null) {
                throw new IllegalArgumentException("duplicate context attribute");
            }
        });
        if (copy.entrySet().stream().mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum()
            > 65_536) {
                throw new IllegalArgumentException("context attributes exceed the request bound");
            }
        return Map.copyOf(copy);
    }
}
