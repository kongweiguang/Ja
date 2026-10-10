// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.loop;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.model.ModelMessage;
import io.github.kongweiguang.ja.agent.domain.model.ModelRole;
import io.github.kongweiguang.ja.agent.domain.model.ToolResultContent;
import io.github.kongweiguang.ja.agent.domain.tool.ToolExecutionBinding;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolState;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定 Runner 的准备、等待、恢复和模型投影边界；持久化事务由 ToolStep 统一拥有。 */
final class AgentToolRunnerTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            "tenant_runner", "thread_runner", "turn_runner", "user_runner");
    private static final AgentTool.ToolBindingContext BINDING_CONTEXT =
            new AgentTool.ToolBindingContext(FINGERPRINT, "b".repeat(64));

    /** 参数重写后重验权限并冻结显式审批等待，调用绑定对应最终动作而不是原始动作。 */
    @Test
    void revalidatesFinalArgumentsAndFreezesApprovalWait() {
        AtomicInteger validations = new AtomicInteger();
        AtomicReference<AgentTool.Invocation> bound = new AtomicReference<>();
        FixtureTool tool = new FixtureTool(bound);
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(
                registration("rewrite", middleware("rewrite", context ->
                        AgentMiddleware.ToolDecision.proceed(arguments("write")), null)),
                registration("approval", middleware("approval", context ->
                        context.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED
                                ? AgentMiddleware.ToolDecision.waitFor(approvalRequest())
                                : AgentMiddleware.ToolDecision.proceed(context.invocation().arguments()), null)));
        AgentToolRunner runner = new AgentToolRunner((schema, args) -> {
            validations.incrementAndGet();
            return mode(args).isBlank() ? Optional.of("mode must be non-empty") : Optional.empty();
        }, policies);

        AgentToolRunner.Waiting waiting = assertInstanceOf(AgentToolRunner.Waiting.class,
                runner.prepare(tool, invocation("call_rewrite", "read"), context("call_rewrite"),
                        BINDING_CONTEXT, states(policies)));

        assertEquals(2, validations.get());
        assertEquals("write", mode(waiting.call().invocation().arguments()));
        assertEquals(ToolSideEffect.EXTERNAL, waiting.call().sideEffect());
        assertEquals(AgentTool.ApprovalRequirement.REQUIRED, waiting.call().approvalRequirement());
        assertEquals("write", mode(bound.get().arguments()));
        assertEquals(FINGERPRINT, waiting.call().binding().orElseThrow().catalogFingerprint());
        assertEquals("approval", waiting.request().kind());
        assertEquals(0, tool.executions.get());
    }

    /** 中间件已经提出等待后不能再改写获批参数，避免批准问题和真实动作脱节。 */
    @Test
    void rejectsArgumentRewriteAfterWaitWasRequested() {
        FixtureTool tool = new FixtureTool(new AtomicReference<>());
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(
                registration("wait", middleware("wait", ignored ->
                        AgentTool.ApprovalRequirement.REQUIRED == AgentTool.ApprovalRequirement.NOT_REQUIRED
                                ? AgentMiddleware.ToolDecision.proceed(arguments("read"))
                                : AgentMiddleware.ToolDecision.waitFor(new AgentMiddleware.WaitRequest(
                                        "question", "Continue?", JsonObject.empty())), null)),
                registration("rewrite", middleware("rewrite", ignored ->
                        AgentMiddleware.ToolDecision.proceed(arguments("write")), null)));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);

        assertThrows(IllegalStateException.class, () -> runner.prepare(tool,
                invocation("call_wait_rewrite", "read"), context("call_wait_rewrite"),
                BINDING_CONTEXT, states(policies)));
        assertEquals(0, tool.executions.get());
    }

    /** 不同中间件的互斥等待必须显式报错，相同等待则合并为一个持久请求。 */
    @Test
    void coalescesIdenticalWaitsAndRejectsConflictingWaits() {
        FixtureTool tool = new FixtureTool(new AtomicReference<>());
        AgentMiddleware.WaitRequest same = new AgentMiddleware.WaitRequest(
                "question", "Continue?", JsonObject.empty());
        List<ExtensionRegistry.MiddlewareRegistration> identical = List.of(
                registration("first", middleware("first", ignored -> AgentMiddleware.ToolDecision.waitFor(same), null)),
                registration("second", middleware("second", ignored -> AgentMiddleware.ToolDecision.waitFor(same), null)));
        AgentToolRunner.Waiting waiting = assertInstanceOf(AgentToolRunner.Waiting.class,
                new AgentToolRunner(validArguments(), identical).prepare(tool,
                        invocation("call_same_wait", "read"), context("call_same_wait"),
                        BINDING_CONTEXT, states(identical)));
        assertEquals(same, waiting.request());
        assertTrue(waiting.call().binding().isPresent());

        List<ExtensionRegistry.MiddlewareRegistration> conflicting = List.of(
                registration("first", middleware("first", ignored -> AgentMiddleware.ToolDecision.waitFor(same), null)),
                registration("second", middleware("second", ignored -> AgentMiddleware.ToolDecision.waitFor(
                        new AgentMiddleware.WaitRequest("question", "Different?", JsonObject.empty())), null)));
        assertThrows(IllegalStateException.class, () -> new AgentToolRunner(validArguments(), conflicting)
                .prepare(tool, invocation("call_conflicting_wait", "read"), context("call_conflicting_wait"),
                        BINDING_CONTEXT, states(conflicting)));
    }

    /** 最終参数无效时立即拒绝，保留准备 transitions 但绝不生成可派发绑定。 */
    @Test
    void rejectsInvalidMiddlewareParametersBeforeDispatch() {
        FixtureTool tool = new FixtureTool(new AtomicReference<>());
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(registration("invalidate",
                middleware("invalidate", ignored -> AgentMiddleware.ToolDecision.proceed(arguments("")), null)));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);

        AgentToolRunner.Rejected rejected = assertInstanceOf(AgentToolRunner.Rejected.class,
                runner.prepare(tool, invocation("call_invalid", "read"), context("call_invalid"),
                        BINDING_CONTEXT, states(policies)));

        assertEquals("TOOL_ARGUMENTS_INVALID", rejected.result().errorCode());
        assertTrue(rejected.call().binding().isEmpty());
        assertEquals(1, rejected.call().transitions().size());
        assertThrows(IllegalArgumentException.class,
                () -> runner.execute(rejected.call(), CancellationToken.none()));
        assertEquals(0, tool.executions.get());
    }

    /** 恢复重验原参数、路由和绑定，却不重跑等待前已提交的 middleware 决策。 */
    @Test
    void restoresPreparedCallWithoutRerunningMiddleware() {
        AtomicInteger beforeCalls = new AtomicInteger();
        FixtureTool tool = new FixtureTool(new AtomicReference<>());
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(registration("policy",
                middleware("policy", ignored -> {
                    beforeCalls.incrementAndGet();
                    return AgentMiddleware.ToolDecision.proceed(arguments("read"));
                }, null)));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);
        AgentToolRunner.PreparedCall original = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(tool, invocation("call_restore", "read"), context("call_restore"),
                        BINDING_CONTEXT, states(policies))).call();

        AgentToolRunner.PreparedCall restored = runner.restorePreparedCall(tool, original.invocation(),
                original.execution(), BINDING_CONTEXT, original.binding().orElseThrow(),
                original.sideEffect(), original.preparedFacts());

        assertEquals(1, beforeCalls.get());
        assertEquals(original.binding(), restored.binding());
        assertTrue(restored.transitions().isEmpty());
        assertThrows(IllegalStateException.class, () -> runner.restorePreparedCall(tool,
                original.invocation(), original.execution(), new AgentTool.ToolBindingContext(
                        "c".repeat(64), "d".repeat(64)), original.binding().orElseThrow(),
                original.sideEffect(), original.preparedFacts()));
    }

    /** 结果中间件按顺序只改模型投影，并分别观察真实 Started 与准入拒绝边界。 */
    @Test
    void projectsVisibleResultWithoutChangingAuthoritativeOutcome() {
        AtomicReference<ToolOutcome> observedOutcome = new AtomicReference<>();
        AtomicInteger observedFacts = new AtomicInteger();
        AtomicReference<Boolean> observedStarted = new AtomicReference<>();
        AtomicReference<OptionalLong> observedDuration = new AtomicReference<>(OptionalLong.empty());
        FixtureTool tool = new FixtureTool(new AtomicReference<>()) {
            /** 观察真实结果耗时并将完成投影变成同事务宿主事实。 */
            @Override
            public List<ExtensionFact> resultFacts(Invocation invocation, ExecutionContext context,
                    ExecutionResult executionResult, AgentMiddleware.ToolResultProjection visible) {
                observedDuration.set(executionResult.executionDurationMillis());
                return List.of(new ExtensionFact("fixture", "projection", 1,
                        JsonObjects.builder().putText("content", visible.content()).build()));
            }
        };
        AgentMiddleware first = middleware("first", null, result -> {
            observedOutcome.set(result.outcome());
            observedFacts.set(result.extensionFacts().size());
            observedStarted.set(result.executionStarted());
            return new AgentMiddleware.ToolResultProjection(result.content() + "-first",
                    result.structuredContent());
        });
        AgentMiddleware second = middleware("second", null, result ->
                new AgentMiddleware.ToolResultProjection(result.content() + "-second", result.structuredContent()));
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(
                registration("first", first), registration("second", second));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);
        AgentToolRunner.PreparedCall ready = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(tool, invocation("call_projection", "read"), context("call_projection"),
                        BINDING_CONTEXT, states(policies))).call();
        AgentTool.ToolResult failure = new AgentTool.ToolResult(
                ToolOutcome.FAILED, "raw", Optional.empty(), "FIXTURE_FAILED");

        AgentToolRunner.ProjectedResult projected = runner.projectResult(ready,
                new AgentTool.ExecutionResult(failure, List.of(FixtureTool.fact()))
                        .withExecutionDurationMillis(37),
                runner.stateAfter(states(policies), ready.transitions()), true);

        assertEquals("raw-first-second", projected.visibleResult().content());
        assertEquals(ToolOutcome.FAILED, observedOutcome.get());
        assertTrue(observedStarted.get());
        assertEquals(1, observedFacts.get());
        assertEquals(OptionalLong.of(37), observedDuration.get());
        assertEquals(ToolOutcome.FAILED, projected.committedResult().result().outcome());
        assertEquals(OptionalLong.of(37), projected.committedResult().executionDurationMillis());
        assertEquals(2, projected.committedResult().facts().size());

        runner.projectResult(ready, AgentTool.ExecutionResult.of(new AgentTool.ToolResult(
                        ToolOutcome.FAILED, "rejected", Optional.empty(), "FIXTURE_REJECTED")),
                runner.stateAfter(states(policies), ready.transitions()), false);
        assertEquals(Boolean.FALSE, observedStarted.get(), "pre-Started rejection must remain distinguishable");
        assertEquals(OptionalLong.empty(), observedDuration.get(), "unmeasured rejection must stay unknown");

        AgentTool.ExecutionResult timed = new AgentTool.ExecutionResult(failure, List.of(FixtureTool.fact()))
                .withExecutionDurationMillis(37);
        AgentTool.ExecutionResult held = timed.withSettlementResource(AgentTool.SettlementResource.none());
        assertEquals(OptionalLong.of(37), held.executionDurationMillis(), "lease transfer must retain timing");
        assertEquals(OptionalLong.of(37), runner.projectAuthoritativeResult(ready, held)
                .committedResult().executionDurationMillis(), "safe projection fallback must retain timing");
        assertThrows(IllegalArgumentException.class, () -> timed.withExecutionDurationMillis(-1));
    }

    /** 空白失败在 middleware 前取得稳定诊断，并将权威结果和可见消息分别 typed 持久化往返。 */
    @Test
    void fillsFailedEmptyContentBeforeProjectionAndStoreEncoding() {
        AtomicReference<String> observedContent = new AtomicReference<>();
        AgentMiddleware policy = middleware("observe", null, result -> {
            observedContent.set(result.content());
            return new AgentMiddleware.ToolResultProjection(result.content(), result.structuredContent());
        });
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(registration("observe", policy));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);
        AgentTool.ToolResult blankFailure = new AgentTool.ToolResult(ToolOutcome.FAILED, " \r\n ",
                Optional.of(JsonObjects.builder().putText("category", "timeout").build()), "FIXTURE_FAILED");
        AgentToolRunner.PreparedCall ready = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(new FixtureTool(new AtomicReference<>()), invocation("call_empty_failure", "read"),
                        context("call_empty_failure"), BINDING_CONTEXT, states(policies))).call();

        AgentToolRunner.ProjectedResult projected = runner.projectResult(ready,
                AgentTool.ExecutionResult.of(blankFailure).withExecutionDurationMillis(37),
                runner.stateAfter(states(policies), ready.transitions()), true);
        String diagnostic = "Tool failed with code FIXTURE_FAILED (category: timeout).";

        assertEquals(diagnostic, observedContent.get());
        assertEquals(diagnostic, projected.committedResult().result().content());
        assertEquals(diagnostic, projected.visibleResult().content());
        assertEquals(OptionalLong.of(37), projected.committedResult().executionDurationMillis());

        StorePayloadCodec codec = new StorePayloadCodec(new ObjectMapper());
        Instant occurredAt = Instant.parse("2026-10-03T00:00:00Z");
        ExecutionFacts.ToolResult resultFact = new ExecutionFacts.ToolResult(
                "call_empty_failure", ToolState.FAILED, projected.committedResult().result().content(), true, "");
        AgentStore.RecordWrite resultWrite = codec.encode("result_empty_failure", resultFact, occurredAt);
        AgentStore.StoredRecord storedResult = new AgentStore.StoredRecord(resultWrite.recordId(),
                resultWrite.recordType(), resultWrite.payloadSchemaVersion(), 1, resultWrite.payload(), occurredAt);
        assertEquals(resultFact, codec.decode(storedResult));

        ExecutionFacts.ToolMessage messageFact = new ExecutionFacts.ToolMessage("item_tool_empty_failure",
                new ModelMessage(ModelRole.TOOL, List.of(new ToolResultContent(
                        "call_empty_failure", projected.visibleResult().content(), true))));
        AgentStore.RecordWrite messageWrite = codec.encode("message_empty_failure", messageFact, occurredAt);
        AgentStore.StoredRecord storedMessage = new AgentStore.StoredRecord(messageWrite.recordId(),
                messageWrite.recordType(), messageWrite.payloadSchemaVersion(), 2, messageWrite.payload(), occurredAt);
        assertEquals(messageFact, codec.decode(storedMessage));
    }

    /** 不可信诊断字段退回稳定错误码；成功的空内容和既有非空失败仍保持原样。 */
    @Test
    void boundsFailureDiagnosticFieldsAndPreservesOtherResults() {
        AgentToolRunner runner = new AgentToolRunner(validArguments(), List.of());
        AgentToolRunner.PreparedCall ready = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(new FixtureTool(new AtomicReference<>()), invocation("call_bad_diagnostic", "read"),
                        context("call_bad_diagnostic"), BINDING_CONTEXT, Map.of())).call();
        AgentTool.ToolResult unsafeFailure = new AgentTool.ToolResult(ToolOutcome.FAILED, "\t",
                Optional.of(JsonObjects.builder().putText("category", "x".repeat(65))
                        .putText("message", "private exception").build()),
                "lowercase-code");

        AgentToolRunner.ProjectedResult failed = runner.projectResult(ready,
                AgentTool.ExecutionResult.of(unsafeFailure).withExecutionDurationMillis(37), Map.of(), true);
        AgentToolRunner.ProjectedResult succeeded = runner.projectResult(ready,
                AgentTool.ExecutionResult.of(AgentTool.ToolResult.success("")), Map.of(), true);
        AgentTool.ToolResult nonEmptyFailure = new AgentTool.ToolResult(
                ToolOutcome.FAILED, "existing diagnostic", Optional.empty(), "FIXTURE_FAILED");
        AgentToolRunner.ProjectedResult existing = runner.projectResult(ready,
                AgentTool.ExecutionResult.of(nonEmptyFailure), Map.of(), true);
        AgentToolRunner.ProjectedResult withheld = runner.projectAuthoritativeResult(ready,
                AgentTool.ExecutionResult.of(unsafeFailure).withExecutionDurationMillis(37));

        assertEquals("Tool failed with code TOOL_FAILED.", failed.visibleResult().content());
        assertEquals(OptionalLong.of(37), failed.committedResult().executionDurationMillis());
        assertEquals("", succeeded.visibleResult().content());
        assertEquals("existing diagnostic", existing.visibleResult().content());
        assertEquals("Tool failed with code TOOL_FAILED.", withheld.committedResult().result().content());
        assertEquals(OptionalLong.of(37), withheld.committedResult().executionDurationMillis());
    }

    /** 中间件 state patch/facts 保留 namespace 顺序并将 revision 交给同一事务提交。 */
    @Test
    void carriesMiddlewareTransitionsAndRevision() {
        AgentMiddleware policy = new AgentMiddleware() {
            /** 测试扩展用稳定身份绑定专属 state namespace。 */
            @Override public String id() { return "stateful"; }

            /** 准备后写入单调计数，事实必须和 Prepared 一起提交。 */
            @Override public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
                JsonObject next = JsonObjects.builder().putNumber("prepared", 1).build();
                return new ExtensionDecision<>(ToolDecision.proceed(context.invocation().arguments()),
                        ExtensionStatePatch.replace(context.extensionState(), next),
                        List.of(new MiddlewareFact("prepared", 1, JsonObject.empty())));
            }

            /** 结果阶段读取准备 revision，并将第二个 patch 与结果一起返回。 */
            @Override public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
                assertEquals(1, context.extensionState().revision());
                JsonObject next = JsonObjects.builder().putNumber("settled", 1).build();
                return new ExtensionDecision<>(new ToolResultProjection(context.content(),
                        context.structuredContent()), ExtensionStatePatch.replace(context.extensionState(), next),
                        List.of(new MiddlewareFact("settled", 1, JsonObject.empty())));
            }
        };
        List<ExtensionRegistry.MiddlewareRegistration> policies = List.of(registration("stateful", policy));
        AgentToolRunner runner = new AgentToolRunner(validArguments(), policies);
        AgentToolRunner.PreparedCall prepared = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(new FixtureTool(new AtomicReference<>()), invocation("call_state", "read"),
                        context("call_state"), BINDING_CONTEXT, states(policies))).call();
        Map<String, AgentMiddleware.ExtensionStateView> afterPrepare = runner.stateAfter(
                states(policies), prepared.transitions());
        AgentToolRunner.ProjectedResult projected = runner.projectResult(prepared,
                AgentTool.ExecutionResult.of(AgentTool.ToolResult.success("done")), afterPrepare, true);

        assertEquals(1, prepared.transitions().size());
        assertEquals(1, afterPrepare.get("stateful").revision());
        assertEquals(1, projected.transitions().size());
        assertEquals(1, projected.transitions().getFirst().facts().size());
        assertEquals(1, projected.transitions().getFirst().statePatch().expectedRevision());
    }

    /** 恢复核验与派发各只调用自身 Hook，异常结局不由 Runner 猜测或自动重试。 */
    @Test
    void forwardsRecoveryEvidenceAndDoesNotRetryUncertainExecution() {
        AtomicReference<AgentTool.Invocation> invoked = new AtomicReference<>();
        FixtureTool tool = new FixtureTool(invoked);
        AgentToolRunner runner = new AgentToolRunner(validArguments(), List.of());
        AgentToolRunner.PreparedCall call = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(tool, invocation("call_recovery", "read"), context("call_recovery"),
                        BINDING_CONTEXT, Map.of())).call();

        AgentTool.RecoveryEvidence evidence = runner.prepareRecoveryEvidence(call, CancellationToken.none())
                .orElseThrow();
        AgentTool.ExecutionResult executed = runner.execute(call, CancellationToken.none()).toCompletableFuture().join();
        AgentTool.RecoveryResolution resolution = runner.reconcile(call, Optional.of(evidence),
                CancellationToken.none()).toCompletableFuture().join();

        assertEquals("fixture.evidence", evidence.typeId());
        assertEquals("call_recovery", invoked.get().callId());
        assertEquals(1, executed.facts().size());
        AgentTool.RecoveryResolution.Applied applied = assertInstanceOf(
                AgentTool.RecoveryResolution.Applied.class, resolution);
        assertEquals("FIXTURE_VERIFIED", applied.result().result().errorCode());

        AtomicInteger calls = new AtomicInteger();
        FixtureTool unknown = new FixtureTool(new AtomicReference<>()) {
            /** 真实调用异常时保留未知结果，防止未提交结果被内核重放。 */
            @Override public java.util.concurrent.CompletionStage<ExecutionResult> execute(
                    Invocation invocation, ExecutionContext context, CancellationToken token) {
                calls.incrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("outcome unavailable"));
            }
        };
        AgentToolRunner.PreparedCall uncertain = assertInstanceOf(AgentToolRunner.Ready.class,
                runner.prepare(unknown, invocation("call_uncertain", "read"), context("call_uncertain"),
                        BINDING_CONTEXT, Map.of())).call();
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> runner.execute(uncertain, CancellationToken.none()).toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    /** 项目一次只验证非空参数，错误文本不参与调用身份或执行选择。 */
    private static ToolArgumentValidator validArguments() {
        return (schema, arguments) -> mode(arguments).isBlank()
                ? Optional.of("mode must be non-empty") : Optional.empty();
    }

    /** 为工具策略构造一个明确包含期限的审批问题，Core 不替扩展推测 TTL。 */
    private static AgentMiddleware.WaitRequest approvalRequest() {
        return new AgentMiddleware.WaitRequest("approval", "Allow this write?",
                JsonObjects.builder().putText("expiresAt", "2026-09-27T00:00:00Z").build());
    }

    /** 测试流程共享不可变参数对象，避免 JSON 构造差异影响策略断言。 */
    private static JsonObject arguments(String mode) {
        return JsonObjects.builder().putText("mode", mode).build();
    }

    /** 构造一份按稳定身份编号的中间件注册，Runner 由此冻结 namespace 和 schema。 */
    private static ExtensionRegistry.MiddlewareRegistration registration(String namespace,
            AgentMiddleware middleware) {
        return new ExtensionRegistry.MiddlewareRegistration(namespace, 1, middleware);
    }

    /** 以注册顺序创建版本零状态，恢复后由 StateRecordCodec 重建相同键集合。 */
    private static Map<String, AgentMiddleware.ExtensionStateView> states(
            List<ExtensionRegistry.MiddlewareRegistration> policies) {
        return policies.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                ExtensionRegistry.MiddlewareRegistration::namespace,
                policy -> AgentMiddleware.ExtensionStateView.empty(policy.namespace(), policy.stateSchemaVersion())));
    }

    /** 用纯决策函数简化中间件 fixture，默认策略仍通过通用实现返回 unchanged patch。 */
    private static AgentMiddleware middleware(String id,
            Function<AgentMiddleware.ToolContext, AgentMiddleware.ToolDecision> before,
            Function<AgentMiddleware.ToolResultContext, AgentMiddleware.ToolResultProjection> project) {
        return new AgentMiddleware() {
            /** 测试策略身份固定且显式，不依赖匿名实现名称。 */
            @Override public String id() { return id; }

            /** 使用夹具决策但仍返回按当前 revision 绑定的状态补丁。 */
            @Override public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
                ToolDecision decision = before == null ? AgentMiddleware.super.beforeTool(context).decision()
                        : before.apply(context);
                return ExtensionDecision.unchanged(decision, context.extensionState());
            }

            /** 结果投影只控制模型可见内容，失败 outcome 由独立真实结果保留。 */
            @Override public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
                ToolResultProjection decision = project == null
                        ? AgentMiddleware.super.projectToolResult(context).decision() : project.apply(context);
                return ExtensionDecision.unchanged(decision, context.extensionState());
            }
        };
    }

    /** Context 与模型 call 使用同一调用键，明确验证恢复时不会从当前任务猜 caller。 */
    private static AgentTool.ExecutionContext context(String callId) {
        return new AgentTool.ExecutionContext(IDENTITY, IDENTITY.toolCallKey(callId), Map.of());
    }

    /** 构造带唯一动作字段的模型调用。 */
    private static AgentTool.Invocation invocation(String callId, String mode) {
        return new AgentTool.Invocation(callId, "fixture_tool", arguments(mode), 0);
    }

    /** 读取动作值；缺少字段使用空值触发 Schema 验证失败。 */
    private static String mode(JsonObject arguments) {
        return arguments.get("mode") instanceof JsonText text ? text.value() : "";
    }

    /** 可观察执行、绑定、恢复与同批 facts 的最小工具夹具。 */
    private static class FixtureTool implements AgentTool {
        private static final ToolSpec SPEC = new ToolSpec("fixture_tool", "Deterministic fixture tool",
                JsonObjects.builder().putText("type", "object")
                        .put("properties", JsonObjects.builder().put("mode",
                                JsonObjects.builder().putText("type", "string").build()).build())
                        .put("required", new io.github.kongweiguang.ja.agent.domain.json.JsonArray(
                                List.of(new JsonText("mode"))))
                        .putBoolean("additionalProperties", false).build());
        private final AtomicReference<AgentTool.Invocation> boundInvocation;
        private final AtomicInteger executions = new AtomicInteger();

        /** 测试显式选择是否观察最终绑定参数。 */
        private FixtureTool(AtomicReference<AgentTool.Invocation> boundInvocation) {
            this.boundInvocation = boundInvocation;
        }

        /** 所有用例共享同一 Schema，差异只由参数与 middleware 决策产生。 */
        @Override public ToolSpec spec() { return SPEC; }

        /** 动作参数决定副作用级别，Runner 必须在参数改写后重读。 */
        @Override public ToolSideEffect sideEffect(Invocation invocation) {
            return "read".equals(mode(invocation.arguments())) ? ToolSideEffect.READ_ONLY : ToolSideEffect.EXTERNAL;
        }

        /** 写动作始终需要显式审批，读动作不会因为工具名推断而等待。 */
        @Override public ApprovalRequirement approvalRequirement(Invocation invocation) {
            return "read".equals(mode(invocation.arguments()))
                    ? ApprovalRequirement.NOT_REQUIRED : ApprovalRequirement.REQUIRED;
        }

        /** 记录最终绑定输入，再使用 Core 默认的 Schema/目录/权限指纹。 */
        @Override public ToolExecutionBinding executionBinding(Invocation invocation, ToolBindingContext context) {
            boundInvocation.set(invocation);
            return AgentTool.super.executionBinding(invocation, context);
        }

        /** 为崩溃恢复提供工具自有的稳定查询凭据。 */
        @Override public Optional<RecoveryEvidence> prepareRecoveryEvidence(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            return Optional.of(new RecoveryEvidence("fixture.evidence", 1, JsonObject.empty(), "c".repeat(64)));
        }

        /** 测试宿主可根据证据重建原结果，Runner 不在恢复时再次派发。 */
        @Override public java.util.concurrent.CompletionStage<RecoveryResolution> reconcile(
                Invocation invocation, ExecutionContext context, Optional<RecoveryEvidence> evidence,
                CancellationToken cancellationToken) {
            return CompletableFuture.completedFuture(new RecoveryResolution.Applied(
                    new ExecutionResult(new ToolResult(ToolOutcome.FAILED, "verified", Optional.empty(),
                            "FIXTURE_VERIFIED"), List.of(fact())), "d".repeat(64)));
        }

        /** 正常调用至多执行一次，并把工具 receipt 与结果绑定到一个 settlement。 */
        @Override public java.util.concurrent.CompletionStage<ExecutionResult> execute(
                Invocation invocation, ExecutionContext context, CancellationToken cancellationToken) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(new ExecutionResult(ToolResult.success("done"), List.of(fact())));
        }

        /** 夹具 receipt 不携带 store record id，让 Runner 保持持久身份中立。 */
        private static ExtensionFact fact() {
            return new ExtensionFact("fixture", "receipt", 1, JsonObject.empty());
        }
    }
}
