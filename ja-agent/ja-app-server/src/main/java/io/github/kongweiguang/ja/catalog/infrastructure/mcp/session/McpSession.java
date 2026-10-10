// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.infrastructure.mcp.session;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;

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
     *
     * @param tools 工具列表
     * @param nextCursor 读取下一页时使用的游标
     */
    record ToolPage(List<RemoteTool> tools,
                    String nextCursor) {
        /**
         * 复制页面，防止传输在上限校验后继续修改。
         */
        public ToolPage {
            tools = List.copyOf(tools == null ? List.of() : tools);
        }
    }

    /**
     * 用于创建请求级 Kernel ToolSpec 的 Provider 中性远端 Tool 元数据。
     *
     * @param name 名称
     * @param description 说明
     * @param inputSchema 工具输入参数的结构约束
     */
    record RemoteTool(String name,
                      String description,
                      JsonObject inputSchema) {
        /**
         * 保留远端名称，但在 Schema 数据进入目录前防御性复制。
         */
        public RemoteTool {
            Objects.requireNonNull(inputSchema, "inputSchema");
        }
    }

    /**
     * 原始结果投影；构建公共 API 前必须校验编码大小。
     *
     * @param error 是否为错误结果
     * @param content 内容
     * @param structuredContent 结构化结果内容（可选）
     * @param images 从远端响应提取的类型化图片内容
     */
    record RemoteResult(boolean error,
                        String content,
                        Optional<JsonValue> structuredContent,
                        List<RemoteImage> images) {
        /**
         * 保留既有纯文本 fake/session 调用面；媒体能力通过独立类型显式传递。
         */
        public RemoteResult(boolean error, String content, Optional<JsonValue> structuredContent) {
            this(error, content, structuredContent, List.of());
        }

        /**
         * 用不可变空值替代缺失可选字段，消除下游空值分支。
         */
        public RemoteResult {
            content = content == null ? "" : content;
            structuredContent = Objects.requireNonNull(structuredContent, "structuredContent");
            images = List.copyOf(Objects.requireNonNull(images, "images"));
            if (images.size() > 10) {
                throw new IllegalArgumentException("too many MCP result images");
            }
        }
    }

    /**
     * MCP 图片内容保留原始 Base64 到有界 Runtime 校验完成，避免 SDK DTO 泄漏到应用边界。
     *
     * @param mediaType 声明 MIME 类型
     * @param base64Data Base64 图片数据
     */
    record RemoteImage(String mediaType,
                       String base64Data) {
        /**
         * 限定编码形状和单项最大长度，防止异常 MCP 响应在导入前扩张内存占用。
         */
        public RemoteImage {
            if (!List.of("image/png", "image/jpeg", "image/gif", "image/webp").contains(mediaType)
                || base64Data == null || base64Data.isEmpty() || base64Data.length() > 16 * 1024 * 1024
                || base64Data.length() % 4 != 0
                || !base64Data.matches("[A-Za-z0-9+/]*={0,2}")) {
                throw new IllegalArgumentException("invalid MCP image content");
            }
        }
    }
}
