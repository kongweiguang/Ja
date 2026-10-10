// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.turn.TurnLimits;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntime;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ModelSelection;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaToolBindingCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.JaRequestBindingSource;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnToolSessionFactory;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.support.FixedAgentPromptSession;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceWriteClaimPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;

import java.net.URI;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja 配置租约、MCP 会话和 Core 能力快照共用同一请求身份与释放边界。 */
final class JaAgentRuntimeResolverTest {
    private static final Instant REQUESTED_AT = Instant.parse("2026-09-26T00:00:00Z");
    private static final WorkspaceWriteLeaseCoordinator WRITE_LEASES = writeLeaseFixture();

    /** 每个 resolver 用例共用一个无副作用租约协调器，并在类结束时关闭它的调度资源。 */
    @AfterAll
    static void closeWriteLeaseFixture() {
        WRITE_LEASES.close();
    }

    /** 独立压缩没有 Ja Turn，仍须生成稳定指纹并释放配置租约，且不打开 MCP 或注册 Ja 工具。 */
    @Test
    void resolvesIndependentCompactionWithoutTurnOrMcp() {
        AtomicInteger leaseCloses = new AtomicInteger();
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentRuntimeRequest.Kind.COMPACTION,
                new AgentIdentity("tenant_test", "thread_1", "compaction_" + "a".repeat(64), "user_test"),
                Optional.empty(), Map.of(), CancellationToken.none());
        TurnRuntimeRequest jaRequest = new TurnRuntimeRequest("thread_1", null, Path.of("C:/workspace"), "ws_test",
                "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, TurnOrigin.USER, REQUESTED_AT);
        RuntimeLease lease = runtimeLease(List.of(jaTool("ja_read")), token -> {
            throw new AssertionError("compaction must not open an MCP session");
        }, leaseCloses);
        try (AgentRequestRuntime resolved = resolver(lease,
                new JaRequestBindingSource.Binding(jaRequest, AgentStoreTransactionParticipant.NONE))
                .resolve(request, emptyExtensions(), Optional.empty())) {
            assertTrue(resolved.bindingFingerprint().matches("[0-9a-f]{64}"));
            assertTrue(resolved.extensions().tools().isEmpty());
            assertSame(AgentStoreTransactionParticipant.NONE, resolved.transactionParticipant());
        }
        assertEquals(1, leaseCloses.get());
    }

    /** Core 扩展、Ja 工具与 MCP 工具按确定顺序合并，取消令牌及事务参与者透传且只释放一次。 */
    @Test
    void resolvesFrozenRuntimeAndReleasesMcpAndConfigurationOnce() {
        AtomicInteger leaseCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        AtomicReference<CancellationToken> openedWith = new AtomicReference<>();
        AgentRuntimeRequest coreRequest = coreRequest();
        TurnRuntimeRequest jaRequest = turnRequest();
        AgentStoreTransactionParticipant participant = transaction -> { };
        ExtensionRegistry registry = new ExtensionRegistry().registerTool(coreTool("core_read"));
        ExtensionRegistry.Snapshot base = registry.freeze();
        RuntimeLease lease = runtimeLease(List.of(jaTool("ja_read")), token -> {
            openedWith.set(token);
            return session(List.of(jaTool("mcp_read")), sessionCloses);
        }, leaseCloses);
        AgentRequestRuntime resolved = resolver(lease,
                new JaRequestBindingSource.Binding(jaRequest, participant)).resolve(
                coreRequest, base, Optional.empty());

        assertEquals(List.of("core_read", "ja_read", "mcp_read"),
                resolved.extensions().tools().stream().map(tool -> tool.spec().name()).toList());
        assertEquals(Set.of("ja.request-tools"), resolved.extensions().extensionIds());
        assertEquals(List.of("ja.tool-visibility", "ja.tool-approval", "ja.clarification-wait", "ja.turn"),
                resolved.extensions().middleware().stream()
                .map(ExtensionRegistry.MiddlewareRegistration::namespace).toList());
        assertSame(coreRequest.cancellationToken(), openedWith.get());
        assertSame(participant, resolved.transactionParticipant());
        assertTrue(resolved.bindingFingerprint().matches("[0-9a-f]{64}"));
        resolved.close();
        resolved.close();
        assertEquals(1, sessionCloses.get());
        assertEquals(1, leaseCloses.get());
    }

