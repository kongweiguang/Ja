// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证 Review 完成依赖持久快照读取证据，以及与报告回执共同提交的 artifact。 */
final class ReviewCompletionMiddlewareTest {
    private static final TestJsonValueCodec CODEC = new TestJsonValueCodec();
    private static final String ATTACHMENT = "att_review";
    private static final String SNAPSHOT = "rev_" + "a".repeat(24);
    private static final String HASH = "b".repeat(64);
    private static final String REVISION = "d".repeat(64);
    private static final String PATH = "src/Main.java";

    /** 恢复后的中间件继续校验读取范围，拒绝未获证据支持的声明并接受后续修正报告。 */
    @Test
    void restoresCoverageAndAllowsCorrectionAfterRejectedReport() {
        ReviewCompletionMiddleware middleware = middleware();
        AgentMiddleware.ExtensionStateView state = emptyState();
        assertInstanceOf(AgentMiddleware.Continue.class, completion(middleware, state));

        state = project(middleware, state, "review_snapshot_list", ToolOutcome.SUCCEEDED,
                CODEC.encode(snapshotList()), Optional.empty());
        JsonObject unearned = reportArguments(List.of(filePair()));
        assertInstanceOf(AgentMiddleware.ToolReject.class, beforeReport(middleware, state, unearned));

        state = project(middleware, state, "review_snapshot_read", ToolOutcome.SUCCEEDED,
                CODEC.encode(readResult(0, "ab", 2)), Optional.empty());
        ReviewCompletionMiddleware restarted = middleware();
        assertInstanceOf(AgentMiddleware.Continue.class, completion(restarted, state));

        state = project(restarted, state, "review_snapshot_read", ToolOutcome.SUCCEEDED,
                CODEC.encode(readResult(2, "c", null)), Optional.empty());
        JsonObject corrected = reportArguments(List.of(filePair()));
        assertInstanceOf(AgentMiddleware.ToolProceed.class, beforeReport(restarted, state, corrected));

        String artifact = CODEC.encode(reportArtifact(List.of(filePair())));
        state = project(restarted, state, "review_report_submit", ToolOutcome.SUCCEEDED,
                "Review report submitted.", Optional.of(artifact));

        assertInstanceOf(AgentMiddleware.Complete.class, completion(middleware(), state));
    }

    /** 事务未提交时不应用候选完成补丁，恢复后的状态因此仍然阻止 Turn 结束。 */
    @Test
    void doesNotCompleteWhenArtifactAndStatePatchWereNotCommitted() {
        ReviewCompletionMiddleware middleware = middleware();
        AgentMiddleware.ExtensionStateView state = project(middleware, emptyState(), "review_snapshot_list",
                ToolOutcome.SUCCEEDED, CODEC.encode(snapshotList()), Optional.empty());
        state = project(middleware, state, "review_snapshot_read", ToolOutcome.SUCCEEDED,
                CODEC.encode(readResult(0, "abc", null)), Optional.empty());

        String artifact = CODEC.encode(reportArtifact(List.of(filePair())));
        AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> candidate = result(
                middleware, state, "review_report_submit", ToolOutcome.SUCCEEDED, "Review report submitted.",
                Optional.of(artifact));
        assertFalse(candidate.statePatch().isEmpty());

        // Store 事务失败时，Tool artifact 与配对状态补丁都不会成为持久事实。
        assertInstanceOf(AgentMiddleware.Continue.class, completion(middleware(), state));
    }

    /** 使用 Runtime 恢复时相同的冻结附件身份构造请求级中间件。 */
    private static ReviewCompletionMiddleware middleware() {
        return new ReviewCompletionMiddleware(new ReviewContext(ATTACHMENT), CODEC);
    }

    /** 构造新接纳 Review Turn 的零修订持久中间件状态。 */
    private static AgentMiddleware.ExtensionStateView emptyState() {
        return AgentMiddleware.ExtensionStateView.empty(ReviewCompletionMiddleware.NAMESPACE, 1);
    }

    /** 在模拟成功 Store 提交时，同时投影 Tool 结果、artifact 和对应状态补丁。 */
    private static AgentMiddleware.ExtensionStateView project(ReviewCompletionMiddleware middleware,
                                                               AgentMiddleware.ExtensionStateView state,
                                                               String toolName, ToolOutcome outcome,
                                                               String content, Optional<String> artifact) {
        return commit(state, result(middleware, state, toolName, outcome, content, artifact));
    }

