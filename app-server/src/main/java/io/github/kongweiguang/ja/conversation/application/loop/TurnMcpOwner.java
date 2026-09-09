// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.TurnToolSessionFactory;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.List;
import java.util.Objects;

/**
 * 独占一次 Provider 请求或恢复批次的 MCP Tool 会话，约束为只打开一次并在该安全边界关闭一次。
 */
final class TurnMcpOwner implements AutoCloseable {
    private TurnToolSessionFactory.Session session;
    private boolean closed;

    /**
     * 为当前请求或恢复批次创建唯一会话；重复打开或关闭后重用会破坏目录一致性，因此直接拒绝。
     */
    void open(TurnToolSessionFactory factory, CancellationToken cancellation) {
        if (session != null || closed) {
            throw new IllegalStateException("MCP session owner already used");
        }
        session = Objects.requireNonNull(factory.open(cancellation), "Tool session");
    }

    /**
     * 返回当前安全点解析出的 Tool 列表，未打开或已关闭时禁止越过资源边界。
     */
    List<AgentTool> tools() {
        if (session == null || closed) {
            throw new IllegalStateException("MCP session is unavailable");
        }
        return List.copyOf(session.tools());
    }

    /**
     * 幂等释放会话，使异常路径与正常终态可以共享同一收口逻辑。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (session != null) {
            session.close();
        }
    }
}
