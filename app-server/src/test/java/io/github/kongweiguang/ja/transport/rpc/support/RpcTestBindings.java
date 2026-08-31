// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.support;

import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.catalog.domain.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.port.in.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnEventSink;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.in.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.port.in.TurnUseCase;
import io.github.kongweiguang.ja.foundation.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** 为 transport 测试按需组合独立端口，未声明能力始终显式失败。 */
public final class RpcTestBindings {
    private static final WorkspaceUseCase NO_WORKSPACES = new UnsupportedWorkspaces();
    private static final ThreadUseCase NO_THREADS = new UnsupportedThreads();
    private static final TurnUseCase NO_TURNS = new UnsupportedTurns();
    private static final ApprovalUseCase NO_APPROVALS = new UnsupportedApprovals();
    private static final CatalogUseCase NO_CATALOG = new UnsupportedCatalog();
    private static final AttachmentUseCase NO_ATTACHMENTS = new UnsupportedAttachments();

    /** 防止实例化；测试通过静态组合方法明确声明所需能力。 */
    private RpcTestBindings() {
    }

    /**
     * 使用调用方提供的端口覆盖默认拒绝实现，并把关闭探针绑定到统一绝对期限协议。
     * null 只表示该测试明确不覆盖对应能力，不会产生成功的空操作。
     */
    public static RpcServiceBindings create(WorkspaceUseCase workspaces, ThreadUseCase threads,
                                            TurnUseCase turns, ApprovalUseCase approvals,
                                            CatalogUseCase catalog, Runnable closeAction) {
        return create(workspaces, threads, turns, approvals, catalog, null, closeAction);
    }

    /** 附件 transport 测试可显式注入唯一允许能力，其余调用方继续获得拒绝式默认端口。 */
    public static RpcServiceBindings create(WorkspaceUseCase workspaces, ThreadUseCase threads,
                                            TurnUseCase turns, ApprovalUseCase approvals,
                                            CatalogUseCase catalog, AttachmentUseCase attachments,
                                            Runnable closeAction) {
        Runnable close = closeAction == null ? () -> { } : closeAction;
        DeadlineCloseable lifecycle = new DeadlineCloseable() {
            /** 执行测试关闭探针；绝对期限由生产 RpcSession 验证。 */
            @Override
            public void closeAt(long shutdownDeadlineNanos) {
                close.run();
            }

            /** 无参数关闭只用于测试 finally，并复用同一个探针。 */
            @Override
            public void close() {
                close.run();
            }
        };
        return new RpcServiceBindings(workspaces == null ? NO_WORKSPACES : workspaces,
                threads == null ? NO_THREADS : threads, turns == null ? NO_TURNS : turns,
                (command, events, cancellation) -> { throw new UnsupportedOperationException("context compaction is unavailable"); },
                approvals == null ? NO_APPROVALS : approvals,
                catalog == null ? NO_CATALOG : catalog,
                attachments == null ? NO_ATTACHMENTS : attachments, lifecycle);
    }

    /** 未声明的附件能力必须失败，避免普通 transport 测试意外接触文件系统。 */
    private static final class UnsupportedAttachments implements AttachmentUseCase {
        /** 未声明的导入能力失败。 */
        @Override public AttachmentMetadata importDraft(ImportRequest request) { throw unsupported(); }
        /** 未声明的丢弃能力失败。 */
        @Override public AttachmentMetadata discard(String attachmentId, Instant discardedAt) { throw unsupported(); }
        /** 未声明的读取能力失败。 */
        @Override public ReadResult read(ReadRequest request) { throw unsupported(); }
        /** 未声明的回收能力失败。 */
        @Override public void collectGarbage() { throw unsupported(); }
    }

