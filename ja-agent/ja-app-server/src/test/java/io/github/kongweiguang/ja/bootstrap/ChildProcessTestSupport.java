// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.bootstrap;

import java.util.concurrent.TimeUnit;

/** 测试启动的子 JVM 由启动测试独占，等待失败和中断都不能把进程留给后续测试。 */
final class ChildProcessTestSupport {
    /** 仅承载测试进程生命周期，避免增加生产进程 API。 */
    private ChildProcessTestSupport() {
    }

    /** 返回真实退出码；所有失败都先清理进程，清理诊断不能替换原始中断或断言。 */
    static int awaitExit(Process child, long timeoutSeconds) throws InterruptedException {
        Throwable primaryFailure = null;
        try {
            if (!child.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new AssertionError("isolated test process timed out: " + child.pid());
            }
            return child.exitValue();
        } catch (InterruptedException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                terminate(child);
            } catch (RuntimeException | Error cleanupFailure) {
                if (primaryFailure == null) {
                    throw cleanupFailure;
                }
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    /** 清理最多等待五秒；期间中断被暂存并恢复，保证首次中断不会跳过退出确认。 */
    private static void terminate(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!child.isAlive()) {
                return;
            }
            child.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (child.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError("isolated test process did not stop: " + child.pid());
                }
                try {
                    child.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
