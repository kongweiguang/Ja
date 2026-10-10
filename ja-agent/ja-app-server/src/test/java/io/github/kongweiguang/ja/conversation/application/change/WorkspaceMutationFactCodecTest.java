// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.change;

import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Workspace 收据由工具、恢复投影和持久参与者共用同一严格解码契约。 */
final class WorkspaceMutationFactCodecTest {
    /** 有效收据同时校验字段闭集、UTF-8 长度、摘要与相对路径。 */
    @Test
    void decodesVerifiedMutationReceipt() {
        WorkspaceMutationFactCodec.MutationReceipt receipt = assertInstanceOf(
                WorkspaceMutationFactCodec.MutationReceipt.class,
                WorkspaceMutationFactCodec.decode(WorkspaceMutationFactCodec.NAMESPACE,
                        WorkspaceMutationFactCodec.RECEIPT_TYPE, WorkspaceMutationFactCodec.SCHEMA_VERSION,
                        receipt("files/allowed.txt", "before", "后😀")));

        assertEquals("ws_fixture", receipt.workspaceId());
        assertEquals("files/allowed.txt", receipt.relativePath());
        assertEquals(Utf8Fingerprint.byteLength("后😀"), receipt.afterBytes());
        assertEquals(Utf8Fingerprint.of("后😀").sha256(), receipt.afterSha256());
    }

    /** 两种被允许的观察失败都解为明确类型，供消费者保留其完整性语义。 */
    @Test
    void decodesObservationFailureAsClosedEnum() {
        WorkspaceMutationFactCodec.ObservationFailure failure = assertInstanceOf(
                WorkspaceMutationFactCodec.ObservationFailure.class,
                WorkspaceMutationFactCodec.decode(WorkspaceMutationFactCodec.NAMESPACE,
                        WorkspaceMutationFactCodec.OBSERVATION_FAILURE_TYPE,
                        WorkspaceMutationFactCodec.SCHEMA_VERSION,
                        JsonObjects.builder().putText("workspaceId", "ws_fixture")
                                .putText("reason", JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE.name())
                                .build()));

        assertEquals(JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE, failure.reason());
    }

    /** 未知 envelope、字段、路径和观察失败值全部失败关闭，不映射成弱化事实。 */
    @Test
    void rejectsUnknownIdentityFieldsAndUnsafePaths() {
        JsonObject valid = receipt("files/allowed.txt", "before", "after");
        LinkedHashMap<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> extraField =
                new LinkedHashMap<>(valid.members());
        extraField.put("future", new io.github.kongweiguang.ja.agent.domain.json.JsonBoolean(true));
        LinkedHashMap<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> unsafePath =
                new LinkedHashMap<>(valid.members());
        unsafePath.put("relativePath", new io.github.kongweiguang.ja.agent.domain.json.JsonText("../outside"));

        assertThrows(IllegalArgumentException.class, () -> WorkspaceMutationFactCodec.decode("other",
                WorkspaceMutationFactCodec.RECEIPT_TYPE, WorkspaceMutationFactCodec.SCHEMA_VERSION, valid));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceMutationFactCodec.decode(
                WorkspaceMutationFactCodec.NAMESPACE, WorkspaceMutationFactCodec.RECEIPT_TYPE, 2, valid));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceMutationFactCodec.decode(
                WorkspaceMutationFactCodec.NAMESPACE, WorkspaceMutationFactCodec.RECEIPT_TYPE,
                WorkspaceMutationFactCodec.SCHEMA_VERSION, new JsonObject(extraField)));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceMutationFactCodec.decode(
                WorkspaceMutationFactCodec.NAMESPACE, WorkspaceMutationFactCodec.RECEIPT_TYPE,
                WorkspaceMutationFactCodec.SCHEMA_VERSION, new JsonObject(unsafePath)));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceMutationFactCodec.decode(
                WorkspaceMutationFactCodec.NAMESPACE, WorkspaceMutationFactCodec.OBSERVATION_FAILURE_TYPE,
                WorkspaceMutationFactCodec.SCHEMA_VERSION,
                JsonObjects.builder().putText("workspaceId", "ws_fixture").putText("reason", "UNKNOWN").build()));
    }

    /** 持久事实拒绝 DEL/C1 控制字符，保证解码与最终 ChangeSet 使用同一闭集路径语义。 */
    @Test
    void rejectsControlCharactersInMutationPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> decodeReceipt(receipt("files/bad" + Character.toString(0x7f) + ".txt", "before", "after")));
        assertThrows(IllegalArgumentException.class,
                () -> decodeReceipt(receipt("files/bad" + Character.toString(0x9f) + ".txt", "before", "after")));
    }

    /** 篡改后的长度或摘要不能被解码成真实副作用事实。 */
    @Test
    void rejectsContentIntegrityMismatch() {
        JsonObject valid = receipt("file.txt", "before", "after");
        LinkedHashMap<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> wrongLength =
                new LinkedHashMap<>(valid.members());
        wrongLength.put("afterBytes", new io.github.kongweiguang.ja.agent.domain.json.JsonNumber(99));
        LinkedHashMap<String, io.github.kongweiguang.ja.agent.domain.json.JsonValue> wrongHash =
                new LinkedHashMap<>(valid.members());
        wrongHash.put("afterSha256", new io.github.kongweiguang.ja.agent.domain.json.JsonText("0".repeat(64)));

        assertThrows(IllegalArgumentException.class, () -> decodeReceipt(new JsonObject(wrongLength)));
        assertThrows(IllegalArgumentException.class, () -> decodeReceipt(new JsonObject(wrongHash)));
    }

    /** 为测试统一传入固定 schema 的 mutation receipt envelope。 */
    private static WorkspaceMutationFactCodec.DecodedFact decodeReceipt(JsonObject payload) {
        return WorkspaceMutationFactCodec.decode(WorkspaceMutationFactCodec.NAMESPACE,
                WorkspaceMutationFactCodec.RECEIPT_TYPE, WorkspaceMutationFactCodec.SCHEMA_VERSION, payload);
    }

    /** 生成摘要自洽的最小收据，生产 decoder 本身仍负责重算并验证全部字段。 */
    private static JsonObject receipt(String path, String before, String after) {
        Utf8Fingerprint beforeFingerprint = Utf8Fingerprint.of(before);
        Utf8Fingerprint afterFingerprint = Utf8Fingerprint.of(after);
        return JsonObjects.builder().putText("workspaceId", "ws_fixture").putText("relativePath", path)
                .putBoolean("beforeExists", true).putBoolean("afterExists", true)
                .putText("beforeText", before).putText("afterText", after)
                .putNumber("beforeBytes", beforeFingerprint.byteLength())
                .putNumber("afterBytes", afterFingerprint.byteLength())
                .putText("beforeSha256", beforeFingerprint.sha256())
                .putText("afterSha256", afterFingerprint.sha256()).putBoolean("confined", true).build();
    }
}
