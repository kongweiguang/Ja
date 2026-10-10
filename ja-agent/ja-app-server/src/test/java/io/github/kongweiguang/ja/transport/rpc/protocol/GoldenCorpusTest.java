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
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.InputFormat;
import java.io.ByteArrayInputStream;
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

/** 以冻结语料证明 Java 仅接受 JA-RPC v2，并拒绝旧协议及越界配置样例。 */
final class GoldenCorpusTest {
    private static final Map<String, String> RESULT_DEFINITIONS = Map.ofEntries(
            Map.entry("runtime/health", "runtimeHealthResult"),
            Map.entry("workspace/list", "workspacePageResult"),
            Map.entry("workspace/path/search", "workspacePathSearchResult"),
            Map.entry("thread/create", "threadResult"),
            Map.entry("thread/fork", "threadForkResult"),
            Map.entry("thread/list", "threadListResult"),
            Map.entry("thread/search", "threadPageResult"),
            Map.entry("thread/read", "threadReadResult"),
            Map.entry("thread/item-content/read", "itemContentReadResult"),
            Map.entry("thread/mcp/read", "threadMcpStatusResult"),
            Map.entry("thread/mcp/check", "threadMcpStatusResult"),
            Map.entry("thread/rename", "threadResult"),
            Map.entry("thread/preferences/update", "threadResult"),
            Map.entry("thread/pin", "threadResult"),
            Map.entry("thread/seen", "threadResult"),
            Map.entry("thread/archive", "threadResult"),
            Map.entry("thread/restore", "threadResult"),
            Map.entry("plan/read", "planProjection"),
            Map.entry("plan/revisions/list", "planRevisionsResult"),
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
            Map.entry("runtime/client/close", "taskAcceptedResult"),
            Map.entry("skill/list", "skillPageResult"),
            Map.entry("mcp/list", "mcpPageResult"),
            Map.entry("mcp/test", "mcpTestResult"),
            Map.entry("mcp/list-tools", "mcpToolsResult"),
            Map.entry("workspace/open", "workspaceResult"),
            Map.entry("thread/compact", "threadCompactResult"),
            Map.entry("attachment/import", "attachmentResult"),
            Map.entry("attachment/discard", "attachmentResult"),
            Map.entry("turn/start", "turnAcceptedResult"),
            Map.entry("turn/resume", "turnResumeResult"),
            Map.entry("turn/recovery/respond", "turnRecoveryRespondResult"),
            Map.entry("turn/cancel", "turnCancelResult"),
            Map.entry("turn/input/enqueue", "turnInputEnqueueResult"),
            Map.entry("turn/input/prioritize", "turnInputMutationResult"),
            Map.entry("turn/input/update", "turnInputMutationResult"),
            Map.entry("turn/input/delete", "turnInputMutationResult"),
            Map.entry("turn/change-set/read", "changeSetArtifactReadResult"),
            Map.entry("configuration/read", "configReadResult"),
            Map.entry("configuration/patch", "configMutationResult"),
            Map.entry("configuration/replace", "configMutationResult"),
            Map.entry("configuration/reset", "configMutationResult"),
            Map.entry("credential/set", "credentialMutationResult"),
            Map.entry("credential/delete", "credentialMutationResult"),
            Map.entry("credential/reveal-provider", "credentialRevealProviderResult"),
            Map.entry("review/start", "reviewStartResult"),
            Map.entry("review/read", "reviewReadResult"),
            Map.entry("process/list", "processListResult"),
            Map.entry("process/read", "processSnapshot"),
            Map.entry("process/write", "processSnapshot"),
            Map.entry("process/stop", "processStopResult"));

