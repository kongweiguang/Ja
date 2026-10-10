// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.conversation.application.change;

import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Workspace 写入事实的唯一纯解码器；不访问文件系统或依赖具体 Workspace 根。
 */
public final class WorkspaceMutationFactCodec {
    public static final String NAMESPACE = "ja.workspace";
    public static final String RECEIPT_TYPE = "mutation-receipt";
    public static final String OBSERVATION_FAILURE_TYPE = "mutation-observation-failure";
    public static final int SCHEMA_VERSION = 1;

    private static final Set<String> RECEIPT_FIELDS = Set.of("workspaceId", "relativePath", "beforeExists",
            "afterExists", "beforeText", "afterText", "beforeBytes", "afterBytes", "beforeSha256",
            "afterSha256", "confined");
    private static final Set<String> OBSERVATION_FAILURE_FIELDS = Set.of("workspaceId", "reason");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * 编解码全部由静态纯函数完成，避免把 JSON 规则拆成可漂移的实例状态。
     */
    private WorkspaceMutationFactCodec() {
    }

    /**
     * 校验事实 envelope 与完整 payload，返回两类受信结构之一供 tracker 和事务参与者共用。
     */
    public static DecodedFact decode(String namespace, String type, int schemaVersion, JsonObject payload) {
        if (!NAMESPACE.equals(namespace) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported Workspace mutation fact identity");
        }
        JsonObject value = Objects.requireNonNull(payload, "payload");
        return switch (Objects.requireNonNull(type, "type")) {
            case RECEIPT_TYPE -> decodeReceipt(value);
            case OBSERVATION_FAILURE_TYPE -> decodeObservationFailure(value);
            default -> throw new IllegalArgumentException("unsupported Workspace mutation fact type");
        };
    }

    /**
     * 严格闭集校验收据，复核 UTF-8 字节数、SHA-256、路径词法与缺失文件的空内容约束。
     */
    private static MutationReceipt decodeReceipt(JsonObject payload) {
        exactFields(payload, RECEIPT_FIELDS);
        String workspaceId = workspaceId(payload);
        String relativePath = relativePath(payload);
        boolean beforeExists = bool(payload, "beforeExists");
        boolean afterExists = bool(payload, "afterExists");
        String beforeText = text(payload, "beforeText");
        String afterText = text(payload, "afterText");
        long beforeBytes = nonNegativeLong(payload, "beforeBytes");
        long afterBytes = nonNegativeLong(payload, "afterBytes");
        String beforeSha256 = sha256(payload, "beforeSha256");
        String afterSha256 = sha256(payload, "afterSha256");
        boolean confined = bool(payload, "confined");
        validateReceiptSnapshot(beforeExists, beforeText, beforeBytes, beforeSha256);
        validateReceiptSnapshot(afterExists, afterText, afterBytes, afterSha256);
        return new MutationReceipt(workspaceId, relativePath, beforeExists, beforeText, beforeBytes, beforeSha256,
                afterExists, afterText, afterBytes, afterSha256, confined);
    }

    /**
     * 解码与公开 DTO 构造共用单侧快照校验，避免 UTF-8 长度或摘要规则在两处漂移。
     */
    private static void validateReceiptSnapshot(boolean exists, String text, long bytes, String sha256) {
        if ((!exists && (!text.isEmpty() || bytes != 0))
            || bytes != Utf8Fingerprint.byteLength(text)
            || !Utf8Fingerprint.of(text).sha256().equals(sha256)) {
            throw new IllegalArgumentException("Workspace mutation receipt integrity mismatch");
        }
    }

    /**
     * 只接受闭集观察失败原因，避免未知值被映射成较弱的完整性结论。
     */
    private static ObservationFailure decodeObservationFailure(JsonObject payload) {
        exactFields(payload, OBSERVATION_FAILURE_FIELDS);
        String workspaceId = workspaceId(payload);
        JaTool.MutationObservationFailure reason;
        try {
            reason = JaTool.MutationObservationFailure.valueOf(text(payload, "reason"));
        } catch (IllegalArgumentException invalidReason) {
            throw new IllegalArgumentException("invalid Workspace mutation observation failure", invalidReason);
        }
        return new ObservationFailure(workspaceId, reason);
    }

    /**
     * 比较完整字段集合，禁止未知扩展字段悄悄改变安全含义。
     */
    private static void exactFields(JsonObject payload, Set<String> expected) {
        if (!payload.members().keySet().equals(expected)) {
            throw new IllegalArgumentException("Workspace mutation fact fields are invalid");
        }
    }

    /**
     * Workspace 身份必须属于明确的宿主命名空间，不能用空串或任意标识绕过隔离。
     */
    private static String workspaceId(JsonObject payload) {
        return validateWorkspaceId(text(payload, "workspaceId"));
    }

