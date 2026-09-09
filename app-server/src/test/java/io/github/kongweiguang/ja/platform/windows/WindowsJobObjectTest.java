// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 仅在 Windows 执行的 Job Object 所有权与单调清理契约测试。 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
final class WindowsJobObjectTest {
    /** 文件和进程状态轮询间隔，在测试速度与系统调用压力之间取平衡。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    /** 显式限定本地测试平台，避免其它平台静默跳过后被误报为通过。 */
    @BeforeEach
    void requireWindows() {
        assertTrue(WindowsJobObject.isSupported(), "Windows Job Object tests require Windows 11");
    }

    /** 关闭已配置 Job 必须终止只在分配后才创建的延迟子进程。 */
    @Test
    void closeKillsRootAndDescendantAndReleasesHandle(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            long handlesBefore = HandleCounter.current();
            WindowsJobObject job = WindowsJobObject.create();
            try {
                job.assign(fixture.root());
                Files.writeString(fixture.release(), "go", StandardCharsets.UTF_8);
                long childPid = awaitPid(fixture.childPid(), Duration.ofSeconds(5));
                job.close();
                assertTrue(job.isClosed());
                assertTrue(fixture.root().waitFor(5, TimeUnit.SECONDS), "root process must exit");
                assertProcessGone(childPid, Duration.ofSeconds(5));
                awaitHandleCountAtMost(handlesBefore + 1, Duration.ofSeconds(3));
            } finally {
                assertDoesNotThrow(job::close);
            }
        }
    }

    /** 显式终止必须到达同一关闭边界，且不得留下存活后代。 */
    @Test
    void explicitTerminateKillsRootAndDescendant(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            try (WindowsJobObject job = WindowsJobObject.create()) {
                job.assign(fixture.root());
                Files.writeString(fixture.release(), "go", StandardCharsets.UTF_8);
                long childPid = awaitPid(fixture.childPid(), Duration.ofSeconds(5));
                job.terminate();
                assertTrue(job.isClosed());
                assertTrue(fixture.root().waitFor(5, TimeUnit.SECONDS), "root process must exit");
                assertProcessGone(childPid, Duration.ofSeconds(5));
            }
        }
    }

    /** 重复关闭必须无害，关闭后再分配则返回稳定脱敏错误码。 */
    @Test
    void closeIsIdempotentAndRejectsAssignAfterClosed(@TempDir Path temp) throws Exception {
        try (ProcessFixture fixture = ProcessFixture.start(temp)) {
            awaitFile(fixture.ready(), Duration.ofSeconds(5));
            WindowsJobObject job = WindowsJobObject.create();
            job.close();
            assertDoesNotThrow(job::close);
            WindowsJobObject.WindowsJobObjectException failure = assertThrows(
                    WindowsJobObject.WindowsJobObjectException.class, () -> job.assign(fixture.root()));
            assertEquals("windows_job_assign_6", failure.code());
        }
    }

    /** 死进程必须在 OpenProcess 前被拒绝，证明适配器不会保留无效句柄。 */
    @Test
    void rejectsDeadProcessWithoutLeakingJob(@TempDir Path temp) throws Exception {
        Process dead = new ProcessBuilder("cmd.exe", "/d", "/c", "exit", "0")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(dead.waitFor(5, TimeUnit.SECONDS));
        try (WindowsJobObject job = WindowsJobObject.create()) {
            WindowsJobObject.WindowsJobObjectException failure = assertThrows(
                    WindowsJobObject.WindowsJobObjectException.class, () -> job.assign(dead));
            assertEquals("windows_job_assign_process_not_alive", failure.code());
            assertTrue(job.isClosed(), "rejected admission must close the unbound Job Object");
        }
    }

    /** CreateProcess 前拒绝环境块注入，同时调用方持有的 Job 仍可正常关闭。 */
    @Test
    void suspendedLauncherRejectsInvalidEnvironmentName(@TempDir Path temp) throws Exception {
        try (WindowsJobObject job = WindowsJobObject.create()) {
            IOException failure = assertThrows(IOException.class, () -> WindowsProcessLauncher.launch(
                    List.of("pwsh.exe", "-NoProfile", "-Command", "exit 0"), temp,
                    Map.of("SAFE=INJECTED", "value"), job));

            assertEquals("windows_process_environment_invalid", failure.getMessage());
        }
    }

    /** 验证 Job 接纳后的功能退出；冷启动 runner 的系统 PowerShell 可超过 5 秒，保留 20 秒硬上限。 */
    @Test
    void suspendedLauncherRunsSystemPowerShell(@TempDir Path temp) throws Exception {
        Map<String, String> environment = Map.of(
                "SystemRoot", System.getenv("SystemRoot"),
                "PATH", System.getenv("PATH"));
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(
                    List.of("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Milliseconds 100"),
                    temp, environment, job);
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            } finally {
                WindowsProcessLauncher.close(process);
            }
        }
    }

    /**
     * 复现桌面 Host Job -> App Server JVM -> Shell Job 的两级托管链；普通单 JVM 用例只验证
     * 最内层 Job，无法发现父 Job 策略导致的 CreateProcess/AssignProcessToJobObject 差异。
     * 外层预算包含 JVM 与系统 PowerShell 两次冷启动，不把宿主负载当作 Job 语义失败。
     */
    @Test
    void nestedHostJobAllowsAppServerToLaunchOwnedPowerShell(@TempDir Path temp) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        List<String> command = List.of(
                java.toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NestedShellProbe.class.getName(),
                temp.toAbsolutePath().toString());
        Map<String, String> environment = minimalWindowsEnvironment();

        try (WindowsJobObject hostJob = WindowsJobObject.create()) {
            Process appServer = WindowsProcessLauncher.launch(command, temp, environment, hostJob);
            try {
                appServer.getOutputStream().close();
                assertTrue(appServer.waitFor(30, TimeUnit.SECONDS), "nested App Server probe must settle");
                String stdout = new String(appServer.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String stderr = new String(appServer.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

                assertEquals(0, appServer.exitValue(), "nested probe stderr=" + stderr);
                assertTrue(stdout.contains("nested-shell-ok"), "nested probe stdout=" + stdout);
                hostJob.closeAfterRootExit();
            } finally {
                if (appServer.isAlive()) {
                    hostJob.terminate();
                }
                WindowsProcessLauncher.close(appServer);
            }
        }
    }

    /**
     * 证明关闭精确进程 owner 会取消另一 platform thread 上的同步匿名管道读取；先观察有界超时，
     * 再要求 close 后得到 EOF，避免测试只覆盖尚未进入 ReadFile 的快速路径。
     */
    @Test
    @SuppressWarnings("PMD.CloseResource")
    void nativeProcessCloseCancelsPendingPipeRead(@TempDir Path temp) throws Exception {
        Map<String, String> environment = Map.of(
                "SystemRoot", System.getenv("SystemRoot"),
                "PATH", System.getenv("PATH"));
        ExecutorService reader = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("ja-pipe-cancel-test").factory());
        long processId = -1L;
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(
                    List.of("cmd.exe", "/d", "/q", "/c",
                            "ping.exe -n 31 127.0.0.1 > nul"),
                    temp, environment, job);
            processId = process.pid();
            CountDownLatch entered = new CountDownLatch(1);
            Future<Integer> pendingRead = reader.submit(() -> {
                entered.countDown();
                return process.getInputStream().read();
            });
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS), "pipe reader did not start");
                assertThrows(TimeoutException.class,
                        () -> pendingRead.get(100, TimeUnit.MILLISECONDS));

                WindowsProcessLauncher.close(process);

                assertEquals(-1, pendingRead.get(2, TimeUnit.SECONDS));
            } finally {
                WindowsProcessLauncher.close(process);
            }
        } finally {
            reader.shutdownNow();
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS),
                    "pipe reader executor did not terminate");
            if (processId > 0) {
                assertProcessGone(processId, Duration.ofSeconds(3));
            }
        }
    }

    /** PATH 中的普通数据文件不能因存在就取得执行资格，扩展名边界由策略先行收紧。 */
    @Test
    void executableResolutionRejectsNonExecutableExtension(@TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("payload.txt"), "not executable", StandardCharsets.UTF_8);
        WindowsProcessNativeApi.WindowsFailure failure = assertThrows(
                WindowsProcessNativeApi.WindowsFailure.class,
                () -> WindowsProcessLaunchPolicy.resolveExecutable(
                        "payload.txt", Map.of("PATH", temp.toString())));
        assertEquals(2, failure.error());
    }

    /** 挂起创建前拒绝 junction 工作目录，确保物理路径固定不可绕过。 */
    @Test
    void suspendedLauncherRejectsJunctionWorkingDirectory(
            @TempDir Path temp, @TempDir Path outside) throws Exception {
        Path junction = temp.resolve("cwd-junction");
        Process helper = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        helper.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertEquals(0, helper.waitFor());
        try (WindowsJobObject job = WindowsJobObject.create()) {
            IOException failure = assertThrows(IOException.class, () -> WindowsProcessLauncher.launch(
                    List.of("pwsh.exe", "-NoProfile", "-Command", "exit 0"), junction, Map.of(), job));

            assertEquals("windows_process_cwd_invalid", failure.getMessage());
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    /** 以硬截止时间等待标记，防止失败 shell 挂死测试线程。 */
    private static void awaitFile(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("timed out waiting for Windows process marker");
    }

    /**
     * 仅在 Windows 共享冲突时重试，直到写入方关闭句柄；进程与 Job 断言保持不变，其它文件系统
     * 失败仍立即抛出。
     */
    private static long awaitPid(Path path, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        java.nio.file.FileSystemException sharingFailure = null;
        while (Instant.now().isBefore(deadline)) {
            if (Files.isRegularFile(path)) {
                try {
                    return Long.parseLong(Files.readString(path, StandardCharsets.UTF_8).trim());
                } catch (java.nio.file.FileSystemException failure) {
                    sharingFailure = failure;
                }
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        AssertionError timeoutFailure = new AssertionError("timed out waiting for readable child PID");
        if (sharingFailure != null) timeoutFailure.initCause(sharingFailure);
        throw timeoutFailure;
    }

    /** 按单个 PID 轮询 tasklist，使测试无需 Java 进程枚举即可证明后代已清理。 */
    private static void assertProcessGone(long pid, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!processExists(pid)) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("descendant process remained alive: " + pid);
    }

    /** 通过 Windows 诊断命令查询精确 PID，且不修改进程状态。 */
    private static boolean processExists(long pid) throws Exception {
        Process probe = new ProcessBuilder("tasklist", "/fi", "PID eq " + pid, "/fo", "csv", "/nh")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            byte[] bytes;
            try (java.io.InputStream output = probe.getInputStream()) {
                bytes = output.readAllBytes();
            }
            if (!probe.waitFor(2, TimeUnit.SECONDS)) {
                probe.destroyForcibly();
                return true;
            }
            return probe.exitValue() == 0
                    && new String(bytes, StandardCharsets.UTF_8).contains("\"" + pid + "\"");
        } finally {
            probe.getErrorStream().close();
            probe.getOutputStream().close();
        }
    }

    /**
     * 只向嵌套 JVM 传递启动 Java/PowerShell 所需的非敏感变量，保持与 Rust Host env_clear
     * 边界一致，避免开发终端的完整环境掩盖托管差异。
     */
    private static Map<String, String> minimalWindowsEnvironment() {
        java.util.LinkedHashMap<String, String> values = new java.util.LinkedHashMap<>();
        for (String name : List.of("SystemRoot", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP")) {
            String value = System.getenv(name);
            if (value != null) {
                values.put(name, value);
            }
        }
        return Map.copyOf(values);
    }

    /** 为关闭后的句柄释放预留短暂收敛窗口。 */
    private static void awaitHandleCountAtMost(long maximum, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (HandleCounter.current() <= maximum) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        long observed = HandleCounter.current();
        assertTrue(observed <= maximum,
                "Job handle remained owned after close: observed=" + observed + ", maximum=" + maximum);
    }

    /** 使用显式 ready、release、child-PID 文件协调的有界 PowerShell 根进程夹具。 */
    private record ProcessFixture(Process root, Path ready, Path release, Path childPid) implements AutoCloseable {
        /**
         * 创建延迟后代夹具，确保 Java 侧分配根进程前子进程不会出现。测试失败时夹具只销毁根进程；
         * release 标记写入后的全部路径均由 Job 持有后代。
         */
        private static ProcessFixture start(Path temp) throws IOException {
            Path ready = temp.resolve("ready.marker");
            Path release = temp.resolve("release.marker");
            Path childPid = temp.resolve("child.pid");
            String script = "$ErrorActionPreference='Stop'; "
                    + "Set-Content -LiteralPath " + quote(ready) + " -Value ready; "
                    + "while (-not (Test-Path -LiteralPath " + quote(release)
                    + ")) { Start-Sleep -Milliseconds 20 }; "
                    + "$child=Start-Process -FilePath $env:ComSpec -ArgumentList @('/d','/c','ping.exe',"
                    + "'127.0.0.1','-n','120') -PassThru; "
                    + "Set-Content -LiteralPath " + quote(childPid) + " -Value ([string]$child.Id); "
                    + "Start-Sleep -Seconds 120";
            Process root = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new ProcessFixture(root, ready, release, childPid);
        }

        /** 将临时路径转义为 PowerShell 单引号字面量。 */
        private static String quote(Path path) {
            return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
        }

        /** 适配器关闭 Job 后，仅以根进程为边界执行夹具兜底清理。 */
        @Override
        public void close() throws Exception {
            if (root.isAlive()) {
                root.destroyForcibly();
                root.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 通过 Kernel32 读取当前进程句柄数，使测试能独立于 Java 生命周期标志发现泄漏的 Job 句柄。
     */
    private static final class HandleCounter {
        /** 使用受限 FFM Arena 查询 GetProcessHandleCount，不保留持久本地状态。 */
        private static long current() {
            try (Arena libraryArena = Arena.ofConfined(); Arena callArena = Arena.ofConfined()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", libraryArena);
                MethodHandle currentProcess = linker.downcallHandle(kernel32.findOrThrow("GetCurrentProcess"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                MethodHandle handleCount = linker.downcallHandle(kernel32.findOrThrow("GetProcessHandleCount"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
                MemorySegment process = (MemorySegment) currentProcess.invokeWithArguments();
                MemorySegment count = callArena.allocate(ValueLayout.JAVA_INT);
                int result = (int) handleCount.invokeWithArguments(process, count);
                assertEquals(1, result, "GetProcessHandleCount failed");
                return Integer.toUnsignedLong(count.get(ValueLayout.JAVA_INT, 0));
            } catch (Throwable failure) {
                throw new AssertionError("cannot query Windows handle count", failure);
            }
        }
    }

    /**
     * 独立 JVM 入口模拟由 Rust Job 托管的 App Server；它再创建 Shell Job 并执行真实
     * PowerShell，使测试包含生产中缺失于普通 ShellToolTest 的父 Job 层级。
     */
    public static final class NestedShellProbe {
        /** 工具入口由父测试以绝对 cwd 启动，任何异常都转成短脱敏 stderr 与非零退出码。 */
        public static void main(String[] arguments) {
            try {
                Path workingDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
                String systemRoot = System.getenv("SystemRoot");
                Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0",
                        "powershell.exe");
                try (WindowsJobObject shellJob = WindowsJobObject.create()) {
                    Process shell = WindowsProcessLauncher.launch(
                            List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                                    "-Command", "Write-Output 'nested-shell-ok'"),
                            workingDirectory,
                            minimalWindowsEnvironment(),
                            shellJob);
                    try {
                        shell.getOutputStream().close();
                        if (!shell.waitFor(20, TimeUnit.SECONDS)) {
                            throw new IOException("nested_shell_timeout");
                        }
                        String stdout = new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        String stderr = new String(shell.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                        if (shell.exitValue() != 0 || !stdout.contains("nested-shell-ok")) {
                            throw new IOException("nested_shell_failed exit=" + shell.exitValue()
                                    + " stderrEmpty=" + stderr.isEmpty());
                        }
                        shellJob.closeAfterRootExit();
                        System.out.print(stdout);
                    } finally {
                        if (shell.isAlive()) {
                            shellJob.terminate();
                        }
                        WindowsProcessLauncher.close(shell);
                    }
                }
            } catch (Exception failure) {
                System.err.println(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                Throwable cause = failure.getCause();
                if (cause != null) {
                    System.err.println(cause.getClass().getSimpleName() + ":" + cause.getMessage());
                }
                System.exit(90);
            }
        }

        /** 该类型只作为独立 JVM 入口，不允许实例化。 */
        private NestedShellProbe() {
        }
    }
}
