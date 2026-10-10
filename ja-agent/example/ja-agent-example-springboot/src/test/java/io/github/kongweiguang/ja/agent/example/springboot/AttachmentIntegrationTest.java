// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.Turn;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.HTTP;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.JSON;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.assertProviderContainsBytes;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.awaitState;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.closeEngine;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.createThread;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueAnthropicText;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueResponsesText;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueResponsesTool;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.input;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.model;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.origin;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.pdf;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.png;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.post;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.postTurn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.start;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.upload;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.uploadMetadata;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实 Spring HTTP 与 PostgreSQL 验收附件权限、幂等、输入引用及 Provider 原生多模态请求。
 */
class AttachmentIntegrationTest {
    private static final Set<ModelPort.InputModality> MULTIMODAL = Set.of(
            ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF);
    private static final ContextBudget MEDIA_CONTEXT = ContextBudget.capabilities(262_144, 32_768, true);

    /**
     * Responses 与 Messages 必须把已上传图片和 PDF 的原始字节送入 Provider 请求，不能仅携带附件标识。
     */
    @Test
    void sendsExactImageAndPdfBytesThroughResponsesAndAnthropic() throws Exception {
        for (ModelPort.Api api : List.of(ModelPort.Api.OPENAI_RESPONSES, ModelPort.Api.ANTHROPIC_MESSAGES)) {
            try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
                provider.start();
                if (api == ModelPort.Api.OPENAI_RESPONSES) {
                    enqueueResponsesText(provider, "资料已读取");
                } else {
                    enqueueAnthropicText(provider, "资料已读取");
                }
                ModelPort.ModelConfiguration model = model(api, provider.url("/v1").uri(), MULTIMODAL);
                try (ConfigurableApplicationContext application = start(postgres.getPostgresDatabase(), model, MEDIA_CONTEXT)) {
                    Turn turn = createThread(application, "media_" + api.name().toLowerCase(java.util.Locale.ROOT));
                    byte[] png = png();
                    byte[] pdf = pdf("Project code: CASE-8426; status: APPROVED");
                    JsonNode image = uploadMetadata(turn, "image_write", "evidence.png", "image/png", png);
                    JsonNode document = uploadMetadata(turn, "pdf_write", "evidence.pdf", "application/pdf", pdf);
                    assertUploadRetryIsStable(turn, "image_write", "evidence.png", "image/png", png,
                            png("CASE ID: JA-7320"),
                            image.path("id").asText());

                    JsonNode declared = JSON.readTree(postTurn(turn, "model", "{}").body())
                            .path("attachmentCapabilities");
                    assertTrue(declared.path("effectiveFormats").toString().contains("image/png"));
                    assertTrue(declared.path("effectiveFormats").toString().contains("application/pdf"));
                    assertTrue(declared.path("maxSingleBytes").asLong() >= png.length);

                    verifyPreview(turn, image, png, "image/png");
                    verifyPreview(turn, document, pdf, "application/pdf");

                    String body = JSON.writeValueAsString(Map.of("operationKey", "file_only_submit",
                            "content", input("", List.of(), List.of(image.path("id").asText(), document.path("id").asText()))));
                    assertEquals(202, postTurn(turn, "submit", body).statusCode());
                    awaitState(turn, "COMPLETED");
                    RecordedRequest request = provider.takeRequest(10, TimeUnit.SECONDS);
                    JsonNode providerPayload = JSON.readTree(request.getBody().readUtf8());
                    assertProviderContainsBytes(providerPayload, png, "image/png");
                    assertProviderContainsBytes(providerPayload, pdf, "application/pdf");

                    JsonNode history = JSON.readTree(post(origin(application), "/api/chat/threads/history",
                            Map.of("threadId", turn.threadId(), "limit", 100)).body());
                    JsonNode userInput = userHistoryInput(history);
                    assertEquals(List.of(image.path("id").asText(), document.path("id").asText()),
                            JSON.convertValue(userInput.path("attachmentIds"), JSON.getTypeFactory()
                                    .constructCollectionType(List.class, String.class)));
                    assertEquals("evidence.png", userInput.path("attachments").get(0).path("fileName").asText());
                    assertEquals("evidence.pdf", userInput.path("attachments").get(1).path("fileName").asText());
                    assertTrue(userInput.path("attachments").get(0).path("referenced").asBoolean());

                    JsonNode metadata = JSON.readTree(post(origin(application), "/api/chat/attachments/metadata",
                            Map.of("threadId", turn.threadId(), "attachmentIds",
                                    List.of(image.path("id").asText(), document.path("id").asText()))).body());
                    assertEquals(2, metadata.path("items").size());
                    assertTrue(metadata.path("items").get(0).path("referenced").asBoolean());
                    closeEngine(application);
                }
            }
        }
    }

    /**
     * 错误 MIME、不可用 Codec、跨 Thread/Actor 引用和稳定键冲突都必须在模型准入前拒绝。
     */
    @Test
    void rejectsSpoofedUnsupportedAndCrossOwnerAttachments() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            ModelPort.ModelConfiguration model = model(ModelPort.Api.OPENAI_RESPONSES,
                    provider.url("/v1").uri(), MULTIMODAL);
            Turn ownerTurn;
            String attachmentId;
            try (ConfigurableApplicationContext application = start(postgres.getPostgresDatabase(), model)) {
                ownerTurn = createThread(application, "owner_source");
                byte[] png = png();
                JsonNode attachment = uploadMetadata(ownerTurn, "owner_file", "private.png", "image/png", png);
                attachmentId = attachment.path("id").asText();
                Turn otherThread = createThread(application, "same_actor_other_thread");
                assertEquals(404, metadataStatus(otherThread, attachmentId));
                String forged = JSON.writeValueAsString(Map.of("operationKey", "cross_thread_submit",
                        "content", input("inspect", List.of(), List.of(attachmentId))));
                assertEquals(404, postTurn(otherThread, "submit", forged).statusCode());
                HttpResponse<byte[]> missingPreview = getAttachment(otherThread, attachmentId);
                assertEquals(404, missingPreview.statusCode());

                HttpResponse<String> spoofed = upload(ownerTurn, "fake_file", "spoofed.png", "image/png",
                        "this is not a PNG".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(415, spoofed.statusCode(), spoofed.body());
                assertEquals(409, upload(ownerTurn, "owner_file", "private.png", "image/png",
                        png("CASE ID: JA-7320")).statusCode());
                closeEngine(application);
            }

            try (ConfigurableApplicationContext otherOwner = start(postgres.getPostgresDatabase(), model,
                    ContextBudget.capabilities(8_192, 1_024, true), new CurrentActor("tenant_other", "user_other"))) {
                Turn unrelated = createThread(otherOwner, "unrelated_owner");
                Turn foreignView = new Turn(unrelated.origin(), ownerTurn.threadId(), "turn_foreign_owner");
                assertEquals(404, metadataStatus(foreignView, attachmentId));
                assertEquals(404, getAttachment(unrelated, attachmentId).statusCode());
                closeEngine(otherOwner);
            }

            try (EmbeddedPostgres unsupportedDb = EmbeddedPostgres.start();
                 ConfigurableApplicationContext unsupported = start(unsupportedDb.getPostgresDatabase(),
                         model(ModelPort.Api.OPENAI_CHAT_COMPLETIONS, provider.url("/v1").uri(),
                                 Set.of(ModelPort.InputModality.TEXT)))) {
                Turn textOnly = createThread(unsupported, "text_only_model");
                JsonNode capabilities = JSON.readTree(postTurn(textOnly, "model", "{}").body())
                        .path("attachmentCapabilities");
                assertTrue(capabilities.path("effectiveFormats").isEmpty());
                assertEquals(415, upload(textOnly, "unsupported_png", "image.png", "image/png", png()).statusCode());
                closeEngine(unsupported);
            }
        }
    }

    /**
     * 在 Provider 的 50 MB 单文件门外提交一个有效 PNG 头，确认超限在准入前以 413 收敛。
     */
    @Test
    void rejectsFileAboveProviderLimit() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            ModelPort.ModelConfiguration model = model(ModelPort.Api.OPENAI_RESPONSES,
                    provider.url("/v1").uri(), MULTIMODAL);
            try (ConfigurableApplicationContext application = start(postgres.getPostgresDatabase(), model)) {
                Turn turn = createThread(application, "oversize_attachment");
                byte[] tooLarge = new byte[50_000_001];
                byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
                System.arraycopy(signature, 0, tooLarge, 0, signature.length);
                HttpResponse<String> response = upload(turn, "oversize_png", "oversize.png", "image/png", tooLarge);
                assertEquals(413, response.statusCode(), response.body());
                assertEquals(0, provider.getRequestCount());
                closeEngine(application);
            }
        }
    }

    /**
     * 队列新增与编辑都能持有纯附件 Content；引用后删除不能回收历史及队列所需字节。
     */
    @Test
    void preservesAttachmentOnlyQueueInputAndDiscardIdempotency() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueResponsesTool(provider, "call_approval", "approval_probe", "{}");
            enqueueResponsesText(provider, "已拒绝该测试操作");
            enqueueResponsesText(provider, "已保留排队的图片资料输入");
            ModelPort.ModelConfiguration model = model(ModelPort.Api.OPENAI_RESPONSES,
                    provider.url("/v1").uri(), MULTIMODAL);
            try (ConfigurableApplicationContext application = start(
                    postgres.getPostgresDatabase(), model, MEDIA_CONTEXT)) {
                Turn turn = createThread(application, "queue_attachment");
                JsonNode file = uploadMetadata(turn, "queue_image", "queued.png", "image/png", png());
                JsonNode disposable = uploadMetadata(turn, "draft_image", "draft.png", "image/png", png());
                HttpResponse<String> firstDiscard = post(origin(application), "/api/chat/attachments/discard",
                        Map.of("threadId", turn.threadId(), "attachmentIds", List.of(disposable.path("id").asText())));
                assertEquals(200, firstDiscard.statusCode(), firstDiscard.body());
                assertEquals(200, post(origin(application), "/api/chat/attachments/discard",
                        Map.of("threadId", turn.threadId(), "attachmentIds", List.of(disposable.path("id").asText()))).statusCode());
                assertEquals(410, upload(turn, "draft_image", "draft.png", "image/png", png()).statusCode());
                assertEquals(409, upload(turn, "draft_image", "draft.png", "image/png",
                        png("CASE ID: JA-7320")).statusCode());
                assertEquals(404, metadataStatus(turn, disposable.path("id").asText()));
                assertEquals(404, getAttachment(turn, disposable.path("id").asText()).statusCode());

                assertEquals(202, postTurn(turn, "submit", JSON.writeValueAsString(Map.of("operationKey", "start_approval",
                        "content", input("请等待操作审批", List.of(), List.of())))).statusCode());
                awaitState(turn, "WAITING_APPROVAL");
                ObjectNode enqueue = JSON.createObjectNode().put("action", "ENQUEUE")
                        .put("operationKey", "queue_add_file").put("inputId", "queue_file").put("kind", "FOLLOW_UP");
                enqueue.set("content", input("", List.of(), List.of(file.path("id").asText())));
                assertEquals(200, postTurn(turn, "queue/mutate", JSON.writeValueAsString(enqueue)).statusCode());
                JsonNode queued = JSON.readTree(postTurn(turn, "queue", "{}").body()).path("entries").get(0);
                assertEquals(file.path("id").asText(), queued.path("content").path("attachmentIds").get(0).asText());
                assertEquals("queued.png", queued.path("content").path("attachments").get(0).path("fileName").asText());

                ObjectNode update = JSON.createObjectNode().put("action", "UPDATE").put("operationKey", "queue_edit_file")
                        .put("inputId", "queue_file").put("expectedInputRevision", queued.path("inputRevision").asLong());
                update.set("content", input(null, List.of(), List.of(file.path("id").asText())));
                assertEquals(200, postTurn(turn, "queue/mutate", JSON.writeValueAsString(update)).statusCode());
                JsonNode edited = JSON.readTree(postTurn(turn, "queue", "{}").body()).path("entries").get(0);
                assertEquals(2, edited.path("inputRevision").asLong());
                JsonNode editedText = edited.path("content").path("text");
                assertTrue(editedText.isMissingNode() || editedText.isNull() || editedText.asText().isBlank());
                assertTrue(edited.path("content").path("attachments").get(0).path("referenced").asBoolean());

                JsonNode wait = currentWait(turn);
                answerWait(turn, wait, "option_deny");
                awaitState(turn, "COMPLETED");
                HttpResponse<byte[]> stillReadable = getAttachment(turn, file.path("id").asText());
                assertEquals(200, stillReadable.statusCode());
                assertArrayEquals(png(), stillReadable.body());
                closeEngine(application);
            }
        }
    }

    /**
     * 历史页会聚合多条输入的引用，批量元数据上限须覆盖一页可承载的附件总量，而非单条输入上限。
     */
    @Test
    void readsMoreThanTenAttachmentsAcrossHistoryInputs() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueResponsesText(provider, "第一批资料已读取");
            enqueueResponsesText(provider, "第二批资料已读取");
            ModelPort.ModelConfiguration model = model(ModelPort.Api.OPENAI_RESPONSES,
                    provider.url("/v1").uri(), MULTIMODAL);
            try (ConfigurableApplicationContext application = start(postgres.getPostgresDatabase(), model, MEDIA_CONTEXT)) {
                Turn firstTurn = createThread(application, "history_many_attachments");
                List<JsonNode> files = new ArrayList<>();
                for (int index = 0; index < 11; index++) {
                    files.add(uploadMetadata(firstTurn, "history_file_" + index,
                            "evidence-" + index + ".png", "image/png", png()));
                }

                List<String> firstBatch = files.subList(0, 10).stream()
                        .map(file -> file.path("id").asText()).toList();
                assertEquals(202, postTurn(firstTurn, "submit", JSON.writeValueAsString(Map.of(
                        "operationKey", "history_first_batch",
                        "content", input(null, List.of(), firstBatch)))).statusCode());
                awaitState(firstTurn, "COMPLETED");

                Turn secondTurn = new Turn(firstTurn.origin(), firstTurn.threadId(), "turn_history_second_batch");
                assertEquals(202, postTurn(secondTurn, "submit", JSON.writeValueAsString(Map.of(
                        "operationKey", "history_second_batch",
                        "content", input(null, List.of(), List.of(files.get(10).path("id").asText()))))).statusCode());
                awaitState(secondTurn, "COMPLETED");

                JsonNode history = JSON.readTree(post(origin(application), "/api/chat/threads/history",
                        Map.of("threadId", firstTurn.threadId(), "limit", 100)).body());
                List<String> historyAttachmentIds = new ArrayList<>();
                List<String> historyFileNames = new ArrayList<>();
                for (JsonNode item : history.path("items")) {
                    JsonNode content = item.path("content");
                    for (JsonNode attachment : content.path("attachments")) {
                        historyAttachmentIds.add(attachment.path("id").asText());
                        historyFileNames.add(attachment.path("fileName").asText());
                    }
                }
                assertEquals(files.stream().map(file -> file.path("id").asText()).toList(), historyAttachmentIds);
                assertEquals(files.stream().map(file -> file.path("fileName").asText()).toList(), historyFileNames);
                closeEngine(application);
            }
        }
    }

    /**
     * 从已持久的 wait 请求读取 interaction 身份，确保队列场景不会伪造待审批修订号。
     */
    private static JsonNode currentWait(Turn turn) throws Exception {
        JsonNode events = JSON.readTree(postTurn(turn, "events", "{}").body()).path("events");
        String requestId = "";
        for (JsonNode event : events) {
            if ("agent.wait.requested".equals(event.path("eventType").asText())) {
                requestId = JSON.readTree(event.path("payloadJson").asText()).path("requestRecordId").asText();
            }
        }
        assertFalse(requestId.isBlank());
        return JSON.readTree(postTurn(turn, "wait", JSON.writeValueAsString(Map.of("recordId", requestId))).body());
    }

    /**
     * 用 Core 当前问题 ID、Interaction ID 和 revision 回答审批，保持恢复命令与持久 wait 一致。
     */
    private static void answerWait(Turn turn, JsonNode wait, String optionId) throws Exception {
        JsonNode question = wait.path("questions").get(0);
        String body = JSON.writeValueAsString(Map.of("operationKey", "answer_queue_approval",
                "interactionId", wait.path("interactionId").asText(),
                "expectedInteractionRevision", wait.path("interactionRevision").asLong(),
                "answers", List.of(Map.of("questionId", question.path("questionId").asText(),
                        "optionIds", List.of(optionId), "skipped", false))));
        assertEquals(204, postTurn(turn, "answers", body).statusCode());
        assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
    }

    /**
     * 相同键同内容必须回同一记录，不同内容则显式冲突，避免丢失响应造成重复文件。
     */
    private static void assertUploadRetryIsStable(Turn turn, String key, String name, String mime,
                                                  byte[] bytes, byte[] changedBytes, String expectedId) throws Exception {
        JsonNode retried = JSON.readTree(upload(turn, key, name, mime, bytes).body());
        assertEquals(expectedId, retried.path("id").asText());
        HttpResponse<String> changed = upload(turn, key, name, mime, changedBytes);
        assertEquals(409, changed.statusCode(), changed.body());
    }

    /**
     * 预览 GET 只返回当前 Thread 附件的原始字节和检测后的 MIME。
     */
    private static void verifyPreview(Turn turn, JsonNode metadata, byte[] bytes, String mime) throws Exception {
        HttpResponse<byte[]> response = getAttachment(turn, metadata.path("id").asText());
        assertEquals(200, response.statusCode());
        assertEquals(mime, response.headers().firstValue("Content-Type").orElse(""));
        assertArrayEquals(bytes, response.body());
    }

    /**
     * 历史消息按 operationKey 对应的 Turn 身份返回完整用户 Content，而非扁平文本摘要。
     */
    private static JsonNode userHistoryInput(JsonNode history) {
        return history.path("items").findValues("content").stream()
                .filter(content -> content.path("attachmentIds").isArray() && !content.path("attachmentIds").isEmpty())
                .findFirst().orElseThrow(() -> new AssertionError("history omitted submitted attachment references"));
    }

    /**
     * 以真实 GET body 接收文件，避免 String decoder 改写 PDF 和图片内容。
     */
    private static HttpResponse<byte[]> getAttachment(Turn turn, String attachmentId) throws Exception {
        String threadId = java.net.URLEncoder.encode(turn.threadId(), java.nio.charset.StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(turn.origin() + "/api/chat/attachments/" + attachmentId
                        + "/content?threadId=" + threadId))
                .timeout(Duration.ofSeconds(20)).GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 对非当前 Thread 的元数据请求保留完整 route 身份，确认不会跨 Thread 泄露文件属性。
     */
    private static int metadataStatus(Turn turn, String attachmentId) throws Exception {
        return post(turn.origin(), "/api/chat/attachments/metadata",
                Map.of("threadId", turn.threadId(), "attachmentIds", List.of(attachmentId))).statusCode();
    }

}
