// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnChangeSetSerialization;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * TurnChangeSet 的显式持久 codec，确保 diff 正文不混入历史摘要 JSON。
 */
public final class TurnChangeSetCodec implements TurnChangeSetSerialization {
    private final ObjectMapper mapper;

    /**
     * 复用 composition ObjectMapper，但字段集由本类固定。
     */
    public TurnChangeSetCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * 枚举在唯一 codec 处转换为小写。
     */
    private static String wire(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * 读取必需字段。
     */
    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            throw invalid();
        }
        return value;
    }

    /**
     * 读取必需文本。
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    /**
     * 读取可选文本。
     */
    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw invalid();
        }
        return value.textValue();
    }

    /**
     * 读取必需非负 long。
     */
    private static long longValue(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalid();
        }
        return value.longValue();
    }

    /**
     * 持久化布尔字段必须保持 JSON boolean 类型，避免 Jackson 将损坏值静默折算成 false。
     */
    private static boolean booleanValue(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw invalid();
        }
        return value.booleanValue();
    }

    /**
     * 存储损坏使用稳定错误。
     */
    private static StorageException invalid() {
        return new StorageException(StorageException.Code.INVALID_STATE, "turn change set is invalid");
    }

    /**
     * 解码失败保留底层解析原因，同时保持持久化错误分类和脱敏文案稳定。
     */
    private static StorageException invalid(Throwable cause) {
        return new StorageException(StorageException.Code.INVALID_STATE, "turn change set is invalid", cause);
    }

    /**
     * 只保存文件事实、汇总和 artifactId，不复制 unified diff。
     */
    public String write(TurnChangeSet value) {
        ObjectNode root = mapper.createObjectNode().put("state", wire(value.state()));
        ArrayNode reasons = root.putArray("incompleteReasons");
        value.incompleteReasons().stream().map(TurnChangeSetCodec::wire).sorted().forEach(reasons::add);
        ArrayNode files = root.putArray("files");
        value.files().forEach(file -> {
            files.addObject().put("path", file.path()).put("status", wire(file.status()))
                    .put("additions", file.additions()).put("deletions", file.deletions())
                    .put("binary", file.binary()).put("truncated", file.truncated());
        });
        TurnChangeSet.Stats stats = value.stats();
        root.putObject("stats").put("files", stats.files()).put("additions", stats.additions())
                .put("deletions", stats.deletions()).put("binaryFiles", stats.binaryFiles())
                .put("truncated", stats.truncated());
        if (value.artifactId() != null) {
            root.put("artifactId", value.artifactId());
        }
        return root.toString();
    }

    /**
     * 将既有持久 JSON 字段结构转成 Core 严格 JSON 值，供 ja.turn ExtensionFact 直接携带。
     */
    @Override
    public JsonObject writePayload(TurnChangeSet value) {
        try {
            JsonValue payload = JacksonJsonValues.fromNode(mapper.readTree(write(value)));
            if (payload instanceof JsonObject object) {
                return object;
            }
            throw new IllegalStateException("generated TurnChangeSet payload is not an object");
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException("generated TurnChangeSet JSON is invalid", impossible);
        }
    }

    /**
     * 损坏的历史 change set 失败关闭，不降级为伪造的 unavailable。
     */
    public TurnChangeSet read(String value) {
        try {
            JsonNode root = mapper.readTree(value);
            java.util.EnumSet<TurnChangeSet.IncompleteReason> reasons =
                    java.util.EnumSet.noneOf(TurnChangeSet.IncompleteReason.class);
            for (JsonNode reason : required(root, "incompleteReasons")) {
                if (!reason.isTextual()) {
                    throw invalid();
                }
                reasons.add(TurnChangeSet.IncompleteReason.valueOf(reason.textValue().toUpperCase(Locale.ROOT)));
            }
            List<TurnChangeSet.FileChange> files = new ArrayList<>();
            for (JsonNode file : required(root, "files")) {
                files.add(new TurnChangeSet.FileChange(text(file, "path"),
                        TurnChangeSet.FileStatus.valueOf(text(file, "status").toUpperCase(Locale.ROOT)),
                        longValue(file, "additions"), longValue(file, "deletions"),
                        booleanValue(file, "binary"), booleanValue(file, "truncated")));
            }
            JsonNode stats = required(root, "stats");
            return new TurnChangeSet(
                    TurnChangeSet.State.valueOf(text(root, "state").toUpperCase(Locale.ROOT)),
                    reasons, files,
                    new TurnChangeSet.Stats(longValue(stats, "files"), longValue(stats, "additions"),
                            longValue(stats, "deletions"), longValue(stats, "binaryFiles"),
                            booleanValue(stats, "truncated")), optionalText(root, "artifactId"));
        } catch (StorageException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid(failure);
        } catch (java.io.IOException failure) {
            throw invalid(failure);
        }
    }

    /**
     * 从 ExtensionFact 的不可变 JSON 对象解码既有 TurnChangeSet schema，不接受另一套字段形状。
     */
    public TurnChangeSet readPayload(JsonObject payload) {
        try {
            return read(mapper.writeValueAsString(JacksonJsonValues.toNode(mapper,
                    Objects.requireNonNull(payload, "payload"))));
        } catch (java.io.IOException impossible) {
            throw new IllegalArgumentException("TurnChangeSet payload could not be encoded", impossible);
        }
    }
}
