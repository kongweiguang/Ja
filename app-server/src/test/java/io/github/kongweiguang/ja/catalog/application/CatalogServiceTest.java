// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.out.CatalogQueryPort;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.domain.model.ModelMessage;
import io.github.kongweiguang.ja.conversation.domain.model.ModelRole;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证 catalog application 独占配置租约，入站调用方不参与跨域生命周期。 */
final class CatalogServiceTest {
    private static final String MCP_ID = "mcp_fixture";

    /**
     * 覆盖同步分页和异步探测，证明每次用例都取得独立租约且只在下游完成后关闭。
     */
    @Test
    void ownsAndClosesEveryConfigurationGenerationLease() {
        RecordingGenerationPort generations = new RecordingGenerationPort();
        RecordingQueryPort queries = new RecordingQueryPort();
        CatalogService service = new CatalogService(
                queries, generations, unsupportedModel(), unsupportedWorkspaces());

        service.listSkills(null, null, 10);
        service.listMcp(null, 10);
        service.testMcp(MCP_ID).toCompletableFuture().join();
        service.readMcpTools(MCP_ID, null, 10);

        assertEquals(4, generations.leases.size());
        assertTrue(generations.leases.stream().allMatch(RecordingLease::closed));
        assertEquals(4, queries.calls.get());
    }

