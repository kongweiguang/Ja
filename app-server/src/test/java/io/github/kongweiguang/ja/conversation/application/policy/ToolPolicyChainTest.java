// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.policy;

import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ToolPolicy;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 锁定 Tool 策略链的确定性排序、短路与失败关闭语义。 */
final class ToolPolicyChainTest {
    /** order 相同时按稳定 ID 排序，首次拒绝后不得继续调用后续策略。 */
    @Test
    void ordersPoliciesAndStopsAtFirstDenial() {
        List<String> trace = new ArrayList<>();
        ToolPolicyChain chain = new ToolPolicyChain(List.of(
                policy("zeta", 10, trace, ToolPolicy.Decision.allow()),
                policy("beta", 0, trace, ToolPolicy.Decision.deny("POLICY_DENIED", "denied")),
                policy("alpha", 0, trace, ToolPolicy.Decision.allow())));

        ToolPolicy.Decision decision = chain.evaluate(context());

        assertFalse(decision.proceed());
        assertEquals("POLICY_DENIED", decision.code());
        assertEquals(List.of("alpha", "beta"), trace);
    }

    /** 策略异常必须转换为稳定拒绝，不能越过策略链进入审批或 Tool 副作用。 */
    @Test
    void failsClosedWhenPolicyThrows() {
        ToolPolicy broken = new ToolPolicy() {
            /** 固定身份使日志可定位实现，但不携带 Tool 参数。 */
            @Override public String id() { return "broken"; }
            /** 测试夹具模拟策略实现故障。 */
            @Override public Decision evaluate(Context context) { throw new IllegalStateException("secret"); }
        };

        ToolPolicy.Decision decision = new ToolPolicyChain(List.of(broken)).evaluate(context());

        assertFalse(decision.proceed());
        assertEquals("TOOL_FAILED", decision.code());
        assertEquals("Tool policy rejected the call", decision.message());
    }

    /** 重复身份会使启动装配失败，禁止依赖列表覆盖或运行时插入顺序选策略。 */
    @Test
    void rejectsDuplicatePolicyIds() {
        assertThrows(IllegalArgumentException.class, () -> new ToolPolicyChain(List.of(
                policy("same", 0, new ArrayList<>(), ToolPolicy.Decision.allow()),
                policy("same", 1, new ArrayList<>(), ToolPolicy.Decision.allow()))));
    }

    /** 构造单一同步策略，执行次序和决定均由测试显式控制。 */
    private static ToolPolicy policy(
            String id, int order, List<String> trace, ToolPolicy.Decision decision) {
        return new ToolPolicy() {
            /** 返回稳定注册身份。 */
            @Override public String id() { return id; }
            /** 返回显式优先级，验证顺序不依赖注入列表。 */
            @Override public int order() { return order; }
            /** 记录被调用策略并返回固定决定。 */
            @Override public Decision evaluate(Context context) {
                trace.add(id);
                return decision;
            }
        };
    }

    /** 使用最小合法调用上下文，策略测试不触达真实 Tool 或文件系统。 */
    private static ToolPolicy.Context context() {
        AgentTool.Invocation invocation = new AgentTool.Invocation(
                "call_fixture", "read", JsonObjects.builder().putText("path", "x").build(), 0);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(
                "thr_fixture", "turn_fixture", Path.of(".").toAbsolutePath(),
                AccessMode.FULL_ACCESS, "cfg_fixture", Instant.now().plusSeconds(30), "ws_fixture");
        return new ToolPolicy.Context(invocation, execution, ToolSideEffect.READ_ONLY);
    }
}