    /** 请求级审批中间件只为最终需要审批的 Ja 调用生成带既有五分钟期限的 Wait。 */
    @Test
    void createsAnExplicitApprovalWaitForApprovalRequiredJaTools() {
        AgentRuntimeRequest coreRequest = coreRequest();
        RuntimeLease lease = runtimeLease(List.of(jaTool("ja_read")),
                token -> session(List.of(), new AtomicInteger()), new AtomicInteger());
        try (AgentRequestRuntime resolved = resolver(lease, new JaRequestBindingSource.Binding(
                turnRequest(), AgentStoreTransactionParticipant.NONE)).resolve(
                coreRequest, emptyExtensions(), Optional.empty())) {
            ExtensionRegistry.MiddlewareRegistration registration = resolved.extensions().middleware().stream()
                    .filter(value -> value.namespace().equals("ja.tool-approval")).findFirst().orElseThrow();
            AgentTool.Invocation invocation = new AgentTool.Invocation("call_wait", "ja_read", JsonObject.empty(), 0);
            AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(coreRequest.identity(),
                    coreRequest.identity().toolCallKey(invocation.callId()), Map.of());
            AgentMiddleware.ToolContext context = new AgentMiddleware.ToolContext(coreRequest.identity(), execution,
                    invocation, ToolSideEffect.READ_ONLY, AgentTool.ApprovalRequirement.REQUIRED,
                    AgentMiddleware.ExtensionStateView.empty(registration.namespace(),
                            registration.middleware().stateSchemaVersion()));

            AgentMiddleware.ToolWait wait = assertInstanceOf(AgentMiddleware.ToolWait.class,
                    registration.middleware().beforeTool(context).decision());

            assertEquals("approval", wait.request().kind());
            assertEquals(REQUESTED_AT.plus(Duration.ofMinutes(5)),
                    Instant.parse(((JsonText) wait.request().payload().get("expiresAt")).value()));

            AgentMiddleware.ToolContext notRequired = new AgentMiddleware.ToolContext(coreRequest.identity(), execution,
                    invocation, ToolSideEffect.READ_ONLY, AgentTool.ApprovalRequirement.NOT_REQUIRED,
                    AgentMiddleware.ExtensionStateView.empty(registration.namespace(),
                            registration.middleware().stateSchemaVersion()));
            assertInstanceOf(AgentMiddleware.ToolProceed.class,
                    registration.middleware().beforeTool(notRequired).decision());
        }
    }

    /** 请求补丁只隐藏 Ja 目录中不可见项，并保留 Core 与当前 MCP 工具。 */
    @Test
    void narrowsOnlyJaToolsUsingFrozenVisibleNames() {
        AtomicInteger leaseCloses = new AtomicInteger();
        AgentRuntimeRequest coreRequest = coreRequest();
        ExtensionRegistry registry = new ExtensionRegistry().registerTool(coreTool("core_read"));
        RuntimeLease lease = runtimeLease(List.of(jaTool("ja_visible"), jaTool("ja_hidden")),
                Set.of("ja_visible"), token -> session(List.of(jaTool("mcp_read")), new AtomicInteger()),
                leaseCloses);
        AgentRequestRuntime resolved = resolver(lease,
                new JaRequestBindingSource.Binding(turnRequest(), AgentStoreTransactionParticipant.NONE))
                .resolve(coreRequest, registry.freeze(), Optional.empty());
        ExtensionRegistry.MiddlewareRegistration registration = resolved.extensions().middleware().stream()
                .filter(value -> value.namespace().equals("ja.tool-visibility")).findFirst().orElseThrow();
        AgentMiddleware.RequestContext context = new AgentMiddleware.RequestContext(coreRequest.identity(), "",
                List.of(), resolved.extensions().tools().stream().map(AgentTool::spec).toList(),
                AgentMiddleware.ExtensionStateView.empty("ja.tool-visibility", registration.stateSchemaVersion()));

        Set<String> visible = registration.middleware().beforeRequest(context).decision().visibleToolNames()
                .orElseThrow();
        assertEquals(Set.of("core_read", "ja_visible", "mcp_read"), visible);
        resolved.close();
        assertEquals(1, leaseCloses.get());
    }

