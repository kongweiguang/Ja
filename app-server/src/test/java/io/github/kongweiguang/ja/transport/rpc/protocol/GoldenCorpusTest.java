// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.transport.rpc.handler.HandshakeContractTestAccess;
import com.networknt.schema.Schema;
import io.github.kongweiguang.ja.conversation.domain.UserContent;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.conversation.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.conversation.domain.model.TextContent;
import io.github.kongweiguang.ja.conversation.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.conversation.domain.model.WorkspaceReferenceContent;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.InputFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/** 以冻结语料证明 Java 仅接受 JA-RPC v1，并拒绝旧协议及越界配置样例。 */
final class GoldenCorpusTest {
    private static final Map<String, String> RESULT_DEFINITIONS = Map.ofEntries(
            Map.entry("workspace/list", "workspacePageResult"),
            Map.entry("workspace/path/search", "workspacePathSearchResult"),
            Map.entry("thread/create", "threadResult"),
            Map.entry("thread/list", "threadListResult"),
            Map.entry("thread/search", "threadPageResult"),
            Map.entry("thread/read", "threadReadResult"),
            Map.entry("thread/rename", "threadResult"),
            Map.entry("thread/preferences/update", "threadResult"),
            Map.entry("thread/pin", "threadResult"),
            Map.entry("thread/seen", "threadResult"),
            Map.entry("thread/archive", "threadResult"),
            Map.entry("thread/restore", "threadResult"),
            Map.entry("goal/read", "goalProjectionResult"),
            Map.entry("goal/events/read", "goalEventsResult"),
            Map.entry("goal/observe", "goalObserveResult"),
            Map.entry("goal/unobserve", "taskAcceptedResult"),
            Map.entry("plan/read", "planProjection"),
            Map.entry("plan/revisions/list", "planRevisionsResult"),
            Map.entry("goal/evidence/list", "goalEvidenceResult"),
            Map.entry("goal/create", "goalProjectionResult"),
            Map.entry("goal/plan/attach", "goalProjectionResult"),
            Map.entry("goal/plan/detach", "goalProjectionResult"),
            Map.entry("goal/pause", "goalProjectionResult"),
            Map.entry("goal/resume", "goalProjectionResult"),
            Map.entry("goal/stop", "goalProjectionResult"),
            Map.entry("plan/create", "planProjection"),
            Map.entry("plan/current/read", "planCurrentReadResult"),
            Map.entry("plan/observe", "planObserveResult"),
            Map.entry("plan/unobserve", "interactionAcceptedResult"),
            Map.entry("plan/events/read", "planEventsResult"),
            Map.entry("plan/evidence/list", "planEvidenceResult"),
            Map.entry("plan/pause", "planProjection"),
            Map.entry("plan/resume", "planProjection"),
            Map.entry("plan/stop", "planProjection"),
            Map.entry("interaction/read", "interactionSnapshot"),
            Map.entry("interaction/observe", "interactionObserveResult"),
            Map.entry("interaction/unobserve", "interactionAcceptedResult"),
            Map.entry("interaction/draft/save", "interactionSnapshot"),
            Map.entry("interaction/respond", "interactionSnapshot"),
            Map.entry("interaction/cancel", "interactionSnapshot"),
            Map.entry("plan/draft/save", "planProjection"),
            Map.entry("plan/draft/discard", "planProjection"),
            Map.entry("plan/propose", "planProjection"),
            Map.entry("plan/execute", "planProjection"),
            Map.entry("plan/reject", "planProjection"),
            Map.entry("task/create", "taskCreateResult"),
            Map.entry("task/list", "taskListResult"),
            Map.entry("task/read", "taskReadResult"),
            Map.entry("task/observe", "taskObserveResult"),
            Map.entry("task/unobserve", "taskAcceptedResult"),
            Map.entry("task/seen", "taskMutationResult"),
            Map.entry("thread/message/send", "taskMessageResult"),
            Map.entry("task/followup", "taskFollowupResult"),
            Map.entry("task/cancel", "taskMutationResult"),
            Map.entry("task/tree/delete", "taskTreeDeleteResult"),
            Map.entry("task/close", "taskCloseResult"),
            Map.entry("skill/list", "skillPageResult"),
            Map.entry("mcp/list", "mcpPageResult"),
            Map.entry("mcp/test", "mcpTestResult"),
            Map.entry("mcp/list-tools", "mcpToolsResult"),
            Map.entry("workspace/open-general", "workspaceResult"),
            Map.entry("thread/compact", "threadCompactResult"),
            Map.entry("attachment/import", "attachmentResult"),
            Map.entry("attachment/discard", "attachmentResult"),
            Map.entry("turn/start", "turnAcceptedResult"),
            Map.entry("turn/resume", "turnResumeResult"),
            Map.entry("turn/cancel", "turnCancelResult"),
            Map.entry("turn/input/enqueue", "turnInputMutationResult"),
            Map.entry("turn/input/prioritize", "turnInputMutationResult"),
            Map.entry("turn/input/update", "turnInputMutationResult"),
            Map.entry("turn/input/delete", "turnInputMutationResult"),
            Map.entry("turn/change-set/read", "changeSetArtifactReadResult"),
            Map.entry("configuration/read", "configReadResult"),
            Map.entry("configuration/patch", "configMutationResult"),
            Map.entry("configuration/replace", "configMutationResult"),
            Map.entry("configuration/reset", "configMutationResult"),
            Map.entry("credential/set", "credentialMutationResult"),
            Map.entry("credential/delete", "credentialMutationResult"));

