// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.application.presentation.ToolPresentationProjector;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonNumber;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.platform.windows.WindowsJobObject;
import io.github.kongweiguang.ja.platform.windows.WindowsProcessLauncher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在真实 PowerShell 与 Windows Job Object 上验证 Shell 的 EOF、超时、取消、双流和进程树闭环。 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
final class ShellToolTest {
    private static final Duration PROCESS_GONE_TIMEOUT = Duration.ofSeconds(3);

    @TempDir Path temp;
    private ShellProfile detected;

    /** 每个测试都要求真实 Windows PowerShell Profile，禁止平台跳过后把进程树契约误报为通过。 */
    @BeforeEach
    void requireWindowsShell() {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        detected = ShellCapability.detectAndPreflight().profile().orElse(null);
        Assumptions.assumeTrue(detected != null && (detected.dialect() == ShellProfile.Dialect.POWERSHELL
                || detected.dialect() == ShellProfile.Dialect.WINDOWS_POWERSHELL));
    }

    /**
     * 该用例复现 sidecar 缺 PATHEXT 时 `node` 不可发现的故障，并证明安全环境补全后可以
     * 解析 `.cmd`，同时保留 stdout/stderr、非零退出码和调用方测得的 duration。
     */
    @Test
    void executesNodeLikeCommandWithBoundedWindowsEnvironment() throws Exception {
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
        AgentTool.Invocation invocation = invocation("node --probe; exit $LASTEXITCODE", null);

        AgentTool.ToolResult result = execute(profile, invocation, CancellationToken.none());

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

    /** 用 junction 复现路径策略分歧；20 秒仅给功能探测的系统 Shell 冷启动，超时语义由独立用例验证。 */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void windowsAppsAliasFallsBackToExecutableShell() throws Exception {
        Path aliases = temp.resolve("aliases");
        Process junction = new ProcessBuilder(System.getenv("ComSpec"), "/c", "mklink", "/J",
                aliases.toString(), detected.executable().getParent().toString()).start();
        try {
            assertTrue(junction.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, junction.exitValue());
        } finally {
            if (junction.isAlive()) junction.destroyForcibly();
        }
        ShellProfile alias = new ShellProfile(detected.os(), detected.dialect(),
                aliases.resolve(detected.executable().getFileName()), detected.arguments(),
                detected.pathStyle(), detected.environment());
        ProcessBuilder oldProbe = new ProcessBuilder(alias.commandLine("exit 0"));
        oldProbe.environment().clear();
        oldProbe.environment().putAll(alias.environment());
        Process acceptedByJvm = oldProbe.start();
        try {
            assertTrue(acceptedByJvm.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, acceptedByJvm.exitValue());
        } finally {
            if (acceptedByJvm.isAlive()) acceptedByJvm.destroyForcibly();
            acceptedByJvm.getInputStream().close();
            acceptedByJvm.getErrorStream().close();
            acceptedByJvm.getOutputStream().close();
        }
        assertFalse(alias.preflight());
        Map<String, String> source = new HashMap<>();
        source.put("PATH", aliases.toString());
        copyHost(source, "SystemRoot");
        copyHost(source, "TEMP");
        copyHost(source, "TMP");
        copyHost(source, "PSModuleAnalysisCachePath");
        ShellProfile profile = ShellCapability.detectAndPreflight("Windows 11", source::get,
                ShellProfile::preflight).profile().orElseThrow();
        assertEquals(ShellProfile.Dialect.WINDOWS_POWERSHELL, profile.dialect());

        AgentTool.ToolResult result = execute(profile,
                invocation("Write-Output 'shell-preflight-execution-ok'", 20_000), CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome(), result.content());
        assertTrue(result.content().contains("shell-preflight-execution-ok"));
    }

    /**
     * Shell 没有交互输入语义，启动后必须立即关闭 stdin；ReadToEnd 在硬 Deadline 前返回空值，
     * 直接锁定无路径 rg 等“等待标准输入”程序不再挂住第一轮。
     */
    @Test
    void closesStdinImmediatelyAndReadToEndObservesEof() {
        String command = "$input=[Console]::In.ReadToEnd(); "
                + "if ($input.Length -eq 0) { Write-Output 'stdin-eof' } else { exit 9 }";

        AgentTool.ToolResult result = execute(detected, invocation(command, 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("stdin-eof"));
    }

    /**
     * Shell 自身默认两分钟、最短 100ms、最长十分钟；短搜索默认快速收口，Native Image 等长构建
     * 可显式提高，但非法值在任何进程启动前被拒绝且永远不能越过 Turn Deadline。
     */
    @Test
    void exposesAndEnforcesBoundedTimeoutContract() {
        assertEquals(120_000, ShellTool.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(100, ShellTool.MIN_TIMEOUT_MILLIS);
        assertEquals(600_000, ShellTool.MAX_TIMEOUT_MILLIS);

        AgentTool.ToolResult below = execute(detected, invocation("exit 0", 99), CancellationToken.none());
        AgentTool.ToolResult above = execute(detected, invocation("exit 0", 600_001), CancellationToken.none());

        assertEquals("tool_arguments_invalid", below.errorCode());
        assertEquals("tool_arguments_invalid", above.errorCode());
    }

    /**
     * Rust Host 会在 env_clear 后传入宿主 PATH；Windows 合法环境可超过旧的 8 KiB 人工上限，
     * Shell 启动策略必须按 CreateProcess 环境块总预算接纳，否则所有命令都会在 1ms 级失败。
     */
    @Test
    void executesWithWindowsRuntimePathAboveEightKilobytes() {
        Map<String, String> environment = new HashMap<>(detected.environment());
        String segment = "C:\\runtime-segment";
        String longPath = java.util.stream.IntStream.range(0, 500)
                .mapToObj(index -> segment + index)
                .collect(java.util.stream.Collectors.joining(";"));
        assertTrue(longPath.length() > 8_192);
        environment.put("PATH", longPath);
        ShellProfile profile = new ShellProfile(
                detected.os(), detected.dialect(), detected.executable(), detected.arguments(),
                detected.pathStyle(), environment);

        AgentTool.ToolResult result = execute(
                profile, invocation("Write-Output 'long-path-ok'", 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("long-path-ok"));
    }

    /** 启动前 cwd 校验失败必须返回可行动稳定码和非空诊断，不再伪装成 Shell 未安装。 */
    @Test
    void reportsStableWorkingDirectoryFailure() {
        Path missing = temp.resolve("missing-workspace");

        AgentTool.ToolResult result = execute(detected, invocation("Write-Output ignored", 2_000),
                CancellationToken.none(), missing);

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("shell_working_directory_invalid", result.errorCode());
        assertTrue(result.content().startsWith("[stderr]\nShell failed: shell_working_directory_invalid."));
    }

    /** Profile 路径在冻结后消失时返回 executable 专用稳定码，模型据此停止重复相同命令。 */
    @Test
    void reportsStableExecutableFailure() {
        ShellProfile missing = new ShellProfile(
                detected.os(), detected.dialect(), temp.resolve("missing-pwsh.exe"), detected.arguments(),
                detected.pathStyle(), detected.environment());

        AgentTool.ToolResult result = execute(
                missing, invocation("Write-Output ignored", 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("shell_executable_unavailable", result.errorCode());
        assertTrue(result.content().contains("Shell failed: shell_executable_unavailable."));
    }

    /** 超过 CreateProcess 总环境块预算时必须在分配句柄前拒绝，并给出 environment 专用码。 */
    @Test
    void reportsStableEnvironmentBudgetFailure() {
        Map<String, String> environment = new HashMap<>(detected.environment());
        environment.put("PATH", "C:\\" + "p".repeat(20_000));
        environment.put("TEMP", "C:\\" + "t".repeat(20_000));
        ShellProfile oversized = new ShellProfile(
                detected.os(), detected.dialect(), detected.executable(), detected.arguments(),
                detected.pathStyle(), environment);

        AgentTool.ToolResult result = execute(
                oversized, invocation("Write-Output ignored", 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("shell_environment_invalid", result.errorCode());
        assertTrue(result.content().contains("Shell failed: shell_environment_invalid."));
    }

    /** 独立 Tool timeout 必须早于宽松 Turn Deadline 返回，并在返回前确认根与后代均已消失。 */
    @Test
    void timeoutKillsRootAndDescendantBeforeReturning() throws Exception {
        Path pids = temp.resolve("timeout-pids.txt");
        String command = spawnLongLivedChildAndWritePids(pids);
        Instant started = Instant.now();

        AgentTool.ToolResult result = execute(detected, invocation(command, 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.CANCELLED, result.outcome());
        assertEquals("shell_timeout", result.errorCode());
        assertTrue(Duration.between(started, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0);
        assertRecordedProcessesGone(pids);
    }

    /** Turn 取消回调必须关闭 Job Object，并等待根进程及后代清理完成后才结算 shell_cancelled。 */
    @Test
    void cancellationKillsRootAndDescendantBeforeReturning() throws Exception {
        Path pids = temp.resolve("cancel-pids.txt");
        CancellationSource cancellation = new CancellationSource();
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("ja-shell-cancel-test").factory());
        try {
            Future<AgentTool.ToolResult> pending = executor.submit(() -> execute(detected,
                    invocation(spawnLongLivedChildAndWritePids(pids), 10_000), cancellation));
            awaitFile(pids, Duration.ofSeconds(3));

            cancellation.cancel("test_cancelled");
            AgentTool.ToolResult result = pending.get(5, TimeUnit.SECONDS);

            assertEquals(ToolOutcome.CANCELLED, result.outcome());
            assertEquals("shell_cancelled", result.errorCode());
            assertRecordedProcessesGone(pids);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /** 根正常退出时仍关闭 Job，防止后台后代继承 stdout/stderr 后让 reader 等不到 EOF。 */
    @Test
    void normalRootExitClosesBackgroundDescendantAndReaders() throws Exception {
        Path childPid = temp.resolve("background-child.txt");
        String command = "$child=Start-Process -FilePath $env:ComSpec "
                + "-ArgumentList @('/d','/c','ping.exe','127.0.0.1','-n','120') -PassThru; "
                + "Set-Content -LiteralPath " + quote(childPid) + " -Value ([string]$child.Id); "
                + "Write-Output 'root-complete'; exit 0";

        AgentTool.ToolResult result = execute(detected, invocation(command, 2_000), CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertTrue(result.content().contains("root-complete"));
        long pid = Long.parseLong(Files.readString(childPid, StandardCharsets.UTF_8).trim());
        assertProcessGone(pid, PROCESS_GONE_TIMEOUT);
    }

    /**
     * 以 Host Job 托管独立 App Server JVM，再从其中执行完整 ShellTool；这条探针覆盖真实桌面
     * 链特有的父 Job、env_clear 和独立 JVM 边界，而不是仅验证当前 Maven JVM 的内层进程。
     */
    @Test
    void executesPowerShellInsideHostedAppServerProcess() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        Map<String, String> environment = minimalWindowsEnvironment();
        List<String> appServerCommand = List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                HostedShellProbe.class.getName(),
                temp.toAbsolutePath().toString());

        try (WindowsJobObject hostJob = WindowsJobObject.create()) {
            Process appServer = WindowsProcessLauncher.launch(
                    appServerCommand, temp, environment, hostJob);
            try {
                appServer.getOutputStream().close();
                assertTrue(appServer.waitFor(8, TimeUnit.SECONDS), "hosted ShellTool probe must settle");
                String stdout = new String(appServer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(appServer.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

                assertEquals(0, appServer.exitValue(), "hosted probe stderr=" + stderr);
                assertTrue(stdout.contains("hosted-shell-ok"), "hosted probe stdout=" + stdout);
                hostJob.closeAfterRootExit();
            } finally {
                if (appServer.isAlive()) {
                    hostJob.terminate();
                }
                WindowsProcessLauncher.close(appServer);
            }
        }
    }

    /** 使用同一冻结 Profile 和宽松 Turn Deadline执行，确保测试测到的是 Shell 独立 timeout。 */
    private AgentTool.ToolResult execute(
            ShellProfile profile, AgentTool.Invocation invocation, CancellationToken cancellation) {
        return execute(profile, invocation, cancellation, temp.toAbsolutePath());
    }

    /** 允许失败用例替换 workspace 根，同时仍经过完整 ToolSupport 执行边界。 */
    private static AgentTool.ToolResult execute(
            ShellProfile profile, AgentTool.Invocation invocation, CancellationToken cancellation,
            Path workspace) {
        ShellTool tool = new ShellTool(profile);
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(
                "thr_shell", "turn_shell", workspace.toAbsolutePath(), AccessMode.FULL_ACCESS,
                "cfg_shell", Instant.now().plusSeconds(30), "ws_shell");
        return tool.execute(invocation, context, cancellation).toCompletableFuture().join();
    }

    /** 构造当前唯一 command/timeout_ms 调用格式；null 表示使用 Tool 的两分钟默认值。 */
    private static AgentTool.Invocation invocation(String command, Integer timeoutMillis) {
        JsonObjectBuilder arguments = JsonObjects.builder().putText("command", command);
        if (timeoutMillis != null) arguments.putNumber("timeout_ms", timeoutMillis);
        return new AgentTool.Invocation("call_shell", "shell", arguments.build(), 0);
    }

    /** 创建持有继承管道的长寿命子进程，并原子写入根/子 PID 供返回后的外部存活断言。 */
    private static String spawnLongLivedChildAndWritePids(Path pids) {
        return "$child=Start-Process -FilePath $env:ComSpec "
                + "-ArgumentList @('/d','/c','ping.exe','127.0.0.1','-n','120') -PassThru; "
                + "Set-Content -LiteralPath " + quote(pids)
                + " -Value @([string]$PID,[string]$child.Id); Start-Sleep -Seconds 120";
    }

    /** 等待 PID 文件可读，使用状态屏障代替任意 sleep，避免取消发生在后代尚未创建之前。 */
    private static void awaitFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path)) {
                try {
                    if (Files.readAllLines(path, StandardCharsets.UTF_8).size() >= 2) return;
                } catch (java.nio.file.FileSystemException ignored) {
                    // Windows 写入句柄尚未关闭时短暂重试，Deadline 仍约束总等待。
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for shell PID fixture");
    }

    /** 读取根/子 PID 并证明 ToolResult 返回时两者都已退出。 */
    private static void assertRecordedProcessesGone(Path pids) throws Exception {
        awaitFile(pids, Duration.ofSeconds(2));
        List<String> values = Files.readAllLines(pids, StandardCharsets.UTF_8);
        assertEquals(2, values.size());
        assertProcessGone(Long.parseLong(values.get(0).trim()), PROCESS_GONE_TIMEOUT);
        assertProcessGone(Long.parseLong(values.get(1).trim()), PROCESS_GONE_TIMEOUT);
    }

    /** 轮询精确 PID；Tool 已完成后仍给 Windows 调度器短收敛窗口，但不修改任何进程。 */
    private static void assertProcessGone(long pid, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) return;
            Thread.sleep(20);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "owned shell process remained alive");
    }

    /** 将临时路径转义为 PowerShell 单引号字面量，测试不依赖路径字符集。 */
    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
    }

    /** 复制 Rust Host env_clear 后保留的 Windows 运行变量，避免开发环境额外变量掩盖问题。 */
    private static Map<String, String> minimalWindowsEnvironment() {
        Map<String, String> values = new HashMap<>();
        for (String name : List.of("SystemRoot", "PATH", "ComSpec", "TEMP", "TMP")) {
            copyHost(values, name);
        }
        return Map.copyOf(values);
    }

    /** 测试只复制产品白名单中的非敏感宿主变量，不把完整环境注入 fixture。 */
    private static void copyHost(Map<String, String> target, String key) {
        String value = System.getenv(key);
        if (value != null) target.put(key, value);
    }

    /**
     * 独立 JVM 模拟 Rust/Tauri 托管的 Ja App Server，并把 ToolOutcome 变成进程退出事实，
     * 让父测试无需跨越私有 JA-RPC 会话也能验证 Shell 真实执行链。
     */
    public static final class HostedShellProbe {
        /** 执行固定无副作用命令；失败只回显稳定 errorCode，不输出环境、命令或路径。 */
        public static void main(String[] arguments) {
            try {
                Path workspace = Path.of(arguments[0]).toAbsolutePath().normalize();
                ShellProfile profile = ShellCapability.detectAndPreflight().profile().orElseThrow(
                        () -> new IllegalStateException("shell_profile_unavailable"));
                ShellTool tool = new ShellTool(profile);
                AgentTool.Invocation invocation = invocation("Write-Output 'hosted-shell-ok'", 2_000);
                AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(
                        "thr_hosted", "turn_hosted", workspace, AccessMode.FULL_ACCESS,
                        "cfg_hosted", Instant.now().plusSeconds(10), "ws_hosted");
                AgentTool.ToolResult result = tool.execute(invocation, context, CancellationToken.none())
                        .toCompletableFuture().join();
                if (result.outcome() != ToolOutcome.SUCCEEDED) {
                    throw new IllegalStateException(Objects.requireNonNullElse(
                            result.errorCode(), "shell_result_failed"));
                }
                System.out.print(result.content());
            } catch (Exception failure) {
                System.err.println(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                System.exit(91);
            }
        }

        /** 该类型只提供独立 JVM 入口，不允许实例化。 */
        private HostedShellProbe() {
        }
    }
}
