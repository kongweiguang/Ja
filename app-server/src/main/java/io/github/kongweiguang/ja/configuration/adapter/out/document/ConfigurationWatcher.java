// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.adapter.out.document;

import io.github.kongweiguang.ja.configuration.adapter.out.generation.ConfigurationRuntimeState;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ConfigurationWatcher 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
 */
public final class ConfigurationWatcher implements AutoCloseable {
    private static final int MAX_WATCHED_WORKSPACES = 1024;

    private final Path homeDirectory;
    private final Path userConfigPath;
    private final Path trustPath;
    private final Supplier<Map<String, Boolean>> trustedWorkspaceLoader;
    private final Runnable invalidateAllGenerations;
    private final Consumer<Path> invalidateGeneration;
    private final Consumer<List<ConfigurationRuntimeState.ConfigChanged>> publishChanges;
    private final Map<WatchKey, WatchRegistration> watchRegistrations = new HashMap<>();
    private final Map<Path, WatchKey> watchedDirectories = new HashMap<>();
    private WatchService watchService;
    private Thread watchThread;
    private int watchedWorkspaces;
    private boolean closed;

    /**
     * ConfigurationWatcher 集中维护 secret 与 credential 的脱敏边界，并确保敏感缓冲区按所有权生命周期清理。
     */
    public ConfigurationWatcher(Path homeDirectory, Path userConfigPath, Path trustPath,
                         Supplier<Map<String, Boolean>> trustedWorkspaceLoader,
                         Runnable invalidateAllGenerations,
                         Consumer<Path> invalidateGeneration,
                         Consumer<List<ConfigurationRuntimeState.ConfigChanged>> publishChanges) {
        this.homeDirectory = Objects.requireNonNull(homeDirectory, "homeDirectory");
        this.userConfigPath = Objects.requireNonNull(userConfigPath, "userConfigPath");
        this.trustPath = Objects.requireNonNull(trustPath, "trustPath");
        this.trustedWorkspaceLoader = Objects.requireNonNull(trustedWorkspaceLoader,
                "trustedWorkspaceLoader");
        this.invalidateAllGenerations = Objects.requireNonNull(invalidateAllGenerations,
                "invalidateAllGenerations");
        this.invalidateGeneration = Objects.requireNonNull(invalidateGeneration,
                "invalidateGeneration");
        this.publishChanges = Objects.requireNonNull(publishChanges, "publishChanges");
    }

    /**
     * start 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    public synchronized void start() {
        if (closed || watchService != null) return;
        try {
            Files.createDirectories(homeDirectory);
            watchService = FileSystems.getDefault().newWatchService();
            registerDirectory(homeDirectory, null, true, false);
            watchThread = new Thread(this::watchLoop, "ja-config-watch");
            watchThread.setDaemon(true);
            watchThread.start();
            registerExistingTrustedWorkspaceWatches();
        } catch (IOException failure) {
            closeWatchServiceQuietly();
        }
    }

    /**
     * 只为已信任工作区注册根目录与 .ja 目录，并以规范 cwd 关联后续代际失效。
     */
    public synchronized void registerWorkspaceWatches(Path canonical) {
        if (watchService == null || closed) return;
        if (!watchedDirectories.containsKey(canonical) && watchedWorkspaces < MAX_WATCHED_WORKSPACES
            && registerDirectory(canonical, canonical, false, true)) {
            watchedWorkspaces++;
        }
        registerDirectory(canonical.resolve(".ja"), canonical, false, false);
    }

