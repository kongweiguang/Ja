// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleService;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.configuration.adapter.out.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.port.out.ConfigurationRuntimePort;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapterFactory;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.workspace.application.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Bean 实例身份、AOT 隔离、部分启动清理和幂等逆序关闭。 */
final class SolonRuntimeCompositionTest {
    /** 启动原生入口组合，并证明每个核心端口都解析为唯一 Bean 实例。 */
    @Test
    void solonPublishesOneCoreRuntimeGeneration() throws Exception {
        Path data = Files.createTempDirectory("ja-solon-");
        String previousData = System.getProperty("ja.data-dir");
        String previousRun = System.getProperty("ja.run-dir");
        try {
            System.setProperty("ja.data-dir", data.toString());
            System.setProperty("ja.run-dir", Files.createTempDirectory("ja-solon-run-").toString());
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            assertSame(Solon.context().getBean(ConversationRepository.class), Solon.context().getBean(ConversationRepository.class));
            assertSame(Solon.context().getBean(CheckpointStore.class),
                    Solon.context().getBean(CheckpointStore.class));
            assertSame(Solon.context().getBean(AgentLoop.class), Solon.context().getBean(AgentLoop.class));
            assertSame(Solon.context().getBean(TurnService.class), Solon.context().getBean(TurnService.class));
            assertTrue(Solon.context().getBean(AutomaticThreadTitleScheduler.class)
                    instanceof AutomaticThreadTitleService);
            ModelPort model = Solon.context().getBean(ModelPort.class);
            SummaryModel.Factory summaryFactory = Solon.context().getBean("jaSummaryModelFactory");
            assertSame(model, summaryFactory);
            assertNotNull(Solon.context().getBean(ContextOrchestratorFactory.class));
            assertNotNull(Solon.context().getBean(TurnMcpSessionFactory.class));
            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            ConfigurationGenerationUseCase generations =
                    Solon.context().getBean(ConfigurationGenerationUseCase.class);
            ConfigurationRuntimePort configurationRuntime =
                    Solon.context().getBean(ConfigurationRuntimePort.class);
            assertTrue(configuration instanceof ConfigurationApplicationService);
            assertSame(configuration, generations);
            assertTrue(configurationRuntime instanceof ConfigurationRuntimeAdapter);
            assertNotSame(configuration, configurationRuntime);
            assertTrue(Files.exists(data.resolve("ja.db")));

            ObjectMapper mapper = Solon.context().getBean(ObjectMapper.class);
            RuntimeServicesFactory factory = Solon.context().getBean(RuntimeServicesFactory.class);
            RpcServiceBindings services = factory.open(mapper);
            assertSame(Solon.context().getBean(TurnService.class), services.turns());
            assertSame(Solon.context().getBean(WorkspaceService.class), services.workspaces());
            assertSame(Solon.context().getBean(MybatisHistoryService.class), services.threads());
            assertSame(Solon.context().getBean(CatalogUseCase.class), services.catalog());
            services.lifecycle().close();
            services.lifecycle().close();
            assertThrows(IllegalStateException.class,
                    () -> factory.open(mapper));
        } finally {
            if (Solon.context() != null) Solon.stopBlock(false, 0);
            restoreProperty("ja.data-dir", previousData);
            restoreProperty("ja.run-dir", previousRun);
        }
    }

    /**
     * 验证生产组合将同一个 Provider 工厂同时暴露为模型和摘要端口，
     * 且无需启动 Solon 或打开数据库、网络会话。
     */
    @Test
    void productionCompositionSharesProviderFactoryAcrossPorts() {
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            ModelAdapterFactory factory = composition.modelAdapterFactory(Clock.systemUTC());
            assertSame(factory, composition.modelPort(factory));
            assertSame(factory, composition.summaryModelFactory(factory));
        } finally {
            composition.close();
        }
    }

    /** 在 AOT 期间调用真实资源 Bean 工厂和 Home 解析，证明装配不要求 Host 参数且不访问磁盘。 */
    @Test
    void aotGuardLeavesResourceFactoriesSideEffectFree() throws Exception {
        Path data = Files.createTempDirectory("ja-aot-");
        String previousAot = System.getProperty("solon.aot.processing");
        String previousData = System.getProperty("ja.data-dir");
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            System.setProperty("solon.aot.processing", "");
            System.setProperty("ja.data-dir", data.toString());
            assertNull(composition.database());
            assertNull(composition.modelAdapterFactory(java.time.Clock.systemUTC()));
            assertTrue(SolonRuntimeComposition.resolveHomePath().endsWith("ja-solon-aot"));
            assertThrows(io.github.kongweiguang.ja.foundation.error.StorageException.class,
                    AotSideEffectGuard::requireRuntimeIo);
            ThreadUseCase threads = proxy(ThreadUseCase.class);
            AutomaticTitleUsagePort usage = proxy(AutomaticTitleUsagePort.class);
            ModelPort models = (request, sink, cancellation) ->
                    java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("AOT Provider must not run"));
            try (AutomaticThreadTitleScheduler titles = composition.automaticThreadTitles(
                    models, threads, usage, Clock.systemUTC())) {
                assertTrue(titles instanceof AutomaticThreadTitleService);
            }
            try (java.util.stream.Stream<Path> files = Files.list(data)) {
                assertTrue(files.findAny().isEmpty());
            }
        } finally {
            composition.close();
            restoreProperty("solon.aot.processing", previousAot);
            restoreProperty("ja.data-dir", previousData);
        }
    }

    /** 模拟后续 Bean 工厂失败，证明此前成功创建的所有者仍会按逆序回收。 */
    @Test
    void partialInitializationFailureClosesEarlierOwnersInReverseOrder() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.own(() -> closed.add("database"));
        lifecycle.own(() -> closed.add("provider"));
        assertThrows(IllegalStateException.class, () -> {
            throw new IllegalStateException("later bean failed");
        });

        lifecycle.close();
        assertEquals(List.of("provider", "database"), closed);
    }

    /** 确保重复的 Solon 或 RPC 关闭调用不会重复释放同一所有者。 */
    @Test
    void lifecycleCloseIsIdempotentAndAggregatesFailures() {
        RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();
        List<String> closed = new ArrayList<>();
        lifecycle.own(() -> closed.add("first"));
        lifecycle.own(() -> {
            closed.add("second");
            throw new IllegalStateException("second failed");
        });
        lifecycle.own(() -> {
            closed.add("third");
            throw new IllegalArgumentException("third failed");
        });

        RuntimeException failure = assertThrows(RuntimeException.class, lifecycle::close);
        assertEquals("third failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of("third", "second", "first"), closed);
        RuntimeException repeated = assertThrows(RuntimeException.class, lifecycle::close);
        assertEquals("third failed", repeated.getMessage());
        assertEquals(1, repeated.getSuppressed().length);
        assertEquals(3, closed.size());
    }

    /** 恢复进程级测试状态，使后续嵌入式 Solon 代际保留各自独立路径。 */
    private static void restoreProperty(String name, String previousValue) {
        if (previousValue == null) System.clearProperty(name);
        else System.setProperty(name, previousValue);
    }

    /**
     * 创建只用于组合身份验证的拒绝型端口代理；AOT 测试若意外调用业务方法会立即失败。
     */
    private static <T> T proxy(Class<T> type) {
        Object value = java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (instance, method, arguments) -> {
                    throw new AssertionError("AOT composition invoked " + method.getName());
                });
        return type.cast(value);
    }

}
