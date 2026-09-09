// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 真文件测试覆盖凭据首次写入、替换与清空，不能只验证不存在文件的首次创建。 */
final class ConfigurationSecretReplacementTest {
    /** 清空最后一个凭据仍是安全原子替换，成功后不能遗留回滚链接或临时明文。 */
    @Test
    void replacesExistingSecretWithEmptyDocument(@TempDir Path root) throws Exception {
        Path auth = root.toRealPath().resolve("auth.json");
        ConfigurationStore.writeAtomic(auth, "{\"cred_fixture\":\"fixture-only\"}".getBytes(StandardCharsets.UTF_8), true);
        byte[] empty = "{}".getBytes(StandardCharsets.UTF_8);
        ConfigurationStore.writeAtomic(auth, empty, true);
        assertArrayEquals(empty, ConfigurationStore.readSecret(auth));
        try (var files = Files.list(root)) {
            assertEquals(1L, files.count());
        }
    }
}
