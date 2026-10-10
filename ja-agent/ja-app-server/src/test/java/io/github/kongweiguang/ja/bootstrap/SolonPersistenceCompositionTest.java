// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.startup.StartupMaintenanceService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationProjectionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisJaOperationReceiptRepository;
import io.github.kongweiguang.ja.agent.application.concurrent.ShutdownDeadline;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.solon.integration.XPluginImpl;
import org.apache.ibatis.solon.MybatisAdapter;
import org.apache.ibatis.solon.integration.MybatisAdapterManager;
import org.apache.ibatis.session.SqlSessionFactory;
import org.noear.solon.Solon;
import org.noear.solon.core.PluginEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 固定 Solon 持久化组合必须使用官方具名工厂和 AOT 安全路径。 */
public final class SolonPersistenceCompositionTest {
    @TempDir
    Path directory;

    /** 用新 JVM 保证注册表初始为空，并同时验证 Solon 插件注册顺序与缺失时的失败关闭行为。 */
    @Test
    void registeredAdapterIsReadAndMissingAdapterFailsWithoutConstruction() throws Exception {
        assertEquals(25, Runtime.version().feature(), "isolated Solon registry probe requires JDK 25");
        Path output = directory.resolve("solon-registry-probe.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                SolonPersistenceCompositionTest.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "isolated Solon registry probe did not stop");
            assertEquals(0, child.exitValue(), () -> {
                try {
                    String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
                    return Files.readString(output, Charset.forName(encoding));
                } catch (java.io.IOException failure) {
                    return failure.getClass().getSimpleName();
                }
            });
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** 独立启动真实 Solon 插件，再在仍有数据源 Bean 的上下文中验证注册与未注册两种路径。 */
    public static void main(String[] arguments) throws Exception {
        if (Runtime.version().feature() != 25 || arguments.length != 1) {
            throw new IllegalArgumentException("isolated registry probe requires JDK 25 and one temp directory");
        }
        verifyRegistryBoundary(Path.of(arguments[0]));
    }

    /** 真实业务 bootstrap 从空注册表发布唯一插件与 Factory；移除 Adapter 后仍须失败关闭。 */
    private static void verifyRegistryBoundary(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path data = Files.createDirectories(home.resolve("data"));
        Path run = Files.createDirectories(root.resolve("run"));
        Files.createDirectories(root.resolve("logs"));
        System.setProperty("user.home", root.resolve("user-home").toString());
        System.setProperty("ja.home-dir", home.toString());
        System.setProperty("ja.data-dir", data.toString());
        System.setProperty("ja.database-path", data.resolve("ja.db").toString());
        System.setProperty("ja.run-dir", run.toString());
        System.setProperty("ja.log-dir", root.resolve("logs").toString());

        Map<String, MybatisAdapter> registry = adapterRegistry();
        assertTrue(registry.isEmpty(), "child JVM must start without a registered Adapter");
        assertNull(Solon.app());
        SidecarConfiguration configuration = new SidecarConfiguration(home, data, run, root.resolve("logs"), 1L);
        RecoverableRuntimeBootstrap bootstrap = new RecoverableRuntimeBootstrap(new String[0], configuration);
        try {
            bootstrap.factory().open(new ObjectMapper());
            assertNotNull(Solon.app());
            PluginEntity plugin = Solon.app().pluginGet(XPluginImpl.class);
            assertNotNull(plugin, "production bootstrap must publish the explicitly registered MyBatis plugin");
            assertSame(plugin, Solon.app().pluginGet(XPluginImpl.class));
            assertSame(XPluginImpl.class, plugin.getPlugin().getClass());
            assertSame(plugin.getPlugin(), Solon.app().pluginGet(XPluginImpl.class).getPlugin());
            assertEquals(1, Solon.app().cfg().plugins().stream()
                    .filter(entity -> entity.getPlugin().getClass() == XPluginImpl.class).count(),
                    "the production business container must register one MyBatis plugin instance");
            MybatisAdapter registered = MybatisAdapterManager.getOnly("ja");
            assertNotNull(registered, "MyBatis-Solon must register the named DataSource during bootstrap");
            assertSame(registered, registry.get("ja"));
            assertSame(registered, registry.get(""), "typed adapters also own MyBatis-Solon's default alias");
            SqlSessionFactory factory = registered.getFactory();
            assertSame(factory, SolonPersistenceComposition.lookupNamedSessions());
            assertSame(factory, SolonPersistenceComposition.lookupNamedSessions());

            Map<String, MybatisAdapter> adapters = new HashMap<>(registry);
            try {
                registry.clear();
                assertNull(SolonPersistenceComposition.lookupNamedSessions());
                IllegalStateException failure = assertThrows(IllegalStateException.class,
                        () -> new SolonPersistenceComposition().conversationProjectionRepository(null));
                assertEquals("Ja named SqlSessionFactory bean is unavailable", failure.getMessage());
                assertTrue(registry.isEmpty(), "a missing registration must not trigger Adapter construction");
            } finally {
                registry.clear();
                registry.putAll(adapters);
            }
        } finally {
            try {
                RuntimeServicesFactory services = Solon.context() == null
                        ? null : Solon.context().getBean(RuntimeServicesFactory.class);
                RuntimeServices runtime = services == null ? null : services.openedServices();
                if (runtime == null) {
                    bootstrap.close(ShutdownDeadline.start());
                } else {
                    runtime.close(ShutdownDeadline.start());
                }
            } finally {
                bootstrap.shutdownSolonSchedulers();
            }
        }
    }

    /** 读取 MyBatis-Solon 的进程级映射，仅供隔离子进程验证注册表边界。 */
    @SuppressWarnings("unchecked")
    private static Map<String, MybatisAdapter> adapterRegistry() throws ReflectiveOperationException {
        Field registry = MybatisAdapterManager.class.getDeclaredField("dbMap");
        registry.setAccessible(true);
        return (Map<String, MybatisAdapter>) registry.get(null);
    }

    /** 错误的 Host 目录必须在任何文件或数据库副作用前拒绝，不能靠 cwd 补齐相对路径。 */
    @Test
    void rejectsRelativeHostDirectoriesBeforeCreatingAttachmentStorage() throws Exception {
        String previousAot = System.getProperty("solon.aot.processing");
        String previousRun = System.getProperty("ja.run-dir");
        String previousData = System.getProperty("ja.data-dir");
        SolonPersistenceComposition composition = new SolonPersistenceComposition(() -> {
            throw new AssertionError("invalid Host directories must not open a database");
        });
        try (RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle()) {
            System.clearProperty("solon.aot.processing");
            System.setProperty("ja.run-dir", "relative-run");
            System.setProperty("ja.data-dir", directory.resolve("data").toString());
            assertThrows(IllegalStateException.class,
                () -> composition.attachments(null, null, Clock.systemUTC(), lifecycle));

            System.setProperty("ja.run-dir", directory.resolve("run").toString());
            System.setProperty("ja.data-dir", "relative-data");
            assertThrows(IllegalStateException.class,
                () -> composition.attachments(null, null, Clock.systemUTC(), lifecycle));
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                assertTrue(files.findAny().isEmpty(), "invalid paths must not create attachment storage");
            }
        } finally {
            restoreProperty("solon.aot.processing", previousAot);
            restoreProperty("ja.run-dir", previousRun);
            restoreProperty("ja.data-dir", previousData);
        }
    }

    /** 恢复测试借用的进程属性，防止失败场景污染其它组合根用例。 */
    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    /** 启动维护只在真实运行时处理 WAL 与临时侧聊，AOT 阶段不触碰数据库。 */
    @Test
    void startupMaintenanceDoesNotOpenDatabaseDuringAotAnalysis() {
        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.setProperty("solon.aot.processing", "");
            StartupMaintenanceService maintenance = new SolonPersistenceComposition(() -> {
                throw new AssertionError("AOT must not query the runtime MyBatis registry");
            }).startupMaintenance(null);
            assertNotNull(maintenance);
            assertThrows(IllegalStateException.class, maintenance::requireInitialized);
            assertTrue(AotSideEffectGuard.processing());
        } finally {
            if (previousAot == null) {
                System.clearProperty("solon.aot.processing");
            }
            else {
                System.setProperty("solon.aot.processing", previousAot);
            }
        }
    }