    /** 工具绑定必须用 Ja 权限事实重算指纹，Core 提供的权限摘要不能冒充 Ja route。 */
    @Test
    void bindsToolRouteUsingResolvedJaAccessMode() {
        AgentRuntimeRequest coreRequest = coreRequest();
        RuntimeLease lease = runtimeLease(List.of(jaTool("ja_read")), token -> session(List.of(), new AtomicInteger()),
                new AtomicInteger());
        try (AgentRequestRuntime resolved = resolver(lease, new JaRequestBindingSource.Binding(turnRequest(),
                AgentStoreTransactionParticipant.NONE)).resolve(coreRequest, emptyExtensions(), Optional.empty())) {
            AgentTool adapted = resolved.extensions().tools().getFirst();
            AgentTool.Invocation invocation = new AgentTool.Invocation(
                    "call_1", "ja_read", JsonObject.empty(), 0);
            AgentTool.ToolBindingContext bindingContext = new AgentTool.ToolBindingContext(
                    "a".repeat(64), "b".repeat(64));

            JsonObject route = adapted.executionBinding(invocation, bindingContext).route();
            assertEquals("APPROVAL_REQUIRED", assertInstanceOf(JsonText.class, route.get("accessMode")).value());
            assertEquals(bindingContext.catalogFingerprint(),
                    adapted.executionBinding(invocation, bindingContext).catalogFingerprint());
            assertNotEquals(bindingContext.permissionFingerprint(),
                    adapted.executionBinding(invocation, bindingContext).permissionFingerprint());
        }
    }

    /** 旧准入时间保持不变，但恢复后的工具 IO 必须取得当前窗口，不能沿用已过期的整轮期限。 */
    @Test
    void restoredAdmissionDoesNotExpireToolIoWindow() {
        AtomicReference<JaTool.ExecutionContext> observed = new AtomicReference<>();
        Instant resumedAt = REQUESTED_AT.plus(Duration.ofHours(2));
        RuntimeLease lease = runtimeLease(List.of(contextTool(observed)),
                token -> session(List.of(), new AtomicInteger()), new AtomicInteger());
        AgentRuntimeRequest request = coreRequest();
        try (AgentRequestRuntime resolved = resolver(lease,
                new JaRequestBindingSource.Binding(turnRequest(), AgentStoreTransactionParticipant.NONE),
                Clock.fixed(resumedAt, ZoneOffset.UTC)).resolve(request, emptyExtensions(), Optional.empty())) {
            executeContextTool(resolved, request, "call_resumed");
            assertEquals(resumedAt.plus(lease.limits().requestWindow()), observed.get().deadline());
            assertEquals(request.identity(), observed.get().identity());
            assertEquals("ws_test", observed.get().workspaceId());
        }
    }

    /** 模型生成、审批或前序工具可以跨过窗口；同一冻结运行时的下次派发仍有独立且有界的 IO 期限。 */
    @Test
    void delayedToolsRenewIoWindowWithoutChangingFrozenIdentity() {
        AtomicReference<JaTool.ExecutionContext> observed = new AtomicReference<>();
        AtomicReference<Instant> now = new AtomicReference<>(REQUESTED_AT);
        Clock advancingClock = new Clock() {
            /** 测试始终用 UTC，避免墙钟推进断言依赖机器时区。 */
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            /** 此 fixture 不改变时区；调用方只消费 instant。 */
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            /** 显式推进逻辑时钟，避免 sleep 让 deadline 回归变成时序测试。 */
            @Override public Instant instant() { return now.get(); }
        };
        RuntimeLease lease = runtimeLease(List.of(contextTool(observed)),
                token -> session(List.of(), new AtomicInteger()), new AtomicInteger());
        AgentRuntimeRequest request = coreRequest();
        try (AgentRequestRuntime resolved = resolver(lease,
                new JaRequestBindingSource.Binding(turnRequest(), AgentStoreTransactionParticipant.NONE),
                advancingClock).resolve(request, emptyExtensions(), Optional.empty())) {
            executeContextTool(resolved, request, "call_first");
            JaTool.ExecutionContext first = observed.get();
            now.set(REQUESTED_AT.plus(Duration.ofHours(1)));
            executeContextTool(resolved, request, "call_delayed");
            JaTool.ExecutionContext delayed = observed.get();
            assertEquals(now.get().plus(lease.limits().requestWindow()), delayed.deadline());
            assertEquals(first.identity(), delayed.identity());
            assertEquals(first.configGeneration(), delayed.configGeneration());
            assertEquals(first.accessMode(), delayed.accessMode());
            assertEquals(request.identity().toolCallKey("call_delayed"), delayed.callKey());
        }
    }

