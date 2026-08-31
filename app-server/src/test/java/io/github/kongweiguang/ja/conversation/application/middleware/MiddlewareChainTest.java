// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.middleware;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.in.TurnEvent;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定窄 Middleware 的顺序、审批短路和提交后异常隔离。 */
class MiddlewareChainTest {
    /** before 正序、after 逆序，首次拒绝阻止后续 beforeTool。 */
    @Test
    void ordersHooksAndShortCircuitsToolDecision() {
        List<String> trace = new ArrayList<>();
        AgentMiddleware first = middleware("first", trace, true, false);
        AgentMiddleware denying = middleware("denying", trace, false, false);
        AgentMiddleware skipped = middleware("skipped", trace, true, false);
        MiddlewareChain chain = new MiddlewareChain(List.of(first, denying, skipped));
        AgentMiddleware.ToolContext context = toolContext(AccessMode.FULL_ACCESS, () -> true);
        assertFalse(chain.beforeTool(context).proceed());
        chain.afterTool(context, AgentTool.ToolResult.success("ok"));
        assertEquals(List.of("before:first", "before:denying", "after:skipped", "after:denying", "after:first"),
                trace.stream().filter(value -> value.startsWith("before") || value.startsWith("after")).toList());
    }

    /** 提交后观察器抛错不会阻止后续观察器处理同一已提交事件。 */
    @Test
    void eventObserverFailureIsIsolated() {
        List<String> trace = new ArrayList<>();
        MiddlewareChain chain = new MiddlewareChain(List.of(
                middleware("broken", trace, true, true), middleware("healthy", trace, true, false)));
        chain.onEventCommitted(new TurnEvent.TextDelta("turn_fixture", 1, "x"));
        assertEquals(List.of("event:broken", "event:healthy"), trace);
    }

    /** 两档权限分别验证逐次审批与完全放开，不建立 SessionGrant。 */
    @Test
    void approvalMiddlewareRequestsEveryProtectedTool() {
        ApprovalMiddleware middleware = new ApprovalMiddleware();
        AtomicInteger requests = new AtomicInteger();
        AgentMiddleware.ApprovalGate deny = () -> { requests.incrementAndGet(); return false; };
        assertFalse(middleware.beforeTool(toolContext(AccessMode.APPROVAL_REQUIRED, deny)).proceed());
        assertFalse(middleware.beforeTool(toolContext(AccessMode.APPROVAL_REQUIRED, deny)).proceed());
        assertEquals(2, requests.get());
        assertTrue(middleware.beforeTool(toolContext(AccessMode.FULL_ACCESS, deny)).proceed());
        assertEquals(2, requests.get());
    }

    /** 构造只记录 Hook 的 Middleware，拒绝和异常由参数显式控制。 */
    private static AgentMiddleware middleware(String name, List<String> trace, boolean proceed, boolean failEvent) {
        return new AgentMiddleware() {
            /** 记录前置顺序，并按夹具参数决定是否短路。 */
            @Override public ToolDecision beforeTool(ToolContext context) {
                trace.add("before:" + name);
                return proceed ? ToolDecision.allow() : ToolDecision.deny("DENIED", "denied");
            }
            /** 记录逆序后置调用，不改写 Tool 结果。 */
            @Override public void afterTool(ToolContext context, AgentTool.ToolResult result) {
                trace.add("after:" + name);
            }
            /** 先记录观察事实，再按夹具触发可隔离异常。 */
            @Override public void onEventCommitted(TurnEvent event) {
                trace.add("event:" + name);
                if (failEvent) throw new IllegalStateException("fixture");
            }
        };
    }

    /** 绑定最小合法 Tool 身份，审批次数由注入 Gate 观测。 */
    private static AgentMiddleware.ToolContext toolContext(AccessMode mode, AgentMiddleware.ApprovalGate gate) {
        AgentTool.Invocation invocation = new AgentTool.Invocation(
                "call_fixture", "read", JsonObjects.builder().putText("path", "x").build(), 0);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext("thr_fixture", "turn_fixture",
                Path.of(".").toAbsolutePath(), mode, "cfg_fixture", Instant.now().plusSeconds(30), "ws_fixture");
        return new AgentMiddleware.ToolContext(invocation, execution, gate);
    }
}
