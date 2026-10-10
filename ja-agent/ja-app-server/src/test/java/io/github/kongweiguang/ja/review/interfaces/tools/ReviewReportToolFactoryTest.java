// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;
import io.github.kongweiguang.ja.support.TestJsonValueCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证模型可见的 Review 工具只读取绑定快照，且报告坐标与实际读取证据一致。 */
final class ReviewReportToolFactoryTest {
    private static final TestJsonValueCodec CODEC = new TestJsonValueCodec();
    private static final String PATH = "src/Main.java";
    private static final String CURRENT = "class Main {\n  void run() { throw new IllegalStateException(); }\n}\n";
    private static final String PATCH = "@@ -1,3 +1,3 @@\n-class Main {}\n+" + CURRENT;

    /** Rust 的每个合法 Git 状态均可进入冻结读取，避免单测只用 modified 掩盖真实仓库准入缺口。 */
    @ParameterizedTest
    @ValueSource(strings = {"added", "modified", "deleted", "renamed", "copied", "conflicted", "untracked"})
    void acceptsEverySerializedGitStatus(String status) {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger(), status);

        JaTool.ToolResult result = invoke(fixture, "review_snapshot_list", JsonObject.empty(),
                "list_" + status, CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        JsonObject file = (JsonObject) ((JsonArray) object(result.content()).get("files")).values().getFirst();
        assertEquals(status, text(file.get("status")));
        assertEquals(ToolOutcome.SUCCEEDED, read(fixture, "patch", 0, 60_000, "read_" + status).outcome());
    }

    /** 错拼状态不能变成兼容别名或绕过校验，失败也不得留下冻结报告。 */
    @ParameterizedTest
    @ValueSource(strings = {"conflict", "unknown"})
    void rejectsStatesOutsideRustSnapshotContract(String status) {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger(), status);

