// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 ChangeSet 中间件只从已提交收据恢复状态，并在唯一终态候选生成投影事实。 */
final class JaTurnChangeSetMiddlewareTest {
    private static final String NAMESPACE = "ja.turn";
    private static final String WORKSPACE_ID = "ws_fixture";
    private static final AgentIdentity IDENTITY = new AgentIdentity(
            "tenant_fixture", "thread_fixture", "turn_fixture", "user_fixture");
    private static final int SCHEMA_VERSION = 1;

    @TempDir
    Path workspace;

    /** 两个进程代际用持久 ExtensionState 延续同文件写入链，且终态 artifact 身份确定。 */
    @Test
    void restoresReceiptsAcrossMiddlewareInstancesAndFreezesOnlyAtCompletion() {
        TurnChangeSetCodec codec = codec();
        AgentMiddleware.ExtensionStateView state = emptyState();
        JaTurnChangeSetMiddleware first = middleware(codec, tool("workspace_edit", JaTool.WorkspaceMutationMode.EXACT_TEXT));
        state = prepare(first, "workspace_edit", "call_first", 0, state);
        state = project(first, "workspace_edit", "call_first", 0, true, ToolOutcome.SUCCEEDED,
                List.of(receipt("notes/a.txt", true, "initial\n", true, "first\n")), state);

        JaTurnChangeSetMiddleware recovered = middleware(codec,
                tool("workspace_edit", JaTool.WorkspaceMutationMode.EXACT_TEXT));
        state = prepare(recovered, "workspace_edit", "call_second", 1, state);
        state = project(recovered, "workspace_edit", "call_second", 1, true, ToolOutcome.SUCCEEDED,
                List.of(receipt("notes/a.txt", true, "first\n", true, "final\n")), state);

        AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> firstCompletion = complete(
                recovered, state, false);
        AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> retriedCompletion = complete(
                middleware(codec, tool("workspace_edit", JaTool.WorkspaceMutationMode.EXACT_TEXT)), state, false);
        TurnChangeSet firstSet = changeSet(firstCompletion, codec);
        TurnChangeSet retriedSet = changeSet(retriedCompletion, codec);

        assertEquals(TurnChangeSet.State.COMPLETE, firstSet.state());
        assertEquals(1, firstSet.files().size());
        assertEquals("notes/a.txt", firstSet.files().getFirst().path());
        assertEquals(firstSet.artifactId(), retriedSet.artifactId());
        String diff = artifactContent(firstCompletion);
        assertTrue(diff.contains("-initial"));
        assertTrue(diff.contains("+final"));
        assertFalse(diff.contains("+first"));
    }

    /** Core 的显式拒绝在 Started 前投影并清除 marker，不能把未派发的 UNOBSERVABLE Tool 报成未知写入。 */
    @Test
    void rejectionBeforeStartedClearsUnobservableMarkerWithoutPartialResult() {
        TurnChangeSetCodec codec = codec();
        JaTurnChangeSetMiddleware middleware = middleware(codec,
                tool("remote_mutator", JaTool.WorkspaceMutationMode.UNOBSERVABLE),
                tool("read_local", JaTool.WorkspaceMutationMode.NONE));
        AgentMiddleware.ExtensionStateView state = prepare(middleware, "remote_mutator", "call_denied", 0, emptyState());
        state = project(middleware, "remote_mutator", "call_denied", 0, false, ToolOutcome.FAILED,
                List.of(), state);

        state = prepare(middleware, "read_local", "call_read", 1, state);
        state = project(middleware, "read_local", "call_read", 1, true, ToolOutcome.SUCCEEDED,
                List.of(), state);
        TurnChangeSet changeSet = changeSet(complete(middleware, state, false), codec);

        assertEquals(TurnChangeSet.State.COMPLETE, changeSet.state());
        assertTrue(changeSet.incompleteReasons().isEmpty());
        assertTrue(changeSet.files().isEmpty());
    }

    /** 已越过持久 Started 的不可观察失败必须保留未知副作用，即使工具本身返回 FAILED。 */
    @Test
    void startedUnobservableFailureProducesPartialChangeSet() {
        TurnChangeSetCodec codec = codec();
        JaTurnChangeSetMiddleware middleware = middleware(codec,
                tool("remote_mutator", JaTool.WorkspaceMutationMode.UNOBSERVABLE));
        AgentMiddleware.ExtensionStateView state = prepare(middleware, "remote_mutator", "call_started", 0, emptyState());
        state = project(middleware, "remote_mutator", "call_started", 0, true, ToolOutcome.FAILED,
                List.of(), state);
        TurnChangeSet changeSet = changeSet(complete(middleware, state, false), codec);

        assertEquals(TurnChangeSet.State.PARTIAL, changeSet.state());
        assertTrue(changeSet.incompleteReasons().contains(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR));
    }

