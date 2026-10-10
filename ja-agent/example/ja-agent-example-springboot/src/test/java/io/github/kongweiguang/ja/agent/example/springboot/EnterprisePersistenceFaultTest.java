// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.JSON;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.Turn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.awaitState;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.closeEngine;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.createThread;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueResponsesText;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueResponsesTool;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.input;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.model;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.png;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.post;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.postTurn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.start;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.uploadMetadata;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 在真实 Spring/Core/PostgreSQL 组合中注入提交边界故障，验证企业 participant 与 Core 事实的原子性。
 */
class EnterprisePersistenceFaultTest {
    private static final Set<ModelPort.InputModality> TEXT_ONLY = Set.of(ModelPort.InputModality.TEXT);
    private static final int OBSERVATION_BODY_LIMIT = 16 * 1024;

    /**
     * participant 已 INSERT 笔记后抛错时，笔记、来源和 note.write 事实必须随 Core 结果一起回滚。
     */
    @org.junit.jupiter.api.Test
    void rollsBackCoreResultWhenParticipantFailsAfterNoteInsert() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            FaultDataSource dataSource = new FaultDataSource(postgres.getPostgresDatabase(), Fault.AFTER_NOTE_INSERT);
            try (ConfigurableApplicationContext application = start(dataSource,
                    model(provider, Set.of(ModelPort.InputModality.TEXT, ModelPort.InputModality.IMAGE)),
                    io.github.kongweiguang.ja.agent.domain.ContextBudget.capabilities(262_144, 1_024, true))) {
                Turn turn = createThread(application, "participant_failure");
                dataSource.threadId(turn.threadId());
                JsonNode attachment = uploadMetadata(turn, "upload_participant_source", "source.png",
                        "image/png", png("CASE ID: JA-PARTICIPANT"));
                String attachmentId = attachment.path("id").asText();
                enqueueSave(provider, "call_participant_failure", "Participant 回滚", "不得留下半笔交易",
                        List.of(attachmentId));
                enqueueResponsesText(provider, "Participant 回滚已核验。");
                approveSave(turn, "submit_participant_failure", List.of(attachmentId));
                awaitFaultSettled(turn, dataSource);

                assertTrue(dataSource.triggered(), "the injected failure must follow a real note INSERT");
                assertEquals(0, noteCount(dataSource, turn));
                assertEquals(0, noteSourceCount(dataSource, turn));
                assertEquals(0, noteWriteFactCount(dataSource, turn));
                assertEquals(0, successfulToolResultCount(dataSource, turn));
                closeEngine(application);
            }
        }
    }

    /**
     * Tool Started 提交后从独立连接推进 fencing token；旧执行者不能再保存 note 或成功结果。
     */
    @org.junit.jupiter.api.Test
    void staleFencingCannotLeaveNoteOrExtensionFact() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueSave(provider, "call_stale_fence", "陈旧执行者", "不得提交笔记");
            enqueueResponsesText(provider, "陈旧执行者不应完成保存。");
            FaultDataSource dataSource = new FaultDataSource(postgres.getPostgresDatabase(), Fault.ADVANCE_FENCE);
            try (ConfigurableApplicationContext application = start(dataSource, model(provider))) {
                Turn turn = createThread(application, "stale_fencing");
                dataSource.threadId(turn.threadId());
                approveSave(turn, "submit_stale_fencing");
                awaitFencingInvalidation(turn, dataSource);

                assertTrue(dataSource.triggered(), "the test must invalidate the active fencing token");
                assertEquals(0, noteCount(dataSource, turn));
                assertEquals(0, noteWriteFactCount(dataSource, turn));
                assertEquals(0, successfulToolResultCount(dataSource, turn));
                closeEngine(application);
            }
        }
    }

    /**
     * Store commit 前连接中断时，先验证宿主丢失执行观察的状态和重连信号，再由 Recovery 使用 Core 身份与 revision 显式 RETRY。
     */
    @org.junit.jupiter.api.Test
    void retriesSameCallAfterCommitWasInterruptedBeforeDatabaseCommit() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueSave(provider, "call_before_commit", "提交前中断", "恢复后应只保存一次");
            enqueueResponsesText(provider, "《提交前中断》已保存。");
            FaultDataSource dataSource = new FaultDataSource(postgres.getPostgresDatabase(), Fault.BEFORE_COMMIT);
            try (ConfigurableApplicationContext application = start(dataSource, model(provider))) {
                Turn turn = createThread(application, "before_commit_interruption");
                dataSource.threadId(turn.threadId());
                approveSave(turn, "submit_before_commit_interruption");
                JsonNode interrupted = awaitCommitInterruptionAfterStarted(turn, dataSource);

                assertTrue(dataSource.triggered(), "the commit interruption must be injected after the participant write");
                assertEquals("RUNNING", interrupted.path("state").asText());
                assertEquals(0, noteCount(dataSource, turn));
                assertEquals(0, noteWriteFactCount(dataSource, turn));
                JsonNode unobserved = awaitResumeRequired(turn);
                assertEquals("RUNNING", unobserved.path("state").asText());
                assertTrue(unobserved.path("resumeRequired").asBoolean(), unobserved.toString());
                long eventWatermark = awaitEventWatermark(turn);
                HttpResponse<byte[]> reconnect = postBounded(turn, "reconnect",
                        Map.of("afterSequence", eventWatermark), Duration.ofSeconds(10));
                assertEquals(200, reconnect.statusCode(), responseText(reconnect));
                assertTrue(responseText(reconnect).contains("event:recovery-required"), responseText(reconnect));

                String threadId = turn.threadId();
                String turnId = turn.turnId();
                closeEngine(application);
                try (ConfigurableApplicationContext recovered = start(dataSource, model(provider))) {
                    turn = new Turn(EnterpriseTestSupport.origin(recovered), threadId, turnId);
                    assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
                    awaitState(turn, "SUSPENDED");

                    JsonNode pending = recoveries(turn);
                    assertEquals(1, pending.path("recoveries").size(), pending.toString());
                    JsonNode observed = JSON.readTree(responseText(postBounded(turn, "status", Map.of(),
                            Duration.ofSeconds(3))));
                    assertFalse(observed.path("resumeRequired").asBoolean(), observed.toString());
                    JsonNode recovery = pending.path("recoveries").get(0);
                    assertEquals("call_before_commit", recovery.path("callId").asText());
                    resolveRecovery(turn, recovery, "RETRY", "retry_before_commit_" + turn.turnId());
                    assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
                    awaitState(turn, "COMPLETED");

                    assertEquals(1, noteCount(dataSource, turn));
                    assertEquals(1, noteWriteFactCount(dataSource, turn));
                    assertEquals(1, successfulToolResultCount(dataSource, turn));
                    assertEquals(2, provider.getRequestCount(),
                            "the stored Tool call is retried without asking the model to recreate it");
                    assertTerminalAnswerContainsTitle(turn, "提交前中断");
                }
            }
        }
    }

    /**
     * 数据库已经提交但响应丢失时，Core operation receipt 对账应继续完成而不重复保存 Tool 副作用。
     */
    @org.junit.jupiter.api.Test
    void reconcilesCommitThatSucceededBeforeConnectionFailure() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueSave(provider, "call_after_commit", "提交后中断", "Core 应读回真实提交回执");
            enqueueResponsesText(provider, "《提交后中断》已保存。");
            FaultDataSource dataSource = new FaultDataSource(postgres.getPostgresDatabase(), Fault.AFTER_COMMIT);
            try (ConfigurableApplicationContext application = start(dataSource, model(provider))) {
                Turn turn = createThread(application, "after_commit_interruption");
                dataSource.threadId(turn.threadId());
                approveSave(turn, "submit_after_commit_interruption");
                awaitState(turn, "COMPLETED");

                assertTrue(dataSource.triggered(), "the injected connection failure must occur after PostgreSQL commit");
                assertEquals(1, noteCount(dataSource, turn));
                assertEquals(1, noteWriteFactCount(dataSource, turn));
                assertEquals(1, successfulToolResultCount(dataSource, turn));
                assertEquals(2, provider.getRequestCount(), "the committed Tool result must not be replayed to the model");
                assertTrue(recoveries(turn).path("recoveries").isEmpty());
                assertTerminalAnswerContainsTitle(turn, "提交后中断");
                closeEngine(application);
            }
        }
    }

    /**
     * 投影回调异常后的保底事务仍保存真实 note.write；普通 resume 从数据库回填标题且不再次执行保存工具。
     */
    @org.junit.jupiter.api.Test
    void resumesFallbackReceiptAndDeliversHydratedTitleWithoutSavingAgain() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueSave(provider, "call_projection_failure", "投影故障回执", "participant 应正常提交真实笔记");
            enqueueResponsesText(provider, "笔记处理完成。");
            enqueueResponsesText(provider, "《投影故障回执》已保存并完成交付。");
            ConfigurableApplicationContext application = startWithProjectionFailure(
                    postgres.getPostgresDatabase(), model(provider));
            try (application) {
                Turn turn = createThread(application, "projection_failure_recovery");
                approveSave(turn, "submit_projection_failure");
                awaitPersistenceCounts(postgres.getPostgresDatabase(), turn, 1, 1);
                awaitState(turn, "SUSPENDED");

                assertEquals(1, noteCount(postgres.getPostgresDatabase(), turn));
                assertEquals(1, noteWriteFactCount(postgres.getPostgresDatabase(), turn));
                assertEquals(1, successfulToolResultCount(postgres.getPostgresDatabase(), turn));
                assertFalse(policyFacts(turn).path("facts").toString().contains("document.note.saved"),
                        "failed projection transitions must not be reported as committed middleware facts");
                assertTrue(recoveries(turn).path("recoveries").isEmpty(),
                        "the authoritative ToolResult and note.write fact already committed");

                assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
                awaitState(turn, "COMPLETED");

                assertEquals(1, noteCount(postgres.getPostgresDatabase(), turn));
                assertEquals(1, noteWriteFactCount(postgres.getPostgresDatabase(), turn));
                assertEquals(1, successfulToolResultCount(postgres.getPostgresDatabase(), turn));
                assertEquals(3, provider.getRequestCount(), "resume should deliver the hydrated title without repeating save_note");
                assertTerminalAnswerContainsTitle(turn, "投影故障回执");
                assertTrue(policyFacts(turn).path("facts").toString().contains("document.delivery.completed"));
                closeEngine(application);
            }
        }
    }

    /**
     * 用与常规示例相同的模型、Store、Web 和 actor 装配，只额外注册一次性投影故障中间件。
     */
    private static ConfigurableApplicationContext startWithProjectionFailure(DataSource dataSource,
                                                                               ModelPort.ModelConfiguration model) {
        io.github.kongweiguang.ja.agent.store.pg.infrastructure.PostgresAgentStore.bootstrap(dataSource);
        return new SpringApplicationBuilder(SpringBootAgentExample.class).web(WebApplicationType.SERVLET)
                .initializers(application -> {
                    GenericApplicationContext beans = (GenericApplicationContext) application;
                    beans.registerBean("testDataSource", DataSource.class, () -> dataSource);
                    beans.registerBean("modelConfiguration", ModelPort.ModelConfiguration.class, () -> model);
                    beans.registerBean("contextBudget", io.github.kongweiguang.ja.agent.domain.ContextBudget.class,
                            () -> io.github.kongweiguang.ja.agent.domain.ContextBudget.capabilities(8_192, 1_024, true));
                    beans.registerBean("projectionFailureMiddleware", AgentMiddleware.class,
                            ProjectionFailureMiddleware::new);
                }).run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                        "--agent.experience.enabled=true");
    }

    /**
     * 为没有附件来源的场景构造真实 save_note 调用，保持所有副作用仍由示例 Extension 与 Core 驱动。
     */
    private static void enqueueSave(MockWebServer provider, String callId, String title, String body) {
        enqueueSave(provider, callId, title, body, List.of());
    }

    /**
     * 冻结实际上传来源 ID 到模型调用，检验 participant 写入的 note source 关联也受同一事务保护。
     */
    private static void enqueueSave(MockWebServer provider, String callId, String title, String body,
                                    List<String> sourceAttachmentIds) {
        enqueueResponsesTool(provider, callId, "save_note", JSON.valueToTree(Map.of(
                "title", title, "body", body, "sourceAttachmentIds", sourceAttachmentIds)).toString());
    }

    /**
     * 使用公开 submit、durable wait 与 answer/resume 路由推进真实审批，不直接调用策略或工具对象。
     */
    private static void approveSave(Turn turn, String operationKey) throws Exception {
        approveSave(turn, operationKey, List.of());
    }

    /**
     * 将真实附件随 submit 一起 admission，确保 save_note 的来源 ID 已由当前 Thread 授权。
     */
    private static void approveSave(Turn turn, String operationKey, List<String> attachmentIds) throws Exception {
        HttpResponse<String> submitted = postTurn(turn, "submit", JSON.writeValueAsString(Map.of(
                "operationKey", operationKey,
                "content", input("按审批结果保存笔记", List.of(), attachmentIds))));
        assertEquals(202, submitted.statusCode(), submitted.body());
        awaitState(turn, "WAITING_APPROVAL");
        JsonNode wait = currentWait(turn);
        assertEquals("approval", wait.path("kind").asText(), wait.toString());
        JsonNode question = wait.path("questions").get(0);
        HttpResponse<String> answer = postTurn(turn, "answers", JSON.writeValueAsString(Map.of(
                "operationKey", "answer_" + turn.turnId(),
                "interactionId", wait.path("interactionId").asText(),
                "expectedInteractionRevision", wait.path("interactionRevision").asLong(),
                "answers", List.of(Map.of("questionId", question.path("questionId").asText(),
                        "optionIds", List.of("option_approve"), "skipped", false)))));
        assertEquals(204, answer.statusCode(), answer.body());
        assertEquals(202, postTurn(turn, "resume", "{}").statusCode());
    }

    /**
     * 从 Core durable wait 事件读取 record ID，避免按随机 ID 或内存状态拼装审批回执。
     */
    private static JsonNode currentWait(Turn turn) throws Exception {
        JsonNode events = JSON.readTree(postTurn(turn, "events", "{}").body()).path("events");
        String recordId = "";
        for (JsonNode event : events) {
            if ("agent.wait.requested".equals(event.path("eventType").asText())) {
                recordId = JSON.readTree(event.path("payloadJson").asText()).path("requestRecordId").asText();
            }
        }
        assertFalse(recordId.isBlank(), "approval must refer to a durable Core wait record");
        return JSON.readTree(postTurn(turn, "wait", JSON.writeValueAsString(Map.of("recordId", recordId))).body());
    }

    /**
     * 以 bounded polling 等待存储故障已停止推进，返回实际持久状态供各故障类型检查。
     */
    private static JsonNode awaitFaultSettled(Turn turn, FaultDataSource dataSource) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode status = JSON.createObjectNode();
        while (System.nanoTime() < deadline) {
            status = JSON.readTree(postTurn(turn, "status", "{}").body());
            String state = status.path("state").asText();
            if (dataSource.triggered() && Set.of("SUSPENDED", "FAILED", "COMPLETED", "CANCELLED").contains(state)) {
                Thread.sleep(150);
                return status;
            }
            Thread.sleep(50);
        }
        fail("fault injection or execution settlement did not complete (triggered=" + dataSource.triggered()
                + ", state=" + status.path("state").asText() + "): "
                + postTurn(turn, "events", "{}").body());
        return status;
    }

    /**
     * Store 提交异常会保留 RUNNING 与 Started；等故障和持久化 Started 同时可见后重启恢复。
     */
    private static JsonNode awaitCommitInterruptionAfterStarted(Turn turn, FaultDataSource dataSource)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode status = JSON.createObjectNode();
        while (System.nanoTime() < deadline) {
            status = JSON.readTree(postTurn(turn, "status", "{}").body());
            if (dataSource.triggered()) {
                JsonNode events = JSON.readTree(postTurn(turn, "events", "{}").body()).path("events");
                for (JsonNode event : events) {
                    if ("agent.tool.started".equals(event.path("eventType").asText())) {
                        return status;
                    }
                }
            }
            Thread.sleep(50);
        }
        fail("commit interruption or persisted Tool Started was not observed: "
                + postTurn(turn, "events", "{}").body());
        return status;
    }

    /**
     * 旧执行者的 fencing 会阻止终态提交，因此以 Tool Started 持久化和外部 token 变更作为等待边界。
     */
    private static void awaitFencingInvalidation(Turn turn, FaultDataSource dataSource) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (dataSource.triggered()) {
                JsonNode events = JSON.readTree(postTurn(turn, "events", "{}").body()).path("events");
                for (JsonNode event : events) {
                    if ("agent.tool.started".equals(event.path("eventType").asText())) {
                        return;
                    }
                }
            }
            Thread.sleep(50);
        }
        fail("fencing was not advanced after a persisted Tool Started fact: "
                + postTurn(turn, "events", "{}").body());
    }

    /**
     * 等待 note 与 note.write 同时达到预期计数，避免在异步 resume 尚未进入 participant 时检查中间状态。
     */
    private static void awaitPersistenceCounts(DataSource dataSource, Turn turn, long notes, long facts) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (noteCount(dataSource, turn) == notes && noteWriteFactCount(dataSource, turn) == facts) {
                return;
            }
            Thread.sleep(50);
        }
        fail("expected note/fact counts " + notes + "/" + facts + " but saw "
                + noteCount(dataSource, turn) + "/" + noteWriteFactCount(dataSource, turn));
    }

    /**
     * 读取公开恢复列表；恢复身份和 revision 必须来自 Core，而不能由测试猜测。
     */
    private static JsonNode recoveries(Turn turn) throws Exception {
        HttpResponse<String> response = postTurn(turn, "recoveries", "{}");
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    /**
     * 按公开 recoveryId、callId、revision 与稳定 operationKey 裁决，再由调用方显式 resume。
     */
    private static void resolveRecovery(Turn turn, JsonNode recovery, String decision,
                                        String operationKey) throws Exception {
        HttpResponse<String> response = postTurn(turn, "recoveries/resolve", JSON.writeValueAsString(Map.of(
                "recoveryId", recovery.path("recoveryId").asText(),
                "callId", recovery.path("callId").asText(),
                "expectedRecoveryRevision", recovery.path("revision").asLong(),
                "operationKey", operationKey,
                "decision", decision)));
        assertEquals(204, response.statusCode(), response.body());
    }

    /**
     * 等待持久状态仍为 RUNNING 且当前宿主已没有对应执行句柄，避免与故障回调清理竞态。
     */
    private static JsonNode awaitResumeRequired(Turn turn) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        JsonNode status = JSON.createObjectNode();
        while (System.nanoTime() < deadline) {
            HttpResponse<byte[]> response = postBounded(turn, "status", Map.of(), Duration.ofSeconds(3));
            assertEquals(200, response.statusCode(), responseText(response));
            status = JSON.readTree(responseText(response));
            if (status.path("resumeRequired").asBoolean()) {
                return status;
            }
            Thread.sleep(50);
        }
        fail("execution observation was not released after the commit failure: " + status);
        return status;
    }

    /**
     * 分页读取到当前事件水位，重连从真实游标继续，避免把已有事件历史塞进有界 SSE 响应。
     */
    private static long awaitEventWatermark(Turn turn) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        long cursor = 0;
        while (System.nanoTime() < deadline) {
            HttpResponse<byte[]> response = postBounded(turn, "events",
                    Map.of("afterSequence", cursor, "limit", 8), Duration.ofSeconds(3));
            assertEquals(200, response.statusCode(), responseText(response));
            JsonNode page = JSON.readTree(responseText(response));
            long next = page.path("nextSequence").asLong(-1);
            assertTrue(next >= cursor, page.toString());
            if (!page.path("hasMore").asBoolean()) {
                return next;
            }
            assertTrue(next > cursor, page.toString());
            cursor = next;
        }
        fail("event history did not reach a stable reconnect watermark");
        return cursor;
    }

    /**
     * 用较短请求超时和有界响应订阅器观察 HTTP 边界，超大流在缓存超过上限前即被取消。
     */
    private static HttpResponse<byte[]> postBounded(Turn turn, String action, Map<String, ?> fields,
                                                     Duration timeout) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode payload = JSON.valueToTree(fields);
        payload.put("threadId", turn.threadId()).put("turnId", turn.turnId());
        HttpRequest request = HttpRequest.newBuilder(URI.create(turn.origin() + "/api/chat/" + action))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)))
                .build();
        HttpResponse<byte[]> response = EnterpriseTestSupport.HTTP.send(request,
                boundedBodyHandler(OBSERVATION_BODY_LIMIT));
        assertTrue(response.body().length <= OBSERVATION_BODY_LIMIT,
                "HTTP response exceeded " + OBSERVATION_BODY_LIMIT + " bytes");
        return response;
    }

    /**
     * 逐批请求 HTTP 正文并限制累计字节数，避免测试先完整缓冲失控的 SSE 响应再检查大小。
     */
    private static HttpResponse.BodyHandler<byte[]> boundedBodyHandler(int maxBytes) {
        return ignored -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 1024));
            private Flow.Subscription subscription;

            /** 返回随订阅状态完成或异常完成的有界正文，供 HTTP 客户端等待消费结果。 */
            @Override
            public CompletionStage<byte[]> getBody() {
                return body;
            }

            /** 仅接收一个订阅并逐批请求数据，避免响应背压失效导致无界缓冲。 */
            @Override
            public void onSubscribe(Flow.Subscription next) {
                if (subscription != null) {
                    next.cancel();
                    return;
                }
                subscription = next;
                next.request(1);
            }

            /** 检查每批累计字节预算后再复制，超限立即取消上游并失败正文阶段。 */
            @Override
            public void onNext(List<ByteBuffer> buffers) {
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    if (size > maxBytes - bytes.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("HTTP response exceeded byte limit"));
                        return;
                    }
                    byte[] chunk = new byte[size];
                    buffer.get(chunk);
                    bytes.write(chunk, 0, chunk.length);
                }
                subscription.request(1);
            }

            /** 将上游失败原样传给等待者，避免把传输错误伪装成完整响应。 */
            @Override
            public void onError(Throwable failure) {
                body.completeExceptionally(failure);
            }

            /** 仅在完整响应结束时返回已收集字节，保留调用方的 UTF-8 断言语义。 */
            @Override
            public void onComplete() {
                body.complete(bytes.toByteArray());
            }
        };
    }

    /**
     * 将已限制大小的 HTTP 正文按接口使用的 UTF-8 解码，避免默认字符集影响 SSE 断言。
     */
    private static String responseText(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /**
     * 查询测试用 EmbeddedPostgres 中当前 Thread/Turn 的企业笔记行数，避免只依赖应用读模型回显。
     */
    private static long noteCount(DataSource dataSource, Turn turn) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM ja_agent_demo_note WHERE thread_id = ? AND turn_id = ?")) {
            statement.setString(1, turn.threadId());
            statement.setString(2, turn.turnId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /**
     * 通过真实 Note 外键关联查询已提交 source 行，回滚断言覆盖来源元数据而非只看主表。
     */
    private static long noteSourceCount(DataSource dataSource, Turn turn) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM ja_agent_demo_note_source source
                JOIN ja_agent_demo_note note ON note.call_key = source.call_key
                WHERE note.thread_id = ? AND note.turn_id = ?
                """)) {
            statement.setString(1, turn.threadId());
            statement.setString(2, turn.turnId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /**
     * 只计数 Store 中已持久化的成功 ToolResult，区分失败记录和原始成功结果。
     */
    private static long successfulToolResultCount(DataSource dataSource, Turn turn) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM agent_record
                WHERE thread_id = ? AND turn_id = ? AND record_type = 'agent.tool.result'
                    AND CAST(payload_json AS jsonb) ->> 'state' = 'SUCCEEDED'
                """)) {
            statement.setString(1, turn.threadId());
            statement.setString(2, turn.turnId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /**
     * 查询真实 Core 扩展事实表中的 note.write 数量，验证其与 participant 笔记写入同成同败。
     */
    private static long noteWriteFactCount(DataSource dataSource, Turn turn) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM agent_record
                WHERE thread_id = ? AND turn_id = ? AND record_type = 'agent.extension.fact'
                    AND CAST(payload_json AS text) LIKE '%note.write%'
                """)) {
            statement.setString(1, turn.threadId());
            statement.setString(2, turn.turnId());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getLong(1);
            }
        }
    }

    /**
     * 读取已提交策略事实，确认失败投影没有提前暴露中间件转移。
     */
    private static JsonNode policyFacts(Turn turn) throws Exception {
        return JSON.readTree(post(turn.origin(), "/api/chat/experience/policy/facts", Map.of(
                "threadId", turn.threadId(), "turnId", turn.turnId(), "afterSequence", 0, "limit", 256)).body());
    }

    /**
     * 从当前 Thread 的 Core-backed 历史确认终答包含 participant 保存的真实标题。
     */
    private static void assertTerminalAnswerContainsTitle(Turn turn, String title) throws Exception {
        HttpResponse<String> response = post(turn.origin(), "/api/chat/threads/history", Map.of(
                "threadId", turn.threadId(), "afterSequence", 0, "limit", 256));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode items = JSON.readTree(response.body()).path("items");
        assertTrue(items.findValuesAsText("text").stream().anyMatch(text -> text.contains(title)), items.toString());
    }

    /**
     * 普通文本用例仅声明文本输入，避免无关模态扩展测试模型能力。
     */
    private static ModelPort.ModelConfiguration model(MockWebServer provider) {
        return model(provider, TEXT_ONLY);
    }

    /**
     * 用显式模态集合匹配真实测试输入，上传图片的 participant 场景不能借助过窄模型契约退化。
     */
    private static ModelPort.ModelConfiguration model(MockWebServer provider,
                                                        Set<ModelPort.InputModality> modalities) {
        return EnterpriseTestSupport.model(ModelPort.Api.OPENAI_RESPONSES, provider.url("/v1").uri(), modalities);
    }

    /**
     * 在企业 Document middleware 后对一次成功 save_note 投影抛错，验证 Core 的权威结果保底路径。
     */
    private static final class ProjectionFailureMiddleware implements AgentMiddleware {
        private final AtomicBoolean pending = new AtomicBoolean(true);

        /**
         * 保持测试故障拥有独立、稳定的 middleware 状态命名空间。
         */
        @Override
        public String id() {
            return "test-projection-failure";
        }

        /**
         * 只在真实 save_note 成功结果上注入一次故障，其余调用沿用 Core 默认投影。
         */
        @Override
        public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext context) {
            if ("save_note".equals(context.invocation().toolName())
                    && context.outcome() == ToolOutcome.SUCCEEDED && pending.compareAndSet(true, false)) {
                throw new IllegalStateException("injected enterprise projection failure");
            }
            return AgentMiddleware.super.projectToolResult(context);
        }
    }

    /**
     * 对 EmbeddedPostgres 做单连接故障注入；业务 SQL 与提交、回滚仍由真实驱动执行。
     */
    private static final class FaultDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final Fault fault;
        private final AtomicBoolean fired = new AtomicBoolean();
        private final AtomicBoolean noteInserted = new AtomicBoolean();
        private volatile String threadId;

        /**
         * 绑定一次性故障模式和原始 EmbeddedPostgres 数据源，不接管其生命周期。
         */
        private FaultDataSource(DataSource delegate, Fault fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        /**
         * 将本用例真实创建的 Thread 限定为 fencing 变更目标，避免修改其它会话租约。
         */
        private void threadId(String value) {
            threadId = value;
        }

        /**
         * 暴露注入点是否实际经过 participant/commit，防止测试因未触发故障而形成假阳性。
         */
        private boolean triggered() {
            return fired.get();
        }

        /**
         * 每次连接都代理同一原始驱动连接，让 Store 与 participant 继续共享该事务资源。
         */
        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        /**
         * 保留 DataSource 鉴权重载语义，连接仍进入相同的测试代理边界。
         */
        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        /**
         * 在真实 Tool Started 或 participant SQL 边界注入故障，保持执行器使用 Store 提供的原始事务资源。
         */
        private Connection wrap(Connection connection) {
            AtomicBoolean toolStartedPendingCommit = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if ("commit".equals(method.getName()) && fault == Fault.ADVANCE_FENCE
                                && toolStartedPendingCommit.get()) {
                            Object committed = invoke(method, connection, arguments);
                            toolStartedPendingCommit.set(false);
                            if (fired.compareAndSet(false, true)) {
                                advanceFence();
                            }
                            return committed;
                        }
                        if ("commit".equals(method.getName()) && noteInserted.get()
                                && isCommitFault() && fired.compareAndSet(false, true)) {
                            if (fault == Fault.BEFORE_COMMIT) {
                                throw new SQLException("simulated connection loss before commit", "08006");
                            }
                            invoke(method, connection, arguments);
                            throw new SQLException("simulated connection loss after commit", "08006");
                        }
                        Object value = invoke(method, connection, arguments);
                        if ("prepareStatement".equals(method.getName()) && arguments != null
                                && arguments.length > 0 && arguments[0] instanceof String sql
                                && value instanceof PreparedStatement statement) {
                            return wrap(statement, connection, sql, toolStartedPendingCommit);
                        }
                        return value;
                    });
        }

        /**
         * 追踪 Tool Started 批次以便在该提交释放租约锁后制造真实旧 token，并保留笔记 INSERT 故障点。
         */
        private PreparedStatement wrap(PreparedStatement statement, Connection connection, String sql,
                                       AtomicBoolean toolStartedPendingCommit) {
            Set<String> recordTypes = new LinkedHashSet<>();
            boolean recordInsert = sql.toLowerCase(java.util.Locale.ROOT).strip()
                    .startsWith("insert into agent_record (");
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (recordInsert && "setString".equals(method.getName()) && arguments != null
                                && arguments.length == 2 && arguments[0] instanceof Integer index && index == 5
                                && arguments[1] instanceof String value) {
                            recordTypes.add(value);
                        }
                        Object value = invoke(method, statement, arguments);
                        if (recordInsert && "executeBatch".equals(method.getName())
                                && recordTypes.contains("agent.tool.started")) {
                            toolStartedPendingCommit.set(true);
                            recordTypes.clear();
                        }
                        if (isNoteInsert(sql) && Set.of("executeUpdate", "executeLargeUpdate").contains(method.getName())) {
                            noteInserted.set(true);
                            if (fault == Fault.AFTER_NOTE_INSERT && fired.compareAndSet(false, true)) {
                                throw new SQLException("injected failure after note participant INSERT", "08006");
                            }
                        }
                        return value;
                    });
        }

        /**
         * 在 Tool Started 已提交并释放租约行锁后用独立连接推进 token，真实使后续提交携带的旧租约失效。
         */
        private void advanceFence() throws SQLException {
            String target = threadId;
            if (target == null) {
                throw new SQLException("fencing injection requires a created Thread");
            }
            try (Connection connection = delegate.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE agent_lease SET fencing_token = fencing_token + 1
                    WHERE tenant_id = 'tenant_demo' AND user_id = 'user_demo' AND thread_id = ?
                    """)) {
                statement.setString(1, target);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("active lease was not found for fencing injection");
                }
            }
        }

        /**
         * 直接透传反射目标异常，避免代理包装改变 PostgreSQL SQLState 和 Core 错误分类。
         */
        private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        /**
         * 只对 commit 前后故障启用 Connection.commit 拦截。
         */
        private boolean isCommitFault() {
            return fault == Fault.BEFORE_COMMIT || fault == Fault.AFTER_COMMIT;
        }

        /**
         * 规范 SQL 空白后识别笔记主表写入，不匹配来源表、查询或管理端独立保存。
         */
        private static boolean isNoteInsert(String sql) {
            String normalized = sql.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
            return normalized.startsWith("insert into ja_agent_demo_note(");
        }
    }

    /**
     * participant 或数据库提交的单次可控故障点。
     */
    private enum Fault {
        /** participant 已插入笔记后失败，验证整笔事务回滚。 */
        AFTER_NOTE_INSERT,
        /** Tool Started 提交后推进 fence，验证陈旧执行者不能继续写入。 */
        ADVANCE_FENCE,
        /** 数据库提交前断开连接，供恢复流程核实后重试。 */
        BEFORE_COMMIT,
        /** 数据库已提交但响应丢失，验证 operation receipt 对账。 */
        AFTER_COMMIT
    }
}
