// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.loop;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 锁定 Tool 自声明副作用，禁止 Runner 按名称维护隐式白名单。 */
final class AgentToolRunnerClassificationTest {

    /** 默认始终保守为 EXTERNAL，只有 Tool 显式声明后才能取得 READ_ONLY 语义。 */
    @Test
    void toolDeclarationOwnsSideEffectClassification() {
        assertEquals(ToolSideEffect.EXTERNAL, tool("read", null).sideEffect());
        assertEquals(ToolSideEffect.EXTERNAL, tool("mcp_custom", null).sideEffect());
        assertEquals(ToolSideEffect.READ_ONLY, tool("read", ToolSideEffect.READ_ONLY).sideEffect());
    }

    /** 测试 Tool 可选择显式副作用；null 保留端口定义的保守默认值。 */
    private static AgentTool tool(String name, ToolSideEffect declared) {
        return new AgentTool() {
            /** 名称是本用例唯一变化量，空 Schema 足够覆盖分类边界。 */
            @Override public ToolSpec spec() { return new ToolSpec(name, "fixture", JsonObject.empty()); }

            /** 仅为显式只读夹具覆盖默认值，避免测试重新引入名字推断。 */
            @Override public ToolSideEffect sideEffect() {
                return declared == null ? AgentTool.super.sideEffect() : declared;
            }

            /** 分类测试若触发执行即说明 AgentToolRunner 边界发生回归。 */
            @Override public CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected tool execution"));
            }
        };
    }
}
