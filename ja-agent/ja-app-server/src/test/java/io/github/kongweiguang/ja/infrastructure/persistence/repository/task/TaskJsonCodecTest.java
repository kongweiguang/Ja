// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 Task 持久化 JSON 在普通 ObjectMapper 配置下仍严格拒绝重复字段和额外根内容。 */
final class TaskJsonCodecTest {
    private final TaskJsonCodec codec = new TaskJsonCodec(new ObjectMapper());

    /** 合法对象和数组沿用基础 JsonValue 语义，严格读取不改变持久格式。 */
    @Test
    void readsValidObjectAndArray() {
        assertEquals(new JsonText("empty"), codec.readObject("{\"kind\":\"empty\"}").get("kind"));
        assertEquals(3, codec.readArray("[true,1,null]").values().size());
    }

    /** 任何层级的重复字段均按持久化损坏处理，且异常链不携带 JSON 正文。 */
    @Test
    void rejectsDuplicateFieldsWithoutEchoingPayload() {
        assertCorruptWithoutEcho("{\"payload\":\"private-task-value\",\"payload\":\"replacement\"}",
                "private-task-value");
    }

    /** 第二个根值与合法根后的垃圾都不能被 Jackson 静默忽略。 */
    @Test
    void rejectsTrailingRootAndGarbageWithoutEchoingPayload() {
        assertCorruptWithoutEcho("{\"payload\":\"private-task-value\"} null", "private-task-value");
        assertCorruptWithoutEcho("{\"payload\":\"private-task-value\"} invalid-tail-token",
                "private-task-value");
    }

    /** 持久解码错误只暴露稳定分类与诊断，不把可能含 prompt 的原始 JSON 放进异常文本。 */
    private void assertCorruptWithoutEcho(String json, String privateValue) {
        StorageException failure = assertThrows(StorageException.class, () -> codec.readObject(json));
        assertEquals(StorageException.Code.INVALID_STATE, failure.code());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertFalse(cause.getMessage() != null && cause.getMessage().contains(privateValue));
            assertFalse(cause.toString().contains(json));
        }
    }
}
