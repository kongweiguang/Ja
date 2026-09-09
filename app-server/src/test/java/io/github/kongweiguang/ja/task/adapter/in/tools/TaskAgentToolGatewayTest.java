// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.task.adapter.in.tools;

import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnOrigin;
import io.github.kongweiguang.ja.conversation.port.out.AgentCapability;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证原生 Task Tools 的冻结身份、权限上限和 QueueOnly 应用端口映射。 */
final class TaskAgentToolGatewayTest {
    private static final Instant DEADLINE = Instant.now().plus(Duration.ofHours(1));
    private static final ThreadPreferences APPROVAL_PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            io.github.kongweiguang.ja.conversation.domain.CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.MANUAL);

    /** Provider 可见目录必须保持六个稳定名称，避免 schema 漂移产生不可恢复的持久 Tool batch。 */
    @Test
    void exposesExactNativeToolCatalog() {
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();

        List<String> names = tools(gateway, APPROVAL_PREFERENCES).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("spawn_agent", "send_message", "followup_task",
                "wait_agent", "list_agents", "cancel_agent"), names);
    }

    /** Late binding 只允许同一 owner 幂等重入，禁止运行中热替换 TaskUseCase。 */
    @Test
    void bindsOneStableTaskOwner() {
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();
        RecordingTasks first = new RecordingTasks();
        gateway.bind(first);

        gateway.bind(first);

        assertThrows(IllegalStateException.class, () -> gateway.bind(new RecordingTasks()));
    }

    /** send_message 只调用 QueueOnly 端口，并以父 Turn 与 Provider callId 形成稳定幂等键。 */
    @Test
    void sendsQueueOnlyMessageWithFrozenCausality() {
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();
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
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();
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
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();
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
        TaskAgentToolGateway gateway = new TaskAgentToolGateway();
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
        assertEquals(ceiling, tasks.spawn.get().capabilityCeiling());
    }

    /** lineage kind 与 ceiling variant 交叉时必须失败关闭，Subagent 不得降级为 access-only。 */
    @Test
    void rejectsCeilingVariantThatDoesNotMatchTaskKind() {
        JsonObject access = JsonObjects.builder().putText("version", "task_access_v1")
                .putText("accessMode", "approval_required").build();
        JsonObject capability = new TaskAgentToolGateway().create(APPROVAL_PREFERENCES, "cfg_test",
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
                "cfg_test", DEADLINE, TurnOrigin.USER);
    }

    /** 统一最终目录身份，Spawn ceiling 与测试断言共享相同安全摘要。 */
    private static AgentCapability.CatalogIdentity catalogIdentity() {
        return new AgentCapability.CatalogIdentity(
                "a".repeat(64), "mcp_test", Set.of("skill_review"));
    }

    /** 只记录本切片实际调用的命令，其余端口拒绝使用以暴露意外耦合。 */
    private static final class RecordingTasks implements TaskUseCase {
        private final AtomicReference<MessageCommand> message = new AtomicReference<>();
        private final AtomicReference<SpawnCommand> spawn = new AtomicReference<>();

        /** 测试不覆盖用户侧边任务创建。 */
        @Override public StartResult createSideTask(CreateCommand command) { throw unsupported(); }

        /** 记录 Subagent 命令；权限拒绝测试断言该入口未被触达。 */
        @Override public StartResult spawnAgent(SpawnCommand command) {
            spawn.set(command);
            throw unsupported();
        }

        /** 测试不物化任务树。 */
        @Override public List<TaskModels.Summary> listTree(String rootThreadId) { throw unsupported(); }

        /** Agent Tool 测试不读取父 Timeline 活动投影。 */
        @Override public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
            throw unsupported();
        }

        /** 测试不读取详情。 */
        @Override public TaskModels.Detail read(String taskThreadId, long afterActivitySequence,
                                                long afterMailboxSequence, int limit) { throw unsupported(); }

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

        /** 测试不调度 Follow-up。 */
        @Override public FollowUpResult followUp(FollowUpCommand command) { throw unsupported(); }

        /** 测试不取消 Task。 */
        @Override public TaskModels.Summary cancel(String taskThreadId, long expectedTaskRevision) {
            throw unsupported();
        }

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
