// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.example.springboot.bootstrap.AgentProperties;
import io.github.kongweiguang.ja.agent.example.springboot.bootstrap.DatabaseAgentConfiguration;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource;
import io.github.kongweiguang.ja.agent.store.pg.infrastructure.PostgresAgentStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.boot.ApplicationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.Set;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 HTTP 验证流式准入、回放和审批；显式 close 使资源释放失败直接失败，而非被 Spring 转成警告。
 */
class AgentExampleStreamingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /**
     * 仅用测试 PostgreSQL 的公开连接信息配置 Spring，保留 Boot 对生产连接池的真实自动装配。
     */
    private static ConfigurableApplicationContext start(EmbeddedPostgres postgres, MockWebServer server) {
        return start(postgres, server, false);
    }

    /**
     * 能力测试仅额外启用实际扩展；底层 PostgreSQL 始终由外层测试资源持有。
     */
    private static ConfigurableApplicationContext start(EmbeddedPostgres postgres, MockWebServer server,
                                                         boolean experience) {
        return start(postgres, server, experience, ignored -> { });
    }

    /**
     * 显式初始化 Core schema 后只把 EmbeddedPostgres 的 URL 交给 Boot，避免把 caller DataSource
     * 注册成会令自动配置退让的裸 Bean；附加 Bean 仅用于验证启动失败时上下文收口。
     */
    private static ConfigurableApplicationContext start(EmbeddedPostgres postgres, MockWebServer server,
                                                         boolean experience,
                                                         Consumer<GenericApplicationContext> additionalBeans) {
        PostgresAgentStore.bootstrap(postgres.getPostgresDatabase());
        ModelPort.ModelConfiguration model = new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test",
                ModelPort.Api.OPENAI_CHAT_COMPLETIONS, "demo-model", server.url("/v1").uri(), "test-only-key",
                Duration.ofSeconds(5), Duration.ofSeconds(30), Set.of(ModelPort.InputModality.TEXT),
                ModelPort.GenerationOptions.defaults());
        return new SpringApplicationBuilder(SpringBootAgentExample.class).web(WebApplicationType.SERVLET)
                .initializers(application -> {
                    GenericApplicationContext beans = (GenericApplicationContext) application;
                    beans.registerBean("modelConfiguration", ModelPort.ModelConfiguration.class, () -> model);
                    beans.registerBean("contextBudget", ContextBudget.class, () -> ContextBudget.capabilities(8_192, 1_024, true));
                    beans.registerBean("approvalProbe", AgentTool.class, ApprovalProbe::new);
                    additionalBeans.accept(beans);
                }).run("--server.port=0", "--spring.main.banner-mode=off", "--agent.experience.enabled=" + experience,
                        "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"));
    }

    /**
     * 复用同一 PostgreSQL backend PID 证明连接已经归还并再次借出，而不以耗时或连接数猜测池化。
     */
    private static int postgresBackendPid(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    /**
     * 使用真实 WebServer 分配的端口，避免测试假定默认监听地址。
     */
    private static String origin(ConfigurableApplicationContext application) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
    }

    /** 通过真实 PostgreSQL 元数据校验列类型，避免仅以可运行 SQL 误判 schema 符合新基线。 */
    private static void assertColumnType(JdbcTemplate jdbc, String table, String column, String expectedType) {
        String actualType = jdbc.queryForObject("""
                SELECT data_type
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                """, String.class, table, column);
        assertEquals(expectedType, actualType, table + "." + column);
    }

    /** 数据库时间列使用 UTC epoch 毫秒，HTTP 仍以 ISO-8601 表示同一毫秒精度事实。 */
    private static void assertThreadTimes(JdbcTemplate jdbc, String threadId, JsonNode response) {
        long[] persisted = jdbc.queryForObject("""
                SELECT created_at_ms, updated_at_ms
                FROM ja_agent_demo_thread
                WHERE tenant_id = 'tenant_demo' AND user_id = 'user_demo' AND thread_id = ?
                """, (row, ignored) -> new long[]{row.getLong(1), row.getLong(2)}, threadId);
        assertEquals(persisted[0], Instant.parse(response.path("createdAt").asText()).toEpochMilli());
        assertEquals(persisted[1], Instant.parse(response.path("updatedAt").asText()).toEpochMilli());
    }

    /** 明确验证业务约束在 PostgreSQL schema 中存在，而非只依靠服务层预检查。 */
    private static void assertConstraint(JdbcTemplate jdbc, String table, String constraint) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.table_constraints
                    WHERE table_schema = current_schema() AND table_name = ? AND constraint_name = ?
                )
                """, Boolean.class, table, constraint);
        assertTrue(Boolean.TRUE.equals(exists), table + "." + constraint);
    }

    /** 索引命名和列类型同时作为可执行契约，确保列表真实查询仍可使用对应复合索引。 */
    private static void assertIndex(JdbcTemplate jdbc, String index) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_indexes
                    WHERE schemaname = current_schema() AND indexname = ?
                )
                """, Boolean.class, index);
        assertTrue(Boolean.TRUE.equals(exists), index);
    }

    /**
     * 用 backend PID 集合证明真实 Spring 池在多物理连接配置下仍会复用；随后分别核对应用池与调用方数据库的关闭边界。
     */
    @Test
    void usesBootManagedPoolAndClosesOnlyTheApplicationDataSource() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            DataSource callerDataSource = postgres.getPostgresDatabase();
            DataSource managedDataSource;
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                managedDataSource = application.getBean(DataSource.class);
                assertEquals("com.zaxxer.hikari.HikariDataSource", managedDataSource.getClass().getName());
                int maximumPoolSize = 10;
                assertEquals(Integer.valueOf(maximumPoolSize), application.getEnvironment()
                        .getProperty("spring.datasource.hikari.maximum-pool-size", Integer.class));

                int borrowCount = maximumPoolSize * 2;
                Set<Integer> backendPids = new HashSet<>(maximumPoolSize);
                for (int borrow = 0; borrow < borrowCount; borrow++) {
                    backendPids.add(postgresBackendPid(managedDataSource));
                }
                assertTrue(backendPids.size() <= maximumPoolSize,
                        "observed backend connections must stay within the configured pool capacity");
                assertTrue(backendPids.size() < borrowCount,
                        "sequential borrows must reuse at least one physical backend connection");
            }

            assertThrows(SQLException.class, managedDataSource::getConnection);
            assertEquals(1, new JdbcTemplate(callerDataSource).queryForObject("SELECT 1", Integer.class));
        }
    }

    /**
     * 启动后置失败也必须由 Spring 关闭已创建的池，同时不能关闭测试调用方持有的 PostgreSQL 进程。
     */
    @Test
    void closesBootManagedPoolWhenStartupFailsAfterContextRefresh() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            DataSource callerDataSource = postgres.getPostgresDatabase();
            AtomicReference<DataSource> managedDataSource = new AtomicReference<>();

            assertThrows(RuntimeException.class, () -> start(postgres, model, false,
                    beans -> {
                        beans.addApplicationListener(event -> {
                            if (event instanceof ContextRefreshedEvent refreshed) {
                                managedDataSource.set(refreshed.getApplicationContext().getBean(DataSource.class));
                            }
                        });
                        beans.registerBean("intentionalStartupFailure", ApplicationRunner.class,
                                () -> args -> { throw new IllegalStateException("intentional startup failure"); });
                    }));

            assertNotNull(managedDataSource.get());
            assertEquals("com.zaxxer.hikari.HikariDataSource", managedDataSource.get().getClass().getName());
            assertThrows(SQLException.class, managedDataSource.get()::getConnection);
            assertEquals(1, new JdbcTemplate(callerDataSource).queryForObject("SELECT 1", Integer.class));
        }
    }

    /**
     * 每个用例先走公开创建契约，确保测试覆盖聊天提交必须存在的 Thread 元数据锚点。
     */
    private static TestTurn base(ConfigurableApplicationContext application, String name) throws Exception {
        String baseOrigin = origin(application);
        HttpResponse<String> response = request(baseOrigin + "/api/chat/threads/create", "POST",
                JSON.writeValueAsString(java.util.Map.of("operationKey", "test_thread_" + name)));
        assertEquals(200, response.statusCode(), response.body());
        String threadId = JSON.readTree(response.body()).path("threadId").asText();
        assertTrue(!threadId.isBlank(), response.body());
        return new TestTurn(baseOrigin, threadId, "turn_" + name);
    }

    /**
     * 让既有文本回归也经过统一 Content DTO，避免测试继续掩盖客户端与服务端契约分叉。
     */
    private static String inputBody(String operationKey, String text) throws Exception {
        return inputBody(operationKey, text, java.util.List.of(), java.util.List.of());
    }

    /**
     * 所有聊天输入按 Skill、附件、文本字段统一构造；队列及附件测试共享同一协议形状。
     */
    private static String inputBody(String operationKey, String text, java.util.List<String> skillIds,
                                    java.util.List<String> attachmentIds) throws Exception {
        ObjectNode content = JSON.createObjectNode();
        if (text == null) {
            content.putNull("text");
        } else {
            content.put("text", text);
        }
        content.set("skillIds", JSON.valueToTree(skillIds));
        content.set("attachmentIds", JSON.valueToTree(attachmentIds));
        ObjectNode body = JSON.createObjectNode().put("operationKey", operationKey);
        body.set("content", content);
        return JSON.writeValueAsString(body);
    }

    /** 固定 Notes 查询契约只发送 Thread，测试不会靠省略身份字段掩盖 HTTP 边界变化。 */
    private static String notesQuery(TestTurn turn) throws Exception {
        return JSON.writeValueAsString(java.util.Map.of("threadId", turn.threadId()));
    }

    /**
     * JSON 正文携带完整 Thread/Turn，测试调用真实 POST 路由。
     */
    private static HttpRequest builder(TestTurn turn, String action, String body) throws Exception {
        ObjectNode payload = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(body == null ? "{}" : body);
        payload.put("threadId", turn.threadId()).put("turnId", turn.turnId());
        return builder(turn.origin() + "/api/chat/" + action, "POST", JSON.writeValueAsString(payload));
    }

    /**
     * 从实际 HTTP 响应核验请求体接口，不直接调用 Engine。
     */
    private static HttpResponse<String> request(TestTurn turn, String action, String body) throws Exception {
        return HTTP.send(builder(turn, action, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 静态页面使用 GET，所有对话接口使用 JSON POST。
     */
    private static HttpRequest builder(String url, String method, String body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /**
     * HTTP 断言直接读取真实响应，不通过私有 Engine 方法替代接口验收。
     */
    private static HttpResponse<String> request(String url, String method, String body) throws Exception {
        return HTTP.send(builder(url, method, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 等待约束属于测试而非 Agent 任务预算，失败报告最后一个真实状态。
     */
    private static void awaitState(TestTurn base, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        String actual = "";
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = request(base, "status", "{}");
            actual = JSON.readTree(response.body()).path("state").asText();
            if (actual.equals(expected)) {
                return;
            }
            if (actual.equals("FAILED")) {
                break;
            }
            Thread.sleep(40);
        }
        assertEquals(expected, actual, request(base, "events", "{}").body());
    }

    /**
     * 保留供应商完整终态和 Usage；受控延迟用于确保取消发生在活动请求内。
     */
    private static void enqueue(MockWebServer server, String delta, String finish, long delayMillis) {
        String body = """
                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"demo-model","choices":[{"index":0,"delta":%s,"finish_reason":null}]}
                
                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"demo-model","choices":[{"index":0,"delta":{},"finish_reason":"%s"}]}
                
                data: {"id":"chatcmpl-test","object":"chat.completion.chunk","model":"demo-model","choices":[],"usage":{"prompt_tokens":20,"completion_tokens":5,"total_tokens":25}}
                
                data: [DONE]
                """.formatted(delta, finish);
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(body).setBodyDelay(delayMillis, TimeUnit.MILLISECONDS));
    }

    /**
     * 草稿在准入前订阅；公开元数据不泄露连接配置，重连和同键重试不重复 Provider 请求。
     */
    @Test
    void streamsTextAndReplaysCommittedMessagesWithoutResubmitting() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, "{\"content\":\"中文最终答复\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                HttpResponse<String> metadata = request(origin(application) + "/api/chat/model", "POST", "{}");
                assertEquals(200, metadata.statusCode());
                JsonNode labels = JSON.readTree(metadata.body());
                assertTrue(labels.path("model").isTextual());
                assertTrue(labels.path("experience").isBoolean());
                assertEquals("demo-model", labels.path("model").asText());
                assertFalse(labels.path("experience").asBoolean());
                assertTrue(labels.path("attachmentCapabilities").isObject());
                assertTrue(labels.path("attachmentCapabilities").path("effectiveFormats").isEmpty());
                assertEquals(0, labels.path("attachmentCapabilities").path("maxSingleBytes").asLong());
                TestTurn base = base(application, "text");
                HttpResponse<String> streamed = request(base, "stream", inputBody("submit_text", "你好"));
                assertEquals(200, streamed.statusCode(), streamed.body());
                assertTrue(streamed.body().contains("event:text-delta"), streamed.body());
                assertTrue(streamed.body().contains("agent.execution.terminal"), streamed.body());
                assertTrue(streamed.body().contains("中文最终答复"), streamed.body());
                JsonNode events = JSON.readTree(request(base, "events", "{}").body()).path("events");
                String recordId = "";
                for (JsonNode event : events) {
                    if (event.path("eventType").asText().equals("agent.model.completed")) {
                        recordId = JSON.readTree(event.path("payloadJson").asText()).path("messageRecordId").asText();
                    }
                }
                assertTrue(!recordId.isBlank());
                JsonNode message = JSON.readTree(request(base, "message", "{\"recordId\":\"" + recordId + "\"}").body());
                assertEquals("中文最终答复", message.path("text").asText());
                JsonNode status = JSON.readTree(request(base, "status", "{}").body());
                assertEquals("user_demo", status.path("identity").path("userId").asText());
                assertEquals(base.threadId(), status.path("identity").path("threadId").asText());
                assertEquals("turn_text", status.path("identity").path("turnId").asText());
                assertTrue(!status.path("identity").has("tenantId"));
                assertEquals(400, request(origin(application) + "/api/chat/status", "POST", "{}").statusCode());
                assertEquals(405, request(origin(application) + "/api/chat/status", "GET", null).statusCode());
                assertEquals(400, request(base, "reconnect", "{\"afterSequence\":-1}").statusCode());
                AgentExampleStreamingTest.TestTurn otherTurn = new TestTurn(base.origin(), base.threadId(), "turn_other");
                assertEquals(404, request(otherTurn, "message", "{\"recordId\":\"" + recordId + "\"}").statusCode());
                assertTrue(message.path("calls").isArray());
                assertTrue(request(base, "reconnect", "{}").body().contains("agent.execution.terminal"));
                assertEquals(202, request(base, "submit", inputBody("submit_text", "你好")).statusCode());
                assertEquals(1, model.getRequestCount());
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /**
     * 从模型实际收到的系统消息验证角色绑定，而非只检查创建接口回显；同一 Thread 的后续 Turn 必须延续角色。
     */
    @Test
    void selectsHostPromptByThreadAndKeepsItAcrossTurns() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, "{\"content\":\"分析首轮\"}", "stop", 0);
            enqueue(model, "{\"content\":\"分析追问\"}", "stop", 0);
            enqueue(model, "{\"content\":\"通用答复\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                String endpoint = origin(application) + "/api/chat/threads";
                HttpResponse<String> catalog = request(endpoint + "/agents", "POST", "{}");
                assertEquals(200, catalog.statusCode(), catalog.body());
                assertTrue(catalog.body().contains("\"id\":\"analyst\""));
                assertTrue(catalog.body().contains("\"id\":\"general\""));
                assertFalse(catalog.body().contains("systemPrompt"));

                HttpResponse<String> selected = request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_analyst\",\"agentId\":\"analyst\"}");
                assertEquals(200, selected.statusCode(), selected.body());
                JsonNode analyst = JSON.readTree(selected.body());
                assertEquals("analyst", analyst.path("agentId").asText());
                HttpResponse<String> retried = request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_analyst\",\"agentId\":\"analyst\"}");
                assertEquals(analyst.path("threadId").asText(), JSON.readTree(retried.body()).path("threadId").asText());
                assertEquals(409, request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_analyst\",\"agentId\":\"general\"}").statusCode());
                assertEquals(400, request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_unknown\",\"agentId\":\"unknown\"}").statusCode());

                AgentExampleStreamingTest.TestTurn first = new TestTurn(origin(application), analyst.path("threadId").asText(), "turn_analyst_1");
                assertEquals(200, request(first, "stream",
                        inputBody("submit_analyst_1", "分析第一题")).statusCode());
                AgentExampleStreamingTest.TestTurn second = new TestTurn(origin(application), first.threadId(), "turn_analyst_2");
                assertEquals(200, request(second, "stream",
                        inputBody("submit_analyst_2", "继续分析")).statusCode());

                HttpResponse<String> generalResponse = request(endpoint + "/create", "POST", "{\"operationKey\":\"create_general\"}");
                assertEquals(200, generalResponse.statusCode(), generalResponse.body());
                JsonNode general = JSON.readTree(generalResponse.body());
                assertEquals("general", general.path("agentId").asText());
                AgentExampleStreamingTest.TestTurn generalTurn = new TestTurn(origin(application), general.path("threadId").asText(), "turn_general");
                assertEquals(200, request(generalTurn, "stream",
                        inputBody("submit_general", "你好")).statusCode());

                for (int index = 0; index < 3; index++) {
                    RecordedRequest captured = model.takeRequest(10, TimeUnit.SECONDS);
                    assertNotNull(captured);
                    JsonNode messages = JSON.readTree(captured.getBody().readUtf8()).path("messages");
                    String system = messages.get(0).path("content").asText();
                    assertEquals("system", messages.get(0).path("role").asText());
                    assertFalse(system.contains("You are a general-purpose agent"));
                    if (index < 2) {
                        assertTrue(system.contains("你是数据分析助手"), system);
                    }
                    else {
                        assertTrue(system.contains("你是一个通用智能体"), system);
                    }
                }
                assertEquals(3, model.getRequestCount());
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /**
     * 真实 PG 验证示例表契约、Thread UTC 毫秒映射、幂等、固定水位和标题意图；HTTP 时间须与数据库事实一致。
     */
    @Test
    void createsListsUpdatesAndReadsThreadHistoryAtAFixedWatermark() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, "{\"content\":\"第一轮答复\"}", "stop", 2_000);
            enqueue(model, "{\"content\":\"第二轮答复\"}", "stop", 0);
            enqueue(model, "{\"content\":\"默认标题首轮答复\"}", "stop", 0);
            enqueue(model, "{\"content\":\"默认标题次轮答复\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
                assertColumnType(jdbc, "ja_agent_demo_agent", "agent_id", "text");
                assertConstraint(jdbc, "ja_agent_demo_agent", "pk_ja_agent_demo_agent");
                assertColumnType(jdbc, "ja_agent_demo_config", "updated_at_ms", "bigint");
                assertConstraint(jdbc, "ja_agent_demo_config", "pk_ja_agent_demo_config");
                assertColumnType(jdbc, "ja_agent_demo_note", "created_at_ms", "bigint");
                assertConstraint(jdbc, "ja_agent_demo_note", "pk_ja_agent_demo_note");
                assertColumnType(jdbc, "ja_agent_demo_thread", "is_archived", "boolean");
                assertColumnType(jdbc, "ja_agent_demo_thread", "created_at_ms", "bigint");
                assertColumnType(jdbc, "ja_agent_demo_thread", "updated_at_ms", "bigint");
                assertConstraint(jdbc, "ja_agent_demo_thread", "pk_ja_agent_demo_thread");
                assertConstraint(jdbc, "ja_agent_demo_thread", "uk_ja_agent_demo_thread_tenant_user_operation_key");
                assertIndex(jdbc, "idx_ja_agent_demo_thread_owner_archive_updated");
                String endpoint = origin(application) + "/api/chat/threads";
                HttpResponse<String> createdResponse = request(endpoint + "/create", "POST", "{\"operationKey\":\"create_history\"}");
                assertEquals(200, createdResponse.statusCode(), createdResponse.body());
                JsonNode created = JSON.readTree(createdResponse.body());
                String threadId = created.path("threadId").asText();
                assertTrue(threadId.startsWith("thr_"));
                assertEquals("新对话", created.path("title").asText());
                assertEquals(0, created.path("version").asLong());
                assertThreadTimes(jdbc, threadId, created);

                JsonNode retried = JSON.readTree(request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_history\"}").body());
                assertEquals(threadId, retried.path("threadId").asText());
                JsonNode other = JSON.readTree(request(endpoint + "/create", "POST",
                        "{\"operationKey\":\"create_other\"}").body());
                assertTrue(!other.path("threadId").asText().isBlank());
                assertTrue(!threadId.equals(other.path("threadId").asText()));

                JsonNode listed = JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"\",\"limit\":100,\"offset\":0}").body());
                assertEquals(2, listed.size());

                HttpResponse<String> renamedResponse = request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":0,\"title\":\"  工作台验收  \"}");
                assertEquals(200, renamedResponse.statusCode(), renamedResponse.body());
                JsonNode renamed = JSON.readTree(renamedResponse.body());
                assertEquals("工作台验收", renamed.path("title").asText());
                assertEquals(1, renamed.path("version").asLong());
                assertThreadTimes(jdbc, threadId, renamed);
                assertEquals(1, JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"验收\",\"limit\":100,\"offset\":0}").body()).size());
                HttpResponse<String> resetTitleResponse = request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":1,\"title\":\"新对话\"}");
                assertEquals(200, resetTitleResponse.statusCode(), resetTitleResponse.body());
                assertEquals(2, JSON.readTree(resetTitleResponse.body()).path("version").asLong());
                assertEquals(409, request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":1,\"archived\":true}").statusCode());

                TestTurn firstTurn = new TestTurn(origin(application), threadId, "turn_history_1");
                CompletableFuture<HttpResponse<String>> firstStreamFuture = HTTP.sendAsync(builder(firstTurn, "stream",
                        inputBody("submit_history_1", "第一轮问题")), HttpResponse.BodyHandlers.ofString());
                assertNotNull(model.takeRequest(10, TimeUnit.SECONDS));
                assertEquals(409, request(new TestTurn(origin(application), threadId, "turn_history_conflict"), "submit",
                        inputBody("submit_history_conflict", "冲突输入")).statusCode());
                assertEquals(202, request(firstTurn, "submit",
                        inputBody("submit_history_1", "第一轮问题")).statusCode());
                assertEquals(409, request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":2,\"archived\":true}").statusCode());
                HttpResponse<String> firstStream = firstStreamFuture.get(20, TimeUnit.SECONDS);
                assertEquals(200, firstStream.statusCode(), firstStream.body());
                assertTrue(firstStream.body().contains("第一轮答复"), firstStream.body());
                JsonNode titleAfterFirstMessage = JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"\",\"limit\":100,\"offset\":0}").body())
                        .findParents("threadId").stream()
                        .filter(item -> item.path("threadId").asText().equals(threadId)).findFirst().orElseThrow();
                assertEquals("新对话", titleAfterFirstMessage.path("title").asText());
                JsonNode firstPage = JSON.readTree(request(endpoint + "/history", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"afterSequence\":0,\"limit\":1}").body());
                long throughSequence = firstPage.path("throughSequence").asLong();
                assertTrue(throughSequence > 0);

                TestTurn secondTurn = new TestTurn(origin(application), threadId, "turn_history_2");
                HttpResponse<String> secondStream = request(secondTurn, "stream", inputBody("submit_history_2", "第二轮问题"));
                assertEquals(200, secondStream.statusCode(), secondStream.body());
                assertTrue(secondStream.body().contains("第二轮答复"), secondStream.body());
                JsonNode titleAfterSecondMessage = JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"\",\"limit\":100,\"offset\":0}").body())
                        .findParents("threadId").stream()
                        .filter(item -> item.path("threadId").asText().equals(threadId)).findFirst().orElseThrow();
                assertEquals("新对话", titleAfterSecondMessage.path("title").asText());

                String autoTitleThreadId = other.path("threadId").asText();
                TestTurn autoTitleFirstTurn = new TestTurn(origin(application), autoTitleThreadId, "turn_auto_title_1");
                HttpResponse<String> autoTitleFirstResponse = request(autoTitleFirstTurn, "stream",
                        inputBody("submit_auto_title_1", "新对话"));
                assertEquals(200, autoTitleFirstResponse.statusCode(), autoTitleFirstResponse.body());
                assertTrue(autoTitleFirstResponse.body().contains("默认标题首轮答复"), autoTitleFirstResponse.body());
                TestTurn autoTitleSecondTurn = new TestTurn(origin(application), autoTitleThreadId, "turn_auto_title_2");
                HttpResponse<String> autoTitleSecondResponse = request(autoTitleSecondTurn, "stream",
                        inputBody("submit_auto_title_2", "第二轮改用不同内容"));
                assertEquals(200, autoTitleSecondResponse.statusCode(), autoTitleSecondResponse.body());
                assertTrue(autoTitleSecondResponse.body().contains("默认标题次轮答复"), autoTitleSecondResponse.body());
                JsonNode titleAfterAutoTitleInitialization = JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"\",\"limit\":100,\"offset\":0}").body())
                        .findParents("threadId").stream()
                        .filter(item -> item.path("threadId").asText().equals(autoTitleThreadId)).findFirst().orElseThrow();
                assertEquals("新对话", titleAfterAutoTitleInitialization.path("title").asText());

                ArrayList<JsonNode> historyItems = new java.util.ArrayList<JsonNode>();
                JsonNode page = firstPage;
                long afterSequence = 0;
                int pageCount = 0;
                while (true) {
                    page.path("items").forEach(historyItems::add);
                    if (!page.path("hasMore").asBoolean()) {
                        break;
                    }
                    afterSequence = page.path("nextSequence").asLong();
                    assertTrue(afterSequence > 0, "history cursor must advance");
                    assertEquals(throughSequence, page.path("throughSequence").asLong());
                    page = JSON.readTree(request(endpoint + "/history", "POST",
                            "{\"threadId\":\"" + threadId + "\",\"afterSequence\":" + afterSequence
                                    + ",\"throughSequence\":" + throughSequence + ",\"limit\":1}").body());
                    assertTrue(++pageCount < 32, "history paging must terminate");
                }
                assertTrue(historyItems.stream().anyMatch(item -> item.path("content").path("text").asText().equals("第一轮问题")));
                assertTrue(historyItems.stream().anyMatch(item -> item.path("text").asText().equals("第一轮答复")));
                assertTrue(historyItems.stream().noneMatch(item -> item.path("content").path("text").asText().contains("第二轮")));
                JsonNode firstUserMessage = historyItems.stream()
                        .filter(item -> item.path("kind").asText().equals("user")
                                && item.path("content").path("text").asText().equals("第一轮问题"))
                        .findFirst().orElseThrow();
                assertTrue(!firstUserMessage.path("recordId").asText().isBlank());
                assertTrue(historyItems.stream().anyMatch(item -> item.path("kind").asText().equals("usage")
                        && !item.path("recordId").asText().isBlank()));

                HttpResponse<String> archivedResponse = request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":2,\"archived\":true}");
                assertEquals(200, archivedResponse.statusCode(), archivedResponse.body());
                assertEquals(3, JSON.readTree(archivedResponse.body()).path("version").asLong());
                assertEquals(1, JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":false,\"search\":\"\",\"limit\":100,\"offset\":0}").body()).size());
                assertEquals(1, JSON.readTree(request(endpoint + "/list", "POST",
                        "{\"archived\":true,\"search\":\"\",\"limit\":100,\"offset\":0}").body()).size());
                HttpResponse<String> restoredResponse = request(endpoint + "/update", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"expectedVersion\":3,\"archived\":false}");
                assertEquals(200, restoredResponse.statusCode(), restoredResponse.body());
                assertEquals(4, JSON.readTree(restoredResponse.body()).path("version").asLong());

                assertEquals(404, request(endpoint + "/history", "POST",
                        "{\"threadId\":\"thr_missing\",\"afterSequence\":0,\"limit\":10}").statusCode());
                assertEquals(404, request(endpoint + "/update", "POST",
                        "{\"threadId\":\"thr_missing\",\"expectedVersion\":0,\"archived\":true}").statusCode());
                assertEquals(400, request(endpoint + "/history", "POST",
                        "{\"threadId\":\"" + threadId + "\",\"afterSequence\":0,\"throughSequence\":-1,\"limit\":10}").statusCode());
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /** 搜索词中的反斜杠是普通字符，不能成为 LIKE 转义符并改变匹配结果。 */
    @Test
    void searchesThreadTitlesWithLiteralBackslash() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                String endpoint = origin(application) + "/api/chat/threads";
                String targetId = JSON.readTree(request(endpoint + "/create", "POST",
                        JSON.writeValueAsString(java.util.Map.of("operationKey", "backslash_target"))).body())
                        .path("threadId").asText();
                String distractorId = JSON.readTree(request(endpoint + "/create", "POST",
                        JSON.writeValueAsString(java.util.Map.of("operationKey", "backslash_distractor"))).body())
                        .path("threadId").asText();
                request(endpoint + "/update", "POST", JSON.writeValueAsString(java.util.Map.of(
                        "threadId", targetId, "expectedVersion", 0, "title", "literal \\ marker")));
                request(endpoint + "/update", "POST", JSON.writeValueAsString(java.util.Map.of(
                        "threadId", distractorId, "expectedVersion", 0, "title", "literal % marker")));

                JsonNode matches = JSON.readTree(request(endpoint + "/list", "POST", JSON.writeValueAsString(java.util.Map.of(
                        "archived", false, "search", "\\", "limit", 10, "offset", 0))).body());

                assertEquals(1, matches.size());
                assertEquals(targetId, matches.get(0).path("threadId").asText());
            }
        }
    }

    /**
     * 真实等待必须先答后恢复；同一 SSE 连接观察结算与终态，不需要重建任务。
     */
    @Test
    void answersApprovalAndResumesTheOriginalStream() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, """
                {"tool_calls":[{"index":0,"id":"call_checklist","type":"function",\
                "function":{"name":"approval_probe","arguments":"{}"}}]}\
                """, "tool_calls", 0);
            enqueue(model, "{\"content\":\"已确认，操作完成。\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model, true)) {
                TestTurn base = base(application, "approval");
                CompletableFuture<HttpResponse<String>> stream = HTTP.sendAsync(builder(base, "stream",
                        inputBody("submit_approval", "执行测试操作")), HttpResponse.BodyHandlers.ofString());
                awaitState(base, "WAITING_APPROVAL");
                assertEquals(1, model.getRequestCount());
                JsonNode events = JSON.readTree(request(base, "events", "{}").body()).path("events");
                String recordId = "";
                for (JsonNode event : events) {
                    if (event.path("eventType").asText().equals("agent.wait.requested")) {
                        recordId = JSON.readTree(event.path("payloadJson").asText()).path("requestRecordId").asText();
                    }
                }
                JsonNode wait = JSON.readTree(request(base, "wait", "{\"recordId\":\"" + recordId + "\"}").body());
                assertEquals("approval", wait.path("kind").asText());
                String body = """
                        {"operationKey":"answer_approval","interactionId":"%s","expectedInteractionRevision":%d,
                         "answers":[{"questionId":"%s","optionIds":["option_approve"],"freeText":null,"skipped":false}]}
                        """.formatted(wait.path("interactionId").asText(), wait.path("interactionRevision").asLong(),
                        wait.path("questions").get(0).path("questionId").asText());
                assertEquals(204, request(base, "answers", body).statusCode());
                awaitState(base, "SUSPENDED");
                assertEquals(1, model.getRequestCount(), "answering must not dispatch the next model request");
                assertEquals(202, request(base, "resume", "{}").statusCode());
                HttpResponse<String> completed = stream.get(30, TimeUnit.SECONDS);
                assertTrue(completed.body().contains("WAIT_RESOLVED"), completed.body());
                assertTrue(completed.body().contains("agent.execution.terminal"), completed.body());
                awaitState(base, "COMPLETED");
                assertEquals(2, model.getRequestCount());
                assertNotNull(model.takeRequest(5, TimeUnit.SECONDS));
                assertTrue(model.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8().contains("用户已确认"));
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /**
     * Provider 收到请求后延迟发送 SSE 正文，确保取消命中真实在途 HTTP 调用而非已结束的模型响应。
     */
    @Test
    void cancelsAnActiveStreamingRequest() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, "{\"content\":\"迟到的模型答复\"}", "stop", 30_000);
            try (ConfigurableApplicationContext application = start(postgres, model)) {
                TestTurn base = base(application, "cancel");
                CompletableFuture<HttpResponse<String>> stream = HTTP.sendAsync(builder(base, "stream",
                        inputBody("submit_cancel", "等待模型")), HttpResponse.BodyHandlers.ofString());
                RecordedRequest providerRequest = model.takeRequest(10, TimeUnit.SECONDS);
                if (providerRequest == null) {
                    JsonNode status = JSON.readTree(request(base, "status", "{}").body());
                    String events = JSON.readTree(request(base, "events", "{}").body())
                            .path("events").findValuesAsText("eventType").toString();
                    String streamState = stream.isDone()
                            ? stream.handle((response, failure) -> failure == null
                                    ? "completed:" + response.statusCode()
                                    : "failed:" + failure.getClass().getSimpleName()).join()
                            : "pending";
                    assertNotNull(providerRequest, "provider did not receive the request; turn="
                            + status.path("state").asText() + ", events=" + events + ", stream=" + streamState);
                }
                assertEquals(1, model.getRequestCount());
                assertEquals(204, request(base, "cancel",
                        "{\"operationKey\":\"cancel_test\",\"reason\":\"User cancelled.\"}").statusCode());
                HttpResponse<String> result = stream.get(20, TimeUnit.SECONDS);
                assertTrue(result.body().contains("CANCELLED"), result.body());
                awaitState(base, "CANCELLED");
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /**
     * HTTP 边界返回具体的 Skill 和工具目录形状；同一用例验证版本冲突、资源保留与真实 Provider 使用。
     */
    @Test
    void readsPlaintextConfigurationAndManagesSkillsWithoutLosingResources() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start(); enqueue(model, "{\"content\":\"遵守技能答复\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model, true)) {
                AgentExampleStreamingTest.TestTurn base = base(application, "skills");
                JsonNode metadata = JSON.readTree(request(base, "model", "{}").body());
                assertEquals("demo-model", metadata.path("model").asText());
                assertTrue(metadata.path("experience").asBoolean());
                assertTrue(metadata.path("attachmentCapabilities").isObject());
                JsonNode skills = JSON.readTree(request(base, "experience/skills", "{}").body());
                assertTrue(skills.isArray());
                assertTrue(java.util.stream.StreamSupport.stream(skills.spliterator(), false)
                        .anyMatch(skill -> "brief-reply".equals(skill.path("name").asText())));
                JsonNode brief = JSON.readTree(request(base, "experience/skills/read", "{\"name\":\"brief-reply\"}").body());
                assertEquals("brief-reply", brief.path("name").asText());
                assertTrue(brief.path("body").isTextual());
                JsonNode tools = JSON.readTree(request(base, "experience/tools", "{}").body());
                JsonNode saveNote = java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                        .filter(tool -> "save_note".equals(tool.path("name").asText())).findFirst().orElseThrow();
                assertTrue(saveNote.path("inputSchema").isObject());
                assertTrue(saveNote.path("inputSchema").path("properties").isObject());
                PostgresSkillSource source = application.getBean(io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource.class);
                SkillDescriptor created = source.write(new io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext("tenant_demo", java.util.Map.of()),
                        new io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource.SkillPackage("resource-test", "resource test", "old body", java.util.Map.of("data.txt", "keep".getBytes(java.nio.charset.StandardCharsets.UTF_8))), 0);
                String update = "{\"name\":\"resource-test\",\"description\":\"changed\",\"body\":\"new body\",\"expectedVersion\":" + created.version() + "}";
                assertEquals(200, request(base, "experience/skills/write", update).statusCode());
                assertEquals(409, request(base, "experience/skills/write", update).statusCode());
                SkillDescriptor descriptor = source.discover(new io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext("tenant_demo", java.util.Map.of()), null, 256).skills().stream().filter(s -> s.name().equals("resource-test")).findFirst().orElseThrow();
                assertEquals("keep", source.read(new io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext("tenant_demo", java.util.Map.of()), descriptor,
                        new io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest("resource-test", "data.txt", 256)).content());
                DatabaseAgentConfiguration configuration = application.getBean(io.github.kongweiguang.ja.agent.example.springboot.bootstrap.DatabaseAgentConfiguration.class);
                JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(postgres.getPostgresDatabase());
                assertColumnType(jdbc, "ja_agent_demo_config", "updated_at_ms", "bigint");
                assertConstraint(jdbc, "ja_agent_demo_config", "pk_ja_agent_demo_config");
                assertTrue(jdbc.queryForObject(
                        "SELECT updated_at_ms > 0 FROM ja_agent_demo_config WHERE profile='demo'", Boolean.class));
                assertEquals(1, jdbc.update("""
                    UPDATE ja_agent_demo_config SET revision=revision+1, \
                    config=jsonb_set(jsonb_set(config, '{model,apiKey}', to_jsonb(CAST(? AS text))), \
                    '{model,baseUri}', to_jsonb(CAST(? AS text))), \
                    updated_at_ms=floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint \
                    WHERE profile='demo'\
                    """,
                        "test-private-secret", model.url("/v1").toString()));
                AgentProperties loaded = configuration.load();
                assertEquals("test-private-secret", loaded.model().apiKey());
                assertEquals(model.url("/v1").toString(), loaded.model().baseUri());
                assertEquals("cfg_db_2", loaded.model().configGeneration());
                assertEquals(202, request(base, "submit", inputBody("skill_submit", "请回答",
                        java.util.List.of("demo-pg:brief-reply"), java.util.List.of())).statusCode());
                awaitState(base, "COMPLETED");
                String providerRequest = model.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
                assertTrue(providerRequest.contains("最多三条"), providerRequest);
                assertTrue(providerRequest.contains("save_note"), providerRequest);
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /** 确认队列拒绝不完整分类不改变持久版本，再验证等待、幂等副作用和澄清闭环。 */
    @Test
    void exercisesApprovalDenialClarificationAndQueueCas() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            call(model, "save_note", "{\"title\":\"approved\",\"body\":\"actual note\"}");
            enqueue(model, "{\"content\":\"已保存 approved\"}", "stop", 0);
            call(model, "save_note", "{\"title\":\"denied\",\"body\":\"do not write\"}");
            enqueue(model, "{\"content\":\"没有保存\"}", "stop", 0);
            call(model, "request_user_input", "{\"prompt\":\"选择风格\",\"options\":[\"简短\",\"详细\"]}");
            enqueue(model, "{\"content\":\"简短\"}", "stop", 0);
            try (ConfigurableApplicationContext application = start(postgres, model, true)) {
                JdbcTemplate jdbc = new JdbcTemplate(postgres.getPostgresDatabase());
                assertColumnType(jdbc, "ja_agent_demo_note", "created_at_ms", "bigint");
                assertConstraint(jdbc, "ja_agent_demo_note", "pk_ja_agent_demo_note");
                AgentExampleStreamingTest.TestTurn approved = base(application, "approved");
                assertEquals(202, request(approved, "submit", inputBody("submit_approved", "保存")).statusCode());
                awaitState(approved, "WAITING_APPROVAL");
                assertEquals("[]", request(approved.origin() + "/api/chat/experience/notes", "POST", notesQuery(approved)).body());

                JsonNode emptyQueue = JSON.readTree(request(approved, "queue", "{}").body());
                assertEquals(0, emptyQueue.path("revision").asLong());
                assertEquals(0, emptyQueue.path("entries").size());
                String enqueueWithoutKind = "{\"action\":\"ENQUEUE\",\"operationKey\":\"enqueue_missing_kind\","
                        + "\"inputId\":\"input_missing_kind\",\"content\":{\"text\":\"queued\","
                        + "\"skillIds\":[],\"attachmentIds\":[]}}";
                String enqueueWithBlankKind = "{\"action\":\"ENQUEUE\",\"operationKey\":\"enqueue_blank_kind\","
                        + "\"inputId\":\"input_blank_kind\",\"kind\":\" \",\"content\":{\"text\":\"queued\","
                        + "\"skillIds\":[],\"attachmentIds\":[]}}";
                String enqueueWithUnknownKind = "{\"action\":\"ENQUEUE\",\"operationKey\":\"enqueue_unknown_kind\","
                        + "\"inputId\":\"input_unknown_kind\",\"kind\":\"UNKNOWN\",\"content\":{\"text\":\"queued\","
                        + "\"skillIds\":[],\"attachmentIds\":[]}}";
                assertEquals(400, request(approved, "queue/mutate", enqueueWithoutKind).statusCode());
                assertEquals(400, request(approved, "queue/mutate", enqueueWithBlankKind).statusCode());
                assertEquals(400, request(approved, "queue/mutate", enqueueWithUnknownKind).statusCode());
                JsonNode unchangedQueue = JSON.readTree(request(approved, "queue", "{}").body());
                assertEquals(emptyQueue.path("revision").asLong(), unchangedQueue.path("revision").asLong());
                assertEquals(emptyQueue.path("entries"), unchangedQueue.path("entries"));

                String queued = "{\"action\":\"ENQUEUE\",\"operationKey\":\"enqueue_test\",\"inputId\":\"input_test\",\"kind\":\"FOLLOW_UP\",\"content\":{\"text\":\"queued\",\"skillIds\":[],\"attachmentIds\":[]}}";
                assertEquals(200, request(approved, "queue/mutate", queued).statusCode());
                JsonNode queue = JSON.readTree(request(approved, "queue", "{}").body());
                assertEquals(approved.turnId(), queue.path("turnId").asText());
                assertEquals(1, queue.path("revision").asLong());
                assertEquals(1, queue.path("entries").size());
                JsonNode queueEntry = queue.path("entries").get(0);
                assertEquals("input_test", queueEntry.path("inputId").asText());
                assertEquals("FOLLOW_UP", queueEntry.path("kind").asText());
                assertEquals("PENDING", queueEntry.path("status").asText());
                assertEquals(1, queueEntry.path("inputRevision").asLong());
                assertEquals("queued", queueEntry.path("content").path("text").asText());
                assertTrue(queueEntry.path("content").path("skillIds").isArray());
                assertTrue(queueEntry.path("content").path("attachmentIds").isArray());
                String edit = "{\"action\":\"UPDATE\",\"operationKey\":\"edit_test\",\"inputId\":\"input_test\",\"expectedInputRevision\":1,\"content\":{\"text\":\"edited\",\"skillIds\":[],\"attachmentIds\":[]}}";
                assertEquals(200, request(approved, "queue/mutate", edit).statusCode());
                JsonNode editedQueue = JSON.readTree(request(approved, "queue", "{}").body());
                assertEquals(2, editedQueue.path("revision").asLong());
                assertEquals(2, editedQueue.path("entries").get(0).path("inputRevision").asLong());
                assertEquals("edited", editedQueue.path("entries").get(0).path("content").path("text").asText());
                assertEquals(409, request(approved, "queue/mutate", edit.replace("edit_test", "edit_stale")).statusCode());
                assertEquals(200, request(approved, "queue/mutate", "{\"action\":\"DELETE\",\"operationKey\":\"delete_test\",\"inputId\":\"input_test\",\"expectedInputRevision\":2}").statusCode());
                answerWait(approved, "option_approve", "approval"); awaitState(approved, "COMPLETED");
                JsonNode notes = JSON.readTree(request(approved.origin() + "/api/chat/experience/notes", "POST", notesQuery(approved)).body());
                assertEquals(1, notes.size());
                assertTrue(jdbc.queryForObject(
                        "SELECT created_at_ms > 0 FROM ja_agent_demo_note WHERE thread_id = ?",
                        Boolean.class, approved.threadId()));
                JsonNode note = notes.get(0);
                assertEquals("approved", note.path("title").asText());
                assertEquals("actual note", note.path("body").asText());
                assertFalse(note.path("callKey").asText().isBlank());
                assertFalse(note.has("tenantId"));
                assertFalse(note.has("userId"));
                assertFalse(note.has("argumentFingerprint"));
                assertEquals(400, request(approved.origin() + "/api/chat/experience/notes", "POST",
                        "{\"threadId\":\"" + approved.threadId() + "\",\"userId\":\"user_attacker\"}").statusCode());
                assertEquals(1, JSON.readTree(request(approved.origin() + "/api/chat/experience/notes", "POST", notesQuery(approved)).body()).size());
                AgentExampleStreamingTest.TestTurn denied = base(application, "denied");
                request(denied, "submit", inputBody("submit_denied", "不要保存"));
                awaitState(denied, "WAITING_APPROVAL"); answerWait(denied, "option_deny", "approval"); awaitState(denied, "COMPLETED");
                assertEquals("[]", request(denied.origin() + "/api/chat/experience/notes", "POST", notesQuery(denied)).body());
                AgentExampleStreamingTest.TestTurn clarified = base(application, "clarified");
                request(clarified, "submit", inputBody("submit_clarified", "问我问题"));
                awaitState(clarified, "WAITING_INPUT"); answerWait(clarified, "option_0", "input"); awaitState(clarified, "COMPLETED");
                String last = "";
                for (int index = 0; index < 6; index++) {
                    last = model.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8();
                }
                assertTrue(last.contains("option_0"), last);
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /** 取消后仍需真实 JDBC 提交终态，防止工具中断标记破坏持久结算。 */
    @Test
    void cancelsWaitToolWithoutInterruptingDatabaseSettlement() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start(); call(model, "wait_seconds", "{\"seconds\":30}");
            try (ConfigurableApplicationContext application = start(postgres, model, true)) {
                AgentExampleStreamingTest.TestTurn turn = base(application, "interaction_cancel");
                request(turn, "submit", inputBody("submit_wait", "等待"));
                long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!request(turn, "events", "{}").body().contains("agent.tool.started")) {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("wait tool did not start");
                    }
                    Thread.sleep(100);
                }
                Thread.sleep(500);
                assertEquals(204, request(turn, "cancel", "{\"operationKey\":\"cancel_wait\",\"reason\":\"User cancelled.\"}").statusCode());
                awaitState(turn, "CANCELLED");
                assertTrue(request(turn, "events", "{}").body().contains("agent.execution.terminal"));
                application.getBean(io.github.kongweiguang.ja.agent.application.api.AgentEngine.class).close();
            }
        }
    }

    /** Chromium 通过真实随机端口 Spring 与受控 Provider 验收用户工作流，默认 Maven 验证不依赖浏览器环境。 */
    /**
     * 浏览器失败时保留原始页面诊断，并清理由本测试启动的 Node 后代，避免 cleanup 覆盖真正断言。
     */
    @Test
    @EnabledIfSystemProperty(named = "ja.example.browser", matches = "true")
    void exercisesReactWorkbenchInChromium() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer model = new MockWebServer()) {
            model.start();
            enqueue(model, "{\"content\":\"浏览器首轮答复\"}", "stop", 0);
            call(model, "unknown_tool", "{}");
            enqueue(model, "{\"content\":\"工具失败后已纠正\"}", "stop", 0);
            call(model, "approval_probe", "{}");
            enqueue(model, "{\"content\":\"用户拒绝，未执行\"}", "stop", 0);
            call(model, "request_user_input", "{\"prompt\":\"选择风格\",\"options\":[\"简短\",\"详细\"]}");
            enqueue(model, "{\"content\":\"已按你的选择回答\"}", "stop", 0);
            enqueue(model, "{\"content\":\"迟到的回答\"}", "stop", 5_000);
            call(model, "wait_seconds", "{\"seconds\":30}");
            try (ConfigurableApplicationContext application = start(postgres, model, true)) {
                Path root = repositoryRoot();
                Path script = root.resolve("ja-agent/example/ja-agent-example-springboot/frontend/browser-workbench.mjs");
                Path log = Files.createTempFile("ja-spring-browser-", ".log");
                boolean succeeded = false;
                try {
                    ProcessBuilder runner = new ProcessBuilder("node", script.toString());
                    runner.directory(root.toFile());
                    runner.redirectErrorStream(true);
                    runner.redirectOutput(log.toFile());
                    runner.environment().put("JA_EXAMPLE_BACKEND_URL", origin(application));
                    Process process = runner.start();
                    try (OwnedChildProcessTree ignored = new OwnedChildProcessTree(process)) {
                        if (!process.waitFor(150, TimeUnit.SECONDS)) {
                            throw new AssertionError("Chromium workbench test timed out; diagnostics: " + log);
                        }
                        String output = Files.readString(log);
                        assertEquals(0, process.exitValue(), output);
                        assertTrue(output.contains("SPRING_BROWSER_OK "), output);
                        assertEquals(9, model.getRequestCount(), "browser flows must reach the controlled Provider exactly once per model round");
                    }
                    succeeded = true;
                } finally {
                    if (succeeded) {
                        Files.deleteIfExists(log);
                    }
                }
            }
        }
    }

    /**
     * 观察 Node 的后代身份并在测试结束时只收口该进程树；轮询保留已发现后代，因为根进程退出后
     * Windows 不再可靠地通过 ProcessHandle.descendants() 枚举仍存活的 Chromium 子进程。
     */
    private static final class OwnedChildProcessTree implements AutoCloseable {
        private static final long OBSERVATION_INTERVAL_MILLIS = 10;
        private static final long CLEANUP_TIMEOUT_SECONDS = 5;

        private final Process root;
        private final ConcurrentMap<Long, ObservedChild> descendants = new ConcurrentHashMap<>();
        private final AtomicReference<RuntimeException> observationFailure = new AtomicReference<>();
        private final Thread observer;

        /**
         * 在 Node 启动时立即开始观察，并记录进程创建时间，避免清理阶段误伤复用 PID 的无关进程。
         */
        private OwnedChildProcessTree(Process root) {
            this.root = root;
            observer = Thread.ofPlatform().daemon().name("ja-example-browser-process-tree").start(this::observeWhileAlive);
        }

        /**
         * 定期快照存活根进程的后代；异步观察失败会在 close 时反馈，不能悄悄退化成只杀根进程。
         */
        private void observeWhileAlive() {
            try {
                while (root.isAlive()) {
                    captureDescendants();
                    Thread.sleep(OBSERVATION_INTERVAL_MILLIS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                observationFailure.compareAndSet(null, failure);
            }
        }

        /**
         * 只纳入当前 Node 进程的后代，并以创建时间隔离 PID 复用。枚举后立即退出的进程可能已无法提供
         * startInstant；只有重新确认其仍存活时才按缺少身份依据拒绝快照。
         */
        private void captureDescendants() {
            if (!root.isAlive()) {
                return;
            }
            root.toHandle().descendants().forEach(descendant -> {
                Optional<Instant> startedAt = descendant.info().startInstant();
                if (startedAt.isEmpty()) {
                    /*
                     * descendants() 与元数据读取之间可能恰好退出；已退出进程无需回收，直接忽略。
                     * 仍存活却无创建时间时，无法安全防止 PID 复用，必须失败并由 close 保留为 suppressed。
                     */
                    if (!descendant.isAlive()) {
                        return;
                    }
                    throw new IllegalStateException("test_child_process_start_time_unavailable");
                }
                descendants.putIfAbsent(descendant.pid(), new ObservedChild(descendant, startedAt.get()));
            });
        }

        /**
         * 清理先停止快照线程，再终止根进程和仍与快照身份匹配的后代；清理失败可由 try-with-resources
         * 抑制在原始浏览器断言之后，且失败日志保留供定位。
         */
        @Override
        public void close() throws IOException {
            observer.interrupt();
            IOException cleanupFailure = null;
            try {
                observer.join(TimeUnit.SECONDS.toMillis(CLEANUP_TIMEOUT_SECONDS));
                if (observer.isAlive()) {
                    cleanupFailure = new IOException("test_child_process_observer_did_not_stop");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                cleanupFailure = new IOException("test_child_process_observer_interrupted", interrupted);
            }
            try {
                captureDescendants();
            } catch (RuntimeException failure) {
                cleanupFailure = appendFailure(cleanupFailure, new IOException("test_child_process_snapshot_failed", failure));
            }
            if (root.isAlive()) {
                root.destroyForcibly();
            }

            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLEANUP_TIMEOUT_SECONDS);
            cleanupFailure = awaitRootExit(deadlineNanos, cleanupFailure);
            for (ObservedChild child : descendants.values()) {
                cleanupFailure = child.terminateAndAwait(deadlineNanos, cleanupFailure);
            }
            RuntimeException asynchronousFailure = observationFailure.get();
            if (asynchronousFailure != null) {
                cleanupFailure = appendFailure(cleanupFailure,
                        new IOException("test_child_process_observation_failed", asynchronousFailure));
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }

        /**
         * 等待指定测试根进程结束，使它不再创建新后代或持有重定向日志句柄。
         */
        private IOException awaitRootExit(long deadlineNanos, IOException failure) {
            if (!root.isAlive()) {
                return failure;
            }
            try {
                if (!root.waitFor(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS)) {
                    return appendFailure(failure, new IOException("test_child_process_root_did_not_exit"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return appendFailure(failure, new IOException("test_child_process_root_wait_interrupted", interrupted));
            }
            return failure;
        }

        /**
         * 将后续清理错误附加到首个错误上，避免次生错误覆盖浏览器断言或首个资源失败。
         */
        private static IOException appendFailure(IOException first, IOException next) {
            if (first == null) {
                return next;
            }
            first.addSuppressed(next);
            return first;
        }

        /**
         * 返回共享 deadline 的剩余预算，确保多个 Chromium 后代不会各自延长整个清理时限。
         */
        private static long remainingNanos(long deadlineNanos) {
            return Math.max(0, deadlineNanos - System.nanoTime());
        }

        /**
         * 保存后代句柄与创建时刻；PID 仅用于查表，不单独构成终止进程的授权。
         * @param handle 已观察到的进程句柄，用于验证存活状态并在授权范围内终止后代。
         * @param startedAt 首次观察到进程时的创建时刻，用于防止 PID 被复用后误杀无关进程。
         */
        private record ObservedChild(ProcessHandle handle,
                                     Instant startedAt) {
            /**
             * 只终止仍匹配原始创建时刻的子进程，避免其 PID 被系统复用后误杀其它进程。
             */
            private IOException terminateAndAwait(long deadlineNanos, IOException failure) {
                try {
                    if (!handle.isAlive()) {
                        return failure;
                    }
                    Optional<Instant> currentStart = handle.info().startInstant();
                    if (currentStart.isEmpty() || !startedAt.equals(currentStart.get())) {
                        return appendFailure(failure, new IOException("test_child_process_identity_changed"));
                    }
                    handle.destroyForcibly();
                    long remainingNanos = remainingNanos(deadlineNanos);
                    if (remainingNanos == 0) {
                        return appendFailure(failure, new IOException("test_child_process_cleanup_timeout"));
                    }
                    handle.onExit().toCompletableFuture().get(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return appendFailure(failure, new IOException("test_child_process_wait_interrupted", interrupted));
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException waitFailure) {
                    return appendFailure(failure, new IOException("test_child_process_did_not_exit", waitFailure));
                }
                return failure;
            }
        }
    }

    /** 从 Maven 继承的工作目录定位仓库根目录，确保 Node 能解析工作区依赖。 */
    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("ja-agent/pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Maven must run inside the Ja repository.");
        }
        return current;
    }

    /** 工具调用使用供应商原生结构化块，不在测试里直接执行工具。 */
    private static void call(MockWebServer model, String name, String arguments) throws Exception {
        enqueue(model, JSON.writeValueAsString(java.util.Map.of("tool_calls", java.util.List.of(java.util.Map.of("index", 0, "id", "call_" + name, "type", "function",
                "function", java.util.Map.of("name", name, "arguments", arguments))))), "tool_calls", 0);
    }

    /** 按最新等待记录核对类型、提交回答并显式恢复，验证实际 HTTP 的调用顺序。 */
    private static void answerWait(TestTurn turn, String optionId, String expectedKind) throws Exception {
        JsonNode events = JSON.readTree(request(turn, "events", "{}").body()).path("events"); String recordId = "";
        for (JsonNode event : events) {
            if (event.path("eventType").asText().equals("agent.wait.requested")) {
            recordId = JSON.readTree(event.path("payloadJson").asText()).path("requestRecordId").asText();
        }
        }
        JsonNode wait = JSON.readTree(request(turn, "wait", JSON.writeValueAsString(java.util.Map.of("recordId", recordId))).body());
        assertEquals(expectedKind, wait.path("kind").asText());
        String answer = JSON.writeValueAsString(java.util.Map.of("operationKey", "answer_" + turn.turnId(), "interactionId", wait.path("interactionId").asText(), "expectedInteractionRevision", wait.path("interactionRevision").asLong(),
                "answers", java.util.List.of(java.util.Map.of("questionId", wait.path("questions").get(0).path("questionId").asText(), "optionIds", java.util.List.of(optionId), "skipped", false))));
        assertEquals(204, request(turn, "answers", answer).statusCode());
        assertEquals(202, request(turn, "resume", "{}").statusCode());
    }

    /** 仅测试注入工具验证标准 Bean 扩展，生产业务能力由 ExperienceExtension 提供。 */
    private static final class ApprovalProbe implements AgentTool {
        /**
         * 无参数测试能力只验证审批，不执行外部副作用。
         */
        @Override
        public ToolSpec spec() {
            return new ToolSpec("approval_probe", "Exercise approval in tests.", JsonObjects.builder()
                    .putText("type", "object").put("properties", JsonObjects.builder().build())
                    .putBoolean("additionalProperties", false).build());
        }

        /**
         * 明确只读，保证失败或恢复不会引入业务写入。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /**
         * 结果只有审批结算后才返回，供下一次模型请求核验。
         */
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context, CancellationToken cancellation) {
            cancellation.throwIfCancellationRequested();
            return CompletableFuture.completedFuture(ExecutionResult.of(ToolResult.success("用户已确认")));
        }
    }

    /**
     * 身份在正文复用，固定用户只由服务端提供。
     *
     * @param origin 来源分类
     * @param threadId 所属对话的稳定标识
     * @param turnId 本次可恢复执行的稳定标识
     */
    private record TestTurn(String origin,
                            String threadId,
                            String turnId) {
    }
}
