// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web.dto;

import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.JsonSchemaSnapshot;

import java.util.Map;
import java.util.Objects;

/**
 * HTTP 工具目录只暴露稳定描述和标准 JSON Schema，不泄露 Core 的 JsonValue 类型。
 *
 * @param name 工具名称
 * @param description 工具说明
 * @param inputSchema JSON Schema 对象
 */
public record ExperienceToolView(String name,
                                 String description,
                                 Map<String, Object> inputSchema) {
    /**
     * 深度冻结 Schema，避免响应对象被嵌套 Map/List 的外部引用修改。
     */
    public ExperienceToolView {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        inputSchema = JsonSchemaSnapshot.copyOf(Objects.requireNonNull(inputSchema, "inputSchema"));
    }
}
