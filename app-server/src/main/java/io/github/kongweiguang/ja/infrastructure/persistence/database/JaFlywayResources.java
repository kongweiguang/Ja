// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.resource.LoadableResource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * 面向 Native Image 的有限 Flyway 资源提供者。
 *
 *
 * <p>Flyway 默认 classpath scanner 会向 JVM 请求 URL 目录列表，但 Graal Native Image
 * 不暴露 {@code resource:} 目录协议。有限 provider 显式限定 migration 发现范围，
 * 解析、校验和事务所有权仍交给 Flyway。</p>
 */
final class JaFlywayResources implements ResourceProvider {
    private static final String RESOURCE_ROOT = "db/migration/";
    private static final List<String> MIGRATIONS = List.of(RESOURCE_ROOT + "V1__kernel.sql",
            RESOURCE_ROOT + "V2__thread_subagent_policies.sql",
            RESOURCE_ROOT + "V3__subagent_reasoning.sql");

    /**
     * 只暴露当前 V1/V2/V3 迁移；资源闭集不包含历史脚本，因此 Native Image 和 JVM 不可能走出不同升级链。
     */
    static JaFlywayResources provider() {
        return new JaFlywayResources();
    }

    /**
     * 精确解析唯一 migration 路径，不探测任意文件系统位置或历史脚本名。
     */
    @Override
    public LoadableResource getResource(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("classpath:")) {
            normalized = normalized.substring("classpath:".length());
        }
        int migrationMarker = normalized.indexOf(RESOURCE_ROOT);
        if (migrationMarker >= 0) {
            normalized = normalized.substring(migrationMarker);
        }
        for (String migration : MIGRATIONS) {
            if (migration.equals(normalized)
                || migration.substring(RESOURCE_ROOT.length()).equals(normalized)) {
                return new ClasspathMigration(migration);
            }
        }
        return null;
    }

    /**
     * Flyway 查询 db/migration 时只返回固定 migration。
     */
    @Override
    public Collection<LoadableResource> getResources(String prefix, String[] suffixes) {
        boolean sqlRequested = suffixes == null || Arrays.stream(suffixes)
                .anyMatch(suffix -> ".sql".equalsIgnoreCase(suffix) || "sql".equalsIgnoreCase(suffix));
        if (!sqlRequested || (prefix != null && !prefix.isBlank() && !"V".equals(prefix))) {
            return List.of();
        }
        return MIGRATIONS.stream().map(ClasspathMigration::new).map(LoadableResource.class::cast).toList();
    }

    /**
     * 将一个受控 classpath migration 绑定到 Flyway resource contract。
     */
    private static final class ClasspathMigration extends LoadableResource {
        private final String path;

        /**
         * 保存规范化 classpath 路径，供全部 Flyway 诊断复用。
         */
        private ClasspathMigration(String path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /**
         * 返回 Flyway history 诊断使用的稳定 classpath URI。
         */
        @Override
        public String getAbsolutePath() {
            return "classpath:" + path;
        }

        /**
         * Native resource 没有磁盘路径，classpath identity 是唯一合法路径。
         */
        @Override
        public String getAbsolutePathOnDisk() {
            return getAbsolutePath();
        }

        /**
         * 返回 Flyway 用于版本解析的文件名。
         */
        @Override
        public String getFilename() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        /**
         * 返回不带前导斜杠的 location-relative path。
         */
        @Override
        public String getRelativePath() {
            return path;
        }

        /**
         * 以严格 UTF-8 文本读取内嵌 migration。
         */
        @Override
        public Reader read() {
            InputStream stream = JaFlywayResources.class.getClassLoader().getResourceAsStream(path);
            if (stream == null) {
                throw new StorageException(StorageException.Code.IO,
                        "Flyway migration resource is missing");
            }
            return new InputStreamReader(stream, StandardCharsets.UTF_8);
        }
    }
}
