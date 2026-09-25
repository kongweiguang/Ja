// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.foundation.concurrent.CancellationSource;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 native 搜索进程在输出消费者较慢时仍能及时响应取消和 Turn Deadline。 */
class NativeSearchProcessTest {
    @TempDir Path temporary;

    /** 子进程虽已写完并退出，慢消费者仍不能阻止 owner 观察 Deadline 并回收进程。 */
    @Test
    void observesDeadlineWhileExitedChildHasSlowConsumer() throws Exception {
        Instant deadline = Instant.now().plusMillis(150);
        NativeSearchProcess.Result result = NativeSearchProcess.run(shell(), shellArguments("echo slow-line"),
                temporary, CancellationToken.none(), deadline, 4_096, 4_096, line -> {
                    sleepAndRestoreInterrupt(400);
                    return true;
                });

        assertTrue(result.deadlineExceeded(), "slow output consumer must remain deadline-observable");
    }

    /** 消费者发布取消后，即使根进程已经退出，owner 仍返回取消事实而不是等待自然完成。 */
    @Test
    void observesCancellationWhileExitedChildHasSlowConsumer() throws Exception {
        CancellationSource source = new CancellationSource();
        AtomicBoolean consumed = new AtomicBoolean();
        NativeSearchProcess.Result result = NativeSearchProcess.run(shell(), shellArguments("echo cancel-line"),
                temporary, source, Instant.now().plusSeconds(5), 4_096, 4_096, line -> {
                    consumed.set(true);
                    source.cancel("test_cancel");
                    sleepAndRestoreInterrupt(250);
                    return true;
                });

        assertTrue(consumed.get(), "the child must have delivered a line before cancellation");
        assertTrue(result.cancelled(), "consumer cancellation must be preserved in the result");
    }

    /** 原生进程收到指定 Turn 的私有环境，证明后台启动环境不会替换客户端变量。 */
    @Test
    void nativeProcessUsesFrozenClientEnvironment() throws Exception {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("JA_NATIVE_CLIENT_MARKER", "isolated-client-value");
        String command = isWindows() ? "echo %JA_NATIVE_CLIENT_MARKER%"
                : "printf '%s\\n' \"$JA_NATIVE_CLIENT_MARKER\"";
        AtomicReference<String> output = new AtomicReference<>();
        NativeSearchProcess.Result result = NativeSearchProcess.run(shell(), shellArguments(command),
                temporary, CancellationToken.none(), Instant.now().plusSeconds(5), 4_096, 4_096,
                environment, line -> {
                    output.set(line.trim());
                    return true;
                });
        assertEquals(0, result.exitCode());
        assertEquals("isolated-client-value", output.get());
    }

    /** 根据当前宿主选择命令解释器，测试进程边界本身而不把 fd/rg 安装位置写死。 */
    private static Path shell() {
        if (isWindows()) {
            String commandShell = System.getenv("ComSpec");
            return Path.of(commandShell == null || commandShell.isBlank() ? "cmd.exe" : commandShell);
        }
        return Path.of("/bin/sh");
    }

    /** 为 Windows cmd 和 Unix sh 生成同一条最小输出命令，避免测试引入额外脚本文件。 */
    private static List<String> shellArguments(String command) {
        return isWindows() ? List.of("/d", "/c", command) : List.of("-c", command);
    }

    /** 测试消费者只允许向进程边界报告 IOException，并保留线程中断语义。 */
    private static void sleepAndRestoreInterrupt(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("test_consumer_interrupted", interrupted);
        }
    }

    /** 只按宿主系统属性切换命令参数，生产搜索解析仍由 NativeSearchToolResolver 负责。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
