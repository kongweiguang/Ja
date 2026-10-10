// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import io.github.kongweiguang.ja.conversation.infrastructure.provider.support.ModelAdapterTestSupport;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.noear.solon.Solon;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实配置、SQLite、Resolver 与 TurnService 联合回归，避免局部 fake 掩盖默认权限和会话权限冲突。 */
final class TurnAccessModeCompositionTest {
    @TempDir Path temporary;

    /**
     * 两种相反的默认值不能覆盖会话选择；首个真实在途 Turn 还必须让项目根重绑返回业务忙碌。
     *
     * @author kongweiguang
     */
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
                try {
                    String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
                    return Files.readString(output, Charset.forName(encoding));
                }
                catch (java.io.IOException failure) { return failure.getClass().getSimpleName(); }
            });
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Solon/MyBatis 有进程级注册表，使用独立 JVM 防止其它组合测试的数据源代际污染这条真实准入回归。 */
    public static void main(String[] arguments) throws Exception {
        TurnAccessModeCompositionTest fixture = new TurnAccessModeCompositionTest();
        fixture.temporary = Path.of(arguments[0]);
        fixture.exerciseAdmission();
    }

    /** 在隔离 Runtime 中验证空闲/在途/并发准入关闭边界，并确认默认值不覆盖 Thread 权限。 */
    private void exerciseAdmission() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Map<String, String> previous = new LinkedHashMap<>();
        CountDownLatch firstProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstProvider = new CountDownLatch(1);
        CountDownLatch racingProviderEntered = new CountDownLatch(1);
        CountDownLatch releaseRacingProvider = new CountDownLatch(1);
        AtomicBoolean holdFirstProvider = new AtomicBoolean(true);
        try (ModelAdapterTestSupport.Loopback server = new ModelAdapterTestSupport.Loopback(
                (call, exchange) -> {
                    CountDownLatch entered = holdFirstProvider.compareAndSet(true, false)
                            ? firstProviderEntered : call == 3 ? racingProviderEntered : null;
                    CountDownLatch release = call == 3 ? releaseRacingProvider : releaseFirstProvider;
                    if (entered != null) {
                        entered.countDown();
                        awaitProviderRelease(release);
                    }
                    ModelAdapterTestSupport.status(exchange, 400);
                })) {
            Files.writeString(home.resolve("config.toml"), configuration(AccessMode.APPROVAL_REQUIRED, server.baseUri().toString()));
            for (Map.Entry<String, Path> entry : Map.of("ja.home-dir", home, "ja.data-dir", home.resolve("data"),
                    "ja.run-dir", home.resolve("run"), "ja.log-dir", home.resolve("logs")).entrySet()) {
                previous.put(entry.getKey(), System.getProperty(entry.getKey()));
                System.setProperty(entry.getKey(), Files.createDirectories(entry.getValue()).toString());
            }
            Solon.start(App.class, new String[0], started -> started.enableHttp(false));
            ConfigurationUseCase configuration = Solon.context().getBean(ConfigurationUseCase.class);
            assertEquals(ConfigurationUseCase.LayerStatus.VALID, configuration.read(null).user().status());
            configuration.setCredential("cred_mode", "isolated-test-key", configuration.read(null).credentialVersion());
            WorkspaceUseCase workspaces = Solon.context().getBean(WorkspaceUseCase.class);
            ThreadUseCase threads = Solon.context().getBean(ThreadUseCase.class);
            TurnUseCase turnService = Solon.context().getBean(TurnUseCase.class);
            assertTrue(turnService.awaitQuiescence(Duration.ZERO),
                    "an initialized Runtime without Turn work is immediately quiescent");
            int calls = 0;
            for (AccessMode requested : AccessMode.values()) {
                AccessMode configured = requested == AccessMode.FULL_ACCESS
                        ? AccessMode.APPROVAL_REQUIRED : AccessMode.FULL_ACCESS;
                Files.writeString(home.resolve("config.toml"), configuration(configured, server.baseUri().toString()));
                assertEquals(new ConfigurationUseCase.TextValue(configured.name().toLowerCase(Locale.ROOT)),
                        configuration.read(null).effective().properties().get("default_access_mode"));
                String suffix = requested.name().toLowerCase(Locale.ROOT);
                String turnId = "turn_mode_" + suffix;
                String threadId = "thr_mode_" + suffix;
                boolean verifyBusyProjectRebind = calls == 0;
                io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace;
                if (verifyBusyProjectRebind) {
                    Path projectRoot = Files.createDirectories(temporary.resolve("busy-project-root"));
                    io.github.kongweiguang.ja.workspace.domain.model.Workspace opened = workspaces.openWorkspace(
                            new WorkspaceUseCase.OpenWorkspace(projectRoot, "Busy project"));
                    workspace = workspaces.setWorkspaceTrust(opened.workspaceId(),
                            io.github.kongweiguang.ja.workspace.domain.model.Workspace.Trust.TRUSTED);
                } else {
                    workspace = workspaces.createSessionWorkspace(threadId);
                }
                assertEquals(workspace, workspaces.requireOpenWorkspace(workspace.workspaceId()));
                ThreadPreferences preferences = new ThreadPreferences("provider_mode", "model_mode", null, requested,
                        CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
                ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(
                        threadId, workspace.workspaceId(), "New thread", preferences, Instant.now()));
                TurnRuntimeResolver resolver = Solon.context().getBean(TurnRuntimeResolver.class);
                try (io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease lease =
                        resolver.resolve(new TurnRuntimeRequest(thread.threadId(), turnId, workspace.root(),
                        workspace.workspaceId(), "provider_mode", "model_mode", null, requested,
                        CollaborationMode.DEFAULT, TurnOrigin.USER, Instant.now()))) {
                    assertEquals(requested, lease.accessMode());
                }
                TurnUseCase.Accepted accepted = turnService.start(new TurnStartRequest(
                        thread.threadId(), turnId, workspace.workspaceId(), workspace.root(),
                        new UserContent(List.of(new TextContent("mode admission regression"))),
                        "provider_mode", "model_mode", null, requested, CollaborationMode.DEFAULT,
                        thread.revision(), 0, Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false),
                        ConversationTurnEventSink.turnEventsOnly(
                                event -> CompletableFuture.completedFuture(null)));
                assertTrue(accepted.threadRevision() > thread.revision());
                if (calls == 0) {
                    assertTrue(firstProviderEntered.await(10, TimeUnit.SECONDS),
                            "first provider request should reach the isolated loopback");
                    assertFalse(turnService.awaitQuiescence(Duration.ZERO),
                            "an admitted request remains work while its provider call is pending");
                    if (verifyBusyProjectRebind) {
                        Path nextRoot = Files.createDirectories(temporary.resolve("busy-project-next-root"));
                        io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure busy = assertThrows(
                                io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.class,
                                () -> workspaces.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                                        workspace.workspaceId(), workspace.revision(), null, nextRoot)));
                        assertEquals(io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure.Code.WORKSPACE_BUSY,
                                busy.code());
                        assertTrue(busy.getMessage().contains("运行中"));
                        assertEquals(workspace.root(), workspaces.readWorkspace(workspace.workspaceId())
                                .orElseThrow().root(), "busy rebind must keep the project root bound to active work");

                        var renamed = workspaces.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                                workspace.workspaceId(), workspace.revision(), "Busy project renamed", null));
                        assertEquals(workspace.workspaceId(), renamed.workspaceId());
                        assertEquals(workspace.root(), renamed.root());
                        assertEquals("Busy project renamed", renamed.displayName());
                    }
                    releaseFirstProvider.countDown();
                }
                accepted.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
                assertTrue(turnService.awaitQuiescence(Duration.ZERO),
                        "terminal projection releases the local Turn owner");
                assertEquals(++calls, server.calls());
                assertEquals(requested, threads.readThread(thread.threadId(), null, 20).orElseThrow()
                        .thread().preferences().accessMode());
            }
            assertAdmissionFenceCompetesWithoutLosingWork(server, turnService, workspaces, threads,
                    racingProviderEntered, releaseRacingProvider);
        } finally {
            releaseFirstProvider.countDown();
            releaseRacingProvider.countDown();
            if (Solon.context() != null) {
                Solon.stopBlock(false, 0);
            }
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                }
                else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    /** 两个并发操作只能形成“关闸先行并拒绝准入”或“准入先行且被关闸识别”之一。 */
    private void assertAdmissionFenceCompetesWithoutLosingWork(ModelAdapterTestSupport.Loopback server,
                                                                TurnUseCase turns, WorkspaceUseCase workspaces,
                                                                ThreadUseCase threads,
                                                                CountDownLatch providerEntered,
                                                                CountDownLatch releaseProvider) throws Exception {
        String threadId = "thr_shutdown_race";
        String turnId = "turn_shutdown_race";
        io.github.kongweiguang.ja.workspace.domain.model.Workspace workspace =
                workspaces.createSessionWorkspace(threadId);
        ThreadPreferences preferences = new ThreadPreferences("provider_mode", "model_mode", null,
                AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = threads.createThread(new ThreadSummary.Creation(threadId, workspace.workspaceId(),
                "Shutdown race", preferences, Instant.now()));
        TurnStartRequest request = new TurnStartRequest(threadId, turnId, workspace.workspaceId(), workspace.root(),
                new UserContent(List.of(new TextContent("shutdown admission race"))), "provider_mode", "model_mode",
                null, AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, thread.revision(), 0,
                Instant.now(), Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false);
        ConversationTurnEventSink sink = ConversationTurnEventSink.turnEventsOnly(
                event -> CompletableFuture.completedFuture(null));
        CyclicBarrier ready = new CyclicBarrier(2);
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            Future<TurnUseCase.Accepted> admission = racers.submit(() -> {
                ready.await();
                return turns.start(request, sink);
            });
            Future<Boolean> fence = racers.submit(() -> {
                ready.await();
                return turns.stopAcceptingIfQuiescent();
            });
            boolean stopped = fence.get(10, TimeUnit.SECONDS);
            if (stopped) {
                ExecutionException rejected = assertThrows(ExecutionException.class,
                        () -> admission.get(10, TimeUnit.SECONDS));
                assertInstanceOf(RejectedExecutionException.class, rejected.getCause());
                assertEquals(2, server.calls(), "shutdown fence must prevent a racing provider request");
                return;
            }

            TurnUseCase.Accepted accepted = admission.get(10, TimeUnit.SECONDS);
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS), "admitted work should reach its provider call");
            assertFalse(turns.stopAcceptingIfQuiescent(), "a live owner must leave admission open and report busy");
            releaseProvider.countDown();
            accepted.completion().toCompletableFuture().get(20, TimeUnit.SECONDS);
            assertTrue(turns.stopAcceptingIfQuiescent(), "completed owner can atomically fence later admissions");
        } finally {
            releaseProvider.countDown();
            racers.shutdownNow();
            racers.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /** Provider fixture 只等待短期测试闩锁，超时或中断均显式失败并恢复线程中断标记。 */
    private static void awaitProviderRelease(CountDownLatch release) throws java.io.IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new java.io.IOException("provider release timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("provider fixture interrupted", failure);
        }
    }

    /** 仅设置创建默认值与本地假 Provider，完全隔离用户凭据和真实付费请求。 */
    private static String configuration(AccessMode defaultMode, String endpoint) {
        return """
                schema_version = 2
                config_revision = 1
                default_access_mode = "%s"
                default_provider_id = "provider_mode"
                default_model_id = "model_mode"
                default_reasoning_level = { __ja_null = true }
                mcp_servers = []
                disabled_skills = []
                [subagents]
                is_enabled = true
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
