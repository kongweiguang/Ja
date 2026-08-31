// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisCheckpointStore;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

/** 固定 Solon 持久化组合必须使用官方具名工厂和 AOT 安全路径。 */
final class SolonPersistenceCompositionTest {
    /**
     * 验证检查点方法在正式生产事务服务中保留同一个具名适配器工厂，
     * 而不是额外创建运行时工厂。
     */
    @Test
    void checkpointStoreUsesNamedFactoryIdentityAndFailsClosedWhenMissing() throws Exception {
        SqlSessionFactory namedFactory = inertFactory();
        SolonPersistenceComposition composition = new SolonPersistenceComposition(() -> namedFactory);
        MybatisCheckpointStore store = composition.checkpointStore(null, new ObjectMapper());
        assertSame(namedFactory, transactionFactory(store));

        SolonPersistenceComposition missingComposition = new SolonPersistenceComposition(() -> null);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> missingComposition.checkpointStore(null, new ObjectMapper()));
        assertEquals("Ja named SqlSessionFactory bean is unavailable", missing.getMessage());
    }

    /** 防止 Solon AOT 回退为依赖 Bean 创建顺序的具名工厂参数注入。 */
    @Test
    void beanMethodsDoNotRequestSqlSessionFactoryParameters() {
        for (Method method : SolonPersistenceComposition.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.noear.solon.annotation.Bean.class) == null) continue;
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertTrue(parameterType != SqlSessionFactory.class,
                        () -> method.getName() + " must resolve the named adapter inside the method body");
            }
        }
    }

    /**
     * 使用任何操作都会抛错的工厂覆盖 AOT 分支；组合层可以保留元数据，
     * 但不得打开 Session，也不得要求运行时数据库所有者存在。
     */
    @Test
    void aotStartupRecoveryDoesNotOpenDatabaseOrConstructNullOwner() {
        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.setProperty("solon.aot.processing", "");
            StartupRecoveryService recovery = new SolonPersistenceComposition(() -> {
                throw new AssertionError("AOT must not query the runtime MyBatis registry");
            })
                    .startupRecovery(null, Clock.systemUTC());
            assertNotNull(recovery);
            assertTrue(AotSideEffectGuard.processing());
        } finally {
            if (previousAot == null) System.clearProperty("solon.aot.processing");
            else System.setProperty("solon.aot.processing", previousAot);
        }
    }

    /**
     * 验证 Solon AOT 可在 MyBatis 插件发布具名工厂之前构造持久化元数据，
     * 同时普通运行时路径仍会拒绝缺失的依赖。
     */
    @Test
    void aotConversationRepositoryAcceptsMissingNamedFactoryWithoutOpeningSessions() {
        String previousAot = System.getProperty("solon.aot.processing");
        try {
            System.setProperty("solon.aot.processing", "");
            MybatisConversationRepository store = new SolonPersistenceComposition(() -> {
                throw new AssertionError("AOT must not query the runtime MyBatis registry");
            })
                    .agentStore(null, new ObjectMapper(), new RuntimeResourceLifecycle());
            assertNotNull(store);
        } finally {
            if (previousAot == null) System.clearProperty("solon.aot.processing");
            else System.setProperty("solon.aot.processing", previousAot);
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

    /** 在不向生产代码增加测试访问器的前提下，读取生产事务所有者的实例身份。 */
    private static SqlSessionFactory transactionFactory(MybatisCheckpointStore store) throws Exception {
        Field transactionField = MybatisCheckpointStore.class.getDeclaredField("transactions");
        transactionField.setAccessible(true);
        Object transactions = transactionField.get(store);
        Field sessionsField = transactions.getClass().getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        return (SqlSessionFactory) sessionsField.get(transactions);
    }
}
