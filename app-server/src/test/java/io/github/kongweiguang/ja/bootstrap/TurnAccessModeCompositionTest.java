// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实配置、SQLite、Resolver 与 TurnService 联合回归，避免局部 fake 掩盖默认权限和会话权限冲突。 */
final class TurnAccessModeCompositionTest {
    @TempDir Path temporary;

    /** 两种相反的默认值均不能覆盖已保存的会话选择；必须真正通过准入并触达隔离 HTTP。 */
    @Test
    void admitsExplicitThreadModeWhenGlobalDefaultDiffers() throws Exception {
        Path output = temporary.resolve("composition-output.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-Dja.log-dir=" + temporary.resolve("logs"),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TurnAccessModeCompositionTest.class.getName(), temporary.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "isolated composition did not stop");
            assertEquals(0, child.exitValue(), () -> {
                try { return Files.readString(output); }
                catch (java.io.IOException failure) { return failure.getClass().getSimpleName(); }
            });
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }

    /** Solon/MyBatis 有进程级注册表，使用独立 JVM 防止其它组合测试的数据源代际污染这条真实准入回归。 */
    public static void main(String[] arguments) throws Exception {
        TurnAccessModeCompositionTest fixture = new TurnAccessModeCompositionTest();
        fixture.temporary = Path.of(arguments[0]);
        fixture.exerciseAdmission();
    }

    /** 在同一隔离 Runtime 中切换创建默认值，两个已有 Thread 的显式模式仍必须分别原样准入。 */
    private void exerciseAdmission() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> ModelAdapterTestSupport.status(exchange, 400))) {
            Files.writeString(home.resolve("config.toml"), configuration(AccessMode.APPROVAL_REQUIRED, server.baseUri().toString()));
            for (var entry : Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                    "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs")).entrySet()) {
                previous.put(entry.getKey(), System.getProperty(entry.getKey()));
                System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
            }
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));
            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, configuration.read(null).user().status());
            configuration.setCredential("cred_mode", "isolated-test-key", configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            var workspace = workspaces.openGeneralWorkspace();
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            int calls = 0;
            for (AccessMode requested : AccessMode.values()) {
                AccessMode configured = requested == AccessMode.FULL_ACCESS
                        ? AccessMode.APPROVAL_REQUIRED : AccessMode.FULL_ACCESS;
                Files.writeString(home.resolve("config.toml"), configuration(configured, server.baseUri().toString()));
                assertEquals(new ConfigurationUseCase.TextValue(configured.name().toLowerCase(Locale.ROOT)),
                        configuration.read(null).effective().properties().get("default_access_mode"));
                String suffix = requested.name().toLowerCase(Locale.ROOT);
                String turnId = "turn_mode_" + suffix;
                var preferences = new ThreadPreferences("provider_mode", "model_mode", null, requested,
                        CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
                var thread = threads.createThread(new ThreadSummary.Creation(
                        "thr_mode_" + suffix, workspace.workspaceId(), "New thread", preferences, Instant.now()));
                TurnRuntimeResolver resolver = Solon.context().getBean(TurnRuntimeResolver.class);
                try (var lease = resolver.resolve(new TurnRuntimeRequest(thread.threadId(), turnId, workspace.root(),
                        workspace.workspaceId(), "provider_mode", "model_mode", null, requested,
                        CollaborationMode.DEFAULT, TurnOrigin.USER, Duration.ofSeconds(20), Instant.now()))) {
                    assertEquals(requested, lease.accessMode());
                }
                var accepted = Solon.context().getBean(TurnUseCase.class).start(new TurnStartRequest(
                        thread.threadId(), turnId, workspace.workspaceId(), workspace.root(),
                        new UserContent(List.of(new TextContent("mode admission regression"))),
                        "provider_mode", "model_mode", null, requested, CollaborationMode.DEFAULT,
                        Duration.ofSeconds(20), thread.revision(), 0, Instant.now()),
                        event -> CompletableFuture.completedFuture(null));
                assertTrue(accepted.threadRevision() > thread.revision());
                accepted.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertEquals(++calls, server.calls());
                assertEquals(requested, threads.readThread(thread.threadId(), null, 20).orElseThrow()
                        .thread().preferences().accessMode());
            }
        } finally {
            if (Solon.context() != null) Solon.stopBlock(false, 0);
            previous.forEach((key, value) -> {
                if (value == null) System.clearProperty(key);
                else System.setProperty(key, value);
            });
        }
    }

    /** 仅设置创建默认值与本地假 Provider，完全隔离用户凭据和真实付费请求。 */
    private static String configuration(AccessMode defaultMode, String endpoint) {
        return """
                schema_version = 1
                config_revision = 1
                default_access_mode = "%s"
                default_provider_id = "provider_mode"
                default_model_id = "model_mode"
                default_reasoning_level = { __ja_null = true }
                mcp_servers = []
                skills = []
                [subagents]
                enabled = true
                provider_id = { __ja_null = true }
                model_id = { __ja_null = true }
                reasoning_level = { __ja_null = true }
                [[providers]]
                provider_id = "provider_mode"
                name = "Mode fixture"
                api = "openai_responses"
                base_url = "%s"
                credential_id = "cred_mode"
                [providers.network_timeouts]
                connect_timeout_ms = 1000
                request_timeout_ms = 10000
                [providers.agent_defaults.context]
                auto_compact = true
                [providers.agent_defaults.turn_limits]
                max_model_rounds = 4
                max_tool_calls = 4
                wall_timeout_ms = 20000
                [[providers.models]]
                model_id = "model_mode"
                name = "Mode fixture"
                model = "test-model"
                reasoning_level_map = {}
                default_reasoning_level = { __ja_null = true }
                [providers.models.capabilities]
                context_window_tokens = 128000
                max_output_tokens = 8192
                """.formatted(defaultMode.name().toLowerCase(Locale.ROOT), endpoint);
    }
}