    /** Checkpoint 只通过 AgentStore 持久化，组合根不得再发布 Ja 专属双写 Bean。 */
    @Test
    void checkpointStoreIsNotPublishedAsASolonBean() {
        boolean legacyBean = java.util.Arrays.stream(SolonPersistenceComposition.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(org.noear.solon.annotation.Bean.class))
                .filter(java.util.Objects::nonNull)
                .anyMatch(bean -> "jaCheckpointStore".equals(bean.value()));
        assertFalse(legacyBean);
    }

    /** 两个只读接入端口复用唯一具名 Factory，AOT 构造也不查询注册表或打开数据库。 */
    @Test
    void engineProjectionAndReceiptAdaptersUseNamedFactoryAndAotPlaceholder() throws Exception {
        SqlSessionFactory namedFactory = inertFactory();
        SolonPersistenceComposition composition = new SolonPersistenceComposition(() -> namedFactory);
        MybatisConversationProjectionRepository projections =
                (MybatisConversationProjectionRepository) composition.conversationProjectionRepository(null);
        MybatisJaOperationReceiptRepository receipts =
                (MybatisJaOperationReceiptRepository) composition.operationReceiptRepository(null);
        assertSame(namedFactory, transactionFactory(projections));
        assertSame(namedFactory, transactionFactory(receipts));

        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.setProperty("solon.aot.processing", "");
            SolonPersistenceComposition aot = new SolonPersistenceComposition(() -> {
                throw new AssertionError("AOT must not query the runtime MyBatis registry");
            });
            assertNotNull(aot.conversationProjectionRepository(null));
            assertNotNull(aot.operationReceiptRepository(null));
        } finally {
            if (previousAot == null) {
                System.clearProperty("solon.aot.processing");
            }
            else {
                System.setProperty("solon.aot.processing", previousAot);
            }
        }
    }

    /** 防止 Solon AOT 回退为依赖 Bean 创建顺序的具名工厂参数注入。 */
    @Test
    void beanMethodsDoNotRequestSqlSessionFactoryParameters() {
        for (Method method : SolonPersistenceComposition.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.noear.solon.annotation.Bean.class) == null) {
                continue;
            }
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertTrue(parameterType != SqlSessionFactory.class,
                        () -> method.getName() + " must resolve the named adapter inside the method body");
            }
        }
    }

    /**
     * AOT 只需解析 History Bean 的声明类型，不构造依赖数据库的服务或具名 Session；
     * 即使可选 Store 缺失，也不得为元数据分析打开 SQL 资源。
     */
    @Test
    void aotHistoryServiceIsNotConstructedWithoutOpeningSqlResources() {
        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.setProperty("solon.aot.processing", "");
            MybatisHistoryService history = new SolonPersistenceComposition(() -> {
                throw new AssertionError("AOT must not query the runtime MyBatis registry");
            })
                    .history(null, SubagentPolicy::defaultPolicy, null, new ObjectMapper(),
                            Clock.systemUTC(), null, null);
            assertNull(history);
        } finally {
            if (previousAot == null) {
                System.clearProperty("solon.aot.processing");
            }
            else {
                System.setProperty("solon.aot.processing", previousAot);
            }
        }
    }

    /**
     * 普通运行时必须要求 Store 与唯一具名工厂；AOT 的无 SQL 路径不能成为缺依赖的运行时回退。
     */
    @Test
    void runtimeHistoryRequiresStoreAndNamedFactory() {
        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.clearProperty("solon.aot.processing");
            assertThrows(IllegalStateException.class, () -> new SolonPersistenceComposition(() -> null)
                    .history(null, SubagentPolicy::defaultPolicy, null, new ObjectMapper(), Clock.systemUTC(), null, null));
            assertThrows(IllegalStateException.class, () -> new SolonPersistenceComposition(() -> null)
                    .history(null, SubagentPolicy::defaultPolicy, inertAgentStore(), new ObjectMapper(),
                            Clock.systemUTC(), null, null));
        } finally {
            if (previousAot == null) {
                System.clearProperty("solon.aot.processing");
            }
            else {
                System.setProperty("solon.aot.processing", previousAot);
            }
        }
    }

    /** 返回所有方法都会失败的工厂代理，用于证明构造器不会打开 Session。 */
    private static SqlSessionFactory inertFactory() {
        return (SqlSessionFactory) Proxy.newProxyInstance(
                SqlSessionFactory.class.getClassLoader(),
                new Class<?>[]{SqlSessionFactory.class},
                (proxy, method, args) -> {
                    throw new AssertionError("composition must not invoke " + method.getName());
                });
    }

    /** AOT 构造只保存 Store 引用，不得调用其执行读写方法。 */
    private static AgentStore inertAgentStore() {
        return (AgentStore) Proxy.newProxyInstance(
                AgentStore.class.getClassLoader(), new Class<?>[]{AgentStore.class},
                (proxy, method, args) -> null);
    }

    /** 在不向生产代码增加测试访问器的前提下，读取持久化适配器事务所有者的实例身份。 */
    private static SqlSessionFactory transactionFactory(Object store) throws Exception {
        Field transactionField = store.getClass().getDeclaredField("transactions");
        transactionField.setAccessible(true);
        Object transactions = transactionField.get(store);
        Field sessionsField = transactions.getClass().getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        return (SqlSessionFactory) sessionsField.get(transactions);
    }
}
