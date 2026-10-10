// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.attachment.domain.model.AttachmentFailure;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleService;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.conversation.application.gateway.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.test.ModelPortTestSupport;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.configuration.application.gateway.ConfigurationRuntimePort;
import io.github.kongweiguang.ja.agent.infrastructure.model.ModelAdapterFactory;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanExtensionFactApplier;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.workspace.application.service.WorkspaceService;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Bean 实例身份、AOT 隔离、部分启动清理和幂等逆序关闭。 */
final class SolonRuntimeCompositionTest {
    /** JSON 边界独立冻结精度和解析策略，宿主 Mapper 后续变化不能改变已发布的工具参数事实。 */
    @Test
    void jsonCodecPreservesDecimalsAndFreezesHostConfiguration() {
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonValueCodec codec = composition.jsonValueCodec(mapper);
            JsonNumber expected = new JsonNumber(new BigDecimal("1234567890.1234567890123456789000"));

            assertEquals(expected, codec.decode(codec.encode(expected)));
            mapper.enable(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_SINGLE_QUOTES);
            assertThrows(IllegalArgumentException.class, () -> codec.decode("'private value'"));
        } finally {
            composition.close();
        }
    }

    /** 目录所有权必须来自 Host；数据库、数据和 Home 的相对配置都应在 IO 之前拒绝。 */
    @Test
    void rejectsRelativeHostPublishedPaths() {
        String previousAot = System.getProperty("solon.aot.processing");
        String previousDatabase = System.getProperty("ja.database-path");
        String previousData = System.getProperty("ja.data-dir");
        String previousHome = System.getProperty("ja.home-dir");
        try {
            System.clearProperty("solon.aot.processing");
            System.clearProperty("ja.home-dir");
            System.setProperty("ja.database-path", "relative-data/ja.db");
            assertThrows(IllegalStateException.class, SolonRuntimeComposition::resolveDatabasePath);

            System.clearProperty("ja.database-path");
            System.setProperty("ja.data-dir", "relative-data");
            assertThrows(IllegalStateException.class, SolonRuntimeComposition::resolveDatabasePath);
            assertThrows(IllegalStateException.class, SolonRuntimeComposition::resolveDataPath);
            assertThrows(IllegalStateException.class, SolonRuntimeComposition::resolveHomePath);

            System.setProperty("ja.home-dir", "relative-home");
            assertThrows(IllegalStateException.class, SolonRuntimeComposition::resolveHomePath);
        } finally {
            restoreProperty("solon.aot.processing", previousAot);
            restoreProperty("ja.database-path", previousDatabase);
            restoreProperty("ja.data-dir", previousData);
            restoreProperty("ja.home-dir", previousHome);
        }
    }

    /** 准入前预算穿过真实延迟代理，但不得触发尚无 Plan binding 的完整租约解析。 */
    @Test
    void deferredResolverForwardsBudgetWithoutPreparingTools() {
        SolonRuntimeComposition.DeferredTurnRuntimeResolver deferred =
                new SolonRuntimeComposition.DeferredTurnRuntimeResolver();
        io.github.kongweiguang.ja.agent.domain.turn.TurnLimits expected =
                io.github.kongweiguang.ja.agent.domain.turn.TurnLimits.defaults();
        deferred.bind(new TurnRuntimeResolver() {
            /** 工具解析若被调用即重现 Plan execute 的循环准入依赖。 */
            @Override public io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease resolve(
                    io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest request) {
                throw new AssertionError("pre-admission must not prepare tools");
            }
            /** 本场景只验证预算委派，绝不启动工作区预热。 */
            @Override public void prepareWorkspace(Path root) { throw new AssertionError("unexpected warmup"); }
            /** 配置预算作为已冻结值原样返回，不能经完整租约构造。 */
            @Override public io.github.kongweiguang.ja.agent.domain.turn.TurnLimits resolveLimits(
                    io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest request) { return expected; }
        });
        assertSame(expected, deferred.resolveLimits(null));
    }

    /** 启动真实组合根，锁定唯一核心实例与显式能力装配，避免仅测试局部工厂而漏掉生产接线。 */
    @Test
    void solonPublishesOneCoreRuntimeGeneration() throws Exception {
        Path data = Files.createTempDirectory("ja-solon-");
        String previousData = System.getProperty("ja.data-dir");
        String previousRun = System.getProperty("ja.run-dir");
        try {
            System.setProperty("ja.data-dir", data.toString());
            System.setProperty("ja.run-dir", Files.createTempDirectory("ja-solon-run-").toString());
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));

            assertSame(Solon.context().getBean(AgentStore.class), Solon.context().getBean(AgentStore.class));
            assertSame(Solon.context().getBean(AgentEngine.class), Solon.context().getBean(AgentEngine.class));
            assertSame(Solon.context().getBean(TurnService.class), Solon.context().getBean(TurnService.class));
            assertTrue(Solon.context().getBean(AutomaticThreadTitleScheduler.class)
                    instanceof AutomaticThreadTitleService);
            ModelPort model = Solon.context().getBean(ModelPort.class);
            SummaryModel.Factory summaryFactory = Solon.context().getBean("jaSummaryModelFactory");
            assertSame(model, summaryFactory);
            assertNotNull(Solon.context().getBean("jaAgentEventBridge"));
            assertNotNull(Solon.context().getBean(TurnMcpSessionFactory.class));
            TurnRuntimeResolver runtimeResolver = Solon.context().getBean(TurnRuntimeResolver.class);
            assertTrue(runtimeResolver instanceof SolonRuntimeComposition.DeferredTurnRuntimeResolver);
            assertNotNull(Solon.context().getBean("jaConfigurationTurnRuntimeResolver"));
            assertNotNull(Solon.context().getBean("jaTaskAdmissionParticipantResolver"));
            assertNotNull(Solon.context().getBean("jaPlanToolFactory"));
            assertNotNull(Solon.context().getBean("jaInteractionToolFactory"));
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
            ProcessSessionUseCase processes = Solon.context().getBean(ProcessSessionUseCase.class);
            ReviewUseCase reviews = Solon.context().getBean(ReviewUseCase.class);
            assertNotNull(processes);
            assertNotNull(reviews);
            assertSame(processes, services.processes());
            assertSame(reviews, services.reviews());
            assertTrue(processes.generation() > 0);
            services.lifecycle().close();
            services.lifecycle().close();
            assertThrows(IllegalStateException.class,
                    () -> factory.open(mapper));
        } finally {
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
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

    /** 真实附件域失败必须在组合根收敛为消费者端口失败，且公开异常不携带底层存储文本。 */
    @Test
    void attachmentReaderMapsAttachmentFailureToSanitizedConsumerFailure() {
        AttachmentFailure source = new AttachmentFailure(
                AttachmentFailure.Code.CONTENT_CORRUPT, new IllegalStateException("private blob path"));
        AttachmentUseCase attachments = (AttachmentUseCase) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{AttachmentUseCase.class},
                (proxy, method, arguments) -> {
                    if ("read".equals(method.getName())) {
                        throw source;
                    }
                    throw new AssertionError("unexpected attachment operation: " + method.getName());
                });
        ManagedAttachmentReader reader = SolonRuntimeComposition.attachmentReader(attachments);

        ManagedAttachmentReader.ReadFailure failure = assertThrows(
                ManagedAttachmentReader.ReadFailure.class,
                () -> reader.inspect("att_missing", "thr_test"));

        assertSame(source, failure.getCause());
        assertEquals("managed attachment read failed", failure.getMessage());
    }

    /** 在 AOT 期间调用资源与摘要 Bean 工厂，证明跳过线程服务且不访问磁盘或启动 Provider。 */
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
            assertThrows(IllegalStateException.class,
                    () -> composition.summaryModelFactory(null).bind(null));
            assertTrue(SolonRuntimeComposition.resolveHomePath().endsWith("ja-solon-aot"));
            assertThrows(io.github.kongweiguang.ja.agent.domain.error.StorageException.class,
                    AotSideEffectGuard::requireRuntimeIo);
            ThreadUseCase threads = proxy(ThreadUseCase.class);
            AutomaticTitleUsagePort usage = proxy(AutomaticTitleUsagePort.class);
            ModelPort models = ModelPortTestSupport.of((request, sink, cancellation) ->
                    java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("AOT Provider must not run")));
            assertNull(composition.automaticThreadTitles(models, threads, usage, Clock.systemUTC()));
            try (java.util.stream.Stream<Path> files = Files.list(data)) {
                assertTrue(files.findAny().isEmpty());
            }
        } finally {
            composition.close();
            restoreProperty("solon.aot.processing", previousAot);
            restoreProperty("ja.data-dir", previousData);
        }
    }

    /** AOT 只生成依赖元数据不创建业务 Bean，因此缺席端口可选注入且真实组合仍必须 fail closed。 */
    @Test
    void aotOnlyDependenciesRemainFailClosedDuringRuntimeComposition() {
        String previousAot = System.getProperty("solon.aot.processing");
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            java.lang.reflect.Method taskFactory = java.util.Arrays.stream(
                            SolonRuntimeComposition.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("taskCoordinator"))
                    .findFirst()
                    .orElseThrow();
            Inject taskAdmission = taskFactory.getParameters()[7].getAnnotation(Inject.class);
            assertNotNull(taskAdmission);
            assertEquals("jaTaskAdmissionGateway", taskAdmission.value());
            assertFalse(taskAdmission.required());

            java.lang.reflect.Method planFactory = java.util.Arrays.stream(
                            SolonRuntimeComposition.class.getDeclaredMethods())
                    .filter(method -> method.getName().equals("planExecutionTurnAdapter"))
                    .findFirst()
                    .orElseThrow();
            String[] planPortBeans = {"jaPlanTurnPort", "jaPlanConversationPort",
                    "jaPlanWorkspacePort", "jaPlanTaskPort"};
            int[] planPortParameters = {0, 1, 2, 6};
            for (int i = 0; i < planPortBeans.length; i++) {
                Inject injection = planFactory.getParameters()[planPortParameters[i]]
                        .getAnnotation(Inject.class);
                assertNotNull(injection);
                assertEquals(planPortBeans[i], injection.value());
                assertFalse(injection.required());
            }

            System.setProperty("solon.aot.processing", "");
            assertNull(composition.taskCoordinator(null, null, null, null, null,
                    null, null, null, null, null));
            assertNull(composition.planExecutionTurnAdapter(null, null, null, null, null, null, null));

            System.clearProperty("solon.aot.processing");
            IllegalStateException taskFailure = assertThrows(IllegalStateException.class,
                    () -> composition.taskCoordinator(null, null, null, null, null,
                            null, null, null, null, null));
            assertEquals("Ja Task admission gateway is unavailable", taskFailure.getMessage());
            IllegalStateException planFailure = assertThrows(IllegalStateException.class,
                    () -> composition.planExecutionTurnAdapter(null, null, null, null, null, null, null));
            assertEquals("Ja Plan execution dependencies are unavailable", planFailure.getMessage());
        } finally {
            composition.close();
            restoreProperty("solon.aot.processing", previousAot);
        }
    }

    /** AOT 允许 Solon review 暂时漏掉代际 Bean，但占位仍须沿现有正值约束进入 Plan applier。 */
    @Test
    void planExtensionFactApplierUsesPlaceholderWhenAotGenerationIsMissing() throws Exception {
        String previousAot = System.getProperty("solon.aot.processing");
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            System.setProperty("solon.aot.processing", "");
            ObjectMapper mapper = new ObjectMapper();
            org.apache.ibatis.session.SqlSessionFactory sessions =
                    new SqlSessionFactoryBuilder().build(new Configuration());
            PlanExtensionFactApplier applier = composition.planExtensionFactApplier(
                    new io.github.kongweiguang.ja.plan.infrastructure.persistence.MybatisPlanRepository(sessions, mapper),
                    composition.planToolCommandCodec(),
                    composition.canonicalPlanJson(mapper), composition.executionRecordCodec(mapper),
                    composition.executionEventCodec(mapper), null);

            assertNotNull(applier);
            java.lang.reflect.Field generation = PlanExtensionFactApplier.class.getDeclaredField("processGeneration");
            assertTrue(generation.trySetAccessible());
            assertEquals(1L, generation.getLong(applier));
        } finally {
            composition.close();
            restoreProperty("solon.aot.processing", previousAot);
        }
    }

    /** 运行时缺失数据库分配的代际不能悄然使用 AOT 占位或建立错误 fencing 身份。 */
    @Test
    void planExtensionFactApplierRequiresGenerationOutsideAot() {
        String previousAot = System.getProperty("solon.aot.processing");
        SolonRuntimeComposition composition = new SolonRuntimeComposition();
        try {
            System.clearProperty("solon.aot.processing");
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> composition.planExtensionFactApplier(null, null, null, null, null, null));
            assertEquals("Ja runtime process generation bean is unavailable", failure.getMessage());
        } finally {
            composition.close();
            restoreProperty("solon.aot.processing", previousAot);
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
        if (previousValue == null) {
            System.clearProperty(name);
        }
        else {
            System.setProperty(name, previousValue);
        }
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