    /** SKIP 路径跳过结果中间件时，下一个 Tool 准入先持久折叠遗留风险并清理 marker。 */
    @Test
    void skippedUnknownRecoveryCanContinueToAnotherToolAndCompletePartial() {
        TurnChangeSetCodec codec = codec();
        JaTurnChangeSetMiddleware middleware = middleware(codec,
                tool("remote_mutator", JaTool.WorkspaceMutationMode.UNOBSERVABLE),
                tool("read_local", JaTool.WorkspaceMutationMode.NONE));
        AgentMiddleware.ExtensionStateView state = prepare(middleware, "remote_mutator", "call_skipped", 0, emptyState());
        state = prepare(middleware, "read_local", "call_after_skip", 1, state);
        state = project(middleware, "read_local", "call_after_skip", 1, true, ToolOutcome.SUCCEEDED,
                List.of(), state);
        TurnChangeSet changeSet = changeSet(complete(middleware, state, true), codec);

        assertEquals(TurnChangeSet.State.PARTIAL, changeSet.state());
        assertTrue(changeSet.incompleteReasons().contains(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY));
        assertTrue(changeSet.incompleteReasons().contains(TurnChangeSet.IncompleteReason.UNKNOWN_MUTATOR));
        assertTrue(state.value().members().keySet().stream().noneMatch(key -> key.startsWith("pending_")));
    }

    /** 完成候选里的持久未核验风险会降级 ChangeSet，即使结果 hook 没有工具级 marker。 */
    @Test
    void durableUnverifiedEffectMarksCompletionPartial() {
        TurnChangeSetCodec codec = codec();
        JaTurnChangeSetMiddleware middleware = middleware(codec);
        TurnChangeSet changeSet = changeSet(complete(middleware, emptyState(), true), codec);

        assertEquals(TurnChangeSet.State.PARTIAL, changeSet.state());
        assertTrue(changeSet.incompleteReasons().contains(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY));
    }

    /** 用相同稳定执行身份和完整中间件状态应用 patch，模拟 Core 的同事务 revision 推进。 */
    private static AgentMiddleware.ExtensionStateView prepare(JaTurnChangeSetMiddleware middleware, String toolName,
            String callId, int ordinal, AgentMiddleware.ExtensionStateView state) {
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolDecision> decision =
                middleware.beforeTool(toolContext(toolName, callId, ordinal, state));
        assertInstanceOf(AgentMiddleware.ToolProceed.class, decision.decision());
        return apply(decision.statePatch(), state);
    }

    /** 把确定 Started 状态和持久 Ja facts 送入工具结果 hook，并保留恢复到内存外的状态快照。 */
    private static AgentMiddleware.ExtensionStateView project(JaTurnChangeSetMiddleware middleware, String toolName,
            String callId, int ordinal, boolean executionStarted, ToolOutcome outcome,
            List<AgentTool.ExtensionFact> facts, AgentMiddleware.ExtensionStateView state) {
        AgentMiddleware.ToolResultContext context = new AgentMiddleware.ToolResultContext(IDENTITY,
                invocation(toolName, callId, ordinal), outcome, executionStarted, "result", Optional.empty(), facts, state);
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> decision =
                middleware.projectToolResult(context);
        return apply(decision.statePatch(), state);
    }

    /** 构造 Core 在所有中间件接受 Complete 后才提交的候选 ChangeSet 事实。 */
    private static AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> complete(
            JaTurnChangeSetMiddleware middleware, AgentMiddleware.ExtensionStateView state,
            boolean hasUnverifiedEffects) {
        return middleware.beforeCompletion(new AgentMiddleware.CompletionContext(
                IDENTITY, "completed", false, hasUnverifiedEffects, state));
    }

    /** 将 patch 作为 Store 同事务 revision 更新，空 patch 保留原 revision。 */
    private static AgentMiddleware.ExtensionStateView apply(AgentMiddleware.ExtensionStatePatch patch,
            AgentMiddleware.ExtensionStateView state) {
        JsonObject value = patch.apply(state);
        long revision = patch.isEmpty() ? state.revision() : Math.addExact(state.revision(), 1L);
        return new AgentMiddleware.ExtensionStateView(NAMESPACE, revision, SCHEMA_VERSION, value);
    }

