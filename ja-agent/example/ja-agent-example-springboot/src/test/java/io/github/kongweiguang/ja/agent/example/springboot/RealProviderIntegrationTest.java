// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.ConfigurableApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.JSON;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.Turn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.content;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.createThread;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.post;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.postTurn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.request;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.start;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.upload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 仅显式联调时使用真实配置；资料与 PostgreSQL 均为独立测试资源，不借用用户业务数据库。 */
@EnabledIfSystemProperty(named = "ja.example.realProvider", matches = "true")
class RealProviderIntegrationTest {
    private static final String TITLE = "企业资料验收";

    /** 核验实际模型同时读到图片与 PDF，再通过真实审批、事务保存及终答交付完成整条业务链。 */
    @Test
    void readsImageAndPdfAndSavesAnApprovedNote() throws Exception {
        ModelPort.ModelConfiguration model = configuration();
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start();
             ConfigurableApplicationContext application = start(postgres.getPostgresDatabase(), model,
                     ContextBudget.capabilities(Integer.parseInt(required("JA_EXAMPLE_CONTEXT_WINDOW_TOKENS")),
                             Integer.parseInt(required("JA_EXAMPLE_MAX_OUTPUT_TOKENS")), true))) {
            Turn turn = createThread(application, "real_provider_" + java.util.UUID.randomUUID());
            try {
                JsonNode image = uploaded(turn, "real_image", "image-evidence.png", "image/png", image());
                JsonNode pdf = uploaded(turn, "real_pdf", "budget-evidence.pdf", "application/pdf", pdf());
                String prompt = "这是隔离的企业资料接入验收，不涉及真实业务决策，需求已完整，无需任何澄清。请实际读取这两个附件，识别图片中印刷的代码、色块颜色，"
                        + "以及 PDF 的项目代码和金额。区分资料事实、推断和不确定信息；不要把附件名称当作正文。"
                        + "然后立即调用 save_note，标题必须是“" + TITLE + "”，正文包含两份资料各自的实际事实，"
                        + "等待原生save_note审批后再保存。工具只需调用save_note，不要调用request_user_input、MCP或等待工具；"
                        + "不要用额外问答代替save_note的原生审批。终答简短包含真实笔记标题及以上事实。";
                String body = JSON.writeValueAsString(Map.of("operationKey", "real_submit",
                        "content", content(prompt, List.of(), List.of(image.path("id").asText(), pdf.path("id").asText()))));
                assertEquals(202, postTurn(turn, "submit", body).statusCode(), "real request must be admitted");
                awaitRealState(turn, "WAITING_APPROVAL");
                JsonNode wait = currentWait(turn);
                assertEquals("approval", wait.path("kind").asText());
                JsonNode question = wait.path("questions").get(0);
                assertNotNull(question, "real save must require approval");
                assertTrue(question.path("prompt").asText().contains(TITLE), "approval must show the actual normalized title");
                Map<String, Object> answer = Map.of("operationKey", "real_approve",
                        "interactionId", wait.path("interactionId").asText(),
                        "expectedInteractionRevision", wait.path("interactionRevision").asLong(),
                        "answers", List.of(Map.of("questionId", question.path("questionId").asText(),
                                "optionIds", List.of("option_approve"), "skipped", false)));
                assertEquals(204, postTurn(turn, "answers", JSON.writeValueAsString(answer)).statusCode());
                assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
                awaitRealState(turn, "COMPLETED");
                HttpResponse<String> notesResponse = post(turn.origin(), "/api/chat/experience/notes",
                        Map.of("threadId", turn.threadId()));
                assertEquals(200, notesResponse.statusCode(), "notes must use their Thread-scoped request contract");
                JsonNode notes = JSON.readTree(notesResponse.body());
                assertEquals(1, notes.size(), "real tool must commit one note");
                JsonNode note = notes.get(0);
                assertEquals(TITLE, note.path("title").asText());
                String facts = note.path("body").asText();
                assertTrue(facts.contains("ALPHA-17"), "image code must come from actual image bytes");
                assertTrue(facts.contains("BRAVO-42"), "PDF code must come from actual PDF bytes");
                assertTrue(facts.contains("128.50") || facts.contains("128.5"), "PDF amount must be read accurately");
                assertTrue(facts.toLowerCase(java.util.Locale.ROOT).contains("red") || facts.contains("红"),
                        "image color must come from actual image bytes");
                JsonNode history = JSON.readTree(request(turn.origin() + "/api/chat/threads/history", "POST",
                        JSON.writeValueAsString(Map.of("threadId", turn.threadId(), "limit", 256))).body());
                String answerText = "";
                for (JsonNode item : history.path("items")) {
                    if ("assistant".equals(item.path("kind").asText())) {
                        answerText = item.path("text").asText();
                    }
                }
                assertTrue(answerText.contains(TITLE), "final answer must deliver the committed note title");
                Path evidence = Files.createTempFile("ja-enterprise-real-provider-result-", ".json");
                Files.writeString(evidence, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                        "model", model.model(), "api", model.api().name(), "state", "COMPLETED",
                        "threadId", turn.threadId(), "title", TITLE, "noteCount", notes.size(),
                        "attachmentCount", 2, "imageAndPdfFacts", facts, "answer", answerText)), StandardCharsets.UTF_8);
                System.out.println("REAL_PROVIDER_OK evidence=" + evidence);
            } finally {
                cancelActive(turn);
            }
        }
    }

    /** 密钥由调用进程环境传入；测试代际遵循Core的cfg_契约，缺配置直接失败且不回显值。 */
    private static ModelPort.ModelConfiguration configuration() {
        return new ModelPort.ModelConfiguration(required("JA_EXAMPLE_PROVIDER_ID"), required("JA_EXAMPLE_MODEL_ID"),
                "cfg_real_provider_test", ModelPort.Api.valueOf(required("JA_EXAMPLE_MODEL_API")),
                required("JA_EXAMPLE_MODEL"), URI.create(required("JA_EXAMPLE_MODEL_BASE_URI")),
                required("JA_EXAMPLE_MODEL_API_KEY"), Duration.ofSeconds(10), Duration.ofSeconds(120),
                Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE, ModelPort.InputModality.PDF),
                new ModelPort.GenerationOptions(null, null, null, System.getenv("JA_EXAMPLE_REASONING_LEVEL")));
    }

    /** 失败只标明缺失的变量名，避免测试报告把凭据写入日志。 */
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing real test environment: " + key);
        }
        return value;
    }

    /** 上传响应的 ID 是后续准入唯一引用，不能在模型请求中用文件名替代已保存字节。 */
    private static JsonNode uploaded(Turn turn, String key, String name, String mime, byte[] bytes) throws Exception {
        HttpResponse<String> response = upload(turn, key, name, mime, bytes);
        assertEquals(200, response.statusCode(), "real test fixture upload must succeed");
        JsonNode metadata = JSON.readTree(response.body());
        assertFalse(metadata.path("id").asText().isBlank());
        return metadata;
    }

    /** 从真实持久事件取等待引用，保证审批使用当前 Core interaction revision。 */
    private static JsonNode currentWait(Turn turn) throws Exception {
        JsonNode events = JSON.readTree(postTurn(turn, "events", "{\"limit\":10000}").body());
        String recordId = "";
        for (JsonNode event : events.path("events")) {
            if ("agent.wait.requested".equals(event.path("eventType").asText())) {
                recordId = JSON.readTree(event.path("payloadJson").asText()).path("requestRecordId").asText();
            }
        }
        assertFalse(recordId.isBlank(), "real save must expose a durable approval request");
        return JSON.readTree(postTurn(turn, "wait", JSON.writeValueAsString(Map.of("recordId", recordId))).body());
    }

    /** 联调等待有界；resume受理后可短暂仍为SUSPENDED，必须等待持久推进，诊断只保存Core公开事实。 */
    private static void awaitRealState(Turn turn, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        String state = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = postTurn(turn, "status", "{}");
            assertEquals(200, response.statusCode());
            state = JSON.readTree(response.body()).path("state").asText();
            if (expected.equals(state)) {
                return;
            }
            if (Set.of("FAILED", "CANCELLED", "COMPLETED", "WAITING_INPUT").contains(state)) {
                break;
            }
            Thread.sleep(250);
        }
        Path diagnostic = Files.createTempFile("ja-enterprise-real-provider-failure-", ".json");
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("events", JSON.readTree(postTurn(turn, "events", "{\"limit\":10000}").body()));
        if ("WAITING_INPUT".equals(state) || "WAITING_APPROVAL".equals(state)) {
            details.put("wait", currentWait(turn));
        }
        Files.writeString(diagnostic, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(details), StandardCharsets.UTF_8);
        fail("real Provider flow expected " + expected + ", observed " + state + "; Core events=" + diagnostic);
    }

    /** 测试异常时取消原 Turn，避免关闭测试页面后仍持续发出真实模型请求。 */
    private static void cancelActive(Turn turn) throws Exception {
        HttpResponse<String> response = postTurn(turn, "status", "{}");
        if (response.statusCode() != 200) {
            return;
        }
        String state = JSON.readTree(response.body()).path("state").asText();
        if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)) {
            postTurn(turn, "cancel", "{\"operationKey\":\"real_cleanup_cancel\",\"reason\":\"Test finished.\"}");
        }
    }

    /** 直接生成有清晰文字与颜色事实的 PNG，视觉断言不能由附件名或用户提示推导。 */
    private static byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(520, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 520, 180);
            graphics.setColor(Color.RED); graphics.fillRect(24, 28, 110, 110);
            graphics.setColor(Color.BLACK); graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));
            graphics.drawString("ALPHA-17", 160, 100);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    /** 生成有效 xref 的单页 PDF，原生 Provider 必须从文档流读取独立事实，不依赖 OCR 或宿主文本提取。 */
    private static byte[] pdf() {
        String stream = "BT /F1 22 Tf 60 720 Td (Project code: BRAVO-42) Tj 0 -40 Td (Budget: 128.50 USD) Tj ET\n";
        List<String> objects = List.of("<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "endstream");
        StringBuilder document = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(document.length());
            document.append(index + 1).append(" 0 obj\n").append(objects.get(index)).append("\nendobj\n");
        }
        int xref = document.length();
        document.append("xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            document.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        document.append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return document.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
