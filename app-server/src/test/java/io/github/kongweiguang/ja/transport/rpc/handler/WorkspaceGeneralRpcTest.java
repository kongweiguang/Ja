// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.runtime.RpcServer;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.NioWorkspaceDirectoryAdapter;
import io.github.kongweiguang.ja.workspace.application.WorkspaceService;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 通过真实 JSONL 验证 WorkspaceUseCase 是工作区 RPC 的唯一业务 owner。 */
final class WorkspaceGeneralRpcTest {
    private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 首次创建、重复调用、进程重启和缺失 cwd 的 Thread 必须复用同一通用身份。 */
    @Test
    void generalWorkspaceIsDurableAndCannotAcceptClientIdentity(@TempDir Path temporaryRoot)
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        WorkspaceHistory history = new WorkspaceHistory();
        RunResult first = run(configuration, history, mapper,
                request(mapper, "c:general-first", "workspace/open-general", mapper.createObjectNode()),
                request(mapper, "c:thread-general", "thread/create", mapper.createObjectNode()
                        .putNull("cwd").put("title", "无项目测试")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")),
                request(mapper, "c:general-cwd", "workspace/open-general", mapper.createObjectNode()
                        .put("cwd", temporaryRoot.toString())),
                request(mapper, "c:general-extra", "workspace/open-general", mapper.createObjectNode()
                        .put("workspaceId", "ws_client")));

        ObjectNode workspace = result(first.frames(), "c:general-first");
        assertExactKeys(workspace, "workspaceId", "root", "displayName", "trust", "revision");
        String workspaceId = workspace.path("workspaceId").textValue();
        String root = workspace.path("root").textValue();
        assertTrue(workspaceId.startsWith("ws_"));
        assertNotEquals("ws_general", workspaceId);
        assertEquals(configuration.dataDirectory().resolve("general-workspace").toRealPath().toString(), root);
        assertEquals("无项目", workspace.path("displayName").textValue());
        assertEquals("trusted", workspace.path("trust").textValue());
        assertEquals(workspaceId, result(first.frames(), "c:thread-general")
                .path("workspaceId").textValue());
        assertEquals(0, first.prepareCalls());
        assertEquals("INVALID_PARAMS", errorCode(first.frames(), "c:general-cwd"));
        assertEquals("INVALID_PARAMS", errorCode(first.frames(), "c:general-extra"));

        RunResult restarted = run(configuration, history, mapper,
                request(mapper, "c:general-restarted", "workspace/open-general", mapper.createObjectNode()));
        assertEquals(workspaceId, result(restarted.frames(), "c:general-restarted")
                .path("workspaceId").textValue());
        assertEquals(root, result(restarted.frames(), "c:general-restarted").path("root").textValue());
    }

    /** 项目打开、Thread 创建、信任、统一分页和注销必须通过同一应用 owner 闭环。 */
    @Test
    void projectWorkspaceCommandsUseOneApplicationOwner(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SidecarConfiguration configuration = configuration(temporaryRoot);
        Path project = Files.createDirectory(temporaryRoot.resolve("project"));
        WorkspaceHistory history = new WorkspaceHistory();
        String workspaceId = new WorkspacePolicy().workspaceId(project.toRealPath());
        RunResult openedRun = run(configuration, history, mapper,
                request(mapper, "c:open", "workspace/open", mapper.createObjectNode()
                        .put("cwd", project.toString()).put("displayName", "项目")));
        RunResult threadRun = run(configuration, history, mapper,
                request(mapper, "c:thread", "thread/create", mapper.createObjectNode()
                        .put("cwd", project.toString()).put("title", "项目会话")
                        .put("providerId", "provider_test").put("modelId", "model_test")
                        .put("reasoningLevel", "medium").put("accessMode", "approval_required")));
        RunResult trustRun = run(configuration, history, mapper,
                workspaces -> workspaces.openWorkspace(
                        new WorkspaceUseCase.OpenWorkspace(project, null)),
                request(mapper, "c:trust", "workspace/set-trust", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("trust", "trusted")));
        RunResult listRun = run(configuration, history, mapper,
                request(mapper, "c:list", "workspace/list", mapper.createObjectNode().put("limit", 20)));
        RunResult unregisterRun = run(configuration, history, mapper,
                workspaces -> workspaces.openWorkspace(
                        new WorkspaceUseCase.OpenWorkspace(project, null)),
                request(mapper, "c:unregister", "workspace/unregister", mapper.createObjectNode()
                        .put("workspaceId", workspaceId).put("expectedRevision", 1)));

        ObjectNode opened = result(openedRun.frames(), "c:open");
        assertEquals(opened.path("workspaceId").textValue(),
                result(threadRun.frames(), "c:thread").path("workspaceId").textValue());
        assertTrue(result(trustRun.frames(), "c:trust").path("accepted").booleanValue());
        ObjectNode page = result(listRun.frames(), "c:list");
        assertExactKeys(page, "items", "nextCursor");
        assertEquals("trusted", page.path("items").get(0).path("trust").textValue());
        assertTrue(result(unregisterRun.frames(), "c:unregister").path("accepted").booleanValue());
        assertEquals(1, openedRun.prepareCalls());
        assertEquals(1, threadRun.prepareCalls());
        assertEquals(2, trustRun.prepareCalls());
        assertEquals(0, listRun.prepareCalls());
        assertEquals(1, unregisterRun.prepareCalls());
    }

