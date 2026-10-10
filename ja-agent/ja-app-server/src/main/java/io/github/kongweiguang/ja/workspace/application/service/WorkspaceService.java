// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.service;

import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspacePreparationPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRootChangePort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceTrustPort;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;

/**
 * 编排目录校验、稳定身份、持久化和跨域协作，不感知 RPC、Jackson、MyBatis 或配置实现。
 */
public final class WorkspaceService implements WorkspaceUseCase, WorkspaceRootChangePort {
    private static final int DEFAULT_MAX_OPEN_WORKSPACES = 128;
    private static final String SESSION_DISPLAY_NAME = "无项目对话";
    private final WorkspaceRepository repository;
    private final WorkspaceDirectoryPort directories;
    private final WorkspacePreparationPort preparation;
    private final WorkspaceTrustPort trustPort;
    private final WorkspacePolicy policy;
    private final Clock clock;
    private final WorkspaceAdmissionGate admissionGate = new WorkspaceAdmissionGate();
    private final int maximumOpenWorkspaces;
    private final Object stateLock = new Object();
    private final Map<String, OpenWorkspaceState> openWorkspaces = new LinkedHashMap<>();
    private final Set<Path> openingRoots = new HashSet<>();
    private final Object rootChangeLock = new Object();
    private final Map<String, List<WorkspaceRootChangePort.Change>> rootChanges = new HashMap<>();
    private final Map<String, Map<String, Long>> consumedRootChangeRevisions = new HashMap<>();

    /**
     * 使用生产容量上限构造唯一应用服务，所有外部副作用都通过窄端口注入。
     */
    public WorkspaceService(
            WorkspaceRepository repository,
            WorkspaceDirectoryPort directories,
            WorkspacePreparationPort preparation,
            WorkspaceTrustPort trustPort,
            WorkspacePolicy policy,
            Clock clock) {
        this(repository, directories, preparation, trustPort, policy, clock,
                DEFAULT_MAX_OPEN_WORKSPACES);
    }

