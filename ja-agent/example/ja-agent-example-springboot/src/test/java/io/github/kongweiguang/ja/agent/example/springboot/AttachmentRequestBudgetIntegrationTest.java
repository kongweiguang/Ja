// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.Turn;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.JSON;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.awaitState;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.closeEngine;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.createThread;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueAnthropicText;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.input;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.model;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.origin;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.post;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.postTurn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.start;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.uploadMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实 Spring MVC 与独立 PostgreSQL 验收 submit、append 和队列变更的附件请求总量准入。
 */
final class AttachmentRequestBudgetIntegrationTest {
    private static final long ANTHROPIC_TOTAL_BYTES = 32_000_000L;
    private static final Set<ModelPort.InputModality> PDF_INPUT = Set.of(
            ModelPort.InputModality.TEXT, ModelPort.InputModality.PDF);
    private static final ContextBudget MEDIA_CONTEXT = ContextBudget.capabilities(262_144, 32_768, true);
    private static final String QUEUE_INPUT_ID = "budget_queue_pdf";

    /** 用真实历史和活动队列资料覆盖四类 HTTP 预算检查及 Store 的无副作用拒绝边界。 */
    @Test
    void checksSubmitAppendAndQueueBudgetsThroughMvcBeforeProviderDispatch() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueAnthropicText(provider, "历史 PDF 已收到。");
            enqueueAnthropicApprovalTool(provider, "call_budget_approval");
            ModelPort.ModelConfiguration configuration = model(ModelPort.Api.ANTHROPIC_MESSAGES,
                    provider.url("/v1").uri(), PDF_INPUT);
            try (ConfigurableApplicationContext application = start(
                    postgres.getPostgresDatabase(), configuration, MEDIA_CONTEXT)) {
                Turn historyTurn = createThread(application, "attachment_budget_history");
                JsonNode capabilities = JSON.readTree(postTurn(historyTurn, "model", "{}").body())
                        .path("attachmentCapabilities");
                assertEquals(ANTHROPIC_TOTAL_BYTES, capabilities.path("maxTotalBytes").asLong());
                long historyBytes = 128_000L;
                long queuedBytes = 17_000_000L;
                long replacementBytes = 17_000_001L;
                long overLimitBytes = ANTHROPIC_TOTAL_BYTES - historyBytes + 1;
                assertTrue(overLimitBytes < capabilities.path("maxSingleBytes").asLong());
                assertTrue(historyBytes + queuedBytes < ANTHROPIC_TOTAL_BYTES);
                assertTrue(historyBytes + queuedBytes + replacementBytes > ANTHROPIC_TOTAL_BYTES);
                assertTrue(historyBytes + overLimitBytes > ANTHROPIC_TOTAL_BYTES);

                JsonNode historyFile = uploadMetadata(historyTurn, "budget_history_pdf", "history.pdf",
                        "application/pdf", validPaddedPdf(historyBytes, "history"));
                JsonNode queueFile = uploadMetadata(historyTurn, "budget_queue_pdf", "queued.pdf",
                        "application/pdf", validPaddedPdf(queuedBytes, "queued"));
                JsonNode replacementFile = uploadMetadata(historyTurn, "budget_replacement_pdf", "replacement.pdf",
                        "application/pdf", validPaddedPdf(replacementBytes, "replacement"));
                JsonNode overflowFile = uploadMetadata(historyTurn, "budget_overflow_pdf", "overflow.pdf",
                        "application/pdf", validPaddedPdf(overLimitBytes, "overflow"));
                assertDraft(application, historyTurn, queueFile.path("id").asText());
                assertDraft(application, historyTurn, replacementFile.path("id").asText());
                assertDraft(application, historyTurn, overflowFile.path("id").asText());

                String historyOperation = "submit_budget_history";
                assertEquals(202, submit(historyTurn, historyOperation,
                        List.of(historyFile.path("id").asText())).statusCode());
                assertEquals("COMPLETED", awaitState(historyTurn, "COMPLETED").path("state").asText());
                assertNotNull(provider.takeRequest(10, TimeUnit.SECONDS));
                assertEquals(1, provider.getRequestCount(), "only the small baseline PDF should reach the provider");

                AgentStore store = application.getBean(AgentStore.class);
                CurrentActor actor = application.getBean(CurrentActorProvider.class).currentActor();
                AgentStore.ConversationKey conversation = conversation(actor, historyTurn.threadId());
                Turn rejectedSubmit = new Turn(historyTurn.origin(), historyTurn.threadId(), "turn_budget_submit_reject");
                String rejectedSubmitOperation = "submit_budget_reject";
                AgentStore.ExecutionKey rejectedSubmitExecution = execution(conversation, rejectedSubmit.turnId());
                HttpResponse<String> submitOverflow = submit(rejectedSubmit, rejectedSubmitOperation,
                        List.of(queueFile.path("id").asText(), overflowFile.path("id").asText()));
                assertEquals(413, submitOverflow.statusCode(), submitOverflow.body());
                assertTrue(store.loadExecution(rejectedSubmitExecution).isEmpty());
                assertTrue(store.readReceipt(operationKey(actor, rejectedSubmitOperation)).isEmpty());
                assertDraft(application, historyTurn, queueFile.path("id").asText());
                assertDraft(application, historyTurn, overflowFile.path("id").asText());
                assertEquals(1, provider.getRequestCount(), "rejected submit must not dispatch its files");

                Turn pendingTurn = new Turn(historyTurn.origin(), historyTurn.threadId(), "turn_budget_pending");
                assertEquals(202, postTurn(pendingTurn, "submit", JSON.writeValueAsString(Map.of(
                        "operationKey", "submit_budget_pending",
                        "content", input("进入审批挂起，随后检查附件准入。", List.of(), List.of())))).statusCode());
                assertEquals("WAITING_APPROVAL", awaitState(pendingTurn, "WAITING_APPROVAL")
                        .path("state").asText());
                assertNotNull(provider.takeRequest(10, TimeUnit.SECONDS));
                assertEquals(2, provider.getRequestCount(), "approval should stop provider work before budget checks");

                AgentStore.ExecutionKey activeExecution = execution(conversation, pendingTurn.turnId());
                String enqueueOperation = "queue_budget_add";
                ObjectNode enqueue = queueCommand("ENQUEUE", enqueueOperation, QUEUE_INPUT_ID, 0,
                        queueFile.path("id").asText());
                assertEquals(200, queueMutation(pendingTurn, enqueue).statusCode());
                JsonNode queued = queue(pendingTurn).path("entries").get(0);
                assertEquals(QUEUE_INPUT_ID, queued.path("inputId").asText());
                assertEquals(1, queued.path("inputRevision").asLong());
                assertEquals(queueFile.path("id").asText(), queued.path("content").path("attachmentIds").get(0).asText());

                List<String> recordsBeforeReplay = recordIds(store, activeExecution);
                assertEquals(200, queueMutation(pendingTurn, enqueue).statusCode());
                assertEquals(recordsBeforeReplay, recordIds(store, activeExecution),
                        "same-key ENQUEUE retry must replay its Core receipt without a second durable input");
                assertEquals(queueFile.path("id").asText(), queue(pendingTurn).path("entries").get(0)
                        .path("content").path("attachmentIds").get(0).asText());

                ObjectNode validUpdate = queueCommand("UPDATE", "queue_budget_replace", QUEUE_INPUT_ID, 1,
                        replacementFile.path("id").asText());
                assertEquals(200, queueMutation(pendingTurn, validUpdate).statusCode());
                JsonNode replaced = queue(pendingTurn).path("entries").get(0);
                assertEquals(2, replaced.path("inputRevision").asLong());
                assertEquals(replacementFile.path("id").asText(),
                        replaced.path("content").path("attachmentIds").get(0).asText());

                List<String> recordsBeforeInvalidEnqueue = recordIds(store, activeExecution);
                String rejectedEnqueueOperation = "queue_budget_reject_add";
                ObjectNode rejectedEnqueue = queueCommand("ENQUEUE", rejectedEnqueueOperation,
                        "budget_queue_overflow", 0, overflowFile.path("id").asText());
                HttpResponse<String> enqueueOverflow = queueMutation(pendingTurn, rejectedEnqueue);
                assertEquals(413, enqueueOverflow.statusCode(), enqueueOverflow.body());
                assertEquals(recordsBeforeInvalidEnqueue, recordIds(store, activeExecution));
                assertTrue(store.readReceipt(operationKey(actor, rejectedEnqueueOperation)).isEmpty());
                assertQueueEntry(queue(pendingTurn), replacementFile.path("id").asText(), 2);
                assertDraft(application, historyTurn, overflowFile.path("id").asText());

                List<String> recordsBeforeInvalidUpdate = recordIds(store, activeExecution);
                String rejectedUpdateOperation = "queue_budget_reject_update";
                ObjectNode rejectedUpdate = queueCommand("UPDATE", rejectedUpdateOperation, QUEUE_INPUT_ID, 2,
                        overflowFile.path("id").asText());
                HttpResponse<String> updateOverflow = queueMutation(pendingTurn, rejectedUpdate);
                assertEquals(413, updateOverflow.statusCode(), updateOverflow.body());
                assertEquals(recordsBeforeInvalidUpdate, recordIds(store, activeExecution));
                assertTrue(store.readReceipt(operationKey(actor, rejectedUpdateOperation)).isEmpty());
                assertQueueEntry(queue(pendingTurn), replacementFile.path("id").asText(), 2);
                assertDraft(application, historyTurn, overflowFile.path("id").asText());

                List<String> recordsBeforeInvalidAppend = recordIds(store, activeExecution);
                String rejectedAppendOperation = "append_budget_reject";
                HttpResponse<String> appendOverflow = postTurn(pendingTurn, "inputs", JSON.writeValueAsString(Map.of(
                        "operationKey", rejectedAppendOperation,
                        "content", input(null, List.of(), List.of(overflowFile.path("id").asText())))));
                assertEquals(413, appendOverflow.statusCode(), appendOverflow.body());
                assertEquals(recordsBeforeInvalidAppend, recordIds(store, activeExecution));
                assertTrue(store.readReceipt(operationKey(actor, rejectedAppendOperation)).isEmpty());
                assertQueueEntry(queue(pendingTurn), replacementFile.path("id").asText(), 2);
                assertDraft(application, historyTurn, overflowFile.path("id").asText());
                assertEquals(2, provider.getRequestCount(), "rejected append and queue writes must not call Provider");
                closeEngine(application);
            }
        }
    }

    /** 构造真实聊天提交请求，确保历史资料和新增资料都走 MVC DTO。 */
    private static HttpResponse<String> submit(Turn turn, String operationKey, List<String> attachmentIds)
            throws Exception {
        return postTurn(turn, "submit", JSON.writeValueAsString(Map.of("operationKey", operationKey,
                "content", input(null, List.of(), attachmentIds))));
    }

    /** 构造带 CAS 修订号的队列内容变更，复用实际公开 API 的动作和字段。 */
    private static ObjectNode queueCommand(String action, String operationKey, String inputId,
                                           long expectedRevision, String attachmentId) {
        ObjectNode command = JSON.createObjectNode().put("action", action).put("operationKey", operationKey)
                .put("inputId", inputId).put("kind", "FOLLOW_UP");
        if (expectedRevision > 0) {
            command.put("expectedInputRevision", expectedRevision);
        }
        command.set("content", input(null, List.of(), List.of(attachmentId)));
        return command;
    }

    /** 通过线程附件元数据确认准入失败没有把草稿标成持久引用。 */
    private static void assertDraft(ConfigurableApplicationContext application, Turn turn, String attachmentId)
            throws Exception {
        JsonNode response = JSON.readTree(post(origin(application), "/api/chat/attachments/metadata",
                Map.of("threadId", turn.threadId(), "attachmentIds", List.of(attachmentId))).body());
        assertEquals(1, response.path("items").size());
        assertFalse(response.path("items").get(0).path("referenced").asBoolean());
    }

    /** 读取真实 PostgreSQL 队列投影，确认失败变更保留上一条输入与修订号。 */
    private static JsonNode queue(Turn turn) throws Exception {
        HttpResponse<String> response = postTurn(turn, "queue", "{}");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    /** 通过线程身份提交公开队列动作并保留服务端返回的准入状态。 */
    private static HttpResponse<String> queueMutation(Turn turn, ObjectNode command) throws Exception {
        return postTurn(turn, "queue/mutate", JSON.writeValueAsString(command));
    }

    /** 核对单一队列条目仍指向原附件且没有因失败准入推进其版本。 */
    private static void assertQueueEntry(JsonNode queue, String attachmentId, long inputRevision) {
        assertEquals(1, queue.path("entries").size());
        JsonNode entry = queue.path("entries").get(0);
        assertEquals(QUEUE_INPUT_ID, entry.path("inputId").asText());
        assertEquals(inputRevision, entry.path("inputRevision").asLong());
        assertEquals(attachmentId, entry.path("content").path("attachmentIds").get(0).asText());
    }

    /** 读取当前执行的完整有限记录页，以记录 ID 集合验证拒绝动作没有留下输入记录。 */
    private static List<String> recordIds(AgentStore store, AgentStore.ExecutionKey execution) {
        AgentStore.ExecutionSnapshot snapshot = store.loadExecution(execution).orElseThrow();
        AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(execution,
                0, snapshot.lastRecordSequence(), 10_000));
        assertFalse(page.hasMore(), "test execution should fit in one bounded Store page");
        return page.records().stream().map(AgentStore.StoredRecord::recordId).toList();
    }

    /** 从测试装配读取的服务端 owner 构造只读 Store 查询键，不把身份字段放入 HTTP 正文。 */
    private static AgentStore.ConversationKey conversation(CurrentActor actor, String threadId) {
        return new AgentStore.ConversationKey(actor.tenantId(), threadId, actor.userId());
    }

    /** 为拒绝 Turn 构造精确 Store 键，供准入后无执行断言使用。 */
    private static AgentStore.ExecutionKey execution(AgentStore.ConversationKey conversation, String turnId) {
        return new AgentStore.ExecutionKey(conversation, turnId);
    }

    /** Store 回执读取与服务端身份保持相同作用域，确认预算拒绝没有创建操作回执。 */
    private static AgentStore.OperationKey operationKey(CurrentActor actor, String operationKey) {
        return new AgentStore.OperationKey(actor.tenantId(), actor.userId(), operationKey);
    }

    /** 将空白填入合法 PDF 内容流并重建 xref，保持文件精确大小及尾部 startxref 可验证。 */
    private static byte[] validPaddedPdf(long requestedBytes, String marker) throws Exception {
        int requestedLength = Math.toIntExact(requestedBytes);
        String safeText = marker.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        byte[] stream = ("BT /F1 24 Tf 72 700 Td (Evidence " + safeText + ") Tj ET")
                .getBytes(StandardCharsets.US_ASCII);
        int paddingLength = requestedLength - buildPaddedPdf(stream, 0).length;
        if (paddingLength < 0) {
            throw new IllegalArgumentException("requested PDF size is smaller than its valid source document");
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            byte[] candidate = buildPaddedPdf(stream, paddingLength);
            int difference = requestedLength - candidate.length;
            if (difference == 0) {
                return candidate;
            }
            paddingLength += difference;
        }
        throw new IllegalStateException("could not size valid padded PDF exactly");
    }

    /** 生成具有真实对象偏移的 PDF，填充只扩展 stream 内容而不伪造尾随数据。 */
    private static byte[] buildPaddedPdf(byte[] stream, int paddingLength) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(output.size());
            output.write((index + 1 + " 0 obj\n" + objects.get(index) + "\nendobj\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        offsets.add(output.size());
        output.write(("5 0 obj\n<< /Length " + (stream.length + paddingLength) + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(stream);
        byte[] spaces = new byte[8_192];
        Arrays.fill(spaces, (byte) ' ');
        for (int remaining = paddingLength; remaining > 0; remaining -= Math.min(remaining, spaces.length)) {
            output.write(spaces, 0, Math.min(remaining, spaces.length));
        }
        output.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
        int xref = output.size();
        output.write(("xref\n0 " + (objects.size() + 2) + "\n0000000000 65535 f \n")
                .getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) {
            output.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset)
                    .getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("trailer\n<< /Size " + (objects.size() + 2) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    /** 进入真实 Anthropic tool_use 终态，使 Agent 停在审批边界供后续准入请求测试。 */
    private static void enqueueAnthropicApprovalTool(MockWebServer server, String callId) {
        String body = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_budget\","
                + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,"
                + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + callId
                + "\",\"name\":\"approval_probe\",\"input\":{}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,"
                + "\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\","
                + "\"stop_sequence\":null},\"usage\":{\"output_tokens\":4}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body));
    }
}
