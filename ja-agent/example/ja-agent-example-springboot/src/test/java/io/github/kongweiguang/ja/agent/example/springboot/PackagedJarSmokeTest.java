// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.sql.Connection;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.HTTP;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.JSON;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.Turn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.assertProviderContainsBytes;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.awaitState;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.content;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.enqueueResponsesText;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.png;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.post;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.postTurn;
import static io.github.kongweiguang.ja.agent.example.springboot.EnterpriseTestSupport.upload;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** 实际执行待交付 JAR 验证同源静态资源和资料 API，不能用测试上下文代替产物验收。 */
@EnabledIfSystemProperty(named = "ja.example.jar", matches = ".+")
class PackagedJarSmokeTest {
    /** 全新 PostgreSQL 与受控模型贯通打包后的页面、上传、纯附件提交、历史和原始内容回读。 */
    @Test
    void servesTheBuiltPageAndNativeAttachmentConversation() throws Exception {
        Path jar = Path.of(System.getProperty("ja.example.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "packaged executable JAR must exist");
        Path log = Files.createTempFile("ja-enterprise-jar-", ".log");
        try (EmbeddedPostgres postgres = EmbeddedPostgres.start(); MockWebServer provider = new MockWebServer()) {
            provider.start();
            enqueueResponsesText(provider, "已读取测试资料，资料事实已确认。");
            String jdbcUrl;
            try (Connection connection = postgres.getPostgresDatabase().getConnection()) {
                jdbcUrl = connection.getMetaData().getURL();
            }
            int port = freePort();
            String origin = "http://127.0.0.1:" + port;
            String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
            ProcessBuilder runner = new ProcessBuilder(java, "-jar", jar.toString());
            runner.redirectErrorStream(true).redirectOutput(log.toFile());
            Map<String, String> environment = runner.environment();
            environment.put("JA_EXAMPLE_PORT", Integer.toString(port));
            environment.put("JA_EXAMPLE_JDBC_URL", jdbcUrl);
            environment.put("JA_EXAMPLE_DB_USER", "postgres");
            environment.put("JA_EXAMPLE_DB_PASSWORD", "");
            environment.put("JA_EXAMPLE_PROVIDER_ID", "packaged_test_provider");
            environment.put("JA_EXAMPLE_MODEL_ID", "packaged_test_model");
            environment.put("JA_EXAMPLE_MODEL", "demo-model");
            environment.put("JA_EXAMPLE_MODEL_API", "OPENAI_RESPONSES");
            environment.put("JA_EXAMPLE_MODEL_BASE_URI", provider.url("/v1").toString());
            environment.put("JA_EXAMPLE_MODEL_API_KEY", "test-only-key");
            environment.put("JA_EXAMPLE_MODEL_INPUT_MODALITIES", "TEXT,IMAGE,PDF");
            environment.put("JA_EXAMPLE_REASONING_LEVEL", "");
            environment.put("JA_EXAMPLE_MAX_OUTPUT_TOKENS", "1024");
            Process process = runner.start();
            try {
                awaitReady(origin, process, log);
                HttpResponse<String> page = HTTP.send(HttpRequest.newBuilder(URI.create(origin)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, page.statusCode());
                assertTrue(page.body().contains("id=\"root\""), "packaged page must contain the actual React entry");
                Matcher script = Pattern.compile("src=\"([^\"]+\\.js)\"").matcher(page.body());
                assertTrue(script.find(), "built page must reference packaged JavaScript");
                HttpResponse<String> asset = HTTP.send(
                        HttpRequest.newBuilder(URI.create(origin).resolve(script.group(1))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, asset.statusCode());
                assertTrue(asset.body().length() > 1000, "built application script must be served");
                JsonNode model = JSON.readTree(post(origin, "/api/chat/model", Map.of()).body());
                assertEquals("demo-model", model.path("model").asText());
                assertFalse(model.path("attachmentCapabilities").path("effectiveFormats").isEmpty());
                HttpResponse<String> created = post(origin, "/api/chat/threads/create",
                        Map.of("operationKey", "packaged_create"));
                assertEquals(200, created.statusCode());
                String threadId = JSON.readTree(created.body()).path("threadId").asText();
                Turn turn = new Turn(origin, threadId, "packaged_turn");
                byte[] bytes = png();
                HttpResponse<String> uploaded = upload(turn, "packaged_upload", "packaged.png", "image/png", bytes);
                assertEquals(200, uploaded.statusCode(), uploaded.body());
                String id = JSON.readTree(uploaded.body()).path("id").asText();
                assertFalse(id.isBlank());
                HttpResponse<String> accepted = postTurn(turn, "submit",
                        JSON.writeValueAsString(Map.of("operationKey", "packaged_submit",
                                "content", content(null, List.of(), List.of(id)))));
                assertEquals(202, accepted.statusCode(), accepted.body());
                awaitState(turn, "COMPLETED");
                assertProviderContainsBytes(provider.takeRequest(5, TimeUnit.SECONDS), bytes, "image/png");
                JsonNode history = JSON.readTree(post(origin, "/api/chat/threads/history",
                        Map.of("threadId", threadId, "limit", 256)).body());
                boolean found = false;
                for (JsonNode item : history.path("items")) {
                    if ("user".equals(item.path("kind").asText())) {
                        assertEquals(id, item.path("content").path("attachmentIds").get(0).asText());
                        found = true;
                    }
                }
                assertTrue(found, "built API must retain admitted attachment history");
                HttpResponse<byte[]> download = HTTP.send(HttpRequest.newBuilder(URI.create(origin
                        + "/api/chat/attachments/" + id + "/content?threadId=" + threadId)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, download.statusCode());
                assertArrayEquals(bytes, download.body());
            } finally {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "owned test JAR process must stop");
                }
            }
        }
    }

    /** 仅为测试释放短暂保留的 loopback 端口，避免借用开发环境默认端口或真实应用监听器。 */
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** 启动以实际 API 可达为准；退出或超时保留非秘密受控测试日志帮助定位产物问题。 */
    private static void awaitReady(String origin, Process process, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                fail("test JAR exited before readiness; log=" + log);
            }
            try {
                if (post(origin, "/api/chat/model", Map.of()).statusCode() == 200) {
                    return;
                }
            } catch (java.io.IOException unavailable) {
                // 本地启动窗口允许连接拒绝；测试从未对真实 Provider 重试。
            }
            Thread.sleep(100);
        }
        fail("test JAR did not become ready; log=" + log);
    }
}
