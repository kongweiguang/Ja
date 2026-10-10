// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证模型恢复提示和持久用户输出分别通过现有结果通道送达。 */
final class ProcessToolFactoryTest {
    /** 非零退出与截断必须进入真实模型正文，展示产物只保存命令输出而不复制会话句柄。 */
    @Test
    void retainsRecoveryAndExitFactsForModelWithoutPollutingPresentation() {
        ProcessSessionUseCase.Snapshot snapshot = new ProcessSessionUseCase.Snapshot(
                "proc_fixture", "ws_fixture", "thr_fixture", "turn_fixture", "call_fixture", 2,
                "exited", Instant.EPOCH, "build failed", 20, 20, true, 7);
        ProcessSessionUseCase owner = owner(snapshot);
        Path workspace = Path.of(".").toAbsolutePath().normalize();
        ShellProfile profile = new ShellProfile(ShellProfile.OperatingSystem.WINDOWS,
                ShellProfile.Dialect.POWERSHELL, Path.of(System.getProperty("java.home"), "bin", "java.exe"),
                List.of(), "windows", Map.of());
        JaTool tool = ProcessToolFactory.create(owner, profile).getFirst();
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_fixture", "exec_command",
                JsonObjects.builder().putText("cmd", "build").build(), 0);
        AgentIdentity identity = new AgentIdentity("tenant_fixture", "thr_fixture", "turn_fixture", "user_fixture");
        JaTool.ExecutionContext context = new JaTool.ExecutionContext(identity, invocation.callId(),
                identity.toolCallKey(invocation.callId()), "thr_fixture", "turn_fixture", workspace,
                AccessMode.FULL_ACCESS, "cfg_fixture", Instant.now().plusSeconds(30), "ws_fixture",
                null, null, TurnOrigin.USER);

        JaTool.ToolResult result = tool.execute(invocation, context, CancellationToken.none())
                .toCompletableFuture().join();
        AgentTool.ToolResult visible = new AgentTool.ToolResult(result.outcome(), result.content(),
                result.structuredContent(), result.errorCode(), result.artifactContent());
        ToolPresentationProjector.Completed completed = ToolPresentationProjector.completed(
                invocation, visible, workspace, List.of(), 10L);

        assertTrue(result.content().contains("session_id=proc_fixture"));
        assertTrue(result.content().contains("next_sequence=20"));
        assertTrue(result.content().contains("exit_code=7"));
        assertTrue(result.content().contains("output_truncated=true"));
        assertEquals("build failed", completed.presentation().stdout());
        assertEquals(7, completed.presentation().exitCode());
        assertTrue(completed.presentation().truncated());
        assertFalse(completed.artifactContent().contains("proc_fixture"));
    }

    /** 只替换进程 owner，保留真实工具参数解析和展示链路；测试不得启动宿主进程。 */
    private static ProcessSessionUseCase owner(ProcessSessionUseCase.Snapshot snapshot) {
        return (ProcessSessionUseCase) Proxy.newProxyInstance(ProcessSessionUseCase.class.getClassLoader(),
                new Class<?>[]{ProcessSessionUseCase.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "generation" -> snapshot.generation();
                    case "exec" -> snapshot;
                    default -> throw new AssertionError("unexpected process operation: " + method.getName());
                });
    }
}
