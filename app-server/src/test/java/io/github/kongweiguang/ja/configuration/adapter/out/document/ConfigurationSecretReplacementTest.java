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
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

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

    /** POSIX 硬链接允许并不允许父目录 symlink 将凭据写入外部位置。 */
    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rejectsSymbolicLinkParent(@TempDir Path root) throws Exception {
        Path canonical = root.toRealPath();
        Path outside = Files.createDirectory(canonical.resolve("outside"));
        Path link = Files.createSymbolicLink(canonical.resolve("alias"), outside);
        assertThrows(java.io.IOException.class, () -> ConfigurationStore.writeAtomic(
                link.resolve("auth.json"), "{}".getBytes(StandardCharsets.UTF_8), true));
        assertEquals(false, Files.exists(outside.resolve("auth.json")));
    }
}