    /** 经真实 Ja 适配器派发，避免只测 deadline 算式而漏掉上下文工厂与 Core 调用的接线。 */
    private static void executeContextTool(AgentRequestRuntime runtime, AgentRuntimeRequest request, String callId) {
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, "read", JsonObject.empty(), 0);
        AgentTool.ExecutionContext context = new AgentTool.ExecutionContext(request.identity(),
                request.identity().toolCallKey(callId), Map.of());
        AgentTool.ExecutionResult result = runtime.extensions().tools().getFirst()
                .execute(invocation, context, CancellationToken.none()).toCompletableFuture().join();
        assertEquals(ToolOutcome.SUCCEEDED, result.result().outcome());
    }

    /** 只读 fixture 捕获实际执行上下文；不写文件或使用假的租约成功掩盖时间绑定错误。 */
    private static JaTool contextTool(AtomicReference<JaTool.ExecutionContext> observed) {
        return new JaTool() {
            /** 使用真实基础工具名，让结果投影经过生产 read 分支。 */
            @Override public ToolSpec spec() { return new ToolSpec("read", "Read fixture", JsonObject.empty()); }
            /** 截止时间测试不申请 workspace 写锁，隔离时间绑定与持久 claim 状态机。 */
            @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }
            /** 明确无文件副作用，不沿用未知工具默认的不可观察写入声明。 */
            @Override public WorkspaceMutationMode workspaceMutationMode(AgentTool.Invocation invocation) {
                return WorkspaceMutationMode.NONE;
            }
            /** 保存适配器派生的上下文并返回真实成功结果。 */
            @Override
            public java.util.concurrent.CompletionStage<JaTool.ToolResult> execute(
                    AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                    CancellationToken cancellationToken) {
                observed.set(context);
                return CompletableFuture.completedFuture(new JaTool.ToolResult(
                        ToolOutcome.SUCCEEDED, "fixture", Optional.empty(), null));
            }
        };
    }

    /** 同名工具不得覆盖 Core、Ja 或 MCP 既有路由，且冲突时两项请求资源都必须归还。 */
    @Test
    void rejectsDuplicateToolNamesAndClosesPartialRuntime() {
        AtomicInteger leaseCloses = new AtomicInteger();
        AtomicInteger sessionCloses = new AtomicInteger();
        ExtensionRegistry registry = new ExtensionRegistry().registerTool(coreTool("collision"));
        RuntimeLease lease = runtimeLease(List.of(jaTool("collision")), token ->
                session(List.of(), sessionCloses), leaseCloses);
        JaAgentRuntimeResolver resolver = resolver(lease,
                new JaRequestBindingSource.Binding(turnRequest(), AgentStoreTransactionParticipant.NONE));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(coreRequest(), registry.freeze(), Optional.empty()));
        assertEquals(1, sessionCloses.get());
        assertEquals(1, leaseCloses.get());
    }

    /** 只有 Plan 执行来源可以携带 Plan run 身份，避免 Tool 上下文从 Thread 类型猜身份。 */
    @Test
    void validatesPlanRuntimeIdentityShape() {
        assertThrows(IllegalArgumentException.class, () -> new TurnRuntimeRequest(
                "thread_1", "turn_1", Path.of("C:/workspace"), "ws_test", "provider_test", "model_test",
                null, AccessMode.APPROVAL_REQUIRED, CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION,
                REQUESTED_AT));
        TurnRuntimeRequest plan = new TurnRuntimeRequest("thread_1", "turn_1", Path.of("C:/workspace"),
                "ws_test", "provider_test", "model_test", null, AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, TurnOrigin.PLAN_EXECUTION, "plan_revision_1", "run_1",
                REQUESTED_AT, null, java.util.Optional.empty());
        assertEquals("run_1", plan.runId());
    }

    /** 恢复必须匹配已保存的 Provider 协议和上游型号，不能仅用当前 provider/model ID 静默切换。 */
    @Test
    void rejectsRecoveredModelProtocolDrift() {
        AtomicInteger leaseCloses = new AtomicInteger();
        RuntimeLease lease = runtimeLease(List.of(), token -> session(List.of(), new AtomicInteger()), leaseCloses);
        JaAgentRuntimeResolver resolver = resolver(lease,
                new JaRequestBindingSource.Binding(turnRequest(), AgentStoreTransactionParticipant.NONE));
        AgentRuntimeRequest request = new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT,
                new AgentIdentity("tenant_test", "thread_1", "turn_1", "user_test"), Optional.empty(),
                Map.of(), CancellationToken.none(), Optional.of(new ModelSelection("provider_test", "model_test",
                        ModelPort.Api.ANTHROPIC_MESSAGES, "other-upstream-model")));

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(request, emptyExtensions(), Optional.empty()));
        assertEquals(1, leaseCloses.get());
    }

    /** 使用唯一业务租约并提供明确 Ja 配置 Binding，工具名称冲突测试也经过生产 bridge。 */
    private static JaAgentRuntimeResolver resolver(RuntimeLease runtime, JaRequestBindingSource.Binding binding) {
        return resolver(runtime, binding, Clock.fixed(REQUESTED_AT, ZoneOffset.UTC));
    }

    /** 注入每安全点时钟，准入事实与 IO 时间分别验证，不改变既有配置和权限 fixture。 */
    private static JaAgentRuntimeResolver resolver(
            RuntimeLease runtime, JaRequestBindingSource.Binding binding, Clock clock) {
        TurnRuntimeResolver resolver = new TurnRuntimeResolver() {
            /** 返回测试构造的冻结租约，不读取 Ja 配置文件。 */
            @Override public RuntimeLease resolve(TurnRuntimeRequest request) { return runtime; }
            /** 冲突测试不启动工作区资源。 */
            @Override public void prepareWorkspace(Path workspaceRoot) { }
        };
        ObjectMapper mapper = new ObjectMapper();
        return new JaAgentRuntimeResolver(resolver, request -> List.of(), modelPort(), (request, recovered) -> binding,
                new JaToolBindingCodec(mapper), WRITE_LEASES, new ToolPresentationCodec(mapper),
                new TurnChangeSetCodec(mapper),
                summaryBinding -> { throw new AssertionError("summary model is not requested in this test"); },
                List.of(), clock);
    }

    /** 工具适配器不会请求共享 workspace claim；若策略回归触发 claim，测试立即显露越界副作用。 */
    private static WorkspaceWriteLeaseCoordinator writeLeaseFixture() {
        ConversationWorkspaceWriteClaimPort claims = (ConversationWorkspaceWriteClaimPort) Proxy.newProxyInstance(
                ConversationWorkspaceWriteClaimPort.class.getClassLoader(), new Class<?>[]{ConversationWorkspaceWriteClaimPort.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("workspace write claim is not expected in resolver tests");
                });
        return new WorkspaceWriteLeaseCoordinator(claims, Clock.fixed(REQUESTED_AT, ZoneOffset.UTC), 1);
    }

    /** 构造绑定请求使用和核心 execution identity 一致的 Thread/Turn 身份。 */
    private static AgentRuntimeRequest coreRequest() {
        return new AgentRuntimeRequest(AgentRuntimeRequest.Kind.USER_INPUT,
                new AgentIdentity("tenant_test", "thread_1", "turn_1", "user_test"),
                Optional.empty(), Map.of(), CancellationToken.none());
    }

    /** 只构造与测试 Core identity 对应的 Ja 用户请求，避免测试依赖业务数据访问适配器。 */
    private static TurnRuntimeRequest turnRequest() {
        return new TurnRuntimeRequest("thread_1", "turn_1", Path.of("C:/workspace"), "ws_test",
                "provider_test", "model_test", "medium", AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, TurnOrigin.USER, REQUESTED_AT);
    }

    /** 返回无扩展的冻结 Engine 能力视图，测试聚焦请求级工具合并。 */
    private static ExtensionRegistry.Snapshot emptyExtensions() {
        return new ExtensionRegistry().freeze();
    }

    /** 组装带可计数释放及惰性 MCP 会话的 Ja 配置租约。 */
    private static RuntimeLease runtimeLease(List<JaTool> tools, TurnToolSessionFactory sessions,
                                             AtomicInteger leaseCloses) {
        Set<String> visibleToolNames = tools.stream().map(tool -> tool.spec().name()).collect(Collectors.toSet());
        return runtimeLease(tools, visibleToolNames, sessions, leaseCloses);
    }

    /** 测试可以显式冻结可见子集，同时仍保留完整目录供恢复和冲突检测。 */
    private static RuntimeLease runtimeLease(List<JaTool> tools, Set<String> visibleToolNames,
                                             TurnToolSessionFactory sessions, AtomicInteger leaseCloses) {
        return new RuntimeLease("cfg_test", modelConfiguration(), AccessMode.APPROVAL_REQUIRED,
                CollaborationMode.DEFAULT, new TurnLimits(8_192, 512, Duration.ofSeconds(30)), tools, visibleToolNames,
                sessions, new ToolProjectionLimits(1_024, 1_024),
                new FixedAgentPromptSession(ContextBudget.capabilities(8_192, 512, true)),
                request -> { throw new AssertionError("attachment access is not expected"); },
                List.of(), "c".repeat(64), "prompt_test", "medium", leaseCloses::incrementAndGet);
    }

    /** MCP 测试会话保留冻结目录，并计量唯一释放。 */
    private static TurnToolSessionFactory.Session session(List<JaTool> tools, AtomicInteger closes) {
        return new TurnToolSessionFactory.Session() {
            /** 返回同一请求中已经冻结的 Ja MCP 工具目录。 */
            @Override public List<JaTool> tools() { return List.copyOf(tools); }
            /** 记录 bridge 的资源归还次数。 */
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    /** 使用固定合法模型路由标识，不在测试中保存真实凭据。 */
    private static ModelPort.ModelConfiguration modelConfiguration() {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_RESPONSES, "fixture-model", URI.create("https://example.test/v1"),
                "placeholder-secret", Duration.ofSeconds(2), Duration.ofSeconds(20),
                Set.of(ModelPort.InputModality.TEXT),
                new ModelPort.GenerationOptions(null, null, 512, "medium"));
    }

    /** 未被用来发送请求的 Provider 端口显式失败，避免测试意外掩盖模型调用。 */
    private static ModelPort modelPort() {
        return new ModelPort() {
            /** 此夹具只验证 Runtime 绑定，不触发 token 估算；固定值避免引入 Provider 依赖。 */
            @Override
            public ModelPort.InputTokenEstimate estimateInputTokens(
                    ModelPort.ModelRequest request, CancellationToken cancellationToken) {
                return new ModelPort.InputTokenEstimate(0, 0, "0".repeat(64));
            }

            /** 本套件只验证 runtime 绑定，不允许触发 Provider 请求。 */
            @Override
            public java.util.concurrent.CompletionStage<ModelPort.ModelOutcome> start(
                    ModelPort.ModelRequest request, ModelEventSink eventSink, CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("model dispatch is not expected"));
            }
        };
    }

    /** 基础 Core 工具仅用来证明 Ja 请求工具以追加顺序进入同一冻结快照。 */
    private static AgentTool coreTool(String name) {
        return new AgentTool() {
            /** 返回测试的稳定 Core 工具契约。 */
            @Override public ToolSpec spec() { return new ToolSpec(name, "Core fixture", JsonObject.empty()); }
            /** 执行不会被调用的显式只读工具。 */
            @Override
            public java.util.concurrent.CompletionStage<ExecutionResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
                return CompletableFuture.failedFuture(new AssertionError("tool execution is not expected"));
            }
        };
    }

    /** 测试 Ja Tool 的最小只读实现，执行入口不属于 runtime resolver 验收范围。 */
    private static JaTool jaTool(String name) {
        return new JaTool() {
            /** 返回通过冻结 Adapter 发布的固定工具 Schema。 */
            @Override public ToolSpec spec() { return new ToolSpec(name, "Ja fixture", JsonObject.empty()); }
            /** 固定只读语义以隔离访问模式指纹断言。 */
            @Override public ToolSideEffect sideEffect() { return ToolSideEffect.READ_ONLY; }
            /** 未执行的测试工具通过失败 Stage 防止误触发副作用。 */
            @Override
            public java.util.concurrent.CompletionStage<JaTool.ToolResult> execute(
                    AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                    CancellationToken cancellationToken) {
                return CompletableFuture.completedFuture(new JaTool.ToolResult(
                        ToolOutcome.SUCCEEDED, "", Optional.empty(), null));
            }
        };
    }
}
