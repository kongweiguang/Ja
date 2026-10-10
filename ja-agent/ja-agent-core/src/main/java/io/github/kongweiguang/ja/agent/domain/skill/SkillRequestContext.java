// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.skill;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.util.Map;
import java.util.Objects;

/**
 * 为来源查询携带租户及宿主自定义的不可变属性；使用字符串属性避免 Core 依赖 Ja Workspace 或本地路径类型。
 *
 * @param tenantId 租户标识，用于隔离不同租户的数据与执行
 * @param attributes 属性集合
 */
public record SkillRequestContext(String tenantId,
                                  Map<String, String> attributes) {
    /**
     * 在请求边界复制并校验扩展属性，防止持有者后续修改 Map 而改变正在执行的来源查询语义。
     */
    public SkillRequestContext {
        tenantId = ContractChecks.identifier(tenantId, "tenantId");
        Objects.requireNonNull(attributes, "attributes");
        attributes = Map.copyOf(attributes);
        attributes.forEach((key, value) -> {
            ContractChecks.identifier(key, "attribute key");
            ContractChecks.text(value, "attribute value", 4_096, false);
        });
    }

    /**
     * 创建不携带宿主属性的租户上下文，供只需数据库租户键的 Skill 来源使用。
     */
    public SkillRequestContext(String tenantId) {
        this(tenantId, Map.of());
    }

    /**
     * 读取必需的来源配置；缺失时明确失败，避免把未配置误解为可访问空根目录或默认租户。
     */
    public String requireAttribute(String key) {
        String checkedKey = ContractChecks.identifier(key, "key");
        String value = attributes.get(checkedKey);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("skill_request_attribute_missing:" + checkedKey);
        }
        return value;
    }
}
