// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实阻塞 JVM 检查退出事实，而不以模拟 Process 或源码断言代替资源验收。 */
final class ChildProcessTestSupportTest {
    @TempDir
    Path root;

    /** 中断等待者必须传播中断，并在调用返回前确认其独占子进程已退出。 */
    @Test
    void interruptionTerminatesOwnedProcessBeforeReturning() throws Exception {
        Process child = startChild();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch waiting = new CountDownLatch(1);
        Thread waiter = Thread.ofPlatform().unstarted(() -> {
            waiting.countDown();
            try {
                ChildProcessTestSupport.awaitExit(child, 90);
                failure.set(new AssertionError("blocking child unexpectedly exited"));
            } catch (InterruptedException | RuntimeException | Error interrupted) {
                failure.set(interrupted);
            }
        });
        try {
            awaitReady(child);
            waiter.start();
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            waiter.interrupt();
            assertTrue(waiter.join(java.time.Duration.ofSeconds(10)));
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(child.isAlive());
        } finally {
            waiter.interrupt();
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            if (waiter.isAlive()) {
                assertTrue(waiter.join(java.time.Duration.ofSeconds(5)));
            }
        }
    }

    /** 超时只结束本测试进程，不能留下持有隔离数据目录的 JVM。 */
    @Test
    void timeoutTerminatesOwnedProcessBeforeFailing() throws Exception {
        Process child = startChild();
        try {
            awaitReady(child);
            AssertionError failure = assertThrows(AssertionError.class,
                    () -> ChildProcessTestSupport.awaitExit(child, 0));
            assertTrue(failure.getMessage().startsWith("isolated test process timed out:"));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
    }

    /** 正常退出保留子进程真实退出码，不以资源清理结果覆盖业务结果。 */
    @Test
    void completedProcessRetainsExitCode() throws Exception {
        Process child = startChild();
        try {
            awaitReady(child);
            child.getOutputStream().close();
            assertEquals(0, ChildProcessTestSupport.awaitExit(child, 10));
            assertFalse(child.isAlive());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        }
    }

    /** 子 JVM 等 stdin EOF，使中断和超时用例的退出由测试 owner 决定。 */
    public static void main(String[] arguments) throws Exception {
        Files.writeString(Path.of(arguments[0]), "ready");
        System.in.read();
    }

    /** 直接使用当前 JDK 启动，日志和 readiness 文件只落在隔离测试目录。 */
    private Process startChild() throws Exception {
        Files.deleteIfExists(root.resolve("ready"));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                ChildProcessTestSupportTest.class.getName(), root.resolve("ready").toString())
                .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
    }

    /** 等待进程真正进入 fixture，避免把启动失败误判成清理通过。 */
    private void awaitReady(Process child) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (child.isAlive() && !Files.exists(root.resolve("ready")) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(child.isAlive(), () -> "fixture exited: " + child.exitValue());
        assertTrue(Files.exists(root.resolve("ready")));
    }
}
