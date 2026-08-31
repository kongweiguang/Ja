// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.domain;

import java.util.Objects;

/**
 * 表示 MCP Tool 名称、描述和规范 JSON Schema 文本。
 */
public record McpToolDescriptor(String name, String description, String inputSchemaJson) {
    /**
     * JSON 文本保持领域层纯 JDK，结构校验由拥有 ObjectMapper 的 adapter 完成。
     */
    public McpToolDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson");
    }
}
