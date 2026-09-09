// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证构建版本只接受已展开且非空的权威资源。 */
final class ProductVersionTest {
    /** 确认默认 Maven 生命周期向测试 classpath 提供了已展开的语义版本。 */
    @Test
    void exposesFilteredProjectVersion() {
        assertTrue(ProductVersion.current().matches("\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?"));
    }

    /** 缺失资源必须失败，防止开发或 Native 构建退回另一套常量。 */
    @Test
    void rejectsMissingResource() {
        assertThrows(IllegalStateException.class, () -> ProductVersion.readVersion(null));
    }

    /** 未经过 Maven 过滤的模板不能成为运行时身份。 */
    @Test
    void rejectsUnexpandedPlaceholder() {
        var stream = new ByteArrayInputStream(
                "product.version=${project.version}\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> ProductVersion.readVersion(stream));
    }

    /** 属性值允许常规空白，但对外暴露稳定、无空白的版本。 */
    @Test
    void trimsFilteredVersion() throws Exception {
        var stream = new ByteArrayInputStream("product.version= 0.1.0 \n".getBytes(StandardCharsets.UTF_8));
        assertEquals("0.1.0", ProductVersion.readVersion(stream));
    }
}

