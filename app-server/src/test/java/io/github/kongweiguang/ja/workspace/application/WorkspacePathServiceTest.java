// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspaceEntryKind;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceReferenceValidator;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 验证路径应用服务的 Workspace ownership、关联字段和窄引用投影。 */
final class WorkspacePathServiceTest {
    @TempDir
    private Path temporaryDirectory;
    private Workspace workspace;
    private RecordingWorkspaceUseCase workspaces;
    private RecordingPathPort paths;
    private WorkspacePathService service;

    /** 每个测试冻结同一 Workspace 与无 IO fake，使应用规则可独立验证。 */
    @BeforeEach
    void setUp() {
        workspace = new Workspace("ws_test", temporaryDirectory, "Test",
                Workspace.Trust.TRUSTED, Workspace.Kind.PROJECT, null, 0);
        workspaces = new RecordingWorkspaceUseCase(workspace);
        paths = new RecordingPathPort();
        service = new WorkspacePathService(workspaces, paths);
    }

    /** 搜索结果必须原样关联 Thread、Workspace、runtime generation 与查询。 */
    @Test
    void preservesSearchCorrelationFields() {
        paths.searchResult = new WorkspacePathPort.SearchOutcome(List.of(
                new WorkspacePathPort.PathEntry("src/Main.java", WorkspaceEntryKind.FILE)),
                true, 42);

        WorkspacePathSearchUseCase.SearchResult result = service.search(
                new WorkspacePathSearchUseCase.SearchRequest(
                        "thr_test", "ws_test", 17, "main", 10));

        assertEquals("thr_test", result.threadId());
        assertEquals("ws_test", result.workspaceId());
        assertEquals(17, result.runtimeGeneration());
        assertEquals("main", result.query());
        assertEquals(List.of(new WorkspacePathSearchUseCase.Entry(
                "src/Main.java", WorkspaceEntryKind.FILE)), result.items());
        assertEquals(temporaryDirectory.toAbsolutePath().normalize(), paths.searchedRoot);
    }

    /** 同一 Thread 的新输入应中止旧扫描，避免快速键入时并发占用 fd 与目录 IO。 */
    @Test
    void supersedesPreviousSearchForSameThread() throws Exception {
        paths.waitForFirstCancellation = true;
        paths.searchResult = new WorkspacePathPort.SearchOutcome(List.of(
                new WorkspacePathPort.PathEntry("src/New.java", WorkspaceEntryKind.FILE)), false, 1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<WorkspacePathSearchUseCase.SearchResult> previous = executor.submit(() -> service.search(
                    new WorkspacePathSearchUseCase.SearchRequest(
                            "thr_test", "ws_test", 17, "old", 10)));
            assertTrue(paths.firstSearchEntered.await(5, TimeUnit.SECONDS));

            WorkspacePathSearchUseCase.SearchResult latest = service.search(
                    new WorkspacePathSearchUseCase.SearchRequest(
                            "thr_test", "ws_test", 17, "new", 10));
            WorkspacePathSearchUseCase.SearchResult stale = previous.get(5, TimeUnit.SECONDS);

            assertTrue(paths.firstSearchCancellation.get().isCancellationRequested());
            assertEquals("new", latest.query());
            assertEquals("old", stale.query());
            assertTrue(stale.truncated());
        }
    }

    /** Thread Workspace 不一致必须在 adapter IO 前失败，避免跨 Workspace 探测。 */
    @Test
    void rejectsReferenceFromAnotherThreadWorkspaceBeforeIo() {
        WorkspacePathFailure failure = assertThrows(WorkspacePathFailure.class,
                () -> service.validate(new WorkspaceReferenceValidator.ValidationRequest(
                        "ws_other", "ws_test", "src/Main.java", WorkspaceEntryKind.FILE)));

        assertEquals(WorkspacePathFailure.Code.WORKSPACE_MISMATCH, failure.code());
        assertEquals(0, paths.validationCalls);
    }

