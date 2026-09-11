// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.adapter.in.tools;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import io.github.kongweiguang.ja.task.port.in.TaskEventSink;
import io.github.kongweiguang.ja.task.port.in.TaskUseCase;
import io.github.kongweiguang.ja.task.port.out.TaskRepositoryException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证原生 Task Tools 的冻结身份、权限上限和 QueueOnly 应用端口映射。 */
final class TaskAgentToolGatewayTest {
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant DEADLINE = Instant.now().plus(Duration.ofHours(1));
    private static final ThreadPreferences APPROVAL_PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.MANUAL);

    /** 测试显式提供与生产相同的策略 Owner，禁用路径由 fake 行控制而非默认放行。 */
    private static TaskAgentToolGateway gateway() {
        return gateway(SubagentPolicy.defaultPolicy());
    }

    /** 让每个测试显式固定 Thread 快照，避免测试构造器隐式放行生产策略。 */
    private static TaskAgentToolGateway gateway(SubagentPolicy policy) {
        return new TaskAgentToolGateway(threadId -> java.util.Optional.of(policy));
    }

    /** Provider 可见目录必须保持七个稳定名称，避免 schema 漂移产生不可恢复的持久 Tool batch。 */
    @Test
    void exposesExactNativeToolCatalog() {
        TaskAgentToolGateway gateway = gateway();

        List<String> names = tools(gateway, APPROVAL_PREFERENCES).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("spawn_agent", "send_message", "continue_agent",
                "wait_agent", "list_agents", "list_threads", "cancel_agent"), names);
    }

    /** 禁用只移除 spawn_agent；已有会话仍可通信、等待、枚举和取消子任务。 */
    @Test
    void disabledPolicyKeepsExistingTaskCommunicationTools() {
        TaskAgentToolGateway gateway = gateway(new SubagentPolicy(false, null, null, null));

        List<String> names = tools(gateway, APPROVAL_PREFERENCES).stream()
                .map(tool -> tool.spec().name()).toList();

        assertTrue(!names.contains("spawn_agent"));
        assertEquals(List.of("send_message", "continue_agent", "wait_agent", "list_agents", "list_threads",
                "cancel_agent"),
                names);
    }

    /** Thread discovery owner 与 Task owner 一样只允许同一实例幂等重入，防止请求中途切换数据库快照。 */
    @Test
    void bindsOneStableThreadDiscoveryOwner() {
        TaskAgentToolGateway gateway = gateway();
        ThreadUseCase first = discoveryOwner(new AtomicReference<>());
        gateway.bindThreads(first);

        gateway.bindThreads(first);

        assertThrows(IllegalStateException.class,
                () -> gateway.bindThreads(discoveryOwner(new AtomicReference<>())));
    }

    /** list_threads 只走 ThreadUseCase discovery，且返回最小字段闭集与不透明下一页游标。 */
    @Test
    void listsThreadsThroughBoundReadOnlyOwner() {
        TaskAgentToolGateway gateway = gateway();
        AtomicReference<ThreadDiscovery.Query> query = new AtomicReference<>();
        gateway.bindThreads(discoveryOwner(query));
        AgentTool tool = tool(gateway, "list_threads");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_list", "list_threads",
                JsonObjects.builder().putText("query", "review")
                        .putText("cursor", "opaque_cursor")
                        .putNumber("limit", 2)
                        .putText("workspaceId", "ws_project").build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_parent"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(ToolSideEffect.READ_ONLY, tool.sideEffect());
        assertEquals(AgentTool.WorkspaceMutationMode.NONE, tool.workspaceMutationMode());
        assertEquals(new ThreadDiscovery.Query("all", "review", "opaque_cursor", 2, "ws_project"),
                query.get());
        JsonObject page = (JsonObject) result.structuredContent().orElseThrow();
        JsonArray items = (JsonArray) page.get("items");
        assertEquals(1, items.values().size());
        JsonObject item = (JsonObject) items.values().getFirst();
        assertEquals(new JsonText("thr_discovered"), item.get("threadId"));
        assertEquals(new JsonText("review target"), item.get("title"));
        assertEquals(new JsonText("subagent"), item.get("kind"));
        assertEquals(new JsonText("ws_project"), item.get("workspaceId"));
        assertEquals(new JsonText("completed"), item.get("status"));
        assertEquals(new JsonText("cursor_next"), page.get("nextCursor"));
    }

    /** continue_agent 只通过带请求者身份的应用端口续跑，并保留父 Turn 形成的幂等因果键。 */
    @Test
    void continuesAgentThroughBoundRequesterAwarePort() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "continue_agent");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_continue", "continue_agent",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "continue the review")
                        .putNumber("expectedTaskRevision", 3).build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_parent"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("thr_parent", tasks.continuationRequester.get());
        assertEquals("thr_parent", tasks.continuation.get().message().senderThreadId());
        assertEquals("thr_child", tasks.continuation.get().message().targetThreadId());
        assertEquals("continue the review", tasks.continuation.get().message().content().text());
        assertEquals(3, tasks.continuation.get().expectedTaskRevision());
    }

    /** list_agents 只返回当前调用者沿 SUBAGENT 边委派的后代，独立侧聊及其分支不外泄。 */
    @Test
    void listsOnlyDelegatedSubagentSubtree() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        tasks.tree = List.of(
                task("thr_agent_a", "thr_parent", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_agent_a_child", "thr_agent_a", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_agent_b", "thr_parent", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_side", "thr_parent", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT),
                task("thr_side_agent", "thr_side", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED));
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "list_agents");

        AgentTool.ToolResult result = tool.execute(
                        new AgentTool.Invocation("call_agents", "list_agents", JsonObjects.builder().build(), 0),
                        context("turn_parent"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        JsonObject structured = (JsonObject) result.structuredContent().orElseThrow();
        JsonArray values = (JsonArray) structured.get("tasks");
        assertEquals(List.of("thr_agent_a", "thr_agent_a_child", "thr_agent_b"), values.values().stream()
                .map(value -> ((JsonObject) value).get("threadId"))
                .map(value -> ((JsonText) value).value()).toList());
    }

    /** Late binding 只允许同一 owner 幂等重入，禁止运行中热替换 TaskUseCase。 */
    @Test
    void bindsOneStableTaskOwner() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks first = new RecordingTasks();
        gateway.bind(first);

        gateway.bind(first);

        assertThrows(IllegalStateException.class, () -> gateway.bind(new RecordingTasks()));
    }

    /** send_message 只调用 QueueOnly 端口，并以父 Turn 与 Provider callId 形成稳定幂等键。 */
    @Test
    void sendsQueueOnlyMessageWithFrozenCausality() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "send_message");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_send", "send_message",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "check the failing test").build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_parent"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("thr_parent", tasks.message.get().senderThreadId());
        assertEquals("thr_child", tasks.message.get().targetThreadId());
        assertEquals("turn_parent", tasks.message.get().causalTurnId());
        assertEquals("tool:turn_parent:call_send", tasks.message.get().idempotencyKey());
        assertEquals("check the failing test", tasks.message.get().content().text());
    }

    /** 已冻结 Tool 不能被另一 Turn 复用，即使 Thread、配置、权限和 Deadline 全部相同。 */
    @Test
    void rejectsExecutionFromAnotherTurn() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "send_message");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_send", "send_message",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "must not arrive").build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_other"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("TASK_TOOL_FAILED", result.errorCode());
        assertNull(tasks.message.get());
    }

    /** approval_required 父 Turn 不能请求 full_access Child，拒绝必须发生在应用端口调用之前。 */
    @Test
    void rejectsChildPermissionEscalation() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "spawn_agent");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_spawn", "spawn_agent",
                JsonObjects.builder().putText("taskName", "review")
                        .putText("brief", "review current changes")
                        .putText("accessMode", "full_access").build(), 0);

        AgentTool.ToolResult result = tool.execute(invocation, context("turn_parent"), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("PERMISSION_DENIED", result.errorCode());
        assertNull(tasks.spawn.get());
    }

    /** full_access 父 Turn 可显式收窄 Child，且 SpawnCommand 必须携带完整父能力 ceiling。 */
    @Test
    void narrowsChildAccessAndPreservesCapabilityCeiling() {
        ThreadPreferences fullPreferences = new ThreadPreferences(
                "provider_test", "model_test", "medium", AccessMode.FULL_ACCESS,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        TaskAgentToolGateway gateway = gateway();
        JsonObject ceiling = gateway.create(fullPreferences, "cfg_test", catalogIdentity());
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool tool = tool(gateway, "spawn_agent", fullPreferences);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_spawn", "spawn_agent",
                JsonObjects.builder().putText("taskName", "review")
                        .putText("brief", "review current changes")
                        .putText("accessMode", "approval_required").build(), 0);

        tool.execute(invocation, context("turn_parent", AccessMode.FULL_ACCESS), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(AccessMode.APPROVAL_REQUIRED, tasks.spawn.get().frozenPreferences().accessMode());
        assertEquals("provider_test", tasks.spawn.get().frozenPreferences().providerId());
        assertEquals("model_test", tasks.spawn.get().frozenPreferences().modelId());
        assertEquals("medium", tasks.spawn.get().frozenPreferences().reasoningLevel());
        assertEquals(ceiling, tasks.spawn.get().capabilityCeiling());
    }

    /** 指定模型只替换模型身份；ceiling 保留父上限，子实际权限仍按显式请求收紧。 */
    @Test
    void specifiedModelUsesChildIdentityWithoutExpandingParentCapability() {
        ThreadPreferences parent = new ThreadPreferences(
                "provider_parent", "model_parent", "high", AccessMode.FULL_ACCESS,
                io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        TaskAgentToolGateway gateway = gateway(new SubagentPolicy(true, "provider_child", "model_child", "high"));
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_child_model", "spawn_agent",
                JsonObjects.builder().putText("taskName", "model-check")
                        .putText("brief", "run with the configured child model")
                        .putText("accessMode", "approval_required").build(), 0);

        tool(gateway, "spawn_agent", parent).execute(invocation,
                        context("turn_parent", AccessMode.FULL_ACCESS), CancellationToken.none())
                .toCompletableFuture().join();

        TaskUseCase.SpawnCommand command = tasks.spawn.get();
        assertEquals("provider_child", command.frozenPreferences().providerId());
        assertEquals("model_child", command.frozenPreferences().modelId());
        assertEquals("high", command.frozenPreferences().reasoningLevel());
        assertEquals(AccessMode.APPROVAL_REQUIRED, command.frozenPreferences().accessMode());
        assertEquals("provider_child", ((JsonText) command.capabilityCeiling().get("providerId")).value());
        assertEquals("model_child", ((JsonText) command.capabilityCeiling().get("modelId")).value());
        assertEquals(parent.accessMode().name().toLowerCase(java.util.Locale.ROOT),
                ((JsonText) command.capabilityCeiling().get("accessMode")).value());
        assertEquals("high", ((JsonText) command.capabilityCeiling().get("reasoningLevel")).value());
    }

    /** lineage kind 与 ceiling variant 交叉时必须失败关闭，Subagent 不得降级为 access-only。 */
    @Test
    void rejectsCeilingVariantThatDoesNotMatchTaskKind() {
        JsonObject access = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
        JsonObject capability = gateway().create(APPROVAL_PREFERENCES, "cfg_test",
                new AgentCapability.CatalogIdentity("a".repeat(64), "mcp_test", Set.of()));

        TaskRepositoryException subagent = assertThrows(TaskRepositoryException.class,
                () -> TaskAgentToolGateway.validateCeilingKind(TaskModels.Kind.SUBAGENT, access));
        TaskRepositoryException sideTask = assertThrows(TaskRepositoryException.class,
                () -> TaskAgentToolGateway.validateCeilingKind(TaskModels.Kind.SIDE_TASK, capability));

        assertEquals(TaskRepositoryException.Code.INVALID_STATE, subagent.code());
        assertEquals(TaskRepositoryException.Code.INVALID_STATE, sideTask.code());
    }

    /** 按名称取唯一 Tool，使测试同时证明目录内没有重复名称。 */
    private static AgentTool tool(TaskAgentToolGateway gateway, String name) {
        return tool(gateway, name, APPROVAL_PREFERENCES);
    }

    /** 按指定父偏好绑定 Tool，供权限收窄测试保持执行上下文一致。 */
    private static AgentTool tool(TaskAgentToolGateway gateway, String name,
                                  ThreadPreferences preferences) {
        List<AgentTool> matches = tools(gateway, preferences).stream()
                .filter(candidate -> candidate.spec().name().equals(name)).toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    /** 构造与 Gateway Binding 完全一致的执行上下文；单项漂移由各测试显式覆盖。 */
    private static AgentTool.ExecutionContext context(String turnId) {
        return context(turnId, AccessMode.APPROVAL_REQUIRED);
    }

    /** 允许测试显式选择父权限，其他冻结字段仍与 Binding 保持相同。 */
    private static AgentTool.ExecutionContext context(String turnId, AccessMode accessMode) {
        return new AgentTool.ExecutionContext("thr_parent", turnId,
                Path.of("C:\\ja-task-tools").toAbsolutePath(), accessMode,
                "cfg_test", DEADLINE, "ws_test");
    }

    /** 通过标准能力端口物化请求级 Tool，测试不依赖 Gateway 私有绑定表示。 */
    private static List<AgentTool> tools(TaskAgentToolGateway gateway, ThreadPreferences preferences) {
        AgentCapability.Prepared prepared = gateway.prepare(request(preferences));
        return prepared.tools().stream().map(contribution -> contribution.binder().apply(catalogIdentity()))
                .toList();
    }

    /** 请求冻结父身份、权限和 deadline，Task capability 不反向读取 runtime 全局状态。 */
    private static AgentCapability.Request request(ThreadPreferences preferences) {
        return new AgentCapability.Request("thr_parent", "turn_parent",
                Path.of("C:\\ja-task-tools").toAbsolutePath(), "ws_test", preferences,
                "cfg_test", true, DEADLINE, TurnOrigin.USER);
    }

    /** 统一最终目录身份，Spawn ceiling 与测试断言共享相同安全摘要。 */
    private static AgentCapability.CatalogIdentity catalogIdentity() {
        return new AgentCapability.CatalogIdentity(
                "a".repeat(64), "mcp_test", Set.of("skill_review"));
    }

    /** 仅实现 discovery 方法的代理；其它 Thread 入口一旦被触达即暴露错误耦合。 */
    private static ThreadUseCase discoveryOwner(AtomicReference<ThreadDiscovery.Query> observed) {
        return (ThreadUseCase) Proxy.newProxyInstance(ThreadUseCase.class.getClassLoader(),
                new Class<?>[]{ThreadUseCase.class}, (proxy, method, arguments) -> {
                    if (!"discoverThreads".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    ThreadDiscovery.Query query = (ThreadDiscovery.Query) arguments[0];
                    observed.set(query);
                    return new CursorPage<>(List.of(new ThreadDiscovery("thr_discovered", "review target",
                            ThreadDiscovery.Kind.SUBAGENT, "ws_project", ThreadDiscovery.Status.COMPLETED)),
                            "cursor_next");
                });
    }

    /** 构造包含真实 parent/kind/lifecycle 的最小 projection，供 list_agents 验证树边界。 */
    private static TaskModels.Summary task(String threadId, String parentThreadId,
                                           TaskModels.Kind kind, TaskModels.Lifecycle lifecycle) {
        int depth = parentThreadId.equals("thr_parent") ? 1 : 2;
        TaskModels.Lineage lineage = new TaskModels.Lineage(threadId, parentThreadId, "thr_parent",
                "turn_origin", threadId, depth, kind, lifecycle,
                "seed_" + threadId.substring("thr_".length()), NOW);
        TaskModels.Projection projection = new TaskModels.Projection(threadId, "thr_parent", 1,
                TaskModels.State.IDLE, 1, null, 0, 0, 0, 0, "idle", null, null, NOW);
        return new TaskModels.Summary(lineage, projection);
    }

    /** 只记录本切片实际调用的命令，其余端口拒绝使用以暴露意外耦合。 */
    private static final class RecordingTasks implements TaskUseCase {
        private final AtomicReference<MessageCommand> message = new AtomicReference<>();
        private final AtomicReference<SpawnCommand> spawn = new AtomicReference<>();
        private final AtomicReference<FollowUpCommand> continuation = new AtomicReference<>();
        private final AtomicReference<String> continuationRequester = new AtomicReference<>();
        private List<TaskModels.Summary> tree;

        /** 测试不覆盖用户侧边任务创建。 */
        @Override public TaskModels.Summary createSideTask(CreateCommand command) { throw unsupported(); }

        /** 记录 Subagent 命令；权限拒绝测试断言该入口未被触达。 */
        @Override public StartResult spawnAgent(SpawnCommand command) {
            spawn.set(command);
            throw unsupported();
        }

        /** list_agents 场景返回显式 fixture，其余测试仍对意外树读取失败关闭。 */
        @Override public List<TaskModels.Summary> listTree(String rootThreadId) {
            if (tree != null) return tree;
            throw unsupported();
        }

        /** Agent Tool 测试不读取父 Timeline 活动投影。 */
        @Override public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
            throw unsupported();
        }

        /** root Thread 的 discovery fixture 用 NOT_FOUND 表示它不是 Child Task。 */
        @Override public TaskModels.Detail read(String taskThreadId, long afterActivitySequence,
                                                long afterMailboxSequence, int limit) {
            if (tree != null) throw new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                    "root thread has no task detail");
            throw unsupported();
        }

        /** 测试不建立观察句柄。 */
        @Override public Observation observe(String taskThreadId, long expectedTaskRevision) { throw unsupported(); }

        /** 测试不释放观察句柄。 */
        @Override public void unobserve(String observationId) { throw unsupported(); }

        /** 测试不推进已读边界。 */
        @Override public TaskModels.Summary markSeen(String taskThreadId, long expectedTaskRevision,
                                                     long throughActivitySequence) { throw unsupported(); }

        /** 记录 QueueOnly 命令并返回稳定持久化回执。 */
        @Override public MessageReceipt sendMessage(MessageCommand command) {
            message.set(command);
            return new MessageReceipt("msg_test", 1);
        }

        /** 记录带 requester 身份的 Subagent continuation；普通 followUp 入口保持未使用。 */
        @Override public FollowUpResult followUp(FollowUpCommand command) { throw unsupported(); }

        /** continue_agent 必须通过 requester-aware 应用端口，不能退回通用 followUp。 */
        @Override public FollowUpResult continueAgentFrom(String requesterThreadId, FollowUpCommand command) {
            continuationRequester.set(requesterThreadId);
            continuation.set(command);
            return new FollowUpResult(task("thr_child", "thr_parent", TaskModels.Kind.SUBAGENT,
                    TaskModels.Lifecycle.ATTACHED), "turn_child", "msg_child");
        }

        /** 测试不取消 Task。 */
        @Override public TaskModels.Summary cancel(String taskThreadId, long expectedTaskRevision) {
            throw unsupported();
        }

        /** 测试不关闭临时侧聊。 */
        @Override public void closeSideChat(String taskThreadId) { throw unsupported(); }
        /** 工具 fixture 没有临时会话，生命周期调用不得启动业务工作。 */
        @Override public void closeTemporarySideChats(long deadlineNanos) { }

        /** 测试不执行带请求者授权的取消。 */
        @Override public TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId,
                                                       long expectedTaskRevision) { throw unsupported(); }

        /** 测试不删除 Task 树。 */
        @Override public int deleteTree(String taskThreadId, long expectedTaskRevision,
                                        String confirmTaskThreadId) { throw unsupported(); }

        /** 测试不启动事件等待。 */
        @Override public CompletionStage<WaitResult> waitAgents(Set<String> taskThreadIds, Duration timeout,
                                                                CancellationToken cancellation) {
            return CompletableFuture.failedFuture(unsupported());
        }

        /** 测试不执行带请求者授权的等待。 */
        @Override public CompletionStage<WaitResult> waitAgentsFrom(
                String requesterThreadId, Set<String> taskThreadIds, Duration timeout,
                CancellationToken cancellation) {
            return CompletableFuture.failedFuture(unsupported());
        }

        /** 测试不建立投影订阅。 */
        @Override public AutoCloseable subscribe(TaskEventSink sink) { throw unsupported(); }

        /** 测试 owner 没有资源。 */
        @Override public void close() { }

        /** 所有未授权路径统一失败，避免测试 fake 静默制造成功。 */
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this test");
        }
    }
}
