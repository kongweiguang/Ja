// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.InputFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** 以冻结语料证明 Java 仅接受 JA-RPC v2，并拒绝旧协议及越界配置样例。 */
final class GoldenCorpusTest {
    /** 遍历全部正向帧，防止协议主版本或配置事件边界在局部测试之外发生漂移。 */
    @Test
    void consumesEveryV2PositiveFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        int frames = 0;
        boolean sawV2 = false;
        boolean sawConfigChanged = false;
        for (Path file : corpusFiles(false)) {
            List<byte[]> documents = documents(file);
            for (int index = 0; index < documents.size(); index++) {
                byte[] bytes = documents.get(index);
                JaRpcCodec.Frame frame;
                try {
                    frame = codec.decode(bytes);
                } catch (JaRpcException failure) {
                    throw new AssertionError("positive frame rejected: " + file + ":" + (index + 1), failure);
                }
                if (frame instanceof JaRpcCodec.Request request && "runtime/initialize".equals(request.method())) {
                    sawV2 = request.params().path("protocolMajor").intValue() == 2
                            && !request.params().has("configSnapshot");
                }
                if (frame instanceof JaRpcCodec.Notification notification
                        && "configuration/changed".equals(notification.method())) sawConfigChanged = true;
                frames++;
            }
        }
        assertTrue(frames > 0 && sawV2 && sawConfigChanged);
    }

    /**
     * 在正确责任边界拒绝全部反向帧：Codec 负责信封结构，冻结的 v2 Schema
     * 及 Secret、cwd 规则负责方法和通知形态，避免生产解码器重复实现合同。
     */
    @Test
    void rejectsEveryV2NegativeFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        Schema schema = v2Schema(codec);
        int frames = 0;
        for (Path file : corpusFiles(true)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                assertNegativeFrame(codec, schema, line.getBytes(StandardCharsets.UTF_8), file, index + 1);
                frames++;
            }
        }
        assertTrue(frames > 0);
    }

    /**
     * 仅在测试中加载仓库的 draft-2020-12 合同，使生产解码保持专注于有界
     * JSON-RPC 分帧，不在运行时重复维护每个方法的 Schema。
     */
    private static Schema v2Schema(JaRpcCodec codec) throws IOException {
        Path schemaPath = goldenRoot().resolve("..").resolve("ja-rpc").resolve("v2").resolve("schema")
                .resolve("ja-rpc-v2.schema.json").normalize();
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaCacheEnabled(true)).getSchema(
                        Files.readString(schemaPath, StandardCharsets.UTF_8));
    }

    /**
     * 分离信封拒绝与合同拒绝，并只保留反向夹具位置；请求值可能含 Secret，
     * 因此失败信息不得回显原始内容。
     */
    private static void assertNegativeFrame(JaRpcCodec codec, Schema schema, byte[] bytes,
                                            Path file, int lineNumber) throws IOException {
        try {
            codec.decode(bytes);
        } catch (JaRpcException expected) {
            return;
        }
        JsonNode document = codec.mapper().readTree(bytes);
        boolean schemaRejected = !schema.validate(document.toString(), InputFormat.JSON).isEmpty();
        boolean semanticRejected = violatesSemanticBoundary(document);
        boolean pendingRejected = isUnroutableResponse(document);
        assertFalse(!schemaRejected && !semanticRejected && !pendingRejected,
                () -> "negative frame accepted: " + file + ":" + lineNumber);
    }

    /**
     * Host 响应在语法上属于合法 JSON-RPC，但只有其 h: id 存在于连接级反向等待表时
     * 才满足状态约束；该状态校验由传输层测试负责，此处只识别不可路由样例。
     */
    private static boolean isUnroutableResponse(JsonNode document) {
        if (!(document instanceof ObjectNode object)) return false;
        String id = object.path("id").textValue();
        return id != null && (id.startsWith("h:") || id.startsWith("c:"))
                && (object.has("result") || object.has("error"));
    }

    /**
     * 镜像刻意位于 JSON Schema 之外的合同约束，重点固定禁止 Secret 泄露和禁止客户端
     * 覆盖 Turn 配置的规则，确保 RPC 边界始终失败关闭。
     */
    private static boolean violatesSemanticBoundary(JsonNode document) {
        if (!(document instanceof ObjectNode object)) return true;
        String method = object.path("method").textValue();
        JsonNode params = object.get("params");
        boolean credentialWrite = "credential/set".equals(method);
        if (containsSecret(params, credentialWrite)) return true;
        if ("configuration/patch".equals(method) && params instanceof ObjectNode config) {
            String path = config.path("path").asText("").toLowerCase(java.util.Locale.ROOT);
            if (path.contains("secret") || path.contains("token") || path.contains("password")
                    || path.contains("authorization") || path.contains("api_key") || path.contains("apikey")) {
                return true;
            }
        }
        if ("runtime/initialize".equals(method)
                && (object.path("params").path("protocolMajor").intValue() != 2
                || object.path("params").has("configSnapshot"))) return true;
        if ("turn/start".equals(method) && params instanceof ObjectNode turn) {
            if (turn.has("cwd") || turn.has("profileId") || turn.has("configRevision")) return true;
            JsonNode content = turn.get("content");
            if (content == null || !content.isArray()) return true;
            Set<String> attachmentIds = new HashSet<>();
            int attachmentCount = 0;
            for (JsonNode item : content) {
                if ("attachment".equals(item.path("type").textValue())) {
                    attachmentCount++;
                    if (!attachmentIds.add(item.path("attachmentId").textValue())) return true;
                }
            }
            if (attachmentCount > 10) return true;
        }
        if ("configuration/changed".equals(method) && params instanceof ObjectNode changed) {
            if (changed.has("cwd")) return true;
            if ("user".equals(changed.path("scope").textValue()) && changed.has("workspaceId")) return true;
            if ("project".equals(changed.path("scope").textValue()) && !changed.has("workspaceId")) return true;
        }
        if ("context/compacted".equals(method) && params instanceof ObjectNode compacted) {
            return compacted.path("inputTokensAfter").longValue()
                    >= compacted.path("inputTokensBefore").longValue();
        }
        return false;
    }

    /** 递归识别 Secret 形态字段，仅允许 credential/set.secret 这一明确写入边界。 */
    private static boolean containsSecret(JsonNode value, boolean credentialWrite) {
        if (value instanceof ObjectNode object) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = object.properties().iterator();
            while (fields.hasNext()) {
                java.util.Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if ((key.equals("secret") && !credentialWrite) || Set.of(
                        "secretvalue", "credentialvalue", "token", "tokenvalue", "password",
                        "authorization", "apikey", "api_key").contains(key)) return true;
                if (containsSecret(entry.getValue(), credentialWrite)) return true;
            }
        } else if (value != null && value.isArray()) {
            for (JsonNode child : value) if (containsSecret(child, credentialWrite)) return true;
        }
        return false;
    }

    /** 使用与历史协议无关的未知通知证明生产 Codec 采用严格白名单，避免重新引入别名特例。 */
    @Test
    void rejectsUnknownNotification() {
        JaRpcCodec codec = new JaRpcCodec();
        assertThrows(JaRpcException.class, () -> codec.decode(
                "{\"jsonrpc\":\"2.0\",\"method\":\"unknown/notification\",\"params\":{}}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** 只定位 v2 语料，避免已退役的 v1 夹具意外进入当前合同验证。 */
    private static List<Path> corpusFiles(boolean invalid) throws IOException {
        Path root = goldenRoot().resolve("v2");
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json") || path.toString().endsWith(".jsonl"))
                    .filter(path -> path.toString().contains("invalid") == invalid)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
    }

    /** 分割 JSONL 时保持原始 UTF-8 字节语义，让严格编码校验仍由生产 Codec 负责。 */
    private static List<byte[]> documents(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(line -> !line.isBlank())
                .map(line -> line.getBytes(StandardCharsets.UTF_8)).toList();
    }

    /** 同时支持 Maven 模块目录和工作区根目录，避免测试启动位置改变合同来源。 */
    private static Path goldenRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("golden");
            if (Files.isDirectory(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("golden corpus is unavailable");
    }
}
