// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceDirectory;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private WorkspaceService service;

    /** 每个测试使用独立内存端口，避免工作区状态和 revision 相互污染。 */
    @BeforeEach
    void setUp() {
        Path project = temporaryDirectory.resolve("project").toAbsolutePath().normalize();
        Path general = temporaryDirectory.resolve("data/general-workspace").toAbsolutePath().normalize();
        Path sessions = temporaryDirectory.resolve("home/workspaces").toAbsolutePath().normalize();
        repository = new FakeWorkspaceRepository();
        directories = new FakeDirectories(project, general, sessions);
        prepared = new ArrayList<>();
        synchronizedTrust = new ArrayList<>();
        service = new WorkspaceService(
                repository,
                directories,
                prepared::add,
                (root, trust) -> synchronizedTrust.add(root + ":" + trust),
                new WorkspacePolicy(),
                CLOCK);
    }

    /** 项目打开由应用派生稳定 ID、默认不受信任，并执行一次最佳努力预热。 */
    @Test
    void opensProjectWithStableIdentityAndPrewarm() {
        Workspace opened = service.openWorkspace(new WorkspaceUseCase.OpenWorkspace(
                directories.project, "项目"));

        assertEquals(new WorkspacePolicy().workspaceId(directories.project), opened.workspaceId());
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

    /** 新主会话按 thread 身份获得独立目录，重开和 turn 准入都重验持久目录。 */
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

        CursorPage<Workspace> page = service.listWorkspaces(null, 20);

        assertEquals(List.of(opened), page.items());
        assertEquals(Optional.of(opened), service.readWorkspace(opened.workspaceId()));
        assertFalse(service.isLegacySharedWorkspace(opened.root()));
    }

    /** 内存仓储模拟新基线的幂等注册、revision 更新和 CAS 注销。 */
    private static final class FakeWorkspaceRepository implements WorkspaceRepository {
        private final Map<String, Workspace> byId = new LinkedHashMap<>();

        /** 保存首次注册事实；相同身份重复注册直接返回权威记录。 */
        @Override
        public Workspace register(Workspace.Registration registration) {
            Workspace candidate = new Workspace(
                    registration.workspaceId(),
                    registration.root(),
                    registration.displayName(),
                    registration.trust(),
                    registration.kind(),
                    registration.legacySharedWorkspaceId(),
                    0);
            return byId.computeIfAbsent(candidate.workspaceId(), ignored -> candidate);
        }

        /** 测试数据量有界，直接返回插入顺序即可验证应用委派。 */
        @Override
        public CursorPage<Workspace> list(String cursor, int limit) {
            return new CursorPage<>(List.copyOf(byId.values()), null);
        }

        /** 按持久类型先过滤再分页，模拟生产仓储的服务端 kind 条件。 */
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
            Workspace prior = byId.get(workspaceId);
            Workspace updated = new Workspace(
                    prior.workspaceId(), prior.root(), prior.displayName(), trust, prior.kind(),
                    prior.legacySharedWorkspaceId(), prior.revision() + 1);
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

    /** 目录 fake 返回 project、session 与 legacy 的规范身份，不执行真实文件 IO。 */
    private static final class FakeDirectories implements WorkspaceDirectoryPort {
        private Path project;
        private final Path general;
        private final Path sessions;

        /** 固定两类目录，便于断言 application 不会自行重写根路径。 */
        private FakeDirectories(Path project, Path general, Path sessions) {
            this.project = project;
            this.general = general;
            this.sessions = sessions;
        }

        /** 将当前测试项目根标记为已验证项目目录。 */
        @Override
        public WorkspaceDirectory verifyProjectDirectory(Path requestedRoot) {
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

        /** 旧共享目录仅由显式 ID 恢复，普通 session 不能继承它。 */
        @Override
        public WorkspaceDirectory verifyLegacySharedDirectory(Path registeredRoot) {
            if (!general.equals(registeredRoot.toAbsolutePath().normalize())) {
                throw new WorkspaceFailure(WorkspaceFailure.Code.IDENTITY_CONFLICT, "legacy identity changed");
            }
            return new WorkspaceDirectory(general, WorkspaceDirectory.Kind.LEGACY_SHARED);
        }

        /** 仅比较规范路径，保持与生产适配器相同的无副作用语义。 */
        @Override
        public boolean isLegacySharedDirectory(Path root) {
            return root.toAbsolutePath().normalize().equals(general);
        }
    }
}
