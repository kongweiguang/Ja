// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.collaboration.interfaces.tools;

import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolCatalogIdentity;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolRequestContext;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.application.api.TaskEventSink;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.foundation.lifecycle.NativeClientOwner;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepositoryException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
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
    private static final Map<TaskAgentToolGateway, SubagentPolicy> POLICIES = new IdentityHashMap<>();
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");
    private static final Instant DEADLINE = Instant.now().plus(Duration.ofHours(1));
    private static final ThreadPreferences APPROVAL_PREFERENCES = new ThreadPreferences(
            "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
            io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
            ThreadPreferences.TitleSource.MANUAL);

    /** 测试显式提供与生产相同的策略 Owner，禁用路径由 fake 行控制而非默认放行。 */
    private static TaskAgentToolGateway gateway() {
        return gateway(SubagentPolicy.defaultPolicy());
    }

    /** 让每个测试显式固定 Thread 快照，避免测试构造器隐式放行生产策略。 */
    private static TaskAgentToolGateway gateway(SubagentPolicy policy) {
        TaskAgentToolGateway gateway = new TaskAgentToolGateway(
                threadId -> java.util.Optional.of(policy), new JaAdmissionParticipants());
        POLICIES.put(gateway, policy);
        return gateway;
    }

    /** Root 没有实际子任务时只暴露委派准入与只读观察入口，不把通信能力伪装成默认工具。 */
    @Test
    void exposesExactNativeToolCatalog() {
        TaskAgentToolGateway gateway = gateway();

        List<String> names = tools(gateway, APPROVAL_PREFERENCES).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("spawn_agent", "wait_agent", "list_agents"), names);
    }

    /** 禁用只移除创建入口；已有委派子任务仍保留通信、等待、枚举、续跑与取消能力。 */
    @Test
    void disabledPolicyKeepsExistingTaskCommunicationTools() {
        TaskAgentToolGateway gateway = gateway(new SubagentPolicy(false, null, null, null));
        RecordingTasks tasks = new RecordingTasks();
        tasks.delegatedAgents = true;
        gateway.bind(tasks);

        List<String> names = tools(gateway, APPROVAL_PREFERENCES).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("send_message", "continue_agent", "wait_agent", "list_agents", "cancel_agent"), names);
    }

    /** 侧聊显式拥有跨会话入口；它仍可按自身策略委派，但不会获得全局 Thread 发现以外的控制权。 */
    @Test
    void sideTaskGetsCrossSessionDiscoveryAndOwnDelegationTools() {
        TaskAgentToolGateway gateway = gateway();

        List<String> names = tools(gateway, APPROVAL_PREFERENCES,
                TaskCapabilityCeilingPort.Kind.SIDE_TASK).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("spawn_agent", "send_message", "wait_agent", "list_agents", "list_threads"), names);
    }

    /** Subagent 可向外投递消息并按自身策略创建后代，但全局 Thread 发现只属于独立侧聊。 */
    @Test
    void subagentGetsCommunicationWithoutGlobalThreadDiscovery() {
        TaskAgentToolGateway gateway = gateway();

        List<String> names = tools(gateway, APPROVAL_PREFERENCES,
                TaskCapabilityCeilingPort.Kind.SUBAGENT).stream()
                .map(tool -> tool.spec().name()).toList();

        assertEquals(List.of("spawn_agent", "send_message", "wait_agent", "list_agents"), names);
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

    /** deadline 刷新后仍走绑定的 ThreadUseCase，返回最小字段闭集与不透明下一页游标。
     *
     * @author kongweiguang
     */
    @Test
    void listsThreadsWithRefreshedDeadlineThroughBoundReadOnlyOwner() {
        TaskAgentToolGateway gateway = gateway();
        AtomicReference<ThreadDiscovery.Query> query = new AtomicReference<>();
        gateway.bindThreads(discoveryOwner(query));
        JaTool tool = tool(gateway, "list_threads", APPROVAL_PREFERENCES,
                TaskCapabilityCeilingPort.Kind.SIDE_TASK);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_list", "list_threads",
                JsonObjects.builder().putText("query", "review")
                        .putText("cursor", "opaque_cursor")
                        .putNumber("limit", 2)
                        .putText("workspaceId", "ws_project").build(), 0);

        JaTool.ToolResult result = tool.execute(invocation,
                context("turn_parent", invocation.callId(), AccessMode.APPROVAL_REQUIRED, DEADLINE.plusSeconds(30)),
                CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals(ToolSideEffect.READ_ONLY, tool.sideEffect());
        assertEquals(JaTool.WorkspaceMutationMode.NONE, tool.workspaceMutationMode(invocation));
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
        assertModelReceipt(result);
    }

    /** continue_agent 只通过带请求者身份的应用端口续跑，并保留父 Turn 形成的幂等因果键。 */
    @Test
    void continuesAgentThroughBoundRequesterAwarePort() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        tasks.delegatedAgents = true;
        gateway.bind(tasks);
        JaTool tool = tool(gateway, "continue_agent");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_continue", "continue_agent",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "continue the review")
                        .putNumber("expectedTaskRevision", 3).build(), 0);

        JaTool.ToolResult result = tool.execute(invocation, context("turn_parent", invocation.callId()), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("thr_parent", tasks.continuationRequester.get());
        assertEquals("thr_parent", tasks.continuation.get().message().senderThreadId());
        assertEquals("thr_child", tasks.continuation.get().message().targetThreadId());
        assertEquals("continue the review", tasks.continuation.get().message().content().text());
        assertEquals(3, tasks.continuation.get().expectedTaskRevision());
        assertModelReceipt(result);
    }

    /** list_agents 只返回当前调用者沿 SUBAGENT 边委派的后代，独立侧聊及其分支不外泄。 */
    @Test
    void listsOnlyDelegatedSubagentSubtree() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        tasks.delegatedAgents = true;
        tasks.tree = List.of(
                task("thr_agent_a", "thr_parent", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_agent_a_child", "thr_agent_a", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_agent_b", "thr_parent", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED),
                task("thr_side", "thr_parent", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT),
                task("thr_side_agent", "thr_side", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED));
        gateway.bind(tasks);
        JaTool tool = tool(gateway, "list_agents");
        AgentTool.Invocation invocation = new AgentTool.Invocation(
                "call_agents", "list_agents", JsonObjects.builder().build(), 0);

        JaTool.ToolResult result = tool.execute(
                        invocation, context("turn_parent", invocation.callId()), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        JsonObject structured = (JsonObject) result.structuredContent().orElseThrow();
        JsonArray values = (JsonArray) structured.get("tasks");
        assertEquals(List.of("thr_agent_a", "thr_agent_a_child", "thr_agent_b"), values.values().stream()
                .map(value -> ((JsonObject) value).get("threadId"))
                .map(value -> ((JsonText) value).value()).toList());
        assertModelReceipt(result);
    }

    /** 等待正文必须包含超时标记、子任务身份及 revision，模型不能只收到不可继续操作的摘要。 */
    @Test
    void waitReceiptPreservesModelVisibleTaskStateForCompletionAndTimeout() {
        for (boolean timedOut : List.of(false, true)) {
            TaskAgentToolGateway gateway = gateway();
            RecordingTasks tasks = new RecordingTasks();
            tasks.waitResult = new TaskUseCase.WaitResult(List.of(
                    task("thr_child", "thr_parent", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED)), timedOut);
            gateway.bind(tasks);
            JaTool tool = tool(gateway, "wait_agent");
            AgentTool.Invocation invocation = new AgentTool.Invocation("call_wait", "wait_agent",
                    JsonObjects.builder().put("taskThreadIds", new JsonArray(List.of(new JsonText("thr_child"))))
                            .putNumber("timeoutMs", 30_000).build(), 0);

            JaTool.ToolResult result = tool.execute(invocation, context("turn_parent", invocation.callId()),
                    CancellationToken.none()).toCompletableFuture().join();

            assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
            assertModelReceipt(result);
            JsonObject receipt = (JsonObject) new JacksonJsonValueCodec().decode(result.content());
            assertEquals(new io.github.kongweiguang.ja.agent.domain.json.JsonBoolean(timedOut),
                    receipt.get("timedOut"));
            JsonObject child = (JsonObject) ((JsonArray) receipt.get("tasks")).values().getFirst();
            assertEquals(new JsonText("thr_child"), child.get("threadId"));
            assertEquals(new JsonText("idle"), child.get("status"));
            assertEquals(new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(1), child.get("revision"));
        }
    }

    /** 真实 Provider 使用正文续传，校验其可解析且与 UI 字段闭集完全相同，避免漏掉控制所需身份。 */
    private static void assertModelReceipt(JaTool.ToolResult result) {
        assertEquals(result.structuredContent().orElseThrow(), new JacksonJsonValueCodec().decode(result.content()));
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
        tasks.delegatedAgents = true;
        gateway.bind(tasks);
        JaTool tool = tool(gateway, "send_message");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_send", "send_message",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "check the failing test").build(), 0);

        JaTool.ToolResult result = tool.execute(invocation, context("turn_parent", invocation.callId()), CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("thr_parent", tasks.message.get().senderThreadId());
        assertEquals("thr_child", tasks.message.get().targetThreadId());
        assertEquals("turn_parent", tasks.message.get().causalTurnId());
        assertEquals("tool:turn_parent:call_send", tasks.message.get().operationKey());
        assertEquals("check the failing test", tasks.message.get().content().text());
        assertModelReceipt(result);
    }

    /** 已冻结 Tool 不能被另一 Turn 复用，即使 Thread、配置、权限和 Deadline 全部相同。 */
    @Test
    void rejectsExecutionFromAnotherTurn() {
        TaskAgentToolGateway gateway = gateway();
        RecordingTasks tasks = new RecordingTasks();
        tasks.delegatedAgents = true;
        gateway.bind(tasks);
        JaTool tool = tool(gateway, "send_message");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_send", "send_message",
                JsonObjects.builder().putText("targetThreadId", "thr_child")
                        .putText("message", "must not arrive").build(), 0);

        JaTool.ToolResult result = tool.execute(invocation, context("turn_other", invocation.callId()), CancellationToken.none())
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
        JaTool tool = tool(gateway, "spawn_agent");
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_spawn", "spawn_agent",
                JsonObjects.builder().putText("taskName", "review")
                        .putText("brief", "review current changes")
                        .putText("accessMode", "full_access").build(), 0);

        JaTool.ToolResult result = tool.execute(invocation, context("turn_parent", invocation.callId()), CancellationToken.none())
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
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        TaskAgentToolGateway gateway = gateway();
        JsonObject ceiling = gateway.create(fullPreferences, "cfg_test", catalogIdentity());
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        JaTool tool = tool(gateway, "spawn_agent", fullPreferences);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_spawn", "spawn_agent",
                JsonObjects.builder().putText("taskName", "review")
                        .putText("brief", "review current changes")
                        .putText("accessMode", "approval_required").build(), 0);

        tool.execute(invocation, context("turn_parent", invocation.callId(), AccessMode.FULL_ACCESS), CancellationToken.none())
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
                io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                ThreadPreferences.TitleSource.MANUAL);
        TaskAgentToolGateway gateway = gateway(new SubagentPolicy(true, "provider_child", "model_child", "high"));
        RecordingTasks tasks = new RecordingTasks();
        gateway.bind(tasks);
        AgentTool.Invocation invocation = new AgentTool.Invocation("call_child_model", "spawn_agent",
                JsonObjects.builder().putText("taskName", "model-check")
                        .putText("brief", "run with the configured child model")
                        .putText("accessMode", "approval_required").build(), 0);

        tool(gateway, "spawn_agent", parent).execute(invocation,
                        context("turn_parent", invocation.callId(), AccessMode.FULL_ACCESS), CancellationToken.none())
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
                new JaToolCatalogIdentity("a".repeat(64), "mcp_test", Set.of()));

        TaskRepositoryException subagent = assertThrows(TaskRepositoryException.class,
                () -> TaskAgentToolGateway.validateCeilingKind(TaskModels.Kind.SUBAGENT, access));
        TaskRepositoryException sideTask = assertThrows(TaskRepositoryException.class,
                () -> TaskAgentToolGateway.validateCeilingKind(TaskModels.Kind.SIDE_TASK, capability));

        assertEquals(TaskRepositoryException.Code.INVALID_STATE, subagent.code());
        assertEquals(TaskRepositoryException.Code.INVALID_STATE, sideTask.code());
    }

    /** 只沿 SUBAGENT/ATTACHED 链向上归属；跨过独立 Side Task 后 Root 不得取得其后代管理入口。 */
    @Test
    void delegatedAgentDetectionStopsAtIndependentSideTask() {
        RecordingTasks tasks = new RecordingTasks();
        tasks.tree = List.of(
                task("thr_side", "thr_parent", TaskModels.Kind.SIDE_TASK, TaskModels.Lifecycle.INDEPENDENT),
                task("thr_child", "thr_side", TaskModels.Kind.SUBAGENT, TaskModels.Lifecycle.ATTACHED));

        assertTrue(tasks.hasDelegatedAgents("thr_side"));
        assertTrue(!tasks.hasDelegatedAgents("thr_parent"));
    }

    /** 按名称取唯一 Tool，使测试同时证明目录内没有重复名称。 */
    private static JaTool tool(TaskAgentToolGateway gateway, String name) {
        return tool(gateway, name, APPROVAL_PREFERENCES);
    }

    /** 按指定父偏好绑定 Tool，供权限收窄测试保持执行上下文一致。 */
    private static JaTool tool(TaskAgentToolGateway gateway, String name,
                                  ThreadPreferences preferences) {
        return tool(gateway, name, preferences, null);
    }

    /** 按真实 Task 身份选择目录，覆盖 Root、Side Task 与 Subagent 的公开边界。 */
    private static JaTool tool(TaskAgentToolGateway gateway, String name,
                                  ThreadPreferences preferences,
                                  TaskCapabilityCeilingPort.Kind taskKind) {
        List<JaTool> matches = tools(gateway, preferences, taskKind).stream()
                .filter(candidate -> candidate.spec().name().equals(name)).toList();
        assertEquals(1, matches.size());
        return matches.getFirst();
    }

    /** 构造 Gateway Binding 身份一致且可单独替换短时 IO deadline 的应用上下文。
     *
     * @author kongweiguang
     */
    private static JaTool.ExecutionContext context(String turnId, String callId) {
        return context(turnId, callId, AccessMode.APPROVAL_REQUIRED, DEADLINE);
    }

    /** Null-turn 辅助解析不需要 pending identity，必须在访问策略 owner 前返回空目录。 */
    @Test
    void nullTurnReturnsEmptyCatalogWithoutRuntimeSnapshot() {
        TaskAgentToolGateway gateway = gateway();
        TurnRuntimeRequest turn = new TurnRuntimeRequest("thr_parent", null,
                Path.of("C:\\ja-task-tools").toAbsolutePath(), "ws_test",
                APPROVAL_PREFERENCES.providerId(), APPROVAL_PREFERENCES.modelId(),
                APPROVAL_PREFERENCES.reasoningLevel(), APPROVAL_PREFERENCES.accessMode(),
                APPROVAL_PREFERENCES.collaborationMode(), TurnOrigin.USER, NOW);
        JaToolRequestContext request = new JaToolRequestContext(turn, APPROVAL_PREFERENCES, "cfg_test",
                true, DEADLINE, java.util.Optional.empty());

        assertTrue(gateway.toolsFor(request, java.util.Optional.empty()).definitions().isEmpty());
    }

    /** 父权限可变但 Thread、Turn、配置代际与 Gateway Binding 保持一致。
     *
     * @author kongweiguang
     */
    private static JaTool.ExecutionContext context(String turnId, String callId, AccessMode accessMode) {
        return context(turnId, callId, accessMode, DEADLINE);
    }

    /** 注入独立 deadline，验证每次请求窗口刷新不改变稳定的授权身份。
     *
     * @author kongweiguang
     */
    private static JaTool.ExecutionContext context(String turnId, String callId, AccessMode accessMode,
                                                   Instant deadline) {
        AgentIdentity identity = new AgentIdentity("tenant_test", "thr_parent", turnId, "user_test");
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId),
                "thr_parent", turnId, Path.of("C:\\ja-task-tools").toAbsolutePath(), accessMode,
                "cfg_test", deadline, "ws_test", null, null, TurnOrigin.USER);
    }

    /** 按冻结请求物化可见 Task Tool，测试不依赖 Gateway 私有绑定表示。 */
    private static List<JaTool> tools(TaskAgentToolGateway gateway, ThreadPreferences preferences) {
        return tools(gateway, preferences, null);
    }

    /** 物化指定 Task 身份的可见 Tool，并按请求目录身份生成 ceiling。 */
    private static List<JaTool> tools(TaskAgentToolGateway gateway, ThreadPreferences preferences,
                                         TaskCapabilityCeilingPort.Kind taskKind) {
        JaToolRequestContext request = request(preferences, taskKind);
        java.util.Optional<TaskCapabilityCeilingPort.RuntimeIdentity> identity = taskKind == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new TaskCapabilityCeilingPort.RuntimeIdentity(
                        "thr_parent", "thr_root", "thr_root", "Parent", "Root", "Root", taskKind));
        java.util.Optional<JsonObject> ceiling = identity.map(ignored -> JsonObjects.builder()
                .putText("version", taskKind == TaskCapabilityCeilingPort.Kind.SIDE_TASK
                        ? "task_access_v1" : "task_capability_v1").build());
        TaskCapabilityCeilingPort.RuntimeSnapshot snapshot = new TaskCapabilityCeilingPort.RuntimeSnapshot(
                JaAdmissionParticipants.identityFor(request.turn().threadId(), request.turn().turnId()),
                ceiling, identity, POLICIES.get(gateway));
        TaskAgentToolGateway.Tools tools = gateway.toolsFor(request, java.util.Optional.of(snapshot));
        return tools.definitions().stream().filter(definition -> tools.visibleNames().contains(definition.spec().name()))
                .map(definition -> definition.materialize(catalogIdentity())).toList();
    }

    /** 请求冻结父身份、权限和 deadline，Task 工厂不反向读取 runtime 全局状态。 */
    private static JaToolRequestContext request(ThreadPreferences preferences) {
        return request(preferences, null);
    }

    /** 请求显式携带由 Task lineage 解析出的 Kind，避免测试通过自然语言模拟通信授权。 */
    private static JaToolRequestContext request(ThreadPreferences preferences,
                                                   TaskCapabilityCeilingPort.Kind taskKind) {
        TurnRuntimeRequest turn = new TurnRuntimeRequest("thr_parent", "turn_parent",
                Path.of("C:\\ja-task-tools").toAbsolutePath(), "ws_test",
                preferences.providerId(), preferences.modelId(), preferences.reasoningLevel(),
                preferences.accessMode(), preferences.collaborationMode(), TurnOrigin.USER, NOW);
        return new JaToolRequestContext(turn, preferences, "cfg_test", true, DEADLINE,
                java.util.Optional.ofNullable(taskKind));
    }

    /** 统一最终目录身份，Spawn ceiling 与测试断言共享相同安全摘要。 */
    private static JaToolCatalogIdentity catalogIdentity() {
        return new JaToolCatalogIdentity(
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
        private boolean delegatedAgents;
        private List<TaskModels.Summary> tree;
        private WaitResult waitResult;

        /** 目录测试显式决定是否存在请求者拥有的子任务，避免从调用文本推导控制范围。 */
        @Override public boolean hasDelegatedAgents(String requesterThreadId) {
            if (delegatedAgents || tree == null) {
                return delegatedAgents;
            }
            return TaskUseCase.super.hasDelegatedAgents(requesterThreadId);
        }

        /** 测试不覆盖用户侧边任务创建。 */
        @Override public TaskModels.Summary createSideTask(CreateCommand command) { throw unsupported(); }

        /**
         * 工具 fixture 不拥有原生客户端，owner-aware 创建路径必须保持显式未启用。
         * @author kongweiguang
         */
        @Override public TaskModels.Summary createSideTask(
                CreateCommand command, java.util.Optional<NativeClientOwner> owner) {
            throw unsupported();
        }

        /**
         * 该工具 fixture 不登记生命周期 root，不应阻止共享后台进入 idle 关闭。
         * @author kongweiguang
         */
        @Override public boolean hasLiveNativeClientWork() { return false; }

        /**
         * 该工具 fixture 不拥有可收口资源，意外生命周期关闭应暴露测试装配错误。
         * @author kongweiguang
         */
        @Override public void closeNativeClient(NativeClientOwner owner, long deadlineNanos) {
            throw unsupported();
        }

        /** 记录 Subagent 命令；权限拒绝测试断言该入口未被触达。 */
        @Override public StartResult spawnAgent(SpawnCommand command) {
            spawn.set(command);
            throw unsupported();
        }

        /** list_agents 场景返回显式 fixture，其余测试仍对意外树读取失败关闭。 */
        @Override public List<TaskModels.Summary> listTree(String rootThreadId) {
            if (tree != null) {
                return tree;
            }
            throw unsupported();
        }

        /** Agent Tool 测试不读取父 Timeline 活动投影。 */
        @Override public List<TaskModels.ActivityProjection> listRootActivities(String rootThreadId, int limit) {
            throw unsupported();
        }

        /** root Thread 的 discovery fixture 用 NOT_FOUND 表示它不是 Child Task。 */
        @Override public TaskModels.Detail read(String taskThreadId, long afterActivitySequence,
                                                long afterMailboxSequence, int limit) {
            if (tree != null) {
                throw new TaskRepositoryException(TaskRepositoryException.Code.NOT_FOUND,
                    "root thread has no task detail");
            }
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
        @Override public TaskModels.SideChatCloseReceipt closeSideChat(String taskThreadId) { throw unsupported(); }

        /**
         * 工具 fixture 不携带 Native owner，不能意外请求 owner-scoped 临时会话关闭。
         * @author kongweiguang
         */
        @Override public TaskModels.SideChatCloseReceipt closeSideChat(
                String taskThreadId, java.util.Optional<NativeClientOwner> ownerScope) {
            throw unsupported();
        }

        /** 工具 fixture 没有临时会话，生命周期调用不得启动业务工作。 */
        @Override public void closeTemporarySideChats(long deadlineNanos) { }

        /** 测试不执行带请求者授权的取消。 */
        @Override public TaskModels.Summary cancelFrom(String requesterThreadId, String taskThreadId,
                                                       long expectedTaskRevision) { throw unsupported(); }

        /** 测试不删除 Task 树。 */
        @Override public int deleteTree(String taskThreadId, long expectedTaskRevision,
                                        String confirmTaskThreadId) { throw unsupported(); }

        /**
         * 工具 fixture 不持有 Native owner；意外走到 owner-scoped deletion 必须失败关闭。
         * @author kongweiguang
         */
        @Override public int deleteTree(String taskThreadId, long expectedTaskRevision,
                                        String confirmTaskThreadId, java.util.Optional<NativeClientOwner> ownerScope) {
            throw unsupported();
        }

        /** 测试不启动事件等待。 */
        @Override public CompletionStage<WaitResult> waitAgents(Set<String> taskThreadIds, Duration timeout,
                                                                CancellationToken cancellation) {
            return CompletableFuture.failedFuture(unsupported());
        }

        /** 等待测试返回显式结果，其余场景拒绝意外等待以保留 fake 的失败关闭约束。 */
        @Override public CompletionStage<WaitResult> waitAgentsFrom(
                String requesterThreadId, Set<String> taskThreadIds, Duration timeout,
                CancellationToken cancellation) {
            return waitResult == null ? CompletableFuture.failedFuture(unsupported())
                    : CompletableFuture.completedFuture(waitResult);
        }

        /** 测试不建立投影订阅。 */
        @Override public AutoCloseable subscribe(TaskEventSink sink) { throw unsupported(); }

        /** 工具 fixture 不接管 Plan 隐藏 Turn 的事件投影，意外调用必须显式失败。 */
        @Override public ConversationTurnEventSink projectContinuationEvents(
                String taskThreadId, ConversationTurnEventSink downstream) {
            throw unsupported();
        }

        /** 测试 owner 没有资源。 */
        @Override public void close() { }

        /** 所有未授权路径统一失败，避免测试 fake 静默制造成功。 */
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by this test");
        }
    }
}
