// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 固定扩展 registry 的显式顺序、状态 namespace 冻结和失败原子性。 */
final class ExtensionRegistryTest {
    /** 每次派生只采用冻结身份，新增能力既不污染基础视图，也不串到另一请求。 */
    @Test
    void requestCopiesPreserveFrozenIdentityAndRemainIsolated() {
        MutableMiddleware basePolicy = new MutableMiddleware("base-policy", 2);
        ExtensionRegistry.Snapshot base = new ExtensionRegistry().registerMiddleware(basePolicy).freeze();
        basePolicy.id.set("changed-after-freeze");
        basePolicy.schemaVersion = 9;
        ExtensionRegistry first = ExtensionRegistry.copyOf(base);
        ExtensionRegistry second = ExtensionRegistry.copyOf(base);
        first.registerMiddleware(new MutableMiddleware("first-only", 1));
        ExtensionRegistry.Snapshot firstSnapshot = first.freeze();
        assertEquals(List.of("base-policy", "first-only"), firstSnapshot.middleware().stream()
                .map(ExtensionRegistry.MiddlewareRegistration::namespace).toList());
        assertEquals(2, firstSnapshot.middleware().getFirst().stateSchemaVersion());
        assertEquals(List.of("base-policy"), second.freeze().middleware().stream()
                .map(ExtensionRegistry.MiddlewareRegistration::namespace).toList());
        assertEquals(1, base.middleware().size());
    }

    /** 派生不是卸载或重装扩展，既有身份和 namespace 冲突仍在注册时拒绝。 */
    @Test
    void requestCopiesCannotReplaceAnExistingExtension() {
        java.util.concurrent.atomic.AtomicInteger registrations = new java.util.concurrent.atomic.AtomicInteger();
        AgentExtension extension = new AgentExtension() {
            /** 使用稳定身份验证复制不会通过空壳注册伪造来源。 */
            @Override public String id() { return "base-extension"; }

            /** 回调只能在原始显式注册时运行，派生请求不得重复业务装配。 */
            @Override public void register(ExtensionRegistry registry) {
                registrations.incrementAndGet();
                registry.registerMiddleware(new MutableMiddleware("base-policy", 1));
            }
        };
        ExtensionRegistry.Snapshot base = new ExtensionRegistry().register(extension).freeze();
        ExtensionRegistry copy = ExtensionRegistry.copyOf(base);
        assertThrows(IllegalArgumentException.class, () -> copy.register(extension));
        assertThrows(IllegalArgumentException.class,
                () -> copy.registerMiddleware(new MutableMiddleware("base-policy", 1)));
        assertEquals(1, registrations.get());
        assertEquals(base.extensionIds(), copy.freeze().extensionIds());
    }

    /** 注册时即捕获 namespace/schema，后续扩展实例改变返回值也不能重路由已建 Engine。 */
    @Test
    void middlewareRegistrationFreezesNamespaceAndSchema() {
        MutableMiddleware middleware = new MutableMiddleware("first", 2);
        ExtensionRegistry registry = new ExtensionRegistry().registerMiddleware(middleware);
        middleware.id.set("changed");
        middleware.schemaVersion = 9;

        ExtensionRegistry.MiddlewareRegistration registered = registry.freeze().middleware().getFirst();

        assertEquals("first", registered.namespace());
        assertEquals(2, registered.stateSchemaVersion());
        assertSame(middleware, registered.middleware());
    }

    /** 两个中间件不能共享持久状态 namespace，重复策略在构造 Engine 前立即失败。 */
    @Test
    void middlewareNamespaceMustBeUnique() {
        ExtensionRegistry registry = new ExtensionRegistry().registerMiddleware(new MutableMiddleware("policy", 1));

        assertThrows(IllegalArgumentException.class,
                () -> registry.registerMiddleware(new MutableMiddleware("policy", 1)));
    }

