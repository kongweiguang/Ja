// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.support;

import io.github.kongweiguang.ja.transport.rpc.RpcServiceBindings;
import io.github.kongweiguang.ja.catalog.domain.model.McpServerDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.McpToolDescriptor;
import io.github.kongweiguang.ja.catalog.domain.model.SkillDescriptor;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;
import io.github.kongweiguang.ja.agent.domain.approval.ApprovalDecision;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.TurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.InternalTurnStartRequest;
import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
import io.github.kongweiguang.ja.agent.application.concurrent.DeadlineCloseable;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** 为 transport 测试按需组合独立端口，未声明能力始终显式失败。 */
public final class RpcTestBindings {
    private static final WorkspaceUseCase NO_WORKSPACES = new UnsupportedWorkspaces();
    private static final WorkspacePathSearchUseCase NO_WORKSPACE_PATH_SEARCH =
            request -> { throw new UnsupportedOperationException("workspace path search is unavailable"); };
    private static final ThreadUseCase NO_THREADS = new UnsupportedThreads();
    private static final ThreadMcpUseCase NO_THREAD_MCP = unsupportedThreadMcp();
    private static final TurnUseCase NO_TURNS = new UnsupportedTurns();
    private static final ApprovalUseCase NO_APPROVALS = new UnsupportedApprovals();
    private static final CatalogUseCase NO_CATALOG = new UnsupportedCatalog();
    private static final AttachmentUseCase NO_ATTACHMENTS = new UnsupportedAttachments();
    private static final TaskUseCase NO_TASKS = passiveTasks();
    private static final PlanUseCase NO_PLANS = passivePlans();

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
                NO_WORKSPACE_PATH_SEARCH,
                threads == null ? NO_THREADS : threads, NO_THREAD_MCP, turns == null ? NO_TURNS : turns,
                (command, events, cancellation) -> { throw new UnsupportedOperationException("context compaction is unavailable"); },
                approvals == null ? NO_APPROVALS : approvals,
                catalog == null ? NO_CATALOG : catalog,
                attachments == null ? NO_ATTACHMENTS : attachments,
                attachments instanceof AttachmentPreviewUseCase previews ? previews :
                        (AttachmentPreviewUseCase) NO_ATTACHMENTS,
                NO_TASKS,
                NO_PLANS,
                passiveInteractions(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedProcesses(),
                io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings.unsupportedReviews(),
                lifecycle);
    }

    /**
     * 为 Turn/Plan 路由测试注入 Plan 控制端口，其余能力继续复用拒绝式绑定。
     */
    public static RpcServiceBindings createWithPlans(WorkspaceUseCase workspaces, ThreadUseCase threads,
                                                     TurnUseCase turns, ApprovalUseCase approvals,
                                                     CatalogUseCase catalog, PlanUseCase plans,
                                                     Runnable closeAction) {
        RpcServiceBindings base = create(workspaces, threads, turns, approvals, catalog, closeAction);
        return new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(), base.threads(), base.threadMcp(),
                base.turns(), base.compactions(), base.approvals(), base.catalog(), base.attachments(),
                base.attachmentPreviews(), base.tasks(), plans == null ? NO_PLANS : plans, base.interactions(),
                base.processes(), base.reviews(), base.lifecycle());
    }

    /**
     * 为进程 RPC 测试注入唯一会话 owner，其余未声明端口继续 fail closed。
     */
    public static RpcServiceBindings createWithProcesses(WorkspaceUseCase workspaces, ThreadUseCase threads,
                                                         TurnUseCase turns, ApprovalUseCase approvals,
                                                         CatalogUseCase catalog, AttachmentUseCase attachments,
                                                         io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase processes,
                                                         Runnable closeAction) {
        RpcServiceBindings base = create(workspaces, threads, turns, approvals, catalog, attachments, closeAction);
        return new RpcServiceBindings(base.workspaces(), base.workspacePathSearch(), base.threads(), base.threadMcp(),
                base.turns(), base.compactions(), base.approvals(), base.catalog(), base.attachments(),
                base.attachmentPreviews(), base.tasks(), base.plans(), base.interactions(),
                processes == null ? unsupportedProcesses() : processes, base.reviews(), base.lifecycle());
    }

    /**
     * 普通 transport 测试必须允许连接建立唯一 Task 事件订阅，但其它 Task 能力仍失败关闭；
     * 这与 RpcSession 的连接级事件 ownership 对齐，又不会让无关测试静默获得业务能力。
     */
    public static TaskUseCase passiveTasks() {
        return (TaskUseCase) Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                new Class<?>[]{TaskUseCase.class}, (proxy, method, arguments) -> {
                    if ("closeTemporarySideChats".equals(method.getName())) {
                        return null;
                    }
                    if ("hasLiveNativeClientWork".equals(method.getName())) {
                        return false;
                    }
                    if ("subscribe".equals(method.getName()) && method.getParameterCount() == 1) {
                        return (AutoCloseable) () -> { };
                    }
                    throw unsupported();
                });
    }

    /** 连接级 Plan 订阅返回可关闭句柄，其余未声明能力仍失败关闭。 */
    public static PlanUseCase passivePlans() {
        return (PlanUseCase) Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                new Class<?>[]{PlanUseCase.class}, (proxy, method, arguments) -> {
                    if (("subscribe".equals(method.getName()) || "subscribePlan".equals(method.getName()))
                            && method.getParameterCount() == 1) {
                        return (AutoCloseable) () -> { };
                    }
                    if ("planTurnControlContext".equals(method.getName())) {
                        return Optional.empty();
                    }
                    throw unsupported();
                });
    }

    /** 无问答场景只允许建立连接订阅，业务调用仍明确失败，避免假实现掩盖接线缺失。 */
    public static InteractionUseCase passiveInteractions() {
        return (InteractionUseCase) Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                new Class<?>[]{InteractionUseCase.class}, (proxy, method, arguments) -> {
                    if ("subscribe".equals(method.getName())) {
                        return (AutoCloseable) () -> { };
                    }
                    throw unsupported();
                });
    }

    /** 未声明的进程端口必须拒绝业务调用，避免无关 RPC 测试意外启动宿主命令。 */
    public static io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase unsupportedProcesses() {
        return (io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase)
                Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                        new Class<?>[]{io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase.class},
                        (proxy, method, arguments) -> switch (method.getName()) {
                            case "close", "closeAt" -> null;
                            case "generation" -> 1L;
                            default -> throw unsupported();
                        });
    }

    /** 审查使用真实 Agent 循环；未配置该循环的夹具不得返回假报告。 */
    public static io.github.kongweiguang.ja.review.application.api.ReviewUseCase unsupportedReviews() {
        return (io.github.kongweiguang.ja.review.application.api.ReviewUseCase)
                Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                        new Class<?>[]{io.github.kongweiguang.ja.review.application.api.ReviewUseCase.class},
                        (proxy, method, arguments) -> { throw unsupported(); });
    }

    /** 无关 RPC 夹具误查会话 MCP 状态时必须失败，避免测试暗中依赖它。 */
    public static ThreadMcpUseCase unsupportedThreadMcp() {
        return (ThreadMcpUseCase) Proxy.newProxyInstance(RpcTestBindings.class.getClassLoader(),
                new Class<?>[]{ThreadMcpUseCase.class}, (proxy, method, arguments) -> {
                    throw unsupported();
                });
    }

    /** 未声明的附件能力必须失败，避免普通 transport 测试意外接触文件系统。 */
    private static final class UnsupportedAttachments implements AttachmentUseCase, AttachmentPreviewUseCase {
        /** 未声明的导入能力失败。 */
        @Override public AttachmentMetadata importDraft(ImportRequest request) { throw unsupported(); }
        /** 未声明的工具图片导入能力失败。 */
        @Override public AttachmentMetadata importMcpImage(String workspaceId, String displayName,
                                                           String mediaType, byte[] content) { throw unsupported(); }
        /** 未声明的丢弃能力失败。 */
        @Override public AttachmentMetadata discard(String attachmentId, Instant discardedAt) { throw unsupported(); }
        /** 未声明的读取能力失败。 */
        @Override public ReadResult read(ReadRequest request) { throw unsupported(); }
        /** RPC 测试默认不配置受管附件元数据。 */
        @Override public java.util.List<AttachmentMetadata> readAuthorizedMetadata(
                String threadId, java.util.List<String> attachmentIds) { throw unsupported(); }
        /** 未声明的回收能力失败。 */
        @Override public void collectGarbage() { throw unsupported(); }
        /** 未声明的预览打开能力失败。 */
        @Override public PreviewDescriptor openPreview(PreviewOpenRequest request) { throw unsupported(); }
        /** 未声明的预览读取能力失败。 */
        @Override public PreviewReadResult readPreview(PreviewReadRequest request) { throw unsupported(); }
        /** 未声明的预览关闭能力失败。 */
        @Override public void closePreview(String previewSessionId) { throw unsupported(); }
    }

    /** 未声明的 Workspace 能力必须失败，避免夹具把缺失行为伪装成成功。 */
    private static final class UnsupportedWorkspaces implements WorkspaceUseCase {
        /** 未声明的注册能力失败。 */
        @Override public Workspace openWorkspace(OpenWorkspace request) { throw unsupported(); }
        /** 未声明的 session 创建能力失败。 */
        @Override public Workspace createSessionWorkspace(String threadId) { throw unsupported(); }
        /** 未声明的创建补偿能力失败。 */
        @Override public void discardUnlinkedSessionWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明的持久目录重开能力失败。 */
        @Override public Workspace openRegisteredWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明的列表能力失败。 */
        @Override public CursorPage<Workspace> listWorkspaces(String cursor, int limit, Workspace.Kind kind) { throw unsupported(); }
        /** 未声明的读取能力失败。 */
        @Override public Optional<Workspace> readWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明的 Workspace 更新能力失败。 */
        @Override public Workspace updateWorkspace(UpdateWorkspace command) { throw unsupported(); }
        /** 未声明的 Turn/Plan admission 能力失败。 */
        @Override public io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceAdmissionPort.Lease
                acquireAdmission(String workspaceId) { throw unsupported(); }
        /** 未声明的已打开能力读取失败。 */
        @Override public Workspace requireOpenWorkspace(String workspaceId) { throw unsupported(); }
        /** 未声明的信任修改能力失败。 */
        @Override public Workspace setWorkspaceTrust(String workspaceId, Workspace.Trust trust) { throw unsupported(); }
        /** 未声明的注销能力失败。 */
        @Override public void unregisterWorkspace(String workspaceId, long expectedRevision) { throw unsupported(); }
        /** 未声明的工作区预热能力失败。 */
        @Override public void refreshPreparedWorkspaces() { throw unsupported(); }
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
        @Override public ThreadSummary updatePreferences(String threadId, io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences value, long revision) { throw unsupported(); }
        /** 未声明的自动标题写入能力失败。 */
        @Override public boolean writeAutomaticTitle(String threadId, String title, long revision) { throw unsupported(); }
        /** 未声明的置顶能力失败。 */
        @Override public ThreadSummary pinThread(String threadId, boolean pinned, long revision) { throw unsupported(); }
        /** 未声明的已读确认能力失败。 */
        @Override public ThreadSummary markThreadSeen(String threadId, long revision) { throw unsupported(); }
        /** 未声明的归档能力失败。 */
        @Override public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的恢复能力失败。 */
        @Override public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的删除能力失败。 */
        @Override public void deleteThread(String threadId, long expectedThreadRevision) { throw unsupported(); }
        /** 未声明的 Turn 查找能力失败。 */
        @Override public Optional<TurnSummary> findTurn(String turnId) { throw unsupported(); }
        /** 通用拒绝夹具不提供冻结文件差异。 */
        @Override public Optional<ChangeSetArtifactFile> readChangeSetArtifact(
                String threadId, String turnId, String artifactId, String filePath) { throw unsupported(); }
        /** 通用拒绝夹具不读取 Thread 正文。 */
        @Override public Optional<ItemContentPage> readItemContent(
                String threadId, String itemId, int offsetCharacters, int limitCharacters) { throw unsupported(); }
        /** 通用拒绝夹具不读取历史 Tool 正文。 */
        @Override public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                                     String artifactId, int offsetCharacters,
                                                                     int limitCharacters) { throw unsupported(); }
        /** 通用拒绝夹具不负责跨 Session 列表。 */
        @Override
        public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) { throw unsupported(); }
        /** 通用夹具没有持久审批，启动观察使用空发现页；专项测试显式提供真实候选。 */
        @Override
        public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) {
            return new CursorPage<>(java.util.List.of(), null);
        }
        /** 通用拒绝夹具不搜索已提交输入。 */
        @Override
        public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) { throw unsupported(); }
        /** 通用拒绝夹具不搜索 Session Thread。 */
        @Override
        public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) { throw unsupported(); }
        /** 通用拒绝夹具不读取 Thread 最新页。 */
        @Override
        public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) { throw unsupported(); }
        /** 通用拒绝夹具不提供用量投影。 */
        @Override
        public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) { throw unsupported(); }
    }

    /** 未声明的 Turn 生命周期能力必须失败。 */
    private static final class UnsupportedTurns implements TurnUseCase {

        /** 未声明的隐藏 Plan Turn 准入能力失败，避免 transport 夹具接管 Plan 生命周期。 */
        @Override
        public Accepted startContinuation(InternalTurnStartRequest request, String instruction,
                ConversationTurnEventSink sink,
                io.github.kongweiguang.ja.conversation.domain.model.NativeExecutionSnapshot inherited) {
            throw unsupported();
        }

        /** 未声明恢复能力的夹具忽略注册，防止测试双倍装配恢复状态机。 */
        @Override
        public void registerResumeContinuation(String turnId,
                java.util.function.Consumer<CompletionStage<TurnResult>> continuation) {
        }

        /** 未声明恢复能力的夹具拒绝 Plan 暂停，避免伪造运行状态。 */
        @Override
        public java.util.concurrent.CompletionStage<Void> suspendPlanRun(String turnId) {
            throw unsupported();
        }
        /** 未声明的接纳能力失败。 */
        @Override public Accepted start(TurnStartRequest request, ConversationTurnEventSink sink) { throw unsupported(); }
        /** 未声明的隐藏继续能力失败。 */
        @Override public Accepted continueQuestion(InternalTurnStartRequest request, ConversationTurnEventSink sink) {
            throw unsupported();
        }
        /** 未声明的恢复回调清理能力失败，避免夹具掩盖错误的 Plan/Turn 耦合。 */
        @Override public void clearResumeContinuation(String turnId) { throw unsupported(); }
        /** 未声明的问题编辑能力失败。 */
        @Override public Accepted reask(TurnStartRequest request, String sourceMessageId, ConversationTurnEventSink sink) {
            throw unsupported();
        }
        /** 该纯拒绝夹具没有本进程派发的 Turn。 */
        @Override public Optional<String> locallyActiveTurnId(String threadId) { return Optional.empty(); }
        /** 该纯拒绝夹具不持有任何特定 Turn 的本地执行句柄。 */
        @Override public boolean locallyOwnsTurn(String threadId, String turnId) { return false; }
        /** 此传输拒绝夹具不创建持久事件观察或后台 watcher。 @author kongweiguang */
        @Override public boolean observeApprovalWait(String turnId, ConversationTurnEventSink sink) { return false; }
        /** 未声明的取消能力失败。 */
        @Override public CancelResult cancel(String turnId) { throw unsupported(); }
        /** 未声明的接纳关闭能力失败。 */
        @Override public void stopAccepting() { throw unsupported(); }
        /** 未声明的原子关闭准入能力失败。 */
        @Override public boolean stopAcceptingIfQuiescent() { throw unsupported(); }
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
        @Override public CompletionStage<ApprovalUseCase.Resolution> resolve(String threadId, String turnId,
                String approvalId, long expectedThreadRevision, ApprovalDecision response,
                String operationKey, String requestFingerprint,
                io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink sink) {
            throw unsupported();
        }
    }

    /** 未声明的 Catalog 能力必须失败。 */
    private static final class UnsupportedCatalog implements CatalogUseCase {
        /** 未声明的 Skill 列表能力失败。 */
        @Override public CursorPage<SkillDescriptor> listSkills(
                String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 未声明的 MCP 列表能力失败。 */
        @Override public CursorPage<McpServerDescriptor> listMcp(String workspaceId, String cursor, int limit) { throw unsupported(); }
        /** 未声明的 MCP 测试能力失败。 */
        @Override public CompletionStage<McpServerDescriptor> testMcp(String workspaceId, String mcpId) { throw unsupported(); }
        /** 未声明的模型测试能力失败，避免 transport 夹具触发真实 Provider。 */
        @Override public CompletionStage<ModelTestResult> testModel(
                String providerId, String modelId, CancellationToken cancellationToken) { throw unsupported(); }
        /** 未声明的上游模型目录能力失败，避免测试夹具发起真实网络请求。 */
        @Override public CompletionStage<ModelDiscoveryResult> discoverModels(
                String providerId, CancellationToken cancellationToken) { throw unsupported(); }
        /** 未声明的 MCP Tool 列表能力失败。 */
        @Override public CursorPage<McpToolDescriptor> readMcpTools(String workspaceId, String mcpId, String cursor, int limit) { throw unsupported(); }
    }

    /** 为所有未声明能力生成相同的显式失败，不泄露调用参数。 */
    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("test capability is not configured");
    }
}
