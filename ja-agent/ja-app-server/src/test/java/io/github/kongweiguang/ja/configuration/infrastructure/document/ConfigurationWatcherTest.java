// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.infrastructure.document;

import io.github.kongweiguang.ja.configuration.application.service.ConfigurationMutationCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 Watcher 在配置路径协调与关闭并发时不启动迟到读取或发布。 */
final class ConfigurationWatcherTest {
    @TempDir
    Path temporary;

    /** 关闭发生在版本读取等待路径条带期间时，释放条带后不得再读取 trust 或触发任何回调。 */
    @Test
    void closeDuringVersionLockWaitSuppressesTrustReloadAndCallbacks() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path userConfig = home.resolve("config.toml");
        Path trustPath = home.resolve("trusted-workspaces.json");
        AtomicInteger trustLoads = new AtomicInteger();
        AtomicInteger allInvalidations = new AtomicInteger();
        AtomicInteger workspaceInvalidations = new AtomicInteger();
        AtomicInteger publications = new AtomicInteger();
        ConfigurationWatcher watcher = new ConfigurationWatcher(home, userConfig, trustPath,
                () -> {
                    trustLoads.incrementAndGet();
                    return Map.of();
                }, allInvalidations::incrementAndGet, ignored -> workspaceInvalidations.incrementAndGet(),
                ignored -> publications.incrementAndGet());
        watcher.start();
        int initialTrustLoads = trustLoads.get();
        CountDownLatch trustFileWritten = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        ExecutorService mutationExecutor = Executors.newSingleThreadExecutor();

        Future<?> mutation = mutationExecutor.submit(() ->
                ConfigurationMutationCoordinator.execute(trustPath, () -> {
                    try {
                        Files.writeString(trustPath, "{\"trusted\":[]}");
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                    trustFileWritten.countDown();
                    awaitLatch(releaseMutation);
                    return null;
                }));
        try {
            assertTrue(trustFileWritten.await(5, TimeUnit.SECONDS));
            Thread settleThread = awaitVersionLookupWaitingForPathLock();

            watcher.close();
            releaseMutation.countDown();
            mutation.get(5, TimeUnit.SECONDS);
            settleThread.join(TimeUnit.SECONDS.toMillis(5));
            assertFalse(settleThread.isAlive());

            assertEquals(initialTrustLoads, trustLoads.get());
            assertEquals(0, allInvalidations.get());
            assertEquals(0, workspaceInvalidations.get());
            assertEquals(0, publications.get());
        } finally {
            releaseMutation.countDown();
            watcher.close();
            mutationExecutor.shutdownNow();
        }
    }

    /** trust loader 阻塞期间允许 close 取得 Watcher 监视器，避免 trust → watcher 反向锁序。 */
    @Test
    void trustReloadDoesNotHoldWatcherMonitor() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("blocked-loader-home"));
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        ConfigurationWatcher watcher = new ConfigurationWatcher(home, home.resolve("config.toml"),
                home.resolve("trusted-workspaces.json"), () -> {
                    loaderStarted.countDown();
                    awaitLatch(releaseLoader);
                    return Map.of();
                }, () -> { }, ignored -> { }, ignored -> { });
        ExecutorService starter = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        Future<?> start = starter.submit(watcher::start);
        try {
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            Future<?> close = closer.submit(() -> {
                watcher.close();
                closed.countDown();
            });
            assertTrue(closed.await(2, TimeUnit.SECONDS),
                    "close must not wait for a trust load while the watcher monitor is held");

            releaseLoader.countDown();
            close.get(5, TimeUnit.SECONDS);
            start.get(5, TimeUnit.SECONDS);
        } finally {
            releaseLoader.countDown();
            watcher.close();
            starter.shutdownNow();
            closer.shutdownNow();
        }
    }

    /** 监视器外加载的旧 trust 快照不得撤销加载期间显式注册的新工作区监听。 */
    @Test
    void staleTrustSnapshotDoesNotRemoveConcurrentWorkspaceRegistration() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("snapshot-home"));
        Path workspace = Files.createDirectories(temporary.resolve("snapshot-workspace"));
        CountDownLatch loaderStarted = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        CountDownLatch projectChange = new CountDownLatch(1);
        ConfigurationWatcher watcher = new ConfigurationWatcher(home, home.resolve("config.toml"),
                home.resolve("trusted-workspaces.json"), () -> {
                    loaderStarted.countDown();
                    awaitLatch(releaseLoader);
                    return Map.of();
                }, () -> { }, ignored -> { }, changes -> {
                    if (changes.stream().anyMatch(change -> "project".equals(change.scope()))) {
                        projectChange.countDown();
                    }
                });
        ExecutorService starter = Executors.newSingleThreadExecutor();
        Future<?> start = starter.submit(watcher::start);
        try {
            assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
            watcher.registerWorkspaceWatches(workspace);
            releaseLoader.countDown();
            start.get(5, TimeUnit.SECONDS);

            Files.createDirectory(workspace.resolve(".ja"));
            Files.writeString(workspace.resolve(".ja").resolve("config.toml"),
                    "schema_version = 2\nconfig_revision = 1\n");
            assertTrue(projectChange.await(5, TimeUnit.SECONDS));
        } finally {
            releaseLoader.countDown();
            watcher.close();
            starter.shutdownNow();
        }
    }

    /** 等到 settle 线程确实阻塞在 Watcher 的版本读取帧，避免只验证了尚未启动的任务。 */
    private static Thread awaitVersionLookupWaitingForPathLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread waiting = Thread.getAllStackTraces().entrySet().stream().filter(entry ->
                    "ja-config-settle".equals(entry.getKey().getName())
                    && entry.getKey().getState() == Thread.State.WAITING
                    && java.util.Arrays.stream(entry.getValue()).anyMatch(frame ->
                            ConfigurationWatcher.class.getName().equals(frame.getClassName())
                            && "configurationVersion".equals(frame.getMethodName())))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (waiting != null) {
                return waiting;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Watcher did not reach its coordinated version lookup");
    }

    /** 测试门闩只用于固定竞争时序；中断继续传播为测试失败并保留线程中断状态。 */
    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", interrupted);
        }
    }
}