    /**
     * unregisterWorkspaceWatches 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    public synchronized void unregisterWorkspaceWatches(Path canonical) {
        List<WatchKey> removed = watchRegistrations.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue().canonicalCwd(), canonical))
                .map(Map.Entry::getKey)
                .toList();
        for (WatchKey watchKey : removed) removeWatchRegistration(watchKey);
    }

    /**
     * registerExistingTrustedWorkspaceWatches 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    private synchronized void registerExistingTrustedWorkspaceWatches() {
        try {
            Set<Path> trustedPaths = new HashSet<>();
            for (String value : trustedWorkspaceLoader.get().keySet()) {
                try {
                    Path workspace = Path.of(value).toAbsolutePath().normalize();
                    trustedPaths.add(workspace);
                    if (Files.isDirectory(workspace)) registerWorkspaceWatches(workspace);
                } catch (RuntimeException ignored) {
                    // 信任登记中单个无法解析的历史路径不应阻断其他工作区恢复监听。
                }
            }
            List<WatchKey> stale = watchRegistrations.entrySet().stream()
                    .filter(entry -> !entry.getValue().home()
                                     && !trustedPaths.contains(entry.getValue().canonicalCwd()))
                    .map(Map.Entry::getKey)
                    .toList();
            stale.forEach(this::removeWatchRegistration);
        } catch (RuntimeException ignored) {
            // 信任登记加载失败时保留 home 监听，不根据不完整快照注册项目目录。
        }
    }

    /**
     * registerDirectory 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    private boolean registerDirectory(Path directory, Path canonicalCwd,
                                      boolean home, boolean workspaceRoot) {
        if (watchService == null || closed || watchedDirectories.containsKey(directory)
            || !Files.isDirectory(directory)) return false;
        try {
            WatchKey key = directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watchRegistrations.put(key, new WatchRegistration(directory, canonicalCwd, home,
                    workspaceRoot));
            watchedDirectories.put(directory, key);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * 在独立线程汇总 WatchKey 事件，先更新注册状态再在锁外触发配置回调。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void watchLoop() {
        while (true) {
            WatchService service;
            synchronized (this) {
                if (closed || watchService == null) return;
                service = watchService;
            }
            final WatchKey key;
            try {
                key = service.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException stopped) {
                return;
            }
            processWatchKey(key);
        }
    }

    /**
     * 把一批 WatchKey 转成有序失效意图和脱敏事件。
     *
     * <p>服务回调必须在释放 Watcher 监视器后执行，避免关闭或 generation 轮换形成
     * Watcher 到服务的反向锁顺序；空 fileName 事件被忽略，不能因平台异常事件终止线程。</p>
     */
    private void processWatchKey(WatchKey key) {
        List<ConfigurationRuntimeState.ConfigChanged> changes = new ArrayList<>();
        List<Path> invalidations = new ArrayList<>();
        boolean invalidateAll = false;
        boolean trustRegistryChanged = false;
        synchronized (this) {
            WatchRegistration registration = watchRegistrations.get(key);
            if (registration == null || closed) return;
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW
                    || !(event.context() instanceof Path relative)) {
                    invalidateAll = true;
                    changes.add(new ConfigurationRuntimeState.ConfigChanged("unknown", null,
                            ConfigurationStore.MISSING_VERSION));
                    continue;
                }
                Path fileName = relative.getFileName();
                if (fileName == null) continue;
                String name = fileName.toString();
                if (registration.home()) {
                    if (name.equalsIgnoreCase("config.toml")) {
                        invalidateAll = true;
                        changes.add(new ConfigurationRuntimeState.ConfigChanged("user", null,
                                ConfigurationStore.version(userConfigPath)));
                    } else if (name.equalsIgnoreCase("auth.json")) {
                        invalidateAll = true;
                        changes.add(new ConfigurationRuntimeState.ConfigChanged("credential", null,
                                ConfigurationStore.secretVersion(homeDirectory.resolve("auth.json"))));
                    } else if (name.equalsIgnoreCase("trusted-workspaces.json")) {
                        invalidateAll = true;
                        trustRegistryChanged = true;
                        changes.add(new ConfigurationRuntimeState.ConfigChanged("trust", null,
                                ConfigurationStore.version(trustPath)));
                    }
                } else if (registration.workspaceRoot()) {
                    if (name.equalsIgnoreCase(".ja")) {
                        registerDirectory(registration.directory().resolve(".ja"),
                                registration.canonicalCwd(), false, false);
                        invalidations.add(Path.of(registration.directory().toString()));
                        changes.add(new ConfigurationRuntimeState.ConfigChanged("project",
                                registration.canonicalCwd(),
                                ConfigurationStore.version(registration.directory().resolve(".ja")
                                        .resolve("config.toml"))));
                    }
                } else if (name.equalsIgnoreCase("config.toml")) {
                    invalidations.add(registration.directory().getParent());
                    changes.add(new ConfigurationRuntimeState.ConfigChanged("project",
                            registration.canonicalCwd(),
                            ConfigurationStore.version(registration.directory().resolve("config.toml"))));
                }
            }
            if (!key.reset()) removeWatchRegistration(key);
        }
        if (invalidateAll) invalidateAllGenerations.run();
        invalidations.forEach(invalidateGeneration);
        if (trustRegistryChanged) registerExistingTrustedWorkspaceWatches();
        publishChanges.accept(changes);
    }

    /**
     * removeWatchRegistration 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    private void removeWatchRegistration(WatchKey key) {
        WatchRegistration registration = watchRegistrations.remove(key);
        if (registration == null) return;
        watchedDirectories.remove(registration.directory());
        if (registration.workspaceRoot()) watchedWorkspaces = Math.max(0, watchedWorkspaces - 1);
        key.cancel();
    }

    /**
     * 从启动失败路径释放尚未进入服务所有权的 WatchService，同时清空注册计数。
     */
    @SuppressWarnings("PMD.CloseResource")
    private void closeWatchServiceQuietly() {
        WatchService service;
        Thread thread;
        synchronized (this) {
            service = watchService;
            watchService = null;
            thread = watchThread;
            watchThread = null;
            for (WatchKey key : List.copyOf(watchRegistrations.keySet())) key.cancel();
            watchRegistrations.clear();
            watchedDirectories.clear();
            watchedWorkspaces = 0;
        }
        if (thread != null) thread.interrupt();
        if (service != null) {
            try {
                service.close();
            } catch (IOException ignored) {
                // WatchService 关闭属于最佳努力清理；状态已标记关闭，不能因二次 IO 失败重开生命周期。
            }
        }
    }

    /**
     * close 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        closeWatchServiceQuietly();
    }

    /**
     * WatchRegistration 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
     */
    private record WatchRegistration(Path directory, Path canonicalCwd, boolean home,
                                     boolean workspaceRoot) {
        /**
         * toString 隔离 Watcher 失效与回调顺序，避免关闭、轮换和通知之间形成锁反转。
         */
        @Override
        public String toString() {
            return "WatchRegistration[home=" + home + ", workspace="
                   + (canonicalCwd == null ? "none" : "present") + "]";
        }
    }
}
