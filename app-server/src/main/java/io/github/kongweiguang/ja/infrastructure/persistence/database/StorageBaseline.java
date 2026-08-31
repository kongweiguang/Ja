// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 新存储代际的唯一准入门，负责验证数据目录标记并在首次建库成功后原子发布标记。
 *
 * <p>标记包含唯一 V1 migration 的 SHA-256；修改 schema 但遗漏提升存储代际时会在启动前
 * 失败。该边界从不发现、迁移或导入旧目录。</p>
 */
final class StorageBaseline {
    static final String MARKER_FILE = "storage-baseline.json";
    private static final String MARKER_RESOURCE = "db/storage-baseline.json";
    private static final String MIGRATION_RESOURCE = "db/migration/V1__kernel.sql";

    /**
     * 在创建 lease 或 SQLite 文件前判定目录代际，避免缺失标记的旧目录被任何数据库代码触碰。
     */
    static Admission prepare(Path databasePath) {
        Path directory = databasePath.getParent();
        if (directory == null) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "database directory is unavailable");
        }
        try {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                        "database directory is not a concrete directory");
            }
            byte[] expected = expectedMarker();
            Path marker = directory.resolve(MARKER_FILE);
            if (isEmpty(directory)) {
                return new Admission(marker, expected, true);
            }
            verifyMarker(marker, expected);
            return new Admission(marker, expected, false);
        } catch (StorageException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.IO,
                    "cannot inspect Ja storage baseline", failure);
        }
    }

    /**
     * 读取受版本控制的 marker，并校验其 migration 摘要与实际 V1 完全一致；仅归一化文本资源的
     * CRLF，运行时落盘始终使用同一份 LF canonical bytes。
     */
    private static byte[] expectedMarker() throws IOException {
        byte[] migration = resource(MIGRATION_RESOURCE);
        String digest;
        try {
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration));
        } catch (NoSuchAlgorithmException impossible) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "SHA-256 is unavailable", impossible);
        }
        byte[] generated = """
                {
                  "format": "ja-storage-baseline",
                  "generation": 1,
                  "migrationVersion": "1",
                  "migration": "V1__kernel.sql",
                  "migrationSha256": "%s"
                }
                """.formatted(digest).getBytes(StandardCharsets.UTF_8);
        byte[] packaged = resource(MARKER_RESOURCE);
        byte[] normalizedPackaged = new String(packaged, StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(generated, normalizedPackaged)) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "packaged storage baseline does not match V1 migration");
        }
        return generated;
    }

    /**
     * 只读取两个固定 classpath 资源，Native Image 不执行目录扫描。
     */
    private static byte[] resource(String name) throws IOException {
        try (InputStream input = StorageBaseline.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                        "required storage baseline resource is missing");
            }
            return input.readAllBytes();
        }
    }

    /**
     * 目录枚举只判定是否为空，不接受任何旧文件名作为兼容入口。
     */
    private static boolean isEmpty(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * marker 必须是普通文件且字节完全一致，禁止宽松 JSON 或未知字段掩盖代际漂移。
     */
    private static void verifyMarker(Path marker, byte[] expected) throws IOException {
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new StorageException(StorageException.Code.FRESH_SCHEMA_REQUIRED,
                    "storage baseline marker is required");
        }
        if (Files.size(marker) != expected.length) {
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                    "storage baseline marker does not match this runtime");
        }
        byte[] actual = Files.readAllBytes(marker);
        if (!MessageDigest.isEqual(actual, expected)) {
            throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                    "storage baseline marker does not match this runtime");
        }
    }

    /**
     * 保存一次启动准入决定；首次建库仅在 Flyway 成功后发布，已有目录在迁移前后都复验。
     */
    record Admission(Path marker, byte[] expected, boolean fresh) {
        /**
         * 防御性复制 marker 内容，避免准入之后被调用方改变已验证代际。
         */
        Admission {
            marker = marker.toAbsolutePath().normalize();
            Objects.requireNonNull(marker.getParent(), "storage baseline marker parent");
            expected = expected.clone();
        }

        /**
         * 对外只提供副本，内部发布始终使用构造时冻结的 marker 内容。
         */
        @Override
        public byte[] expected() {
            return expected.clone();
        }

        /**
         * lease 获取后复验，关闭 marker 校验与数据库打开之间的替换窗口。
         */
        void verifyAfterLease() throws IOException {
            if (fresh) {
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                            "storage baseline changed during startup");
                }
                return;
            }
            verifyMarker(marker, expected);
        }

        /**
         * V1 成功后才使用同目录临时文件、强制刷盘和原子 rename 发布 marker；不支持原子移动的
         * 文件系统直接拒绝，避免进程崩溃留下可被误认的半个标记。
         */
        void complete() throws IOException {
            if (!fresh) {
                verifyMarker(marker, expected);
                return;
            }
            Path directory = marker.getParent();
            if (directory == null) {
                throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                        "storage baseline directory is unavailable");
            }
            Path staging = Files.createTempFile(directory, ".storage-baseline-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(expected);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new StorageException(StorageException.Code.STORAGE_CONFLICT,
                            "storage baseline changed during startup");
                }
                Files.move(staging, marker, StandardCopyOption.ATOMIC_MOVE);
                verifyMarker(marker, expected);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new StorageException(StorageException.Code.IO,
                        "storage baseline requires atomic publication", failure);
            } finally {
                Files.deleteIfExists(staging);
            }
        }
    }

    /**
     * 纯静态策略不允许被实例化，确保所有打开路径都经过同一个准入入口。
     */
    private StorageBaseline() {
    }
}
