// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.port.in;

import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;

import java.util.List;
import java.util.Objects;

/**
 * Composer 的 Workspace 路径检索入口；结果只包含相对路径，不读取或返回文件正文。
 */
public interface WorkspacePathSearchUseCase {
    /** 在当前已打开 Workspace 内执行有界路径检索。 */
    SearchResult search(SearchRequest request);

    /**
     * 搜索上下文由可信 handler 注入 runtime generation，客户端不能伪造服务端代际。
     */
    record SearchRequest(String threadId, String workspaceId, long runtimeGeneration,
                         String query, int limit) {
        /** 尽早冻结上下文与产品上限，文件系统规则仍由 adapter 统一执行。 */
        public SearchRequest {
            Objects.requireNonNull(threadId, "threadId");
            if (threadId.length() > 128 || threadId.indexOf('\0') >= 0 || threadId.isBlank()) {
                throw new IllegalArgumentException("invalid threadId");
            }
            Objects.requireNonNull(workspaceId, "workspaceId");
            if (workspaceId.length() > 100 || workspaceId.indexOf('\0') >= 0 || workspaceId.isBlank()) {
                throw new IllegalArgumentException("invalid workspaceId");
            }
            Objects.requireNonNull(query, "query");
            if (query.length() > 1_024 || query.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid query");
            }
            if (runtimeGeneration < 1 || runtimeGeneration > 9_007_199_254_740_991L) {
                throw new IllegalArgumentException("invalid runtimeGeneration");
            }
            if (limit < 1 || limit > 50) {
                throw new IllegalArgumentException("limit must be between 1 and 50");
            }
        }
    }

    /** 回传请求关联字段，使 WebView 能丢弃 Thread、Workspace 或代际切换后的迟到结果。 */
    record SearchResult(String threadId, String workspaceId, long runtimeGeneration,
                        String query, List<Entry> items, boolean truncated) {
        /** 复制条目列表，避免异步 transport 期间结果被外部修改。 */
        public SearchResult {
            threadId = Objects.requireNonNull(threadId, "threadId");
            workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
            query = Objects.requireNonNull(query, "query");
            items = List.copyOf(items);
        }
    }

    /** 单条建议只公开标准斜杠相对路径及物理条目类型。 */
    record Entry(String relativePath, WorkspaceEntryKind kind) {
        /** 禁止把空路径或缺失类型带入 RPC 投影。 */
        public Entry {
            Objects.requireNonNull(relativePath, "relativePath");
            if (relativePath.length() > 4_096 || relativePath.indexOf('\0') >= 0 || relativePath.isBlank()) {
                throw new IllegalArgumentException("invalid relativePath");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }
}
