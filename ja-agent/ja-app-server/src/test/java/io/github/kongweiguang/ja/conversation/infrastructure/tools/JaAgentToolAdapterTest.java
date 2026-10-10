// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaToolFactContributor;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationWorkspaceWriteClaimPort;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Ja 事实转换与真实请求权限绑定不会被 Core 摘要或工具载荷绕过。 */
final class JaAgentToolAdapterTest {
    private static final AtomicLong CLAIM_SEQUENCE = new AtomicLong();
    private static final Map<String, ConversationWorkspaceWriteClaimPort.WriteClaim> CLAIMS = new ConcurrentHashMap<>();
    private static final WorkspaceWriteLeaseCoordinator WRITE_LEASES = writeLeaseFixture();

    @TempDir
    Path temporary;

    /** 释放测试共用的调度资源，避免测试越界启动实际 Workspace 写操作。 */
    @AfterAll
    static void closeWriteLeaseFixture() {
        WRITE_LEASES.close();
    }

    /** 相同工具目录在不同实际访问模式下必须产生不同且可读的授权绑定。 */
    @Test
    void bindsTheActualAccessModeIntoPermissionIdentity() {
        JaTool delegate = tool((invocation, context, token) -> CompletableFuture.completedFuture(success()));
        JaToolBindingCodec codec = new JaToolBindingCodec(new ObjectMapper());
        AgentTool.Invocation invocation = invocation("call_binding");
        AgentTool.ToolBindingContext context = new AgentTool.ToolBindingContext("a".repeat(64), "b".repeat(64));
        JaTool.ExecutionContextFactory factory = (call, core) -> appContext(call, core, temporary);

        ToolExecutionBinding approval = new JaAgentToolAdapter(delegate,
                dependencies(factory, AccessMode.APPROVAL_REQUIRED, codec)).executionBinding(invocation, context);
        ToolExecutionBinding full = new JaAgentToolAdapter(delegate,
                dependencies(factory, AccessMode.FULL_ACCESS, codec)).executionBinding(invocation, context);

        assertEquals("APPROVAL_REQUIRED", ((JsonText) approval.route().get("accessMode")).value());
        assertEquals("FULL_ACCESS", ((JsonText) full.route().get("accessMode")).value());
        assertNotEquals(approval.permissionFingerprint(), full.permissionFingerprint());
    }

    /** 把会话模式映射到 Core 最终审批要求，FULL_ACCESS 仍不跳过工具自己的安全校验。 */
    @Test
    void mapsSessionAccessModeToCoreApprovalRequirement() {
        JaTool delegate = tool((invocation, context, token) -> CompletableFuture.completedFuture(success()));
        AgentTool.Invocation invocation = invocation("call_approval_mode");
        JaToolBindingCodec codec = new JaToolBindingCodec(new ObjectMapper());
        JaTool.ExecutionContextFactory factory = (call, core) -> appContext(call, core, temporary);

        AgentTool.ApprovalRequirement approvalRequired = new JaAgentToolAdapter(delegate,
                dependencies(factory, AccessMode.APPROVAL_REQUIRED, codec)).approvalRequirement(invocation);
        AgentTool.ApprovalRequirement fullAccess = new JaAgentToolAdapter(delegate,
                dependencies(factory, AccessMode.FULL_ACCESS, codec)).approvalRequirement(invocation);

        assertEquals(AgentTool.ApprovalRequirement.REQUIRED, approvalRequired);
        assertEquals(AgentTool.ApprovalRequirement.NOT_REQUIRED, fullAccess);
    }

