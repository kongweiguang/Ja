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

/** 锁定 Tool 副作用分类，避免 read/read_attachment 被误记为可能修改工作区。 */
final class AgentToolRunnerClassificationTest {

    /** 只有读取类 Tool 是 READ_ONLY；写入和未知扩展仍需外部副作用保护。 */
    @Test
    void readToolsNeverProduceExternalMutationClassification() {
        assertEquals(ToolSideEffect.READ_ONLY, AgentToolRunner.sideEffect(tool("read")));
        assertEquals(ToolSideEffect.READ_ONLY, AgentToolRunner.sideEffect(tool("read_attachment")));
        assertEquals(ToolSideEffect.EXTERNAL, AgentToolRunner.sideEffect(tool("edit")));
        assertEquals(ToolSideEffect.EXTERNAL, AgentToolRunner.sideEffect(tool("mcp_custom")));
    }

    /** 测试 Tool 只暴露冻结名称，不执行任何外部能力。 */
    private static AgentTool tool(String name) {
        return new AgentTool() {
            /** 名称是本用例唯一变化量，空 Schema 足够覆盖分类边界。 */
            @Override public ToolSpec spec() { return new ToolSpec(name, "fixture", JsonObject.empty()); }

            /** 分类测试若触发执行即说明 AgentToolRunner 边界发生回归。 */
            @Override public CompletionStage<ToolResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("unexpected tool execution"));
            }
        };
    }
}
