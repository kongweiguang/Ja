// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;

import io.github.kongweiguang.ja.transport.rpc.runtime.RpcServer;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import org.noear.solon.Solon;
import org.noear.solon.SolonApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 为传输层持有的原生 Kernel 组合提供 Solon 生命周期外壳，集中控制进程退出边界。
 */
public final class StdioApplication {
    /**
     * 以无 HTTP 模式启动 Solon，并让 Stdio 运行时持有阻塞式 sidecar 生命周期。
     * 只有在 Rust 持有的数据目录发布后才初始化日志，否则 Logback 会固化相对于安装目录
     * 或启动工作目录的错误路径。
     */
    @SuppressWarnings("PMD.CloseResource")
    public int run(String[] args) {
        SolonApp app = null;
        RpcServer runtime = null;
        Logger logger = null;
        int exitCode = 0;
        boolean aotProcessing = AotSideEffectGuard.processing();
        String previousHomeDirectory = System.getProperty("ja.home-dir");
        String previousDataDirectory = System.getProperty("ja.data-dir");
        String previousRunDirectory = System.getProperty("ja.run-dir");
        String previousLogDirectory = System.getProperty("ja.log-dir");
        try {
            SidecarConfiguration configuration = null;
            if (!aotProcessing) {
                configuration = SidecarConfiguration.fromArgs(args);
                publishRuntimeLocations(configuration);
            }
            logger = LoggerFactory.getLogger(StdioApplication.class);
            app = Solon.start(App.class, args, started -> started.enableHttp(false));
            if (!aotProcessing) {
                RuntimeServicesFactory factory = Solon.context() == null
                        ? null : Solon.context().getBean(RuntimeServicesFactory.class);
                if (factory == null) {
                    throw new IllegalStateException("Ja Solon runtime factory is unavailable");
                }
                ConfigurationUseCase configurationUseCase =
                        Solon.context().getBean(ConfigurationUseCase.class);
                if (configurationUseCase == null) {
                    throw new IllegalStateException("Ja configuration owner is unavailable");
                }
                runtime = new RpcServer(System.in, System.out, configuration,
                        adaptRuntimeFactory(factory), configurationUseCase);
                exitCode = runtime.run();
            }
        } catch (RuntimeException failure) {
            Logger activeLogger = logger == null ? LoggerFactory.getLogger(StdioApplication.class) : logger;
            activeLogger.error("Ja Kernel sidecar failed ({})", failure.getClass().getSimpleName());
            exitCode = 1;
        } finally {
            ShutdownDeadline shutdownDeadline = ShutdownDeadline.start();
            RuntimeException closeFailure = closeRuntime(runtime, shutdownDeadline);
            closeFailure = closeLifecycle(app, aotProcessing, shutdownDeadline, closeFailure);
            closeFailure = stopSolon(app, aotProcessing, shutdownDeadline, closeFailure);
            if (closeFailure != null) {
                Logger activeLogger = logger == null ? LoggerFactory.getLogger(StdioApplication.class) : logger;
                activeLogger.error("Ja Kernel sidecar cleanup failed ({})", closeFailure.getClass().getSimpleName());
                exitCode = 1;
            }
            restoreProperty("ja.data-dir", previousDataDirectory);
            restoreProperty("ja.home-dir", previousHomeDirectory);
            restoreProperty("ja.run-dir", previousRunDirectory);
            restoreProperty("ja.log-dir", previousLogDirectory);
        }
        return exitCode;
    }

    /**
     * 在唯一组合根把 Solon 工厂适配为 transport 所有的端口，确保协议实现不认识 bootstrap 类型。
     * 生产适配必须显式转发 Host Tool 通道，不能退化为测试用的无宿主工具默认实现。
     */
    private static RpcServicesFactory adaptRuntimeFactory(RuntimeServicesFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory::open;
    }

