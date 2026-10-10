// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;

/**
 * 定义连接初始化时创建应用服务图的入站边界。
 *
 * <p>该端口归 transport 所有，组合根负责适配具体容器，避免协议层反向依赖
 * bootstrap 或 Solon。每个连接只允许创建一个服务图。</p>
 */
@FunctionalInterface
public interface RpcServicesFactory {
    /**
     * 为当前连接发布唯一服务图；Tool 全部在 Java Runtime 或冻结的 MCP 会话内执行，
     * 因此连接层不再拥有反向 Host RPC。
     */
    RpcServiceBindings open(ObjectMapper mapper);

    /**
     * 提供组合根持有的严格 JSON 值边界；仅在解析终答 Schema 时按需调用。
     */
    default JsonValueCodec jsonValueCodec() {
        throw new IllegalStateException("runtime JSON value codec is unavailable");
    }

    /** 返回启动恢复的脱敏事实；恢复状态本身不代表业务服务图已经可用。 */
    default RecoveryStatus storageRecovery() { return null; }
    /** 按相同策略显式重试恢复，不能丢弃或重建原数据库来伪造成功。 */
    default void retryStorage() { }

    /**
     * 恢复状态只公开脱敏事实，不允许路径或异常原文跨越运行时健康边界。
     *
     * @param id 当前实体的稳定标识
     * @param phase 阶段
     * @param reason 原因说明
     * @param historyAvailable 是否可读取历史
     * @param backupAvailable 是否有可用备份
     * @param rebuilt 是否已重建
     */
    record RecoveryStatus(String id,
                          String phase,
                          String reason,
                          boolean historyAvailable,
                          boolean backupAvailable,
                          boolean rebuilt) { }
}