    /** 仓储返回不同物理根时必须在 transport 绑定前映射为脱敏约束错误。 */
    @Test
    void generalWorkspaceRejectsPersistedRootMismatch(@TempDir Path temporaryRoot) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path wrongRoot = Files.createDirectory(temporaryRoot.resolve("wrong-root"));
        RunResult run = run(configuration(temporaryRoot), new WorkspaceHistory(wrongRoot), mapper,
                request(mapper, "c:general-root", "workspace/open-general", mapper.createObjectNode()));

        assertEquals("WORKSPACE_CONFINEMENT", errorCode(run.frames(), "c:general-root"));
    }

    /** 构造不启动 Solon 的生产形状目录，确保通用工作区使用真实 data 边界。 */
    private static SidecarConfiguration configuration(Path temporaryRoot) throws Exception {
        Path home = Files.createDirectory(temporaryRoot.resolve("home"));
        Path data = Files.createDirectory(temporaryRoot.resolve("data"));
        Path run = Files.createDirectory(temporaryRoot.resolve("run"));
        Path logs = Files.createDirectory(temporaryRoot.resolve("logs"));
        return new SidecarConfiguration(home, data, run, logs);
    }

    /** 对一个隔离 sidecar generation 执行真实 JSONL 请求，并保留预热次数。 */
    private static RunResult run(
            SidecarConfiguration configuration,
            WorkspaceHistory history,
            ObjectMapper mapper,
            ObjectNode... requests) throws Exception {
        return run(configuration, history, mapper, ignored -> { }, requests);
    }

    /** 允许测试在启动前通过正式入站端口绑定物理目录，避免依赖异步请求执行顺序。 */
    private static RunResult run(
            SidecarConfiguration configuration,
            WorkspaceHistory history,
            ObjectMapper mapper,
            Consumer<WorkspaceUseCase> beforeRun,
            ObjectNode... requests) throws Exception {
        StringBuilder input = new StringBuilder();
        input.append(mapper.writeValueAsString(initialize(mapper))).append('\n');
        input.append("{\"jsonrpc\":\"2.0\",\"method\":\"runtime/initialized\","
                + "\"params\":{\"readyToken\":\"0123456789abcdef0123456789abcdef\"}}\n");
        for (ObjectNode request : requests) {
            input.append(mapper.writeValueAsString(request)).append('\n');
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger prepareCalls = new AtomicInteger();
        WorkspaceUseCase workspaces = new WorkspaceService(
                history,
                new NioWorkspaceDirectoryAdapter(configuration.dataDirectory()),
                ignored -> prepareCalls.incrementAndGet(),
                (ignored, trust) -> { },
                new WorkspacePolicy(),
                CLOCK);
        beforeRun.accept(workspaces);
        RpcServer server = server(
                new ByteArrayInputStream(input.toString().getBytes(StandardCharsets.UTF_8)),
                output,
                configuration,
                CLOCK,
                ignored -> RpcTestBindings.create(
                        workspaces, history, null, null, null, () -> closed.set(true)),
                TestConfigurationPorts.unavailable());
        assertEquals(0, server.run());
        List<ObjectNode> frames = new ArrayList<>();
        for (String line : output.toString(StandardCharsets.UTF_8).lines().toList()) {
            if (!line.isBlank()) {
                frames.add((ObjectNode) mapper.readTree(line));
            }
        }
        assertTrue(closed.get());
        return new RunResult(frames, prepareCalls.get());
    }

    /** 构造严格 v2 initialize 请求，复用生产 capability 与 limit owner。 */
    private static ObjectNode initialize(ObjectMapper mapper) {
        ObjectNode params = mapper.createObjectNode().put("protocolMajor", 2).put("protocolMinor", 0)
                .put("clientVersion", "2.0.0");
        params.set("capabilities", HandshakeHandler.capabilities(mapper));
        params.set("limits", HandshakeHandler.limits(mapper));
        return request(mapper, "c:init", "runtime/initialize", params);
    }

    /** 构造带明确 object params 边界的请求。 */
    private static ObjectNode request(ObjectMapper mapper, String id, String method, ObjectNode params) {
        return mapper.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method)
                .set("params", params);
    }

    /** 按 id 查找响应并忽略 sidecar 生命周期通知。 */
    private static ObjectNode response(List<ObjectNode> frames, String id) {
        return frames.stream().filter(frame -> id.equals(frame.path("id").textValue()))
                .findFirst().orElseThrow(() -> new AssertionError("response is missing: " + id));
    }

    /** 解包成功 JSON-RPC envelope，供测试断言公开结果。 */
    private static ObjectNode result(List<ObjectNode> frames, String id) {
        return (ObjectNode) response(frames, id).path("result");
    }

    /** 严格比较公开对象字段，防止旧 DTO 或持久化字段重新出现。 */
    private static void assertExactKeys(ObjectNode result, String... keys) {
        Set<String> expected = Set.of(keys);
        Set<String> actual = new java.util.HashSet<>();
        result.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }

    /** 只提取稳定机器错误码，不依赖脱敏消息文本。 */
    private static String errorCode(List<ObjectNode> frames, String id) {
        return response(frames, id).path("error").path("data").path("errorCode").textValue();
    }

    /** 保存一轮真实 RPC 输出和跨域预热观测。 */
    private record RunResult(List<ObjectNode> frames, int prepareCalls) {
        /** 复制帧集合，避免测试在服务关闭后修改观测结果。 */
        private RunResult {
            frames = List.copyOf(frames);
        }
    }

    /** 内存仓储模拟 SQLite 工作区事实，并同时承载本测试的 Thread 历史。 */
    private static final class WorkspaceHistory implements WorkspaceRepository, ThreadUseCase {
        private final Map<String, Workspace> byRoot = new LinkedHashMap<>();
        private final Map<String, Workspace> byId = new LinkedHashMap<>();
        private final Map<String, ThreadSummary> threads = new LinkedHashMap<>();
        private final Path forcedRoot;

        /** 创建正常根目录仓储。 */
        private WorkspaceHistory() {
            this(null);
        }

        /** 注入错误持久化根，仅用于验证 fail-closed identity check。 */
        private WorkspaceHistory(Path forcedRoot) {
            this.forcedRoot = forcedRoot;
        }

        /** 幂等注册并返回 revision 为零的权威事实。 */
        @Override
        public synchronized Workspace register(Workspace.Registration registration) {
            Workspace existing = byRoot.get(registration.root().toString());
            if (existing != null) {
                return existing;
            }
            Path persistedRoot = forcedRoot == null ? registration.root() : forcedRoot;
            Workspace value = new Workspace(registration.workspaceId(), persistedRoot,
                    registration.displayName(), registration.trust(), 0);
            byRoot.put(persistedRoot.toString(), value);
            byId.put(value.workspaceId(), value);
            return value;
        }

        /** 返回当前内存顺序的统一工作区页。 */
        @Override
        public synchronized CursorPage<Workspace> list(String cursor, int limit) {
            return new CursorPage<>(List.copyOf(byRoot.values()), null);
        }

        /** 按身份读取权威事实。 */
        @Override
        public synchronized Optional<Workspace> findById(String workspaceId) {
            return Optional.ofNullable(byId.get(workspaceId));
        }

        /** 按规范根读取权威事实。 */
        @Override
        public synchronized Optional<Workspace> findByRoot(Path root) {
            return Optional.ofNullable(byRoot.get(root.toAbsolutePath().normalize().toString()));
        }

        /** 更新信任并推进 revision。 */
        @Override
        public synchronized Workspace updateTrust(String workspaceId, Workspace.Trust trust) {
            Workspace current = byId.get(workspaceId);
            Workspace updated = new Workspace(current.workspaceId(), current.root(),
                    current.displayName(), trust, current.revision() + 1);
            byId.put(workspaceId, updated);
            byRoot.put(current.root().toString(), updated);
            return updated;
        }

        /** revision 匹配时只删除元数据，不删除测试目录。 */
        @Override
        public synchronized void unregister(String workspaceId, long expectedRevision) {
            Workspace current = byId.get(workspaceId);
            if (current == null || current.revision() != expectedRevision) {
                throw new IllegalStateException("revision conflict");
            }
            byId.remove(workspaceId);
            byRoot.remove(current.root().toString());
        }

        /** 创建 Thread 前确认 workspace 已由同一仓储注册。 */
        @Override
        public synchronized ThreadSummary createThread(ThreadSummary.Creation request) {
            if (!byId.containsKey(request.workspaceId())) {
                throw new IllegalStateException("workspace is missing");
            }
            ThreadSummary value = new ThreadSummary(
                    request.threadId(), request.workspaceId(), request.title(), request.preferences(),
                    ThreadSummary.Status.ACTIVE, 0, request.occurredAt(), request.occurredAt());
            threads.put(value.threadId(), value);
            return value;
        }

        /** 返回当前内存顺序的 Thread 页。 */
        @Override
        public synchronized CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) {
            return new CursorPage<>(List.copyOf(threads.values()), null);
        }

        /** 本用例搜索路径未启用，避免把列表夹具误当搜索语义。 */
        @Override
        public CursorPage<ThreadSummary> searchThreads(
                String workspaceId, String query, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不构造 Thread 快照。 */
        @Override
        public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            return Optional.empty();
        }

        /** 本测试未声明重命名能力。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
        /** 本测试未声明下一轮偏好修改能力。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw new UnsupportedOperationException(); }
        /** 本测试未声明自动标题写入能力。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }

        /** 本测试未声明归档能力。 */
        @Override
        public void archiveThread(String threadId, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试未声明删除能力。 */
        @Override
        public void deleteThread(String threadId, long expectedThreadRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试未声明 Turn 查找能力。 */
        @Override
        public Optional<TurnSummary> findTurn(String turnId) {
            return Optional.empty();
        }
    }
}
