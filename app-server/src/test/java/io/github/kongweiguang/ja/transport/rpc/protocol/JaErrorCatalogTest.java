// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.transport.rpc.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 将编译后的 Java 错误表锁定到唯一冻结合同，避免多语言错误语义分叉。 */
final class JaErrorCatalogTest {
    /** 逐项比对 v2 错误元组，并拒绝数值 code 或 errorCode 重复造成的歧义映射。 */
    @Test
    void exactlyMatchesFrozenCatalog() throws IOException {
        JsonNode contract = new ObjectMapper().readTree(Files.readString(catalogPath()));
        Set<Integer> codes = new HashSet<>();
        Set<String> names = new HashSet<>();
        int count = 0;
        for (JsonNode item : contract.path("errors")) {
            int code = item.path("code").intValue();
            String name = item.path("errorCode").textValue();
            String category = item.path("category").textValue();
            boolean retryable = item.path("retryable").booleanValue();
            JaErrorCatalog value = JaErrorCatalog.valueOf(name);
            assertEquals(code, value.code());
            assertEquals(category, value.category().wireName());
            assertEquals(retryable, value.retryable());
            JaErrorCatalog.requireTuple(code, name, category, retryable);
            if (!codes.add(code) || !names.add(name)) throw new AssertionError("duplicate error tuple");
            count++;
        }
        assertEquals(58, count);
        assertEquals(count, JaErrorCatalog.values().length);
        assertThrows(IllegalArgumentException.class,
                () -> JaErrorCatalog.requireTuple(-32009, "QUEUE_FULL", "capacity", true));
    }

    /** 兼容模块与工作区两种执行目录，但始终只读取仓库内冻结的错误合同。 */
    private static Path catalogPath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("contracts/ja-rpc/v2/error-catalog.json");
            if (Files.isRegularFile(candidate)) return candidate;
            current = current.getParent();
        }
        throw new IllegalStateException("error catalog is unavailable");
    }
}