    /**
     * 显式容量构造器用于在聚焦测试中验证边界，不改变生产默认值。
     */
    public WorkspaceService(
            WorkspaceRepository repository,
            WorkspaceDirectoryPort directories,
            WorkspacePreparationPort preparation,
            WorkspaceTrustPort trustPort,
            WorkspacePolicy policy,
            Clock clock,
            int maximumOpenWorkspaces) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.directories = Objects.requireNonNull(directories, "directories");
        this.preparation = Objects.requireNonNull(preparation, "preparation");
        this.trustPort = Objects.requireNonNull(trustPort, "trustPort");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumOpenWorkspaces < 1) {
            throw new IllegalArgumentException("maximumOpenWorkspaces must be positive");
        }
        this.maximumOpenWorkspaces = maximumOpenWorkspaces;
    }

    /**
     * 统一构造脱敏身份冲突，避免路径或持久化行内容进入 RPC。
     */
    private static WorkspaceFailure identityConflict() {
        return new WorkspaceFailure(WorkspaceFailure.Code.IDENTITY_CONFLICT,
                "workspace identity changed");
    }

    /**
     * 持久目录缺失统一映射为不可用，避免把注册表中残留的路径当作可信能力。
     */
    private static WorkspaceFailure unavailable(String message) {
        return new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, message);
    }

    /**
     * 先确认物理目录，再由应用派生身份，客户端无法注入或重放另一目录的 workspaceId。
     */
    @Override
    public Workspace openWorkspace(OpenWorkspace command) {
        Objects.requireNonNull(command, "command");
        WorkspaceDirectory directory = directories.verifyProjectDirectory(command.root());
        Workspace value = open(directory, policy.displayName(directory.root(), command.displayName()));
        prepareBestEffort(directory);
        return value;
    }

    /**
     * Java 生成 Thread identity 后创建独立目录；创建失败必须反馈，保持每个 Thread 的独立目录身份。
     */
    @Override
    public Workspace createSessionWorkspace(String threadId) {
        WorkspaceDirectory directory = directories.createSessionDirectory(threadId);
        Workspace.Kind kind = Workspace.Kind.SESSION;
        String workspaceId = policy.workspaceId(directory.root());
        Workspace persisted = repository.findByRoot(directory.root()).orElseGet(() -> repository.register(
                new Workspace.Registration(workspaceId, directory.root(), SESSION_DISPLAY_NAME,
                        policy.initialTrust(directory.kind()), kind, clock.instant())));
        verifyIdentity(persisted, directory);
        return persisted;
    }

    /**
     * 补偿 thread/create 的持久化失败只撤销无引用注册行；空目录保留，避免清理路径扩大目录删除权限。
     */
    @Override
    public void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision) {
        Workspace workspace = repository.findById(Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> unavailable("workspace is unavailable"));
        if (workspace.kind() != Workspace.Kind.SESSION || workspace.revision() != expectedRevision) {
            throw identityConflict();
        }
        repository.unregister(workspaceId, expectedRevision);
    }

    /**
     * 数据库 identity 是重开 session 根的唯一入口；轻型目录投影不留在进程绑定缓存。
     */
    @Override
    public synchronized Workspace openRegisteredWorkspace(String workspaceId) {
        Workspace persisted = repository.findById(Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> unavailable("workspace is unavailable"));
        if (persisted.kind() == Workspace.Kind.SESSION) {
            return verifyRegisteredDirectory(persisted);
        }
        try (WorkspaceAdmissionPort.Lease ignored = admissionGate.acquire(workspaceId)) {
            Workspace current = repository.findById(workspaceId)
                    .orElseThrow(() -> unavailable("workspace is unavailable"));
            WorkspaceDirectory directory = directories.verifyProjectDirectory(current.root());
            verifyIdentity(current, directory);
            boolean reserved = reserve(directory.root(), current.workspaceId());
            try {
                trustPort.synchronize(directory.root(), current.trust());
                bind(directory, current);
                prepareBestEffort(directory);
                return current;
            } finally {
                releaseReservation(directory.root(), reserved);
            }
        }
    }

    /**
     * session 目录不持有 OS 句柄；每次按持久 ID 做物理路径校验，避免会话切换积累无界绑定。
     */
    private Workspace verifyRegisteredDirectory(Workspace persisted) {
        WorkspaceDirectory directory = switch (persisted.kind()) {
            case SESSION -> {
                Path threadDirectoryName = persisted.root().getFileName();
                if (threadDirectoryName == null) {
                    throw identityConflict();
                }
                yield directories.verifySessionDirectory(threadDirectoryName.toString(), persisted.root());
            }
            case PROJECT -> throw identityConflict();
        };
        verifyIdentity(persisted, directory);
        return persisted;
    }

    /**
     * 将可选类型过滤留在 SQLite keyset 查询中，避免超出首屏的其它类型遮蔽结果；null 表示不过滤。
     */
    @Override
    public CursorPage<Workspace> listWorkspaces(String cursor, int limit, Workspace.Kind kind) {
        return repository.list(cursor, limit, kind);
    }

    /**
     * 持久化查询不隐式触发目录 IO，调用方需要能力时必须显式 requireOpenWorkspace。
     */
    @Override
    public Optional<Workspace> readWorkspace(String workspaceId) {
        return repository.findById(workspaceId);
    }

    /**
     * 展示名更新只依赖持久身份，根变更则经物理目录校验、准入屏障和事务忙碌检查后替换旧绑定。
     */
    @Override
    public synchronized Workspace updateWorkspace(UpdateWorkspace command) {
        Objects.requireNonNull(command, "command");
        Workspace persisted = repository.findById(command.workspaceId())
                .orElseThrow(() -> unavailable("workspace is unavailable"));
        if (persisted.kind() != Workspace.Kind.PROJECT) {
            throw identityConflict();
        }

        Path root = persisted.root();
        boolean rootChanged = false;
        if (command.cwd() != null) {
            WorkspaceDirectory directory = directories.verifyProjectDirectory(command.cwd());
            root = directory.root();
            rootChanged = !directories.samePhysicalDirectory(persisted.root(), root);
        }
        if (!rootChanged) {
            root = persisted.root();
        }
        String displayName = command.displayName() == null
                ? persisted.displayName() : policy.displayName(root, command.displayName());

        Workspace updated;
        if (rootChanged) {
            try (WorkspaceAdmissionPort.Lease ignored = admissionGate.acquireRebind(persisted.workspaceId())) {
                updated = repository.updateProject(persisted.workspaceId(), command.expectedRevision(), root,
                        displayName, true, clock.instant());
                recordRootChange(updated.workspaceId(), updated.revision(), persisted.root(), updated.root());
                synchronized (stateLock) {
                    openWorkspaces.remove(persisted.workspaceId());
                }
                // 先撤销旧根信任与 Watcher，再确认新根保持未信任；失败时 DB 仍是唯一权威，且旧缓存已解除绑定。
                trustPort.synchronize(persisted.root(), Workspace.Trust.UNTRUSTED);
                trustPort.synchronize(root, Workspace.Trust.UNTRUSTED);
            }
        } else {
            updated = repository.updateProject(persisted.workspaceId(), command.expectedRevision(), root,
                    displayName, false, clock.instant());
        }
        return updated;
    }

    /**
     * 合并指定 Thread 未读的连续重绑；每个 Thread 独立消费，进程内变更事实不进入 SQLite。
     */
    @Override
    public Optional<WorkspaceRootChangePort.Change> pending(String workspaceId, String threadId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        synchronized (rootChangeLock) {
            long consumedRevision = consumedRootChangeRevisions
                    .getOrDefault(workspaceId, Map.of()).getOrDefault(threadId, 0L);
            List<WorkspaceRootChangePort.Change> changes = rootChanges.getOrDefault(workspaceId, List.of());
            WorkspaceRootChangePort.Change first = null;
            WorkspaceRootChangePort.Change latest = null;
            for (WorkspaceRootChangePort.Change change : changes) {
                if (change.revision() > consumedRevision) {
                    if (first == null) {
                        first = change;
                    }
                    latest = change;
                }
            }
            return first == null ? Optional.empty() : Optional.of(new WorkspaceRootChangePort.Change(
                    latest.revision(), first.oldRoot(), latest.newRoot()));
        }
    }

    /**
     * 只推进至该 Thread 已见的精确重绑 revision，避免并发新变更被旧请求提前消费。
     */
    @Override
    public void consume(String workspaceId, String threadId, long revision) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(threadId, "threadId");
        if (revision < 1) {
            throw new IllegalArgumentException("invalid workspace root change revision");
        }
        synchronized (rootChangeLock) {
            List<WorkspaceRootChangePort.Change> changes = rootChanges.getOrDefault(workspaceId, List.of());
            boolean exists = changes.stream().anyMatch(change -> change.revision() == revision);
            if (!exists) {
                throw new IllegalStateException("workspace root change is no longer available");
            }
            Map<String, Long> consumed = consumedRootChangeRevisions.computeIfAbsent(
                    workspaceId, ignored -> new HashMap<>());
            consumed.merge(threadId, revision, Math::max);
        }
    }

    /**
     * 在 Workspace CAS 返回成功后记录旧/新根，使后续每个 Thread 都能独立看到同一变化。
     */
    private void recordRootChange(String workspaceId, long revision, Path oldRoot, Path newRoot) {
        WorkspaceRootChangePort.Change change = new WorkspaceRootChangePort.Change(revision, oldRoot, newRoot);
        synchronized (rootChangeLock) {
            List<WorkspaceRootChangePort.Change> changes = rootChanges.computeIfAbsent(
                    workspaceId, ignored -> new ArrayList<>());
            if (!changes.isEmpty() && changes.getLast().revision() >= revision) {
                throw new IllegalStateException("workspace root change revision must increase");
            }
            changes.add(change);
        }
    }

    /**
     * 把 Turn 与 Plan 的同步持久 admission 接到本服务持有的读侧锁。
     */
    @Override
    public WorkspaceAdmissionPort.Lease acquireAdmission(String workspaceId) {
        return admissionGate.acquire(workspaceId);
    }

    /**
     * PROJECT 必须在进程中显式打开；SESSION 则按持久 ID 每次重验固定目录，避免切换后累积缓存。
     */
    @Override
    public Workspace requireOpenWorkspace(String workspaceId) {
        synchronized (stateLock) {
            OpenWorkspaceState state = openWorkspaces.get(workspaceId);
            if (state != null) {
                return state.workspace();
            }
        }
        Workspace persisted = repository.findById(Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> unavailable("workspace is unavailable"));
        if (persisted.kind() == Workspace.Kind.PROJECT) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.WORKSPACE_NOT_OPEN, "workspace is not open");
        }
        return verifyRegisteredDirectory(persisted);
    }

    /**
     * 设置只访问持久登记的 PROJECT，并每次重验物理目录及稳定 ID，不进入活动绑定表。
     */
    @Override
    public Workspace requireSettingsWorkspace(String workspaceId) {
        Workspace persisted = repository.findById(Objects.requireNonNull(workspaceId, "workspaceId"))
                .orElseThrow(() -> unavailable("workspace is unavailable"));
        verifySettingsDirectory(persisted);
        return persisted;
    }

    /**
     * 串行完成校验、持久化和配置同步，避免较早的 trust 在并发更新后反向覆盖配置 owner。
     * 同步失败不回滚已提交事实，相同目标状态可再次提交并同步。
     */
    @Override
    public synchronized Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(trust, "trust");

        Workspace persisted = repository.findById(workspaceId)
                .orElseThrow(() -> unavailable("workspace is unavailable"));

        if (persisted.kind() != Workspace.Kind.PROJECT) {
            OpenWorkspaceState prior = requireOpenState(workspaceId);
            verifyIdentity(persisted, prior.directory());
            if (trust != Workspace.Trust.TRUSTED) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.TRUST_CONFLICT,
                        "non-project workspace trust is fixed");
            }
            Workspace updated = repository.updateTrust(workspaceId, trust);
            verifyIdentity(updated, prior.directory());
            replaceOpenState(prior, updated);
            return updated;
        }

        WorkspaceDirectory directory = verifySettingsDirectory(persisted);
        OpenWorkspaceState prior;
        synchronized (stateLock) {
            prior = openWorkspaces.get(workspaceId);
        }
        if (prior != null) {
            if (prior.directory().kind() != WorkspaceDirectory.Kind.PROJECT) {
                throw identityConflict();
            }
            verifyIdentity(persisted, prior.directory());
        }

        Workspace updated = repository.updateTrust(workspaceId, trust);
        verifyIdentity(updated, directory);
        if (prior != null) {
            replaceOpenState(prior, updated);
        }
        trustPort.synchronize(directory.root(), trust);
        if (prior != null) {
            prepareBestEffort(directory);
        }
        return updated;
    }

    /**
     * Repository CAS 成功后才删除进程绑定，失败时仍保留可重试的物理能力。
     */
    @Override
    public void unregisterWorkspace(String workspaceId, long expectedRevision) {
        OpenWorkspaceState prior = requireOpenState(workspaceId);
        repository.unregister(workspaceId, expectedRevision);
        synchronized (stateLock) {
            openWorkspaces.remove(workspaceId, prior);
        }
    }

    /**
     * 复制状态快照后再执行外部预热，避免慢 IO 持有进程内注册锁。
     */
    @Override
    public void refreshPreparedWorkspaces() {
        List<OpenWorkspaceState> snapshot;
        synchronized (stateLock) {
            snapshot = List.copyOf(openWorkspaces.values());
        }
        snapshot.stream()
                .map(OpenWorkspaceState::directory)
                .filter(directory -> directory.kind() == WorkspaceDirectory.Kind.PROJECT)
                .forEach(this::prepareBestEffort);
    }


    /**
     * 复用持久化事实或创建全新事实，并在绑定前验证稳定身份与物理根不变。
     */
    private synchronized Workspace open(WorkspaceDirectory directory, String displayName) {
        Workspace existing = repository.findByRoot(directory.root()).orElse(null);
        String workspaceId = existing == null
                ? directory.kind() == WorkspaceDirectory.Kind.PROJECT
                    ? policy.newProjectWorkspaceId() : policy.workspaceId(directory.root())
                : existing.workspaceId();
        boolean reserved = reserve(directory.root(), workspaceId);
        try {
            Workspace.Kind kind = Workspace.Kind.valueOf(directory.kind().name());
            Workspace value = existing != null ? existing : repository.register(new Workspace.Registration(
                            workspaceId,
                            directory.root(),
                            displayName,
                            policy.initialTrust(directory.kind()),
                            kind,
                            clock.instant()));
            verifyIdentity(value, directory);
            if (directory.kind() != WorkspaceDirectory.Kind.PROJECT
                && value.trust() != Workspace.Trust.TRUSTED) {
                value = repository.updateTrust(value.workspaceId(), Workspace.Trust.TRUSTED);
                verifyIdentity(value, directory);
            }
            bind(directory, value);
            return value;
        } finally {
            releaseReservation(directory.root(), reserved);
        }
    }

    /**
     * 在持久化副作用前预留唯一根容量；相同根的并发幂等打开共享一个配额，
     * 不同根则不能通过慢 Repository 竞争突破上限。
     */
    private boolean reserve(Path root, String workspaceId) {
        synchronized (stateLock) {
            OpenWorkspaceState alreadyOpen = openWorkspaces.get(workspaceId);
            if (alreadyOpen != null) {
                if (!directories.samePhysicalDirectory(alreadyOpen.directory().root(), root)) {
                    throw identityConflict();
                }
                return false;
            }
            if (openingRoots.contains(root)) {
                return false;
            }
            if (openWorkspaces.size() + openingRoots.size() >= maximumOpenWorkspaces) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.CAPACITY_EXHAUSTED,
                        "workspace capacity is exhausted");
            }
            openingRoots.add(root);
            return true;
        }
    }

    /**
     * 只释放本次调用实际取得的配额，避免同根并发调用提前清除他人的预留。
     */
    private void releaseReservation(Path root, boolean reserved) {
        if (!reserved) {
            return;
        }
        synchronized (stateLock) {
            openingRoots.remove(root);
        }
    }

    /**
     * 在一个短临界区内执行容量与身份冲突检查，避免并发打开突破上限。
     */
    private void bind(WorkspaceDirectory directory, Workspace workspace) {
        OpenWorkspaceState candidate = new OpenWorkspaceState(directory, workspace);
        synchronized (stateLock) {
            OpenWorkspaceState prior = openWorkspaces.get(workspace.workspaceId());
            if (prior != null) {
                if (!directories.samePhysicalDirectory(prior.directory().root(), directory.root())) {
                    throw identityConflict();
                }
                openWorkspaces.put(workspace.workspaceId(), candidate);
                return;
            }
            if (openWorkspaces.size() >= maximumOpenWorkspaces) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.CAPACITY_EXHAUSTED,
                        "workspace capacity is exhausted");
            }
            boolean rootBoundToAnotherIdentity = openWorkspaces.values().stream()
                    .anyMatch(state -> directories.samePhysicalDirectory(
                            state.directory().root(), directory.root()));
            if (rootBoundToAnotherIdentity) {
                throw identityConflict();
            }
            openWorkspaces.put(workspace.workspaceId(), candidate);
        }
    }

    /**
     * 将 SESSION 派生身份或 PROJECT 持久身份与已验证物理根对齐，拒绝旧数据或冲突行。
     */
    private void verifyIdentity(Workspace workspace, WorkspaceDirectory directory) {
        boolean sessionIdentityMatches = directory.kind() != WorkspaceDirectory.Kind.SESSION
                || policy.workspaceId(directory.root()).equals(workspace.workspaceId());
        if (!sessionIdentityMatches
            || !directories.samePhysicalDirectory(directory.root(), workspace.root())
            || !Workspace.Kind.valueOf(directory.kind().name()).equals(workspace.kind())) {
            throw identityConflict();
        }
    }

    /**
     * 设置和信任变更都只接受持久登记的 PROJECT，并在任何写入前重新验证物理目录与派生 ID。
     */
    private WorkspaceDirectory verifySettingsDirectory(Workspace persisted) {
        if (persisted.kind() != Workspace.Kind.PROJECT) {
            throw identityConflict();
        }
        WorkspaceDirectory directory = directories.verifyProjectDirectory(persisted.root());
        verifyIdentity(persisted, directory);
        return directory;
    }

    /**
     * 读取完整绑定状态，使信任与注销操作同时获得目录种类和权威 workspace。
     */
    private OpenWorkspaceState requireOpenState(String workspaceId) {
        synchronized (stateLock) {
            OpenWorkspaceState state = openWorkspaces.get(workspaceId);
            if (state == null) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.WORKSPACE_NOT_OPEN,
                        "workspace is not open");
            }
            return state;
        }
    }

    /**
     * 只在绑定仍等于调用前快照时替换，避免覆盖并发注销或重新绑定。
     */
    private void replaceOpenState(OpenWorkspaceState prior, Workspace updated) {
        synchronized (stateLock) {
            openWorkspaces.replace(updated.workspaceId(), prior,
                    new OpenWorkspaceState(prior.directory(), updated));
        }
    }

    /**
     * 项目预热是性能优化而非打开门禁，失败保留到 Turn admission 的权威检查处理。
     */
    private void prepareBestEffort(WorkspaceDirectory directory) {
        try {
            preparation.prepare(directory.root());
        } catch (RuntimeException unavailable) {
            Objects.requireNonNull(unavailable, "unavailable");
            // 打开、设置与列表仍可用；Turn admission 会重新获取配置代际并 fail closed。
        }
    }

    /**
     * 将已验证目录与持久化投影绑定为不可变进程状态。
     *
     * @param directory 目录
     * @param workspace 工作区
     */
    private record OpenWorkspaceState(WorkspaceDirectory directory,
                                      Workspace workspace) {
        /**
         * 拒绝缺失分量，防止并发状态表中出现半绑定记录。
         */
        private OpenWorkspaceState {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(workspace, "workspace");
        }
    }
}