        JaTool.ToolResult result = invoke(fixture, "review_snapshot_list", JsonObject.empty(),
                "invalid_" + status, CancellationToken.none());

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("TOOL_ARGUMENTS_INVALID", result.errorCode());
        assertFalse(result.artifactContent().isPresent());
    }

    /** Review 目录只暴露冻结读取与报告提交，不继承 Workspace、Shell 或 MCP 写能力。 */
    @Test
    void exposesOnlyReadOnlySnapshotTools() {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());

        assertEquals(Set.of("review_snapshot_list", "review_snapshot_read", "review_report_submit"),
                fixture.tools().stream().map(tool -> tool.spec().name()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(fixture.tools().stream().allMatch(tool -> tool.sideEffect() == ToolSideEffect.READ_ONLY));
        assertTrue(fixture.tools().stream().allMatch(tool ->
                tool.workspaceMutationMode() == JaTool.WorkspaceMutationMode.NONE));
    }

    /** 完整读取 patch 后才允许提交报告，artifact 同时固定快照身份、层、行号和原文证据。 */
    @Test
    void persistsReportOnlyForFullyReadSnapshotEvidence() {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());
        JaTool.ToolResult listed = invoke(fixture, "review_snapshot_list", JsonObject.empty(), "list", CancellationToken.none());
        assertEquals(ToolOutcome.SUCCEEDED, listed.outcome());
        assertEquals("rev_" + "a".repeat(24), text(object(listed.content()).get("snapshotId")));

        JaTool.ToolResult read = read(fixture, "patch", 0, 60_000, "read_patch");
        assertEquals(ToolOutcome.SUCCEEDED, read.outcome());
        assertEquals(PATCH, text(object(read.content()).get("content")));

        JaTool.ToolResult submitted = invoke(fixture, "review_report_submit",
                reportInput(List.of(reviewed(PATH, "unstaged")),
                        List.of(finding(PATH, "unstaged", "current", 2, 2,
                                "  void run() { throw new IllegalStateException(); }"))),
                "submit_report", CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, submitted.outcome(), submitted::toString);
        assertTrue(submitted.artifactContent().isPresent());
        JsonObject report = CODEC.decodeObject(submitted.artifactContent().orElseThrow());
        assertEquals("rev_" + "a".repeat(24), text(report.get("snapshotId")));
        assertEquals("b".repeat(64), text(report.get("snapshotHash")));
        JsonObject coverage = (JsonObject) report.get("coverage");
        assertTrue(bool(coverage.get("complete")));
        assertTrue(bool(coverage.get("reviewComplete")));
        assertEquals(PATH, text(((JsonObject) ((JsonArray) coverage.get("filesReviewed")).values().getFirst())
                .get("path")));
        assertEquals(text(((JsonObject) submitted.structuredContent().orElseThrow()).get("reportId")),
                text(report.get("reportId")));
    }

    /** 读取工具只暴露快照真实提供的文本 side，不通过磁盘或其他层回退。 */
    @Test
    void readsAvailableSnapshotSidesAndRejectsUnknownSides() {
        Fixture invalid = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());
        JaTool.ToolResult base = read(invalid, "base", 0, 60_000, "read_base");
        JaTool.ToolResult badDisk = read(invalid, "disk", 0, 60_000, "read_bad_disk");
        assertEquals(ToolOutcome.SUCCEEDED, base.outcome());
        assertEquals("class Main {}\n", text(object(base.content()).get("content")));
        assertEquals(ToolOutcome.FAILED, badDisk.outcome());
    }

    /** 行号、side 与 evidence 必须和冻结文件正文完全相符才可生成 artifact。 */
    @Test
    void rejectsInvalidFindingLocationAndEvidence() {
        assertFindingRejected(finding(PATH, "unstaged", "current", 99, 99, "missing"), "bad_line");
        assertFindingRejected(finding(PATH, "unstaged", "current", 2, 2, "different text"), "bad_evidence");
        assertFindingRejected(finding(PATH, "unstaged", "patch", 2, 2, "-class Main {}"), "bad_side");
        assertFindingRejected(finding(PATH, "comparison", "current", 2, 2,
                "  void run() { throw new IllegalStateException(); }"), "bad_finding_layer");
    }

    /** 同一冻结输入上的错误报告不能留下成功 artifact，修正坐标与证据后应能正常重试。 */
    @Test
    void allowsCorrectedReportAfterInvalidSubmission() {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());
        assertEquals(ToolOutcome.SUCCEEDED,
                read(fixture, "patch", 0, 60_000, "retry_read").outcome());

        JaTool.ToolResult rejected = invoke(fixture, "review_report_submit",
                reportInput(List.of(reviewed(PATH, "unstaged")),
                        List.of(finding(PATH, "unstaged", "current", 99, 99, "missing"))),
                "retry_rejected", CancellationToken.none());
        assertEquals(ToolOutcome.FAILED, rejected.outcome());
        assertFalse(rejected.artifactContent().isPresent());

        JaTool.ToolResult corrected = invoke(fixture, "review_report_submit",
                reportInput(List.of(reviewed(PATH, "unstaged")),
                        List.of(finding(PATH, "unstaged", "current", 2, 2,
                                "  void run() { throw new IllegalStateException(); }"))),
                "retry_corrected", CancellationToken.none());
        assertEquals(ToolOutcome.SUCCEEDED, corrected.outcome(), corrected::toString);
        assertTrue(corrected.artifactContent().isPresent());
    }

    /** 部分快照可以提交诚实的部分报告，但不能把二进制缺口折算成完整审查。 */
    @Test
    void reportsPartialSnapshotWithoutClaimingCompleteReview() {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), true, new AtomicInteger());
        assertEquals(ToolOutcome.SUCCEEDED,
                read(fixture, "patch", 0, 60_000, "partial_read").outcome());

        JaTool.ToolResult submitted = invoke(fixture, "review_report_submit",
                reportInput(List.of(reviewed(PATH, "unstaged")), List.of()), "partial_report", CancellationToken.none());

        assertEquals(ToolOutcome.SUCCEEDED, submitted.outcome(), submitted::toString);
        JsonObject coverage = (JsonObject) CODEC.decodeObject(submitted.artifactContent().orElseThrow()).get("coverage");
        assertFalse(bool(coverage.get("complete")));
        assertFalse(bool(coverage.get("reviewComplete")));
        assertEquals("binary_file", text(((JsonArray) coverage.get("partialReasons")).values().getFirst()));
    }

    /** 读取取消和附件故障均返回真实失败，不伪造空快照或成功结果。 */
    @Test
    void preservesCancellationAndAttachmentFailure() {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());
        CancellationToken cancelled = new CancellationToken() {
            /** 测试令牌在调用前即已取消，验证工具不会访问附件端口。 */
            @Override
            public boolean isCancellationRequested() {
                return true;
            }

            /** 提供不含业务数据的稳定测试原因。 */
            @Override
            public Optional<String> reason() {
                return Optional.of("test cancellation");
            }

            /** 已取消令牌不需要持久回调。 */
            @Override
            public Registration onCancellation(Runnable callback) {
                return Registration.noop();
            }
        };
        JaTool.ToolResult stopped = invoke(fixture, "review_snapshot_list", JsonObject.empty(), "cancel_list", cancelled);
        assertEquals(ToolOutcome.CANCELLED, stopped.outcome());

        List<JaTool> brokenTools = ReviewReportToolFactory.createTools(new ReviewContext("att_broken"),
                request -> { throw new IllegalStateException("storage detail must stay private"); }, CODEC);
        JaTool.ToolResult broken = invoke(new Fixture(brokenTools), "review_snapshot_list",
                JsonObject.empty(), "broken_list", CancellationToken.none());
        assertEquals(ToolOutcome.FAILED, broken.outcome());
        assertEquals("TOOL_IO_FAILED", broken.errorCode());
        assertFalse(broken.content().contains("storage detail"));
    }

    /** 新 Turn 的 Review 工具绑定新附件，即使旧 Tool 实例仍存在也不会复用旧快照正文。 */
    @Test
    void bindsEachReviewFactoryToItsOwnSnapshotAttachment() {
        Fixture first = fixture("att_first", "rev_" + "a".repeat(24), false, new AtomicInteger());
        Fixture second = fixture("att_second", "rev_" + "c".repeat(24), false, new AtomicInteger());

        String firstId = text(object(invoke(first, "review_snapshot_list", JsonObject.empty(),
                "first_list", CancellationToken.none()).content()).get("snapshotId"));
        String secondId = text(object(invoke(second, "review_snapshot_list", JsonObject.empty(),
                "second_list", CancellationToken.none()).content()).get("snapshotId"));

        assertEquals("rev_" + "a".repeat(24), firstId);
        assertEquals("rev_" + "c".repeat(24), secondId);
    }

    /** 未能完整交付一个有效文件时，任意报告声明都不能获得成功 Tool outcome。 */
    private static void assertFindingRejected(JsonObject finding, String callSuffix) {
        Fixture fixture = fixture("att_review", "rev_" + "a".repeat(24), false, new AtomicInteger());
        assertEquals(ToolOutcome.SUCCEEDED, read(fixture, "patch", 0, 60_000,
                callSuffix + "_read").outcome());
        JaTool.ToolResult result = invoke(fixture, "review_report_submit",
                reportInput(List.of(reviewed(PATH, "unstaged")), List.of(finding)),
                callSuffix, CancellationToken.none());
        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertFalse(result.artifactContent().isPresent());
    }

    /** 构造一个受管快照和严格文本读取器，计数只用于确认新工厂没有共享旧附件访问器。 */
    private static Fixture fixture(String attachmentId, String snapshotId, boolean partial,
                                   AtomicInteger attachmentReads) {
        return fixture(attachmentId, snapshotId, partial, attachmentReads, "modified");
    }

    /** 单独变异 status，保留真实工具调用与附件解析链路，禁止为准入测试直接调用私有 parser。 */
    private static Fixture fixture(String attachmentId, String snapshotId, boolean partial,
                                   AtomicInteger attachmentReads, String status) {
        List<JsonValue> files = new ArrayList<>();
        files.add(file(PATH, "unstaged", status, false, "class Main {}\n", CURRENT, PATCH));
        if (partial) {
            files.add(file("assets/logo.bin", "untracked", "untracked", true, null, null, null));
        }
        JsonObject coverage = JsonObjects.builder().putBoolean("complete", !partial)
                .putNumber("files", files.size()).putNumber("bytes", 128)
                .putNumber("maxFiles", 2_000).putNumber("maxBytes", 64 * 1024 * 1024)
                .put("partialReasons", partial ? texts("binary_file") : new JsonArray(List.of())).build();
        String hash = "b".repeat(64);
        String revision = "d".repeat(64);
        JsonObject snapshot = JsonObjects.builder().putNumber("version", 1).putText("snapshotId", snapshotId)
                .putText("workspaceId", "ws_review").put("source", JsonObjects.builder()
                        .putText("kind", "uncommitted").build())
                .putText("revision", revision).putText("snapshotHash", hash).put("coverage", coverage)
                .put("files", new JsonArray(files)).build();
        byte[] bytes = CODEC.encode(snapshot).getBytes(StandardCharsets.UTF_8);
        ManagedAttachmentReader reader = request -> {
            attachmentReads.incrementAndGet();
            int from = Math.toIntExact(request.offsetBytes());
            int to = Math.min(bytes.length, from + request.maxBytes());
            String content = new String(bytes, from, to - from, StandardCharsets.UTF_8);
            return new ManagedAttachmentReader.ReadResult(attachmentId, "review-snapshot.json", bytes.length,
                    "text", "application/json", from, to, to == bytes.length, "utf-8", content);
        };
        return new Fixture(ReviewReportToolFactory.createTools(new ReviewContext(attachmentId), reader, CODEC));
    }

    /** 显式传入 Rust 序列化状态，不从 binary 或 layer 猜测 Git 状态，保持各字段独立。 */
    private static JsonObject file(String path, String layer, String status, boolean binary,
                                   String base, String current, String patch) {
        return JsonObjects.builder().putText("path", path).put("oldPath", JsonNull.INSTANCE)
                .putText("status", status).putText("layer", layer)
                .putBoolean("binary", binary).put("baseContent", nullableText(base))
                .put("content", nullableText(current)).put("patch", nullableText(patch))
                .put("baseSha256", JsonNull.INSTANCE).put("contentSha256", JsonNull.INSTANCE)
                .put("patchSha256", JsonNull.INSTANCE).build();
    }

    /** 将一个冻结侧读完，偏移始终按 code point 而非 UTF-16 code unit 表示。 */
    private static JaTool.ToolResult read(Fixture fixture, String side, int offset, int maximum, String callSuffix) {
        JsonObject arguments = JsonObjects.builder().putText("path", PATH).putText("layer", "unstaged")
                .putText("side", side).putNumber("offsetCharacters", offset).putNumber("maxCharacters", maximum).build();
        return invoke(fixture, "review_snapshot_read", arguments, callSuffix, CancellationToken.none());
    }

    /** 通过 Tool 名查找本轮冻结目录中的唯一实现。 */
    private static JaTool.ToolResult invoke(Fixture fixture, String name, JsonObject arguments,
                                            String callSuffix, CancellationToken cancellation) {
        JaTool tool = fixture.tools().stream().filter(candidate -> candidate.spec().name().equals(name))
                .findFirst().orElseThrow();
        String callId = "call_" + callSuffix;
        return tool.execute(new AgentTool.Invocation(callId, name, arguments, 0), context(callId), cancellation)
                .toCompletableFuture().join();
    }

    /** 组装 Review finding 输入，保持所有定位字段显式而便于单维度变异。 */
    private static JsonObject finding(String path, String layer, String side, int startLine, int endLine,
                                      String evidence) {
        return JsonObjects.builder().putText("severity", "P1").putText("title", "Invalid state transition")
                .putText("body", "The changed method can fail unexpectedly.").putText("path", path)
                .putText("layer", layer).putNumber("startLine", startLine).putNumber("endLine", endLine)
                .putText("side", side).putText("evidence", evidence).build();
    }

    /** 构造闭集报告输入；snapshot identity 只能从绑定附件派生，不能由模型指定。 */
    private static JsonObject reportInput(List<JsonObject> reviewed, List<JsonObject> findings) {
        return JsonObjects.builder().putText("summary", "One actionable issue was found.")
                .put("filesReviewed", new JsonArray(reviewed.stream().map(JsonValue.class::cast).toList()))
                .put("findings", new JsonArray(findings.stream().map(JsonValue.class::cast).toList())).build();
    }

    /** 以冻结 path/layer 二元组表达已审查文件身份。 */
    private static JsonObject reviewed(String path, String layer) {
        return JsonObjects.builder().putText("path", path).putText("layer", layer).build();
    }

    /** 从 ToolResult 编码的 JSON object 读取测试断言对象。 */
    private static JsonObject object(String encoded) {
        return CODEC.decodeObject(encoded);
    }

    /** 只接受测试预期的文本 JSON 节点。 */
    private static String text(JsonValue value) {
        assertTrue(value instanceof JsonText);
        return ((JsonText) value).value();
    }

    /** 只接受真实 JSON boolean，不把字符串或数字转换成覆盖状态。 */
    private static boolean bool(JsonValue value) {
        assertTrue(value instanceof io.github.kongweiguang.ja.agent.domain.json.JsonBoolean);
        return ((io.github.kongweiguang.ja.agent.domain.json.JsonBoolean) value).value();
    }

    /** 在没有 source side 时使用显式 JSON null，避免测试伪造字段缺失。 */
    private static JsonValue nullableText(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /** 将固定 partial reason 转为严格 JSON 文本数组。 */
    private static JsonArray texts(String... values) {
        return new JsonArray(java.util.Arrays.stream(values).map(JsonText::new)
                .map(JsonValue.class::cast).toList());
    }

    /** 固定 Turn 与 Workspace 身份，使测试覆盖真正的 JaTool 执行上下文校验。 */
    private static JaTool.ExecutionContext context(String callId) {
        AgentIdentity identity = new AgentIdentity("tenant_review", "thr_review", "turn_review", "user_review");
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId), identity.threadId(),
                identity.turnId(), Path.of("C:\\ja-review-test"), AccessMode.APPROVAL_REQUIRED,
                "cfg_review", Instant.now().plusSeconds(30), "ws_review", null, null, TurnOrigin.USER);
    }

    /**
     * 工厂测试包装器构造后冻结工具列表，避免夹具改写本轮能力目录。
     *
     * @param tools 当前测试请求允许使用的 Review 工具
     */
    private record Fixture(List<JaTool> tools) {
        /** 复制请求级工具目录，确保测试调用与生产工具集合使用相同不可变边界。 */
        private Fixture {
            tools = List.copyOf(tools);
        }
    }
}
