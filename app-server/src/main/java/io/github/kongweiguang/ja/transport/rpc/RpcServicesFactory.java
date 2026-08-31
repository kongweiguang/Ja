// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;

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
}
