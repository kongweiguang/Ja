// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * 由 Solon composition root 持有的不可变数据库设置。
 *
 *
 * <p>runtime 只接受一个明确 SQLite 文件，不接受目录或旧位置；文件选择集中在此边界，
 * 防止持久化代码探测旧应用数据树。</p>
 */
public record DatabaseConfig(Path databasePath, Duration busyTimeout) {
    /**
     * SQLite 锁等待保持有界，避免停滞 peer 永久占住 Turn。
     */
    public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(5);

    /**
     * datasource 打开前校验唯一可变资源设置。
     */
    public DatabaseConfig {
        Objects.requireNonNull(databasePath, "databasePath");
        databasePath = databasePath.toAbsolutePath().normalize();
        if (databasePath.toString().isBlank() || databasePath.getFileName() == null) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "databasePath must name one concrete file");
        }
        Objects.requireNonNull(busyTimeout, "busyTimeout");
        if (busyTimeout.isZero() || busyTimeout.isNegative()
            || busyTimeout.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new StorageException(StorageException.Code.INVALID_CONFIGURATION,
                    "busyTimeout is outside the safe bound");
        }
    }

    /**
     * 使用生产锁预算，不引入第二条默认路径。
     */
    public static DatabaseConfig of(Path databasePath) {
        return new DatabaseConfig(databasePath, DEFAULT_BUSY_TIMEOUT);
    }

    /**
     * 保持同一个显式文件，仅允许测试调整锁压力预算。
     */
    public DatabaseConfig withBusyTimeout(Duration timeout) {
        return new DatabaseConfig(databasePath, timeout);
    }
}
