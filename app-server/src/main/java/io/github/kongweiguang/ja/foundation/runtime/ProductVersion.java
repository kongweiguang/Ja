// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.foundation.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * 暴露构建时注入的 Ja 产品版本，避免协议身份维护独立硬编码。
 */
public final class ProductVersion {
    private static final String RESOURCE_PATH = "/ja-build.properties";
    private static final String VERSION = loadFromClasspath();

    /** 静态版本入口不允许实例化，避免调用方误以为版本会随对象或请求变化。 */
    private ProductVersion() {
    }

    /**
     * 返回当前二进制实际携带的版本；资源无效时类初始化已经失败，不提供猜测性回退。
     */
    public static String current() {
        return VERSION;
    }

    /**
     * 从固定 classpath 资源加载版本，使 JVM 与 Native Image 使用完全相同的身份来源。
     */
    private static String loadFromClasspath() {
        try (InputStream stream = ProductVersion.class.getResourceAsStream(RESOURCE_PATH)) {
            return readVersion(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read Ja product version resource", exception);
        }
    }

    /**
     * 严格验证过滤结果；未展开的 Maven 占位符也必须失败，防止错误构建伪装成有效身份。
     */
    static String readVersion(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalStateException("Ja product version resource is missing");
        }
        Properties properties = new Properties();
        properties.load(stream);
        String version = Objects.toString(properties.getProperty("product.version"), "").trim();
        if (version.isEmpty() || version.contains("${")) {
            throw new IllegalStateException("Ja product version resource is invalid");
        }
        return version;
    }
}

