// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.ToolPresentation;
import io.github.kongweiguang.ja.conversation.domain.permission.AccessMode;
import io.github.kongweiguang.ja.foundation.json.JsonArray;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonText;
import io.github.kongweiguang.ja.foundation.json.JsonValue;
import io.github.kongweiguang.ja.task.domain.TaskModels;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

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
                new UserContent(java.util.List.of(new TextContent("next"))), java.util.List.of());
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
                "item_message", Instant.EPOCH, "turn_target", "thr_sender", "发送方标题", "阶段结果");

        var result = RpcResults.snapshotItem(new ObjectMapper(), item);

        assertEquals("thread_message", result.path("kind").textValue());
        assertEquals("thr_sender", result.path("sourceThreadId").textValue());
        assertEquals("发送方标题", result.path("sourceTitle").textValue());
        assertEquals("阶段结果", result.path("content").textValue());
    }

    /** USER item 内联精确摘要且不再产生独立 attachment snapshot item。 */
    @Test
    void mapsAttachmentSummaryInsideOwningUserItem() {
        ThreadSnapshot.UserInputItem item = new ThreadSnapshot.UserInputItem(
                "item_user", Instant.EPOCH, "turn_second",
                new UserContent(List.of(new io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent(
                        "att_capture"))),
                List.of(new AttachmentSummary(
                        "att_capture", "capture.png", 128, "image", "image/png")));

        var result = RpcResults.snapshotItem(new ObjectMapper(), item);

        assertEquals("user_input", result.path("kind").textValue());
        assertEquals("att_capture", result.path("attachments").get(0).path("attachmentId").textValue());
        assertFalse(result.path("attachments").get(0).has("state"));
    }

    /** Turn 历史只公开生命周期和稳定错误码，请求级 Provider 事实由 Usage 投影承担。 */
    @Test
    void mapsFrozenTurnRuntimeWithoutErrorMessage() {
        ThreadSnapshot.Turn turn = new ThreadSnapshot.Turn(
                "turn_failed", "failed", Instant.EPOCH, Instant.EPOCH, Instant.EPOCH,
                "INTERNAL_ERROR", null);

        var result = RpcResults.snapshotTurn(new ObjectMapper(), turn);

        assertEquals("INTERNAL_ERROR", result.path("errorCode").textValue());
        assertFalse(result.has("runtime"));
        assertFalse(result.has("errorMessage"));
    }

    /** Thread 偏好通过 Wire 显式公开协作模式，且与 full access 保持两个独立字段。 */
    @Test
    void mapsCollaborationModeIndependentlyFromAccessMode() {
        ThreadPreferences preferences = new ThreadPreferences("provider_test", "model_test", null,
                AccessMode.FULL_ACCESS, CollaborationMode.PLAN, ThreadPreferences.TitleSource.PLACEHOLDER);
        ThreadSummary thread = new ThreadSummary("thr_test", "ws_test", "Test", preferences,
                ThreadSummary.Status.ACTIVE, false, null, true, null, 0, Instant.EPOCH, Instant.EPOCH);

        var result = RpcResults.thread(new ObjectMapper(), thread).path("preferences");

        assertEquals("full_access", result.path("accessMode").textValue());
        assertEquals("plan", result.path("collaborationMode").textValue());
    }

    /** 侧边任务详情只公开 USER/ASSISTANT 文本和附件，Tool 参数及 System 内容不能越过投影边界。 */
    @Test
    void projectsBoundedInheritedContextWithoutInternalBlocks() {
        String longText = "界".repeat(600);
        JsonObject context = object("messages", array(
                message("SYSTEM", object("kind", text("text"), "text", text("hidden system"))),
                message("USER", object("kind", text("text"), "text", text(longText)),
                        object("kind", text("attachment"), "attachmentId", text("att_capture")),
                        object("kind", text("tool_call"), "arguments", text("secret argument"))),
                message("ASSISTANT", object("kind", text("tool_result"), "content", text("hidden result"))),
                message("ASSISTANT", object("kind", text("text"), "text", text("safe answer")))));

        var result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                context));

        var preview = result.path("inheritedContextPreview");
        assertEquals(2, preview.size());
        assertEquals("user", preview.get(0).path("role").textValue());
        assertEquals(512, preview.get(0).path("text").textValue().codePointCount(0,
                preview.get(0).path("text").textValue().length()));
        assertEquals("att_capture", preview.get(0).path("attachmentIds").get(0).textValue());
        assertEquals("safe answer", preview.get(1).path("text").textValue());
        assertFalse(result.toString().contains("hidden"));
        assertFalse(result.toString().contains("secret argument"));
    }

    /** Subagent 的 BRIEF_ONLY 种子必须显式返回 null 摘要和空预览，客户端无需猜测继承语义。 */
    @Test
    void returnsEmptyInheritedPreviewForBriefOnlySeed() {
        var result = RpcResults.taskSeed(new ObjectMapper(), seed(TaskModels.InheritanceMode.BRIEF_ONLY, null));

        assertNull(result.get("inheritedContextSummary").textValue());
        assertEquals(0, result.path("inheritedContextPreview").size());
    }

    /** 构造历史 Wire 测试所需的最小安全展示 DTO。 */
    private static ToolPresentation presentation() {
        return new ToolPresentation(ToolPresentation.Kind.READ, "read", ToolPresentation.Status.SUCCESS,
                null, "ok", List.of("a.txt"), null, null, null, null, null, 1L, false, null);
    }

    /** 构造有效 seed，确保测试只聚焦 Wire 安全投影。 */
    private static TaskModels.ContextSeed seed(TaskModels.InheritanceMode mode, JsonObject context) {
        return new TaskModels.ContextSeed("seed_fixture", "thr_parent", "turn_parent", 7, mode,
                new UserContent(List.of(new TextContent("brief"))), context, new JsonArray(List.of()),
                JsonObject.empty(), "a".repeat(64), Instant.EPOCH);
    }

    /** 构造冻结消息的真实 role/blocks 结构，避免测试依赖字符串 JSON 解析。 */
    private static JsonObject message(String role, JsonObject... blocks) {
        return object("role", text(role), "blocks", array(blocks));
    }

    /** 按插入顺序构造小型基础 JSON 对象。 */
    private static JsonObject object(Object... entries) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], (JsonValue) entries[index + 1]);
        }
        return new JsonObject(values);
    }

    /** 把固定测试值包装为基础 JSON 文本。 */
    private static JsonText text(String value) {
        return new JsonText(value);
    }

    /** 把固定测试值包装为基础 JSON 数组。 */
    private static JsonArray array(JsonValue... values) {
        return new JsonArray(List.of(values));
    }
}