    /**
     * 在 Solon 选择任何资源 Bean 前发布 Rust 持有的路径，避免组合结果依赖 cwd。
     */
    private static void publishRuntimeLocations(SidecarConfiguration configuration) {
        Path homeDirectory = configuration.homeDirectory();
        if (homeDirectory != null) {
            System.setProperty("ja.home-dir", homeDirectory.toAbsolutePath().normalize().toString());
        }
        Path dataDirectory = configuration.dataDirectory();
        if (dataDirectory != null) {
            System.setProperty("ja.data-dir", dataDirectory.toAbsolutePath().normalize().toString());
        }
        Path runDirectory = configuration.runDirectory();
        if (runDirectory != null) {
            System.setProperty("ja.run-dir", runDirectory.toAbsolutePath().normalize().toString());
        }
        Path logDirectory = configuration.logDirectory();
        if (logDirectory != null) {
            System.setProperty("ja.log-dir", logDirectory.toAbsolutePath().normalize().toString());
        }
    }

    /**
     * 恢复嵌入式启动器的进程级属性，避免当前位置泄漏到后续运行代际。
     */
    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }

    /**
     * 在调用方持有的预算内关闭传输准入，避免外层生命周期已耗尽额度后，重复关闭又静默
     * 开启一个新的十秒窗口。
     */
    private static RuntimeException closeRuntime(RpcServer runtime, ShutdownDeadline deadline) {
        if (runtime == null) return null;
        Objects.requireNonNull(deadline, "deadline");
        try {
            runtime.close(deadline);
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    /**
     * 为 Solon 对象图复用外层 finally 的 Deadline。除非 RpcServer 已完成生命周期，否则传输失败
     * 保守视为尚未证明入口静默，防止后续 Solon 销毁回调重新获得一份依赖关闭预算。
     */
    @SuppressWarnings("PMD.CloseResource")
    private static RuntimeException closeLifecycle(
            SolonApp app, boolean aotProcessing, ShutdownDeadline deadline, RuntimeException prior) {
        if (app == null || aotProcessing || Solon.context() == null) return prior;
        RuntimeResourceLifecycle lifecycle = Solon.context().getBean(RuntimeResourceLifecycle.class);
        if (lifecycle == null) return prior;
        try {
            if (prior == null) lifecycle.close(deadline);
            else lifecycle.requireForcedTermination(prior);
            return prior;
        } catch (RuntimeException failure) {
            if (prior == null) return failure;
            if (failure != prior) prior.addSuppressed(failure);
            return prior;
        }
    }

    /**
     * 将生产 Solon 停止动作绑定到已经开始计时的外层关闭 Deadline，维持单一预算。
     */
    private static RuntimeException stopSolon(
            SolonApp app, boolean aotProcessing, ShutdownDeadline deadline, RuntimeException prior) {
        return stopSolon(app != null, aotProcessing, deadline, prior,
                () -> Solon.stopBlock(false, 0));
    }

    /**
     * 在守护虚拟线程中只停止一次 Solon，并且只等待调用方预算的剩余时间。Solon 或插件关闭
     * 可能忽略中断，因此超时后刻意不再 join 工作线程：失败的进程代际仍保持可观测，
     * Rust Job Object 继续作为终止所有残留 Java 线程的最终权威。
     */
    static RuntimeException stopSolon(
            boolean applicationStarted, boolean aotProcessing, ShutdownDeadline deadline,
            RuntimeException prior, Runnable stopAction) {
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(stopAction, "stopAction");
        if (!applicationStarted || aotProcessing) return prior;
        if (deadline.expired()) {
            return mergeStopFailure(prior,
                    ShutdownDeadline.forced("Solon stop deadline expired", null));
        }
        CompletableFuture<Void> stopped = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("ja-solon-stop").start(() -> {
            try {
                stopAction.run();
                stopped.complete(null);
            } catch (Throwable failure) {
                stopped.completeExceptionally(failure);
            }
        });
        try {
            deadline.await(stopped, "Solon stop");
            return prior;
        } catch (RuntimeException failure) {
            worker.interrupt();
            return mergeStopFailure(prior, failure);
        }
    }

    /**
     * 保持强制终止异常为主异常，同时将更早的清理失败作为 suppressed 证据保留。
     */
    private static RuntimeException mergeStopFailure(RuntimeException prior, RuntimeException stopFailure) {
        if (prior == null || prior == stopFailure) return stopFailure;
        if (stopFailure instanceof ShutdownDeadline.ForcedTerminationException) {
            stopFailure.addSuppressed(prior);
            return stopFailure;
        }
        prior.addSuppressed(stopFailure);
        return prior;
    }
}
