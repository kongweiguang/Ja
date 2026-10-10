// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRootChangePort;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePolicy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证 workspace application 对目录、持久化、信任和预热端口的编排。 */
final class WorkspaceServiceTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    @TempDir
    private Path temporaryDirectory;
    private FakeWorkspaceRepository repository;
    private FakeDirectories directories;
    private List<Path> prepared;
    private List<String> synchronizedTrust;
    private AtomicBoolean failNextTrustSynchronization;
    private WorkspaceService service;

    /** 每个测试使用独立内存端口，避免工作区状态和 revision 相互污染。 */
    @BeforeEach
    void setUp() {
        Path project = temporaryDirectory.resolve("project").toAbsolutePath().normalize();
        Path sessions = temporaryDirectory.resolve("home/workspaces").toAbsolutePath().normalize();
        repository = new FakeWorkspaceRepository();
        directories = new FakeDirectories(project, sessions);
        prepared = new ArrayList<>();
        synchronizedTrust = new ArrayList<>();
        failNextTrustSynchronization = new AtomicBoolean();
        service = new WorkspaceService(
                repository,
                directories,
                prepared::add,
                (root, trust) -> {
                    if (failNextTrustSynchronization.getAndSet(false)) {
                        throw new IllegalStateException("configuration sync failed");
                    }
                    synchronizedTrust.add(root + ":" + trust);
                },
                new WorkspacePolicy(),
                CLOCK);
    }

    /** 新项目分配 schema 可接受的随机稳定 ID、默认不受信任，并执行一次最佳努力预热。 */
    @Test
    void opensProjectWithStableIdentityAndPrewarm() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "项目"));

        assertTrue(opened.workspaceId().matches("ws_[0-9a-f]{32}"));
        assertEquals(Workspace.Trust.UNTRUSTED, opened.trust());
        assertEquals(List.of(directories.project), prepared);
        assertEquals(opened, service.requireOpenWorkspace(opened.workspaceId()));
    }

    /** 同一根目录重复打开复用持久化事实，不因新的展示名产生第二个身份。 */
    @Test
    void reopensSameRootWithoutDuplicatingIdentity() {
        Workspace first = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "第一个名称"));
        Workspace second = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "另一个名称"));

        assertEquals(first, second);
        assertEquals(1, repository.byId.size());
        assertEquals(2, prepared.size());
    }

    /** 设置访问验证持久项目 ID，但不把重启后的项目加入活动工作区或触发预热。 */
    @Test
    void readsRegisteredProjectSettingsWithoutOpeningIt() {
        Workspace registered = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "项目"));
        WorkspaceService restarted = new WorkspaceService(repository, directories, prepared::add,
                (root, trust) -> synchronizedTrust.add(root + ":" + trust), new WorkspacePolicy(), CLOCK);

        assertThrows(WorkspaceFailure.class, () -> restarted.requireOpenWorkspace(registered.workspaceId()));
        assertEquals(registered, restarted.requireSettingsWorkspace(registered.workspaceId()));
        assertEquals(List.of(directories.project), prepared);
        assertTrue(synchronizedTrust.isEmpty());
    }

    /** 非活动但已登记的项目允许变更 trust，且设置写入不会隐式打开或预热它。 */
    @Test
    void updatesTrustForRegisteredProjectWithoutOpeningIt() {
        Workspace registered = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "项目"));
        WorkspaceService restarted = new WorkspaceService(repository, directories, prepared::add,
                (root, trust) -> synchronizedTrust.add(root + ":" + trust),
                new WorkspacePolicy(), CLOCK);

        Workspace trusted = restarted.setWorkspaceTrust(registered.workspaceId(), Workspace.Trust.TRUSTED);

        assertEquals(Workspace.Trust.TRUSTED, trusted.trust());
        assertEquals(registered.revision() + 1, trusted.revision());
        assertEquals(trusted, restarted.requireSettingsWorkspace(registered.workspaceId()));
        assertThrows(WorkspaceFailure.class,
                () -> restarted.requireOpenWorkspace(registered.workspaceId()));
        assertEquals(List.of(directories.project + ":TRUSTED"), synchronizedTrust);
        assertEquals(1, prepared.size());
    }

    /** 未登记 identity 使用与设置读取一致的失败语义，且不会尝试信任写入或同步。 */
    @Test
    void rejectsUnregisteredWorkspaceTrustBeforeSideEffects() {
        WorkspaceFailure settingsFailure = assertThrows(WorkspaceFailure.class,
                () -> service.requireSettingsWorkspace("ws_unknown"));

        WorkspaceFailure trustFailure = assertThrows(WorkspaceFailure.class,
                () -> service.setWorkspaceTrust("ws_unknown", Workspace.Trust.TRUSTED));

        assertEquals(settingsFailure.code(), trustFailure.code());
        assertEquals(0, repository.trustUpdateCalls);
        assertTrue(synchronizedTrust.isEmpty());
    }

    /** 项目身份是持久 opaque ID，信任写入不能再从当前根目录推导或改写它。 */
    @Test
    void projectTrustKeepsPersistedOpaqueIdentityInsteadOfDerivingFromRoot() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));

        Workspace trusted = service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED);

        assertNotEquals(new WorkspacePolicy().workspaceId(opened.root()), opened.workspaceId());
        assertEquals(opened.workspaceId(), trusted.workspaceId());
        assertEquals(1, repository.trustUpdateCalls);
        assertEquals(List.of(opened.root() + ":TRUSTED"), synchronizedTrust);
    }

    /** 物理目录失效时，非活动项目的 trust 写入也必须在触碰持久化前失败。 */
    @Test
    void rejectsUnverifiableRegisteredDirectoryBeforeTrustUpdate() {
        Workspace registered = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        WorkspaceService restarted = new WorkspaceService(repository, directories, prepared::add,
                (root, trust) -> synchronizedTrust.add(root + ":" + trust),
                new WorkspacePolicy(), CLOCK);
        directories.rejectProjectDirectory = true;

        assertThrows(WorkspaceFailure.class,
                () -> restarted.setWorkspaceTrust(registered.workspaceId(), Workspace.Trust.TRUSTED));

        assertEquals(0, repository.trustUpdateCalls);
        assertTrue(synchronizedTrust.isEmpty());
    }

    /** 信任变更先更新权威 revision，再同步配置边界并重新预热项目。 */
    @Test
    void synchronizesTrustAndPreparesProject() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));

        Workspace trusted = service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED);

        assertEquals(Workspace.Trust.TRUSTED, trusted.trust());
        assertEquals(1, trusted.revision());
        assertEquals(List.of(directories.project + ":TRUSTED"), synchronizedTrust);
        assertEquals(2, prepared.size());
    }

    /** 同步失败保留已提交 trust；相同目标值可再次同步并恢复完整成功路径。 */
    @Test
    void retriesTrustSynchronizationAfterFailure() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        failNextTrustSynchronization.set(true);

        assertThrows(IllegalStateException.class,
                () -> service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED));

        assertEquals(Workspace.Trust.TRUSTED,
                repository.findById(opened.workspaceId()).orElseThrow().trust());
        assertEquals(Workspace.Trust.TRUSTED,
                service.requireOpenWorkspace(opened.workspaceId()).trust());
        assertTrue(synchronizedTrust.isEmpty());

        Workspace retried = service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED);

        assertEquals(Workspace.Trust.TRUSTED, retried.trust());
        assertEquals(List.of(directories.project + ":TRUSTED"), synchronizedTrust);
        assertEquals(2, prepared.size());
    }

    /** 两次并发 trust 变更必须按持久化顺序同步，避免旧配置覆盖更新后的权威状态。 */
    @Test
    void serializesTrustPersistenceAndSynchronization() throws Exception {
        CountDownLatch firstSyncEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstSync = new CountDownLatch(1);
        CountDownLatch secondSyncEntered = new CountDownLatch(1);
        CountDownLatch secondCallStarted = new CountDownLatch(1);
        ConcurrentLinkedQueue<Workspace.Trust> syncOrder = new ConcurrentLinkedQueue<>();
        WorkspaceService concurrentService = new WorkspaceService(
                repository,
                directories,
                prepared::add,
                (root, trust) -> {
                    syncOrder.add(trust);
                    if (trust == Workspace.Trust.TRUSTED) {
                        firstSyncEntered.countDown();
                        await(releaseFirstSync);
                    }
                    else {
                        secondSyncEntered.countDown();
                    }
                },
                new WorkspacePolicy(),
                CLOCK);
        Workspace opened = concurrentService.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Workspace> first = executor.submit(() -> concurrentService.setWorkspaceTrust(
                    opened.workspaceId(), Workspace.Trust.TRUSTED));
            assertTrue(firstSyncEntered.await(5, TimeUnit.SECONDS));

            Future<Workspace> second = executor.submit(() -> {
                secondCallStarted.countDown();
                return concurrentService.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.UNTRUSTED);
            });
            assertTrue(secondCallStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondSyncEntered.await(100, TimeUnit.MILLISECONDS));
            assertEquals(Workspace.Trust.TRUSTED,
                    repository.findById(opened.workspaceId()).orElseThrow().trust());

            releaseFirstSync.countDown();
            assertEquals(Workspace.Trust.TRUSTED, first.get(5, TimeUnit.SECONDS).trust());
            assertEquals(Workspace.Trust.UNTRUSTED, second.get(5, TimeUnit.SECONDS).trust());
        }
        finally {
            releaseFirstSync.countDown();
            executor.shutdownNow();
        }

        assertEquals(List.of(Workspace.Trust.TRUSTED, Workspace.Trust.UNTRUSTED),
                List.copyOf(syncOrder));
        assertEquals(Workspace.Trust.UNTRUSTED,
                repository.findById(opened.workspaceId()).orElseThrow().trust());
    }

    /** 等待测试中的同步门闩并在中断时恢复线程状态，避免遗留后台更新。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for trust sync release");
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("trust sync interrupted", interrupted);
        }
    }

    /** 新主会话按 thread 身份获得独立目录并固定 TRUSTED，重开和 turn 准入都重验持久目录。 */
    @Test
    void createsAndRevalidatesIndependentSessionWorkspace() {
        Workspace first = service.createSessionWorkspace("thr_session_a");
        Workspace second = service.createSessionWorkspace("thr_session_b");

        assertEquals("无项目对话", first.displayName());
        assertEquals(Workspace.Kind.SESSION, first.kind());
        assertNotEquals(first.workspaceId(), second.workspaceId());
        assertNotEquals(first.root(), second.root());
        assertEquals(first, service.requireOpenWorkspace(first.workspaceId()));
        assertEquals(first, service.openRegisteredWorkspace(first.workspaceId()));
        assertTrue(prepared.isEmpty());
        assertEquals(2, repository.byId.size());
        assertEquals(2, service.listWorkspaces(null, 20, Workspace.Kind.SESSION).items().size());
        assertTrue(service.listWorkspaces(null, 20, Workspace.Kind.PROJECT).items().isEmpty());
        assertThrows(WorkspaceFailure.class,
                () -> service.setWorkspaceTrust(first.workspaceId(), Workspace.Trust.UNTRUSTED));
    }

    /** 注销只有在 Repository CAS 成功后才移除进程目录绑定。 */
    @Test
    void unregistersMetadataAndOpenState() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));

        service.unregisterWorkspace(opened.workspaceId(), opened.revision());

        assertTrue(repository.byId.isEmpty());
        assertThrows(WorkspaceFailure.class,
                () -> service.requireOpenWorkspace(opened.workspaceId()));
    }

    /** 容量检查在绑定临界区内执行，第二个物理根不能突破上限。 */
    @Test
    void enforcesOpenWorkspaceCapacity() {
        service = new WorkspaceService(
                repository,
                directories,
                prepared::add,
                (root, trust) -> synchronizedTrust.add(root + ":" + trust),
                new WorkspacePolicy(),
                CLOCK,
                1);
        service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(directories.project, null));
        directories.project = temporaryDirectory.resolve("second").toAbsolutePath().normalize();

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                        directories.project, null)));

        assertEquals(WorkspaceFailure.Code.CAPACITY_EXHAUSTED, failure.code());
        assertEquals(1, repository.byId.size());
    }

    /** 列表和读取只委派仓储，不把持久化记录隐式恢复为文件能力。 */
    @Test
    void readsPersistenceWithoutOpeningDirectory() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));

        CursorPage<Workspace> page = service.listWorkspaces(null, 20, null);

        assertEquals(List.of(opened), page.items());
        assertEquals(Optional.of(opened), service.readWorkspace(opened.workspaceId()));
    }

    /** 纯名称更新即使旧根已不可用仍可修复显示名，并保留根、信任和 stable identity。 */
    @Test
    void updatesProjectNameWithoutRequiringDirectoryAvailability() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "原名称"));
        directories.rejectProjectDirectory = true;

        Workspace updated = service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                opened.workspaceId(), opened.revision(), "新名称", null));

        assertEquals(opened.workspaceId(), updated.workspaceId());
        assertEquals("新名称", updated.displayName());
        assertEquals(opened.root(), updated.root());
        assertEquals(opened.trust(), updated.trust());
        assertEquals(opened.revision() + 1, updated.revision());
    }

    /** 根迁移更新原 Workspace 行、保留 ID 并清理旧能力；信任只在物理根变化时撤销。 */
    @Test
    void rebindsProjectRootWithoutChangingIdentityAndResetsTrust() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "保留名称"));
        Workspace trusted = service.setWorkspaceTrust(opened.workspaceId(), Workspace.Trust.TRUSTED);
        Path newRoot = temporaryDirectory.resolve("moved-project").toAbsolutePath().normalize();

        Workspace updated = service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                opened.workspaceId(), trusted.revision(), null, newRoot));

        assertEquals(opened.workspaceId(), updated.workspaceId());
        assertEquals(newRoot, updated.root());
        assertEquals("保留名称", updated.displayName());
        assertEquals(Workspace.Trust.UNTRUSTED, updated.trust());
        assertEquals(trusted.revision() + 1, updated.revision());
        assertEquals(Workspace.Trust.UNTRUSTED,
                repository.findById(opened.workspaceId()).orElseThrow().trust());
        assertEquals(List.of(directories.project + ":TRUSTED",
                        directories.project + ":UNTRUSTED", newRoot + ":UNTRUSTED"), synchronizedTrust);
        assertThrows(WorkspaceFailure.class, () -> service.requireOpenWorkspace(opened.workspaceId()));
    }

    /** 根变更历史按 Thread 独立消费，连续重绑合并为最初路径到最终路径。 */
    @Test
    void rootChangeNoticeIsPerThreadAndFoldsConsecutiveRebinds() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        Path secondRoot = temporaryDirectory.resolve("second-root").toAbsolutePath().normalize();
        Path thirdRoot = temporaryDirectory.resolve("third-root").toAbsolutePath().normalize();
        Workspace second = service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                opened.workspaceId(), opened.revision(), null, secondRoot));
        Workspace third = service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                opened.workspaceId(), second.revision(), null, thirdRoot));

        WorkspaceRootChangePort.Change firstThreadChange = service.pending(opened.workspaceId(), "thr_one")
                .orElseThrow();
        WorkspaceRootChangePort.Change secondThreadChange = service.pending(opened.workspaceId(), "thr_two")
                .orElseThrow();
        assertEquals(third.revision(), firstThreadChange.revision());
        assertEquals(opened.root(), firstThreadChange.oldRoot());
        assertEquals(thirdRoot, firstThreadChange.newRoot());
        assertEquals(firstThreadChange, secondThreadChange);

        service.consume(opened.workspaceId(), "thr_one", firstThreadChange.revision());

        assertTrue(service.pending(opened.workspaceId(), "thr_one").isEmpty());
        assertEquals(Optional.of(secondThreadChange), service.pending(opened.workspaceId(), "thr_two"));
    }

    /** 重绑目标必须唯一，不能把另一个 Workspace 的 root 偷换到原身份。 */
    @Test
    void rejectsRebindingToRootOwnedByAnotherWorkspace() {
        Workspace first = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        Path anotherRoot = temporaryDirectory.resolve("another-project").toAbsolutePath().normalize();
        Workspace second = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(anotherRoot, null));

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                        first.workspaceId(), first.revision(), null, second.root())));

        assertEquals(WorkspaceFailure.Code.UPDATE_CONFLICT, failure.code());
        assertEquals(first, repository.findById(first.workspaceId()).orElseThrow());
    }

    /** 同 Workspace 正在提交 admission 时，根重绑快速失败且不等待或改写持久根。 */
    @Test
    void rejectsRootRebindDuringTurnOrPlanAdmissionWindow() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        Path newRoot = temporaryDirectory.resolve("busy-target").toAbsolutePath().normalize();

        try (var ignored = service.acquireAdmission(opened.workspaceId())) {
            WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                    () -> service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                            opened.workspaceId(), opened.revision(), null, newRoot)));

            assertEquals(WorkspaceFailure.Code.WORKSPACE_BUSY, failure.code());
        }

        assertEquals(opened.root(), repository.findById(opened.workspaceId()).orElseThrow().root());
    }

    /** 已持久化的活动或可恢复事实同样阻止重绑，即使当前没有 JVM admission 锁持有者。 */
    @Test
    void rejectsRootRebindWhenRepositoryReportsRecoverableWork() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, null));
        repository.activeWork = true;
        Path newRoot = temporaryDirectory.resolve("recoverable-target").toAbsolutePath().normalize();

        WorkspaceFailure failure = assertThrows(WorkspaceFailure.class,
                () -> service.updateWorkspace(new WorkspaceUseCase.UpdateWorkspace(
                        opened.workspaceId(), opened.revision(), null, newRoot)));

        assertEquals(WorkspaceFailure.Code.WORKSPACE_BUSY, failure.code());
        assertEquals(opened.root(), repository.findById(opened.workspaceId()).orElseThrow().root());
    }

    /** 内存仓储模拟新基线的幂等注册、revision 更新和 CAS 注销。 */
    private static final class FakeWorkspaceRepository implements WorkspaceRepository {
        private final Map<String, Workspace> byId = new LinkedHashMap<>();
        private int trustUpdateCalls;
        private boolean activeWork;

        /** 保存首次注册事实；相同身份重复注册直接返回权威记录。 */
        @Override
        public Workspace register(Workspace.Registration registration) {
            Workspace candidate = new Workspace(registration.workspaceId(), registration.root(), registration.displayName(), registration.trust(), registration.kind(), 0);
            return byId.computeIfAbsent(candidate.workspaceId(), ignored -> candidate);
        }

        /** null 表示无筛选，测试仍走与生产一致的唯一类型化列表入口。 */
        @Override
        public CursorPage<Workspace> list(String cursor, int limit, Workspace.Kind kind) {
            List<Workspace> values = byId.values().stream()
                    .filter(workspace -> kind == null || workspace.kind() == kind)
                    .toList();
            return new CursorPage<>(values, null);
        }

        /** 按身份读取内存权威记录。 */
        @Override
        public Optional<Workspace> findById(String workspaceId) {
            return Optional.ofNullable(byId.get(workspaceId));
        }

        /** 按规范根读取内存权威记录。 */
        @Override
        public Optional<Workspace> findByRoot(Path canonicalRoot) {
            return byId.values().stream()
                    .filter(workspace -> workspace.root().equals(canonicalRoot))
                    .findFirst();
        }

        /** 每次信任写入都推进 revision，模拟持久化权威版本。 */
        @Override
        public Workspace updateTrust(String workspaceId, Workspace.Trust trust) {
            trustUpdateCalls++;
            Workspace prior = byId.get(workspaceId);
            Workspace updated = new Workspace(prior.workspaceId(), prior.root(), prior.displayName(), trust, prior.kind(), prior.revision() + 1);
            byId.put(workspaceId, updated);
            return updated;
        }

        /** 模拟数据库 CAS、root 唯一索引和 durable activity 检查，保留历史 owner identity。 */
        @Override
        public Workspace updateProject(String workspaceId, long expectedRevision, Path root,
                                       String displayName, boolean rootChanged, Instant occurredAt) {
            Workspace prior = byId.get(workspaceId);
            if (prior == null || prior.revision() != expectedRevision) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.UPDATE_CONFLICT, "stale Workspace");
            }
            if (rootChanged && activeWork) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.WORKSPACE_BUSY, "active Workspace");
            }
            boolean duplicateRoot = rootChanged && byId.values().stream()
                    .anyMatch(value -> !workspaceId.equals(value.workspaceId()) && value.root().equals(root));
            if (duplicateRoot) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.UPDATE_CONFLICT, "root already registered");
            }
            Workspace updated = new Workspace(prior.workspaceId(), root, displayName,
                    rootChanged ? Workspace.Trust.UNTRUSTED : prior.trust(), prior.kind(),
                    prior.revision() + 1);
            byId.put(workspaceId, updated);
            return updated;
        }

        /** revision 不匹配时拒绝删除，匹配时只移除元数据。 */
        @Override
        public void unregister(String workspaceId, long expectedRevision) {
            Workspace prior = byId.get(workspaceId);
            if (prior == null || prior.revision() != expectedRevision) {
                throw new IllegalStateException("revision conflict");
            }
            byId.remove(workspaceId);
        }
    }

    /** 目录 fake 返回 project 与 session 的规范身份，不执行真实文件 IO。 */
    private static final class FakeDirectories implements WorkspaceDirectoryPort {
        private Path project;
        private final Path sessions;
        private boolean rejectProjectDirectory;

        /** 固定两类目录，便于断言 application 不会自行重写根路径。 */
        private FakeDirectories(Path project, Path sessions) {
            this.project = project;
            this.sessions = sessions;
        }

        /** 返回规范项目根，并可显式模拟物理目录验证失败。 */
        @Override
        public WorkspaceDirectory verifyProjectDirectory(Path requestedRoot) {
            if (rejectProjectDirectory) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE,
                        "project directory is unavailable");
            }
            return new WorkspaceDirectory(requestedRoot.toAbsolutePath().normalize(),
                    WorkspaceDirectory.Kind.PROJECT);
        }

        /** 依据主 Thread ID 返回独立会话目录。 */
        @Override
        public WorkspaceDirectory createSessionDirectory(String threadId) {
            return new WorkspaceDirectory(sessions.resolve(threadId), WorkspaceDirectory.Kind.SESSION);
        }

        /** 只接受固定 thread leaf 的已登记会话根。 */
        @Override
        public WorkspaceDirectory verifySessionDirectory(String threadId, Path registeredRoot) {
            Path expected = sessions.resolve(threadId).toAbsolutePath().normalize();
            if (!expected.equals(registeredRoot.toAbsolutePath().normalize())) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.IDENTITY_CONFLICT, "session identity changed");
            }
            return new WorkspaceDirectory(expected, WorkspaceDirectory.Kind.SESSION);
        }

    }
}
