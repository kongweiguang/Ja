// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在真实 PowerShell 子进程上验证 node-like `.cmd`、双流、退出码与展示计时。 */
final class ShellToolTest {
    @TempDir Path temp;

    /**
     * 该用例复现 sidecar 缺 PATHEXT 时 `node` 不可发现的故障，并证明安全环境补全后可以
     * 解析 `.cmd`，同时保留 stdout/stderr、非零退出码和调用方测得的 duration。
     */
    @Test
    void executesNodeLikeCommandWithBoundedWindowsEnvironment() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        ShellProfile detected = ShellCapability.detectAndPreflight().profile().orElse(null);
        Assumptions.assumeTrue(detected != null && (detected.dialect() == ShellProfile.Dialect.POWERSHELL
                || detected.dialect() == ShellProfile.Dialect.WINDOWS_POWERSHELL));

        Path bin = Files.createDirectory(temp.resolve("node-bin"));
        Files.writeString(bin.resolve("node.cmd"),
                "@echo off\r\n@echo node-stdout\r\n@echo node-stderr 1>&2\r\n@exit /b 7\r\n",
                StandardCharsets.UTF_8);
        Map<String, String> source = new HashMap<>();
        source.put("PATH", bin.toString());
        copyHost(source, "SystemRoot");
        copyHost(source, "ComSpec");
        source.put("TEMP", temp.toString());
        source.put("TMP", temp.toString());
        ShellProfile profile = new ShellProfile(detected.os(), detected.dialect(), detected.executable(),
                detected.arguments(), detected.pathStyle(),
                ShellProcessEnvironment.capture(ShellProfile.OperatingSystem.WINDOWS, source::get));
        ShellTool tool = new ShellTool(profile);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_node", "shell",
                JsonObjects.builder().putText("command", "node --probe; exit $LASTEXITCODE").build(), 0);
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(
                "thr_shell", "turn_shell", temp.toAbsolutePath(), AccessMode.FULL_ACCESS,
                "cfg_shell", Instant.now().plusSeconds(30), "ws_shell");

        AgentTool.ToolResult result = tool.execute(invocation, context, CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertTrue(result.content().contains("[stdout]\nnode-stdout"));
        assertTrue(result.content().contains("[stderr]\nnode-stderr"));
        JsonObject metadata = (JsonObject) result.structuredContent().orElseThrow();
        assertEquals(7, ((JsonNumber) metadata.members().get("exit_code")).value().intValueExact());
        ToolPresentation presentation = ToolPresentationProjector.completed(
                invocation, result, temp, List.of(), 25L).presentation();
        assertEquals(7, presentation.exitCode());
        assertEquals(25L, presentation.durationMs());
        assertNotNull(presentation.artifactId());
    }

    /** 测试只复制产品白名单中的非敏感宿主变量，不把完整环境注入 fixture。 */
    private static void copyHost(Map<String, String> target, String key) {
        String value = System.getenv(key);
        if (value != null) target.put(key, value);
    }
}
