// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.workspace.application;

import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePathFailure;
import io.github.kongweiguang.ja.workspace.port.in.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceReferenceValidator;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePathPort;

import java.util.Objects;

/**
 * 把已打开 Workspace 的权威根交给路径 adapter，并维持搜索与引用准入相同的 ownership。
 */
public final class WorkspacePathService
        implements WorkspacePathSearchUseCase, WorkspaceReferenceValidator {
    private final WorkspaceUseCase workspaces;
    private final WorkspacePathPort paths;

    /** 通过窄端口组合身份与文件系统能力，不在 application 直接调用 NIO。 */
    public WorkspacePathService(WorkspaceUseCase workspaces, WorkspacePathPort paths) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
        this.paths = Objects.requireNonNull(paths, "paths");
    }

    /**
     * 只有已绑定的 Workspace 能搜索；关联字段保持原值返回，供 UI 丢弃迟到结果。
     */
    @Override
    public SearchResult search(SearchRequest request) {
        Objects.requireNonNull(request, "request");
        Workspace workspace = workspaces.requireOpenWorkspace(request.workspaceId());
        WorkspacePathPort.SearchOutcome outcome = paths.search(
                workspace.root(), request.query(), request.limit());
        return new SearchResult(request.threadId(), workspace.workspaceId(),
                request.runtimeGeneration(), request.query(), outcome.entries().stream()
                        .map(entry -> new Entry(entry.relativePath(), entry.kind()))
                        .toList(), outcome.truncated());
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
}
