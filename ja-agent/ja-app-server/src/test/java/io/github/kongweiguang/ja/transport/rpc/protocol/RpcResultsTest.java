// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.context.ContextMessage;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.agent.domain.model.ReasoningContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 锁定历史快照与实时审批事件共享同一小写 wire 词汇表。 */
final class RpcResultsTest {
    /** SQLite 枚举使用大写，thread/read 必须在唯一 RPC 边界归一化而不能泄露存储格式。 */
    @Test
    void mapsPersistedApprovalDecisionToWireVocabulary() {
        ThreadSnapshot.ApprovalItem item = new ThreadSnapshot.ApprovalItem(
                "item_fixture", Instant.EPOCH, "appr_fixture", "turn_fixture",
                "call_fixture", "shell", "需要执行命令", "APPROVE", Instant.EPOCH);

        assertEquals("approve", RpcResults.snapshotItem(new ObjectMapper(), item).path("decision").textValue());
    }

    /** 平坦 item 必须显式携带所属 Turn，避免客户端把第二轮消息错误并入第一轮。 */
    @Test
    void mapsEveryFlatItemToItsPersistedTurnIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        ThreadSnapshot.UserInputItem text = new ThreadSnapshot.UserInputItem(
                "item_text", Instant.EPOCH, "turn_second",
                new UserContent(java.util.List.of(new TextContent("next"))), java.util.List.of(), 4);
        ThreadSnapshot.ToolItem tool = new ThreadSnapshot.ToolItem(
                "item_tool", Instant.EPOCH, "turn_second", ThreadSnapshot.ToolKind.TOOL_CALL,
                "call_fixture", "read_file", presentation(), 1);

