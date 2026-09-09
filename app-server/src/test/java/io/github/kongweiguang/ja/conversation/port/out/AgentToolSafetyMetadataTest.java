// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.port.out;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 锁定 Tool 安全元数据的保守默认值及其持久绑定身份语义。 */
final class AgentToolSafetyMetadataTest {
    /** 未显式证明安全的实现必须同时保持外部副作用与不可观察工作区修改默认值。 */
    @Test
    void defaultsUnknownToolsToConservativeSafetyMetadata() {
        AgentTool tool = fixtureTool();

        assertEquals(ToolSideEffect.EXTERNAL, tool.sideEffect());
        assertEquals(AgentTool.WorkspaceMutationMode.UNOBSERVABLE, tool.workspaceMutationMode());
    }

    /** Schema 相同但副作用或工作区可观察性变化时 route hash 必须变化，禁止按旧安全声明恢复。 */
    @Test
    void includesSafetyMetadataInBuiltinBindingIdentity() {
        ToolSpec spec = new ToolSpec("fixture", "fixture tool", JsonObject.empty());
        AgentTool.ToolBindingDescriptor conservative = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.UNOBSERVABLE);
        AgentTool.ToolBindingDescriptor readOnly = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.READ_ONLY, AgentTool.WorkspaceMutationMode.NONE);
        AgentTool.ToolBindingDescriptor observableWrite = AgentTool.builtinBindingDescriptor(
                spec, ToolSideEffect.EXTERNAL, AgentTool.WorkspaceMutationMode.EXACT_TEXT);

        assertEquals(conservative.schemaHash(), readOnly.schemaHash());
        assertEquals(conservative.schemaHash(), observableWrite.schemaHash());
        assertNotEquals(conservative.routeHash(), readOnly.routeHash());
        assertNotEquals(conservative.routeHash(), observableWrite.routeHash());
        assertNotEquals(readOnly.routeHash(), observableWrite.routeHash());
    }

    /** 测试替身只提供规范与默认元数据，执行路径永远不应被触发。 */
    private static AgentTool fixtureTool() {
        return new AgentTool() {
            /** 固定空 Schema，确保断言只观察安全元数据默认值。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture", "fixture tool", JsonObject.empty());
            }

            /** 元数据测试不执行 Tool，意外调用以失败 Future 明确暴露。 */
            @Override
            public CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected tool execution"));
            }
        };
    }
}
