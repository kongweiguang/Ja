// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 验证类型化 RPC 命令不会把可变 Wire 参数跨异步边界共享。
 */
final class RpcCommandTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 锁定构造输入与读取结果的双向隔离，避免异步 Handler 通过可变 JSON 互相污染。
     */
    @Test
    void isolatesMutableParamsAtBothBoundaries() {
        ObjectNode source = MAPPER.createObjectNode().put("value", "original");
        RpcCommand command = new RpcCommand(RpcMethod.RUNTIME_HEALTH, source);

        source.put("sourceMutation", true);
        ObjectNode firstRead = command.params();
        firstRead.put("readerMutation", true);

        ObjectNode secondRead = command.params();
        assertEquals("original", secondRead.path("value").textValue());
        assertFalse(secondRead.has("sourceMutation"));
        assertFalse(secondRead.has("readerMutation"));
    }
}
