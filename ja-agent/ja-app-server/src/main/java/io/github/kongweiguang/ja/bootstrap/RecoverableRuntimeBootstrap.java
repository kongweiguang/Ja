// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.foundation.error.FailureDiagnostics;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.database.StorageRecovery;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory;
import io.github.kongweiguang.ja.transport.rpc.RpcServicesFactory.RecoveryStatus;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.apache.ibatis.solon.integration.XPluginImpl;
import org.noear.solon.SimpleSolonApp;
import org.noear.solon.core.util.RunUtil;
import org.noear.solon.net.websocket.WebSocketRouter;
import org.noear.solon.net.websocket.listener.SimpleWebSocketListener;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 初始化失败可恢复时继续提供 RPC；只有完整服务图构建成功后才发布业务能力。
 */
final class RecoverableRuntimeBootstrap {
    private final String[] args;
    private final DatabaseConfig databaseConfig;
    private final ConfigurationRuntimeAdapter configurationRuntime;
    private final ConfigurationUseCase configurationUseCase;
    private final RpcServicesFactory factory;
    private final Set<ScheduledExecutorService> solonSchedulers = ConcurrentHashMap.newKeySet();
    private SimpleWebSocketListener transportListener;
    // 装配仍由 openServices 串行负责；监听器和关闭路径只读取已发布的本代资源。
    private volatile RuntimeResourceLifecycle lifecycle;
    private volatile SimpleSolonApp app;
    private RuntimeServicesFactory services;
    private volatile RecoveryStatus recovery;
    private volatile boolean cleanupFailed;

    /**
     * 配置所有权属于进程，即使存储初始化失败也要保留，以便认证连接仍可修复存储。
     */
    RecoverableRuntimeBootstrap(String[] args, SidecarConfiguration configuration) {
        this.args = args.clone();
        databaseConfig = DatabaseConfig.of(configuration.dataDirectory().resolve("ja.db"), configuration.homeDirectory());
        configurationRuntime = new ConfigurationRuntimeAdapter(configuration, new ObjectMapper());
        configurationUseCase = new ConfigurationApplicationService(configurationRuntime);
        factory = new RpcServicesFactory() {
            /**
             * 延迟装配服务图，确保初始化失败仍能通过认证连接返回可操作状态。
             */
            @Override public RpcServiceBindings open(ObjectMapper mapper) { return openServices(mapper); }
            /**
             * 暴露 Runtime 已注入的 codec，避免 RPC 适配层自行构造 Core 基础设施实现。
             */
            @Override public JsonValueCodec jsonValueCodec() {
                RuntimeServicesFactory current = services;
                if (current == null) {
                    throw new IllegalStateException("runtime services are unavailable");
                }
                return current.jsonValueCodec();
            }
            /**
             * 只发布当前进程持有的最新恢复决定，避免客户端推导持久状态。
             */
            @Override public RecoveryStatus storageRecovery() { return recovery; }
            /**
             * 重试沿用持久候选身份，普通 I/O 失败不会清理或重建用户数据。
             */
            @Override public void retryStorage() {
                if (cleanupFailed) {
                    throw new IllegalStateException("runtime cleanup did not settle");
                }
                StorageRecovery.Result current = StorageRecovery.latest(databaseConfig);
                if (current != null && current.phase().equals("blocked")) {
                    try { StorageRecovery.retry(databaseConfig, current.id()); }
                    catch (io.github.kongweiguang.ja.agent.domain.error.StorageException failed) {
                        if (failed.code() != io.github.kongweiguang.ja.agent.domain.error.StorageException.Code.INVALID_STATE) {
                            throw failed;
                        }
                    }
                }
            }
        };
    }

    /**
     * 装配异常由 Ja 接管；数据库由容器的 lifecycle 持有，失败先按同一期限关闭本次资源，
     * 保留认证 RPC 供显式重试，不在借用 Bean 的位置重复关闭数据库。
     */
    @SuppressWarnings("PMD.CloseResource")
    private synchronized RpcServiceBindings openServices(ObjectMapper mapper) {
        if (cleanupFailed) {
            throw new IllegalStateException("runtime cleanup did not settle");
        }
        try {
            lifecycle = new RuntimeResourceLifecycle();
            startApplication();
            services = app.context().getBean(RuntimeServicesFactory.class);
            if (services == null) {
                throw new IllegalStateException("runtime services are unavailable");
            }
            JaDatabase database = app.context().getBean(JaDatabase.class);
            StorageRecovery.Result result = database.recovery();
            recovery = result == null ? null : new RecoveryStatus(result.id(), result.phase(), result.reason(),
                    result.historyAvailable(), result.backupAvailable(), result.rebuilt());
            return services.open(mapper);
        } catch (RuntimeException | Error failure) {
            StorageRecovery.Result persisted = null;
            try {
                persisted = StorageRecovery.latest(databaseConfig);
            } catch (RuntimeException readFailure) {
                if (failure != readFailure) {
                    failure.addSuppressed(readFailure);
                }
            }
            String recoveryId = persisted != null ? persisted.id() : recovery != null ? recovery.id() : UUID.randomUUID().toString();
            recovery = new RecoveryStatus(recoveryId,
                    "blocked", StorageRecovery.reason(failure), persisted == null || persisted.historyAvailable(),
                    persisted != null && persisted.backupAvailable(), persisted != null && persisted.rebuilt());
            FailureDiagnostics.Summary diagnostics = FailureDiagnostics.summarize(failure);
            org.slf4j.LoggerFactory.getLogger(RecoverableRuntimeBootstrap.class)
                    .warn("Ja initialization needs recovery reason={} type={} origin={}",
                            recovery.reason(), diagnostics.type(), diagnostics.origin());
            ShutdownDeadline deadline = ShutdownDeadline.start();
            RuntimeException cleanup = null;
            try {
                lifecycle.close(deadline);
            } catch (RuntimeException closeFailure) {
                cleanup = closeFailure;
            }
            cleanup = stopApplication(deadline, cleanup);
            if (cleanup != null) {
                cleanupFailed = true;
                if (cleanup != failure) {
                    failure.addSuppressed(cleanup);
                }
            }
            app = null;
            services = null;
            throw failure;
        }
    }