    /** 未声明的 Workspace 能力必须失败，避免夹具把缺失行为伪装成成功。 */
    private static final class UnsupportedWorkspaces implements WorkspaceUseCase {
        /** 未声明的注册能力失败。 */
        @Override public Workspace openWorkspace(OpenWorkspace request) { throw unsupported(); }
        /** 未声明的通用工作区能力失败。 */
        @Override public Workspace openGeneralWorkspace() { throw unsupported(); }
        /** 未声明的列表能力失败。 */
        @Override public CursorPage<Workspace> listWorkspaces(String cursor, int limit) { throw unsupported(); }
        /** 未声明的读取能力失败。 */
        @Override public Optional<Workspace> readWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明的已打开能力读取失败。 */
        @Override public Workspace requireOpenWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明的信任修改能力失败。 */
        @Override public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) { throw unsupported(); }
        /** 未声明的注销能力失败。 */
        @Override public void unregisterWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明的工作区预热能力失败。 */
        @Override public void refreshPreparedWorkspaces() { throw unsupported(); }
        /** 未声明的通用工作区判断能力失败。 */
        @Override public boolean isGeneralWorkspace(Path root) { throw unsupported(); }
    }

    /** 未声明的 Thread 能力必须失败。 */
    private static final class UnsupportedThreads implements ThreadUseCase {
        /** 未声明的创建能力失败。 */
        @Override public ThreadSummary createThread(ThreadSummary.Creation request) { throw unsupported(); }
        /** 未声明的列表能力失败。 */
        @Override public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 未声明的搜索能力失败。 */
        @Override public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) { throw unsupported(); }
        /** 未声明的快照读取能力失败。 */
        @Override public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) { throw unsupported(); }
        /** 未声明的重命名能力失败。 */
        @Override public ThreadSummary renameThread(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明的偏好更新能力失败。 */
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 未声明的自动标题写入能力失败。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明的归档能力失败。 */
        @Override public void archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的删除能力失败。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的 Turn 查找能力失败。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
    }

    /** 未声明的 Turn 生命周期能力必须失败。 */
    private static final class UnsupportedTurns implements TurnUseCase {
        /** 未声明的接纳能力失败。 */
        @Override public Accepted start(TurnStartRequest request, TurnEventSink sink) { throw unsupported(); }
        /** 未声明的取消能力失败。 */
        @Override public CancelResult cancel(String turnId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的接纳关闭能力失败。 */
        @Override public void stopAccepting() { throw unsupported(); }
        /** 未声明的静默等待能力失败。 */
        @Override public boolean awaitQuiescence(Duration timeout) { throw unsupported(); }
        /** 未声明的有界关闭能力失败。 */
        @Override public void closeAt(long shutdownDeadlineNanos) { throw unsupported(); }
        /** 未声明的关闭能力失败。 */
        @Override public void close() { throw unsupported(); }
    }

    /** 未声明的审批能力必须失败。 */
    private static final class UnsupportedApprovals implements ApprovalUseCase {
        /** 未声明的解决能力失败。 */
        @Override public boolean resolve(String approvalId, ApprovalDecision response,
                                         Instant resolvedAt) { throw unsupported(); }
    }

    /** 未声明的 Catalog 能力必须失败。 */
    private static final class UnsupportedCatalog implements CatalogUseCase {
        /** 未声明的 Skill 列表能力失败。 */
        @Override public CursorPage<SkillDescriptor> listSkills(String cursor, int limit) { throw unsupported(); }
        /** 未声明的 MCP 列表能力失败。 */
        @Override public CursorPage<McpServerDescriptor> listMcp(String cursor, int limit) { throw unsupported(); }
        /** 未声明的 MCP 测试能力失败。 */
        @Override public CompletionStage<McpServerDescriptor> testMcp(String mcpId) { throw unsupported(); }
        /** 未声明的模型测试能力失败，避免 transport 夹具触发真实 Provider。 */
        @Override public CompletionStage<ModelTestResult> testModel(
                String providerId, String modelId, CancellationToken cancellationToken) { throw unsupported(); }
        /** 未声明的 MCP Tool 列表能力失败。 */
        @Override public CursorPage<McpToolDescriptor> readMcpTools(String mcpId, String cursor, int limit) { throw unsupported(); }
    }

    /** 为所有未声明能力生成相同的显式失败，不泄露调用参数。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}
