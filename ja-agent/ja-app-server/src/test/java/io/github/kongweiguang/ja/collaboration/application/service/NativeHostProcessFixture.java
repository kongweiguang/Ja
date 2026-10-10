// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.application.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 供传输生命周期测试登记稳定 PID/startInstant，直到测试显式终止本进程。 */
public final class NativeHostProcessFixture {
    /**
     * 测试辅助进程只能通过其 main 方法启动，避免误用为库对象。
     * @author kongweiguang
     */
    private NativeHostProcessFixture() {
    }

    /**
     * 通过与 App Server 不同的真实 JVM 提供可观察的宿主 PID，避免当前测试进程伪造可清理 owner。
     * @author kongweiguang
     */
    public static Host start() throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"),
                NativeHostProcessFixture.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.isAlive()) {
            throw new IOException("Native host fixture exited during startup");
        }
        return new Host(process);
    }

    /**
     * 等待宿主进程退出事件，不访问 Ja 服务、用户数据或网络资源。
     * @author kongweiguang
     */
    public static void main(String[] arguments) throws InterruptedException {
        Thread.sleep(120_000L);
    }

    /** 持有真实测试宿主进程与仅在本生命周期内稳定的 Native owner 身份。 */
    public static final class Host implements AutoCloseable {
        private final Process process;
        private final String lifetimeId = "native_" + UUID.randomUUID().toString().replace("-", "");

        /**
         * Host 只能由 {@link #start()} 创建，确保 PID 与 owner token 同属一个独立进程 fixture。
         * @author kongweiguang
         */
        private Host(Process process) {
            this.process = process;
        }

        /** 返回服务端可解析且不是 App Server 自身的实际宿主 PID。 */
        public long processId() {
            return process.pid();
        }

        /** 返回当前测试 Host 的规范 opaque identity，供断线重试时沿用。 */
        public String lifetimeId() {
            return lifetimeId;
        }

        /**
         * 正常结束 fixture，等待真实退出观察完成；超时才强制回收，避免测试 JVM 留下宿主子进程。
         * @author kongweiguang
         */
        @Override
        public void close() throws InterruptedException {
            if (!process.isAlive()) {
                return;
            }
            process.destroy();
            try {
                if (process.waitFor(2, TimeUnit.SECONDS)) {
                    return;
                }
                process.destroyForcibly();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Native host fixture did not terminate");
                }
            } catch (InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw interrupted;
            }
        }
    }
}
