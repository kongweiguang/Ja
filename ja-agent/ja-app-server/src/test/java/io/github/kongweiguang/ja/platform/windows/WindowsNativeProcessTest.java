// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.platform.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** 验证原生进程 HANDLE 在并发等待与关闭时只释放一次且不会留下空转等待。 */
final class WindowsNativeProcessTest {
    @TempDir
    private Path temporary;

    /** 仅在 Windows 执行真实 Kernel32 进程句柄测试。 */
    @BeforeEach
    void requireWindows() {
        assertTrue(WindowsJobObject.isSupported(), "Windows process HANDLE tests require Windows 11");
    }

    /**
     * 同时等待同一进程时关闭必须先阻止新 HANDLE 使用、等待已开始的 downcall 归还，再释放句柄；
     * 关闭后未知退出状态不能继续被无限 waitFor 忙循环伪装成等待。
     * 测试故意先关闭适配器，清理另等宿主进程退出，避免 Job 终止尚未完成就删除被占用的 cwd。
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void closeSettlesConcurrentWaitersAndRejectsUnknownInfiniteWait() throws Exception {
        String systemRoot = System.getenv("SystemRoot");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        Map<String, String> environment = new HashMap<>(System.getenv());
        try (WindowsJobObject job = WindowsJobObject.create()) {
            Process process = WindowsProcessLauncher.launch(
                    List.of(powershell.toString(), "-NoLogo", "-NoProfile", "-NonInteractive",
                            "-Command", "Start-Sleep -Seconds 30"),
                    temporary, environment, job);
            java.util.concurrent.CompletableFuture<ProcessHandle> processExit =
                    ProcessHandle.of(process.pid()).orElseThrow().onExit();
            ExecutorService waiters = Executors.newFixedThreadPool(6,
                    Thread.ofVirtual().name("ja-process-close-waiter-", 0).factory());
            try {
                process.getOutputStream().close();
                CountDownLatch ready = new CountDownLatch(6);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int index = 0; index < 6; index++) {
                    results.add(waiters.submit(() -> {
                        ready.countDown();
                        start.await();
                        return process.waitFor(10, TimeUnit.SECONDS);
                    }));
                }
                assertTrue(ready.await(1, TimeUnit.SECONDS), "waiters did not reach the start barrier");
                start.countDown();
                // 为每个等待线程留出进入短时原生等待的机会，再由 owner 关闭 HANDLE。
                Thread.sleep(30);

                WindowsProcessLauncher.close(process);

                for (Future<Boolean> result : results) {
                    assertFalse(result.get(1, TimeUnit.SECONDS), "closing the process must release timed waiters");
                }
                IllegalStateException closed = assertThrows(IllegalStateException.class, process::waitFor);
                assertEquals("windows_process_closed", closed.getMessage());
            } finally {
                try {
                    waiters.shutdownNow();
                    assertTrue(waiters.awaitTermination(2, TimeUnit.SECONDS));
                } finally {
                    job.terminate();
                    processExit.get(2, TimeUnit.SECONDS);
                }
            }
        }
    }
}
