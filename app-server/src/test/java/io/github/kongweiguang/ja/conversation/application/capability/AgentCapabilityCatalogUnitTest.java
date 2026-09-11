// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.capability;

import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证极简能力目录只固定装配与安全描述，不承担领域权限或执行状态。 */
final class AgentCapabilityCatalogUnitTest {
    /** 注册输入顺序不影响 prepare 顺序，重复能力 ID 在启动时直接失败。 */
    @Test
    void freezesRegistrationOrderAndRejectsDuplicateIds() {
        AgentCapability later = capability("fixture.z", 20, "tool_z", "later");
        AgentCapability first = capability("fixture.a", 10, "tool_a", "first");
        AgentCapabilityCatalog catalog = new AgentCapabilityCatalog(List.of(later, first));

        assertEquals("first\nlater", catalog.prepare(request()).promptFragment());
        assertThrows(IllegalArgumentException.class, () -> new AgentCapabilityCatalog(List.of(
                capability("fixture.same", 1, "tool_a", "a"),
                capability("fixture.same", 2, "tool_b", "b"))));
    }

    /** Tool 重名按当前请求可见集合判断，失败发生在 Provider 看到目录之前。 */
    @Test
    void rejectsRequestScopedDuplicateTools() {
        AgentCapabilityCatalog catalog = new AgentCapabilityCatalog(List.of(
                capability("fixture.a", 1, "same_tool", "a"),
                capability("fixture.b", 2, "same_tool", "b")));

        assertThrows(IllegalArgumentException.class, () -> catalog.prepare(request()));
    }

    /** Binder 若替换副作用或路由身份必须失败，不能把 prepare 描述当作未经验证的承诺。 */
    @Test
    void rejectsToolThatChangesAfterPrepare() {
        ToolSpec spec = spec("drifting_tool");
        AgentTool.ToolBindingDescriptor descriptor = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE);
        AgentCapability capability = new AgentCapability() {
            /** 稳定 fixture ID。 */ @Override public String id() { return "fixture.drift"; }
            /** 单能力无需额外排序。 */ @Override public int order() { return 1; }
            /** 声明只读但物化外部副作用，用于证明 Catalog 会复核。 */
            @Override public Prepared prepare(Request request) {
                return new Prepared("", List.of(new ToolContribution(spec, ToolSideEffect.READ_ONLY,
                        AgentTool.WorkspaceMutationMode.NONE, descriptor,
                        ignored -> tool(spec, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.NONE))));
            }
        };
        AgentCapabilityCatalog.PreparedCapabilities prepared =
                new AgentCapabilityCatalog(List.of(capability)).prepare(request());

        assertThrows(IllegalStateException.class, () -> prepared.bind(identity()));
    }

    /** Binder 若丢失可信内核审批标记必须 fail-closed，避免物化阶段意外重新打开用户审批边界。 */
    @Test
    void rejectsToolThatChangesApprovalRequirementAfterPrepare() {
        ToolSpec spec = spec("internal_tool");
        AgentTool.ToolBindingDescriptor descriptor = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE);
        AgentCapability capability = new AgentCapability() {
            /** 稳定 fixture ID。 */
            @Override public String id() { return "fixture.approval_drift"; }
            /** 单能力无需额外排序。 */
            @Override public int order() { return 1; }
            /** prepare 声明可信内核操作，但 binder 返回默认要求用户审批的 Tool。 */
            @Override public Prepared prepare(Request request) {
                return new Prepared("", List.of(new ToolContribution(spec, ToolSideEffect.READ_ONLY,
                        AgentTool.WorkspaceMutationMode.NONE, descriptor, AgentTool.PlanAccess.DISALLOWED,
                        AgentTool.ApprovalRequirement.TRUSTED_INTERNAL,
                        ignored -> tool(spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE))));
            }
        };
        AgentCapabilityCatalog.PreparedCapabilities prepared =
                new AgentCapabilityCatalog(List.of(capability)).prepare(request());

        assertThrows(IllegalStateException.class, () -> prepared.bind(identity()));
    }

    /** 构造一个 schema 与执行实现同源的能力 fixture。 */
    private static AgentCapability capability(String id, int order, String toolName, String prompt) {
        ToolSpec spec = spec(toolName);
        AgentTool.ToolBindingDescriptor descriptor = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE);
        return new AgentCapability() {
            /** 返回冻结 ID。 */ @Override public String id() { return id; }
            /** 返回冻结顺序。 */ @Override public int order() { return order; }
            /** 同一个 ToolSpec 同时服务目录与真实 Tool。 */
            @Override public Prepared prepare(Request request) {
                return new Prepared(prompt, List.of(new ToolContribution(spec, ToolSideEffect.READ_ONLY,
                        AgentTool.WorkspaceMutationMode.NONE, descriptor,
                        ignored -> tool(spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE))));
            }
        };
    }

    /** 创建最小稳定 ToolSpec。 */
    private static ToolSpec spec(String name) {
        return new ToolSpec(name, "fixture tool", JsonObjects.builder().putText("type", "object")
                .put("properties", JsonObject.empty()).putBoolean("additionalProperties", false).build());
    }

    /** 创建不执行 IO 的 Tool，测试只观察其冻结安全描述。 */
    private static AgentTool tool(ToolSpec spec, ToolSideEffect sideEffect,
                                  AgentTool.WorkspaceMutationMode mutationMode) {
        return new AgentTool() {
            /** 返回共享规格。 */ @Override public ToolSpec spec() { return spec; }
            /** 返回测试指定副作用。 */ @Override public ToolSideEffect sideEffect() { return sideEffect; }
            /** 返回测试指定工作区模式。 */
            @Override public WorkspaceMutationMode workspaceMutationMode() { return mutationMode; }
            /** 测试不调用执行；仍返回稳定成功值保持完整实现。 */
            @Override public java.util.concurrent.CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
                return CompletableFuture.completedFuture(new ToolResult(
                        io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome.SUCCEEDED,
                        "ok", Optional.empty(), null));
            }
        };
    }

    /** 构造请求级冻结身份。 */
    private static AgentCapability.Request request() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        return new AgentCapability.Request("thr_test", "turn_test", Path.of("C:\\ja-capability").toAbsolutePath(),
                "ws_test", preferences, "cfg_test", true, Instant.parse("2026-09-08T12:00:00Z"), TurnOrigin.USER);
    }

    /** 构造最终摘要身份，binder 不应观察任何可变集合。 */
    private static AgentCapability.CatalogIdentity identity() {
        return new AgentCapability.CatalogIdentity("a".repeat(64), "mcp_test", Set.of());
    }
}