    /**
     * 同时约束 DTO 构造入口与 JSON 解码入口，避免未经过 envelope 的对象成为受信事实。
     */
    private static String validateWorkspaceId(String value) {
        Objects.requireNonNull(value, "workspaceId");
        if (!value.startsWith("ws_")) {
            throw new IllegalArgumentException("invalid Workspace identity");
        }
        return ContractChecks.identifier(value, "workspaceId");
    }

    /**
     * 收据路径保持 slash-separated 相对形式，解码不依赖平台或文件系统状态。
     */
    private static String relativePath(JsonObject payload) {
        return validateRelativePath(text(payload, "relativePath"));
    }

    /**
     * 以跨平台词法规则校验相对路径，实际根和链接边界仍由 tracker 在消费时验证。
     */
    static String validateRelativePath(String value) {
        Objects.requireNonNull(value, "relativePath");
        if (value.length() > 4_096 || value.startsWith("/") || value.contains("\\") || value.contains(":")
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid Workspace mutation path");
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("invalid Workspace mutation path");
            }
        }
        return value;
    }

    /**
     * 读取非空文本字段，拒绝 JSON 的隐式数字或布尔字符串转换。
     */
    private static String text(JsonObject payload, String name) {
        if (!(payload.get(name) instanceof JsonText(
                String value1
        ))) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value1;
    }

    /**
     * SHA-256 同时校验格式与内容，错误摘要不能退化成普通可见结果。
     */
    private static String sha256(JsonObject payload, String name) {
        return validateSha256(text(payload, name), name);
    }

    /**
     * 校验十六进制摘要的闭集格式，供持久快照恢复沿用收据解码规则。
     */
    private static String validateSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value;
    }

    /**
     * 读取严格布尔成员，保留 false 与缺失字段之间的区别。
     */
    private static boolean bool(JsonObject payload, String name) {
        if (!(payload.get(name) instanceof JsonBoolean value)) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return value.value();
    }

    /**
     * 读取非负精确整数，拒绝浮点、溢出和负字节数。
     */
    private static long nonNegativeLong(JsonObject payload, String name) {
        if (!(payload.get(name) instanceof JsonNumber(
                BigDecimal number
        ))) {
            throw new IllegalArgumentException("invalid " + name);
        }
        long decoded = number.longValueExact();
        if (decoded < 0) {
            throw new IllegalArgumentException("invalid " + name);
        }
        return decoded;
    }

    /**
     * 两种允许的工作区事实以闭集表示，调用方必须显式处理每种已验证语义。
     */
    public sealed interface DecodedFact permits MutationReceipt, ObservationFailure {
    }

    /**
     * 文件前后快照与 confinement 声明；绝对路径需由消费方绑定当前 frozen Workspace 根。
     *
     * @param workspaceId 工作区稳定标识
     * @param relativePath 相对于工作区的路径
     * @param beforeExists 变更前是否存在
     * @param beforeText 变更前的文本
     * @param beforeBytes 变更前内容的字节数
     * @param beforeSha256 变更前内容的 SHA-256 摘要
     * @param afterExists 变更后是否存在
     * @param afterText 变更后的文本
     * @param afterBytes 变更后内容的字节数
     * @param afterSha256 变更后内容的 SHA-256 摘要
     * @param confined 是否受目录边界约束
     */
    public record MutationReceipt(String workspaceId,
                                  String relativePath,
                                  boolean beforeExists,
                                  String beforeText,
                                  long beforeBytes,
                                  String beforeSha256,
                                  boolean afterExists,
                                  String afterText,
                                  long afterBytes,
                                  String afterSha256,
                                  boolean confined) implements DecodedFact {
        /**
         * 再冻结 DTO 值，阻断调用方通过可变或空字段改变已校验事实。
         */
        public MutationReceipt {
            workspaceId = validateWorkspaceId(workspaceId);
            relativePath = validateRelativePath(relativePath);
            beforeText = Objects.requireNonNull(beforeText, "beforeText");
            beforeSha256 = validateSha256(beforeSha256, "beforeSha256");
            afterText = Objects.requireNonNull(afterText, "afterText");
            afterSha256 = validateSha256(afterSha256, "afterSha256");
            if (beforeBytes < 0 || afterBytes < 0) {
                throw new IllegalArgumentException("Workspace mutation receipt integrity mismatch");
            }
            validateReceiptSnapshot(beforeExists, beforeText, beforeBytes, beforeSha256);
            validateReceiptSnapshot(afterExists, afterText, afterBytes, afterSha256);
        }
    }

    /**
     * 无法完整观察写后状态的明确原因；完整性退化由消费方映射到相应变更集状态。
     *
     * @param workspaceId 工作区稳定标识
     * @param reason 原因说明
     */
    public record ObservationFailure(String workspaceId,
                                     JaTool.MutationObservationFailure reason) implements DecodedFact {
        /**
         * 拒绝缺失身份或原因，确保事务投影不会将损坏值吞成普通文件修改。
         */
        public ObservationFailure {
            workspaceId = validateWorkspaceId(workspaceId);
            Objects.requireNonNull(reason, "reason");
        }
    }
}