    /** 用任意正文证明可对话，不要求 OK；固定 hi 且无系统提示，租约覆盖完整异步生命周期。 */
    @Test
    void modelTestUsesBoundedRequestAndClosesLeaseAfterCompletion() {
        ModelGenerationPort generations = new ModelGenerationPort("secret");
        CompletableFuture<ModelPort.ModelOutcome> providerResult = new CompletableFuture<>();
        AtomicReference<ModelPort.ModelRequest> captured = new AtomicReference<>();
        ModelPort modelPort = (request, sink, cancellation) -> {
            captured.set(request);
            sink.onEvent(new ModelPort.TextDelta("discarded response body"));
            return providerResult;
        };
        CatalogService service = new CatalogService(
                new RecordingQueryPort(), generations, modelPort, unsupportedWorkspaces());

        CompletionStage<CatalogService.ModelTestResult> result = service.testModel(
                "provider_fixture", "model_fixture", CancellationToken.none());
        assertFalse(generations.lease.closed());
        ModelPort.ModelRequest request = captured.get();
        assertEquals(1, request.messages().size());
        assertEquals("", request.prompt().systemPrompt());
        assertEquals(List.of(new ModelMessage(ModelRole.USER, List.of(new TextContent("hi")))),
                request.messages());
        assertTrue(request.tools().isEmpty());
        assertNull(request.continuation());
        assertEquals(Set.of(ModelPort.InputModality.TEXT), request.configuration().inputModalities());
        assertEquals(16, request.configuration().generation().maxOutputTokens());
        assertEquals(Duration.ofSeconds(30), request.configuration().requestTimeout());

        providerResult.complete(new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, null));
        assertEquals("gpt-fixture", result.toCompletableFuture().join().responseModel());
        assertTrue(generations.lease.closed());
    }

    /** Catalog 模型探测按三种显式协议路由，收到正文后通过，并读取供应商自己的凭据引用。 */
    @Test
    void deepSeekModelTestsKeepApiAndCredentialIdentity() {
        Map<ConfigurationGenerationSnapshot.Api, ModelPort.Api> routes = Map.of(
                ConfigurationGenerationSnapshot.Api.ANTHROPIC_MESSAGES, ModelPort.Api.ANTHROPIC_MESSAGES,
                ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES, ModelPort.Api.OPENAI_RESPONSES,
                ConfigurationGenerationSnapshot.Api.OPENAI_CHAT_COMPLETIONS,
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS);

        routes.forEach((configuredApi, expectedApi) -> {
            String suffix = configuredApi.name().toLowerCase(java.util.Locale.ROOT);
            String credentialId = "cred_" + suffix;
            String secret = "secret-" + suffix;
            ModelGenerationPort generations = new ModelGenerationPort(
                    configuredApi, credentialId, secret);
            AtomicReference<ModelPort.ModelRequest> captured = new AtomicReference<>();
            ModelPort modelPort = (request, sink, cancellation) -> {
                captured.set(request);
                sink.onEvent(new ModelPort.TextDelta("Hello!"));
                return CompletableFuture.completedFuture(
                        new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, null));
            };
            CatalogService service = new CatalogService(
                    new RecordingQueryPort(), generations, modelPort, unsupportedWorkspaces());

            service.testModel("provider_fixture", "model_fixture", CancellationToken.none())
                    .toCompletableFuture().join();

            ModelPort.ModelConfiguration configuration = captured.get().configuration();
            assertEquals(expectedApi, configuration.api());
            assertEquals(secret, configuration.apiKey());
            assertTrue(generations.lease.closed());
        });
    }

    /** 空流、空白正文和仅思考均不能证明可对话，失败也必须释放租约。 */
    @Test
    void rejectsResponsesWithoutReplyText() {
        List<List<ModelPort.ModelEvent>> responses = List.of(
                List.of(), List.of(new ModelPort.TextDelta(" \n\t")),
                List.of(new ModelPort.ReasoningSummaryDelta("thinking")));
        for (List<ModelPort.ModelEvent> events : responses) {
            ModelGenerationPort generations = new ModelGenerationPort("secret");
            ModelPort modelPort = (request, sink, cancellation) -> {
                events.forEach(sink::onEvent);
                return CompletableFuture.completedFuture(
                        new ModelPort.ModelOutcome(ModelPort.FinishReason.STOP, null, null));
            };
            CatalogService service = new CatalogService(
                    new RecordingQueryPort(), generations, modelPort, unsupportedWorkspaces());
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> service.testModel("provider_fixture", "model_fixture", CancellationToken.none())
                            .toCompletableFuture().join());
            assertEquals("model returned no reply text", failure.getCause().getMessage());
            assertTrue(generations.lease.closed());
        }
    }

    /** 本用例只验证查询租约；任何意外模型探测都必须显式失败而不是访问外部 Provider。 */
    private static ModelPort unsupportedModel() {
        return (request, sink, cancellation) ->
                CompletableFuture.failedFuture(new AssertionError("unexpected model test"));
    }

    /** 仅通用 catalog 测试不应解析工作区，任何意外跨域调用都立即失败。 */
    private static WorkspaceUseCase unsupportedWorkspaces() {
        return (WorkspaceUseCase) Proxy.newProxyInstance(CatalogServiceTest.class.getClassLoader(),
                new Class<?>[]{WorkspaceUseCase.class}, (proxy, method, arguments) -> {
                    throw new AssertionError("unexpected workspace call: " + method.getName());
                });
    }

    /**
     * workspaceId 只能通过已打开 Workspace 能力解析，且同一规范根必须同时进入配置租约和发现端口。
     */
    @Test
    void resolvesOpenProjectWorkspaceBeforeSkillDiscovery() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-catalog-project")
                .toAbsolutePath().normalize();
        RecordingGenerationPort generations = new RecordingGenerationPort(root);
        RecordingQueryPort queries = new RecordingQueryPort();
        Workspace workspace = new Workspace("ws_fixture", root, "Fixture", Workspace.Trust.TRUSTED, 0);
        WorkspaceUseCase workspaces = (WorkspaceUseCase) Proxy.newProxyInstance(
                CatalogServiceTest.class.getClassLoader(), new Class<?>[]{WorkspaceUseCase.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "requireOpenWorkspace" -> workspace;
                    case "isGeneralWorkspace" -> false;
                    default -> throw new AssertionError("unexpected workspace call: " + method.getName());
                });
        CatalogService service = new CatalogService(
                queries, generations, unsupportedModel(), workspaces);

        service.listSkills("ws_fixture", null, 10);

        assertEquals(root, queries.skillWorkspace.get());
        assertTrue(queries.skillWorkspaceTrusted);
        assertTrue(generations.leases.getFirst().closed());
    }

    /**
     * 用动态代理提供最窄领域投影；任何未计划的配置读取都会立即暴露为测试失败。
     */
    private static ConfigurationGenerationSnapshot snapshot() {
        return (ConfigurationGenerationSnapshot) Proxy.newProxyInstance(
                CatalogServiceTest.class.getClassLoader(),
                new Class<?>[]{ConfigurationGenerationSnapshot.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("requireMcp")) {
                        assertEquals(MCP_ID, arguments[0]);
                        return mcpServer();
                    }
                    if (method.getName().equals("generationId")) return "generation_fixture";
                    throw new AssertionError("unexpected snapshot call: " + method.getName());
                });
    }

    /** 构造无凭据 MCP 描述，避免生命周期测试引入 Secret 或真实传输副作用。 */
    private static ConfigurationGenerationSnapshot.McpServer mcpServer() {
        return new ConfigurationGenerationSnapshot.McpServer(
                MCP_ID, "Fixture", ConfigurationGenerationSnapshot.Transport.STDIO,
                "fixture", List.of(), java.util.Map.of(), java.util.Map.of(),
                new ConfigurationGenerationSnapshot.Auth(
                        ConfigurationGenerationSnapshot.AuthKind.NONE, null, null), true);
    }

    /** 记录每次 application 取得的通用配置租约。 */
    private static final class RecordingGenerationPort implements ConfigurationGenerationPort {
        private final List<RecordingLease> leases = new CopyOnWriteArrayList<>();
        private final Path expectedWorkspaceRoot;

        /** 默认夹具只允许通用配置租约。 */
        private RecordingGenerationPort() {
            this(null);
        }

        /** 项目夹具固定唯一规范根，防止测试误把 workspaceId 当路径传递。 */
        private RecordingGenerationPort(Path expectedWorkspaceRoot) {
            this.expectedWorkspaceRoot = expectedWorkspaceRoot;
        }

        /** 强制 catalog 查询使用预期工作区，并为每个命令返回独立租约。 */
        @Override
        public Lease acquire(Path workspaceRoot) {
            assertEquals(expectedWorkspaceRoot, workspaceRoot);
            RecordingLease lease = new RecordingLease();
            leases.add(lease);
            return lease;
        }
    }

    /** 为模型探测提供最窄 Provider/Model 快照，并记录异步租约生命周期。 */
    private static final class ModelGenerationPort implements ConfigurationGenerationPort {
        private final RecordingLease lease;

        /** 冻结测试 Secret 只供单次租约读取，避免 fake 绕过真实凭据生命周期。 */
        private ModelGenerationPort(String secret) {
            this(ConfigurationGenerationSnapshot.Api.OPENAI_RESPONSES, "cred_fixture", secret);
        }

        /** 冻结指定 API 与独立凭据，覆盖自定义供应商的多协议模型探测路由。 */
        private ModelGenerationPort(
                ConfigurationGenerationSnapshot.Api api,
                String expectedCredentialId,
                String secret) {
            lease = new RecordingLease() {
                /** 关闭前返回配置代际，证明模型请求不会在租约外构造。 */
                @Override
                public String generationId() {
                    requireOpen();
                    return "cfg_fixture";
                }

                /** 关闭前返回冻结 Provider/Model 目录，拒绝异步完成后的继续访问。 */
                @Override
                public ConfigurationGenerationSnapshot snapshot() {
                    requireOpen();
                    return modelSnapshot(api, expectedCredentialId);
                }

                /** 只允许解析测试 Provider 声明的凭据引用，不接受任意 Selector。 */
                @Override
                public String secretFor(String credentialId) {
                    requireOpen();
                    assertEquals(expectedCredentialId, credentialId);
                    return secret;
                }
            };
        }

        /** 模型探测只能取得通用工作区的单个测试租约。 */
        @Override public Lease acquire(Path workspaceRoot) {
            assertNull(workspaceRoot);
            return lease;
        }
    }

    /** 构造不含附件能力的模型目录，防止探测请求继承正常 Turn 能力。 */
    private static ConfigurationGenerationSnapshot modelSnapshot(
            ConfigurationGenerationSnapshot.Api api,
            String credentialId) {
        ConfigurationGenerationSnapshot.Model model = new ConfigurationGenerationSnapshot.Model(
                "model_fixture", "Fixture", "gpt-fixture",
                new ConfigurationGenerationSnapshot.Capabilities(
                        128_000, 8_192, List.of(ConfigurationGenerationSnapshot.InputModality.TEXT)),
                Map.of(), null);
        ConfigurationGenerationSnapshot.Provider provider = new ConfigurationGenerationSnapshot.Provider(
                "provider_fixture", "Fixture", api,
                URI.create("https://example.com/v1"), credentialId,
                new ConfigurationGenerationSnapshot.NetworkTimeouts(
                        Duration.ofSeconds(5), Duration.ofMinutes(2)),
                new ConfigurationGenerationSnapshot.AgentDefaults(
                        new ConfigurationGenerationSnapshot.Context(true),
                        new ConfigurationGenerationSnapshot.TurnLimits(8, 32, Duration.ofMinutes(5))),
                List.of(model));
        return (ConfigurationGenerationSnapshot) Proxy.newProxyInstance(
                CatalogServiceTest.class.getClassLoader(),
                new Class<?>[]{ConfigurationGenerationSnapshot.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "requireProvider" -> provider;
                    case "requireModel" -> model;
                    case "generationId" -> "cfg_fixture";
                    default -> throw new AssertionError("unexpected model snapshot call: " + method.getName());
                });
    }

    /** 模拟一次不可变配置代际，并显式记录关闭状态。 */
    private static class RecordingLease implements ConfigurationGenerationPort.Lease {
        private boolean closed;

        /** 关闭前返回稳定代际标识，关闭后拒绝任何继续读取。 */
        @Override public String generationId() {
            requireOpen();
            return "generation_fixture";
        }

        /** 关闭前返回只支持 requireMcp 的最窄领域投影。 */
        @Override public ConfigurationGenerationSnapshot snapshot() {
            requireOpen();
            return CatalogServiceTest.snapshot();
        }

        /** 本测试不允许解析 Secret，意外调用直接失败。 */
        @Override public String secretFor(String credentialId) {
            throw new AssertionError("unexpected secret lookup");
        }

        /** 记录幂等关闭，使同步和异步路径可以共享断言。 */
        @Override public void close() { closed = true; }

        /** 返回当前关闭状态，供最终所有权断言使用。 */
        private boolean closed() { return closed; }

        /** 在下游查询阶段拒绝提前关闭，锁定异步完成屏障。 */
        protected final void requireOpen() {
            if (closed) throw new AssertionError("generation lease closed too early");
        }
    }

    /** 记录 application 是否在活动租约内委派了全部 catalog 查询。 */
    private static final class RecordingQueryPort implements CatalogQueryPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Path> skillWorkspace = new AtomicReference<>();
        private boolean skillWorkspaceTrusted;

        /** 验证 Skill 查询发生在租约关闭前。 */
        @Override
        public CursorPage<SkillDescriptor> listSkills(
                ConfigurationGenerationPort.Lease generation, Path workspaceRoot, boolean workspaceTrusted,
                String cursor, int limit) {
            admit(generation);
            skillWorkspace.set(workspaceRoot);
            skillWorkspaceTrusted = workspaceTrusted;
            return new CursorPage<>(List.of(), null);
        }

        /** 验证 MCP 列表查询发生在租约关闭前。 */
        @Override
        public CursorPage<McpServerDescriptor> listMcp(
                ConfigurationGenerationPort.Lease generation, String cursor, int limit) {
            admit(generation);
            return new CursorPage<>(List.of(), null);
        }

        /** 验证异步探测创建阶段仍持有租约，并返回已完成夹具结果。 */
        @Override
        public CompletionStage<McpServerDescriptor> testMcp(
                ConfigurationGenerationPort.Lease generation, String mcpId) {
            admit(generation);
            return CompletableFuture.completedFuture(new McpServerDescriptor(
                    MCP_ID, "Fixture", "stdio", "available", 0));
        }

        /** 验证 Tool Schema 查询发生在租约关闭前。 */
        @Override
        public CursorPage<McpToolDescriptor> readMcpTools(
                ConfigurationGenerationPort.Lease generation, String mcpId,
                String cursor, int limit) {
            admit(generation);
            return new CursorPage<>(List.of(), null);
        }

        /** 读取代际身份以证明委派时租约尚未关闭，并累计调用。 */
        private void admit(ConfigurationGenerationPort.Lease generation) {
            assertEquals("generation_fixture", generation.generationId());
            calls.incrementAndGet();
        }
    }
}
