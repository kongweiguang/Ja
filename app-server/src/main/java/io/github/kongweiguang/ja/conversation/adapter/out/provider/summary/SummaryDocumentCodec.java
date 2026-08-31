// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.provider.summary;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.shared.AbstractStreamingModelAdapter;
import io.github.kongweiguang.ja.conversation.application.context.ContextException;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryDocument;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.foundation.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 编码并严格校验 Summary v2 的事实来源与退休清单结构。 */
final class SummaryDocumentCodec {
    private static final List<String> FACT_FIELDS = List.of(
            "goals", "constraints", "completedProgress", "currentProgress", "blockers", "decisions",
            "nextSteps", "criticalFacts", "files", "pendingEffects");
    private static final JsonObject SCHEMA = (JsonObject) JacksonJsonValues.fromNode(schemaNode());

    /** 无状态 Codec 不允许实例化，避免产生第二套摘要 schema。 */
    private SummaryDocumentCodec() { throw new AssertionError("no instances"); }

    /** 返回 OpenAI 与 Anthropic Structured Outputs 共用的严格 schema。 */
    static JsonObject schema() { return SCHEMA; }

    /** 显式投影事实和退休项，不依赖 record 反射或字段命名策略。 */
    static ObjectNode encode(SummaryDocument document) {
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        addFacts(root, "goals", document.goals());
        addFacts(root, "constraints", document.constraints());
        addFacts(root, "completedProgress", document.completedProgress());
        addFacts(root, "currentProgress", document.currentProgress());
        addFacts(root, "blockers", document.blockers());
        addFacts(root, "decisions", document.decisions());
        addFacts(root, "nextSteps", document.nextSteps());
        addFacts(root, "criticalFacts", document.criticalFacts());
        addFacts(root, "files", document.files());
        addFacts(root, "pendingEffects", document.pendingEffects());
        ArrayNode retirements = root.putArray("retirements");
        for (SummaryDocument.Retirement retirement : document.retirements()) {
            retirements.addObject().put("text", retirement.text())
                    .put("sourceOrdinal", retirement.sourceOrdinal())
                    .put("status", retirement.status().name().toLowerCase(Locale.ROOT));
        }
        return root;
    }

    /** 严格解码完整替换文档，未知字段、重复键和非整数来源全部失败关闭。 */
    static SummaryDocument decode(String value) {
        try (JsonParser parser = AbstractStreamingModelAdapter.JSON.createParser(value)) {
            parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
            JsonNode root = AbstractStreamingModelAdapter.JSON.readTree(parser);
            if (!(root instanceof ObjectNode object) || parser.nextToken() != null
                || object.size() != FACT_FIELDS.size() + 1 || !object.has("retirements")) {
                throw failure("summary model returned an invalid v2 document shape");
            }
            for (String field : FACT_FIELDS) if (!object.has(field)) {
                throw failure("summary model returned an invalid v2 document shape");
            }
            return new SummaryDocument(
                    facts(object, "goals"), facts(object, "constraints"),
                    facts(object, "completedProgress"), facts(object, "currentProgress"),
                    facts(object, "blockers"), facts(object, "decisions"),
                    facts(object, "nextSteps"), facts(object, "criticalFacts"),
                    facts(object, "files"), facts(object, "pendingEffects"), retirements(object));
        } catch (ContextException failure) {
            throw failure;
        } catch (java.io.IOException | IllegalArgumentException failure) {
            throw failure("summary model returned invalid structured JSON");
        }
    }

    /** 构造所有 Provider 都能严格执行的对象数组 schema。 */
    private static ObjectNode schemaNode() {
        ObjectNode root = AbstractStreamingModelAdapter.JSON.createObjectNode();
        root.put("type", "object").put("additionalProperties", false);
        ObjectNode properties = root.putObject("properties");
        ObjectNode fact = properties.objectNode().put("type", "object").put("additionalProperties", false);
        fact.putObject("properties").putObject("text").put("type", "string");
        fact.withObject("properties").putObject("sourceOrdinal").put("type", "integer").put("minimum", 1);
        fact.putArray("required").add("text").add("sourceOrdinal");
        for (String field : FACT_FIELDS) {
            properties.putObject(field).put("type", "array").set("items", fact.deepCopy());
        }
        ObjectNode retirement = fact.deepCopy();
        retirement.withObject("properties").putObject("status").put("type", "string")
                .putArray("enum").add("resolved").add("superseded").add("cancelled");
        retirement.withArray("required").add("status");
        properties.putObject("retirements").put("type", "array").set("items", retirement);
        ArrayNode required = root.putArray("required");
        FACT_FIELDS.forEach(required::add);
        required.add("retirements");
        return root;
    }

    /** 将一个事实分区编码为稳定对象数组。 */
    private static void addFacts(ObjectNode root, String field, List<SummaryDocument.Fact> values) {
        ArrayNode array = root.putArray(field);
        for (SummaryDocument.Fact fact : values) {
            array.addObject().put("text", fact.text()).put("sourceOrdinal", fact.sourceOrdinal());
        }
    }

    /** 读取事实数组并拒绝额外字段或非整数 ordinal。 */
    private static List<SummaryDocument.Fact> facts(ObjectNode root, String field) {
        JsonNode value = root.get(field);
        if (!(value instanceof ArrayNode array)) throw failure("summary fact collection is invalid");
        List<SummaryDocument.Fact> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object) || object.size() != 2
                || !object.path("text").isTextual() || !object.path("sourceOrdinal").isIntegralNumber()) {
                throw failure("summary fact is invalid");
            }
            result.add(new SummaryDocument.Fact(
                    object.path("text").textValue(), object.path("sourceOrdinal").longValue()));
        }
        return List.copyOf(result);
    }

    /** 读取退休清单并把封闭 wire 词汇映射为领域枚举。 */
    private static List<SummaryDocument.Retirement> retirements(ObjectNode root) {
        JsonNode value = root.get("retirements");
        if (!(value instanceof ArrayNode array)) throw failure("summary retirement collection is invalid");
        List<SummaryDocument.Retirement> result = new ArrayList<>();
        for (JsonNode item : array) {
            if (!(item instanceof ObjectNode object) || object.size() != 3
                || !object.path("text").isTextual() || !object.path("sourceOrdinal").isIntegralNumber()
                || !object.path("status").isTextual()) {
                throw failure("summary retirement is invalid");
            }
            SummaryDocument.Status status = switch (object.path("status").textValue()) {
                case "resolved" -> SummaryDocument.Status.RESOLVED;
                case "superseded" -> SummaryDocument.Status.SUPERSEDED;
                case "cancelled" -> SummaryDocument.Status.CANCELLED;
                default -> throw failure("summary retirement status is invalid");
            };
            result.add(new SummaryDocument.Retirement(object.path("text").textValue(),
                    object.path("sourceOrdinal").longValue(), status));
        }
        return List.copyOf(result);
    }

    /** 创建不含 Provider 正文的稳定摘要失败。 */
    private static ContextException failure(String message) {
        return new ContextException(ContextException.Code.SUMMARY_FAILURE, message);
    }
}
