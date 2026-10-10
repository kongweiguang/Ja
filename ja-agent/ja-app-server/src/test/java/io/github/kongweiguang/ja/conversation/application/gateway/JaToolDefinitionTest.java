// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.gateway;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证请求级 Ja Tool 定义在物化时仍服从已纳入摘要的安全元数据。 */
final class JaToolDefinitionTest {
    private static final ToolSpec SPEC = new ToolSpec("fixture_tool", "Fixture tool",
            JsonObjects.builder().putText("type", "object").build());
    private static final JaTool.WorkspaceMutationMode MUTATION = JaTool.WorkspaceMutationMode.NONE;
    private static final JaTool.PlanAccess PLAN_ACCESS = JaTool.PlanAccess.DISALLOWED;
    private static final JaTool.ApprovalRequirement APPROVAL = JaTool.ApprovalRequirement.USER_REQUIRED;
    private static final JaToolCatalogIdentity IDENTITY = new JaToolCatalogIdentity(
            "a".repeat(64), "mcp_fixture", Set.of());

    /** 请求注册只消费已经入摘要的描述，binder 不能在最终 identity 注入时改变工具权限。 */
    @Test
    void materializesOnlyTheSafetyMetadataThatWasHashed() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool tool = tool(SPEC, ToolSideEffect.READ_ONLY, descriptor);
        AtomicReference<JaToolCatalogIdentity> boundIdentity = new AtomicReference<>();
        JaTool.Definition definition = definition(descriptor,
                identity -> {
                    boundIdentity.set(identity);
                    return tool;
                }, ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL);