    /** 最终路由必须和工具逐调用声明共同证明 MCP 本地读取，普通远端调用仍挂起审批。 */
    @Test
    void exemptsOnlyTheVerifiedMcpLocalReadAction() {
        JaTool delegate = new JaTool() {
            /** 固定调用展示名称与 Schema，避免工具身份来自模型参数。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture", "Fixture tool", JsonObjects.builder().putText("type", "object").build());
            }

            /** 测试不派发远端工具，只验证已冻结路由上的审批分类。 */
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    AgentTool.Invocation invocation, ExecutionContext context, CancellationToken token) {
                return CompletableFuture.completedFuture(success());
            }

            /** MCP 网关能力只有对精确本地读取动作才声明内部审批层级。 */
            @Override
            public ApprovalRequirement approvalRequirement() {
                return ApprovalRequirement.TRUSTED_INTERNAL;
            }

            /** MCP 绑定明确属于远端路由，策略不能把它当成本地 Builtin。 */
            @Override
            public ToolBindingDescriptor bindingDescriptor() {
                return new ToolBindingDescriptor(RouteKind.MCP, "fixture", "mcp-test", "remote_fixture",
                        "a".repeat(64), "b".repeat(64));
            }

            /** 仅已由 MCP 工具校验过的本地读取参数标记可触发受信读取豁免。 */
            @Override
            public boolean isTrustedLocalReadAction(AgentTool.Invocation call) {
                return call.arguments().members().containsKey("localRead");
            }
        };
        JaAgentToolAdapter adapter = new JaAgentToolAdapter(delegate, dependencies(
                (invocation, core) -> appContext(invocation, core, temporary), AccessMode.APPROVAL_REQUIRED,
                new JaToolBindingCodec(new ObjectMapper())));