    /**
     * SimpleSolonApp 使用同一容器并把启动失败交回调用者；Solon.start 的 JVM 分支会 System.exit，
     * 无法进入恢复逻辑。业务容器显式唯一注册 MyBatis 插件，避免依赖启动器的 classpath 插件发现；
     * 传输监听由独立 owner 持有，先登记对象以保证部分装配失败也能由唯一 owner 回收。
     */
    private void startApplication() {
        try {
            SimpleSolonApp opened = new SimpleSolonApp(App.class, args);
            app = opened;
            opened.start(started -> {
                started.enableHttp(false);
                started.enableWebSocket(false);
                started.pluginExclude(XPluginImpl.class);
                started.pluginAdd(10, new XPluginImpl());
                if (transportListener != null) {
                    WebSocketRouter.getInstance().of("/ja-rpc", transportListener);
                }
                started.context().wrapAndPut("jaBootstrapLifecycle", lifecycle);
                started.context().wrapAndPut("jaBootstrapConfiguration", configurationRuntime);
            });
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("runtime assembly failed", failure);
        }
    }

    /**
     * 实例级停止只释放本次业务容器；Solon.stopBlock 会清掉全局 app 并切断传输容器的归属。
     */
    private RuntimeException stopApplication(ShutdownDeadline deadline, RuntimeException prior) {
        SimpleSolonApp current = app;
        if (current != null) {
            rememberSolonScheduler();
        }
        return StdioApplication.stopSolon(current != null, false, deadline, prior,
                () -> { if (current != null) { current.stop(); } });
    }

    /** Solon 的每代 app.stop 会丢弃调度池引用；保留旧池至进程终止才可取消长空闲任务。 */
    void rememberSolonScheduler() {
        solonSchedulers.add(RunUtil.timer());
    }

    /** 传输也已停止后取消所有遗留延时任务，避免非守护调度线程阻止 JVM 退出。 */
    void shutdownSolonSchedulers() {
        rememberSolonScheduler();
        solonSchedulers.forEach(ScheduledExecutorService::shutdownNow);
        RunUtil.shutdown();
    }

    /**
     * Transport 只依赖稳定工厂端口，不直接引用 Solon 容器。
     */
    RpcServicesFactory factory() { return factory; }
    /** Solon 的 WebSocketRouter 随当前 AppContext 切换；两个上下文登记同一进程级回调。 */
    void bindTransport(SimpleWebSocketListener listener) { transportListener = listener; }
    /**
     * 完整服务图继续复用启动阶段的唯一配置所有者。
     */
    ConfigurationUseCase configurationUseCase() { return configurationUseCase; }
    /**
     * Runtime 完成前允许先挂载配置变化订阅。
     */
    ConfigurationRuntimeAdapter configurationRuntime() { return configurationRuntime; }
    /**
     * 监听器只读取一次已发布容器，避免装配失败清理时两次读取跨越不同代际。
     */
    WorkspaceUseCase workspaces() {
        SimpleSolonApp current = app;
        return current == null ? null : current.context().getBean(WorkspaceUseCase.class);
    }

    /**
     * 关闭沿用调用方 deadline；各 owner 均得到释放机会，后续失败不能覆盖首要诊断。
     */
    void close(ShutdownDeadline deadline) {
        RuntimeException failure = null;
        try { if (lifecycle != null) {
            lifecycle.close(deadline);
        } }
        catch (RuntimeException close) { failure = close; }
        try { configurationRuntime.close(); }
        catch (RuntimeException close) { if (failure == null) {
            failure = close;
        } else if (failure != close) {
            failure.addSuppressed(close);
        } }
        failure = stopApplication(deadline, failure);
        if (failure != null) {
            throw failure;
        }
    }
}