        assertSame(tool, definition.materialize(IDENTITY));
        assertSame(IDENTITY, boundIdentity.get());
        assertEquals(SPEC, definition.spec());
    }

    /** 目录哈希完成后若 binder 改变副作用分类，物化必须在请求派发前失败。 */
    @Test
    void rejectsSafetyMetadataDriftDuringBinding() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool.Definition definition = definition(descriptor,
                ignored -> tool(SPEC, ToolSideEffect.EXTERNAL, MUTATION,
                        PLAN_ACCESS, APPROVAL, descriptor),
                ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL);

        assertThrows(IllegalStateException.class, () -> definition.materialize(IDENTITY));
    }

    /** 绑定不得悄悄放宽摘要阶段固定的用户审批要求。 */
    @Test
    void rejectsApprovalRequirementDriftDuringBinding() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool.Definition definition = new JaTool.Definition(SPEC, ToolSideEffect.READ_ONLY, MUTATION,
                descriptor, PLAN_ACCESS, JaTool.ApprovalRequirement.TRUSTED_INTERNAL,
                ignored -> tool(SPEC, ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL, descriptor));

        assertThrows(IllegalStateException.class, () -> definition.materialize(IDENTITY));
    }

    /** 绑定不能把禁止的 Plan 工具替换为受信内部写入口。 */
    @Test
    void rejectsPlanAuthorizationDriftDuringBinding() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool.Definition definition = new JaTool.Definition(SPEC, ToolSideEffect.READ_ONLY, MUTATION,
                descriptor, PLAN_ACCESS, APPROVAL,
                ignored -> tool(SPEC, ToolSideEffect.READ_ONLY, MUTATION,
                        JaTool.PlanAccess.INTERNAL_MUTATION, APPROVAL, descriptor));

        assertThrows(IllegalStateException.class, () -> definition.materialize(IDENTITY));
    }

    /** Schema 相同也不能用不同路由摘要把已审查的 Tool 绑定到另一个执行器。 */
    @Test
    void rejectsRouteDescriptorDriftDuringBinding() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool.ToolBindingDescriptor changedRoute = new JaTool.ToolBindingDescriptor(
                descriptor.routeKind(), descriptor.localName(), descriptor.serverId(), descriptor.remoteName(),
                descriptor.schemaHash(), "b".repeat(64));
        JaTool.Definition definition = definition(descriptor,
                ignored -> tool(SPEC, ToolSideEffect.READ_ONLY, MUTATION,
                        PLAN_ACCESS, APPROVAL, changedRoute),
                ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL);

        assertThrows(IllegalStateException.class, () -> definition.materialize(IDENTITY));
    }

    /** 摘要描述和 binder 必须指向同一个路由名，防止安全目录与执行器路由分叉。 */
    @Test
    void rejectsDescriptorForAnotherToolName() {
        ToolSpec other = new ToolSpec("other_tool", "Other tool",
                JsonObjects.builder().putText("type", "object").build());
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                other, ToolSideEffect.READ_ONLY, MUTATION);

        assertThrows(IllegalArgumentException.class, () -> definition(descriptor,
                ignored -> tool(SPEC, ToolSideEffect.READ_ONLY, MUTATION,
                        PLAN_ACCESS, APPROVAL, descriptor),
                ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL));
    }

    /** 同名定义会让目录摘要与绑定路由产生歧义，选择值必须在构造时拒绝。 */
    @Test
    void rejectsDuplicateDefinitionsInSelection() {
        JaTool.ToolBindingDescriptor descriptor = JaTool.builtinBindingDescriptor(
                SPEC, ToolSideEffect.READ_ONLY, MUTATION);
        JaTool.Definition definition = definition(descriptor,
                ignored -> tool(SPEC, ToolSideEffect.READ_ONLY, descriptor),
                ToolSideEffect.READ_ONLY, MUTATION, PLAN_ACCESS, APPROVAL);

        assertThrows(IllegalArgumentException.class,
                () -> new JaTool.Selection(java.util.List.of(definition, definition), Set.of(), ""));
    }

    /** 工具权限与副作用按身份隔离，重复字段错配必须在执行器取得上下文前拒绝。 */
    @Test
    void rejectsExecutionContextForAnotherThreadOrTurn() {
        assertThrows(IllegalArgumentException.class,
                () -> executionContext("thr_other", "turn_fixture", TurnOrigin.USER, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> executionContext("thr_fixture", "turn_other", TurnOrigin.USER, null, null));
    }

    /** 只有 Plan 执行携带完整关联身份，半套身份不能变成普通工具的授权依据。 */
    @Test
    void enforcesPlanIdentityForEveryOrigin() {
        for (TurnOrigin origin : java.util.List.of(TurnOrigin.USER,
                TurnOrigin.USER_CONTINUATION, TurnOrigin.CHILD_TASK)) {
            assertEquals(origin, executionContext("thr_fixture", "turn_fixture", origin, null, null).origin());
            assertThrows(IllegalArgumentException.class,
                    () -> executionContext("thr_fixture", "turn_fixture", origin, "revision_fixture", null));
            assertThrows(IllegalArgumentException.class,
                    () -> executionContext("thr_fixture", "turn_fixture", origin, null, "run_fixture"));
            assertThrows(IllegalArgumentException.class,
                    () -> executionContext("thr_fixture", "turn_fixture", origin, "revision_fixture", "run_fixture"));
        }
        assertThrows(IllegalArgumentException.class, () -> executionContext("thr_fixture", "turn_fixture",
                TurnOrigin.PLAN_EXECUTION, null, null));
        assertThrows(IllegalArgumentException.class, () -> executionContext("thr_fixture", "turn_fixture",
                TurnOrigin.PLAN_EXECUTION, "revision_fixture", null));
        assertThrows(IllegalArgumentException.class, () -> executionContext("thr_fixture", "turn_fixture",
                TurnOrigin.PLAN_EXECUTION, null, "run_fixture"));
        JaTool.ExecutionContext context = executionContext("thr_fixture", "turn_fixture",
                TurnOrigin.PLAN_EXECUTION, "revision_fixture", "run_fixture");
        assertEquals("revision_fixture", context.planRevisionId());
        assertEquals("run_fixture", context.runId());
    }

    /** Windows 可表示部分 ISO 控制字符，但持久 ChangeSet 不允许它们，收据必须先行拒绝。 */
    @Test
    void rejectsControlCharactersInConfinedMutationReceipt() {
        Path root = Path.of("C:/workspace");
        for (int control : java.util.List.of(0x7F, 0x85, 0x9F)) {
            String relative = "file" + (char) control + ".txt";
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> JaTool.MutationReceipt.confined(root, relative, root.resolve(relative),
                            false, "", true, "changed"));
            assertEquals("invalid mutation receipt relative path", failure.getMessage());
        }
        assertEquals("file.txt", JaTool.MutationReceipt.confined(root, "file.txt", root.resolve("file.txt"),
                false, "", true, "changed").confinedRelativePath());
    }

    /** 固定 Core 身份，仅改变待验证字段，避免非法调用键提前遮蔽归属校验。 */
    private static JaTool.ExecutionContext executionContext(String threadId, String turnId,
            TurnOrigin origin, String planRevisionId, String runId) {
        AgentIdentity identity = new AgentIdentity("tenant_fixture", "thr_fixture", "turn_fixture", "user_fixture");
        String callId = "call_fixture";
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId), threadId, turnId,
                Path.of("C:/workspace"), AccessMode.FULL_ACCESS, "cfg_fixture", Instant.parse("2026-01-01T00:00:00Z"),
                "ws_fixture", planRevisionId, runId, origin);
    }

    /** 固定同一项目录描述与实际物化行为，失败时不调用业务副作用。 */
    private static JaTool.Definition definition(JaTool.ToolBindingDescriptor descriptor,
            java.util.function.Function<JaToolCatalogIdentity, JaTool> binder, ToolSideEffect sideEffect,
            JaTool.WorkspaceMutationMode mutation, JaTool.PlanAccess planAccess,
            JaTool.ApprovalRequirement approval) {
        return new JaTool.Definition(SPEC, sideEffect, mutation, descriptor, planAccess, approval, binder);
    }

    /** 用只响应元数据查询的 JaTool 替身验证物化器，不掩盖执行路径测试。 */
    private static JaTool tool(ToolSpec spec, ToolSideEffect sideEffect,
                               JaTool.ToolBindingDescriptor descriptor) {
        return tool(spec, sideEffect, MUTATION, PLAN_ACCESS, APPROVAL, descriptor);
    }

    /** 通过独立可变参数构造物化结果，让每项安全声明都能分别验证 fail-closed。 */
    private static JaTool tool(ToolSpec spec, ToolSideEffect sideEffect,
                               JaTool.WorkspaceMutationMode mutation, JaTool.PlanAccess planAccess,
                               JaTool.ApprovalRequirement approval, JaTool.ToolBindingDescriptor descriptor) {
        return (JaTool) Proxy.newProxyInstance(JaTool.class.getClassLoader(), new Class<?>[]{JaTool.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "spec" -> spec;
                    case "sideEffect" -> sideEffect;
                    case "workspaceMutationMode" -> mutation;
                    case "planAccess" -> planAccess;
                    case "approvalRequirement" -> approval;
                    case "bindingDescriptor" -> descriptor;
                    case "toString" -> "JaToolFixture[" + spec.name() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
