// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.conversation.application.loop.AgentLoop;
import io.github.kongweiguang.ja.conversation.application.loop.TurnQueue;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleService;
import io.github.kongweiguang.ja.conversation.application.approval.InMemoryApprovalBroker;
import io.github.kongweiguang.ja.conversation.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.conversation.application.context.checkpoint.CheckpointStore;
import io.github.kongweiguang.ja.conversation.application.context.summary.SummaryModel;
import io.github.kongweiguang.ja.conversation.application.approval.ApprovalBroker;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.conversation.port.out.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.cancellation.CancellationCoordinator;
import io.github.kongweiguang.ja.conversation.port.out.ModelPort;
import io.github.kongweiguang.ja.conversation.port.out.JsonValueCodec;
import io.github.kongweiguang.ja.conversation.adapter.out.provider.ModelAdapterFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.runtime.ConfigurationTurnRuntimeResolver;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationCatalog;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.generation.GenerationTurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.adapter.out.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.application.CatalogService;
import io.github.kongweiguang.ja.catalog.port.in.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.port.out.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.configuration.adapter.out.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationWorkspaceTrustUseCase;
import io.github.kongweiguang.ja.configuration.port.out.ConfigurationRuntimePort;
import io.github.kongweiguang.ja.conversation.port.out.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.adapter.out.tools.ShellCapability;
import io.github.kongweiguang.ja.conversation.application.middleware.ApprovalMiddleware;
import io.github.kongweiguang.ja.conversation.application.middleware.MiddlewareChain;
import io.github.kongweiguang.ja.conversation.application.prompt.DefaultAgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.instruction.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.catalog.adapter.out.skills.JaSkillSources;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.NioWorkspaceDirectoryAdapter;
import io.github.kongweiguang.ja.workspace.application.WorkspaceService;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.domain.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.port.out.WorkspacePreparationPort;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceTrustPort;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;