    /** 生成与同一 Tool 记录共提交的中间件结果转换。 */
    private static AgentMiddleware.ExtensionDecision<AgentMiddleware.ToolResultProjection> result(
            ReviewCompletionMiddleware middleware, AgentMiddleware.ExtensionStateView state,
            String toolName, ToolOutcome outcome, String content, Optional<String> artifact) {
        String callId = "call_" + toolName;
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, toolName, JsonObject.empty(), 0);
        return middleware.projectToolResult(new AgentMiddleware.ToolResultContext(identity(), invocation, outcome,
                true, content, Optional.empty(), artifact, List.of(), state));
    }

    /** 仅在模拟成功原子 Store 提交的测试路径中应用候选状态补丁。 */
    private static AgentMiddleware.ExtensionStateView commit(
            AgentMiddleware.ExtensionStateView state,
            AgentMiddleware.ExtensionDecision<?> decision) {
        JsonObject value = decision.statePatch().apply(state);
        long revision = decision.statePatch().isEmpty() ? state.revision() : Math.addExact(state.revision(), 1);
        return new AgentMiddleware.ExtensionStateView(state.namespace(), revision, state.schemaVersion(), value);
    }

    /** 使用结构有效的报告参数调用派发前校验钩子。 */
    private static AgentMiddleware.ToolDecision beforeReport(ReviewCompletionMiddleware middleware,
                                                              AgentMiddleware.ExtensionStateView state,
                                                              JsonObject arguments) {
        String callId = "call_report";
        AgentTool.Invocation invocation = new AgentTool.Invocation(callId, "review_report_submit", arguments, 0);
        AgentTool.ExecutionContext execution = new AgentTool.ExecutionContext(identity(),
                identity().toolCallKey(callId), Map.of());
        return middleware.beforeTool(new AgentMiddleware.ToolContext(identity(), execution, invocation,
                ToolSideEffect.READ_ONLY, AgentTool.ApprovalRequirement.NOT_REQUIRED, state)).decision();
    }

    /** 用新建 Runtime 可见的持久状态评估是否允许完成。 */
    private static AgentMiddleware.CompletionDecision completion(ReviewCompletionMiddleware middleware,
                                                                  AgentMiddleware.ExtensionStateView state) {
        return middleware.beforeCompletion(new AgentMiddleware.CompletionContext(identity(), "Review summary",
                false, false, state)).decision();
    }

    /** 构造仅含一个 patch 侧文件且快照身份固定的清单。 */
    private static JsonObject snapshotList() {
        JsonObject coverage = JsonObjects.builder().putBoolean("complete", true).putNumber("files", 1)
                .putNumber("bytes", 3).putNumber("maxFiles", 2_000).putNumber("maxBytes", 64 * 1024 * 1024)
                .put("partialReasons", new JsonArray(List.of())).build();
        JsonObject file = JsonObjects.builder().putText("path", PATH).putText("layer", "unstaged")
                .put("oldPath", JsonNull.INSTANCE).putText("status", "modified").putBoolean("binary", false)
                .putBoolean("hasBase", false).putBoolean("hasCurrent", true).putBoolean("hasPatch", true).build();
        return JsonObjects.builder().putText("snapshotId", SNAPSHOT).putText("snapshotHash", HASH)
                .putText("revision", REVISION).put("coverage", coverage).put("files", array(file)).build();
    }

    /** 构造工具产生的读取区间；中间件按 Unicode code point 计算正文长度。 */
    private static JsonObject readResult(int offset, String content, Integer nextOffset) {
        JsonValue next = nextOffset == null ? JsonNull.INSTANCE
                : new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(nextOffset);
        return JsonObjects.builder().putText("snapshotId", SNAPSHOT).putText("snapshotHash", HASH)
                .putText("path", PATH).putText("layer", "unstaged").putText("side", "patch")
                .put("requiredSides", texts("patch")).putNumber("offsetCharacters", offset)
                .putNumber("totalCharacters", 3).putText("content", content).put("nextOffsetCharacters", next).build();
    }

    /** 构造字段闭合且符合当前 Review schema 的报告 artifact。 */
    private static JsonObject reportArtifact(List<JsonObject> filesReviewed) {
        JsonObject coverage = JsonObjects.builder().putBoolean("complete", true).putNumber("files", 1)
                .putNumber("bytes", 3).putNumber("maxFiles", 2_000).putNumber("maxBytes", 64 * 1024 * 1024)
                .put("partialReasons", new JsonArray(List.of())).put("filesReviewed", array(filesReviewed))
                .putBoolean("reviewComplete", filesReviewed.size() == 1).build();
        return JsonObjects.builder().putText("reportId", "report_" + "c".repeat(24)).putText("snapshotId", SNAPSHOT)
                .putText("snapshotHash", HASH).putText("revision", REVISION).putText("summary", "Review summary")
                .put("coverage", coverage).put("findings", new JsonArray(List.of())).build();
    }

    /** 生成中间件和实际 Tool schema 共用的报告提交参数。 */
    private static JsonObject reportArguments(List<JsonObject> filesReviewed) {
        return JsonObjects.builder().putText("summary", "Review summary").put("filesReviewed", array(filesReviewed))
                .put("findings", new JsonArray(List.of())).build();
    }

    /** 按报告契约形状编码一个路径与变更层身份。 */
    private static JsonObject filePair() {
        return JsonObjects.builder().putText("path", PATH).putText("layer", "unstaged").build();
    }

    /** 转为 JsonValue 列表并保留强类型 JSON 契约。 */
    private static JsonArray array(JsonObject... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonValue.class::cast).toList());
    }

    /** 将夹具列表转换为类型明确的 JSON array。 */
    private static JsonArray array(List<JsonObject> values) {
        return new JsonArray(values.stream().map(JsonValue.class::cast).toList());
    }

    /** 构造快照必需文本侧的严格类型数组。 */
    private static JsonArray texts(String... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonText::new)
                .map(JsonValue.class::cast).toList());
    }

    /** 为各模拟中间件钩子提供同一份完整持久执行身份。 */
    private static AgentIdentity identity() {
        return new AgentIdentity("tenant_review", "thr_review", "turn_review", "user_review");
    }
}