    /** 遍历全部正向帧，防止协议主版本或配置事件边界在局部测试之外发生漂移。 */
    @Test
    void consumesEveryV1PositiveFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        ObjectMapper mapper = new ObjectMapper();
        int frames = 0;
        boolean sawV1 = false;
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
                    sawV1 = request.params().path("protocolMajor").intValue() == 1
                            && !request.params().has("configSnapshot");
                    assertEquals(HandshakeContractTestAccess.capabilities(mapper),
                            request.params().path("capabilities"),
                            "Java runtime handshake must match the frozen client capability set");
                }
                if (frame instanceof JaRpcCodec.Notification notification
                        && "configuration/changed".equals(notification.method())) sawConfigChanged = true;
                frames++;
            }
        }
        assertTrue(frames > 0 && sawV1 && sawConfigChanged);
    }

    /**
     * 在正确责任边界拒绝全部反向帧：Codec 负责信封结构，冻结的 v1 Schema
     * 及 Secret、cwd 规则负责方法和通知形态，避免生产解码器重复实现合同。
     */
    @Test
    void rejectsEveryV1NegativeFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        ContractSchemas schemas = contractSchemas(codec);
        int frames = 0;
        for (Path file : corpusFiles(true)) {
            boolean correlated = file.toString().contains(
                    Path.of("invalid", "correlated").toString());
            Map<String, String> pending = new HashMap<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                JsonNode document = codec.mapper().readTree(bytes);
                if (correlated && document.has("method") && document.has("id")) {
                    registerCorrelatedRequest(codec, schemas.envelope(), pending,
                            bytes, document, file, index + 1);
                    continue;
                }
                if (correlated && document.has("id")) {
                    assertNegativeCorrelatedResponse(codec, schemas, pending,
                            bytes, document, file, index + 1);
                } else {
                    assertNegativeFrame(codec, schemas.envelope(), bytes, file, index + 1);
                }
                frames++;
            }
            assertTrue(pending.isEmpty(), () -> "correlated negative response is missing: " + file);
        }
        assertTrue(frames > 0);
    }

    /** 消费事件必须原子保留队列摘要，不能让同一附件 ID 在 Timeline 中静默换名或换类型。 */
    @Test
    void rejectsConsumedAttachmentSummaryDrift() throws IOException {
        JsonNode document = new ObjectMapper().readTree("""
                {
                  "jsonrpc":"2.0",
                  "method":"turn/input-consumed",
                  "params":{
                    "turnId":"turn_demo",
                    "input":{
                      "turnId":"turn_demo",
                      "content":[{"type":"attachment","attachmentId":"att_capture"}],
                      "attachments":[{
                        "attachmentId":"att_capture","displayName":"capture.png",
                        "sizeBytes":128,"mediaKind":"image","mediaType":"image/png"
                      }]
                    },
                    "userItem":{
                      "turnId":"turn_demo",
                      "content":[{"type":"attachment","attachmentId":"att_capture"}],
                      "attachments":[{
                        "attachmentId":"att_capture","displayName":"other.png",
                        "sizeBytes":128,"mediaKind":"image","mediaType":"image/png"
                      }]
                    }
                  }
                }
                """);

        assertTrue(violatesSemanticBoundary(document));
    }

    /**
     * 仅在测试中加载仓库的 draft-2020-12 合同，使生产解码保持专注于有界
     * JSON-RPC 分帧，不在运行时重复维护每个方法的 Schema。
     */
    private static ContractSchemas contractSchemas(JaRpcCodec codec) throws IOException {
        Path schemaPath = goldenRoot().resolve("..").resolve("ja-rpc").resolve("v1").resolve("schema")
                .resolve("ja-rpc-v1.schema.json").normalize();
        String source = Files.readString(schemaPath, StandardCharsets.UTF_8);
        ObjectNode document = (ObjectNode) codec.mapper().readTree(source);
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemaCacheEnabled(true));
        Map<String, Schema> results = new HashMap<>();
        for (Map.Entry<String, String> entry : RESULT_DEFINITIONS.entrySet()) {
            results.put(entry.getKey(), namedSchema(codec, registry, document, entry.getValue()));
        }
        return new ContractSchemas(registry.getSchema(source), Map.copyOf(results));
    }

    /**
     * 从冻结合同的共享 `$defs` 构造方法结果 validator，避免 Java 测试复制字段闭集或 Turn 状态词汇。
     */
    private static Schema namedSchema(JaRpcCodec codec, SchemaRegistry registry,
                                      ObjectNode document, String definition) {
        ObjectNode wrapper = codec.mapper().createObjectNode();
        wrapper.put("$schema", document.path("$schema").textValue());
        wrapper.set("$defs", document.path("$defs").deepCopy());
        wrapper.put("$ref", "#/$defs/" + definition);
        return registry.getSchema(wrapper.toString());
    }

    /**
     * correlated 目录中的请求只是为后续响应提供方法身份；它自身必须同时通过 Codec 与总 Schema。
     */
    private static void registerCorrelatedRequest(JaRpcCodec codec, Schema schema,
                                                  Map<String, String> pending, byte[] bytes,
                                                  JsonNode document, Path file, int lineNumber) {
        try {
            JaRpcCodec.Frame frame = codec.decode(bytes);
            if (!(frame instanceof JaRpcCodec.Request request)
                || !schema.validate(document.toString(), InputFormat.JSON).isEmpty()
                || pending.putIfAbsent(request.id(), request.method()) != null) {
                throw new AssertionError("invalid correlated request context: " + file + ":" + lineNumber);
            }
        } catch (JaRpcException failure) {
            throw new AssertionError("correlated request context rejected: " + file + ":" + lineNumber,
                    failure);
        }
    }

    /**
     * 响应先通过信封校验，再消费同文件 pending 方法并验证专属 result；只有至少一个边界拒绝才是有效负例。
     */
    private static void assertNegativeCorrelatedResponse(JaRpcCodec codec, ContractSchemas schemas,
                                                         Map<String, String> pending, byte[] bytes,
                                                         JsonNode document, Path file, int lineNumber) {
        try {
            codec.decode(bytes);
        } catch (JaRpcException expected) {
            pending.remove(document.path("id").textValue());
            return;
        }
        boolean envelopeRejected = !schemas.envelope()
                .validate(document.toString(), InputFormat.JSON).isEmpty();
        String method = pending.remove(document.path("id").textValue());
        boolean correlationRejected = method == null;
        Schema resultSchema = method == null ? null : schemas.results().get(method);
        boolean resultRejected = document.has("result") && resultSchema != null
                && !resultSchema.validate(document.get("result").toString(), InputFormat.JSON).isEmpty();
        boolean semanticRejected = rejectsChangeSetArtifactSemantics(method, document.path("result"));
        assertTrue(envelopeRejected || correlationRejected || resultRejected || semanticRejected,
                () -> "negative correlated response accepted: " + file + ":" + lineNumber);
    }

    /**
     * JSON Schema 无法关联 Base64 正文、字节长度和摘要；仅对完整字段形态复核该跨字段不变量，
     * 并要求正文是严格 UTF-8，避免历史 Diff 在不同消费者间产生不同解释。
     */
    private static boolean rejectsChangeSetArtifactSemantics(String method, JsonNode result) {
        if (!"turn/change-set/read".equals(method)
                || !result.path("contentBase64").isTextual()
                || !result.path("byteLength").canConvertToInt()
                || !result.path("sha256").isTextual()) return false;
        try {
            byte[] content = Base64.getDecoder().decode(result.path("contentBase64").textValue());
            if (content.length != result.path("byteLength").intValue()) return true;
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            if (!digest.equals(result.path("sha256").textValue())) return true;
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content));
            return false;
        } catch (IllegalArgumentException | CharacterCodingException invalidContent) {
            return true;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
        if ("configuration/replace".equals(method) && params instanceof ObjectNode config
                && providersShareCredential(config.path("document"))) return true;
        if ("runtime/initialize".equals(method)
                && (object.path("params").path("protocolMajor").intValue() != 1
                || object.path("params").has("configSnapshot"))) return true;
        if ("turn/start".equals(method) && params instanceof ObjectNode turn) {
            if (turn.has("cwd") || turn.has("profileId") || turn.has("configRevision")) return true;
            JsonNode content = turn.get("content");
            if (content == null || !content.isArray()) return true;
            if (invalidUserContent(content)) return true;
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
        if ("turn/input-consumed".equals(method) && params instanceof ObjectNode consumed) {
            JsonNode input = consumed.path("input");
            JsonNode userItem = consumed.path("userItem");
            return !input.path("turnId").equals(consumed.path("turnId"))
                    || !userItem.path("turnId").equals(consumed.path("turnId"))
                    || !input.path("content").equals(userItem.path("content"))
                    || !input.path("attachments").equals(userItem.path("attachments"));
        }
        if ("task/tree/delete".equals(method) && params instanceof ObjectNode taskDelete) {
            return !taskDelete.path("taskThreadId").equals(taskDelete.path("confirmTaskThreadId"));
        }
        return false;
    }

    /** 使用生产领域值对象镜像内容顺序、去重和可发送性，并补充同一 Workspace 的跨块约束。 */
    private static boolean invalidUserContent(JsonNode content) {
        try {
            List<UserContentBlock> blocks = new java.util.ArrayList<>();
            String workspaceId = null;
            for (JsonNode item : content) {
                String type = item.path("type").textValue();
                switch (type == null ? "" : type) {
                    case "text" -> blocks.add(new TextContent(item.path("text").textValue()));
                    case "attachment" -> blocks.add(new AttachmentContent(item.path("attachmentId").textValue()));
                    case "skill_reference" -> blocks.add(
                            new SkillReferenceContent(item.path("skillId").textValue()));
                    case "workspace_reference" -> {
                        String currentWorkspace = item.path("workspaceId").textValue();
                        if (workspaceId != null && !workspaceId.equals(currentWorkspace)) return true;
                        workspaceId = currentWorkspace;
                        blocks.add(new WorkspaceReferenceContent(currentWorkspace,
                                item.path("relativePath").textValue(), WorkspaceReferenceContent.Kind.valueOf(
                                item.path("kind").textValue().toUpperCase(java.util.Locale.ROOT))));
                    }
                    default -> { return true; }
                }
            }
            new UserContent(blocks);
            return false;
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    /**
     * Provider 与凭据必须一一绑定；该跨数组项唯一性无法由当前 JSON Schema 直接表达，
     * 因此 golden consumer 在合同边界镜像生产 Policy 的失败关闭规则。
     */
    private static boolean providersShareCredential(JsonNode document) {
        JsonNode providers = document.path("providers");
        if (!providers.isArray()) return false;
        Set<String> credentialIds = new HashSet<>();
        for (JsonNode provider : providers) {
            String credentialId = provider.path("credential_id").textValue();
            if (credentialId != null && !credentialIds.add(credentialId)) return true;
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

    /** 只定位 v1 语料，目录外 fixture 永远不能成为当前协议输入。 */
    private static List<Path> corpusFiles(boolean invalid) throws IOException {
        Path root = goldenRoot().resolve("v1");
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

    /** 总信封与按 method 选择的成功结果 schema 共享同一份冻结合同来源。 */
    private record ContractSchemas(Schema envelope, Map<String, Schema> results) { }

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
