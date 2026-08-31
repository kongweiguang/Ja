// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application;

import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePreparationPort;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceTrustPort;

import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 编排目录校验、稳定身份、持久化和跨域协作，不感知 RPC、Jackson、MyBatis 或配置实现。
 */
public final class WorkspaceService implements WorkspaceUseCase {
    private static final int DEFAULT_MAX_OPEN_WORKSPACES = 128;
    private static final String GENERAL_DISPLAY_NAME = "无项目";
    private final WorkspaceRepository repository;
    private final WorkspaceDirectoryPort directories;
    private final WorkspacePreparationPort preparation;
    private final WorkspaceTrustPort trustPort;
    private final WorkspacePolicy policy;
    private final Clock clock;
    private final int maximumOpenWorkspaces;
    private final Object stateLock = new Object();
    private final Map<String, OpenWorkspaceState> openWorkspaces = new LinkedHashMap<>();
    private final Set<Path> openingRoots = new HashSet<>();

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
     * 通用目录、展示名与信任均由 Java 固定，避免客户端伪造无项目身份。
     */
    @Override
    public Workspace openGeneralWorkspace() {
        WorkspaceDirectory directory = directories.ensureGeneralDirectory();
        return open(directory, GENERAL_DISPLAY_NAME);
    }

    /**
     * 分页和 opaque cursor 由持久化端口负责，application 不复制编码规则。
     */
    @Override
    public CursorPage<Workspace> listWorkspaces(String cursor, int limit) {
        return repository.list(cursor, limit);
    }

    /**
     * 持久化查询不隐式触发目录 IO，调用方需要能力时必须显式 requireOpenWorkspace。
     */
    @Override
    public Optional<Workspace> readWorkspace(String workspaceId) {
        return repository.findById(workspaceId);
    }

    /**
     * 只返回本进程已经验证并绑定的物理目录能力，重启后必须重新打开。
     */
    @Override
    public Workspace requireOpenWorkspace(String workspaceId) {
        synchronized (stateLock) {
            OpenWorkspaceState state = openWorkspaces.get(workspaceId);
            if (state == null) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.WORKSPACE_NOT_OPEN,
                        "workspace is not open");
            }
            return state.workspace();
        }
    }

    /**
     * 持久化事实先提交，再同步配置 owner；同步失败允许按相同目标状态安全重试。
     */
    @Override
    public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
        Objects.requireNonNull(trust, "trust");
        OpenWorkspaceState prior = requireOpenState(workspaceId);
        if (prior.directory().kind() == WorkspaceDirectory.Kind.GENERAL
            && trust != Workspace.Trust.TRUSTED) {
            throw new WorkspaceFailure(WorkspaceFailure.Code.TRUST_CONFLICT,
                    "general workspace trust is fixed");
        }
        Workspace updated = repository.updateTrust(workspaceId, trust);
        verifyIdentity(updated, prior.directory());
        replaceOpenState(prior, updated);
        if (prior.directory().kind() == WorkspaceDirectory.Kind.PROJECT) {
            trustPort.synchronize(prior.directory().root(), trust);
            prepareBestEffort(prior.directory());
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
     * 委派无副作用的规范路径比较，路由判断不会意外创建通用目录。
     */
    @Override
    public boolean isGeneralWorkspace(Path root) {
        return directories.isGeneralDirectory(root);
    }

    /**
     * 复用持久化事实或创建全新事实，并在绑定前验证稳定身份与物理根不变。
     */
    private Workspace open(WorkspaceDirectory directory, String displayName) {
        String workspaceId = policy.workspaceId(directory.root());
        boolean reserved = reserve(directory.root(), workspaceId);
        try {
            Workspace value = repository.findByRoot(directory.root())
                    .orElseGet(() -> repository.register(new Workspace.Registration(
                            workspaceId,
                            directory.root(),
                            displayName,
                            policy.initialTrust(directory.kind()),
                            clock.instant())));
            verifyIdentity(value, directory);
            if (directory.kind() == WorkspaceDirectory.Kind.GENERAL
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
                if (!alreadyOpen.directory().root().equals(root)) {
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
                if (!prior.directory().root().equals(directory.root())) {
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
                    .anyMatch(state -> state.directory().root().equals(directory.root()));
            if (rootBoundToAnotherIdentity) {
                throw identityConflict();
            }
            openWorkspaces.put(workspace.workspaceId(), candidate);
        }
    }

    /**
     * 将仓储返回值与稳定派生身份和已验证物理根对齐，拒绝旧数据或冲突行。
     */
    private void verifyIdentity(Workspace workspace, WorkspaceDirectory directory) {
        String derivedId = policy.workspaceId(directory.root());
        if (!derivedId.equals(workspace.workspaceId()) || !directory.root().equals(workspace.root())) {
            throw identityConflict();
        }
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
     * 统一构造脱敏身份冲突，避免路径或持久化行内容进入 RPC。
     */
    private static WorkspaceFailure identityConflict() {
        return new WorkspaceFailure(WorkspaceFailure.Code.IDENTITY_CONFLICT,
                "workspace identity changed");
    }

    /**
     * 将已验证目录与持久化投影绑定为不可变进程状态。
     */
    private record OpenWorkspaceState(WorkspaceDirectory directory, Workspace workspace) {
        /**
         * 拒绝缺失分量，防止并发状态表中出现半绑定记录。
         */
        private OpenWorkspaceState {
            Objects.requireNonNull(directory, "directory");
            Objects.requireNonNull(workspace, "workspace");
        }
    }
}
