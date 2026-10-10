// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.infrastructure.tool;

import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 校验 JSON Schema 适配器接受合法输入、拒绝违规值且不把秘密正文写入模型诊断。 */
final class JsonSchemaToolArgumentValidatorTest {
    private final JsonSchemaToolArgumentValidator validator =
            new JsonSchemaToolArgumentValidator(JsonValueCodecFactory.standard());

    /** 合法对象通过 Schema，防止适配器只实现失败路径。 */
    @Test
    void acceptsArgumentsMatchingRequiredAndTypedFields() {
        assertTrue(validator.invalidReason(schema(), object(Map.of("name", new JsonText("Ja")))).isEmpty());
    }

    /** 缺少必填项和错误类型都返回可供模型纠正的验证结果，不把校验异常误作执行成功。 */
    @Test
    void reportsSchemaViolationsForMissingAndWrongTypeFields() {
        assertTrue(validator.invalidReason(schema(), JsonObject.empty()).isPresent());
        assertTrue(validator.invalidReason(schema(), object(Map.of("name", new JsonText("x")))).isPresent());
        assertTrue(validator.invalidReason(schema(), object(Map.of("name",
                new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(10)))).isPresent());
    }

    /** 错误消息只含安全位置和约束信息，不能回显模型提交的机密参数值。 */
    @Test
    void sanitizesValidationDiagnostic() {
        String secret = "private-token-value";
        String message = validator.invalidReason(schema(), object(Map.of("name", new JsonText(secret))))
                .orElseThrow();

        assertFalse(message.contains(secret));
        assertTrue(message.contains("name"));
    }

    /** Schema 本身无效属于配置错误；保留定位栈帧时也不能让第三方编译错误回显 Schema 正文。 */
    @Test
    void rejectsMalformedSchemaBeforeValidatingArguments() {
        String secret = "private-token-value";
        JsonObject malformed = object(Map.of("pattern", new JsonText("[" + secret)));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> validator.invalidReason(malformed, JsonObject.empty()));
        assertEquals("Tool input schema is invalid", failure.getMessage());
        assertEquals("JSON Schema compilation failed", failure.getCause().getMessage());
        assertTrue(failure.getCause().getStackTrace().length > 0);

        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        assertFalse(stackTrace.toString().contains(secret));
    }

    /** 使用实际本地 HTTP 计数证明 $ref 不发起请求，不把网络失败误当作禁止远程加载的证据。 */
    @Test
    void rejectsRemoteReferencesWithoutMakingNetworkRequests() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"type\":\"object\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            JsonObject remote = object(Map.of("$ref", new JsonText(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/schema")));
            assertThrows(IllegalArgumentException.class, () -> validator.invalidReason(remote, JsonObject.empty()));
            org.junit.jupiter.api.Assertions.assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /** 同一 Schema 内的引用不需要外部 IO，必须保留正常的复用定义能力。 */
    @Test
    void acceptsLocalDefinitionsWithoutExternalLoading() {
        JsonObject definition = object(Map.of("type", new JsonText("object")));
        JsonObject local = object(Map.of("$defs", object(Map.of("entry", definition)),
                "$ref", new JsonText("#/$defs/entry")));
        assertTrue(validator.invalidReason(local, JsonObject.empty()).isEmpty());
    }

    /** 限定动态工具 Schema 长期变化时的缓存大小，防止进程无界保留编译图。 */
    @Test
    void boundsCompiledSchemaCache() throws Exception {
        for (int index = 0; index < 257; index++) {
            JsonObject uniqueSchema = object(Map.of("type", new JsonText("object"),
                    "title", new JsonText("tool-schema-" + index)));
            assertTrue(validator.invalidReason(uniqueSchema, JsonObject.empty()).isEmpty());
        }

        java.lang.reflect.Field cacheField = JsonSchemaToolArgumentValidator.class.getDeclaredField("schemas");
        cacheField.setAccessible(true);
        assertEquals(256, ((Map<?, ?>) cacheField.get(validator)).size());
    }

    /** 以严格 JSON 闭集建立对象，避免测试偷偷通过弱类型 Map 编码。 */
    private static JsonObject object(Map<String, ? extends io.github.kongweiguang.ja.agent.domain.json.JsonValue> values) {
        return new JsonObject(values);
    }

    /** 构造不读取远程 reference 的封闭 Schema，仅覆盖工具参数对象约束。 */
    private static JsonObject schema() {
        JsonObject name = object(Map.of("type", new JsonText("string"), "minLength",
                new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(2), "maxLength",
                new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(4)));
        JsonObject properties = object(Map.of("name", name));
        Map<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> fields = new LinkedHashMap<>();
        fields.put("type", new JsonText("object"));
        fields.put("properties", properties);
        fields.put("required", new JsonArray(List.of(new JsonText("name"))));
        fields.put("additionalProperties", new io.github.kongweiguang.ja.agent.domain.json.JsonBoolean(false));
        return new JsonObject(fields);
    }
}