/** 作为单个 Ja sidecar 进程的 Solon 4 组合根与生命周期所有者，避免资源所有权分散。 */
@Component(index = 0)
public final class SolonRuntimeComposition {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolonRuntimeComposition.class);
    private final RuntimeResourceLifecycle lifecycle = new RuntimeResourceLifecycle();

    /** 发布唯一 UTC 时钟，使持久化、Deadline、审批和上下文共享同一时间权威。 */
    @Bean(value = "jaClock", typed = true)
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** 发布严格 JSON 编解码器，并保留 BigDecimal scale 供 Tool、MCP 与持久化边界无损往返。 */
    @Bean(value = "jaObjectMapper", typed = true)
    public ObjectMapper objectMapper() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
                .build();
    }

    /** 向后续组合组件暴露唯一的逆序关闭所有者，避免重复维护资源栈。 */
    @Bean(value = "jaResourceLifecycle", typed = true)
    public RuntimeResourceLifecycle resourceLifecycle() {
        return lifecycle;
    }

    /**
     * 在 Host 发布明确根目录后创建配置运行时适配器；构造阶段不执行配置或认证 I/O，
     * 使配置文件缺失或损坏时 Settings 仍可进入修复流程。
     */
    @Bean(value = "jaConfigurationRuntimePort", typed = true)
    public ConfigurationRuntimePort configurationRuntimePort(ObjectMapper mapper) {
        Path home = AotSideEffectGuard.processing() ? aotHomePath() : resolveHomePath();
        ConfigurationRuntimeAdapter adapter = new ConfigurationRuntimeAdapter(home, mapper);
        return AotSideEffectGuard.processing() ? adapter : lifecycle.own(adapter);
    }

    /**
     * 在出站配置运行时之上发布唯一应用服务，使 RPC、Catalog 和 workspace 只解析入站端口。
     */
    @Bean(value = "jaConfigurationApplicationService", typed = true)
    public ConfigurationApplicationService configurationApplicationService(ConfigurationRuntimePort runtime) {
        return new ConfigurationApplicationService(runtime);
    }

    /**
     * 在组合根把 configuration 入站能力适配为 catalog 自有出站端口，禁止业务包直接反向依赖。
     */
    @Bean(value = "jaCatalogConfigurationGenerationPort", typed = true)
    public ConfigurationGenerationPort catalogConfigurationGenerationPort(
            ConfigurationGenerationUseCase configurations) {
        return new ConfigurationGenerationBridge(configurations);
    }

    /** 由单一所有者打开 Flyway、文件租约和唯一生产 SQLite 数据源，保证逆序释放。 */
    @Bean(value = "jaDatabase", typed = true)
    public JaDatabase database() {
        if (AotSideEffectGuard.processing()) return null;
        AotSideEffectGuard.requireRuntimeIo();
        return lifecycle.own(JaDatabase.open(DatabaseConfig.of(resolveDatabasePath())));
    }

    /** 发布供官方 MyBatis-Solon 适配器使用的具名数据源，AOT 阶段仅提供内存占位。 */
    @Bean(value = "ja", typed = true)
    public DataSource dataSource(@Inject(required = false) JaDatabase database) {
        if (AotSideEffectGuard.processing()) return aotDataSource();
        if (database == null) throw new IllegalStateException("Ja database bean is unavailable");
        return database.dataSource();
    }

    /** 将 Provider 工厂发布为 Solon 资源，避免为每个 RPC 对象图重复创建连接资源。 */
    @Bean(value = "jaModelAdapterFactory", typed = false)
    public ModelAdapterFactory modelAdapterFactory(Clock clock) {
        if (AotSideEffectGuard.processing()) return null;
        AotSideEffectGuard.requireRuntimeIo();
        return lifecycle.own(new ModelAdapterFactory(clock));
    }

    /** 运行阶段只复用生产 Provider 工厂；AOT 占位仅供 Solon 分析 Bean 图且拒绝任何调用。 */
    @Bean(value = "jaModelPort", typed = true)
    public ModelPort modelPort(@Inject(value = "jaModelAdapterFactory", required = false)
                               ModelAdapterFactory modelFactory) {
        if (AotSideEffectGuard.processing()) {
            return (request, eventSink, cancellationToken) ->
                    java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("Provider is unavailable during AOT processing"));
        }
        AotSideEffectGuard.requireRuntimeIo();
        if (modelFactory == null) throw new IllegalStateException("Ja Provider factory bean is unavailable");
        return modelFactory;
    }

    /** 只发布一次不可变 Skill 源读取器，每个 Turn 的快照仍保持请求级作用域。 */
    @Bean(value = "jaSkillSources", typed = true)
    public JaSkillSources skillSources() {
        return new JaSkillSources();
    }

    /**
     * AGENTS catalog 复用持久 scope 仓储与全局时钟；文件正文仍在每次 Prompt 刷新时读取，
     * 避免把可能变化的项目规则缓存为进程级事实。
     */
    @Bean(value = "jaAgentInstructionCatalog", typed = true)
    public AgentInstructionCatalog instructionCatalog(InstructionScopeRepository scopes, Clock clock) {
        return new AgentInstructionCatalog(scopes, clock);
    }

    /** 每个 Turn 由工厂创建独占 Prompt Session，禁止跨 Turn 共享激活 Skill 和 revision。 */
    @Bean(value = "jaAgentPromptSessionFactory", typed = true)
    public AgentPromptSessionFactory promptSessionFactory(AgentInstructionCatalog instructions) {
        return new DefaultAgentPromptSessionFactory(instructions);
    }

    /**
     * 基于规范 Ja Home 发布不可变代际目录；Settings 探测不得继承进程 cwd，
     * 也不得伪装为某个 Turn 的工作区。
     */
    @Bean(value = "jaCatalog", typed = true)
    public GenerationCatalog catalog(ObjectMapper mapper) {
        if (AotSideEffectGuard.processing()) {
            return new GenerationCatalog(aotHomePath(), mapper, McpLimits.DEFAULT);
        }
        return lifecycle.own(new GenerationCatalog(resolveHomePath(), mapper, McpLimits.DEFAULT));
    }

    /**
     * 发布不携带 cwd 的逐 Turn MCP 会话工厂；每次打开都必须绑定冻结的配置代际租约、
     * Provider/Model 身份以及命令对应的规范工作区根目录。
     */
    @Bean(value = "jaTurnMcpSessionFactory", typed = true)
    public GenerationTurnMcpSessionFactory turnMcpSessionFactory(ObjectMapper mapper,
                                                                 GenerationCatalog catalog) {
        return new GenerationTurnMcpSessionFactory(mapper, McpLimits.DEFAULT, catalog);
    }

    /** 发布只含 catalog 用例的应用边界，RPC 不直接持有 MCP 或 Skill adapter。 */
    @Bean(value = "jaCatalogUseCase", typed = true)
    public CatalogUseCase catalogUseCase(GenerationCatalog catalog,
                                         ConfigurationGenerationPort configurations,
                                         ModelPort models) {
        return new CatalogService(catalog, configurations, models);
    }

    /** Shell 只影响 Tool 集；预检失败时保留可用的配置、历史与 RPC 恢复面。 */
    @Bean(value = "jaShellCapability", typed = true)
    public ShellCapability shellCapability() {
        ShellCapability capability = ShellCapability.detectAndPreflight();
        if (capability.profile().isEmpty()) {
            LOGGER.warn("Shell capability is unavailable; starting Ja App Server without the shell tool");
        }
        return capability;
    }

    /**
     * 组合配置代际、Skill、MCP 和 Host Tool 出站适配器；TurnService 只依赖 Resolver 端口，
     * bootstrap 不会重新解释 Provider/Model 选择、预算或 Tool 集合。
     */
    @Bean(value = "jaTurnRuntimeResolver", typed = true)
    public ConfigurationTurnRuntimeResolver turnRuntimeResolver(
            ConfigurationGenerationPort configurations,
            JaSkillSources skills,
            ShellCapability shellCapability,
            GenerationCatalog catalog,
            GenerationTurnMcpSessionFactory turnMcpSessions,
            AgentPromptSessionFactory promptSessions,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase attachments) {
        return new ConfigurationTurnRuntimeResolver(configurations, skills,
                Path.of(System.getProperty("user.home"), ".agents", "skills"),
                resolveHomePath().resolve("skills"), shellCapability, catalog, turnMcpSessions, promptSessions,
                attachmentReader(attachments));
    }

    /** bootstrap 只做消费者自有出站端口桥接，Tool 永远不能直接依赖附件入站用例。 */
    private static io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader attachmentReader(
            io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase attachments) {
        return request -> {
            io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase.ReadResult result = attachments.read(
                    new io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase.ReadRequest(
                            request.attachmentId(), request.threadId(), request.offsetBytes(), request.maxBytes()));
            return new io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader.ReadResult(
                    result.metadata().attachmentId(), result.metadata().displayName(), result.metadata().sizeBytes(),
                    result.metadata().mediaKind().name().toLowerCase(java.util.Locale.ROOT),
                    result.metadata().mediaType(), result.offsetBytes(), result.nextOffsetBytes(),
                    result.endOfFile(), result.encoding(), result.content());
        };
    }

    /** 发布无状态工作区策略，使应用服务和聚焦测试共享同一身份规则。 */
    @Bean(value = "jaWorkspacePolicy", typed = true)
    public WorkspacePolicy workspacePolicy() {
        return new WorkspacePolicy();
    }

    /** 冻结 Host 发布的数据目录，但直到 workspace/open-general 才执行目录 IO。 */
    @Bean(value = "jaWorkspaceDirectoryPort", typed = true)
    public WorkspaceDirectoryPort workspaceDirectoryPort() {
        return new NioWorkspaceDirectoryAdapter(resolveDataPath());
    }

    /** 用窄端口把工作区预热委派给 Turn Resolver，不暴露配置租约或 MCP adapter。 */
    @Bean(value = "jaWorkspacePreparationPort", typed = true)
    public WorkspacePreparationPort workspacePreparationPort(TurnRuntimeResolver runtimeResolver) {
        return runtimeResolver::prepareWorkspace;
    }

    /** 将持久化后的信任事实交给配置入站端口，workspace 不依赖文件适配器。 */
    @Bean(value = "jaWorkspaceTrustPort", typed = true)
    public WorkspaceTrustPort workspaceTrustPort(ConfigurationWorkspaceTrustUseCase configuration) {
        return (root, trust) -> configuration.synchronize(root, trust == Workspace.Trust.TRUSTED);
    }

    /** 组合 Repository、目录、预热和信任端口，发布唯一工作区域入站用例。 */
    @Bean(value = "jaWorkspaceUseCase", typed = true)
    public WorkspaceService workspaceService(
            MybatisHistoryService repository,
            WorkspaceDirectoryPort directories,
            WorkspacePreparationPort preparation,
            WorkspaceTrustPort trust,
            WorkspacePolicy policy,
            Clock clock) {
        return new WorkspaceService(repository, directories, preparation, trust, policy, clock);
    }

    /** 发布 AgentLoop 与 JA-RPC 响应共同使用的同一个审批代理，避免审批状态分叉。 */
    @Bean(value = "jaApprovalBroker", typed = true)
    public InMemoryApprovalBroker approvalBroker(Clock clock) {
        InMemoryApprovalBroker broker =
                new InMemoryApprovalBroker(clock, 1_024, 8_192, Duration.ofMinutes(10));
        return AotSideEffectGuard.processing() ? broker : lifecycle.own(broker);
    }

    /**
     * 静态注册唯一 Middleware 链；Shell 环境已经冻结在 Turn Runtime，Middleware 只负责审批短路。
     */
    @Bean(value = "jaMiddlewareChain", typed = true)
    public MiddlewareChain middlewareChain() {
        return new MiddlewareChain(List.of(new ApprovalMiddleware()));
    }

    /**
     * 发布持久化 Tool 参数跨越上下文边界时使用的唯一 JSON 语法；编解码器保持无状态，
     * 因而不会成为第二个持久化或 ObjectMapper 所有者。
     */
    @Bean(value = "jaJsonValueCodec", typed = true)
    public JsonValueCodec jsonValueCodec(ObjectMapper mapper) {
        return new JacksonJsonValueCodec(mapper);
    }

    /** 持有唯一取消注册表，使排队中与运行中的 Turn 共享同一关闭闸门。 */
    @Bean(value = "jaCancellationCoordinator", typed = true)
    public DefaultCancellationCoordinator cancellationCoordinator() {
        DefaultCancellationCoordinator coordinator = new DefaultCancellationCoordinator();
        return AotSideEffectGuard.processing() ? coordinator : lifecycle.own(coordinator);
    }

    /**
     * 在 Settings 目录所有者之后创建唯一 AgentLoop，并注入独立的逐 Turn MCP 会话工厂，
     * 确保探测使用的 cwd 或状态不会被复用为执行会话。
     */
    @Bean(value = "jaAgentLoop", typed = true)
    public AgentLoop agentLoop(ModelPort model, ApprovalBroker approvals,
                               ConversationRepository store,
                               io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory contexts,
                               JsonValueCodec argumentsCodec,
                               MiddlewareChain middleware, Clock clock) {
        AgentLoop loop = new AgentLoop(
                model, approvals, store, contexts, argumentsCodec, middleware, clock);
        return AotSideEffectGuard.processing() ? loop : lifecycle.own(loop);
    }

    /** 独立于可变配置快照持有有界准入队列，确保并发上限在代际切换时保持稳定。 */
    @Bean(value = "jaTurnQueue", typed = true)
    public TurnQueue turnQueue() {
        TurnQueue queue = new TurnQueue(64, 8, 8);
        return AotSideEffectGuard.processing() ? queue : lifecycle.own(queue);
    }

    /**
     * 复用真实 Provider、Thread CAS 与独立 usage ledger 组装标题服务；AOT 分析不得创建后台线程。
     */
    @Bean(value = "jaAutomaticThreadTitleScheduler", typed = true)
    public AutomaticThreadTitleScheduler automaticThreadTitles(
            ModelPort models,
            @Inject(value = "jaHistory", required = true) ThreadUseCase threads,
            @Inject(value = "jaAutomaticTitleUsage", required = true)
            AutomaticTitleUsagePort usage, Clock clock) {
        return new AutomaticThreadTitleService(models, threads, usage, clock);
    }

    /**
     * 将唯一 Turn 生命周期 Bean 作为依赖关闭屏障；其有界停止、取消和静默必须先完成，
     * 随后才能关闭队列、循环、取消协调器、持久化层或数据库。
     */
    @Bean(value = "jaTurnService", typed = true)
    public TurnService turnService(ConversationRepository store, AgentLoop loop, TurnQueue queue,
                                   CancellationCoordinator cancellations,
                                   TurnRuntimeResolver runtimeResolver, Clock clock,
                                   AutomaticThreadTitleScheduler automaticTitles) {
        TurnService service = new TurnService(
                store, loop, queue, cancellations, runtimeResolver, clock, automaticTitles);
        return AotSideEffectGuard.processing() ? service : lifecycle.ownShutdownFence(service);
    }

    /** 复用完整生产上下文与 Provider 端口发布手动压缩用例，不创建第二套状态或配置 Owner。 */
    @Bean(value = "jaManualContextCompactionService", typed = true)
    public io.github.kongweiguang.ja.conversation.application.loop.ManualContextCompactionService manualContextCompactionService(
            ConversationRepository conversations, CheckpointStore checkpoints,
            WorkspaceUseCase workspaces, TurnRuntimeResolver runtimes,
            io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory contexts,
            ModelPort models, JsonValueCodec argumentsCodec, Clock clock) {
        return new io.github.kongweiguang.ja.conversation.application.loop.ManualContextCompactionService(
                conversations, checkpoints, workspaces, runtimes, contexts, models, argumentsCodec, clock);
    }

    /** 向 bootstrap 使用方暴露绑定所需的 Kernel 工厂，同时避免创建第二套上下文对象图。 */
    @Bean(value = "jaContextOrchestratorFactory", typed = true)
    public ContextOrchestratorFactory contextOrchestratorFactory(
            io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory contexts) {
        return new ContextOrchestratorFactory(contexts);
    }

    /**
     * 发布由真实 Provider 支持摘要接缝的 Kernel 上下文工厂；在 RPC 视图移除前，
     * bootstrap 投影保持独立命名，避免两类 Bean 身份冲突。
     */
    @Bean(value = "jaKernelContextOrchestratorFactory", typed = true)
    public io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory kernelContextOrchestratorFactory(
            CheckpointStore checkpoints, Clock clock,
            @Inject(value = "jaSummaryModelFactory", required = true)
            SummaryModel.Factory summaryModels) {
        return new io.github.kongweiguang.ja.conversation.application.context.ContextOrchestratorFactory(
                checkpoints, clock, summaryModels);
    }

    /** 发布与具体 Provider 解耦的摘要工厂，组合阶段不得提前打开模型请求。 */
    @Bean(value = "jaSummaryModelFactory", typed = false)
    public SummaryModel.Factory summaryModelFactory(
            @Inject(value = "jaModelAdapterFactory", required = false) ModelAdapterFactory modelFactory) {
        if (AotSideEffectGuard.processing()) {
            return binding -> prompt -> {
                throw new IllegalStateException("summary model is unavailable during AOT processing");
            };
        }
        if (modelFactory == null) throw new IllegalStateException("Ja Provider factory bean is unavailable");
        return modelFactory;
    }

    /** 基于 Solon 持有的精确运行时 Bean 身份发布握手后视图工厂，避免复制服务对象图。 */
    @Bean(value = "jaRuntimeServicesFactory", typed = true)
    public RuntimeServicesFactory runtimeServicesFactory(
            WorkspaceUseCase workspaces,
            MybatisHistoryService threads,
            TurnService turns,
            io.github.kongweiguang.ja.conversation.port.in.ContextCompactionUseCase compactions,
            InMemoryApprovalBroker approvals,
            CatalogUseCase catalog,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase attachments) {
        if (AotSideEffectGuard.processing()) return null;
        return new RuntimeServicesFactory(
                workspaces, threads, turns, compactions, approvals, catalog, attachments, lifecycle::close);
    }

    /** 即使前序资源关闭失败也继续逆序关闭已成功创建的 Bean，以尽可能释放全部资源。 */
    @Destroy
    public void close() {
        lifecycle.close();
    }

    /** 只解析 Host 发布的数据库或数据目录，Java 不再自行派生另一套 Home。 */
    static Path resolveDatabasePath() {
        String explicit = System.getProperty("ja.database-path");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        String dataDirectory = System.getProperty("ja.data-dir");
        if (dataDirectory != null && !dataDirectory.isBlank()) {
            return Path.of(dataDirectory).toAbsolutePath().normalize().resolve("ja.db");
        }
        throw new IllegalStateException("Ja database path was not published by the Host");
    }

    /** 解析 Host 发布的数据目录；AOT 只返回无副作用占位路径，运行时绝不回退到进程 cwd。 */
    static Path resolveDataPath() {
        if (AotSideEffectGuard.processing()) {
            return aotHomePath().resolve("data");
        }
        Path database = resolveDatabasePath();
        Path dataDirectory = database.getParent();
        if (dataDirectory == null) {
            throw new IllegalStateException("Ja database path has no data directory");
        }
        return dataDirectory;
    }

    /**
     * 派生唯一规范 Ja Home，并拒绝无法确定 Home 所有者的浅层显式数据库路径。
     * Solon AOT 会完整装配 Bean 图但不会发布 Host 参数，因此必须返回无副作用占位路径，
     * 不能为了生成 Native metadata 而放宽生产启动的路径所有权校验。
     */
    static Path resolveHomePath() {
        if (AotSideEffectGuard.processing()) {
            return aotHomePath();
        }
        String explicit = System.getProperty("ja.home-dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }
        Path database = resolveDatabasePath();
        Path dataDirectory = database.getParent();
        Path home = dataDirectory == null ? null : dataDirectory.getParent();
        if (home == null) throw new IllegalStateException("Ja database path has no canonical home");
        return home;
    }

    /** 在不打开连接或访问构建主机文件系统的前提下提供 MyBatis AOT 元数据。 */
    private static DataSource aotDataSource() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:file:ja-solon-aot?mode=memory&cache=shared");
        return dataSource;
    }

    /** 提供无需连接的绝对目录根路径，其值不会被运行时镜像保留。 */
    private static Path aotHomePath() {
        return Path.of(System.getProperty("java.io.tmpdir"), "ja-solon-aot").toAbsolutePath().normalize();
    }
}
