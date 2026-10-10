// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;

/** Catalog 消费方定义的配置投影端口；配置实体和凭据只在 ACL 内转换。 */
public interface CatalogConfigurationPort {
    /** 在当前租约内读取模型探测配置和展示名。 */
    ModelProbe modelProbe(String providerId, String modelId, ConfigurationGenerationPort.Lease lease);

    /** 在当前租约内读取模型发现配置。 */
    ModelPort.ModelDiscoveryRequest modelDiscoveryRequest(String providerId,
                                                          ConfigurationGenerationPort.Lease lease);

    /**
     * Catalog 自有的最小模型探测结果。
     * @param configuration 模型运行配置
     * @param modelDisplayName 面向用户的模型显示名
     */
    record ModelProbe(ModelPort.ModelConfiguration configuration,
                      String modelDisplayName) {
    }
}