        assertEquals("turn_second", RpcResults.snapshotItem(mapper, text).path("turnId").textValue());
        assertEquals("turn_second", RpcResults.snapshotItem(mapper, tool).path("turnId").textValue());
    }

    /** 跨会话消息必须保留来源 Thread 标题快照，并使用独立 kind，不能退化成 user_input。 */
    @Test
    void mapsThreadMessageWithFrozenSourceIdentity() {
        ThreadSnapshot.ThreadMessageItem item = new ThreadSnapshot.ThreadMessageItem(
                "item_message", Instant.EPOCH, "turn_target", "thr_sender", "发送方标题", "阶段结果", 4);

        ObjectNode result = RpcResults.snapshotItem(new ObjectMapper(), item);

        assertEquals("thread_message", result.path("kind").textValue());
        assertEquals("thr_sender", result.path("sourceThreadId").textValue());
        assertEquals("发送方标题", result.path("sourceTitle").textValue());
        assertEquals("阶段结果", result.path("content").textValue());
        assertEquals(4, result.path("totalCharacters").intValue());
        assertFalse(result.path("truncated").booleanValue());
    }

    /** USER item 内联精确摘要且不再产生独立 attachment snapshot item。 */
    @Test
    void mapsAttachmentSummaryInsideOwningUserItem() {
        ThreadSnapshot.UserInputItem item = new ThreadSnapshot.UserInputItem(
                "item_user", Instant.EPOCH, "turn_second",
                new UserContent(List.of(new io.github.kongweiguang.ja.agent.domain.model.AttachmentContent(
                        "att_capture"))),
                List.of(new AttachmentSummary(
                        "att_capture", "capture.png", 128, "image", "image/png")), 0);

        ObjectNode result = RpcResults.snapshotItem(new ObjectMapper(), item);

        assertEquals("user_input", result.path("kind").textValue());
        assertEquals("att_capture", result.path("attachments").get(0).path("attachmentId").textValue());
        assertFalse(result.path("attachments").get(0).has("state"));
        assertEquals(0, result.path("totalCharacters").intValue());
        assertFalse(result.path("truncated").booleanValue());
    }

    /** 长快照只携带 Unicode code-point 预览与权威长度，不能拆开代理对或伪报已完整。 */
    @Test
    void projectsUnicodePreviewMetadataForSnapshotText() {
        ObjectMapper mapper = new ObjectMapper();
        String preview = "界".repeat(511) + "😀";
        ThreadSnapshot.TextItem item = new ThreadSnapshot.TextItem(
                "item_long", Instant.EPOCH, "turn_long", ThreadSnapshot.TextKind.FINAL_ANSWER,
                preview, 900, null);

        ObjectNode result = RpcResults.snapshotItem(mapper, item);

        assertEquals(preview, result.path("text").textValue());
        assertEquals(512, result.path("text").textValue().codePointCount(0,
                result.path("text").textValue().length()));
        assertEquals(900, result.path("totalCharacters").intValue());
        assertTrue(result.path("truncated").booleanValue());
    }

    /** Core 的 sealed 内容块必须全部映射，避免未来新增类型静默变成空 Wire 项。 */
    @Test
    void mapsEveryPermittedUserContentBlock() {
        UserContent content = new UserContent(List.of(
                new ResourceReferenceContent("ws_fixture", "src/README.md", "file"),
                new SkillReferenceContent("fixture:guide"), new AttachmentContent("att_capture"),
                new TextContent("question")));

        JsonNode result = RpcResults.userContent(new ObjectMapper(), content);

        assertEquals(4, result.size());
        assertEquals("workspace_reference", result.path(0).path("type").textValue());
        assertEquals("ws_fixture", result.path(0).path("workspaceId").textValue());
        assertEquals("src/README.md", result.path(0).path("relativePath").textValue());
        assertEquals("file", result.path(0).path("kind").textValue());
        assertEquals("skill_reference", result.path(1).path("type").textValue());
        assertEquals("fixture:guide", result.path(1).path("skillId").textValue());
        assertEquals("attachment", result.path(2).path("type").textValue());
        assertEquals("att_capture", result.path(2).path("attachmentId").textValue());
        assertEquals("text", result.path(3).path("type").textValue());
        assertEquals("question", result.path(3).path("text").textValue());
    }

    /** Turn 历史只公开生命周期和稳定错误码，请求级 Provider 事实由 Usage 投影承担。 */
    @Test
    void mapsFrozenTurnRuntimeWithoutErrorMessage() {
        ThreadSnapshot.Turn turn = new ThreadSnapshot.Turn(
                "turn_failed", "failed", Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                "INTERNAL_ERROR", null, 0, 0, null);

        ObjectNode result = RpcResults.snapshotTurn(new ObjectMapper(), turn);

        assertEquals("INTERNAL_ERROR", result.path("errorCode").textValue());
        assertFalse(result.has("runtime"));
        assertFalse(result.has("errorMessage"));
    }

    /** Thread 偏好通过 Wire 显式公开协作模式，且与 full access 保持两个独立字段。 */
    @Test
    void mapsCollaborationModeIndependentlyFromAccessMode() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.FULL_ACCESS, CollaborationMode.PLAN, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = new ThreadSummary("thr_test", "ws_test", "Test", "project", preferences,
                ThreadSummary.Status.ACTIVE, false, null, true, 0, Instant.EPOCH, Instant.EPOCH);

        JsonNode result = RpcResults.thread(new ObjectMapper(), thread).path("preferences");

        assertEquals("full_access", result.path("accessMode").textValue());
        assertEquals("plan", result.path("collaborationMode").textValue());
    }

    /** 历史正文可达 Core 的 10 MB 边界；Wire 预览只扫描预算内 code point 且不拆开 emoji。 */
    @Test
    void projectsBoundedInheritedContextWithoutInternalBlocks() {
        String longText = "界".repeat(255) + "😀" + "界".repeat(9_999_743);
        InitialContextSeed context = contextSeed(List.of(
                new ContextMessage("msg_user", "turn_parent", 1, ContextMessage.Role.USER,
                        List.of(new ContextMessage.TextBlock(longText),
                                new ContextMessage.AttachmentBlock("att_capture")), 1),
                new ContextMessage("msg_assistant", "turn_parent", 2, ContextMessage.Role.ASSISTANT,
                        List.of(new ContextMessage.TextBlock("safe answer"),
                                new ContextMessage.ReasoningBlock(new ReasoningContent("provider", "model",
                                        "responses", "upstream", "a".repeat(64), "reasoning",
                                        "{\"secret\":\"hidden reasoning\"}")),
                                new ContextMessage.ToolCallBlock("call_secret", "secret_tool", "secret argument")), 1),
                new ContextMessage("msg_tool", "turn_parent", 3, ContextMessage.Role.TOOL,
                        List.of(new ContextMessage.ToolResultBlock("call_secret", "secret_tool",
                                ContextMessage.ToolOutput.full("hidden result", null, null, null))), 1)),
                summary("safe summary detail"));

        ObjectNode result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                context));

        JsonNode preview = result.path("inheritedContextPreview");
        assertEquals(2, preview.size());
        assertEquals("user", preview.get(0).path("role").textValue());
        String previewText = preview.get(0).path("text").textValue();
        assertEquals(longText.substring(0, longText.offsetByCodePoints(0, 512)), previewText);
        assertEquals(512, previewText.codePointCount(0, previewText.length()));
        assertFalse(Character.isHighSurrogate(previewText.charAt(previewText.length() - 1)));
        assertEquals("att_capture", preview.get(0).path("attachmentIds").get(0).textValue());
        assertEquals("safe answer", preview.get(1).path("text").textValue());
        assertTrue(result.path("inheritedContextSummary").textValue().contains("safe summary detail"));
        assertFalse(result.toString().contains("hidden result"));
        assertFalse(result.toString().contains("hidden reasoning"));
        assertFalse(result.toString().contains("secret argument"));
    }

    /** 仅有压缩摘要的 typed seed 仍须向任务详情显示冻结背景，不伪造历史消息。 */
    @Test
    void projectsSummaryOnlyTypedSeedWithoutInventingMessages() {
        InitialContextSeed context = contextSeed(List.of(), summary("summary-only fact"));

        ObjectNode result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                context));

        assertTrue(result.path("inheritedContextSummary").textValue().contains("summary-only fact"));
        assertEquals(0, result.path("inheritedContextPreview").size());
    }

    /** 空父历史仍保持协议非空摘要约束，但明确告知用户没有可继承内容。 */
    @Test
    void describesEmptyEffectiveContextWithoutClaimingItWasInherited() {
        ObjectNode result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.EFFECTIVE_CONTEXT, null));

        assertEquals("父任务 revision 7 没有可继承的上下文。",
                result.path("inheritedContextSummary").textValue());
        assertEquals(0, result.path("inheritedContextPreview").size());
    }

    /** 摘要预览严格服从当前 Wire 字符上限，并且只在完整 Unicode code point 边界截断。 */
    @Test
    void truncatesSummaryWithinWireCharacterBudget() {
        String summaryText = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                contextSeed(List.of(), summary("😀".repeat(20_000)))))
                .path("inheritedContextSummary").textValue();

        assertTrue(summaryText.length() <= 32_768);
        assertTrue(summaryText.endsWith("…（摘要预览已截断）"));
        assertFalse(Character.isHighSurrogate(summaryText.charAt(
                summaryText.length() - "…（摘要预览已截断）".length() - 1)));
    }

    /** Subagent 的 BRIEF_ONLY 种子必须显式返回 null 摘要和空预览，客户端无需猜测继承语义。 */
    @Test
    void returnsEmptyInheritedPreviewForBriefOnlySeed() {
        ObjectNode result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.BRIEF_ONLY, null));

        assertNull(result.get("inheritedContextSummary").textValue());
        assertEquals(0, result.path("inheritedContextPreview").size());
    }

    /** 构造历史 Wire 测试所需的最小安全展示 DTO。 */
    private static ToolPresentation presentation() {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", ToolPresentation.Status.SUCCESS,
                null, "ok", "读取完成", List.of("a.txt"), null, null, null, null, null, 1L, false, null);
    }

    /** 构造有效 seed，确保测试只聚焦 Wire 安全投影。 */
    private static TaskModels.ContextSeed seed(TaskModels.InheritanceMode mode, InitialContextSeed context) {
        return new TaskModels.ContextSeed("seed_fixture", "thr_parent", "turn_parent", 7, mode,
                mode == TaskModels.InheritanceMode.BRIEF_ONLY
                        ? new UserContent(List.of(new TextContent("brief"))) : null,
                context, new JsonArray(List.of()),
                JsonObject.empty(), "a".repeat(64), Instant.EPOCH);
    }

    /** 用真实 Core 历史消息和摘要构造 seed，覆盖工具配对约束与冻结来源身份。 */
    private static InitialContextSeed contextSeed(List<ContextMessage> messages, SummaryDocument summary) {
        return new InitialContextSeed(new InitialContextSeed.SourceIdentity(
                "tenant_local", "thr_parent", "turn_parent"), 3, messages, Optional.of(summary));
    }

    /** 构造包含单条当前有效事实的摘要，验证安全文本经现有 summary 字段投影。 */
    private static SummaryDocument summary(String fact) {
        return new SummaryDocument(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new SummaryDocument.Fact(fact, 1)), List.of(), List.of(), List.of());
    }
}