    /** 已验证引用仅返回标准相对路径和类型，不把 adapter 接收的绝对根泄露给消费者。 */
    @Test
    void returnsNarrowValidatedReference() {
        paths.validated = new WorkspacePathPort.ValidatedPath(
                "src/Main.java", WorkspaceEntryKind.FILE);

        WorkspaceReferenceValidator.ValidatedReference result = service.validate(
                new WorkspaceReferenceValidator.ValidationRequest(
                        "ws_test", "ws_test", "src\\Main.java", WorkspaceEntryKind.FILE));

        assertEquals(new WorkspaceReferenceValidator.ValidatedReference(
                "ws_test", "src/Main.java", WorkspaceEntryKind.FILE), result);
        assertEquals(1, paths.validationCalls);
    }

    /** Workspace fake 只实现本切片需要的打开态读取，其它方法禁止测试误调用。 */
    private static final class RecordingWorkspaceUseCase implements WorkspaceUseCase {
        private final Workspace workspace;

        /** 冻结唯一打开 Workspace。 */
        private RecordingWorkspaceUseCase(Workspace workspace) {
            this.workspace = workspace;
        }

        /** 本测试不允许打开新的 Workspace。 */
        @Override
        public Workspace openWorkspace(OpenWorkspace command) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不创建会话目录。 */
        @Override
        public Workspace createSessionWorkspace(String threadId) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不执行 Thread 创建补偿。 */
        @Override
        public void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不重开持久 session 或 legacy 根。 */
        @Override
        public Workspace openRegisteredWorkspace(String workspaceId) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不使用持久化列表。 */
        @Override
        public CursorPage<Workspace> listWorkspaces(String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不使用非能力型读取。 */
        @Override
        public Optional<Workspace> readWorkspace(String workspaceId) {
            throw new UnsupportedOperationException();
        }

        /** 仅精确身份可取得已打开 Workspace，模拟生产进程绑定。 */
        @Override
        public Workspace requireOpenWorkspace(String workspaceId) {
            if (!workspace.workspaceId().equals(workspaceId)) {
                throw new IllegalArgumentException("workspace is not open");
            }
            return workspace;
        }

        /** 本测试不修改信任。 */
        @Override
        public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不注销 Workspace。 */
        @Override
        public void unregisterWorkspace(String workspaceId, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        /** 本测试不触发配置预热。 */
        @Override
        public void refreshPreparedWorkspaces() {
            throw new UnsupportedOperationException();
        }

        /** 本测试不判断旧共享目录。 */
        @Override
        public boolean isLegacySharedWorkspace(Path root) {
            throw new UnsupportedOperationException();
        }
    }

    /** 路径 fake 记录应用传递的绝对根，但只返回受控相对 DTO。 */
    private static final class RecordingPathPort implements WorkspacePathPort {
        private SearchOutcome searchResult = new SearchOutcome(List.of(), false, 0);
        private ValidatedPath validated = new ValidatedPath("src/Main.java", WorkspaceEntryKind.FILE);
        private Path searchedRoot;
        private int validationCalls;
        private boolean waitForFirstCancellation;
        private final CountDownLatch firstSearchEntered = new CountDownLatch(1);
        private final CountDownLatch firstSearchCancelled = new CountDownLatch(1);
        private final AtomicReference<CancellationToken> firstSearchCancellation = new AtomicReference<>();

        /** 记录搜索根并返回测试设置的结果。 */
        @Override
        public SearchOutcome search(Path workspaceRoot, String query, int limit,
                                    CancellationToken cancellationToken) {
            searchedRoot = workspaceRoot;
            if (waitForFirstCancellation && firstSearchCancellation.compareAndSet(null, cancellationToken)) {
                firstSearchEntered.countDown();
                try (CancellationToken.Registration ignored = cancellationToken.onCancellation(
                        firstSearchCancelled::countDown)) {
                    if (!firstSearchCancelled.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("old workspace search was not cancelled");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("old workspace search was interrupted", interrupted);
                }
                return new SearchOutcome(List.of(), true, 0);
            }
            return searchResult;
        }

        /** 记录引用重新准入次数，验证 Workspace mismatch 不会触发 IO。 */
        @Override
        public ValidatedPath validate(Path workspaceRoot, String relativePath,
                                      WorkspaceEntryKind kind) {
            validationCalls++;
            return validated;
        }
    }
}
