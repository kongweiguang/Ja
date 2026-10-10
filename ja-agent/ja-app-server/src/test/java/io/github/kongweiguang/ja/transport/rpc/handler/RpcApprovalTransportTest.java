// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;

import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ClientOperationReceipt;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.domain.model.TurnEvent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.PlanControl;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase.PlanTurnContext;
import io.github.kongweiguang.ja.plan.application.gateway.PlanRepositoryException;
import io.github.kongweiguang.ja.plan.domain.PlanModels.PlanStatus;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static io.github.kongweiguang.ja.conversation.testsupport.ConversationTestFixtures.preferences;

/** 通过真实 RPC 会话与 Writer 验证 Turn 启动边界和审批通知写出顺序。 */
final class RpcApprovalTransportTest {
    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** 提供 JA-RPC v2 Sidecar 边界要求的四个明确根目录。 */
    private static SidecarConfiguration testConfiguration() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-approval-test")
                .toAbsolutePath().normalize();
        return new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                root.resolve("run"), root.resolve("logs"));
    }

    /** 无效决策必须在 resolve 前以稳定错误拒绝；有效决策的 resolved 通知须先于响应完成 flush。 */
    @Test
    void approvalResolvedFlushesBeforeResponseWithoutWriterDeadlock() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ApprovalServices services = new ApprovalServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(),
                    mapper, CLOCK, writer, ignored -> services.bindings(),
                    TestConfigurationPorts.unavailable());
            session = current;
            services.session.set(current);
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            current.registerTurnNotificationContext("turn_test", "ws_test", "thr_test", 0);
            output.reset();

            current.publish(new TurnEvent.ApprovalRequested(
                    context("evt_requested", 3), "appr_test", "call_test", "shell",
                    new ToolPresentation(ToolPresentation.Kind.SHELL, "执行命令", ToolPresentation.Status.PENDING,
                            null, null, null, List.of(), List.of(), "pnpm test", "workspace",
                            null, null, null, null, false, null),
                    "run tests", NOW.plusSeconds(60))).get(2, TimeUnit.SECONDS);

            ObjectNode params = mapper.createObjectNode().put("approvalId", "appr_test")
                    .put("turnId", "turn_test").put("decision", "approve")
                    .put("expectedThreadRevision", 3)
                    .put("operationKey", "op_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            params.put("decision", "invalid_decision");
            JaRpcException invalidDecision = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(current).handle(
                            new RpcCommand(RpcMethod.APPROVAL_RESPOND, params)));
            assertEquals("INVALID_PARAMS", invalidDecision.errorCode());
            assertEquals(0, services.resolveCalls.get());
            assertEquals(0, services.resolvedEventAttempts.get());
            params.put("decision", "approve");
            CompletionStage<ObjectNode> result = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.APPROVAL_RESPOND, params));
            /*
             * 组合 Writer 确认而不是停在队列准入；断言随后立即检查字节流，
             * 若只等待响应回调，最终帧仍可能与检查竞争，即使 resolved 通知已完成 flush。
             */
            CompletionStage<Void> response = result.thenCompose(value ->
                    writer.response("c:approval", value));
            response.toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(1, services.resolveCalls.get());
            assertEquals(1, services.resolvedEventAttempts.get());
            List<String> frames = output.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            assertEquals(3, frames.size());
            assertTrue(frames.get(0).contains("approval/requested"));
            assertTrue(frames.get(1).contains("approval/resolved"));
            assertTrue(frames.get(2).contains("\"id\":\"c:approval\""));
            ObjectNode requested = (ObjectNode) mapper.readTree(frames.get(0)).path("params");
            assertEquals("ws_test", requested.path("workspaceId").textValue());
            assertEquals("thr_test", requested.path("threadId").textValue());
            assertEquals("turn_test", requested.path("turnId").textValue());
            assertEquals(3, requested.path("threadRevision").longValue());
            assertTrue(requested.path("sequence").longValue() > 0);
            assertTrue(requested.path("generation").longValue() > 0);
            assertEquals("pnpm test", requested.path("presentation").path("command").textValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** 新连接没有收到 requested 通知时仍可依据持久用例回答，不能依赖连接本地 pending。 */
    @Test
    void reconnectAnswersWithoutConnectionLocalApproval() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApprovalServices services = new ApprovalServices();
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            services.session.set(session);
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("approvalId", "appr_test")
                    .put("turnId", "turn_test").put("decision", "approve")
                    .put("expectedThreadRevision", 3)
                    .put("operationKey", "op_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            ObjectNode result = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.APPROVAL_RESPOND, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertTrue(result.path("accepted").booleanValue());
            assertEquals(4, result.path("threadRevision").longValue());
        }
    }

    /** 审批持久层失败按稳定业务分类映射，防止过期、不存在与存储故障被误当成重复裁决。 */
    @Test
    void approvalFailuresMapToDistinctRpcErrors() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ApprovalServices services = new ApprovalServices();
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");

            ApprovalUseCase.Failure[] failures = {
                ApprovalUseCase.Failure.NOT_FOUND,
                ApprovalUseCase.Failure.EXPIRED,
                ApprovalUseCase.Failure.ALREADY_RESOLVED,
                ApprovalUseCase.Failure.CONFLICT,
                ApprovalUseCase.Failure.UNAVAILABLE
            };
            String[] errorCodes = {
                "APPROVAL_NOT_FOUND",
                "APPROVAL_EXPIRED",
                "APPROVAL_ALREADY_RESOLVED",
                "CONFLICT",
                "STORAGE_UNAVAILABLE"
            };
            for (int index = 0; index < failures.length; index++) {
                services.failure.set(failures[index]);
                ObjectNode params = mapper.createObjectNode().put("approvalId", "appr_test")
                        .put("turnId", "turn_test").put("decision", "approve")
                        .put("expectedThreadRevision", 3)
                        .put("operationKey", "op_" + "a".repeat(31) + Integer.toHexString(index));
                java.util.concurrent.CompletionException failure = assertThrows(
                        java.util.concurrent.CompletionException.class,
                        () -> new TurnApprovalHandler(session)
                                .handle(new RpcCommand(RpcMethod.APPROVAL_RESPOND, params))
                                .toCompletableFuture().join());
                assertTrue(failure.getCause() instanceof JaRpcException);
                assertEquals(errorCodes[index], ((JaRpcException) failure.getCause()).errorCode());
            }
        }
    }

    /** 队列容量允许重试，而内容超限需要修改输入，传输层必须保留两者不同语义。 */
    @Test
    void inputMutationCapacityAndContentFailuresKeepRetrySemantics() throws Exception {
        JaRpcException queueFailure = inputMutationFailure(TurnUseCase.InputMutationFailure.QUEUE_FULL);
        assertEquals("TURN_INPUT_QUEUE_FULL", queueFailure.errorCode());
        assertTrue(queueFailure.retryable());

        JaRpcException contentFailure = inputMutationFailure(TurnUseCase.InputMutationFailure.CONTENT_TOO_LARGE);
        assertEquals("CONTENT_TOO_LARGE", contentFailure.errorCode());
        assertFalse(contentFailure.retryable());
    }

    /** 经真实 Handler 和 RPC 会话触发闭集失败，验证领域到协议映射的重试契约。 */
    private static JaRpcException inputMutationFailure(TurnUseCase.InputMutationFailure failure) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CapturingTurns turns = new CapturingTurns(false, failure);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> RpcTestBindings.create(null, null, turns, null, null, null),
                     TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_test")
                    .put("inputId", "input_test").put("expectedInputRevision", 1);
            return assertThrows(JaRpcException.class, () -> new TurnApprovalHandler(session)
                    .handle(new RpcCommand(RpcMethod.TURN_INPUT_PRIORITIZE, params)));
        }
    }

    /**
     * 验证 transport 只把严格 Wire 参数映射为 TurnStartRequest；配置、Tool 与 RuntimeLease
     * 均不在 RPC 组合图中解析，从而由 TurnService 独占运行时冻结和释放职责。
     */
    @Test
    void turnStartDelegatesTransportFreeRequestToTurnUseCase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StartServices services = new StartServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(),
                    TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");

            ObjectNode params = mapper.createObjectNode()
                    .put("threadId", "thr_start")
                    .put("operationKey", "op_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            params.putArray("content").addObject().put("type", "text").put("text", "第一段\n第二段");

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_START, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            TurnStartRequest request = ((CapturingTurns) services.turns).request.get();
            assertEquals("thr_start", request.threadId());
            assertTrue(request.turnId().startsWith("turn_"));
            assertEquals("ws_start", request.workspaceId());
            assertEquals(services.workspaceRoot, request.workspaceRoot());
            assertEquals("第一段\n第二段", request.input());
            assertEquals(java.util.List.of(), request.attachmentIds());
            assertEquals("provider_start", request.providerId());
            assertEquals("model_start", request.modelId());
            assertEquals("medium", request.reasoningLevel());
            assertEquals(io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.APPROVAL_REQUIRED,
                    request.accessMode());
            assertEquals(7, request.expectedThreadRevision());
            assertEquals(0, request.initialTurnMutationVersion());
            assertEquals(NOW, request.requestedAt());
            assertEquals(request.turnId(), response.path("turnId").textValue());
            assertEquals(8, response.path("threadRevision").longValue());
            assertTrue(response.path("accepted").booleanValue());
            assertTrue(response.path("queued").booleanValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /**
     * 以 Composer 真窗使用的完整上下文顺序穿过 transport，锁定 Skill、文件、目录和正文的
     * 判别联合形状；测试不读取 Workspace，也不把引用值展开为正文。
     */
    @Test
    void turnStartAcceptsCanonicalComposerContextBlocks() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StartServices services = new StartServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(),
                    TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");

            ObjectNode params = mapper.createObjectNode().put("threadId", "thr_start")
                    .put("operationKey", "op_cccccccccccccccccccccccccccccccc");
            com.fasterxml.jackson.databind.node.ArrayNode content = params.putArray("content");
            content.addObject().put("type", "skill_reference").put("skillId", "user:composer-context");
            content.addObject().put("type", "workspace_reference").put("workspaceId", "ws_start")
                    .put("relativePath", "sample.ts").put("kind", "file");
            content.addObject().put("type", "workspace_reference").put("workspaceId", "ws_start")
                    .put("relativePath", "folder-fixture").put("kind", "directory");
            content.addObject().put("type", "text").put("text", "Composer 上下文首轮验收");
            assertDoesNotThrow(() -> RpcUserContent.parse(mapper.createArrayNode()
                    .add(content.get(0)).add(content.get(3))), "skill + text");
            assertDoesNotThrow(() -> RpcUserContent.parse(mapper.createArrayNode()
                    .add(content.get(1)).add(content.get(3))), "file + text");
            assertDoesNotThrow(() -> RpcUserContent.parse(mapper.createArrayNode()
                    .add(content.get(2)).add(content.get(3))), "directory + text");
            io.github.kongweiguang.ja.agent.domain.UserContent parsedContent = assertDoesNotThrow(
                    () -> RpcUserContent.parse(content), "full canonical content");
            assertDoesNotThrow(() -> new TurnStartRequest(
                    "thr_start", "turn_context", "ws_start", services.workspaceRoot,
                    parsedContent, "provider_start", "model_start", "medium",
                    io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode.APPROVAL_REQUIRED,
                    io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode.DEFAULT,
                    7, 0, NOW, Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false), "domain turn request");

            new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_START, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            TurnStartRequest request = ((CapturingTurns) services.turns).request.get();
            assertEquals(List.of("user:composer-context"), request.content().skillIds());
            assertEquals(List.of("sample.ts", "folder-fixture"), request.content().resourceReferences()
                    .stream().map(reference -> reference.resourceId()).toList());
            assertEquals("Composer 上下文首轮验收", request.input());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /**
     * Core AgentStore 的 CAS 冲突可发生在 transport 读取 Thread 后；turn/start 没有客户端 CAS 参数，
     * 因而必须在首次无副作用 admission 冲突后重读最新偏好，而不是把内部竞态暴露为随机失败。
     */
    @Test
    void turnStartRetriesOneAdmissionConflictAgainstFreshThreadRevision() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ThreadSummary first = new ThreadSummary(
                "thr_start", "ws_start", "占位标题", "project",
                preferences("provider_start", "model_start"),
                ThreadSummary.Status.ACTIVE, false, null, true, 7, NOW.minusSeconds(60), NOW);
        ThreadSummary refreshed = new ThreadSummary(
                "thr_start", "ws_start", "自动标题", "project",
                preferences("provider_latest", "model_latest"),
                ThreadSummary.Status.ACTIVE, false, null, true, 8, NOW.minusSeconds(60), NOW);
        RetryingTurns turns = new RetryingTurns();
        StartServices services = new StartServices(new SequencedStartThreads(first, refreshed), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("threadId", "thr_start")
                    .put("operationKey", "op_dddddddddddddddddddddddddddddddd");
            params.putArray("content").addObject().put("type", "text").put("text", "继续下一轮");

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_START, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(2, turns.attempts.get());
            assertEquals(8, turns.request.get().expectedThreadRevision());
            assertEquals("provider_latest", turns.request.get().providerId());
            assertEquals("model_latest", turns.request.get().modelId());
            assertEquals(9, response.path("threadRevision").longValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** 确认 Core Store 连续两次 CAS 冲突后只重读一次，不在不稳定 Thread 上继续循环准入。 */
    @Test
    void turnStartStopsAfterOneCoreStoreConflictRetry() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ThreadSummary first = new ThreadSummary(
                "thr_start", "ws_start", "占位标题", "project",
                preferences("provider_start", "model_start"),
                ThreadSummary.Status.ACTIVE, false, null, true, 7, NOW.minusSeconds(60), NOW);
        ThreadSummary refreshed = new ThreadSummary(
                "thr_start", "ws_start", "自动标题", "project",
                preferences("provider_latest", "model_latest"),
                ThreadSummary.Status.ACTIVE, false, null, true, 8, NOW.minusSeconds(60), NOW);
        RetryingTurns turns = new RetryingTurns(2);
        StartServices services = new StartServices(new SequencedStartThreads(first, refreshed), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("threadId", "thr_start")
                    .put("operationKey", "op_eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
            params.putArray("content").addObject().put("type", "text").put("text", "继续下一轮");

            AgentStore.StoreConflictException failure = assertThrows(AgentStore.StoreConflictException.class,
                    () -> new TurnApprovalHandler(current).handle(
                            new RpcCommand(RpcMethod.TURN_START, params)));

            assertEquals("fixture admission conflict", failure.getMessage());
            assertEquals(2, turns.attempts.get());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /**
     * turn/resume 只接收原 Turn identity 与 Thread CAS；工作区和通知关联必须从权威投影恢复，
     * 不能要求 React 回传运行时快照或生成新的 Operation identity。
     */
    @Test
    void turnResumeDelegatesOriginalIdentityAndAuthoritativeNotificationContext() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StartServices services = new StartServices();
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode()
                    .put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_RESUME, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            CapturingTurns turns = (CapturingTurns) services.turns;
            assertEquals("turn_resume", turns.resumedTurn.get());
            assertEquals(7, turns.resumedRevision.get());
            assertEquals("turn_resume", response.path("turnId").textValue());
            assertEquals(8, response.path("threadRevision").longValue());
            assertTrue(response.path("accepted").booleanValue());
            assertTrue(response.path("queued").booleanValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** Transport 预检发现 Turn/Thread revision 漂移时必须返回专用可重试顺序冲突，不能降级为通用 CONFLICT。 */
    @Test
    void turnResumePrecheckUsesStableOrderConflict() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StartServices services = new StartServices(6);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode()
                    .put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(current)
                            .handle(new RpcCommand(RpcMethod.TURN_RESUME, params)));

            assertEquals("TURN_RESUME_ORDER_CONFLICT", failure.errorCode());
            assertTrue(failure.retryable());
            assertNull(((CapturingTurns) services.turns).resumedTurn.get());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** 暂停的 Plan Turn 通过同一 run 恢复，ACK revision 来自恢复后的权威 Thread 投影。 */
    @Test
    void turnResumeRoutesPausedPlanTurnThroughPlanControl() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanControls plans = new PlanControls(planContext(PlanStatus.PAUSED), () -> { });
        StartServices services = new StartServices(plans.api);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            ObjectNode first = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_RESUME, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            ObjectNode replay = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_RESUME, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(first.path("accepted").booleanValue());
            assertTrue(first.path("queued").booleanValue());
            assertEquals(7, first.path("threadRevision").longValue());
            assertEquals(first, replay);
            assertEquals(2, plans.resumeCalls.get());
            assertEquals(plans.firstResumeControl.get(), plans.resumedControl.get());
            assertEquals("plan_route", plans.resumedControl.get().planId());
            assertEquals("run_route", plans.resumedControl.get().runId());
            assertEquals(4, plans.resumedControl.get().expectedPlanRevision());
            assertTrue(plans.resumedControl.get().operationKey().startsWith("op_"));
            assertNull(((CapturingTurns) services.turns).resumedTurn.get());
        }
    }

    /** 错绑 Plan Turn 不能回落为普通 Turn，且对客户端返回稳定的人工恢复错误。 */
    @Test
    void turnResumeMapsPersistedPlanBindingFailureToRecoveryRequired() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanRepositoryException invalidBinding = new PlanRepositoryException(
                PlanRepositoryException.Code.PLAN_RECOVERY_REQUIRED, "fixture binding mismatch");
        PlanControls plans = new PlanControls(planContext(PlanStatus.PAUSED), () -> { }, invalidBinding);
        StartServices services = new StartServices(plans.api);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(session).handle(new RpcCommand(RpcMethod.TURN_RESUME, params)));

            assertEquals("PLAN_RECOVERY_REQUIRED", failure.errorCode());
            assertEquals(0, plans.resumeCalls.get());
            assertNull(((CapturingTurns) services.turns).resumedTurn.get());
        }
    }

    /** 同一 run 的历史 Plan Turn binding 不得让已结束 Turn 重新注册为活动恢复通知目标。 */
    @Test
    void turnResumeRejectsTerminalHistoricalPlanTurns() throws Exception {
        for (String status : List.of("completed", "failed", "cancelled")) {
            ObjectMapper mapper = new ObjectMapper();
            PlanControls plans = new PlanControls(planContext(PlanStatus.EXECUTING), () -> { });
            StartServices services = new StartServices(plans.api, status);
            try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
                 RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                         ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
                session.initialize();
                markReady(session, "0123456789abcdef0123456789abcdef");
                ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                        .put("expectedThreadRevision", 7);

                JaRpcException failure = assertThrows(JaRpcException.class,
                        () -> new TurnApprovalHandler(session)
                                .handle(new RpcCommand(RpcMethod.TURN_RESUME, params)));

                assertEquals("TURN_NOT_RESUMABLE", failure.errorCode());
                assertEquals(0, plans.resumeCalls.get());
                assertNull(plans.resumedControl.get());
                assertNull(((CapturingTurns) services.turns).resumedTurn.get());
            }
        }
    }

    /** 正在执行的 Plan 只恢复观察 ACK，不重新准入 Plan 或普通 Turn。 */
    @Test
    void turnResumeDoesNotReadmitAnExecutingPlanTurn() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanControls plans = new PlanControls(planContext(PlanStatus.EXECUTING), () -> { });
        StartServices services = new StartServices(plans.api);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            ObjectNode first = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_RESUME, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            ObjectNode replay = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_RESUME, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(first, replay);
            assertEquals(0, plans.resumeCalls.get());
            assertNull(plans.resumedControl.get());
            assertNull(((CapturingTurns) services.turns).resumedTurn.get());
        }
    }

    /** Plan revision 已变化时必须先拒绝 Thread CAS，不触碰 Plan resume admission。 */
    @Test
    void turnResumeChecksThreadRevisionBeforePlanMutation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanControls plans = new PlanControls(planContext(PlanStatus.PAUSED), () -> { });
        StartServices services = new StartServices(6, plans.api);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("expectedThreadRevision", 7);

            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(session).handle(new RpcCommand(RpcMethod.TURN_RESUME, params)));

            assertEquals("TURN_RESUME_ORDER_CONFLICT", failure.errorCode());
            assertEquals(0, plans.resumeCalls.get());
            assertNull(plans.resumedControl.get());
        }
    }

    /** Plan stop 后必须回读真实 Turn 终态；重复停止只读取该终态且不再次写 Plan。 */
    @Test
    void turnCancelStopsPlanRunAndReplaysTerminalTurnWithoutSecondStop() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<String> turnStatus = new AtomicReference<>("running");
        PlanControls plans = new PlanControls(planContext(PlanStatus.EXECUTING),
                () -> turnStatus.set("cancelled"));
        StartServices services = new StartServices(plans.api, turnStatus);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume");

            ObjectNode stopped = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_CANCEL, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);
            ObjectNode replay = new TurnApprovalHandler(session).handle(
                    new RpcCommand(RpcMethod.TURN_CANCEL, params)).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(stopped.path("accepted").booleanValue());
            assertEquals("cancelled", stopped.path("status").textValue());
            assertFalse(replay.path("accepted").booleanValue());
            assertEquals("cancelled", replay.path("status").textValue());
            assertEquals(1, plans.stopCalls.get());
            assertEquals("plan_route", plans.stoppedControl.get().planId());
            assertEquals("run_route", plans.stoppedControl.get().runId());
        }
    }

    /** Plan stop 若未收口内部 Turn，transport 必须失败关闭并保留可核实的未终态事实。 */
    @Test
    void turnCancelFailsClosedWhenStoppedPlanTurnIsNotTerminal() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PlanControls plans = new PlanControls(planContext(PlanStatus.EXECUTING), () -> { });
        StartServices services = new StartServices(plans.api);
        try (StdioWriter writer = new StdioWriter(new ByteArrayOutputStream(), mapper, 4 * 1024 * 1024);
             RpcSession session = new RpcSession(testConfiguration(), mapper, CLOCK, writer,
                     ignored -> services.bindings(), TestConfigurationPorts.unavailable())) {
            session.initialize();
            markReady(session, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume");

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> new TurnApprovalHandler(session).handle(new RpcCommand(RpcMethod.TURN_CANCEL, params)));

            assertEquals("Stopped Plan Turn has not reached a terminal state", failure.getMessage());
            assertEquals(1, plans.stopCalls.get());
        }
    }

    /**
     * 恢复裁决拒绝违反合同字符集的 operationKey；有效裁决使用提交所得的新 revision 立即复用唯一
     * turn/resume 入口，React 只接收最终事件，不自行猜测何时应重新启动 Turn。
     */
    @Test
    void toolRecoveryDecisionResumesAfterLastPendingItem() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingTurns turns = new CapturingTurns();
        StartServices services = new StartServices(
                new StartThreads(recoveryThread(8), 8), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("callId", "call_recovery").put("expectedThreadRevision", 7)
                    .put("expectedRecoveryRevision", 1).put("decision", "skip")
                    .put("operationKey", "op_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

            ObjectNode invalidParams = params.deepCopy().put("operationKey", "recovery key");
            JaRpcException invalidKey = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(current)
                            .handle(new RpcCommand(RpcMethod.TURN_RECOVERY_RESPOND, invalidParams)));
            assertEquals("INVALID_PARAMS", invalidKey.errorCode());
            assertTrue(turns.recoveryRequest.get() == null);

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_RECOVERY_RESPOND, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals("turn_resume", turns.recoveryRequest.get().turnId());
            assertEquals(TurnUseCase.ToolRecoveryDisposition.SKIP, turns.recoveryRequest.get().disposition());
            assertEquals("turn_resume", turns.resumedTurn.get());
            assertEquals(8, turns.resumedRevision.get());
            assertTrue(response.path("accepted").booleanValue());
            assertTrue(response.path("resumed").booleanValue());
            assertEquals(8, response.path("threadRevision").longValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /**
     * 多项未知 Tool 必须按原顺序逐项处理：当前裁决已经提交时，下一项仍未知只保留 suspended 状态，
     * 不能把正常等待伪装成失败或要求用户再点击一次全局“继续运行”。
     */
    @Test
    void toolRecoveryDecisionWaitsWhenAnotherItemRemainsUnknown() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CapturingTurns turns = new CapturingTurns(true);
        StartServices services = new StartServices(
                new StartThreads(recoveryThread(8), 8), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            ObjectNode params = mapper.createObjectNode().put("turnId", "turn_resume")
                    .put("callId", "call_first_recovery").put("expectedThreadRevision", 7)
                    .put("expectedRecoveryRevision", 1).put("decision", "retry")
                    .put("operationKey", "op_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_RECOVERY_RESPOND, params))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(TurnUseCase.ToolRecoveryDisposition.RETRY, turns.recoveryRequest.get().disposition());
            assertEquals("turn_resume", turns.resumedTurn.get());
            assertEquals(8, turns.resumedRevision.get());
            assertTrue(response.path("accepted").booleanValue());
            assertFalse(response.path("resumed").booleanValue());
            assertEquals(8, response.path("threadRevision").longValue());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** Java transport 必须接受合同上限 101 的 Turn ID，并在调用应用端口前拒绝 102 字符。 */
    @Test
    void turnCancelEnforcesCanonicalTurnIdLengthBoundary() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BoundaryCancellationTurns turns = new BoundaryCancellationTurns();
        StartServices services = new StartServices(new CancellationThreads(1, "RUNNING", false), turns);
        StdioWriter writer = new StdioWriter(output, mapper, 4 * 1024 * 1024);
        RpcSession session = null;
        try {
            RpcSession current = new RpcSession(
                    testConfiguration(), mapper, CLOCK, writer,
                    ignored -> services.bindings(), TestConfigurationPorts.unavailable());
            session = current;
            current.initialize();
            markReady(current, "0123456789abcdef0123456789abcdef");
            String maximum = "turn_" + "a".repeat(96);
            String oversized = "turn_" + "a".repeat(97);

            ObjectNode response = new TurnApprovalHandler(current)
                    .handle(new RpcCommand(RpcMethod.TURN_CANCEL,
                            mapper.createObjectNode().put("turnId", maximum)))
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            JaRpcException failure = assertThrows(JaRpcException.class,
                    () -> new TurnApprovalHandler(current).handle(new RpcCommand(
                            RpcMethod.TURN_CANCEL, mapper.createObjectNode().put("turnId", oversized))));

            assertEquals(101, maximum.length());
            assertEquals(maximum, turns.cancelledTurn.get());
            assertEquals(maximum, response.path("turnId").textValue());
            assertEquals(102, oversized.length());
            assertEquals("INVALID_PARAMS", failure.errorCode());
            assertEquals(1, turns.attempts.get());
        } finally {
            if (session != null) {
                session.close();
            }
            writer.close();
        }
    }

    /** 创建由审批请求与解决事实共享的不可变 revision 上下文。 */
    private static TurnEvent.Context context(String eventId, long revision) {
        return new TurnEvent.Context(eventId, "thr_test", "turn_test", revision, 0, NOW);
    }

    /** 构造已提交恢复裁决后的同一 Thread 快照，确保 resume 预检读取的是新 revision。 */
    private static ThreadSummary recoveryThread(long revision) {
        return new ThreadSummary("thr_start", "ws_start", "启动测试",
                "project", preferences("provider_start", "model_start"), ThreadSummary.Status.ACTIVE,
                false, null, true, revision, NOW.minusSeconds(60), NOW);
    }

    /** 为 Plan 分流夹具提供固定且完整的冻结 Run identity。 */
    private static PlanTurnContext planContext(PlanStatus status) {
        return new PlanTurnContext("plan_route", 4, status, "plan_revision_route", "run_route");
    }

    /** 恢复夹具必须同时提供权威 Turn 投影，供 transport 读取已完成模型轮次与 mutation fence。 */
    private static ThreadSnapshot.Turn recoveryTurn() {
        return new ThreadSnapshot.Turn("turn_resume", TurnState.SUSPENDED.name(),
                NOW.minusSeconds(60), NOW, null, null, null, 0, 0, null);
    }

    /** 仅提供 approval/respond 使用的应用投影，其余端口均以显式失败关闭。 */
    private static final class ApprovalServices {
        private final AtomicReference<RpcSession> session = new AtomicReference<>();
        private final AtomicReference<ApprovalUseCase.Failure> failure = new AtomicReference<>();
        private final AtomicInteger resolveCalls = new AtomicInteger();
        private final AtomicInteger resolvedEventAttempts = new AtomicInteger();
        private final ThreadUseCase history = new ApprovalHistory();
        private final ApprovalUseCase approvals = new ApprovalUseCase() {
            /** 记录 resolve 与事件尝试，使非法输入的零副作用边界可被直接断言。 */
            @Override
            public java.util.concurrent.CompletionStage<ApprovalUseCase.Resolution> resolve(
                    String threadId, String turnId, String approvalId, long expectedThreadRevision,
                    ApprovalDecision response, String operationKey, String requestFingerprint,
                    io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink sink) {
                resolveCalls.incrementAndGet();
                ApprovalUseCase.Failure configuredFailure = failure.get();
                if (configuredFailure != null) {
                    return CompletableFuture.failedFuture(new ApprovalUseCase.ApprovalException(configuredFailure));
                }
                resolvedEventAttempts.incrementAndGet();
                session.get().publish(new TurnEvent.ApprovalResolved(
                        context("evt_resolved", 4), approvalId, response));
                return java.util.concurrent.CompletableFuture.completedFuture(new ApprovalUseCase.Resolution(4));
            }

        };

        /** 只组合审批与 Turn 查找端口，其余能力由测试拒绝实现保护。 */
        private RpcServiceBindings bindings() {
            return RpcTestBindings.create(null, history, new CapturingTurns(), approvals, null, null);
        }

        /** 最小权威查询为 respond 提供准确的 Thread revision CAS 投影。 */
        private static final class ApprovalHistory implements ThreadUseCase {
            /** 审批响应夹具不允许创建 Thread。 */
            @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许列出 Thread。 */
            @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许搜索 Thread。 */
            @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw new UnsupportedOperationException(); }
            /** 重连从权威 Thread 恢复通知归属，不依赖旧连接缓存或已发送 requested。 */
            @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
                return Optional.of(new ThreadSnapshot(new ThreadSummary("thr_test", "ws_test", "审批测试",
                        "project", preferences("provider_test", "model_test"),
                        ThreadSummary.Status.ACTIVE, false, null, true, 3, NOW, NOW),
                        List.of(new ThreadSnapshot.Turn("turn_test", "WAITING", NOW, NOW,
                                null, null, null, 0, 0, null)), List.of(), null, null, null));
            }
            /** 审批响应夹具不允许重命名 Thread。 */
            @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许改写下一轮偏好。 */
            @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不运行自动标题竞争。 */
            @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不修改置顶状态。 */
            @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不确认 Thread 已读。 */
            @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许归档 Thread。 */
            @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许恢复 Thread。 */
            @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw new UnsupportedOperationException(); }
            /** 审批响应夹具不允许删除 Thread。 */
            @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw new UnsupportedOperationException(); }

            /** 返回响应关联与 CAS 使用的唯一 waiting_approval 快照。 */
            @Override
            public Optional<TurnSummary> findTurn(String turnId) {
                return Optional.of(new TurnSummary("thr_test", turnId, "waiting_approval", 3, false));
            }

            /** 审批响应不读取冻结文件差异，意外访问必须在夹具边界失败。 */
            @Override
            public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                    String threadId, String turnId, String artifactId, String filePath) {
                throw new UnsupportedOperationException();
            }

            /** 审批响应不物化历史正文；额外读取会改变本测试覆盖的最小查询边界。 */
            @Override
            public Optional<ItemContentPage> readItemContent(
                    String threadId, String itemId, int offsetCharacters, int limitCharacters) {
                throw new UnsupportedOperationException();
            }

            /** 审批回归只读取 Turn 审批投影，不读取历史 Tool 正文。 */
            @Override
            public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                               String artifactId, int offsetCharacters,
                                                               int limitCharacters) {
                throw new UnsupportedOperationException();
            }

            /** 审批响应夹具不读取 Session 列表。 */
            @Override
            public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
            /** 审批响应夹具不执行 Thread 发现。 */
            @Override
            public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
            /** 审批响应夹具不搜索用户输入。 */
            @Override
            public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
            /** 审批响应夹具不搜索 Session Thread。 */
            @Override
            public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
            /** 审批响应夹具不读取 Thread 最新页。 */
            @Override
            public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
            /** 审批响应夹具不提供用量投影。 */
            @Override
            public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }

        }
    }

    /** 组合 turn/start 唯一允许触达的工作区、Thread 与 Turn 入站端口。 */
    private static final class StartServices {
        private final Path workspaceRoot = Path.of(System.getProperty("java.io.tmpdir"), "ja-rpc-start-workspace")
                .toAbsolutePath().normalize();
        private final Workspace workspace = new Workspace(
                "ws_start", workspaceRoot, "启动测试", Workspace.Trust.TRUSTED,
                Workspace.Kind.PROJECT, 4);
        private final ThreadSummary thread = new ThreadSummary(
                "thr_start", "ws_start", "启动测试", "project",
                preferences("provider_start", "model_start"),
                ThreadSummary.Status.ACTIVE, false, null, true, 7, NOW.minusSeconds(60), NOW);
        private final ThreadUseCase threads;
        private final TurnUseCase turns;
        private final PlanUseCase plans;

        /** 默认夹具保持单次固定快照和捕获型 Turn 端口。 */
        private StartServices() {
            this.threads = new StartThreads(thread);
            this.turns = new CapturingTurns();
            this.plans = null;
        }

        /** 构造 revision 漂移夹具，使 Resume 在调用 Turn 用例前由 transport 稳定拒绝。 */
        private StartServices(long resumeTurnRevision) {
            this.threads = new StartThreads(thread, resumeTurnRevision);
            this.turns = new CapturingTurns();
            this.plans = null;
        }

        /** Plan resume CAS 回归固定相同 Thread snapshot 与指定 Plan 用例。 */
        private StartServices(long resumeTurnRevision, PlanUseCase plans) {
            this.threads = new StartThreads(thread, resumeTurnRevision);
            this.turns = new CapturingTurns();
            this.plans = plans;
        }

        /** Plan 路由回归使用规范 Thread fixture，并明确绑定 Plan 用例。 */
        private StartServices(PlanUseCase plans) {
            this.threads = new StartThreads(thread);
            this.turns = new CapturingTurns();
            this.plans = plans;
        }

        /** Plan resume 回归可模拟同一 run 中仍被绑定的历史终态 Turn。 */
        private StartServices(PlanUseCase plans, String turnStatus) {
            this.threads = new StartThreads(thread, thread.revision(), new AtomicReference<>(turnStatus));
            this.turns = new CapturingTurns();
            this.plans = plans;
        }

        /** Stop 回读测试共享唯一 Turn 状态，让 Plan 停止与持久 Thread 投影保持同一事实。 */
        private StartServices(PlanUseCase plans, AtomicReference<String> turnStatus) {
            this.threads = new StartThreads(thread, thread.revision(), turnStatus);
            this.turns = new CapturingTurns();
            this.plans = plans;
        }

        /** 允许竞态回归注入会推进 revision 的 Thread 投影与一次冲突的 Turn 端口。 */
        private StartServices(ThreadUseCase threads, TurnUseCase turns) {
            this(threads, turns, null);
        }

        /** Plan 分流 fixture 仅在显式提供时覆盖默认拒绝 Plan 端口。 */
        private StartServices(ThreadUseCase threads, TurnUseCase turns, PlanUseCase plans) {
            this.threads = threads;
            this.turns = turns;
            this.plans = plans;
        }

        /** 通过拒绝型测试工厂组合三个明确端口，不引入 Runtime Resolver 测试替身。 */
        private RpcServiceBindings bindings() {
            if (plans != null) {
                return RpcTestBindings.createWithPlans(new StartWorkspaces(workspace), threads,
                        turns, null, null, plans, null);
            }
            return RpcTestBindings.create(new StartWorkspaces(workspace), threads,
                    turns, null, null, null);
        }
    }

    /** 只实现 Turn 控制所需的 Plan 查询与 resume/stop，未配置的调用明确失败。 */
    private static final class PlanControls {
        private final AtomicReference<PlanTurnContext> context;
        private final AtomicReference<PlanControl> firstResumeControl = new AtomicReference<>();
        private final AtomicReference<PlanControl> resumedControl = new AtomicReference<>();
        private final AtomicReference<PlanControl> stoppedControl = new AtomicReference<>();
        private final AtomicInteger resumeCalls = new AtomicInteger();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private final RuntimeException planContextFailure;
        private final PlanUseCase api;

        /** 捕获控制命令并允许 Stop 后同步更新 fixture 的持久投影。 */
        private PlanControls(PlanTurnContext initial, Runnable afterStop) {
            this(initial, afterStop, null);
        }

        /** 为 binding 故障回归注入稳定的 Plan 恢复异常。 */
        private PlanControls(PlanTurnContext initial, Runnable afterStop, RuntimeException planContextFailure) {
            this.context = new AtomicReference<>(initial);
            this.planContextFailure = planContextFailure;
            this.api = (PlanUseCase) java.lang.reflect.Proxy.newProxyInstance(
                    PlanUseCase.class.getClassLoader(), new Class<?>[]{PlanUseCase.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "PlanControls";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> throw unsupported();
                            };
                        }
                        return switch (method.getName()) {
                            case "subscribePlan" -> (AutoCloseable) () -> { };
                            case "planTurnControlContext" -> {
                                if (planContextFailure != null) {
                                    throw planContextFailure;
                                }
                                PlanTurnContext selected = context.get();
                                yield selected != null && "thr_start".equals(args[0])
                                        && "turn_resume".equals(args[1])
                                        ? Optional.of(selected) : Optional.empty();
                            }
                            case "resumePlan" -> {
                                PlanControl control = (PlanControl) args[0];
                                firstResumeControl.compareAndSet(null, control);
                                resumedControl.set(control);
                                resumeCalls.incrementAndGet();
                                yield null;
                            }
                            case "stopPlan" -> {
                                PlanControl control = (PlanControl) args[0];
                                stoppedControl.set(control);
                                stopCalls.incrementAndGet();
                                PlanTurnContext current = context.get();
                                context.set(new PlanTurnContext(current.planId(), current.planRevision(),
                                        PlanStatus.STOPPED, current.planRevisionId(), current.runId()));
                                afterStop.run();
                                yield null;
                            }
                            default -> throw unsupported();
                        };
                    });
        }
    }

    /** 仅允许按已打开身份取得固定工作区，任何其他工作区操作均失败。 */
    private static final class StartWorkspaces implements WorkspaceUseCase {
        private final Workspace workspace;

        /** 固定预先打开的工作区，使测试不会产生文件系统副作用。 */
        private StartWorkspaces(Workspace workspace) {
            this.workspace = workspace;
        }

        /** 未声明工作区打开能力。 */
        @Override public Workspace openWorkspace(OpenWorkspace command) { throw unsupported(); }
        /** 未声明 session 创建能力。 */
        @Override public Workspace createSessionWorkspace(String threadId) { throw unsupported(); }
        /** 未声明 thread 创建补偿能力。 */
        @Override public void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明持久目录重开能力。 */
        @Override public Workspace openRegisteredWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明工作区列表能力。 */
        @Override public CursorPage<Workspace> listWorkspaces(String cursor, int limit, Workspace.Kind kind) { throw unsupported(); }
        /** 未声明可选工作区读取能力。 */
        @Override public Optional<Workspace> readWorkspace(String workspaceId) { throw unsupported(); }
        /** 启动夹具不覆盖 Workspace 元数据修改。 */
        @Override public Workspace updateWorkspace(UpdateWorkspace command) { throw unsupported(); }
        /** 固定工作区夹具不执行并发准入。 */
        @Override public io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort.Lease
                acquireAdmission(String workspaceId) {
            return io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort.noOpLease();
        }
        /** 仅返回与 Thread 关联且已经打开的工作区。 */
        @Override public Workspace requireOpenWorkspace(String workspaceId) {
            if (!workspace.workspaceId().equals(workspaceId)) {
                throw unsupported();
            }
            return workspace;
        }
        /** 未声明信任状态修改能力。 */
        @Override public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) { throw unsupported(); }
        /** 未声明工作区注销能力。 */
        @Override public void unregisterWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明预热刷新能力。 */
        @Override public void refreshPreparedWorkspaces() { throw unsupported(); }
    }

    /** 仅发布 turn/start 所需的固定 Thread 快照。 */
    private static final class StartThreads implements ThreadUseCase {
        private final ThreadSummary thread;
        private final long resumeTurnRevision;
        private final AtomicReference<String> turnStatus;

        /** 固定 Thread 权威投影，避免测试从 Wire 参数推导 Profile 或工作区。 */
        private StartThreads(ThreadSummary thread) {
            this(thread, thread.revision());
        }

        /** 允许恢复用例单独冻结 Turn revision，以验证双快照预检的一致错误映射。 */
        private StartThreads(ThreadSummary thread, long resumeTurnRevision) {
            this(thread, resumeTurnRevision, new AtomicReference<>("suspended"));
        }

        /** Plan 取消夹具与停止边界共享权威终态投影。 */
        private StartThreads(ThreadSummary thread, long resumeTurnRevision, AtomicReference<String> turnStatus) {
            this.thread = thread;
            this.resumeTurnRevision = resumeTurnRevision;
            this.turnStatus = turnStatus;
        }

        /** 未声明 Thread 创建能力。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 未声明 Thread 列表能力。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 未声明 Thread 搜索能力。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 返回启动请求引用的唯一 Thread 快照。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            return thread.threadId().equals(threadId)
                    ? Optional.of(new ThreadSnapshot(thread, List.of(recoveryTurn()), List.of(), null, null, null))
                    : Optional.empty();
        }
        /** 未声明 Thread 重命名能力。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明下一轮偏好修改能力。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 未声明自动标题写入能力。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明 Thread 置顶能力。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 未声明已读确认能力。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 未声明 Thread 归档能力。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明 Thread 恢复能力。 */
        @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明 Thread 删除能力。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 从共享状态读取唯一 Turn 投影，支持 Stop 后验证终态回读。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) {
            return "turn_resume".equals(turnId)
                    ? Optional.of(new TurnSummary(thread.threadId(), turnId, turnStatus.get(), resumeTurnRevision, false))
                    : Optional.empty();
        }
        /** 启动预检不读取冻结差异，保持此端口只暴露 Thread 与 Turn 投影。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** 启动预检不物化历史正文，意外调用应暴露超出 fixture 的数据访问。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** 启动预检不物化 Tool 输出正文。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                      String artifactId, int offsetCharacters,
                                                                      int limitCharacters) { throw unsupported(); }
        /** 启动预检不列出跨 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 启动预检不执行 Thread 发现。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
        /** 启动预检不搜索用户输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 启动预检不搜索 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 启动预检不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 启动预检不读取用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /** 捕获 transport 交付的纯启动意图，并返回不触发异步终态的接纳回执。 */
    private static final class CapturingTurns implements TurnUseCase {
        private final AtomicReference<TurnStartRequest> request = new AtomicReference<>();
        private final AtomicReference<String> resumedTurn = new AtomicReference<>();
        private final AtomicReference<Long> resumedRevision = new AtomicReference<>();
        private final AtomicReference<ToolRecoveryRequest> recoveryRequest = new AtomicReference<>();
        private final boolean recoveryRemainsPending;
        private final TurnUseCase.InputMutationFailure inputFailure;

        /** 启动夹具不接管隐藏 Plan Turn，意外准入必须显式失败。 */
        @Override
        public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                ConversationTurnEventSink sink,
                io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot inherited) {
            throw unsupported();
        }

        /** 默认夹具模拟全部未知项已经处理完毕；专用分支才返回下一项仍待裁决。 */
        private CapturingTurns() {
            this(false, null);
        }

        /** 明确区分 handler 尝试续跑和真正接纳成功，避免测试把多项未知误判成失败。 */
        private CapturingTurns(boolean recoveryRemainsPending) {
            this(recoveryRemainsPending, null);
        }

        /** 队列错误映射测试显式注入领域失败，保证其他能力仍沿用拒绝型夹具。 */
        private CapturingTurns(boolean recoveryRemainsPending, TurnUseCase.InputMutationFailure inputFailure) {
            this.recoveryRemainsPending = recoveryRemainsPending;
            this.inputFailure = inputFailure;
        }

        /** 保存唯一启动请求；未完成 Future 使测试专注准入边界而非执行生命周期。 */
        @Override
        public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink) {
            if (!this.request.compareAndSet(null, request)) {
                throw unsupported();
            }
            return new Accepted(request.threadId(), request.turnId(),
                    request.expectedThreadRevision() + 1, true, new CompletableFuture<>());
        }

        /** 传输映射夹具只观察一次新准入，持久幂等由 Repository 独立验证。 */
        @Override
        public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink,
                              String operationKey, String requestFingerprint) {
            return start(request, sink);
        }

        /** 无已提交回执，允许 Handler 进入本次新准入路径。 */
        @Override
        public Optional<ClientOperationReceipt>
        readClientOperation(String operationKey) {
            return Optional.empty();
        }

        /** 当前 transport 启动夹具不模拟失败问题恢复。 */
        @Override public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink) {
            throw unsupported();
        }

        /** 捕获夹具没有挂起恢复回调，清理请求保持显式空操作。 */
        @Override public void clearResumeContinuation(String turnId) { }

        /** 捕获夹具不执行恢复回调，注册动作保持无副作用。 */
        @Override public void registerResumeContinuation(String turnId,
                java.util.function.Consumer<CompletionStage<TurnResult>> continuation) { }

        /** 捕获夹具不模拟 Plan 暂停，避免传输测试启动隐藏状态机。 */
        @Override public java.util.concurrent.CompletionStage<Void> suspendPlanRun(String turnId) {
            throw unsupported();
        }

        /** 当前 transport 启动夹具不模拟问题编辑与路径切换。 */
        @Override public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink) {
            throw unsupported();
        }

        /** 捕获型 transport 夹具只持有其未完成回执，不代表 Engine 执行 owner。 */
        @Override public Optional<String> locallyActiveTurnId(String threadId) { return Optional.empty(); }
        /** 捕获型 transport 夹具不持有任何特定 Turn 的执行句柄。 */
        @Override public boolean locallyOwnsTurn(String threadId, String turnId) { return false; }

        /** 捕获原 Turn identity 与 CAS，证明 transport 没有生成新 Operation 或传递配置快照。 */
        @Override
        public Accepted resume(String turnId, long expectedThreadRevision, ConversationTurnEventSink sink) {
            if (!resumedTurn.compareAndSet(null, turnId)) {
                throw unsupported();
            }
            resumedRevision.set(expectedThreadRevision);
            if (recoveryRemainsPending) {
                throw TurnResumeException.of(ResumeFailure.RECOVERY_REQUIRED);
            }
            return new Accepted("thr_start", turnId, expectedThreadRevision + 1,
                    true, new CompletableFuture<>());
        }

        /** 返回与持久裁决匹配的新 revision；handler 是否继续 resume 由 changed 事实而非 UI 决定。 */
        @Override
        public ToolRecoveryResponse respondToolRecovery(ToolRecoveryRequest request) {
            if (!recoveryRequest.compareAndSet(null, request)) {
                throw unsupported();
            }
            return new ToolRecoveryResponse("thr_start", request.turnId(),
                    request.expectedThreadRevision() + 1, request.disposition(), true);
        }

        /** 仅在错误映射场景按配置失败，避免模拟未涉及的队列持久化行为。 */
        @Override
        public InputMutation prioritizeInput(String turnId, String inputId, long expectedInputRevision) {
            if (inputFailure == null) {
                throw unsupported();
            }
            throw TurnUseCase.InputMutationException.of(inputFailure);
        }

        /** 未声明取消能力。 */
        @Override public CancelResult cancel(String turnId) { throw unsupported(); }
        /** 关闭测试会话时停止准入，无需改变已捕获请求。 */
        @Override public void stopAccepting() { }
        /** 测试未登记运行 owner，原子关闭准入应成功。 */
        @Override public boolean stopAcceptingIfQuiescent() { return true; }
        /** 测试没有执行中 Turn，因此立即满足静默等待。 */
        @Override public boolean awaitQuiescence(Duration timeout) { return true; }
        /** 测试端口不持有资源，绝对期限关闭为空操作。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试端口不持有资源，普通关闭为空操作。 */
        @Override public void close() { }
    }

    /** 每次读取依次返回旧、新 Thread，使 admission 竞态无需 sleep 即可确定复现。 */
    private static final class SequencedStartThreads implements ThreadUseCase {
        private final ThreadSummary first;
        private final ThreadSummary refreshed;
        private final AtomicInteger reads = new AtomicInteger();

        /** 两个快照仅允许 revision 和下一轮偏好随权威提交推进。 */
        private SequencedStartThreads(ThreadSummary first, ThreadSummary refreshed) {
            this.first = first;
            this.refreshed = refreshed;
        }

        /** 首次返回读后即过期的投影，重试返回自动标题提交后的最新投影。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
            ThreadSummary selected = reads.getAndIncrement() == 0 ? first : refreshed;
            return Optional.of(new ThreadSnapshot(selected, List.of(), List.of(), null, null, null));
        }
        /** 竞态夹具不创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 竞态夹具不列出 Thread。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不搜索 Thread。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不重命名 Thread。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 竞态夹具不更新偏好。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 竞态夹具不写自动标题。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 竞态夹具不修改置顶状态。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 竞态夹具不确认已读。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 竞态夹具不归档。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 竞态夹具不恢复归档 Thread。 */
        @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 竞态夹具不删除。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 竞态夹具不读取 Turn。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
        /** 准入重试只重读 Thread 摘要，不接触已冻结文件产物。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** admission 竞态只读取 Thread 摘要，不物化历史 item 正文。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** admission 竞态不展开 Tool 输出 artifact。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                      String artifactId, int offsetCharacters,
                                                                      int limitCharacters) { throw unsupported(); }
        /** 竞态夹具不列出跨 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不执行 Thread 发现。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
        /** 竞态夹具不搜索用户输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不搜索 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 竞态夹具不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 竞态夹具不提供用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /** 仅提供 cancellation 重试所需的最新 Turn revision，其他 Thread 能力全部失败关闭。 */
    private static final class CancellationThreads implements ThreadUseCase {
        private final long revision;
        private final String status;
        private final boolean cancellationRequested;

        /** 固定权威 revision、状态与 cancel intent，使所有拒绝条件不依赖线程调度。 */
        private CancellationThreads(long revision, String status, boolean cancellationRequested) {
            this.revision = revision;
            this.status = status;
            this.cancellationRequested = cancellationRequested;
        }

        /** 返回唯一活动 Turn 的最新持久 revision。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) {
            return Optional.of(new TurnSummary(
                    "thr_cancel", turnId, status, revision, cancellationRequested));
        }
        /** 取消夹具不创建 Thread。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 取消夹具不列出 Thread。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不搜索 Thread。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不读取完整 Thread。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不重命名 Thread。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 取消夹具不更新偏好。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 取消夹具不写自动标题。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 取消夹具不修改置顶状态。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 取消夹具不确认已读。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 取消夹具不归档 Thread。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 取消夹具不恢复归档 Thread。 */
        @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 取消夹具不删除 Thread。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 取消重试只读取 Turn 状态，不读取冻结文件差异。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** 取消状态重试不读取历史正文，保持测试只覆盖 Turn 状态确认。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** 取消状态读取不展开 Tool 输出 artifact。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                      String artifactId, int offsetCharacters,
                                                                      int limitCharacters) { throw unsupported(); }
        /** 取消夹具不列出跨 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不执行 Thread 发现。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) { throw unsupported(); }
        /** 取消夹具不搜索用户输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不搜索 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 取消夹具不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 取消夹具不提供用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /** 只捕获一次合法取消调用，证明超长 identity 在 transport 层已被拒绝。 */
    private static final class BoundaryCancellationTurns implements TurnUseCase {

        /** 取消边界夹具不消费恢复回调，注册动作保持无副作用。 */
        @Override
        public void registerResumeContinuation(String turnId,
                java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
        }
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<String> cancelledTurn = new AtomicReference<>();

        /** 边界取消夹具不接管隐藏 Plan Turn 准入。 */
        @Override
        public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                ConversationTurnEventSink sink,
                io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot inherited) {
            throw unsupported();
        }

        /** 边界测试不接纳新 Turn。 */
        @Override public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink) { throw unsupported(); }
        /** 边界测试不触发隐藏继续。 */
        @Override public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink) { throw unsupported(); }
        /** 边界取消夹具不登记恢复回调，清理请求保持显式空操作。 */
        @Override public void clearResumeContinuation(String turnId) { }
        /** 边界夹具不模拟 Plan 暂停，避免引入未测试的异步状态。 */
        @Override public java.util.concurrent.CompletionStage<Void> suspendPlanRun(String turnId) { throw unsupported(); }
        /** 边界测试不编辑历史问题。 */
        @Override public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink) { throw unsupported(); }
        /** 边界取消夹具不持有本地执行 owner。 */
        @Override public Optional<String> locallyActiveTurnId(String threadId) { return Optional.empty(); }
        /** 边界取消夹具不持有任何特定 Turn 的执行句柄。 */
        @Override public boolean locallyOwnsTurn(String threadId, String turnId) { return false; }
        /** 记录 transport 已验证的 Turn identity；completion 仍提供 TurnService 权威终态屏障。 */
        @Override public CancelResult cancel(String turnId) {
            attempts.incrementAndGet();
            cancelledTurn.set(turnId);
            return new CancelResult(true, turnId, TurnState.CANCELLED, 2,
                    CompletableFuture.completedFuture(cancelledResult(turnId)));
        }
        /** 关闭测试会话时停止准入。 */
        @Override public void stopAccepting() { }
        /** 边界测试不持有运行 owner，原子关闭准入应成功。 */
        @Override public boolean stopAcceptingIfQuiescent() { return true; }
        /** 测试没有执行中 Turn，因此立即满足静默等待。 */
        @Override public boolean awaitQuiescence(Duration timeout) { return true; }
        /** 测试端口不持有资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试端口不持有资源。 */
        @Override public void close() { }
    }

    /** 事件 identity 独立于 Turn identity；使用有界 fixture ID，避免合法最长 turnId 污染 eventId。 */
    private static TurnResult cancelledResult(String turnId) {
        TurnEvent.Context context = new TurnEvent.Context("evt_cancel_result", "thr_test", turnId, 2, 0, NOW);
        TurnEvent.Terminal terminal = new TurnEvent.Terminal(context, TurnState.CANCELLED,
                "cancelled", null, null, null, null, null);
        return new TurnResult(TurnState.CANCELLED, terminal.summary(), terminal);
    }

    /** admission 按配置次数以 Core Store CAS 冲突失败，此后捕获完整重读请求。 */
    private static final class RetryingTurns implements TurnUseCase {

        /** 重试夹具由测试显式驱动，不在注册时启动额外异步工作。 */
        @Override
        public void registerResumeContinuation(String turnId,
                java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
        }
        private final int conflictCount;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicReference<TurnStartRequest> request = new AtomicReference<>();

        /** 重试夹具只验证用户 Turn 准入，不接管隐藏 Plan Turn。 */
        @Override
        public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                ConversationTurnEventSink sink,
                io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot inherited) {
            throw unsupported();
        }

        /** 默认只注入一次冲突，覆盖既有单次安全重试场景。 */
        private RetryingTurns() {
            this(1);
        }

        /** 配置 CAS 冲突次数，使测试能断言重试上限且不会启动额外 Turn。 */
        private RetryingTurns(int conflictCount) {
            this.conflictCount = conflictCount;
        }

        /** 配置次数内只在持久副作用前失败，避免误把已接纳 Turn 当成可安全重放请求。 */
        @Override public Accepted start(TurnStartRequest candidate, ConversationTurnEventSink sink) {
            if (attempts.incrementAndGet() <= conflictCount) {
                throw new AgentStore.StoreConflictException("fixture admission conflict");
            }
            request.set(candidate);
            return new Accepted(candidate.threadId(), candidate.turnId(),
                    candidate.expectedThreadRevision() + 1, true, new CompletableFuture<>());
        }
        /** 首次无副作用 CAS 冲突后仍复用同一客户端操作身份重试。 */
        @Override public Accepted start(TurnStartRequest candidate, ConversationTurnEventSink sink,
                                        String operationKey, String requestFingerprint) {
            return start(candidate, sink);
        }
        /** 存储成功前没有可重放回执。 */
        @Override public Optional<ClientOperationReceipt>
        readClientOperation(String operationKey) { return Optional.empty(); }
        /** 竞态夹具不模拟无用户消息的继续请求。 */
        @Override public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink) {
            throw unsupported();
        }
        /** 重试夹具没有恢复回调，清理请求保持显式空操作。 */
        @Override public void clearResumeContinuation(String turnId) { }
        /** 重试夹具不模拟 Plan 暂停，保持测试只覆盖 admission 冲突重试。 */
        @Override public java.util.concurrent.CompletionStage<Void> suspendPlanRun(String turnId) { throw unsupported(); }
        /** 竞态夹具不模拟历史路径切换。 */
        @Override public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink) {
            throw unsupported();
        }
        /** 重试夹具只观察 admission，不表示已启动的本地执行。 */
        @Override public Optional<String> locallyActiveTurnId(String threadId) { return Optional.empty(); }
        /** 重试夹具不持有任何特定 Turn 的执行句柄。 */
        @Override public boolean locallyOwnsTurn(String threadId, String turnId) { return false; }
        /** 竞态夹具不取消 Turn。 */
        @Override public CancelResult cancel(String turnId) { throw unsupported(); }
        /** 关闭测试会话时不再接纳。 */
        @Override public void stopAccepting() { }
        /** 重试夹具没有运行 owner，原子关闭准入应成功。 */
        @Override public boolean stopAcceptingIfQuiescent() { return true; }
        /** 没有执行中 Turn，立即静默。 */
        @Override public boolean awaitQuiescence(Duration timeout) { return true; }
        /** 测试端口无资源。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { }
        /** 测试端口无资源。 */
        @Override public void close() { }
    }

    /** 为测试中所有越界端口调用生成统一失败，避免误把空操作当作行为覆盖。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}
