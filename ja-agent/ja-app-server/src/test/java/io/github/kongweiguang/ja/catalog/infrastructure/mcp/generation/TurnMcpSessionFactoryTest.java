// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.testsupport.McpStdioFixture;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.NetworkntToolArgumentValidation;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.tools.McpAgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import io.github.kongweiguang.ja.catalog.application.gateway.McpGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
/** 验证每次请求的 workspace 绑定、代际捕获、batch 释放以及 stdio 子进程零残留。 */
final class TurnMcpSessionFactoryTest {
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    /** 打开失败保留稳定外层消息，并传递只含本地稳定码的 cause 供内部诊断。 */
    @Test
    void openFailureKeepsStableMessageAndSanitizedCause(@TempDir Path workspace) throws Exception {
        RuntimeException registrationFailure = new IllegalStateException("mcp_catalog_pin_release_failed");
        CancellationToken cancellation = new CancellationToken() {
            /** 注入的失败不代表请求已取消。 */
            @Override public boolean isCancellationRequested() { return false; }
            /** 本用例不发布取消原因。 */
            @Override public Optional<String> reason() { return Optional.empty(); }
            /** 让 Gateway 已完成 pin 转交后在注册边界失败，验证包装异常的诊断链。 */
            @Override public Registration onCancellation(Runnable callback) { throw registrationFailure; }
        };
        try (GenerationCatalog catalog = new GenerationCatalog(workspace, JSON, McpLimits.DEFAULT, new WorkspaceBoundaryFactoryAdapter());
             GenerationTurnMcpSessionFactory.CatalogSnapshot snapshot =
                     GenerationTurnMcpSessionFactory.CatalogSnapshot.planningEmpty()) {
            GenerationTurnMcpSessionFactory factory =
                    new GenerationTurnMcpSessionFactory(JSON, McpLimits.DEFAULT, catalog);
            IllegalStateException observed = assertThrows(IllegalStateException.class,
                    () -> factory.open(snapshot, cancellation));
            assertEquals("turn_mcp_open_failed", observed.getMessage());
            assertSame(registrationFailure, observed.getCause());
        }
    }
    /** 同一工作区和配置下的两个客户端必须创建独立 stdio，并在各自 Turn 租约结束时
     * 只回收自己的进程；客户端源 Map 的后续变化不能重写已冻结环境。 */
    @Test
    void nativeClientsKeepDistinctMcpEnvironmentsAndReleaseAtTerminal(@TempDir Path workspace) throws Exception {
        Path report = workspace.resolve("client-mcp-report.txt");
        Path classes = Path.of(McpStdioFixture.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        Path home = Files.createDirectories(workspace.resolve("home"));
        Path data = Files.createDirectories(workspace.resolve("data"));
        Path run = Files.createDirectories(workspace.resolve("run"));
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        Files.writeString(home.resolve("config.toml"), generationConfig(report, classes));
        SidecarConfiguration configuration = new SidecarConfiguration(home, data, run, logs);
        long firstPid;
        long secondPid;
        try (ConfigurationRuntimeAdapter adapter = new ConfigurationRuntimeAdapter(configuration, JSON);
             GenerationCatalog catalog = new GenerationCatalog(workspace, JSON, McpLimits.DEFAULT, new WorkspaceBoundaryFactoryAdapter())) {
            ConfigurationApplicationService owner = new ConfigurationApplicationService(adapter);
            ConfigurationUseCase.CredentialResult model = owner.setCredential(
                    "cred_model", "model-secret", "cfg_missing");
            owner.setCredential("cred_mcp", "mcp-secret", model.version());
            try (ConfigurationGenerationPort.Lease lease = new TestGenerationLease(owner.acquire(null))) {
                GenerationTurnMcpSessionFactory factory = new GenerationTurnMcpSessionFactory(
                        JSON, McpLimits.DEFAULT, catalog);
                Map<String, String> firstEnvironment = new HashMap<>(System.getenv());
                firstEnvironment.put("USERPROFILE", workspace.resolve("first-profile").toString());
                firstEnvironment.put("JA_CLIENT_PRIVATE", "first-private-value");
                NativeExecutionSnapshot firstSnapshot = new NativeExecutionSnapshot(firstEnvironment, null);
                firstEnvironment.remove("USERPROFILE");
                firstEnvironment.put("JA_CLIENT_PRIVATE", "mutated-private-value");
                Map<String, String> secondEnvironment = new HashMap<>(System.getenv());
                secondEnvironment.remove("USERPROFILE");
                secondEnvironment.put("JA_CLIENT_PRIVATE", "second-private-value");
                NativeExecutionSnapshot secondSnapshot = new NativeExecutionSnapshot(secondEnvironment, null);
                Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
                TurnMcpSessionFactory.Context first = new TurnMcpSessionFactory.Context(
                        "provider_turn_mcp", "model_turn_mcp", workspace, deadline, firstSnapshot);
                TurnMcpSessionFactory.Context second = new TurnMcpSessionFactory.Context(
                        "provider_turn_mcp", "model_turn_mcp", workspace, deadline, secondSnapshot);
                try (GenerationTurnMcpSessionFactory.CatalogSnapshot firstCatalog = factory.catalog(first, lease);
                     TurnMcpSessionFactory.Session firstSession =
                             factory.open(firstCatalog, CancellationToken.none())) {
                    assertTrue(awaitObservation(report, "parent=true"));
                    firstPid = Long.parseLong(Files.readAllLines(report, StandardCharsets.UTF_8).getFirst());
                    assertTrue(ProcessHandle.of(firstPid).orElseThrow().isAlive());
                    try (GenerationTurnMcpSessionFactory.CatalogSnapshot secondCatalog = factory.catalog(second, lease);
                         TurnMcpSessionFactory.Session secondSession =
                                 factory.open(secondCatalog, CancellationToken.none())) {
                        assertTrue(awaitObservation(report, "parent=false"));
                        secondPid = Long.parseLong(Files.readAllLines(report, StandardCharsets.UTF_8).getFirst());
                        assertTrue(firstPid != secondPid, "client environments must not share one stdio process");
                        assertTrue(ProcessHandle.of(firstPid).orElseThrow().isAlive(),
                                "first in-flight Turn remains alive after second client connects");
                        assertTrue(ProcessHandle.of(secondPid).orElseThrow().isAlive());
                        String observation = Files.readString(report, StandardCharsets.UTF_8);
                        assertFalse(observation.contains("first-private-value"));
                        assertFalse(observation.contains("second-private-value"));
                        assertFalse(observation.contains("mutated-private-value"));
                        assertEquals(1, firstSession.snapshot().tools().size());
                        assertEquals(1, secondSession.snapshot().tools().size());
                    }
                    assertTrue(awaitExit(secondPid));
                    assertTrue(ProcessHandle.of(firstPid).orElseThrow().isAlive(),
                            "closing one client must not cancel the other Turn");
                }
            }
            assertTrue(awaitExit(firstPid));
        }
    }
    /** 验证同一代际复用选定 stdio，batch 取消只释放 pin，catalog 关闭才回收进程。 */
    @Test
    void bindsGenerationModelToTurnWorkspaceAndCancellation(@TempDir Path workspace) throws Exception {
        Path report = workspace.resolve("turn-mcp-report.txt");
        Path classes = Path.of(McpStdioFixture.class
                .getProtectionDomain().getCodeSource().getLocation().toURI());
        String secret = "turn-private-secret";
        Path home = Files.createDirectories(workspace.resolve("home"));
        Path data = Files.createDirectories(workspace.resolve("data"));
        Path run = Files.createDirectories(workspace.resolve("run"));
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        long[] observedPid = {-1L};
        Files.writeString(home.resolve("config.toml"), generationConfig(report, classes));
        SidecarConfiguration configuration = new SidecarConfiguration(
                home, data, run, logs);
        try (ConfigurationRuntimeAdapter adapter = new ConfigurationRuntimeAdapter(configuration, JSON);
             GenerationCatalog catalog = new GenerationCatalog(workspace, JSON, McpLimits.DEFAULT, new WorkspaceBoundaryFactoryAdapter())) {
            ConfigurationApplicationService owner = new ConfigurationApplicationService(adapter);
            ConfigurationUseCase.CredentialResult model = owner.setCredential(
                    "cred_model", "model-secret", "cfg_missing");
            owner.setCredential("cred_mcp", secret, model.version());
            try (ConfigurationGenerationPort.Lease lease =
                         new TestGenerationLease(owner.acquire(null))) {
                ConfigurationGenerationSnapshot.Provider provider =
                        lease.snapshot().requireProvider("provider_turn_mcp");
                ConfigurationGenerationSnapshot.Model modelDefinition =
                        lease.snapshot().requireModel(provider.providerId(), "model_turn_mcp");
                TurnMcpSessionFactory.Context context = new TurnMcpSessionFactory.Context(
                        provider.providerId(), modelDefinition.modelId(), workspace.toAbsolutePath(),
                        Instant.now().plus(Duration.ofSeconds(20)), null);
                catalog.prepareWorkspace(workspace, lease);
                assertFalse(Files.exists(report), "workspace prepare must not start MCP IO");
                GenerationTurnMcpSessionFactory factory = new GenerationTurnMcpSessionFactory(
                        JSON, McpLimits.DEFAULT, catalog);
                ManualToken token = new ManualToken();
                GenerationTurnMcpSessionFactory.CatalogSnapshot providerCatalog =
                        factory.catalog(context, lease);
                assertEquals(1, providerCatalog.snapshot().tools().size());
                assertEquals(1, providerCatalog.routeIdentities().size());
                TurnMcpSessionFactory.Session session = factory.open(providerCatalog, token);
                try {
                    assertEquals(providerCatalog.snapshot(), session.snapshot());
                    assertEquals(1, session.snapshot().tools().size());
                    assertTrue(awaitObservation(report, "method=initialize"));
                    List<String> observations = Files.readAllLines(report, StandardCharsets.UTF_8);
                    long pid = Long.parseLong(observations.getFirst());
                    observedPid[0] = pid;
                    assertTrue(observations.contains("cwd=" + workspace.toRealPath()));
                    assertTrue(observations.contains("secret=true"));
                    assertTrue(observations.contains("method=initialize"));
                    assertTrue(observations.contains("method=tools/list"));
                    assertFalse((session.toString() + observations).contains(secret));
                    token.cancel();
                    assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive(),
                            "batch release keeps healthy notification session alive");
                } finally {
                    session.close();
                }
            }
            catalog.close();
            assertTrue(awaitExit(observedPid[0]));
        }
    }
    /** 项目服务使用真实配置和 stdio 进入冻结模型目录；撤信后旧 batch 的新调用也必须被拒绝。 */
    @Test
    void trustedProjectToolCallsAndTrustRevocationBlocksNewCalls(@TempDir Path workspace) throws Exception {
        Path report = workspace.resolve("project-mcp-report.txt");
        Path classes = Path.of(McpStdioFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path home = Files.createDirectories(workspace.resolve("home"));
        Path data = Files.createDirectories(workspace.resolve("data"));
        Path run = Files.createDirectories(workspace.resolve("run"));
        Path logs = Files.createDirectories(workspace.resolve("logs"));
        String configurationText = generationConfig(report, classes);
        int projectBlock = configurationText.indexOf("[[mcp_servers]]");
        Files.writeString(home.resolve("config.toml"), configurationText.substring(0, projectBlock));
        Files.writeString(Files.createDirectories(workspace.resolve(".ja")).resolve("config.toml"),
                "schema_version = 2\nconfig_revision = 1\n" + configurationText.substring(projectBlock));
        SidecarConfiguration configuration = new SidecarConfiguration(home, data, run, logs);
        long[] observedPid = {-1L};
        try (ConfigurationRuntimeAdapter adapter = new ConfigurationRuntimeAdapter(configuration, JSON);
             GenerationCatalog catalog = new GenerationCatalog(workspace, JSON, McpLimits.DEFAULT, new WorkspaceBoundaryFactoryAdapter())) {
            ConfigurationApplicationService owner = new ConfigurationApplicationService(adapter);
            ConfigurationUseCase.CredentialResult model = owner.setCredential(
                    "cred_model", "model-secret", "cfg_missing");
            owner.setCredential("cred_mcp", "project-secret", model.version());
            assertTrue(adapter.synchronizeWorkspaceTrust(workspace, true));
            ConfigurationGenerationPort generation = path -> new TestGenerationLease(owner.acquire(path));
            try (ConfigurationGenerationPort.Lease lease = generation.acquire(workspace)) {
                assertTrue(lease.snapshot().trusted());
                assertEquals(ConfigurationGenerationSnapshot.Scope.PROJECT,
                        lease.snapshot().mcpDefinitions().getFirst().scope());
                GenerationTurnMcpSessionFactory factory = new GenerationTurnMcpSessionFactory(
                        JSON, McpLimits.DEFAULT, catalog, generation);
                TurnMcpSessionFactory.Context context = new TurnMcpSessionFactory.Context(
                        "provider_turn_mcp", "model_turn_mcp", workspace,
                        Instant.now().plus(Duration.ofSeconds(20)), null);
                catalog.prepareWorkspace(workspace, lease);
                GenerationTurnMcpSessionFactory.CatalogSnapshot discovered = factory.catalog(context, lease);
                assertEquals(1, discovered.snapshot().tools().size());
                try (TurnMcpSessionFactory.Session session = factory.open(discovered, CancellationToken.none())) {
                    McpGateway.McpSnapshot snapshot = session.snapshot();
                    TestJsonValueCodec codec = new TestJsonValueCodec();
                    JaTool modelTool = McpAgentTool.adapt(session.gateway(), snapshot,
                            session.routeIdentities(), codec, new NetworkntToolArgumentValidation(codec)).getFirst();
                    AgentIdentity identity = new AgentIdentity(
                            "tenant_project", "thread_project", "turn_project", "user_project");
                    AgentTool.Invocation searchInvocation = new AgentTool.Invocation(
                            "call_project_search", "mcp", modelAction("search", null, null, "echo", null), 0);
                    JaTool.ExecutionContext searchContext = contextForCall(
                            identity, searchInvocation, workspace, lease.generationId());
                    JaTool.ToolResult search = modelTool.execute(
                            searchInvocation, searchContext, CancellationToken.none())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals("SUCCEEDED", search.outcome().name());
                    assertTrue(search.content().contains("mcp_turn_fixture"));
                    assertTrue(search.content().contains("echo"));
                    AgentTool.Invocation invocation = new AgentTool.Invocation(
                            "call_project_tool", "mcp",
                            modelAction("call", "mcp_turn_fixture", "echo", null, "{}"), 1);
                    JaTool.ExecutionContext execution = contextForCall(
                            identity, invocation, workspace, lease.generationId());
                    JaTool.ToolResult result = modelTool.execute(invocation, execution, CancellationToken.none())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals("SUCCEEDED", result.outcome().name());
                    assertTrue(awaitObservation(report, "method=tools/call"));
                    observedPid[0] = Long.parseLong(Files.readAllLines(report, StandardCharsets.UTF_8).getFirst());
                    assertTrue(adapter.synchronizeWorkspaceTrust(workspace, false));
                    JaTool.ToolResult revoked = modelTool.execute(invocation, execution, CancellationToken.none())
                            .toCompletableFuture().get(3, TimeUnit.SECONDS);
                    assertEquals("MCP_TOOL_FAILED", revoked.errorCode());
                    assertEquals(1, Files.readAllLines(report, StandardCharsets.UTF_8).stream()
                            .filter("method=tools/call"::equals).count());
                }
            }
            catalog.close();
            assertTrue(awaitExit(observedPid[0]));
        }
    }
    /** 为每个原生 callId 单独冻结 Ja 上下文，重复 Thread/Turn 必须与 AgentIdentity 一致。 */
    private static JaTool.ExecutionContext contextForCall(AgentIdentity identity,
            AgentTool.Invocation invocation, Path workspace, String configGeneration) {
        return new JaTool.ExecutionContext(identity, invocation.callId(), identity.toolCallKey(invocation.callId()),
                identity.threadId(), identity.turnId(), workspace, AccessMode.APPROVAL_REQUIRED, configGeneration,
                Instant.now().plus(Duration.ofSeconds(20)), "ws_project", null, null, TurnOrigin.USER);
    }
    /** 用固定字段构造模型可见的 MCP 动作，确保项目测试覆盖真实 Adapter 而非直连 Runtime。 */
    private static io.github.kongweiguang.ja.agent.domain.json.JsonObject modelAction(
            String action, String serverId, String toolName, String query, String argumentsJson) {
        return JsonObjects.builder().putText("action", action)
                .put("serverId", serverId == null ? JsonNull.INSTANCE : new JsonText(serverId))
                .put("toolName", toolName == null ? JsonNull.INSTANCE : new JsonText(toolName))
                .put("query", query == null ? JsonNull.INSTANCE : new JsonText(query))
                .put("offset", JsonNull.INSTANCE)
                .put("argumentsJson", argumentsJson == null ? JsonNull.INSTANCE : new JsonText(argumentsJson))
                .build();
    }
    /** 使用凭据引用写入 Java 所有的 Schema，夹具不得包含 MCP Secret 明文。 */
    private static String generationConfig(Path report, Path classes) {
        return """
            schema_version = 2
            config_revision = 1
            default_access_mode = "approval_required"
            default_provider_id = "provider_turn_mcp"
            default_model_id = "model_turn_mcp"
            default_reasoning_level = "medium"
            disabled_skills = []
            interaction = { clarification_enabled = true }
            [subagents]
            is_enabled = true
            provider_id = { __ja_null = true }
            model_id = { __ja_null = true }
            reasoning_level = { __ja_null = true }
            [[providers]]
            provider_id = "provider_turn_mcp"
            name = "Turn MCP"
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
            model_id = "model_turn_mcp"
            name = "Turn MCP Model"
            model = "fixture"
            reasoning_level_map = { medium = "medium" }
            default_reasoning_level = "medium"
            [providers.models.capabilities]
            context_window_tokens = 128000
            max_output_tokens = 8192
            [[mcp_servers]]
            mcp_id = "mcp_turn_fixture"
            name = "Turn fixture"
            transport = "stdio"
            endpoint = "\
            """ + toml(javaExecutable()) + "\"\n"
                + "args = [\"-cp\", \"" + toml(classes.toString()) + "\", \""
                + McpStdioFixture.class.getName() + "\", \""
                + toml(report.toString()) + """
                    "]
                    env = { JA_ALLOWED = "yes" }
                    headers = {}
                    auth = { kind = "env", name = "JA_SECRET", credential_id = "cred_mcp" }
                    is_enabled = true
                    """;
    }
    /** 转义 Windows 路径分隔符，以满足严格 TOML 字符串边界。 */
    private static String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
    /** 显式解析当前 Java 二进制，避免子进程依赖父进程 PATH 查找。 */
    private static String javaExecutable() {
        String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", binary).toString();
    }
    /** 只等待有界夹具交接，不把任意文件系统活动误当作完成信号。 */
    private static boolean awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path) && Files.size(path) > 0) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
    /** 等待精确的子进程观测值，避免断言与传输初始化竞争。 */
    private static boolean awaitObservation(Path path, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (awaitFile(path) && Files.readAllLines(path, StandardCharsets.UTF_8).contains(expected)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
    /** 检查操作系统进程表，因为仅凭 Session.close 返回不能证明没有孤儿进程。 */
    private static boolean awaitExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
    /**
     * 在测试组合根模拟生产桥接，验证 catalog 只依赖自己拥有的出站租约类型。
     *
     * @param delegate 委派执行者
     */
    private record TestGenerationLease(ConfigurationGenerationLease delegate)
            implements ConfigurationGenerationPort.Lease {
        /** 拒绝空底层租约，避免夹具绕过真实 configuration 生命周期。 */
        private TestGenerationLease {
            Objects.requireNonNull(delegate, "delegate");
        }
        /** 原样转发代际身份，不在测试中发明第二套缓存键。 */
        @Override public String generationId() { return delegate.generationId(); }
        /** 只上转为领域投影，确保 adapter 测试不引用 configuration 入站视图。 */
        @Override public ConfigurationGenerationSnapshot snapshot() { return delegate.view(); }
        /** 让真实凭据借用仍受 configuration 租约关闭语义约束。 */
        @Override public String secretFor(String credentialId) { return delegate.secretFor(credentialId); }
        /** 关闭包装时同步释放真实租约，保留 Secret 清理证据。 */
        @Override public void close() { delegate.close(); }
    }
    /** 单次取消令牌同时封闭注册前后两个方向的竞争窗口。 */
    private static final class ManualToken implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();
        /** 发布取消并同步启动所有已注册资源的清理。 */
        private void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                callbacks.forEach(Runnable::run);
            }
        }
        /** 读取单调取消标记，不等待资源清理完成。 */
        @Override public boolean isCancellationRequested() { return cancelled.get(); }
        /** 只返回稳定测试原因，避免夹具携带传输或进程细节。 */
        @Override public Optional<String> reason() {
            return cancelled.get() ? Optional.of("test_cancelled") : Optional.empty();
        }
        /** 注册清理回调；若取消已获胜则立即执行，以封闭迟到注册竞争。 */
        @Override public Registration onCancellation(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            if (cancelled.get()) {
                callback.run();
                return Registration.noop();
            }
            callbacks.add(callback);
            if (cancelled.get() && callbacks.remove(callback)) {
                callback.run();
                return Registration.noop();
            }
            return () -> callbacks.remove(callback);
        }
    }
}