    /** 以本请求固定身份和工具目录创建新的中间件实例，模拟进程恢复时重建扩展。 */
    private JaTurnChangeSetMiddleware middleware(TurnChangeSetCodec codec, JaTool... tools) {
        return new JaTurnChangeSetMiddleware(IDENTITY, workspace, WORKSPACE_ID, List.of(tools), List.of(), codec);
    }

    /** 创建空 namespace 状态，测试不得通过读取文件系统推断执行前历史。 */
    private static AgentMiddleware.ExtensionStateView emptyState() {
        return AgentMiddleware.ExtensionStateView.empty(NAMESPACE, SCHEMA_VERSION);
    }

    /** 构造完整的最终参数上下文，使 workspace mode 只来自显式 Ja Tool 声明。 */
    private static AgentMiddleware.ToolContext toolContext(String name, String callId, int ordinal,
            AgentMiddleware.ExtensionStateView state) {
        AgentTool.Invocation invocation = invocation(name, callId, ordinal);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(
                IDENTITY, IDENTITY.toolCallKey(callId), Map.of());
        return new AgentMiddleware.ToolContext(IDENTITY, execution, invocation, ToolSideEffect.EXTERNAL,
                AgentTool.ApprovalRequirement.REQUIRED, state);
    }

    /** 构造与 Core Assistant tool call 相同身份和批次 ordinal 的调用值。 */
    private static AgentTool.Invocation invocation(String name, String callId, int ordinal) {
        return new AgentTool.Invocation(callId, name, JsonObject.empty(), ordinal);
    }

    /** 构造最小声明式 Ja Tool；此测试只触发元数据，不执行真实业务动作。 */
    private static JaTool tool(String name, JaTool.WorkspaceMutationMode mode) {
        return new JaTool() {
            /** 固定一个无参数 Schema，避免测试依赖工具实现的路由输入。 */
            @Override public ToolSpec spec() { return new ToolSpec(name, "Fixture tool", JsonObject.empty()); }
            /** 声明 workspace 副作用模式供 Ja middleware 按值决策。 */
            @Override public JaTool.WorkspaceMutationMode workspaceMutationMode() { return mode; }
            /** 该 fixture 不应进入真实工具派发。 */
            @Override public java.util.concurrent.CompletionStage<ToolResult> execute(
                    AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                    io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellationToken) {
                throw new AssertionError("fixture Tool must not execute");
            }
        };
    }

    /** 使用唯一规范 codec 解码终态 fact，测试实际持久 schema 而不是实现内对象。 */
    private static TurnChangeSet changeSet(
            AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> completion,
            TurnChangeSetCodec codec) {
        assertInstanceOf(AgentMiddleware.Complete.class, completion.decision());
        AgentMiddleware.MiddlewareFact fact = completion.facts().stream()
                .filter(value -> value.type().equals("change-set")).findFirst().orElseThrow();
        assertEquals(1, fact.schemaVersion());
        return codec.readPayload(fact.payload());
    }

    /** 提取终态 diff Artifact 正文，确保 ChangeSet metadata 和真实 diff 同批生成。 */
    private static String artifactContent(
            AgentMiddleware.ExtensionDecision<AgentMiddleware.CompletionDecision> completion) {
        JsonObject payload = completion.facts().stream().filter(value -> value.type().equals("change-set-artifact"))
                .findFirst().orElseThrow().payload();
        return ((io.github.kongweiguang.ja.agent.domain.json.JsonText) payload.get("content")).value();
    }

    /** 按 production receipt schema 构造收据，字节长度和指纹都由 UTF-8 定义。 */
    private static AgentTool.ExtensionFact receipt(String relativePath, boolean beforeExists, String before,
            boolean afterExists, String after) {
        Utf8Fingerprint beforeHash = Utf8Fingerprint.of(before);
        Utf8Fingerprint afterHash = Utf8Fingerprint.of(after);
        JsonObject payload = JsonObjects.builder().putText("workspaceId", WORKSPACE_ID)
                .putText("relativePath", relativePath).putBoolean("beforeExists", beforeExists)
                .putBoolean("afterExists", afterExists).putText("beforeText", before).putText("afterText", after)
                .putNumber("beforeBytes", beforeHash.byteLength()).putNumber("afterBytes", afterHash.byteLength())
                .putText("beforeSha256", beforeHash.sha256()).putText("afterSha256", afterHash.sha256())
                .putBoolean("confined", true).build();
        return new AgentTool.ExtensionFact("ja.workspace", "mutation-receipt", 1, payload);
    }

    /** 为测试创建独立的严格 codec；不使用 Solon Bean 或共享 ObjectMapper 状态。 */
    private static TurnChangeSetCodec codec() {
        return new TurnChangeSetCodec(new ObjectMapper());
    }
}
