// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.session.TurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.testsupport.McpStdioFixture;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationGenerationSnapshot;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.configuration.adapter.out.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationLease;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
             GenerationCatalog catalog = new GenerationCatalog(workspace, JSON, McpLimits.DEFAULT)) {
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
                        Instant.now().plus(Duration.ofSeconds(20)));
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

    /** 使用凭据引用写入 Java 所有的 Schema，夹具不得包含 MCP Secret 明文。 */
    private static String generationConfig(Path report, Path classes) {
        return "schema_version = 1\n"
                + "config_revision = 1\n"
                + "default_access_mode = \"approval_required\"\n"
                + "default_provider_id = \"provider_turn_mcp\"\n"
                + "default_model_id = \"model_turn_mcp\"\n"
                + "default_reasoning_level = \"medium\"\n"
                + "skills = []\n"
                + "[[providers]]\n"
                + "provider_id = \"provider_turn_mcp\"\n"
                + "name = \"Turn MCP\"\n"
                + "api = \"openai_responses\"\n"
                + "base_url = \"http://127.0.0.1\"\n"
                + "credential_id = \"cred_model\"\n"
                + "[providers.network_timeouts]\nconnect_timeout_ms = 10000\nrequest_timeout_ms = 120000\n"
                + "[providers.agent_defaults]\n"
                + "[providers.agent_defaults.context]\nauto_compact = true\n"
                + "[providers.agent_defaults.turn_limits]\nmax_model_rounds = 32\nmax_tool_calls = 128\nwall_timeout_ms = 3600000\n"
                + "[[providers.models]]\nmodel_id = \"model_turn_mcp\"\nname = \"Turn MCP Model\"\n"
                + "model = \"fixture\"\nreasoning_level_map = { medium = \"medium\" }\n"
                + "default_reasoning_level = \"medium\"\n"
                + "[providers.models.capabilities]\ncontext_window_tokens = 128000\n"
                + "max_output_tokens = 8192\n"
                + "[[mcp_servers]]\n"
                + "mcp_id = \"mcp_turn_fixture\"\n"
                + "name = \"Turn fixture\"\n"
                + "transport = \"stdio\"\n"
                + "endpoint = \"" + toml(javaExecutable()) + "\"\n"
                + "args = [\"-cp\", \"" + toml(classes.toString()) + "\", \""
                + McpStdioFixture.class.getName() + "\", \""
                + toml(report.toString()) + "\"]\n"
                + "env = { JA_ALLOWED = \"yes\" }\n"
                + "headers = {}\n"
                + "enabled = true\n"
                + "[mcp_servers.auth]\n"
                + "kind = \"env\"\n"
                + "name = \"JA_SECRET\"\n"
                + "credential_id = \"cred_mcp\"\n";
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
            if (Files.isRegularFile(path) && Files.size(path) > 0) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /** 等待精确的子进程观测值，避免断言与传输初始化竞争。 */
    private static boolean awaitObservation(Path path, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (awaitFile(path) && Files.readAllLines(path, StandardCharsets.UTF_8).contains(expected)) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /** 检查操作系统进程表，因为仅凭 Session.close 返回不能证明没有孤儿进程。 */
    private static boolean awaitExit(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).isEmpty() || !ProcessHandle.of(pid).orElseThrow().isAlive()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /**
     * 在测试组合根模拟生产桥接，验证 catalog 只依赖自己拥有的出站租约类型。
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
            if (cancelled.compareAndSet(false, true)) callbacks.forEach(Runnable::run);
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