        assertEquals(AgentTool.ApprovalRequirement.REQUIRED,
                adapter.approvalRequirement(invocation("call_remote_action")));
        AgentTool.Invocation localRead = new AgentTool.Invocation("call_local_read", "fixture",
                JsonObjects.builder().putText("localRead", "true").build(), 0);
        assertEquals(AgentTool.ApprovalRequirement.NOT_REQUIRED, adapter.approvalRequirement(localRead));
    }

    /** 越出真实 Workspace 的绝对路径只能保存观察失败事实，不能成为成功变更收据。 */
    @Test
    void convertsAnOutsideMutationIntoAnObservationFailureFact() {
        Path workspace = temporary.resolve("workspace").toAbsolutePath().normalize();
        JaTool.MutationReceipt receipt = JaTool.MutationReceipt.of(
                temporary.resolve("outside.txt"), false, "", true, "secret");
        JaTool delegate = tool((invocation, context, token) -> CompletableFuture.completedFuture(
                new JaTool.ToolResult(ToolOutcome.SUCCEEDED, "written", Optional.empty(), null,
                        List.of(), Optional.of(receipt), Optional.empty())));
        AgentTool.Invocation invocation = invocation("call_outside");
        AgentTool.ExecutionContext core = coreContext(invocation);
        JaAgentToolAdapter adapter = adapter(delegate, workspace);

        AgentTool.ExecutionResult result = adapter.execute(invocation, core, CancellationToken.none())
                .toCompletableFuture().join();

        assertEquals(1, result.facts().size());
        assertEquals("ja.workspace", result.facts().getFirst().namespace());
        assertEquals("mutation-observation-failure", result.facts().getFirst().type());
        assertEquals("OUTSIDE_WORKSPACE", ((JsonText) result.facts().getFirst().payload().get("reason")).value());
    }

    /** 成功与工具级失败都展示真实派发耗时，恢复提交不得用等待或重启间隔补造时长。 */
    @Test
    void preservesMeasuredDurationForResolvedToolResults() {
        List<JaTool.ToolResult> outcomes = List.of(success(),
                new JaTool.ToolResult(ToolOutcome.FAILED, "tool rejected", Optional.empty(), "FIXTURE_FAILURE"));
        for (JaTool.ToolResult outcome : outcomes) {
            AgentTool.Invocation invocation = invocation("call_duration_" + CLAIM_SEQUENCE.incrementAndGet());
            AgentTool.ExecutionContext core = coreContext(invocation);
            JaAgentToolAdapter adapter = adapter(tool((call, context, token) -> {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                return CompletableFuture.completedFuture(outcome);
            }), temporary);

            AgentTool.ExecutionResult execution = adapter.execute(invocation, core, CancellationToken.none())
                    .toCompletableFuture().join();
            try {
                long measured = execution.executionDurationMillis().orElseThrow();
                assertTrue(measured >= 10, "the measured interval must include the delayed delegate completion");

                List<AgentTool.ExtensionFact> facts = adapter.resultFacts(invocation, core, execution,
                        new AgentMiddleware.ToolResultProjection(outcome.content(), outcome.structuredContent()));
                String encodedPresentation = ((JsonText) facts.getFirst().payload().get("presentationJson")).value();
                ToolPresentation presentation = new ToolPresentationCodec(new ObjectMapper()).read(encodedPresentation);
                assertEquals(measured, presentation.durationMs());
            } finally {
                execution.close();
            }
        }
    }

    /** 异常 CompletionStage 保持未结算，不把异常执行猜成带耗时的 Tool 结果。 */
    @Test
    void propagatesExceptionalExecutionWithoutFabricatingASettledDuration() {
        JaTool delegate = tool((invocation, context, token) ->
                CompletableFuture.failedFuture(new IllegalStateException("fixture failure")));
        AgentTool.Invocation invocation = invocation("call_duration_exception");

        CompletionException failure = assertThrows(CompletionException.class,
                () -> adapter(delegate, temporary).execute(invocation, coreContext(invocation), CancellationToken.none())
                        .toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IllegalStateException);
    }

    /** Unknown recovery 只保留挂起结论，不重派工具，也不把恢复等待计入执行耗时。 */
    @Test
    void unknownRecoveryDoesNotDispatchOrCreateExecutionDuration() {
        AtomicInteger dispatches = new AtomicInteger();
        JaTool delegate = tool((invocation, context, token) -> {
            dispatches.incrementAndGet();
            return CompletableFuture.completedFuture(success());
        });
        AgentTool.Invocation invocation = invocation("call_unknown_recovery");
        JaAgentToolAdapter adapter = adapter(delegate, temporary);

        AgentTool.RecoveryResolution resolution = adapter.reconcile(invocation, coreContext(invocation),
                Optional.empty(), CancellationToken.none()).toCompletableFuture().join();

        AgentTool.RecoveryResolution.Unknown unknown =
                org.junit.jupiter.api.Assertions.assertInstanceOf(AgentTool.RecoveryResolution.Unknown.class, resolution);
        try {
            assertEquals("TOOL_OUTCOME_UNVERIFIABLE", unknown.reasonCode());
            assertEquals(0, dispatches.get());
        } finally {
            unknown.settlementResource().close();
        }
    }

    /** 只有 NotApplied 后真实再次派发才重新计时，并沿用原核验租约到结果提交。 */
    @Test
    void measuresRetryDispatchAfterNotAppliedRecovery() {
        JaTool delegate = new JaTool() {
            /** 固定恢复测试工具身份，避免验证分支依赖工具名推导。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture", "Fixture tool", JsonObjects.builder().putText("type", "object").build());
            }

            /** 在真实重派阶段等待可控时间，随后返回可结算的成功结果。 */
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    AgentTool.Invocation invocation, ExecutionContext context, CancellationToken token) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
                return CompletableFuture.completedFuture(success());
            }

            /** 明确证明上一次尝试未执行；Core 才能提交 retry intent 后调用 execute。 */
            @Override
            public java.util.concurrent.CompletionStage<RecoveryResolution> reconcile(
                    AgentTool.Invocation invocation, ExecutionContext context,
                    Optional<AgentTool.RecoveryEvidence> evidence, CancellationToken token) {
                return CompletableFuture.completedFuture(new RecoveryResolution.NotApplied("a".repeat(64)));
            }
        };
        AgentTool.Invocation invocation = invocation("call_duration_retry");
        AgentTool.ExecutionContext core = coreContext(invocation);
        JaAgentToolAdapter adapter = adapter(delegate, temporary);
        AgentTool.RecoveryResolution.NotApplied notApplied =
                org.junit.jupiter.api.Assertions.assertInstanceOf(AgentTool.RecoveryResolution.NotApplied.class,
                        adapter.reconcile(invocation, core, Optional.empty(), CancellationToken.none())
                                .toCompletableFuture().join());

        AgentTool.ExecutionResult execution = null;
        try {
            execution = adapter.executeAfterNotApplied(invocation, core, CancellationToken.none(),
                    notApplied.settlementResource()).toCompletableFuture().join();
            assertTrue(execution.executionDurationMillis().orElseThrow() >= 10);
        } finally {
            if (execution == null) {
                notApplied.settlementResource().close();
            }
            else {
                execution.close();
            }
        }
    }

    /** 保留 Workspace 命名空间只由 adapter 产生，避免业务工具伪造已核验文件收据。 */
    @Test
    void rejectsDelegateFactsInTheAdapterOwnedWorkspaceNamespace() {
        JaTool delegate = tool((invocation, context, token) -> CompletableFuture.completedFuture(
                new JaTool.ToolResult(ToolOutcome.SUCCEEDED, "ok", Optional.empty(), null,
                        List.of(new AgentTool.ExtensionFact("ja.workspace", "mutation-receipt", 1,
                                JsonObject.empty())), Optional.empty(), Optional.empty())));
        AgentTool.Invocation invocation = invocation("call_forged");

        CompletionException failure = assertThrows(CompletionException.class,
                () -> adapter(delegate, temporary).execute(invocation, coreContext(invocation), CancellationToken.none())
                        .toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    /** 结算贡献者不能伪造 adapter 命名空间；拒绝后保留已派发边界供 Core 核验，不伪造成功回执。 */
    @Test
    void rejectsSettledContributorFactsInAdapterOwnedNamespacesAfterSingleDispatch() {
        for (String namespace : List.of("ja.workspace", "ja.tool", "ja.turn")) {
            AtomicInteger dispatches = new AtomicInteger();
            JaTool delegate = tool((invocation, context, token) -> {
                dispatches.incrementAndGet();
                return CompletableFuture.completedFuture(success());
            });
            JaToolFactContributor contributor = new JaToolFactContributor() {
                /** 每轮只注册一个唯一贡献者，避免测试重复身份掩盖 namespace 拒绝。 */
                @Override
                public String id() {
                    return "forged_" + namespace.replace('.', '_');
                }

                /** 真实执行后故意尝试写入 adapter 专属空间，必须在结果事实提交前失败关闭。 */
                @Override
                public List<AgentTool.ExtensionFact> settled(
                        AgentTool.Invocation invocation, JaTool.ExecutionContext context, JaTool.ToolResult result) {
                    return List.of(new AgentTool.ExtensionFact(namespace, "forged", 1, JsonObject.empty()));
                }
            };
            AgentTool.Invocation invocation = invocation("call_forged_settled_" + namespace.replace('.', '_'));
            JaTool.ExecutionContextFactory factory =
                    (call, core) -> appContext(call, core, temporary);
            JaAgentToolAdapter adapter = new JaAgentToolAdapter(delegate,
                    dependencies(factory, AccessMode.FULL_ACCESS, new JaToolBindingCodec(new ObjectMapper()),
                            List.of(contributor)));

            CompletionException failure = assertThrows(CompletionException.class,
                    () -> adapter.execute(invocation, coreContext(invocation), CancellationToken.none())
                            .toCompletableFuture().join());

            assertTrue(failure.getCause() instanceof IllegalArgumentException);
            assertEquals(1, dispatches.get(), "rejected settlement must not re-dispatch the external action");
        }
    }

    /** 用真实调用身份组装两侧上下文，测试不借助 Core attributes 填充 Ja 配置。 */
    private static JaAgentToolAdapter adapter(JaTool tool, Path workspace) {
        return new JaAgentToolAdapter(tool, dependencies(
                (invocation, core) -> appContext(invocation, core, workspace), AccessMode.FULL_ACCESS,
                new JaToolBindingCodec(new ObjectMapper())));
    }

    /** 适配器依赖必须显式提供安全序列化、权限与写租约，测试不得借旧构造器省略边界。 */
    private static JaAgentToolAdapter.Dependencies dependencies(
            JaTool.ExecutionContextFactory contextFactory, AccessMode accessMode, JaToolBindingCodec bindingCodec) {
        return dependencies(contextFactory, accessMode, bindingCodec, List.of());
    }

    /** 仅向单测显式装入被测贡献者，生产 adapter 仍通过 Resolver 注册真实 owner。 */
    private static JaAgentToolAdapter.Dependencies dependencies(
            JaTool.ExecutionContextFactory contextFactory, AccessMode accessMode,
            JaToolBindingCodec bindingCodec, List<JaToolFactContributor> factContributors) {
        return new JaAgentToolAdapter.Dependencies(contextFactory, accessMode, bindingCodec, WRITE_LEASES,
                new ToolPresentationCodec(new ObjectMapper()), List.of("secret"), factContributors,
                TurnOrigin.USER, CollaborationMode.DEFAULT);
    }

    /** 仅模拟持久 claim 的状态推进；测试工具不能触达工作区文件或真实业务副作用。 */
    private static WorkspaceWriteLeaseCoordinator writeLeaseFixture() {
        ConversationWorkspaceWriteClaimPort claims = new ConversationWorkspaceWriteClaimPort() {
            /** 记录隔离测试中的排队 claim，保持生产要求的 sequence 与 fencing token 正值。 */
            @Override public WriteClaim enqueue(String claimId, String workspaceId, String threadId, String turnId,
                    long processGeneration, Instant requestedAt) {
                long sequence = CLAIM_SEQUENCE.incrementAndGet();
                WriteClaim waiting = new WriteClaim(sequence, claimId, workspaceId, threadId, turnId,
                        processGeneration, sequence, State.WAITING, requestedAt, null, null, null);
                CLAIMS.put(claimId, waiting);
                return waiting;
            }

            /** 测试 claim 立即取得队首位置，不引入线程等待或工作区访问。 */
            @Override public Optional<WriteClaim> tryAcquire(String claimId, long fencingToken, Instant acquiredAt) {
                return mutateClaim(claimId, fencingToken, current -> current.state() == State.WAITING
                        ? new WriteClaim(current.sequence(), current.claimId(), current.workspaceId(), current.threadId(),
                        current.turnId(), current.processGeneration(), current.fencingToken(), State.HELD,
                        current.requestedAt(), acquiredAt, acquiredAt, null) : null);
            }

            /** 续租只更新测试快照的时间戳，与真实 claim 一样要求 HELD owner。 */
            @Override public Optional<WriteClaim> heartbeat(String claimId, long fencingToken, Instant occurredAt) {
                return mutateClaim(claimId, fencingToken, current -> current.state() == State.HELD
                        ? new WriteClaim(current.sequence(), current.claimId(), current.workspaceId(), current.threadId(),
                        current.turnId(), current.processGeneration(), current.fencingToken(), State.HELD,
                        current.requestedAt(), current.acquiredAt(), occurredAt, null) : null);
            }

            /** 正常结束记录释放态，保证 coordinator 的结束校验仍覆盖完整状态转换。 */
            @Override public Optional<WriteClaim> release(String claimId, long fencingToken, Instant occurredAt) {
                return mutateClaim(claimId, fencingToken, current -> current.state() == State.HELD
                        ? new WriteClaim(current.sequence(), current.claimId(), current.workspaceId(), current.threadId(),
                        current.turnId(), current.processGeneration(), current.fencingToken(), State.RELEASED,
                        current.requestedAt(), current.acquiredAt(), current.heartbeatAt(), occurredAt) : null);
            }

            /** 取消或超时只关闭未结束 claim，不将它重标为成功释放。 */
            @Override public Optional<WriteClaim> abandon(String claimId, long fencingToken, Instant occurredAt) {
                return mutateClaim(claimId, fencingToken, current -> current.state() == State.WAITING
                        || current.state() == State.HELD
                        ? new WriteClaim(current.sequence(), current.claimId(), current.workspaceId(), current.threadId(),
                        current.turnId(), current.processGeneration(), current.fencingToken(), State.ABANDONED,
                        current.requestedAt(), current.acquiredAt(), current.heartbeatAt(), occurredAt) : null);
            }
        };
        return new WorkspaceWriteLeaseCoordinator(claims, Clock.systemUTC(), 1);
    }

    /** 以原子替换模拟 fencing CAS，失败 owner 不会覆盖较新的测试状态。 */
    private static Optional<ConversationWorkspaceWriteClaimPort.WriteClaim> mutateClaim(String claimId, long fencingToken,
            UnaryOperator<ConversationWorkspaceWriteClaimPort.WriteClaim> mutation) {
        while (true) {
            ConversationWorkspaceWriteClaimPort.WriteClaim current = CLAIMS.get(claimId);
            if (current == null || current.fencingToken() != fencingToken) {
                return Optional.empty();
            }
            ConversationWorkspaceWriteClaimPort.WriteClaim next = mutation.apply(current);
            if (next == null) {
                return Optional.empty();
            }
            if (CLAIMS.replace(claimId, current, next)) {
                return Optional.of(next);
            }
        }
    }

    /** 只替换待测执行动作，其余元数据保持完整默认策略，覆盖公开 JaTool 端口。 */
    private static JaTool tool(ToolExecution action) {
        return new JaTool() {
            /** 固定一个符合 Provider Schema 的工具身份。 */
            @Override
            public ToolSpec spec() {
                return new ToolSpec("fixture", "Fixture tool", JsonObjects.builder().putText("type", "object").build());
            }

            /** 将当前测试明确指定的动作作为唯一副作用执行器。 */
            @Override
            public java.util.concurrent.CompletionStage<ToolResult> execute(
                    AgentTool.Invocation invocation, ExecutionContext context, CancellationToken token) {
                return action.execute(invocation, context, token);
            }
        };
    }

    /** 以闭合 Tool 名和稳定调用键创建输入，避免 binding 测试被身份校验提前终止。 */
    private static AgentTool.Invocation invocation(String callId) {
        return new AgentTool.Invocation(callId, "fixture", JsonObject.empty(), 0);
    }

    /** 生成 Core 执行上下文，callKey 与调用身份必须完全一致。 */
    private static AgentTool.ExecutionContext coreContext(AgentTool.Invocation invocation) {
        AgentIdentity identity = identity();
        return new AgentTool.ExecutionContext(identity, identity.toolCallKey(invocation.callId()), java.util.Map.of());
    }

    /** 用宿主 Workspace 与同一 Core 身份补充 Ja 字段，不另造重复的 Thread/Turn 值。 */
    private static JaTool.ExecutionContext appContext(
            AgentTool.Invocation invocation, AgentTool.ExecutionContext core, Path workspace) {
        return new JaTool.ExecutionContext(core.identity(), invocation.callId(), core.callKey(), core.identity().threadId(),
                core.identity().turnId(), workspace.toAbsolutePath().normalize(), AccessMode.FULL_ACCESS, "cfg_fixture",
                Instant.now().plusSeconds(30), "ws_fixture", null, null, TurnOrigin.USER);
    }

    /** 固定合法 tenant/thread/turn/user 组合供稳定键推导，避免测试继续传播旧身份术语。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("tenant_fixture", "thread_fixture", "turn_fixture", "user_fixture");
    }

    /** 提供无 Ja 附加事实的最小成功结果。 */
    private static JaTool.ToolResult success() {
        return new JaTool.ToolResult(ToolOutcome.SUCCEEDED, "ok", Optional.empty(), null);
    }

    /** 保留异步执行签名，便于测试替换返回成功或副作用收据的路径。 */
    @FunctionalInterface
    private interface ToolExecution {
        /** 将单次调用交给测试动作，保持生产端口的取消与 Ja 上下文参数。 */
        java.util.concurrent.CompletionStage<JaTool.ToolResult> execute(
                AgentTool.Invocation invocation, JaTool.ExecutionContext context, CancellationToken token);
    }
}
