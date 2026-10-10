// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.JsonSchemaSnapshot;

import java.util.List;
import java.util.Map;

/**
 * MCP 目录和调用契约；HTTP 接口只依赖该业务能力，不持有 SDK 客户端实现。
 */
public interface McpDirectory extends AutoCloseable {
    /** 握手并返回已发现的服务与工具快照。 */
    List<ServerView> catalog();

    /** 调用已发现工具，取消只关闭当前 MCP 会话。 */
    String call(String server, String tool, JsonObject arguments, CancellationToken cancellation);

    /**
     * 目录服务公开快照，隔离 SDK 会话生命周期与 HTTP 序列化。
     *
     * @param id 服务标识
     * @param transport 传输方式
     * @param status 服务状态
     * @param tools 已发现工具的不可变快照
     */
    record ServerView(String id,
                      String transport,
                      String status,
                      List<ToolView> tools) {
        /** 快照不能被 HTTP 序列化过程反向修改。 */
        public ServerView {
            tools = List.copyOf(tools);
        }
    }

    /**
     * MCP 工具公开快照，不向调用方暴露 SDK 的可变 Schema。
     *
     * @param name 工具名称
     * @param description 工具用途
     * @param inputSchema 参数契约的不可变快照
     */
    record ToolView(String name,
                    String description,
                    Map<String, Object> inputSchema) {
        /** Schema 快照深拷贝并冻结嵌套容器，避免 SDK 生命周期越过契约边界。 */
        public ToolView {
            inputSchema = JsonSchemaSnapshot.copyOf(inputSchema);
        }

        /**
         * 返回构造时已深拷贝并冻结的 JSON 树；静态分析无法推导 copyOf 的递归不可变保证。
         */
        public Map<String, Object> inputSchema() {
            return inputSchema;
        }
    }
}
