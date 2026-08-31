// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.database;

import io.github.kongweiguang.ja.foundation.error.StorageException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单一 Ja 数据库文件的进程所有权。
 *
 *
 * <p>SQLite 只协调 page lock，不能阻止两个 sidecar 同时认为自己拥有 runtime 生命周期。
 * 因此必须在 Flyway 或 MyBatis 打开文件前获取相邻 lease，并在所有数据库消费者停止后释放。</p>
 */
final class DatabaseLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    /**
     * 将操作系统 lock 与 channel 作为同一个关闭单元持有。
     */
    private DatabaseLease(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 获取唯一 owner lease，不探测或迁移其它文件。
     */
    static DatabaseLease acquire(Path databasePath) {
        Path lockPath = databasePath.resolveSibling(databasePath.getFileName() + ".lock");
        try {
            Path parent = lockPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    throw new StorageException(StorageException.Code.INSTANCE_LOCKED,
                            "Ja database is already owned by another instance");
                }
                return new DatabaseLease(channel, lock);
            } catch (OverlappingFileLockException conflict) {
                channel.close();
                throw new StorageException(StorageException.Code.INSTANCE_LOCKED,
                        "Ja database is already owned by this process", conflict);
            } catch (IOException | RuntimeException failure) {
                channel.close();
                throw failure;
            }
        } catch (StorageException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.IO,
                    "cannot acquire Ja database lease", failure);
        }
    }

    /**
     * 幂等释放 lease，操作系统仍是进程异常退出时的最终保护。
     */
    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // 即使 lock.release 失败，关闭 channel 仍会让 Windows 释放进程锁。
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // close 返回后此对象不再允许任何后继 owner 进入。
        }
    }
}
