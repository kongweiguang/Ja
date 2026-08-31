// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.session;

import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonValue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 包内防腐层，将 Reactor 与 SDK 类型限制在 MCP Adapter 内。
 */
public interface McpSession extends AutoCloseable {
    /**
     * 接受任何目录数据前完成协议协商。
     */
    void initialize();

    /**
     * 每次精确拉取一页；Cursor 循环与聚合上限由 Gateway 负责。
     */
    ToolPage listTools(String cursor);

    /**
     * 执行一次原始服务调用；所属 Gateway 按 Session 串行化。
     */
    RemoteResult call(String toolName, JsonObject arguments);

    /**
     * 关闭 SDK Client 及其传输；实现必须幂等。
     */
    @Override
    void close();

    /**
     * 与 SDK Record 版本解耦的不可信单页目录载荷。
     */
    record ToolPage(List<RemoteTool> tools, String nextCursor) {
        /**
         * 复制页面，防止传输在上限校验后继续修改。
         */
        public ToolPage {
            tools = List.copyOf(tools == null ? List.of() : tools);
        }
    }

    /**
     * 用于创建冻结 Kernel ToolSpec 的 Provider 中性远端 Tool 元数据。
     */
    record RemoteTool(String name, String description, JsonObject inputSchema) {
        /**
         * 保留远端名称，但在 Schema 数据进入目录前防御性复制。
         */
        public RemoteTool {
            Objects.requireNonNull(inputSchema, "inputSchema");
        }
    }

    /**
     * 原始结果投影；构建公共 API 前必须校验编码大小。
     */
    record RemoteResult(boolean error, String content, Optional<JsonValue> structuredContent) {
        /**
         * 用不可变空值替代缺失可选字段，消除下游空值分支。
         */
        public RemoteResult {
            content = content == null ? "" : content;
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
        }
    }
}
