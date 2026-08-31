// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.aot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 验证 Native Image 元数据覆盖运行时真实创建的全部 MyBatis Mapper 代理。 */
final class NativeReachabilityMetadataTest {
    private static final String METADATA_PATH =
            "src/native/resources/META-INF/native-image/io/github/kongweiguang/ja/"
                    + "reachability-metadata.json";

    /**
     * 从生产 Mapper 聚合记录派生闭集，避免新增 Mapper 只在 JVM 测试通过、到 Native 启动时才失败。
     */
    @Test
    void registersEveryPersistenceMapperProxy() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(metadataPath()));
        Set<String> proxies = new HashSet<>();
        for (JsonNode entry : root.path("reflection")) {
            JsonNode proxy = entry.path("type").path("proxy");
            if (proxy.isArray() && proxy.size() == 1 && proxy.get(0).isTextual()) {
                proxies.add(proxy.get(0).textValue());
            }
        }

        for (RecordComponent component : PersistenceMappers.class.getRecordComponents()) {
            assertTrue(proxies.contains(component.getType().getName()),
                    () -> "missing Native proxy metadata for " + component.getType().getName());
        }
    }

    /**
     * Maven 可从仓库根或 module 根启动；仅在这两个确定位置解析源码元数据，不搜索父目录或旧路径。
     */
    private static Path metadataPath() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path moduleRoot = Files.isRegularFile(workingDirectory.resolve(METADATA_PATH))
                ? workingDirectory
                : workingDirectory.resolve("app-server");
        return moduleRoot.resolve(METADATA_PATH);
    }
}
