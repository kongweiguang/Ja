// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.domain.model;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

/**
 * 宿主提供的有界资源线索；Core 保留身份但不读取资源或解释其位置。
 *
 * @param sourceId 来源标识
 * @param resourceId 资源标识
 * @param kind 类型分类
 */
public record ResourceReferenceContent(String sourceId,
                                       String resourceId,
                                       String kind)
        implements UserContentBlock {
    /**
     * 只限制可持久化值的大小和空值，不约束来源前缀或资源语法；授权、路径安全与真实读取由宿主负责。
     */
    public ResourceReferenceContent {
        sourceId = ContractChecks.text(sourceId, "sourceId", 256, false);
        resourceId = ContractChecks.text(resourceId, "resourceId", 4_096, false);
        kind = ContractChecks.text(kind, "kind", 128, false);
    }
}
