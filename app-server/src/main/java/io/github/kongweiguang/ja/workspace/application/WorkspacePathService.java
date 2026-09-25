// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application;

import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceReferenceValidator;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 把已打开 Workspace 的权威根交给路径 adapter，并维持搜索与引用准入相同的 ownership。
 */
public final class WorkspacePathService
        implements WorkspacePathSearchUseCase, WorkspaceReferenceValidator {
    private final WorkspaceUseCase workspaces;
    private final WorkspacePathPort paths;
    private final ConcurrentMap<String, SearchCancellation> activeSearches = new ConcurrentHashMap<>();

    /** 通过窄端口组合身份与文件系统能力，不在 application 直接调用 NIO。 */
    public WorkspacePathService(WorkspaceUseCase workspaces, WorkspacePathPort paths) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /**
     * 只让当前 Thread 的最新输入继续消耗目录 IO；旧请求保持原关联字段以便 UI 丢弃迟到结果。
     */
    @Override
    public SearchResult search(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        Workspace workspace = workspaces.requireOpenWorkspace(request.workspaceId());
        SearchCancellation cancellation = new SearchCancellation();
        SearchCancellation previous = activeSearches.put(request.threadId(), cancellation);
        if (previous != null) previous.cancel();
        try {
            WorkspacePathPort.SearchOutcome outcome = paths.search(
                    workspace.root(), request.query(), request.limit(), cancellation);
            return new SearchResult(request.threadId(), workspace.workspaceId(),
                    request.runtimeGeneration(), request.query(), outcome.entries().stream()
                            .map(entry -> new Entry(entry.relativePath(), entry.kind()))
                            .toList(), outcome.truncated());
        } finally {
            activeSearches.remove(request.threadId(), cancellation);
        }
    }

    /**
     * Thread Workspace 不一致时在任何文件 IO 前失败；路径仍在每次消费前重新解析。
     */
    @Override
    public ValidatedReference validate(ValidationRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.threadWorkspaceId().equals(request.workspaceId())) {
            throw new WorkspacePathFailure(WorkspacePathFailure.Code.WORKSPACE_MISMATCH,
                    "workspace reference does not belong to thread");
        }
        Workspace workspace = workspaces.requireOpenWorkspace(request.workspaceId());
        WorkspacePathPort.ValidatedPath validated = paths.validate(
                workspace.root(), request.relativePath(), request.kind());
        return new ValidatedReference(workspace.workspaceId(),
                validated.relativePath(), validated.kind());
    }

    /** 搜索替代只取消目录进程，不触及已选引用或其它 Thread 的查询。 */
    private static final class SearchCancellation implements CancellationToken {
        private final Object lock = new Object();
        private final List<Runnable> callbacks = new ArrayList<>();
        private boolean cancelled;

        /** 在线程间安全读取取消事实，供 native search 的阻塞循环轮询。 */
        @Override
        public boolean isCancellationRequested() {
            synchronized (lock) {
                return cancelled;
            }
        }

        /** 仅返回固定分类，不把路径、查询文本或其它请求内容带入诊断。 */
        @Override
        public Optional<String> reason() {
            synchronized (lock) {
                return cancelled ? Optional.of("workspace path search superseded") : Optional.empty();
            }
        }

        /** 新查询可在旧进程启动前后注册，已取消令牌会立即触发新注册的回调。 */
        @Override
        public Registration onCancellation(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            boolean invokeNow;
            synchronized (lock) {
                invokeNow = cancelled;
                if (!invokeNow) callbacks.add(callback);
            }
            if (invokeNow) callback.run();
            return () -> {
                synchronized (lock) {
                    callbacks.remove(callback);
                }
            };
        }

        /** 发布一次取消并在锁外终止进程，避免资源回收回调阻塞其它搜索请求。 */
        private void cancel() {
            List<Runnable> pending;
            synchronized (lock) {
                if (cancelled) return;
                cancelled = true;
                pending = List.copyOf(callbacks);
                callbacks.clear();
            }
            pending.forEach(Runnable::run);
        }
    }
}
