// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 验证 Ja 工具默认权限保持保守，且业务安全声明进入可恢复路由身份。 */
final class JaToolSafetyMetadataTest {
    /** 未证明安全的 Ja Tool 必须同时按外部副作用和不可观察工作区修改处理。 */
    @Test
    void defaultsUnknownToolsToConservativeSafetyMetadata() {
        JaTool tool = fixtureTool();

        assertEquals(ToolSideEffect.EXTERNAL, tool.sideEffect());
        assertEquals(JaTool.WorkspaceMutationMode.UNOBSERVABLE, tool.workspaceMutationMode());
    }

    /** Schema 相同但副作用或工作区可观察性变化时，绑定身份必须要求重新核验授权。 */
    @Test
    void includesSafetyMetadataInBuiltinBindingIdentity() {
        ToolSpec spec = new ToolSpec("fixture", "fixture tool", JsonObject.empty());
        JaTool.ToolBindingDescriptor conservative = JaTool.builtinBindingDescriptor(
                spec, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.UNOBSERVABLE);
        JaTool.ToolBindingDescriptor readOnly = JaTool.builtinBindingDescriptor(
                spec, ToolSideEffect.READ_ONLY, JaTool.WorkspaceMutationMode.NONE);
        JaTool.ToolBindingDescriptor observableWrite = JaTool.builtinBindingDescriptor(
                spec, ToolSideEffect.EXTERNAL, JaTool.WorkspaceMutationMode.EXACT_TEXT);

        assertEquals(conservative.schemaHash(), readOnly.schemaHash());
        assertEquals(conservative.schemaHash(), observableWrite.schemaHash());
        assertNotEquals(conservative.routeHash(), readOnly.routeHash());
        assertNotEquals(conservative.routeHash(), observableWrite.routeHash());
        assertNotEquals(readOnly.routeHash(), observableWrite.routeHash());
    }

    /** 只实现工具结构与执行端口；本测试从不派发该动作。 */
    private static JaTool fixtureTool() {
        return new JaTool() {
            /** 返回固定空 Schema，避免元数据断言依赖具体业务参数。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture", "fixture tool", JsonObject.empty());
            }

            /** 元数据测试不应执行工具；意外派发会以明确失败暴露。 */
            @Override
            public CompletionStage<ToolResult> execute(AgentTool.Invocation invocation, ExecutionContext context,
                    CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected tool execution"));
            }
        };
    }
}
