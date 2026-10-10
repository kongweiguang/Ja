// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import io.github.kongweiguang.ja.agent.store.pg.infrastructure.PostgresAgentStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import javax.sql.DataSource;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 集中提供真实 HTTP、独立 PostgreSQL 和受控 Provider 的测试夹具，避免集成用例绕过宿主边界调用 Engine 私有实现。
 */
final class EnterpriseTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String MODEL_NAME = "demo-model";

    /**
     * 先安装干净 Store schema，再用与生产相同的 Spring MVC 装配覆盖测试模型和数据库资源。
     */
    static ConfigurableApplicationContext start(DataSource dataSource, ModelPort.ModelConfiguration model) {
        return start(dataSource, model, ContextBudget.capabilities(8_192, 1_024, true));
    }

    /**
     * 允许真实 Provider 验收传入与桌面模型一致的上下文预算，受控测试仍用紧凑默认值。
     */
    static ConfigurableApplicationContext start(DataSource dataSource, ModelPort.ModelConfiguration model,
                                                ContextBudget budget) {
        return start(dataSource, model, budget, null);
    }

    /**
     * 可替换服务端 Actor 以覆盖多用户隔离；请求正文仍不参与身份构造。
     */
    static ConfigurableApplicationContext start(DataSource dataSource, ModelPort.ModelConfiguration model,
                                                ContextBudget budget, CurrentActor actor) {
        PostgresAgentStore.bootstrap(dataSource);
        return new SpringApplicationBuilder(SpringBootAgentExample.class).web(WebApplicationType.SERVLET)
                .initializers(application -> {
                    GenericApplicationContext beans = (GenericApplicationContext) application;
                    beans.registerBean("testDataSource", DataSource.class, () -> dataSource);
                    beans.registerBean("modelConfiguration", ModelPort.ModelConfiguration.class, () -> model);
                    beans.registerBean("contextBudget", ContextBudget.class, () -> budget);
                    beans.registerBean("approvalProbe", AgentTool.class, ApprovalProbe::new);
                    if (actor != null) {
                        beans.registerBean("testCurrentActorProvider", CurrentActorProvider.class,
                                () -> () -> actor);
                    }
                }).run("--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
                        "--agent.experience.enabled=true");
    }

    /**
     * 构造受控 Provider 配置，模态声明仍经独立 ModelConfiguration 门禁验证。
     */
    static ModelPort.ModelConfiguration model(ModelPort.Api api, URI baseUri,
                                              Set<ModelPort.InputModality> modalities) {
        return new ModelPort.ModelConfiguration("provider_test", "model_test", "cfg_test", api, MODEL_NAME,
                baseUri, "test-only-key", Duration.ofSeconds(5), Duration.ofSeconds(30), modalities,
                ModelPort.GenerationOptions.defaults());
    }

    /**
     * 映射随机 WebServer 端口，避免测试误连默认监听地址或端口。
     */
    static String origin(ConfigurableApplicationContext application) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
    }

    /**
     * 通过公开 Thread 创建接口固定后续操作锚点，测试不伪造持久会话 ID。
     */
    static Turn createThread(ConfigurableApplicationContext application, String operationKey) throws Exception {
        HttpResponse<String> response = request(origin(application) + "/api/chat/threads/create", "POST",
                JSON.writeValueAsString(java.util.Map.of("operationKey", operationKey)));
        org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(), response.body());
        String threadId = JSON.readTree(response.body()).path("threadId").asText();
        org.junit.jupiter.api.Assertions.assertFalse(threadId.isBlank(), response.body());
        return new Turn(origin(application), threadId, "turn_" + operationKey);
    }

    /**
     * 构造聊天和队列共同使用的结构化 Content，保留可空正文以覆盖附件单独发送。
     */
    static ObjectNode content(String text, List<String> skillIds, List<String> attachmentIds) {
        ObjectNode content = JSON.createObjectNode();
        if (text == null) {
            content.putNull("text");
        } else {
            content.put("text", text);
        }
        content.set("skillIds", JSON.valueToTree(skillIds));
        content.set("attachmentIds", JSON.valueToTree(attachmentIds));
        return content;
    }

    /**
     * 暴露统一 Content 值给 HTTP body builder，减少各集成测试对字段拼接顺序的依赖。
     */
    static ObjectNode input(String text, List<String> skillIds, List<String> attachmentIds) {
        return content(text, skillIds, attachmentIds);
    }

    /**
     * 为 Turn 路由添加完整执行身份；正文结构仍由调用方按正式 DTO 提供。
     */
    static HttpRequest turnRequest(Turn turn, String action, String body) {
        try {
            ObjectNode payload = (ObjectNode) JSON.readTree(body);
            payload.put("threadId", turn.threadId()).put("turnId", turn.turnId());
            return jsonRequest(turn.origin() + "/api/chat/" + action, "POST", JSON.writeValueAsString(payload));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid test request JSON", failure);
        }
    }

    /**
     * 通过标准 HttpClient 发出 Turn 请求，使 SSE 和普通 JSON 均经过真实 MVC 转换。
     */
    static HttpResponse<String> postTurn(Turn turn, String action, String body) throws Exception {
        return HTTP.send(turnRequest(turn, action, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 统一以有界 HTTP 超时读取公开接口，避免测试等待掩盖服务端未收口。
     */
    static HttpResponse<String> request(String url, String method, String body) throws Exception {
        return HTTP.send(jsonRequest(url, method, body), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 将结构化值编码后请求相对 API 路径，供独立应用进程和测试上下文共用。
     */
    static HttpResponse<String> post(String origin, String path, Object body) throws Exception {
        String json = body == null ? null : body instanceof String text ? text : JSON.writeValueAsString(body);
        return request(origin + path, "POST", json);
    }

    /**
     * 创建 JSON 请求并显式区分无正文命令，避免错误编码影响路由验证。
     */
    static HttpRequest jsonRequest(String url, String method, String body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body)).build();
    }

    /**
     * 上传单个文件并将 Thread、稳定操作键和文件放在同一 multipart 请求中。
     */
    static HttpResponse<String> upload(Turn turn, String operationKey, String fileName,
                                       String mediaType, byte[] bytes) throws Exception {
        return upload(turn.origin(), turn.threadId(), operationKey, fileName, mediaType, bytes);
    }

    /**
     * 使用 UTF-8 multipart 元数据和原始文件字节上传，供打包进程及上下文测试共用。
     */
    static HttpResponse<String> upload(String origin, String threadId, String operationKey, String fileName,
                                       String mediaType, byte[] bytes) throws Exception {
        String boundary = "ja-test-" + java.util.UUID.randomUUID();
        byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"threadId\"\r\n\r\n"
                + threadId + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"operationKey\"\r\n\r\n" + operationKey
                + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + fileName + "\"\r\nContent-Type: " + mediaType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(header),
                HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(suffix));
        HttpRequest request = HttpRequest.newBuilder(URI.create(origin + "/api/chat/attachments/upload"))
                .timeout(Duration.ofSeconds(45)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(body).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 核验上传响应返回实际受管附件身份与原始大小，避免后续测试使用虚构 ID。
     */
    static JsonNode uploadMetadata(Turn turn, String key, String name, String mime, byte[] bytes) throws Exception {
        HttpResponse<String> response = upload(turn, key, name, mime, bytes);
        org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(), response.body());
        JsonNode metadata = JSON.readTree(response.body());
        org.junit.jupiter.api.Assertions.assertTrue(metadata.path("id").asText().startsWith("att_"), response.body());
        org.junit.jupiter.api.Assertions.assertEquals(name, metadata.path("fileName").asText());
        org.junit.jupiter.api.Assertions.assertEquals(bytes.length, metadata.path("sizeBytes").asLong());
        return metadata;
    }

    /**
     * 显式关闭 Engine 调度资源，使共享 Spring 上下文收尾可在每个集成用例内重复执行。
     */
    static void closeEngine(ConfigurableApplicationContext application) {
        application.getBean(AgentEngine.class).close();
    }

    /**
     * 生成含高对比可读编号的真实 PNG，让受控与真实模型都能依据像素内容作答。
     */
    static byte[] png() throws Exception {
        return png("CASE ID: JA-7319");
    }

    /**
     * 生成不同但有效的 PNG 内容，用于验证幂等键冲突而不混入文件格式校验失败。
     */
    static byte[] png(String label) throws Exception {
        BufferedImage image = new BufferedImage(900, 260, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 58));
            graphics.drawString(label, 36, 145);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        org.junit.jupiter.api.Assertions.assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }

    /**
     * 生成带一页可复制文本的最小 PDF，使用精确 xref 字节偏移确保 Provider 收到可解析文档。
     */
    static byte[] pdf(String text) throws Exception {
        String safeText = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String stream = "BT /F1 24 Tf 72 700 Td (" + safeText + ") Tj ET";
        List<String> objects = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "\nendstream");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        List<Integer> offsets = new ArrayList<>();
        for (int index = 0; index < objects.size(); index++) {
            offsets.add(output.size());
            output.write((index + 1 + " 0 obj\n" + objects.get(index) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = output.size();
        output.write(("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) {
            output.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    /**
     * 排队至公开状态终结；FAILED 立即以真实事件作为断言上下文，而不是空轮询超时。
     */
    static JsonNode awaitState(Turn turn, String expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String actual = "";
        JsonNode status = JSON.createObjectNode();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = postTurn(turn, "status", "{}");
            status = JSON.readTree(response.body());
            actual = status.path("state").asText();
            if (expected.equals(actual)) {
                return status;
            }
            if (Set.of("FAILED", "CANCELLED").contains(actual)) {
                break;
            }
            Thread.sleep(40);
        }
        HttpResponse<String> events = postTurn(turn, "events", "{}");
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual,
                events.statusCode() + " " + events.body());
        return status;
    }

    /**
     * 生成 Responses 原生 SSE 文本结局，最终输出 item 与流式 delta 使用同一正文。
     */
    static void enqueueResponsesText(MockWebServer server, String text) {
        String escaped = JSON.valueToTree(text).toString();
        String output = "[{\"id\":\"message_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":"
                + escaped + ",\"annotations\":[],\"logprobs\":[]}]}]";
        String inProgress = responseEnvelope("resp_test", "in_progress", "[]", false);
        String completed = responseEnvelope("resp_test", "completed", output, true);
        String body = "event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                + inProgress + "}\n\n"
                + "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,"
                + "\"output_index\":0,\"item_id\":\"message_1\",\"content_index\":0,\"delta\":" + escaped + "}\n\n"
                + "event: response.output_text.done\ndata: {\"type\":\"response.output_text.done\",\"sequence_number\":2,"
                + "\"output_index\":0,\"item_id\":\"message_1\",\"content_index\":0,\"text\":" + escaped + "}\n\n"
                + "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"sequence_number\":3,"
                + "\"output_index\":0,\"item\":" + output.substring(1, output.length() - 1) + "}\n\n"
                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":4,\"response\":"
                + completed + "}\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body));
    }

    /**
     * 构造 Responses 终态外壳并可按事件阶段附加真实 Token 用量。
     */
    private static String responseEnvelope(String id, String status, String output, boolean usage) {
        return "{\"id\":\"" + id + "\",\"created_at\":1,\"model\":\"" + MODEL_NAME
                + "\",\"object\":\"response\",\"output\":" + output + ",\"parallel_tool_calls\":true,"
                + "\"tool_choice\":\"auto\",\"tools\":[],\"status\":\"" + status + "\""
                + (usage ? ",\"usage\":{\"input_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":0,\"cache_write_tokens\":0},"
                + "\"output_tokens\":4,\"output_tokens_details\":{\"reasoning_tokens\":0},\"total_tokens\":14}" : "") + "}";
    }

    /**
     * 构造 Responses 原生函数调用终态，以真实协议进入企业审批等待而非直接调用 Middleware。
     */
    static void enqueueResponsesTool(MockWebServer server, String callId, String tool, String arguments) {
        String argumentsJson = JSON.valueToTree(arguments).toString();
        String item = "{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"" + callId
                + "\",\"name\":" + JSON.valueToTree(tool) + ",\"arguments\":" + argumentsJson + "}";
        String added = "{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"" + callId
                + "\",\"name\":" + JSON.valueToTree(tool) + ",\"arguments\":\"\"}";
        String inProgress = responseEnvelope("resp_test", "in_progress", "[]", false);
        String completed = responseEnvelope("resp_test", "completed", "[" + item + "]", true);
        String body = "event: response.created\ndata: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":"
                + inProgress + "}\n\n"
                + "event: response.output_item.added\ndata: {\"type\":\"response.output_item.added\",\"sequence_number\":1,"
                + "\"output_index\":0,\"item\":" + added + "}\n\n"
                + "event: response.function_call_arguments.delta\ndata: {\"type\":\"response.function_call_arguments.delta\","
                + "\"sequence_number\":2,\"output_index\":0,\"item_id\":\"item_1\",\"delta\":" + argumentsJson + "}\n\n"
                + "event: response.function_call_arguments.done\ndata: {\"type\":\"response.function_call_arguments.done\","
                + "\"sequence_number\":3,\"output_index\":0,\"item_id\":\"item_1\",\"arguments\":" + argumentsJson + "}\n\n"
                + "event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"sequence_number\":4,"
                + "\"output_index\":0,\"item\":" + item + "}\n\n"
                + "event: response.completed\ndata: {\"type\":\"response.completed\",\"sequence_number\":5,\"response\":"
                + completed + "}\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body));
    }

    /**
     * 生成 Anthropic Messages SSE 终态，使同一上传链路覆盖另一套原生媒体编码。
     */
    static void enqueueAnthropicText(MockWebServer server, String text) {
        String escaped = JSON.valueToTree(text).toString();
        String body = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\","
                + "\"type\":\"message\",\"role\":\"assistant\",\"content\":[],\"model\":\"claude-test\","
                + "\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":"
                + escaped + "}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":4}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body));
    }

    /**
     * 从受控 Provider 收到的完整 JSON 请求中查找匹配数据 URI，并逐字节确认附件没有退化为引用文字。
     */
    static void assertProviderContainsBytes(RecordedRequest request, byte[] expected, String mediaType) throws Exception {
        assertNotNull(request, "provider request was not received");
        assertProviderContainsBytes(JSON.readTree(request.getBody().readUtf8()), expected, mediaType);
    }

    /**
     * 复用单次解析后的 Provider 请求检查多份附件，避免重复读取 MockWebServer 的消耗型 body。
     */
    static void assertProviderContainsBytes(JsonNode payload, byte[] expected, String mediaType) {
        List<String> values = new ArrayList<>();
        collectText(payload, values);
        String encoded = Base64.getEncoder().encodeToString(expected);
        org.junit.jupiter.api.Assertions.assertTrue(values.stream().anyMatch(value -> value.contains(mediaType)),
                "provider request must declare the attachment media type");
        for (String value : values) {
            int start = value.indexOf(encoded);
            if (start >= 0) {
                byte[] actual = Base64.getDecoder().decode(value.substring(start, start + encoded.length()));
                assertArrayEquals(expected, actual, "provider must receive exact attachment bytes");
                return;
            }
        }
        org.junit.jupiter.api.Assertions.fail("controlled provider request did not contain the expected native attachment");
    }

    /**
     * 递归遍历 JSON 标量而不依赖 Provider 的不同嵌套路径，保持字节验证对协议字段改名敏感度适中。
     */
    private static void collectText(JsonNode node, List<String> values) {
        if (node.isTextual()) {
            values.add(node.asText());
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectText(child, values));
        }
    }

    /**
     * 对受控 Chat 协议生成单次 Tool 调用帧，参数保持原样供 Middleware 实际处理。
     */
    static void enqueueChatTool(MockWebServer server, String callId, String tool, String arguments) {
        String body = "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"model\":\""
                + MODEL_NAME + "\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                + "\"id\":\"" + callId + "\",\"type\":\"function\",\"function\":{\"name\":\"" + tool
                + "\",\"arguments\":" + JSON.valueToTree(arguments) + "}}]},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"model\":\""
                + MODEL_NAME + "\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"model\":\""
                + MODEL_NAME + "\",\"choices\":[],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5,\"total_tokens\":25}}\n\n"
                + "data: [DONE]\n\n";
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body));
    }

    /**
     * 提供受控 approval_probe 工具用于现有等待和取消测试，副作用只在测试计数器中体现。
     */
    private static final class ApprovalProbe implements AgentTool {
        private final ToolSpec spec = new ToolSpec("approval_probe", "Exercise approval in tests.",
                JsonObjects.builder().putText("type", "object").putBoolean("additionalProperties", false).build());

        /**
         * 返回稳定 Tool 描述，让真实 Core 决定参数绑定、等待与恢复。
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 完成无外部写入的探针执行，测试可验证审批后才越过 Core 执行边界。
         */
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context,
                                                         CancellationToken cancellation) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    ExecutionResult.of(AgentTool.ToolResult.success("approved test action")));
        }
    }

    /**
     * 保持 Thread 和 Turn 以一个不可变值传递，避免测试请求跨身份混用。
     *
     * @param origin 当前测试应用的 HTTP 地址
     * @param threadId 测试会话的稳定标识
     * @param turnId 测试执行的稳定标识
     */
    record Turn(String origin,
                String threadId,
                String turnId) {
    }
}
