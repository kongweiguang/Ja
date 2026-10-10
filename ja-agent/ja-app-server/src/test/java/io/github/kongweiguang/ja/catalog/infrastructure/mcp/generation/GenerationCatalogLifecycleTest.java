// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.McpSession;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.McpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpDeadline;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpServerDefinition;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** 验证工作区缓存淘汰与配置刷新不会关闭捕获中的 MCP 目录。 */
final class GenerationCatalogLifecycleTest {
    private static final String PROVIDER_ID = "provider_catalog_lifecycle";
    private static final String MODEL_ID = "model_catalog_lifecycle";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 容量淘汰后，旧快照仍可打开；Gateway pin 释放前真实 Session 必须保持活动。 */
    @Test
    void capacityEvictionRetiresOnlyAfterCapturedGatewayCloses(@TempDir Path temporary) throws Exception {
        try (Fixture fixture = Fixture.create(temporary)) {
            Path capturedRoot = Files.createDirectories(temporary.resolve("workspace-00"));
            try (ConfigurationGenerationPort.Lease lease = fixture.acquire(capturedRoot);
                 GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot = fixture.capture(capturedRoot, lease)) {
                FakeSession capturedSession = fixture.sessions.sessions.getFirst();
                assertEquals(1, fixture.sessions.activeSessions.get());
                for (int index = 1; index <= 32; index++) {
                    Path otherRoot = Files.createDirectories(temporary.resolve("workspace-%02d".formatted(index)));
                    try (ConfigurationGenerationPort.Lease otherLease = fixture.acquire(otherRoot)) {
                        fixture.catalog.prepareWorkspace(otherRoot, otherLease);
                    }
                }
                assertEquals(1, fixture.sessions.activeSessions.get(),
                        "workspace eviction must preserve the capture reservation");
                try (TurnMcpSessionFactory.Session session = fixture.factory.open(snapshot, CancellationToken.none())) {
                    assertEquals("available", session.gateway().serverStatuses().getFirst().state());
                    assertEquals(1, fixture.sessions.activeSessions.get(),
                            "the Gateway pin must survive release of the capture reservation");
                }
                assertEquals(1, capturedSession.closeCount.get());
                assertEquals(0, fixture.sessions.activeSessions.get());
            }
        }
    }
    /** 代际刷新移除旧缓存 owner，但捕获窗口 pin 允许旧 Turn 打开并在终态后关闭。 */
    @Test
    void generationRefreshAfterCaptureKeepsOldCatalogOpenable(@TempDir Path temporary) throws Exception {
        try (Fixture fixture = Fixture.create(temporary)) {
            Path workspace = Files.createDirectories(temporary.resolve("workspace-refresh"));
            String originalGeneration;
            FakeSession originalSession;
            try (ConfigurationGenerationPort.Lease originalLease = fixture.acquire(workspace)) {
                originalGeneration = originalLease.generationId();
                try (GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot =
                             fixture.capture(workspace, originalLease)) {
                    originalSession = fixture.sessions.sessions.getFirst();
                    fixture.refreshProviderCredential();
                    try (ConfigurationGenerationPort.Lease refreshed = fixture.acquire(workspace)) {
                        assertNotEquals(originalGeneration, refreshed.generationId());
                        fixture.catalog.prepareWorkspace(workspace, refreshed);
                    }
                    assertEquals(1, fixture.sessions.activeSessions.get(),
                            "generation refresh must leave the captured session available");
                    try (TurnMcpSessionFactory.Session session =
                                 fixture.factory.open(snapshot, CancellationToken.none())) {
                        assertEquals("available", session.gateway().serverStatuses().getFirst().state());
                    }
                    assertEquals(1, originalSession.closeCount.get());
                    try (ConfigurationGenerationPort.Lease refreshed = fixture.acquire(workspace);
                         GenerationTurnMcpSessionFactory.CatalogSnapshot next = fixture.capture(workspace, refreshed);
                         TurnMcpSessionFactory.Session session = fixture.factory.open(next, CancellationToken.none())) {
                        assertEquals(2, fixture.sessions.sessions.size(),
                                "the refreshed generation must own a newly created service directory");
                        assertTrue(fixture.sessions.activeSessions.get() > 0);
                    }
                }
            }
        }
    }
    /** 未打开的快照在退休后关闭时释放最后一个 reservation，避免目录和 Session 残留。 */
    @Test
    void closingUnopenedRetiredSnapshotReleasesReservation(@TempDir Path temporary) throws Exception {
        try (Fixture fixture = Fixture.create(temporary)) {
            Path workspace = Files.createDirectories(temporary.resolve("workspace-unopened"));
            GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot;
            try (ConfigurationGenerationPort.Lease originalLease = fixture.acquire(workspace)) {
                snapshot = fixture.capture(workspace, originalLease);
            }
            FakeSession capturedSession = fixture.sessions.sessions.getFirst();
            fixture.refreshProviderCredential();
            try (ConfigurationGenerationPort.Lease refreshed = fixture.acquire(workspace)) {
                fixture.catalog.prepareWorkspace(workspace, refreshed);
            }
            assertEquals(1, fixture.sessions.activeSessions.get());
            snapshot.close();
            assertEquals(1, capturedSession.closeCount.get());
            assertEquals(0, fixture.sessions.activeSessions.get());
        }
    }
    /** 取消发生在 Gateway admission 后也必须释放 Gateway pin 与已转交的 capture reservation。 */
    @Test
    void cancelledOpenReleasesBothDirectoryReferences(@TempDir Path temporary) throws Exception {
        try (Fixture fixture = Fixture.create(temporary)) {
            Path workspace = Files.createDirectories(temporary.resolve("workspace-cancelled"));
            try (ConfigurationGenerationPort.Lease lease = fixture.acquire(workspace);
                 GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot = fixture.capture(workspace, lease)) {
                FakeSession capturedSession = fixture.sessions.sessions.getFirst();
                assertThrows(java.util.concurrent.CancellationException.class,
                        () -> fixture.factory.open(snapshot, new CancelledToken()));
                fixture.refreshProviderCredential();
                try (ConfigurationGenerationPort.Lease refreshed = fixture.acquire(workspace)) {
                    fixture.catalog.prepareWorkspace(workspace, refreshed);
                }
                assertEquals(1, capturedSession.closeCount.get(),
                        "cancelled open must release its Gateway pin before workspace retirement");
                assertEquals(0, fixture.sessions.activeSessions.get());
            }
        }
    }
    /** 调用方可同步于公开快照；清理不能依赖该对象的 monitor，否则外部持锁会阻止资源释放。 */
    @Test
    void closeDoesNotWaitForCallerHeldSnapshotMonitor() throws Exception {
        GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot =
                GenerationTurnMcpSessionFactory.CatalogSnapshot.planningEmpty();
        CountDownLatch monitorHeld = new CountDownLatch(1);
        CountDownLatch releaseMonitor = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread monitorOwner = new Thread(() -> {
            synchronized (snapshot) {
                monitorHeld.countDown();
                try {
                    releaseMonitor.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Thread closer = new Thread(() -> {
            snapshot.close();
            closeReturned.countDown();
        });
        monitorOwner.start();
        try {
            assertTrue(monitorHeld.await(1, TimeUnit.SECONDS));
            closer.start();
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS));
        } finally {
            releaseMonitor.countDown();
            monitorOwner.join(1_000);
            if (closer.getState() != Thread.State.NEW) {
                closer.join(1_000);
            }
        }
    }
    /** 捕获时冻结 Provider、Model、工作区与请求预算身份。 */
    private static TurnMcpSessionFactory.Context context(Path workspace) {
        return new TurnMcpSessionFactory.Context(PROVIDER_ID, MODEL_ID, workspace,
                Instant.now().plus(Duration.ofSeconds(20)), null);
    }
    /** 配置使用真实 schema 与 MCP 定义，只有传输创建被 fake 替代。 */
    private static String configuration() {
        return """
            schema_version = 2
            config_revision = 1
            default_access_mode = "approval_required"
            default_provider_id = "provider_catalog_lifecycle"
            default_model_id = "model_catalog_lifecycle"
            default_reasoning_level = "medium"
            disabled_skills = []
            interaction = { clarification_enabled = true }
            [subagents]
            is_enabled = true
            provider_id = { __ja_null = true }
            model_id = { __ja_null = true }
            reasoning_level = { __ja_null = true }
            [[providers]]
            provider_id = "provider_catalog_lifecycle"
            name = "Catalog lifecycle"
            api = "openai_responses"
            base_url = "http://127.0.0.1"
            credential_id = "cred_model"
            [providers.network_timeouts]
            connect_timeout_ms = 10000
            request_timeout_ms = 120000
            [providers.agent_defaults]
            [providers.agent_defaults.context]
            auto_compact = true
            [[providers.models]]
            model_id = "model_catalog_lifecycle"
            name = "Catalog lifecycle model"
            model = "fixture"
            reasoning_level_map = { medium = "medium" }
            default_reasoning_level = "medium"
            [providers.models.capabilities]
            context_window_tokens = 128000
            max_output_tokens = 8192
            [[mcp_servers]]
            mcp_id = "mcp_catalog_lifecycle"
            name = "Catalog lifecycle service"
            transport = "stdio"
            endpoint = "mcp-lifecycle-fake"
            args = []
            env = {}
            headers = {}
            auth = { kind = "none" }
            is_enabled = true
            """;
    }
    /** 通过一次性配置代际租约更新无关 Provider 凭据，以产生新代际且保持 MCP 定义不变。 */
    private static final class Fixture implements AutoCloseable {
        private final ConfigurationRuntimeAdapter adapter;
        private final GenerationCatalog catalog;
        private final ConfigurationApplicationService configurations;
        private final FakeSessionFactory sessions;
        private final GenerationTurnMcpSessionFactory factory;
        private String configurationVersion;
        /** 只保存测试组合的既有资源 owner，不创建额外配置或 MCP 生命周期。 */
        private Fixture(ConfigurationRuntimeAdapter adapter, GenerationCatalog catalog,
                        ConfigurationApplicationService configurations, FakeSessionFactory sessions,
                        String configurationVersion) {
            this.adapter = adapter;
            this.catalog = catalog;
            this.configurations = configurations;
            this.sessions = sessions;
            this.factory = new GenerationTurnMcpSessionFactory(JSON, McpLimits.DEFAULT, catalog);
            this.configurationVersion = configurationVersion;
        }
        /** 使用磁盘配置 Owner 与计数型 Session fake 还原真实目录组装路径。 */
        private static Fixture create(Path temporary) throws Exception {
            Path home = Files.createDirectories(temporary.resolve("ja-home"));
            Path data = Files.createDirectories(temporary.resolve("data"));
            Path run = Files.createDirectories(temporary.resolve("run"));
            Path logs = Files.createDirectories(temporary.resolve("logs"));
            Files.writeString(home.resolve("config.toml"), configuration());
            ConfigurationRuntimeAdapter adapter = new ConfigurationRuntimeAdapter(
                    new SidecarConfiguration(home, data, run, logs), JSON);
            FakeSessionFactory sessions = new FakeSessionFactory();
            GenerationCatalog catalog = new GenerationCatalog(temporary, JSON, McpLimits.DEFAULT, sessions, new WorkspaceBoundaryFactoryAdapter());
            try {
                ConfigurationApplicationService configurations = new ConfigurationApplicationService(adapter);
                ConfigurationUseCase.CredentialResult model = configurations.setCredential(
                        "cred_model", "model-secret", "cfg_missing");
                return new Fixture(adapter, catalog, configurations, sessions, model.version());
            } catch (RuntimeException failure) {
                catalog.close();
                adapter.close();
                throw failure;
            }
        }
        /** 获取当前工作区的领域代际租约，不让测试直接访问 configuration adapter 类型。 */
        private ConfigurationGenerationPort.Lease acquire(Path workspace) {
            return new TestGenerationLease(configurations.acquire(workspace));
        }
        /** 登记工作区后在 Provider 安全点捕获 MCP schema 和其共享目录 reservation。 */
        private GenerationTurnMcpSessionFactory.CatalogSnapshot capture(
                Path workspace, ConfigurationGenerationPort.Lease lease) {
            catalog.prepareWorkspace(workspace, lease);
            return factory.catalog(context(workspace), lease);
        }
        /** 推进凭据版本但不更改 MCP 定义，确保刷新仅由 generation identity 触发。 */
        private void refreshProviderCredential() {
            ConfigurationUseCase.CredentialResult refreshed = configurations.setCredential(
                    "cred_model", "model-secret-refreshed", configurationVersion);
            configurationVersion = refreshed.version();
        }
        /** 先关闭 MCP 目录 owner，再关闭配置运行时及其代际缓存。 */
        @Override
        public void close() {
            try {
                catalog.close();
            } finally {
                adapter.close();
            }
        }
    }
    /**
     * 配置端口的测试桥只委派已存在 lease 能力，不扩大测试所见的配置 owner。
     * @param delegate 配置 owner 创建的 lease，负责冻结视图、凭据解析和生命周期释放
     */
    private record TestGenerationLease(ConfigurationGenerationLease delegate)
            implements ConfigurationGenerationPort.Lease {
        /** 拒绝空底层 lease，避免夹具跳过真实配置生命周期。 */
        private TestGenerationLease {
            java.util.Objects.requireNonNull(delegate, "delegate");
        }
        /** 返回 configuration owner 发布的冻结代际身份。 */
        @Override
        public String generationId() {
            return delegate.generationId();
        }
        /** 暴露仅含领域值与凭据引用的配置投影。 */
        @Override
        public ConfigurationGenerationSnapshot snapshot() {
            return delegate.view();
        }
        /** 凭据解析仍由原始 generation lease 控制。 */
        @Override
        public String secretFor(String credentialId) {
            return delegate.secretFor(credentialId);
        }
        /** 幂等释放原配置代际引用。 */
        @Override
        public void close() {
            delegate.close();
        }
    }
    /** 已取消 Token 在回调注册时同步关闭 Gateway，模拟取消先于执行入口的准入竞态。 */
    private static final class CancelledToken implements CancellationToken {
        /** 取消状态在本夹具整个生命周期中保持为真。 */
        @Override
        public boolean isCancellationRequested() {
            return true;
        }
        /** 返回不含外部信息的固定取消原因。 */
        @Override
        public Optional<String> reason() {
            return Optional.of("test_cancelled");
        }
        /** 注册时同步执行资源清理，覆盖取消先到达的交错顺序。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            java.util.Objects.requireNonNull(callback, "callback").run();
            return Registration.noop();
        }
    }
    /** 每次目录建立创建可计数 Session，关闭时同步更新活动数以提供确定性生命周期断言。 */
    private static final class FakeSessionFactory implements McpSessionFactory {
        private final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
        private final AtomicInteger activeSessions = new AtomicInteger();
        /** 以不启动真实子进程的 Session 替换 transport 层。 */
        @Override
        public McpSession open(McpServerDefinition definition, McpDeadline deadline) {
            FakeSession session = new FakeSession(activeSessions);
            sessions.add(session);
            activeSessions.incrementAndGet();
            return session;
        }
    }
    /** 空工具目录足以验证 owner 生命周期，close 计数则用于确认最终没有活跃 Session。 */
    private static final class FakeSession implements McpSession {
        private final AtomicInteger activeSessions;
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        /** 绑定目录 factory 的活动计数器。 */
        private FakeSession(AtomicInteger activeSessions) {
            this.activeSessions = activeSessions;
        }
        /** 无副作用完成协议初始化。 */
        @Override
        public void initialize() {
        }
        /** 返回确定的空目录，避免生命周期测试依赖远端 Tool 定义。 */
        @Override
        public ToolPage listTools(String cursor) {
            return new ToolPage(List.of(), null);
        }
        /** 调用路径不属于本回归范围。 */
        @Override
        public RemoteResult call(String toolName, io.github.kongweiguang.ja.agent.domain.json.JsonObject arguments) {
            return new RemoteResult(false, "ok", Optional.empty());
        }
        /** 幂等归还活动 Session 计数，模拟传输关闭边界。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
                activeSessions.decrementAndGet();
            }
        }
    }
}