    /** 遍历全部正向帧并复核活动流与关闭身份语义，防止负例规则误拒绝合法投影。 */
    @Test
    void consumesEveryV2PositiveFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        ObjectMapper mapper = new ObjectMapper();
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
                    assertEquals(HandshakeContractTestAccess.capabilities(mapper),
                            request.params().path("capabilities"),
                            "Java runtime handshake must match the frozen client capability set");
                }
                if (frame instanceof JaRpcCodec.Notification notification
                        && "configuration/changed".equals(notification.method())) {
                            sawConfigChanged = true;
                        }
                JsonNode document = mapper.readTree(bytes);
                if (frame instanceof JaRpcCodec.Notification notification
                        && "task/closed".equals(notification.method())) {
                    assertFalse(violatesSemanticBoundary(document),
                            "positive Task close semantics rejected: " + file + ":" + (index + 1));
                }
                JsonNode result = document.path("result");
                if (result.has("liveStream")) {
                    assertFalse(rejectsLiveStreamSemantics("thread/read", result),
                            "positive live stream semantics rejected: " + file + ":" + (index + 1));
                }
                frames++;
            }
        }
        assertTrue(frames > 0 && sawV2 && sawConfigChanged);
    }

    /**
     * 在正确责任边界拒绝全部反向帧：Codec 负责 JSON 语法与信封结构，冻结的 v2 Schema
     * 及 Secret、cwd 规则负责方法和通知形态，避免生产解码器重复实现合同。
     */
    @Test
    void rejectsEveryV2NegativeFrame() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        ContractSchemas schemas = contractSchemas(codec);
        int frames = 0;
        for (Path file : corpusFiles(true)) {
            boolean correlated = file.toString().contains(
                    Path.of("invalid", "correlated").toString());
            Map<String, CorrelatedRequest> pending = new HashMap<>();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) {
                    continue;
                }
                byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
                JsonNode document;
                try {
                    document = codec.mapper().reader()
                            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(bytes);
                } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                    assertThrows(JaRpcException.class, () -> codec.decode(bytes),
                            "malformed JSON must be rejected by the production codec: " + file + ":" + (index + 1));
                    frames++;
                    continue;
                }
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

    /** 匹配 owner 的状态缺失时合同校验应拒绝 malformed 结果，而不向调用方泄漏 NullPointerException。 */
    @Test
    void rejectsLiveStreamOwnerWithMissingStatus() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode result = mapper.createObjectNode();
        ObjectNode stream = mapper.createObjectNode().put("turnId", "turn_owner").put("streamSeq", 0);
        stream.putArray("segments");
        result.set("liveStream", stream);
        result.putArray("turns").addObject().put("turnId", "turn_owner");

        assertFalse(ThreadReadContract.isValidLiveStream(result));
    }

    /**
     * 仅在测试中加载仓库的 draft-2020-12 合同，使生产解码保持专注于有界
     * JSON-RPC 分帧，不在运行时重复维护每个方法的 Schema。
     */
    private static ContractSchemas contractSchemas(JaRpcCodec codec) throws IOException {
        Path schemaPath = goldenRoot().resolve("..").resolve("ja-rpc").resolve("v2").resolve("schema")
                .resolve("ja-rpc-v2.schema.json").normalize();
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
     * correlated 请求除方法名外还需保留原始参数，以便响应校验能检查代际等跨帧约束。
     */
    private static void registerCorrelatedRequest(JaRpcCodec codec, Schema schema,
                                                  Map<String, CorrelatedRequest> pending, byte[] bytes,
                                                  JsonNode document, Path file, int lineNumber) {
        try {
            JaRpcCodec.Frame frame = codec.decode(bytes);
            if (!(frame instanceof JaRpcCodec.Request request)
                || !schema.validate(document.toString(), InputFormat.JSON).isEmpty()) {
                throw new AssertionError("invalid correlated request context: " + file + ":" + lineNumber);
            }
            if (pending.putIfAbsent(request.id(), new CorrelatedRequest(request.method(), document.path("params")))
                    != null) {
                throw new AssertionError("duplicate correlated request context: " + file + ":" + lineNumber);
            }
        } catch (JaRpcException failure) {
            throw new AssertionError("correlated request context rejected: " + file + ":" + lineNumber,
                    failure);
        }
    }

    /**
     * 响应先通过信封校验，再消费请求上下文并检查 Schema 与跨帧不变量；只做单帧校验会漏掉代际错配。
     */
    private static void assertNegativeCorrelatedResponse(JaRpcCodec codec, ContractSchemas schemas,
                                                         Map<String, CorrelatedRequest> pending, byte[] bytes,
                                                         JsonNode document, Path file, int lineNumber) {
        try {
            codec.decode(bytes);
        } catch (JaRpcException expected) {
            pending.remove(document.path("id").textValue());
            return;
        }
        boolean envelopeRejected = !schemas.envelope()
                .validate(document.toString(), InputFormat.JSON).isEmpty();
        CorrelatedRequest request = pending.remove(document.path("id").textValue());
        String method = request == null ? null : request.method();
        boolean correlationRejected = request == null;
        Schema resultSchema = method == null ? null : schemas.results().get(method);
        boolean resultRejected = document.has("result") && resultSchema != null
                && !resultSchema.validate(document.get("result").toString(), InputFormat.JSON).isEmpty();
        boolean semanticRejected = !resultRejected && (rejectsChangeSetArtifactSemantics(method, document.path("result"))
                || rejectsLiveStreamSemantics(method, document.path("result"))
                || ("thread/read".equals(method) && rejectsToolMediaSemantics(document.path("result")))
                || rejectsReviewSemantics(method,
                        request == null ? null : request.params(), document.path("result"))
                || rejectsThreadForkSemantics(method,
                        request == null ? null : request.params(), document.path("result"))
                || rejectsOperationReadSemantics(method,
                        request == null ? null : request.params(), document.path("result"))
                || rejectsProcessListGenerationStateMismatch(method, document.path("result"))
                || rejectsProcessReadGenerationStateMismatch(method,
                        request == null ? null : request.params(), document.path("result")));
        assertTrue(envelopeRejected || correlationRejected || resultRejected || semanticRejected,
                () -> "negative correlated response accepted: " + file + ":" + lineNumber);
    }

    /** 复用生产端 thread/read 活动流校验，避免 Golden 只用测试专属规则放行残缺恢复基线。 */
    private static boolean rejectsLiveStreamSemantics(String method, JsonNode result) {
        if (!"thread/read".equals(method)) {
            return false;
        }
        return !ThreadReadContract.isValidLiveStream(result);
    }

    /**
     * 字段形态由命名 Schema 先行校验；这里镜像 Rust/TypeScript 的请求身份、终态报告与证据关系，
     * 避免结构合法的其它 Turn 报告或无报告 completed 状态被 Golden 门禁接受。
     */
    private static boolean rejectsReviewSemantics(String method, JsonNode requestParams, JsonNode result) {
        if (!("review/start".equals(method) || "review/read".equals(method))
                || requestParams == null || !result.isObject()) {
            return false;
        }
        if (!result.path("threadId").equals(requestParams.path("threadId"))) {
            return true;
        }
        if ("review/start".equals(method)) {
            return result.path("threadRevision").longValue()
                    < requestParams.path("expectedThreadRevision").longValue();
        }
        if (!result.path("turnId").equals(requestParams.path("turnId"))) {
            return true;
        }
        JsonNode report = result.path("report");
        if (report.isNull()) {
            return "completed".equals(result.path("status").textValue());
        }
        return rejectsReviewReportSemantics(report);
    }

    /**
     * 报告的结构与单字段预算沿用 Schema，仅补齐跨字段完整度、路径与 Git 层联合身份及总 UTF-8 预算，
     * 保持与 Rust/TypeScript 的冻结证据边界一致，而不把同路径的不同 Git 层视为同一文件。
     */
    private static boolean rejectsReviewReportSemantics(JsonNode report) {
        if (report.toString().getBytes(StandardCharsets.UTF_8).length > 3 * 1024 * 1024) {
            return true;
        }
        JsonNode coverage = report.path("coverage");
        JsonNode filesReviewed = coverage.path("filesReviewed");
        boolean complete = coverage.path("complete").booleanValue();
        if (complete != coverage.path("partialReasons").isEmpty()
                || filesReviewed.size() > coverage.path("files").longValue()
                || coverage.path("reviewComplete").booleanValue()
                    != (complete && filesReviewed.size() == coverage.path("files").longValue())) {
            return true;
        }
        Set<List<String>> coveredFiles = new HashSet<>();
        for (JsonNode file : filesReviewed) {
            if (!coveredFiles.add(List.of(file.path("path").textValue(), file.path("layer").textValue()))) {
                return true;
            }
        }
        Set<String> findingIds = new HashSet<>();
        for (JsonNode finding : report.path("findings")) {
            if (!findingIds.add(finding.path("findingId").textValue())
                    || !coveredFiles.contains(List.of(finding.path("path").textValue(),
                            finding.path("layer").textValue()))
                    || finding.path("endLine").longValue() < finding.path("startLine").longValue()) {
                return true;
            }
        }
        return false;
    }

    /** Schema 无法将冻结来源 Item 绑定到原请求；分叉回执还必须返回独立、活动且没有最新 Turn 的新会话。 */
    private static boolean rejectsThreadForkSemantics(String method, JsonNode requestParams, JsonNode result) {
        if (!"thread/fork".equals(method) || requestParams == null || !result.isObject()) {
            return false;
        }
        JsonNode source = result.path("source");
        JsonNode thread = result.path("thread");
        return !source.path("threadId").equals(requestParams.path("sourceThreadId"))
                || !source.path("itemId").equals(requestParams.path("sourceItemId"))
                || !source.path("threadRevision").equals(requestParams.path("expectedThreadRevision"))
                || thread.path("threadId").equals(source.path("threadId"))
                || !"active".equals(thread.path("status").textValue())
                || !thread.path("latestTurnStatus").isNull()
                || !thread.path("latestTurnSeen").booleanValue();
    }

    /** A fork receipt queried with a source owner must return that same owner, preventing cross-Thread recovery. */
    private static boolean rejectsOperationReadSemantics(String method, JsonNode requestParams, JsonNode result) {
        if (!"operation/read".equals(method) || requestParams == null
                || !"committed".equals(result.path("status").textValue())) {
            return false;
        }
        boolean forkReceipt = "thread/fork".equals(result.path("method").textValue());
        if (requestParams.has("sourceThreadId")) {
            return !forkReceipt
                    || !result.path("threadId").equals(requestParams.path("sourceThreadId"))
                    || !result.path("result").path("source").path("threadId")
                            .equals(requestParams.path("sourceThreadId"));
        }
        return forkReceipt;
    }

    /**
     * Schema 的 uniqueItems 只能比较完整对象；Tool 图片以 attachmentId 为身份，
     * 不允许同一图片换名后重复出现。只在 Tool 事件与 Thread 回读中递归检查 presentation 容器。
     */
    private static boolean rejectsToolMediaSemantics(JsonNode node) {
        JsonNode media = node.path("presentation").path("media");
        if (media.isArray()) {
            Set<String> attachmentIds = new HashSet<>();
            for (JsonNode image : media) {
                String attachmentId = image.path("attachmentId").textValue();
                if (attachmentId != null && !attachmentIds.add(attachmentId)) {
                    return true;
                }
            }
        }
        for (JsonNode child : node) {
            if (child.isContainerNode() && rejectsToolMediaSemantics(child)) {
                return true;
            }
        }
        return false;
    }

    /** 列表顶层代际代表当前运行时，旧代际条目只能作为退出或中断事实展示，不能仍宣称存活。 */
    private static boolean rejectsProcessListGenerationStateMismatch(String method, JsonNode result) {
        if (!"process/list".equals(method) || !result.path("generation").canConvertToLong()
                || !result.path("sessions").isArray()) {
            return false;
        }
        long currentGeneration = result.path("generation").longValue();
        for (JsonNode session : result.path("sessions")) {
            if (!session.path("generation").canConvertToLong()) {
                continue;
            }
            long sessionGeneration = session.path("generation").longValue();
            String state = session.path("state").textValue();
            if (sessionGeneration > currentGeneration
                    || (sessionGeneration < currentGeneration
                        && state != null && Set.of("starting", "running", "stopping").contains(state))) {
                return true;
            }
        }
        return false;
    }

    /** 旧代际快照的活动状态必须投影为 interrupted，避免回读让客户端误判进程仍可控制。 */
    private static boolean rejectsProcessReadGenerationStateMismatch(String method, JsonNode requestParams,
                                                                       JsonNode result) {
        if (!"process/read".equals(method) || requestParams == null
                || !requestParams.path("generation").canConvertToLong()
                || !result.path("generation").canConvertToLong()) {
            return false;
        }
        long currentGeneration = requestParams.path("generation").longValue();
        long sessionGeneration = result.path("generation").longValue();
        String state = result.path("state").textValue();
        if (state == null) {
            return false;
        }
        return sessionGeneration > currentGeneration
                || (sessionGeneration < currentGeneration
                    && Set.of("starting", "running", "stopping").contains(state));
    }

    /**
     * JSON Schema 无法关联 Base64 正文、字节长度和摘要；仅对完整字段形态复核该跨字段不变量，
     * 并要求正文是严格 UTF-8，避免历史 Diff 在不同消费者间产生不同解释。
     */
    private static boolean rejectsChangeSetArtifactSemantics(String method, JsonNode result) {
        if (!"turn/change-set/read".equals(method)
                || !result.path("contentBase64").isTextual()
                || !result.path("byteLength").canConvertToInt()
                || !result.path("sha256").isTextual()) {
                    return false;
                }
        try {
            byte[] content = Base64.getDecoder().decode(result.path("contentBase64").textValue());
            if (content.length != result.path("byteLength").intValue()) {
                return true;
            }
            String digest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
            if (!digest.equals(result.path("sha256").textValue())) {
                return true;
            }
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
     * 区分可解析的响应信封与 JA-RPC 的单向路由约束；这里只为负例分类，不赋予反向请求能力。
     */
    private static boolean isUnroutableResponse(JsonNode document) {
        if (!(document instanceof ObjectNode object)) {
            return false;
        }
        String id = object.path("id").textValue();
        return id != null && (id.startsWith("h:") || id.startsWith("c:"))
                && (object.has("result") || object.has("error"));
    }

    /**
     * 镜像刻意位于 JSON Schema 之外的合同约束，重点固定禁止 Secret 泄露和禁止客户端
     * 覆盖 Turn 配置与 Task close 身份关系的规则，确保 RPC 合同始终失败关闭。
     */
    private static boolean violatesSemanticBoundary(JsonNode document) {
        if (!(document instanceof ObjectNode object)) {
            return true;
        }
        String method = object.path("method").textValue();
        JsonNode params = object.get("params");
        if (method != null && method.startsWith("tool/") && params != null
                && rejectsToolMediaSemantics(params)) {
            return true;
        }
        boolean credentialWrite = "credential/set".equals(method);
        if (containsSecret(params, credentialWrite)) {
            return true;
        }
        if ("turn/state-changed".equals(method)) {
            return rejectsTurnTransition(params);
        }
        if ("configuration/patch".equals(method) && params instanceof ObjectNode config) {
            String path = config.path("path").asText("").toLowerCase(java.util.Locale.ROOT);
            if (path.contains("secret") || path.contains("token") || path.contains("password")
                    || path.contains("authorization") || path.contains("api_key") || path.contains("apikey")) {
                return true;
            }
        }
        if ("configuration/replace".equals(method) && params instanceof ObjectNode config
                && providersShareCredential(config.path("document"))) {
                    return true;
                }
        if ("runtime/initialize".equals(method)
                && (object.path("params").path("protocolMajor").intValue() != 2
                || object.path("params").has("configSnapshot"))) {
                    return true;
                }
        if ("turn/start".equals(method) && params instanceof ObjectNode turn) {
            if (turn.has("cwd") || turn.has("profileId") || turn.has("configRevision")) {
                return true;
            }
            JsonNode content = turn.get("content");
            if (content == null || !content.isArray()) {
                return true;
            }
            if (invalidUserContent(content)) {
                return true;
            }
            Set<String> attachmentIds = new HashSet<>();
            int attachmentCount = 0;
            for (JsonNode item : content) {
                if ("attachment".equals(item.path("type").textValue())) {
                    attachmentCount++;
                    if (!attachmentIds.add(item.path("attachmentId").textValue())) {
                        return true;
                    }
                }
            }
            if (attachmentCount > 10) {
                return true;
            }
        }
        if ("configuration/changed".equals(method) && params instanceof ObjectNode changed) {
            if (changed.has("cwd")) {
                return true;
            }
            if ("user".equals(changed.path("scope").textValue()) && changed.has("workspaceId")) {
                return true;
            }
            if ("project".equals(changed.path("scope").textValue()) && !changed.has("workspaceId")) {
                return true;
            }
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
        if ("task/closed".equals(method) && params instanceof ObjectNode closed) {
            String rootThreadId = closed.path("rootThreadId").textValue();
            String taskThreadId = closed.path("taskThreadId").textValue();
            JsonNode closedThreadIds = closed.get("closedThreadIds");
            if (rootThreadId == null || rootThreadId.equals(taskThreadId)
                    || taskThreadId == null || closedThreadIds == null || !closedThreadIds.isArray()) {
                return true;
            }
            for (JsonNode threadId : closedThreadIds) {
                if (taskThreadId.equals(threadId.textValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** 直接复用生产状态机校验跨字段转换，避免合法枚举值组合绕过等待与终态边界。 */
    private static boolean rejectsTurnTransition(JsonNode params) {
        if (!(params instanceof ObjectNode object)) {
            return true;
        }
        try {
            TurnState from = TurnState.valueOf(object.path("from").asText("")
                    .toUpperCase(java.util.Locale.ROOT));
            TurnState to = TurnState.valueOf(object.path("to").asText("")
                    .toUpperCase(java.util.Locale.ROOT));
            return !from.canTransitionTo(to);
        } catch (IllegalArgumentException invalidState) {
            return true;
        }
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
                        if (workspaceId != null && !workspaceId.equals(currentWorkspace)) {
                            return true;
                        }
                        workspaceId = currentWorkspace;
                        String kind = item.path("kind").textValue();
                        if (!("file".equals(kind) || "directory".equals(kind))) {
                            return true;
                        }
                        blocks.add(new ResourceReferenceContent(currentWorkspace,
                                item.path("relativePath").textValue(), kind));
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
        if (!providers.isArray()) {
            return false;
        }
        Set<String> credentialIds = new HashSet<>();
        for (JsonNode provider : providers) {
            String credentialId = provider.path("credential_id").textValue();
            if (credentialId != null && !credentialIds.add(credentialId)) {
                return true;
            }
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
                        "authorization", "apikey", "api_key").contains(key)) {
                            return true;
                        }
                if (containsSecret(entry.getValue(), credentialWrite)) {
                    return true;
                }
            }
        } else if (value != null && value.isArray()) {
            for (JsonNode child : value) {
                if (containsSecret(child, credentialWrite)) {
                return true;
            }
            }
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

    /**
     * JSONL 的根值必须与物理行一一对应；尾随第二个 JSON 不能被 Jackson 默默丢弃。
     */
    @Test
    void rejectsSecondJsonRootWithinOneLine() throws IOException {
        JaRpcCodec codec = new JaRpcCodec();
        String response = "{\"jsonrpc\":\"2.0\",\"id\":\"c:test\",\"result\":{}}";
        byte[] malformed = (response + " " + response + "\n").getBytes(StandardCharsets.UTF_8);

        assertThrows(JaRpcException.class, () -> codec.decode(malformed));
        assertThrows(JaRpcException.class, () -> codec.read(new ByteArrayInputStream(malformed)));

        ByteArrayInputStream separateLines = new ByteArrayInputStream(
                (response + "\n" + response + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(codec.read(separateLines).orElseThrow() instanceof JaRpcCodec.Response);
        assertTrue(codec.read(separateLines).orElseThrow() instanceof JaRpcCodec.Response);
        assertTrue(codec.read(separateLines).isEmpty());
    }

    /** 字节入口与 JSONL 入口须接受同一最大帧，并以相同错误拒绝多一字节的内容。 */
    @Test
    void enforcesSameFrameSizeForDecodeAndRead() throws IOException {
        int maxFrameBytes = 1_024;
        JaRpcCodec codec = new JaRpcCodec(maxFrameBytes);
        byte[] response = "{\"jsonrpc\":\"2.0\",\"id\":\"c:limit\",\"result\":{}}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] boundary = java.util.Arrays.copyOf(response, maxFrameBytes);
        java.util.Arrays.fill(boundary, response.length, boundary.length, (byte) ' ');
        byte[] boundaryLine = java.util.Arrays.copyOf(boundary, boundary.length + 1);
        boundaryLine[boundary.length] = '\n';

        assertTrue(codec.decode(boundary) instanceof JaRpcCodec.Response);
        assertTrue(codec.read(new ByteArrayInputStream(boundaryLine)).orElseThrow()
                instanceof JaRpcCodec.Response);

        byte[] oversized = java.util.Arrays.copyOf(boundary, maxFrameBytes + 1);
        oversized[maxFrameBytes] = ' ';
        byte[] oversizedLine = java.util.Arrays.copyOf(oversized, oversized.length + 1);
        oversizedLine[oversized.length] = '\n';
        assertEquals("FRAME_TOO_LARGE", assertThrows(JaRpcException.class,
                () -> codec.decode(oversized)).errorCode());
        assertEquals("FRAME_TOO_LARGE", assertThrows(JaRpcException.class,
                () -> codec.read(new ByteArrayInputStream(oversizedLine))).errorCode());
    }

    /** 只定位 v2 语料，目录外 fixture 永远不能成为当前协议输入。 */
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

    /**
     * 总信封与按 method 选择的成功结果 schema 共享同一份冻结合同来源。
     *
     * @param envelope 协议封装
     * @param results 结果列表
     */
    private record ContractSchemas(Schema envelope,
                                   Map<String, Schema> results) { }

    /**
     * 保留 correlated 语料的请求参数，供不适合写入 JSON Schema 的跨帧约束复核。
     *
     * @param method 用于选择响应 Schema 的 JA-RPC 方法名
     * @param params 原始请求参数，用于复核依赖请求上下文的跨帧约束
     */
    private record CorrelatedRequest(String method,
                                     JsonNode params) { }

    /** 同时支持 Maven 模块目录和工作区根目录，避免测试启动位置改变合同来源。 */
    private static Path goldenRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("contracts").resolve("golden");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("golden corpus is unavailable");
    }
}
