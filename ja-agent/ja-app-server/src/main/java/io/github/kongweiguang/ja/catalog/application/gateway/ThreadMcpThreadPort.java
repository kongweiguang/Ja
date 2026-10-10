// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application.gateway;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;

import java.util.Optional;

/**
 * Thread MCP 状态只需读取一份有限历史快照；不把 Conversation 的完整入站用例带入 Catalog。
 */
@FunctionalInterface
public interface ThreadMcpThreadPort {
    /**
     * 读取指定 Thread 的头部及最近一条 Turn；游标保持与 Conversation 权威接口相同的字符串语义，
     * 让 Catalog 不自行解释持久化分页格式。
     */
    Optional<ThreadSnapshot> readThread(String threadId, String beforeCursor, int limit);
}