    /** 不兼容状态 schema 在注册处拒绝，避免 Engine 成功构造后才在首个任务恢复时崩溃。 */
    @Test
    void middlewareStateSchemaMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExtensionRegistry().registerMiddleware(new MutableMiddleware("policy", 0)));
    }

    /** Middleware 复用通用扩展事实 envelope，因此不能占用核心保留的 agent namespace。 */
    @Test
    void middlewareCannotUseReservedCoreNamespace() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExtensionRegistry().registerMiddleware(new MutableMiddleware("agent.policy", 1)));
    }

    /** 显式扩展回调失败时其 namespace 与能力贡献整体回滚，不留下可冻结的半注册状态。 */
    @Test
    void failedExtensionRegistrationLeavesRegistryUnchanged() {
        ExtensionRegistry registry = new ExtensionRegistry();
        AgentExtension broken = new AgentExtension() {
            /** 返回本次测试内唯一扩展身份。 */
            @Override
            public String id() {
                return "broken-extension";
            }

            /** 先贡献一个策略再失败，验证 registry 只会采用完整成功的候选状态。 */
            @Override
            public void register(ExtensionRegistry candidate) {
                candidate.registerMiddleware(new MutableMiddleware("temporary-policy", 1));
                throw new IllegalStateException("registration failed");
            }
        };

        assertThrows(IllegalStateException.class, () -> registry.register(broken));

        assertEquals(List.of(), registry.freeze().middleware());
    }

    /** 注册回调保留的候选引用不能在成功返回后偷偷追加能力，变更必须属于下一次显式注册。 */
    @Test
    void successfulRegistrationClosesTheCandidate() {
        AtomicReference<ExtensionRegistry> leaked = new AtomicReference<>();
        ExtensionRegistry registry = new ExtensionRegistry().register(new AgentExtension() {
            /** 使用固定身份确保测试只针对候选生命周期。 */
            @Override public String id() { return "retained-candidate"; }

            /** 模拟扩展缓存传入引用，宿主仍必须控制能力视图的冻结时点。 */
            @Override public void register(ExtensionRegistry candidate) {
                candidate.registerMiddleware(new MutableMiddleware("accepted-policy", 1));
                leaked.set(candidate);
            }
        });
        assertThrows(IllegalStateException.class,
                () -> leaked.get().registerMiddleware(new MutableMiddleware("late-policy", 1)));
        assertEquals(List.of("accepted-policy"), registry.freeze().middleware().stream()
                .map(ExtensionRegistry.MiddlewareRegistration::namespace).toList());
    }

    /** 冻结快照只读，防止 Builder 构造后通过列表改写策略执行顺序。 */
    @Test
    void frozenMiddlewareOrderCannotBeMutated() {
        ExtensionRegistry.Snapshot snapshot = new ExtensionRegistry()
                .registerMiddleware(new MutableMiddleware("first", 1))
                .registerMiddleware(new MutableMiddleware("second", 1))
                .freeze();

        assertEquals(List.of("first", "second"),
                snapshot.middleware().stream().map(ExtensionRegistry.MiddlewareRegistration::namespace).toList());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.middleware().clear());
    }

    /** 提供可变测试替身，以验证 registry 仅在注册阶段读取一次身份和 schema。 */
    private static final class MutableMiddleware implements AgentMiddleware {
        private final AtomicReference<String> id;
        private volatile int schemaVersion;

        /** 构造受控可变替身，不触碰 Engine 线程或实际扩展状态。 */
        private MutableMiddleware(String id, int schemaVersion) {
            this.id = new AtomicReference<>(id);
            this.schemaVersion = schemaVersion;
        }

        /** 返回测试控制的 identity，模拟实现对象在注册后发生错误漂移。 */
        @Override
        public String id() {
            return id.get();
        }

        /** 返回测试控制的 state schema，验证 Engine 快照已将其固定。 */
        @Override
        public int stateSchemaVersion() {
            return schemaVersion;
        }
    }

    /** 防止测试容器实例化 registry 契约测试类。 */
    private ExtensionRegistryTest() {
    }
}
