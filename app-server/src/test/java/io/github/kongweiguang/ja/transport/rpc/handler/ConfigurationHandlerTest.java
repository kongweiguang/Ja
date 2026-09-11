// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.JaErrorCatalog;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationScope;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证配置 RPC 只通过工作区入站端口解析 workspaceId，并保持首版配置约束。 */
final class ConfigurationHandlerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC);

    /** 项目 Merge Patch 必须使用已打开且受信任工作区的根目录，并原样传递 CAS 版本。 */
    @Test
    void projectPatchUsesOpenTrustedWorkspaceAndCasVersion() {
        Workspace workspace = workspace(Workspace.Trust.TRUSTED);
        try (Harness harness = new Harness(workspace)) {
            ObjectNode patch = harness.mapper.createObjectNode();
            patch.set("provider", harness.mapper.createObjectNode().put("model", "gpt-latest"));
            patch.putNull("removedField");
            ObjectNode params = harness.mapper.createObjectNode().put("scope", "project")
                    .put("workspaceId", workspace.workspaceId()).put("expectedVersion", "cfg_1");
            params.set("patch", patch);

            ObjectNode result = harness.handler.handle(
                    new RpcCommand(RpcMethod.CONFIGURATION_PATCH, params)).toCompletableFuture().join();

            assertEquals(workspace.workspaceId(), harness.workspaces.requiredWorkspaceId);
            assertEquals(workspace.root(), harness.configuration.patchRoot);
            assertEquals(ConfigurationScope.PROJECT, harness.configuration.patchScope);
            assertEquals("cfg_1", harness.configuration.expectedVersion);
            ConfigurationUseCase.ObjectValue provider = (ConfigurationUseCase.ObjectValue)
                    harness.configuration.patchDocument.properties().get("provider");
            assertEquals(new ConfigurationUseCase.TextValue("gpt-latest"),
                    provider.properties().get("model"));
            assertEquals(ConfigurationUseCase.NullValue.INSTANCE,
                    harness.configuration.patchDocument.properties().get("removedField"));
            assertEquals(1, harness.workspaces.refreshCount);
            assertExactFields(result, "accepted", "scope", "version");
        }
    }

    /** 读取指定工作区时也必须经过打开状态校验，Wire 只返回 workspaceId 而不返回路径。 */
    @Test
    void readResolvesOpenWorkspaceWithoutExposingPath() {
        Workspace workspace = workspace(Workspace.Trust.UNTRUSTED);
        try (Harness harness = new Harness(workspace)) {
            ObjectNode params = harness.mapper.createObjectNode().put("workspaceId", workspace.workspaceId());

            ObjectNode result = harness.handler.handle(
                    new RpcCommand(RpcMethod.CONFIGURATION_READ, params)).toCompletableFuture().join();

            assertEquals(workspace.workspaceId(), harness.workspaces.requiredWorkspaceId);
            assertEquals(workspace.root(), harness.configuration.readRoot);
            assertEquals(workspace.workspaceId(), result.path("workspaceId").textValue());
            assertFalse(result.has("path"));
            assertFalse(result.has("root"));
            assertFalse(result.has("credentialVersion"));
            assertFalse(result.path("user").has("version"));
            assertFalse(result.path("project").has("version"));
            assertExactFields((ObjectNode) result.path("cas"),
                    "userVersion", "projectVersion", "credentialVersion");
            assertEquals("cfg_1", result.path("cas").path("userVersion").textValue());
            assertEquals("cfg_1", result.path("cas").path("projectVersion").textValue());
            assertEquals("cfg_1", result.path("cas").path("credentialVersion").textValue());
        }
    }

    /** 配置读取必须把领域树中的显式 null 保留到 effective 与 user Wire 文档，不能丢成缺失字段。 */
    @Test
    void readPreservesNullableReasoningFieldsInWireDocuments() {
        try (Harness harness = new Harness(workspace(Workspace.Trust.TRUSTED))) {
            harness.configuration.includeNullableRead = true;

            ObjectNode result = harness.handler.handle(new RpcCommand(
                    RpcMethod.CONFIGURATION_READ, harness.mapper.createObjectNode()))
                    .toCompletableFuture().join();

            assertTrue(result.path("effective").has("default_reasoning_level"));
            assertTrue(result.path("effective").path("default_reasoning_level").isNull());
            assertTrue(result.path("effective").path("providers").get(0).path("models").get(0)
                    .path("default_reasoning_level").isNull());
            assertTrue(result.path("user").path("document").path("default_reasoning_level").isNull());
        }
    }

    /** 未受信任工作区不得进入项目配置 owner，避免绕过工作区信任边界读取并合并项目文档。 */
    @Test
    void rejectsUntrustedProjectMutationBeforeConfigurationOwner() {
        Workspace workspace = workspace(Workspace.Trust.UNTRUSTED);
        try (Harness harness = new Harness(workspace)) {
            ObjectNode params = projectPatch(harness.mapper, workspace.workspaceId(), "cfg_1");

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.CONFIGURATION_PATCH, params)));

            assertEquals(JaErrorCatalog.WORKSPACE_TRUST_REQUIRED.name(), failure.errorCode());
            assertEquals(0, harness.configuration.patchCount);
        }
    }

    /** 用户作用域禁止夹带 workspaceId，项目作用域也禁止恢复 path 等旧 Wire 字段。 */
    @Test
    void rejectsWorkspaceIdentityOutsideStrictScopeShape() {
        Workspace workspace = workspace(Workspace.Trust.TRUSTED);
        try (Harness harness = new Harness(workspace)) {
            ObjectNode user = harness.mapper.createObjectNode().put("scope", "user")
                    .put("workspaceId", workspace.workspaceId()).put("expectedVersion", "cfg_1");
            user.set("patch", harness.mapper.createObjectNode());
            ObjectNode project = projectPatch(harness.mapper, workspace.workspaceId(), "cfg_1");
            project.put("path", workspace.root().toString());

            JaRpcException userFailure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.CONFIGURATION_PATCH, user)));
            JaRpcException projectFailure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.CONFIGURATION_PATCH, project)));

            assertEquals(JaErrorCatalog.INVALID_PARAMS.name(), userFailure.errorCode());
            assertEquals(JaErrorCatalog.INVALID_PARAMS.name(), projectFailure.errorCode());
            assertEquals(0, harness.configuration.patchCount);
        }
    }

    /** 空或缺失的 expectedVersion 不能调用配置 owner，CAS 失败必须在边界处稳定拒绝。 */
    @Test
    void requiresNonBlankCasVersion() {
        Workspace workspace = workspace(Workspace.Trust.TRUSTED);
        try (Harness harness = new Harness(workspace)) {
            ObjectNode blank = projectPatch(harness.mapper, workspace.workspaceId(), " ");
            ObjectNode missing = projectPatch(harness.mapper, workspace.workspaceId(), "cfg_1");
            missing.remove("expectedVersion");

            JaRpcException blankFailure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.CONFIGURATION_PATCH, blank)));
            JaRpcException missingFailure = assertThrows(JaRpcException.class,
                    () -> harness.handler.handle(new RpcCommand(RpcMethod.CONFIGURATION_PATCH, missing)));

            assertEquals(JaErrorCatalog.INVALID_PARAMS.name(), blankFailure.errorCode());
            assertEquals(JaErrorCatalog.INVALID_PARAMS.name(), missingFailure.errorCode());
            assertEquals(0, harness.configuration.patchCount);
        }
    }

    /** 构造统一的项目 Patch Wire 参数，避免测试在字段白名单上产生无关差异。 */
    private static ObjectNode projectPatch(ObjectMapper mapper, String workspaceId, String expectedVersion) {
        ObjectNode params = mapper.createObjectNode().put("scope", "project")
                .put("workspaceId", workspaceId).put("expectedVersion", expectedVersion);
        params.set("patch", mapper.createObjectNode().put("enabled", true));
        return params;
    }

    /** 构造不依赖真实文件系统的稳定工作区领域对象。 */
    private static Workspace workspace(Workspace.Trust trust) {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-configuration-handler")
                .toAbsolutePath().normalize();
        return new Workspace("ws_configuration", root, "Configuration", trust, 1);
    }

    /** 校验响应字段闭集，防止适配器私有字段或路径重新进入 Wire。 */
    private static void assertExactFields(ObjectNode result, String... expectedFields) {
        Set<String> actual = new HashSet<>();
        result.fieldNames().forEachRemaining(actual::add);
        assertEquals(Set.of(expectedFields), actual);
    }

    /** 为直接 handler 测试建立已完成握手的最小 RPC 会话，并确保后台 writer 被关闭。 */
    private static final class Harness implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
        private final RecordingWorkspaces workspaces;
        private final RecordingConfiguration configuration = new RecordingConfiguration();
        private final RpcSession session;
        private final ConfigurationHandler handler;

        /** 只注入当前测试需要的工作区与配置端口，其余端口一旦被调用就显式失败。 */
        private Harness(Workspace workspace) {
            workspaces = new RecordingWorkspaces(workspace);
            RpcServiceBindings services = new RpcServiceBindings(workspaces,
                    unusedPort(io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase.class),
                    unusedPort(ThreadUseCase.class), unusedPort(TurnUseCase.class),
                    (command, events, cancellation) -> { throw new UnsupportedOperationException("context compaction is unavailable"); },
                    unusedPort(ApprovalUseCase.class), unusedPort(CatalogUseCase.class),
                    unusedPort(io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase.class),
                        unusedPort(io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase.class),
                        io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveTasks(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveGoals(),
                    io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.passiveInteractions(),
                        noOpLifecycle());
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-configuration-handler-test")
                    .toAbsolutePath();
            SidecarConfiguration sidecar = new SidecarConfiguration(
                    root.resolve("home"), root.resolve("data"), root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(sidecar, mapper, CLOCK, writer,
                    ignoredMapper -> services,
                    configuration);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            handler = new ConfigurationHandler(session);
        }

        /** 按生产顺序关闭会话和 writer，避免测试遗留后台线程或未刷新通知。 */
        @Override
        public void close() {
            try {
                session.close();
            } finally {
                writer.close();
            }
        }
    }

    /** 记录 handler 对工作区入站端口的调用，同时拒绝所有无关能力。 */
    private static final class RecordingWorkspaces implements WorkspaceUseCase {
        private final Workspace workspace;
        private String requiredWorkspaceId;
        private int refreshCount;

        /** 固定唯一已打开工作区，使身份、根目录和信任断言保持一致。 */
        private RecordingWorkspaces(Workspace workspace) {
            this.workspace = workspace;
        }

        /** 本测试不覆盖工作区注册，调用即表示 handler 越界。 */
        @Override
        public Workspace openWorkspace(OpenWorkspace command) {
            throw unsupported();
        }

        /** 本测试不覆盖通用工作区创建，调用即表示 handler 越界。 */
        @Override
        public Workspace openGeneralWorkspace() {
            throw unsupported();
        }

        /** 本测试不覆盖工作区列表，调用即表示 handler 越界。 */
        @Override
        public CursorPage<Workspace> listWorkspaces(String cursor, int limit) {
            throw unsupported();
        }

        /** 本测试要求 handler 使用打开状态校验，普通读取入口不得被调用。 */
        @Override
        public Optional<Workspace> readWorkspace(String workspaceId) {
            throw unsupported();
        }

        /** 记录 workspaceId 并只返回当前进程已经打开的唯一工作区。 */
        @Override
        public Workspace requireOpenWorkspace(String workspaceId) {
            requiredWorkspaceId = workspaceId;
            if (!workspace.workspaceId().equals(workspaceId)) throw unsupported();
            return workspace;
        }

        /** 本测试不修改工作区信任，配置 handler 只能读取领域信任状态。 */
        @Override
        public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
            throw unsupported();
        }

        /** 本测试不注销工作区，调用即表示配置命令产生了越界副作用。 */
        @Override
        public void unregisterWorkspace(String workspaceId, long expectedRevision) {
            throw unsupported();
        }

        /** 记录成功配置变更后的工作区预热刷新，不执行真实 I/O。 */
        @Override
        public void refreshPreparedWorkspaces() {
            refreshCount++;
        }

        /** 配置 handler 不应按路径反查通用工作区，调用即表示旧路径语义回流。 */
        @Override
        public boolean isGeneralWorkspace(Path root) {
            throw unsupported();
        }
    }

    /** 记录配置 owner 的读取与 Patch 输入，其他写操作一律拒绝。 */
    private static final class RecordingConfiguration extends TestConfigurationPorts {
        private Path readRoot;
        private ConfigurationScope patchScope;
        private Path patchRoot;
        private ConfigurationUseCase.Document patchDocument;
        private String expectedVersion;
        private int patchCount;
        private boolean includeNullableRead;

        /** 返回满足 Wire 闭集的脱敏投影，并记录解析后的内部工作区根目录。 */
        @Override
        public ConfigurationUseCase.ReadResult read(Path workspaceRoot) {
            readRoot = workspaceRoot;
            if (includeNullableRead) {
                ConfigurationUseCase.Document document = nullableDocument();
                ConfigurationUseCase.Layer user = new ConfigurationUseCase.Layer(
                        ConfigurationScope.USER, true, true, "cfg_1",
                        ConfigurationUseCase.LayerStatus.VALID, document);
                return new ConfigurationUseCase.ReadResult(false, user,
                        layer(ConfigurationScope.PROJECT, false), document, java.util.Map.of(),
                        "cfg_1", java.util.List.of());
            }
            return new ConfigurationUseCase.ReadResult(false,
                    layer(ConfigurationScope.USER, true), layer(ConfigurationScope.PROJECT, false),
                    new ConfigurationUseCase.Document(java.util.Map.of()), java.util.Map.of(),
                    "cfg_1", java.util.List.of());
        }

        /** 构造带根级和模型级显式 null 的配置树，验证 Wire 映射不丢失字段存在性。 */
        private static ConfigurationUseCase.Document nullableDocument() {
            ConfigurationUseCase.ObjectValue model = new ConfigurationUseCase.ObjectValue(java.util.Map.of(
                    "default_reasoning_level", ConfigurationUseCase.NullValue.INSTANCE));
            ConfigurationUseCase.ObjectValue provider = new ConfigurationUseCase.ObjectValue(java.util.Map.of(
                    "models", new ConfigurationUseCase.ArrayValue(java.util.List.of(model))));
            return new ConfigurationUseCase.Document(java.util.Map.of(
                    "default_reasoning_level", ConfigurationUseCase.NullValue.INSTANCE,
                    "providers", new ConfigurationUseCase.ArrayValue(java.util.List.of(provider))));
        }

        /** 记录 RFC 7396 对象 Patch、目标层、内部根目录与 CAS 版本，并返回固定首版结果。 */
        @Override
        public ConfigurationUseCase.MutationResult patch(
                ConfigurationScope scope, Path workspaceRoot, ConfigurationUseCase.Document patch,
                String expectedVersion) {
            patchCount++;
            patchScope = scope;
            patchRoot = workspaceRoot;
            patchDocument = patch;
            this.expectedVersion = expectedVersion;
            return new ConfigurationUseCase.MutationResult(ConfigurationScope.PROJECT, "cfg_2");
        }

        /** 本测试不覆盖完整替换，调用即表示命令分派错误。 */
        @Override
        public ConfigurationUseCase.MutationResult replace(
                ConfigurationScope scope, Path workspaceRoot, ConfigurationUseCase.Document document,
                String expectedVersion) {
            throw unsupported();
        }

        /** 本测试不覆盖重置，调用即表示命令分派错误。 */
        @Override
        public ConfigurationUseCase.MutationResult reset(
                ConfigurationScope scope, Path workspaceRoot, String expectedVersion) {
            throw unsupported();
        }

        /** 本测试不接收 Secret，调用即表示普通配置命令越权。 */
        @Override
        public ConfigurationUseCase.CredentialResult setCredential(
                String credentialId, String secret, String expectedVersion) {
            throw unsupported();
        }

        /** 本测试不删除凭据，调用即表示普通配置命令越权。 */
        @Override
        public ConfigurationUseCase.CredentialResult deleteCredential(
                String credentialId, String expectedVersion) {
            throw unsupported();
        }

        /** 构造严格单层投影，避免读取测试依赖真实配置文件。 */
        private static ConfigurationUseCase.Layer layer(ConfigurationScope scope, boolean trusted) {
            return new ConfigurationUseCase.Layer(scope, false, trusted, "cfg_1",
                    ConfigurationUseCase.LayerStatus.MISSING, null);
        }
    }

    /** 创建只允许组合、不允许调用的端口代理，缩小直接 handler 测试夹具。 */
    private static <T> T unusedPort(Class<T> type) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, arguments) -> {
                    throw new UnsupportedOperationException("unused test port: " + method.getName());
                });
        return type.cast(proxy);
    }

    /** 提供不产生副作用的生命周期端口，使会话仍按生产关闭顺序完成。 */
    private static DeadlineCloseable noOpLifecycle() {
        return new DeadlineCloseable() {
            /** 测试没有应用资源，仅消费相同的绝对关闭期限。 */
            @Override
            public void closeAt(long shutdownDeadlineNanos) {
            }

            /** 测试清理无需额外资源释放。 */
            @Override
            public void close() {
            }
        };
    }

    /** 为无关能力生成一致的显式失败，避免测试夹具静默吞掉越界调用。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}
