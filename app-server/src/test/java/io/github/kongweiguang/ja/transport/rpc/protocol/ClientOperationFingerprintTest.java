// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 幂等指纹只承认规范化 JSON 语义相同，避免载荷键顺序导致误冲突。 */
final class ClientOperationFingerprintTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 嵌套对象重排和操作 ID 替换不改变命令内容，数组顺序与正文变化仍改变摘要。 */
    @Test
    void objectOrderIsCanonicalButContentAndArrayOrderRemainMeaningful() throws Exception {
        ObjectNode first = (ObjectNode) mapper.readTree("""
                {"clientOperationId":"op_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","threadId":"thr_a",
                 "content":[{"type":"text","text":"one"},{"type":"text","text":"two"}],
                 "metadata":{"z":1,"a":{"y":2,"x":3}}}
                """);
        ObjectNode reordered = (ObjectNode) mapper.readTree("""
                {"metadata":{"a":{"x":3,"y":2},"z":1},
                 "content":[{"text":"one","type":"text"},{"text":"two","type":"text"}],
                 "threadId":"thr_a","clientOperationId":"op_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                """);
        String baseline = ClientOperationFingerprint.sha256(mapper, first);
        assertEquals(baseline, ClientOperationFingerprint.sha256(mapper, reordered));
        reordered.withArray("content").remove(0);
        assertNotEquals(baseline, ClientOperationFingerprint.sha256(mapper, reordered));
    }
}
