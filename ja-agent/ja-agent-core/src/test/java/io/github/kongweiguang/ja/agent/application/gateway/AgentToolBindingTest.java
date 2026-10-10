// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结调用绑定必须可重建且对权限变化敏感，不能依赖请求对象的内存身份。 */
final class AgentToolBindingTest {
    private static final AgentTool TOOL = new TestTool();
    private static final String CATALOG = "a".repeat(64);
    private static final String PERMISSIONS = "b".repeat(64);

    /** 确认通用绑定只依赖冻结 schema 与显式策略，不从调用 ID 或参数推断路由。 */
    @Test
    void defaultBindingIsCallIndependentAndPermissionSensitive() {
        AgentTool.Invocation first = invocation("call_1", JsonObject.empty());
        AgentTool.Invocation second = invocation("call_2", new JsonObject(Map.of("value", new JsonText("x"))));
        AgentTool.ToolBindingContext context = new AgentTool.ToolBindingContext(CATALOG, PERMISSIONS);

        ToolExecutionBinding firstBinding = TOOL.executionBinding(first, context);
        ToolExecutionBinding secondBinding = TOOL.executionBinding(second, context);
        ToolExecutionBinding differentPermission = TOOL.executionBinding(first,
                new AgentTool.ToolBindingContext(CATALOG, "c".repeat(64)));

        assertEquals(firstBinding, secondBinding);
        assertEquals(JsonObject.empty(), firstBinding.route());
        assertNotEquals(firstBinding.permissionFingerprint(), differentPermission.permissionFingerprint());
        assertNotEquals(firstBinding, differentPermission);
    }

    /** 拒绝把其它已注册工具的调用标识套入当前适配器的恢复绑定。 */
    @Test
    void bindingRejectsAnotherToolName() {
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_1", "other_tool", JsonObject.empty(), 0);

        assertThrows(IllegalArgumentException.class, () -> TOOL.executionBinding(invocation,
                new AgentTool.ToolBindingContext(CATALOG, PERMISSIONS)));
    }

    /** 缺少宿主 reconciliation 能力时结果必须明确未知，不能被解释成可安全重派。 */
    @Test
    void missingRecoveryVerifierReturnsUnknown() {
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(
                new io.github.kongweiguang.ja.agent.domain.model.AgentIdentity("tenant", "thread", "turn", "user"),
                "call_" + "d".repeat(64), Map.of());

        AgentTool.RecoveryResolution resolution = TOOL.reconcile(invocation("call_1", JsonObject.empty()),
                context, Optional.empty(), io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken.none())
                .toCompletableFuture().join();

        assertTrue(resolution instanceof AgentTool.RecoveryResolution.Unknown);
    }

    /** 宿主扩展事实不能覆盖核心命名空间，避免伪造控制游标或事实类型。 */
    @Test
    void extensionFactsCannotUseCoreNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool.ExtensionFact("agent", "message", 1, JsonObject.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTool.ExtensionFact("agent.execution", "state", 1, JsonObject.empty()));
    }

    /** 两个资源抛出同一异常对象时仍由首个关闭故障作为结果，避免自抑制替换原始异常。 */
    @Test
    void combinedResourceFailureDoesNotSuppressItself() {
        IllegalStateException failure = new IllegalStateException("shared close failure");
        AgentTool.SettlementResource combined = AgentTool.SettlementResource.combine(
                () -> {
                    throw failure;
                },
                () -> {
                    throw failure;
                });

        assertSame(failure, assertThrows(IllegalStateException.class, combined::close));
        assertEquals(0, failure.getSuppressed().length);
    }

    /** 保持模型调用值不含外部执行，仅用于验证绑定身份的构造。 */
    private static AgentTool.Invocation invocation(String callId, JsonObject arguments) {
        return new AgentTool.Invocation(callId, "test_tool", arguments, 0);
    }

    /** 提供纯通用工具描述，确保默认 binding 不依赖具体 adapter 类型。 */
    private static final class TestTool implements AgentTool {
        private static final ToolSpec SPEC = new ToolSpec("test_tool", "A test tool",
                new JsonObject(Map.of("type", new JsonText("object"))));

        /** 返回稳定 schema 供默认 binding 内容寻址。 */
        @Override
        public ToolSpec spec() {
            return SPEC;
        }

        /** 测试无需派发；此实现仅满足工具端口并将副作用留在验证之外。 */
        @Override
        public java.util.concurrent.CompletionStage<ExecutionResult> execute(Invocation invocation,
                ExecutionContext context, io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
            return CompletableFuture.completedFuture(ExecutionResult.of(
                    new ToolResult(ToolOutcome.SUCCEEDED, "", Optional.empty(), null)));
        }
    }
}
