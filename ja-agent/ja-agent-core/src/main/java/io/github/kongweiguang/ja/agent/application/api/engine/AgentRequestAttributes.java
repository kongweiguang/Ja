// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.api.engine;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 固定准入请求共享的宿主属性约束，避免不同请求种类出现不同尺寸边界。
 */
final class AgentRequestAttributes {
    /**
     * 避免绕过统一冻结入口，所有属性都必须经过相同的数量、字符与总字节边界。
     */
    private AgentRequestAttributes() {
    }

    /**
     * 冻结有界的纯数据属性；不把宿主元数据解释成 Prompt 或能力授权。
     */
    static Map<String, String> freeze(Map<String, String> source) {
        Objects.requireNonNull(source, "contextAttributes");
        if (source.size() > 64) {
            throw new IllegalArgumentException("too many context attributes");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String safeKey = ContractChecks.identifier(key, "context attribute key");
            String safeValue = ContractChecks.text(value, "context attribute value", 16_384, true);
            if (values.putIfAbsent(safeKey, safeValue) != null) {
                throw new IllegalArgumentException("duplicate context attribute key");
            }
        });
        int size = values.entrySet().stream()
                .mapToInt(entry -> entry.getKey().length() + entry.getValue().length()).sum();
        if (size > 65_536) {
            throw new IllegalArgumentException("context attributes exceed the request bound");
        }
        return Map.copyOf(values);
    }
}
