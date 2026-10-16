// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionCursorCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionEventCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.model.ModelAdapterFactory;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.bootstrap.runtime.ConfigurationTurnRuntimeResolver;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation.GenerationCatalog;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.generation.GenerationTurnMcpSessionFactory;
import io.github.kongweiguang.ja.catalog.infrastructure.mcp.support.McpLimits;
import io.github.kongweiguang.ja.catalog.infrastructure.skills.JaSkillCatalog;
import io.github.kongweiguang.ja.catalog.infrastructure.acl.ThreadMcpIdentity;
import io.github.kongweiguang.ja.catalog.application.service.CatalogService;
import io.github.kongweiguang.ja.catalog.application.service.ThreadMcpService;
import io.github.kongweiguang.ja.catalog.application.api.CatalogUseCase;
import io.github.kongweiguang.ja.catalog.application.api.ThreadMcpUseCase;
import io.github.kongweiguang.ja.catalog.application.gateway.ConfigurationGenerationPort;
import io.github.kongweiguang.ja.catalog.application.gateway.CatalogWorkspacePort;
import io.github.kongweiguang.ja.catalog.infrastructure.acl.CatalogConfigurationAcl;
import io.github.kongweiguang.ja.catalog.infrastructure.acl.CatalogThreadAcl;
import io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter;
import io.github.kongweiguang.ja.configuration.application.service.ConfigurationApplicationService;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationWorkspaceTrustUseCase;
import io.github.kongweiguang.ja.configuration.application.gateway.ConfigurationRuntimePort;
import io.github.kongweiguang.ja.conversation.infrastructure.runtime.ConfigurationJaRequestBindingSource;
import io.github.kongweiguang.ja.conversation.infrastructure.runtime.JaAgentRuntimeResolver;
import io.github.kongweiguang.ja.conversation.infrastructure.acl.ConversationAttachmentAcl;
import io.github.kongweiguang.ja.conversation.infrastructure.acl.ConversationWorkspaceReferenceAcl;
import io.github.kongweiguang.ja.conversation.infrastructure.acl.WorkspaceWriteClaimAcl;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.JaToolBindingCodec;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.AttachmentManagedToolMediaImporter;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.ShellCapability;
import io.github.kongweiguang.ja.conversation.application.admission.JaAdmissionParticipants;
import io.github.kongweiguang.ja.conversation.application.gateway.PreparedAdmissionRuntimePort;
import io.github.kongweiguang.ja.conversation.application.approval.JaApprovalService;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionService;
import io.github.kongweiguang.ja.conversation.application.interaction.InteractionToolFactory;
import io.github.kongweiguang.ja.conversation.application.loop.WorkspaceWriteLeaseCoordinator;
import io.github.kongweiguang.ja.conversation.application.prompt.DefaultAgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.application.service.JaExecutionContexts;
import io.github.kongweiguang.ja.conversation.application.service.TurnService;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleScheduler;
import io.github.kongweiguang.ja.conversation.application.title.AutomaticThreadTitleService;
import io.github.kongweiguang.ja.conversation.application.service.AgentInstructionCatalog;
import io.github.kongweiguang.ja.conversation.application.api.ApprovalUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger;
import io.github.kongweiguang.ja.conversation.infrastructure.tools.process.ProcessSessionRegistry;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.review.application.service.ReviewService;
import io.github.kongweiguang.ja.review.interfaces.tools.ReviewTurnRuntimeMiddlewareFactory;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.AdmissionParticipantResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentExecutionEvents;
import io.github.kongweiguang.ja.conversation.application.gateway.AgentPromptSessionFactory;
import io.github.kongweiguang.ja.conversation.application.gateway.AutomaticTitleUsagePort;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.InitialContextSeedSource;
import io.github.kongweiguang.ja.conversation.application.gateway.InstructionScopeRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.InteractionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.JaRequestBindingSource;
import io.github.kongweiguang.ja.conversation.application.gateway.PendingQueueInputSource;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicyRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.TaskCapabilityCeilingPort;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnChangeSetSerialization;
import io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeResolver;
import io.github.kongweiguang.ja.conversation.application.gateway.WorkspaceBindingResolver;
import io.github.kongweiguang.ja.plan.interfaces.tools.PlanToolFactory;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanExtensionFactApplier;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanExecutionAdmissionParticipantResolver;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.PlanToolAttemptFactApplier;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.MybatisPlanRepository;
import io.github.kongweiguang.ja.plan.infrastructure.runtime.PlanCommittedEventListener;
import io.github.kongweiguang.ja.plan.application.service.PlanCommittedPublisher;
import io.github.kongweiguang.ja.plan.application.service.PlanEventRegistry;
import io.github.kongweiguang.ja.plan.application.service.PlanExecutionCoordinator;
import io.github.kongweiguang.ja.plan.application.service.PlanExecutionTurnAdapter;
import io.github.kongweiguang.ja.plan.application.service.PlanService;
import io.github.kongweiguang.ja.plan.application.service.PlanStartupRecovery;
import io.github.kongweiguang.ja.plan.application.service.PlanToolCommandCodec;
import io.github.kongweiguang.ja.plan.application.service.PlanToolExecutionLedger;
import io.github.kongweiguang.ja.plan.application.api.PlanUseCase;
import io.github.kongweiguang.ja.plan.application.gateway.PlanConversationPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTaskPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanTurnPort;
import io.github.kongweiguang.ja.plan.application.gateway.PlanWorkspacePort;
import io.github.kongweiguang.ja.plan.infrastructure.acl.PlanConversationAcl;
import io.github.kongweiguang.ja.plan.infrastructure.acl.PlanTaskAcl;
import io.github.kongweiguang.ja.plan.infrastructure.acl.PlanTurnAcl;
import io.github.kongweiguang.ja.plan.infrastructure.acl.PlanWorkspaceAcl;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.infrastructure.persistence.database.DatabaseConfig;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.startup.ApprovalExpiryRecoveryListener;
import io.github.kongweiguang.ja.infrastructure.persistence.startup.ApprovalExpiryStartupRecovery;
import io.github.kongweiguang.ja.infrastructure.persistence.startup.StartupMaintenanceService;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAdmissionParticipantFactory;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentEventBridge;
import io.github.kongweiguang.ja.infrastructure.persistence.store.JaTransactionParticipant;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.collaboration.interfaces.tools.TaskAgentToolGateway;
import io.github.kongweiguang.ja.collaboration.infrastructure.persistence.TaskInitialContextSeedSource;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskAdmissionParticipantResolver;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskAdmissionGatewayAdapter;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.ConversationBoundaryAdapter;
import io.github.kongweiguang.ja.collaboration.application.service.TaskCoordinator;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskAdmissionGateway;
import io.github.kongweiguang.ja.collaboration.application.gateway.ConversationBoundary;
import io.github.kongweiguang.ja.collaboration.infrastructure.acl.TaskMailboxQueueInputSource;
import io.github.kongweiguang.ja.collaboration.application.api.TaskUseCase;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskMailboxPort;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.NioWorkspaceDirectoryAdapter;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.NioWorkspacePathAdapter;
import io.github.kongweiguang.ja.workspace.application.service.WorkspacePathService;
import io.github.kongweiguang.ja.workspace.application.service.WorkspaceService;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspacePolicy;
import io.github.kongweiguang.ja.workspace.application.api.WorkspacePathSearchUseCase;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceReferenceValidator;
import io.github.kongweiguang.ja.workspace.application.api.WorkspaceUseCase;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceDirectoryPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspacePathPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspacePreparationPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceTrustPort;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * 作为单个 Ja sidecar 进程的 Solon 4 组合根与生命周期所有者，避免资源所有权分散。
 */
@Component(index = 0)
public final class SolonRuntimeComposition {
    private static final Logger LOGGER = LoggerFactory.getLogger(SolonRuntimeComposition.class);
    private final RuntimeResourceLifecycle lifecycle = runtimeLifecycle();

    /**
     * 将资源栈保存在 bootstrap 可取得的位置，避免 Solon 清理失败上下文时丢失关闭句柄。
     */
    private static RuntimeResourceLifecycle runtimeLifecycle() {
        RuntimeResourceLifecycle supplied = Solon.context() == null ? null
                : Solon.context().getBean("jaBootstrapLifecycle");
        return supplied == null ? new RuntimeResourceLifecycle() : supplied;
    }
    private final DeferredTurnRuntimeResolver deferredRuntimeResolver = new DeferredTurnRuntimeResolver();

    /**
     * bootstrap 只做消费者自有出站端口桥接，Tool 永远不能直接依赖附件入站用例；包级可见性
     * 仅用于组合根合同测试，避免测试反射私有实现。
     */
    static io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader attachmentReader(
            io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments) {
        return request -> {
            try {
                io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase.ReadResult result = attachments.read(
                        new io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase.ReadRequest(
                                request.attachmentId(), request.threadId(),
                                request.offsetBytes(), request.maxBytes()));
                return new io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader.ReadResult(
                        result.metadata().attachmentId(), result.metadata().displayName(), result.metadata().sizeBytes(),
                        result.metadata().mediaKind().name().toLowerCase(java.util.Locale.ROOT),
                        result.metadata().mediaType(), result.offsetBytes(), result.nextOffsetBytes(),
                        result.endOfFile(), result.encoding(), result.content());
            } catch (io.github.kongweiguang.ja.attachment.domain.model.AttachmentFailure failure) {
                throw new io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader.ReadFailure(failure);
            }
        };
    }

    /**
     * 只解析 Host 发布的绝对数据库或数据目录，错误的相对值不能借 cwd 成为另一套存储根。
     */
    static Path resolveDatabasePath() {
        String explicit = System.getProperty("ja.database-path");
        if (explicit != null && !explicit.isBlank()) {
            return absolutePublishedPath(explicit);
        }
        String dataDirectory = System.getProperty("ja.data-dir");
        if (dataDirectory != null && !dataDirectory.isBlank()) {
            return absolutePublishedPath(dataDirectory).resolve("ja.db");
        }
        throw new IllegalStateException("Ja database path was not published by the Host");
    }

    /**
     * 解析 Host 发布的数据目录；AOT 只返回无副作用占位路径，运行时绝不回退到进程 cwd。
     */
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
            return absolutePublishedPath(explicit);
        }
        Path database = resolveDatabasePath();
        Path dataDirectory = database.getParent();
        Path home = dataDirectory == null ? null : dataDirectory.getParent();
        if (home == null) {
            throw new IllegalStateException("Ja database path has no canonical home");
        }
        return home;
    }

    /** Host 是目录所有者；检查原始路径的绝对性后才规范化，禁止静默继承进程 cwd。 */
    private static Path absolutePublishedPath(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalStateException("Ja Host-published path must be absolute");
        }
        return path.normalize();
    }

    /**
     * 在不打开连接或访问构建主机文件系统的前提下提供 MyBatis AOT 元数据。
     */
    private static DataSource aotDataSource() {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:file:ja-solon-aot?mode=memory&cache=shared");
        return dataSource;
    }

    /**
     * 提供无需连接的绝对目录根路径，其值不会被运行时镜像保留。
     */
    private static Path aotHomePath() {
        return Path.of(System.getProperty("java.io.tmpdir"), "ja-solon-aot").toAbsolutePath().normalize();
    }

    /**
     * 发布唯一 UTC 时钟，使持久化、Deadline、审批和上下文共享同一时间权威。
     */
    @Bean(value = "jaClock", typed = true)
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 发布严格 JSON 编解码器，并保留 BigDecimal scale 供 Tool、MCP 与持久化边界无损往返。
     */
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

    /**
     * 向后续组合组件暴露唯一的逆序关闭所有者，避免重复维护资源栈。
     */
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
        ConfigurationRuntimeAdapter supplied = Solon.context() == null ? null
                : Solon.context().getBean("jaBootstrapConfiguration");
        if (supplied != null) {
            return supplied;
        }
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

    /**
     * 由单一所有者打开 Flyway、文件租约和唯一生产 SQLite 数据源，保证逆序释放。
     */
    @Bean(value = "jaDatabase", typed = true)
    public JaDatabase database() {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        AotSideEffectGuard.requireRuntimeIo();
        return lifecycle.own(JaDatabase.open(DatabaseConfig.of(resolveDatabasePath(), resolveHomePath())));
    }

    /**
     * 发布供官方 MyBatis-Solon 适配器使用的具名数据源，AOT 阶段仅提供内存占位。
     */
    @Bean(value = "ja", typed = true)
    public DataSource dataSource(@Inject(required = false) JaDatabase database) {
        if (AotSideEffectGuard.processing()) {
            return aotDataSource();
        }
        if (database == null) {
            throw new IllegalStateException("Ja database bean is unavailable");
        }
        return database.dataSource();
    }

    /**
     * 通用执行事实编码不依赖 Ja 配置；同一 Bean 保证循环、压缩与迁移使用相同的版本规则。
     */
    @Bean(value = "jaExecutionRecordCodec", typed = true)
    public io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec executionRecordCodec(
            ObjectMapper mapper) {
        return new io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec(mapper);
    }

    /**
     * Core 控制记录使用唯一版本化 codec，Ja 查询等待状态时不自行解释原始 JSON。
     */
    @Bean(value = "jaExecutionControlCodec", typed = true)
    public ExecutionControlCodec executionControlCodec(ObjectMapper mapper) {
        return new ControlPayloadCodec(mapper);
    }

    /**
     * durable event 的编码规则由 Engine 与 Ja 投影共用，避免事件水位恢复出现双重解析。
     */
    @Bean(value = "jaExecutionEventCodec", typed = true)
    public ExecutionEventCodec executionEventCodec(ObjectMapper mapper) {
        return new JacksonExecutionEventCodec(mapper);
    }

    /**
     * 子任务恢复游标与 Engine 使用同一闭集状态 codec，不使用反射或兼容旧格式。
     */
    @Bean(value = "jaExecutionCursorCodec", typed = true)
    public ExecutionCursorCodec executionCursorCodec(ObjectMapper mapper) {
        return new TurnExecutionStateCodec(mapper);
    }

    /**
     * 扩展事实 codec 只操作固定 JsonValue 结构，无需注入序列化器；仍由 JaStore 参与者同步投影。
     */
    @Bean(value = "jaExtensionFactRecordCodec", typed = true)
    public ExtensionFactRecordCodec extensionFactRecordCodec() {
        return new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();
    }

    /**
     * Request-scoped native context 只保留在 Ja 内存，不出现在 core request binding 中。
     */
    @Bean(value = "jaExecutionContexts", typed = true)
    public JaExecutionContexts jaExecutionContexts() {
        return new JaExecutionContexts();
    }

    /**
     * App Server 单本地主体只在组合根声明一次，供 Core keys、Thread owner 和执行身份共用。
     */
    @Bean(value = "jaAppServerIdentity", typed = true)
    public AppServerIdentity appServerIdentity() {
        return AppServerIdentity.local();
    }

    /**
     * Child admission 的短期注册表只跨越同步准入，不作为执行状态或恢复来源。
     */
    @Bean(value = "jaAdmissionParticipants", typed = true)
    public JaAdmissionParticipants jaAdmissionParticipants() {
        return new JaAdmissionParticipants();
    }

    /**
     * Thread/Turn 的业务准入事实参与同一 AgentStore admission 事务。
     */
    @Bean(value = "jaAdmissionParticipantFactory", typed = true)
    public AdmissionParticipantFactory jaAdmissionParticipantFactory() {
        return new JaAdmissionParticipantFactory();
    }

    /**
     * 组合 Task 投影与 Plan 冻结 binding 的 staged admission；AOT 只登记空 resolver，不触碰数据库。
     */
    @Bean(value = "jaTaskAdmissionParticipantResolver", typed = true)
    public AdmissionParticipantResolver taskAdmissionParticipantResolver(
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository tasks,
            @Inject(value = "jaPlanRepository", required = false) MybatisPlanRepository plans,
            ExecutionRecordCodec records, ExecutionCursorCodec cursors, ObjectMapper mapper) {
        if (AotSideEffectGuard.processing()) {
            return facts -> java.util.Optional.empty();
        }
        if (tasks == null || plans == null) {
            throw new IllegalStateException("Ja admission repositories are unavailable");
        }
        AdmissionParticipantResolver taskResolver = new TaskAdmissionParticipantResolver(tasks, records, cursors);
        return new PlanExecutionAdmissionParticipantResolver(taskResolver, plans, mapper);
    }

    /**
     * SQLiteAgentStore 是唯一物理执行存储；业务适配器与初始上下文冻结共用此 raw 实例。
     */
    @Bean(value = "jaRawAgentStore", typed = true)
    public AgentStore rawAgentStore(DataSource dataSource) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        AotSideEffectGuard.requireRuntimeIo();
        return lifecycle.own(new io.github.kongweiguang.ja.agent.store.local.infrastructure.SQLiteAgentStore(dataSource));
    }

    /**
     * 复用 Task 终态和附件仓储，使 Task receipt、ToolResult 附件 owner 与通用执行事实共享 Store 事务。
     * AOT 审查可能先于仓储装配，允许这些依赖暂缺并在返回前退出；真实运行仍要求全部齐全。
     */
    @Bean(value = "jaAgentStore", typed = true)
    public AgentStore agentStore(@Inject(value = "jaRawAgentStore", required = false) AgentStore local,
                                 DataSource dataSource, ObjectMapper mapper,
                                 @Inject(value = "jaPlanExtensionFactApplier", required = false)
                                 PlanExtensionFactApplier planCommands,
                                 @Inject(value = "jaPlanToolAttemptFactApplier", required = false)
                                 PlanToolAttemptFactApplier planAttempts,
                                 @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository taskRepository,
                                 @Inject(value = "jaAttachmentRepository", required = false)
                                 MybatisAttachmentRepository attachments) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (local == null) {
            throw new IllegalStateException("Ja raw AgentStore is unavailable");
        }
        if (taskRepository == null) {
            throw new IllegalStateException("Ja Task terminal projection is unavailable");
        }
        if (attachments == null) {
            throw new IllegalStateException("Ja attachment repository is unavailable");
        }
        if (planCommands == null || planAttempts == null) {
            throw new IllegalStateException("Ja Plan transaction appliers are unavailable");
        }
        return new io.github.kongweiguang.ja.infrastructure.persistence.store.JaAgentStore(local,
                new JaTransactionParticipant(mapper, List.of(planCommands, planAttempts), taskRepository, attachments),
                new io.github.kongweiguang.ja.infrastructure.persistence.store.JaConversationParticipant());
    }

    /**
     * 为 Plan Tool 命令冻结唯一规范化 Plan JSON 规则，事务 applier 不依赖 PlanService 生命周期。
     */
    @Bean(value = "jaCanonicalPlanJson", typed = true)
    public io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson canonicalPlanJson(ObjectMapper mapper) {
        return new io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson(mapper);
    }

    /**
     * Tool 参数校验与同事务事实应用共用同一命令 codec，避免解析闭集出现偏差。
     */
    @Bean(value = "jaPlanToolCommandCodec", typed = true)
    public PlanToolCommandCodec planToolCommandCodec() {
        return new PlanToolCommandCodec();
    }

    /**
     * Plan 命令 applier 直接操作当前事务连接；代际按 Bean 名注入。Solon AOT 允许审查顺序暂缺该 Bean，
     * 但真实运行必须使用持久分配的代际，不能退回占位值。
     */
    @Bean(value = "jaPlanExtensionFactApplier", typed = true)
    public PlanExtensionFactApplier planExtensionFactApplier(
            MybatisPlanRepository plans, PlanToolCommandCodec commands,
            io.github.kongweiguang.ja.plan.infrastructure.persistence.serialization.CanonicalPlanJson canonical,
            ExecutionRecordCodec records, ExecutionEventCodec events,
            @Inject(value = "jaRuntimeProcessGeneration", required = false) RuntimeProcessGeneration generation) {
        RuntimeProcessGeneration selectedGeneration = generation;
        if (selectedGeneration == null) {
            if (!AotSideEffectGuard.processing()) {
                throw new IllegalStateException("Ja runtime process generation bean is unavailable");
            }
            selectedGeneration = RuntimeProcessGeneration.aotPlaceholder();
        }
        return new PlanExtensionFactApplier(
                plans, commands, canonical, records, events, selectedGeneration.value());
    }

    /**
     * Tool attempt 的持久状态与 Plan 命令分属独立事实，分别由精确 schema applier 处理。
     */
    @Bean(value = "jaPlanToolAttemptFactApplier", typed = true)
    public PlanToolAttemptFactApplier planToolAttemptFactApplier(
            MybatisPlanRepository plans, ExecutionRecordCodec records, ExecutionEventCodec events) {
        return new PlanToolAttemptFactApplier(plans, records, events);
    }

    /**
     * 将 Provider 工厂发布为 Solon 资源，避免为每个 RPC 对象图重复创建连接资源。
     */
    @Bean(value = "jaModelAdapterFactory", typed = false)
    public ModelAdapterFactory modelAdapterFactory(Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        AotSideEffectGuard.requireRuntimeIo();
        return lifecycle.own(new ModelAdapterFactory(clock));
    }

    /**
     * 运行阶段只复用生产 Provider 工厂；AOT 占位仅供 Solon 分析 Bean 图且拒绝任何调用。
     */
    @Bean(value = "jaModelPort", typed = true)
    public ModelPort modelPort(@Inject(value = "jaModelAdapterFactory", required = false)
                               ModelAdapterFactory modelFactory) {
        if (AotSideEffectGuard.processing()) {
            return new ModelPort() {
                /** AOT 不执行估算，防止分析期间物化用户输入或使用未初始化的 Provider。 */
                @Override
                public InputTokenEstimate estimateInputTokens(ModelRequest request,
                        io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                    throw new IllegalStateException("Provider is unavailable during AOT processing");
                }

                /** 占位能力只用于 Bean 图分析，实际调用必须失败关闭。 */
                @Override
                public java.util.concurrent.CompletionStage<ModelOutcome> start(ModelRequest request,
                        io.github.kongweiguang.ja.agent.application.gateway.ModelEventSink eventSink,
                        io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken cancellation) {
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new IllegalStateException("Provider is unavailable during AOT processing"));
                }
            };
        }
        AotSideEffectGuard.requireRuntimeIo();
        if (modelFactory == null) {
            throw new IllegalStateException("Ja Provider factory bean is unavailable");
        }
        return modelFactory;
    }

    /**
     * AOT 只登记隔离占位目录；真实进程才解析用户 Skill 与 Ja Home，避免分析期触碰宿主配置。
     */
    @Bean(value = "jaSkillSources", typed = true)
    public JaSkillCatalog skillSources() {
        Path home = AotSideEffectGuard.processing() ? aotHomePath()
                : Path.of(System.getProperty("user.home"));
        Path agentsSkills = home.resolve(".agents").resolve("skills");
        Path jaHome = AotSideEffectGuard.processing() ? aotHomePath() : resolveHomePath();
        return new JaSkillCatalog(agentsSkills, jaHome.resolve("skills"));
    }

    /**
     * AGENTS catalog 复用持久 scope 仓储与全局时钟；文件正文仍在每次 Prompt 刷新时读取，
     * 避免把可能变化的项目规则缓存为进程级事实。
     */
    @Bean(value = "jaAgentInstructionCatalog", typed = true)
    public AgentInstructionCatalog instructionCatalog(InstructionScopeRepository scopes, Clock clock) {
        return new AgentInstructionCatalog(scopes, clock);
    }

    /**
     * 每个 Turn 使用独占 Prompt Session，并连接 Java 持有的 Workspace 根变更账本。
     */
    @Bean(value = "jaAgentPromptSessionFactory", typed = true)
    public AgentPromptSessionFactory promptSessionFactory(
            AgentInstructionCatalog instructions,
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceService workspaces) {
        if (AotSideEffectGuard.processing()) {
            return new DefaultAgentPromptSessionFactory(instructions);
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        return new DefaultAgentPromptSessionFactory(instructions, workspaces);
    }

    /**
     * 基于规范 Ja Home 发布不可变代际目录；Settings 探测不得继承进程 cwd，
     * 也不得伪装为某个 Turn 的工作区。
     */
    @Bean(value = "jaCatalog", typed = true)
    public GenerationCatalog catalog(ObjectMapper mapper, JaSkillCatalog skills,
                                     WorkspaceBoundaryFactory workspaceBoundaryFactory) {
        if (AotSideEffectGuard.processing()) {
            return new GenerationCatalog(aotHomePath(), mapper, McpLimits.DEFAULT, skills,
                    workspaceBoundaryFactory);
        }
        return lifecycle.own(new GenerationCatalog(resolveHomePath(), mapper, McpLimits.DEFAULT, skills,
                workspaceBoundaryFactory));
    }

    /**
     * 在组合根集中创建 Workspace 文件边界，使 Catalog 只依赖边界工厂，
     * 避免 Catalog 自己持有进程级路径状态并绕过 Workspace 的权限校验。
     */
    @Bean(value = "jaWorkspaceBoundaryFactory", typed = true)
    public WorkspaceBoundaryFactory workspaceBoundaryFactory() {
        return new WorkspaceBoundaryFactoryAdapter();
    }

    /**
     * 发布不携带 cwd 的请求级 MCP 会话工厂；每次打开都必须绑定当前配置代际租约、
     * Provider/Model 身份以及命令对应的规范工作区根目录。
     */
    @Bean(value = "jaTurnMcpSessionFactory", typed = true)
    public GenerationTurnMcpSessionFactory turnMcpSessionFactory(ObjectMapper mapper,
                                                                 GenerationCatalog catalog,
                                                                 ConfigurationGenerationPort configurations) {
        return new GenerationTurnMcpSessionFactory(mapper, McpLimits.DEFAULT, catalog, configurations);
    }

    /**
     * 发布只含 catalog 用例的应用边界，RPC 不直接持有 MCP 或 Skill adapter。
     */
    @Bean(value = "jaCatalogUseCase", typed = true)
    public CatalogUseCase catalogUseCase(GenerationCatalog catalog,
                                         ConfigurationGenerationPort configurations,
                                         ModelPort models,
                                         @Inject(value = "jaWorkspaceUseCase", required = false)
                                         WorkspaceUseCase workspaces) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        // 组合根完成 Workspace/Configuration 的跨上下文投影，Catalog service 只消费自有端口。
        CatalogWorkspacePort workspacePort = workspaceId -> {
            Workspace workspace = workspaces.requireSettingsWorkspace(workspaceId);
            return workspace.kind() == Workspace.Kind.PROJECT
                    ? java.util.Optional.of(new CatalogWorkspacePort.ProjectBinding(
                            workspace.root(), workspace.trust() == Workspace.Trust.TRUSTED))
                    : java.util.Optional.empty();
        };
        return new CatalogService(catalog, configurations, models, workspacePort, new CatalogConfigurationAcl());
    }

    /**
     * AOT 不创建持久 Thread/Workspace 查询服务；真实运行仍以同一 Ja Store 提供 MCP ceiling 读取。
     */
    @Bean(value = "jaThreadMcpUseCase", typed = true)
    public ThreadMcpUseCase threadMcpUseCase(
            @Inject(value = "jaHistory", required = false) MybatisHistoryService threads,
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
            ConfigurationGenerationPort configurations,
            GenerationCatalog catalog,
            TaskAgentToolGateway taskTools) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (threads == null || workspaces == null) {
            throw new IllegalStateException("Ja Thread/Workspace repositories are unavailable");
        }
        // 组合根把跨上下文 JSON 能力压缩为稳定摘要，Catalog 服务无需依赖 Collaboration 的模型。
        return new ThreadMcpService(threads::readThread, workspaces::requireOpenWorkspace,
                configurations, catalog,
                threadId -> taskTools.permissionCeilingForThread(threadId)
                        .map(ThreadMcpIdentity::ceilingFingerprint),
                new CatalogThreadAcl(threads::readThread, workspaces::requireOpenWorkspace));
    }

    /**
     * Shell 只影响 exec_command/write_stdin 集；预检失败时保留可用的配置、历史与 RPC 恢复面。
     */
    @Bean(value = "jaShellCapability", typed = true)
    public ShellCapability shellCapability() {
        ShellCapability capability = ShellCapability.detectAndPreflight();
        if (capability.profile().isEmpty()) {
            LOGGER.warn("Shell 环境不可用：exec_command 与 write_stdin 持续进程工具不可用");
        }
        return capability;
    }

    /**
     * 先发布 late-bound 网关以断开 Resolver -> TaskCoordinator -> TurnService -> Resolver 构造环；
     * Task Tool 只读取准入快照端口；Coordinator 绑定前调用会失败关闭，不提供临时 no-op 实现。
     */
    @Bean(value = "jaTaskAgentToolGateway", typed = true)
    public TaskAgentToolGateway taskAgentToolGateway(SubagentPolicyRepository subagentPolicies,
                                                     PreparedAdmissionRuntimePort admissions) {
        return new TaskAgentToolGateway(subagentPolicies, admissions);
    }

    /**
     * 先发布稳定的 Resolver 端口以断开 Plan Tool 与 TurnService/Workspace 的组合环；
     * 真实请求在 Solon 完成全部 Bean 装配后才会到达，提前调用必须失败关闭。
     */
    @Bean(value = "jaTurnRuntimeResolver", typed = true)
    public TurnRuntimeResolver deferredTurnRuntimeResolver() {
        return deferredRuntimeResolver;
    }

    /**
     * 组合配置代际、Skill、MCP 和 Host Tool 出站适配器；完整实现只按名发布，
     * 所有消费者继续依赖上方的稳定端口，避免 Solon 再次通过具体类形成环；AOT 不解析配置或 MCP。
     */
    @Bean(value = "jaConfigurationTurnRuntimeResolver", typed = false)
    public ConfigurationTurnRuntimeResolver configurationTurnRuntimeResolver(
            ConfigurationGenerationPort configurations,
            JaSkillCatalog skills,
            ShellCapability shellCapability,
            WorkspaceBoundaryFactory workspaceBoundaryFactory,
            GenerationCatalog catalog,
            GenerationTurnMcpSessionFactory turnMcpSessions,
            JsonValueCodec argumentsCodec,
            AgentPromptSessionFactory promptSessions,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments,
            TaskAgentToolGateway taskTools,
            @Inject(value = "jaPlanToolFactory", required = false) PlanToolFactory planTools,
            InteractionToolFactory interactionTools,
            TaskCapabilityCeilingPort taskCeilings,
            @Inject(value = "jaProcessSessionUseCase", required = false) ProcessSessionUseCase processes) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (planTools == null) {
            throw new IllegalStateException("Ja Plan tool factory is unavailable");
        }
        ConfigurationTurnRuntimeResolver resolver = new ConfigurationTurnRuntimeResolver(configurations, skills,
                resolveHomePath(), workspaceBoundaryFactory, shellCapability, catalog, turnMcpSessions,
                argumentsCodec, promptSessions,
                attachmentReader(attachments), new AttachmentManagedToolMediaImporter(attachments),
                planTools, taskTools, interactionTools, taskCeilings, processes);
        deferredRuntimeResolver.bind(resolver);
        return resolver;
    }

    /**
     * Ja Tool 边界共享一个严格绑定 codec，保留批准、路由与执行调用键之间的稳定关联。
     */
    @Bean(value = "jaToolBindingCodec", typed = true)
    public JaToolBindingCodec jaToolBindingCodec(ObjectMapper mapper) {
        return new JaToolBindingCodec(mapper);
    }

    /**
     * 持久工具展示只走字段白名单 codec，原始参数仍留在执行 Store。
     */
    @Bean(value = "jaToolPresentationCodec", typed = true)
    public ToolPresentationCodec toolPresentationCodec(ObjectMapper mapper) {
        return new ToolPresentationCodec(mapper);
    }

    /**
     * Change-set durable facts 与历史查询共用一个显式闭集 codec。
     */
    @Bean(value = "jaTurnChangeSetCodec", typed = true)
    public TurnChangeSetCodec turnChangeSetCodec(ObjectMapper mapper) {
        return new TurnChangeSetCodec(mapper);
    }

    /**
     * 供 request binding 读取当前身份的原生上下文；恢复时必须由调用方重新显式绑定。
     */
    @Bean(value = "jaRequestBindingSource", typed = true)
    public JaRequestBindingSource jaRequestBindingSource(
            ConversationProjectionRepository conversations,
            @Inject(value = "jaConversationWorkspaceBindingResolver", required = false)
            WorkspaceBindingResolver workspaces,
            JaExecutionContexts contexts, JaAdmissionParticipants admissions,
            AdmissionParticipantFactory participants, AdmissionParticipantResolver admissionParticipants) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace binding resolver is unavailable");
        }
        return new ConfigurationJaRequestBindingSource(conversations, workspaces, contexts::find,
                admissions, participants, admissionParticipants);
    }

    /**
     * 将 Workspace 入站用例转换为 conversation 的窄出站绑定，不让运行时适配器反向依赖入站端口。
     */
    @Bean(value = "jaConversationWorkspaceBindingResolver", typed = true)
    public WorkspaceBindingResolver conversationWorkspaceBindingResolver(
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        return workspaceId -> {
            Workspace workspace = workspaces.requireOpenWorkspace(workspaceId);
            return new WorkspaceBindingResolver.WorkspaceBinding(workspace.workspaceId(), workspace.root());
        };
    }

    /**
     * Workspace 写声明协调器是进程级共享资源，所有变更工具共用同一 SQLite FIFO/fencing owner；
     * AOT 只扫描 Bean 图而不创建持久 claim，实际运行缺少 Task 仓储时必须失败关闭；代际显式按 Bean 名注入。
     */
    @Bean(value = "jaWorkspaceWriteLeaseCoordinator", typed = true)
    public WorkspaceWriteLeaseCoordinator workspaceWriteLeaseCoordinator(
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository claims,
            Clock clock,
            @Inject(value = "jaRuntimeProcessGeneration", required = true) RuntimeProcessGeneration generation) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (claims == null) {
            throw new IllegalStateException("Ja workspace write claims are unavailable");
        }
        // ACL 在组合根隔离 Workspace claim owner，Conversation Loop 只看到自己的消费端口。
        return lifecycle.own(new WorkspaceWriteLeaseCoordinator(new WorkspaceWriteClaimAcl(claims), clock,
                generation.value()));
    }

    /**
     * Plan ledger 仅作为每请求 Ja Tool 的纯事实贡献者；代际显式绑定具名 Bean 以稳定 AOT 参数解析。
     */
    @Bean(value = "jaPlanToolExecutionLedger", typed = true)
    public PlanToolExecutionLedger planToolExecutionLedger(MybatisPlanRepository plans, Clock clock,
                                                           @Inject(value = "jaRuntimeProcessGeneration", required = true) RuntimeProcessGeneration generation) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        return new PlanToolExecutionLedger(plans, generation.value());
    }

    /**
     * 把每请求 Ja runtime 与业务中间件端口适配为唯一 Engine 的冻结能力快照，不另建执行循环或历史。
     */
    @Bean(value = "jaAgentRuntimeResolver", typed = false)
    public JaAgentRuntimeResolver jaAgentRuntimeResolver(
            TurnRuntimeResolver turnRuntimes, ModelPort models,
            @Inject(value = "jaReviewTurnRuntimeMiddlewareFactory", required = false)
            ReviewTurnRuntimeMiddlewareFactory reviewMiddlewareFactory,
            @Inject(value = "jaRequestBindingSource", required = false) JaRequestBindingSource bindings,
            JaToolBindingCodec bindingCodec,
            @Inject(value = "jaWorkspaceWriteLeaseCoordinator", required = false)
            WorkspaceWriteLeaseCoordinator writeLeases,
            ToolPresentationCodec presentations,
            @Inject(value = "jaTurnChangeSetCodec", required = false) TurnChangeSetSerialization changes,
            @Inject(value = "jaSummaryModelFactory", required = false) SummaryModel.Factory summaries,
            @Inject(value = "jaPlanToolExecutionLedger", required = false)
            PlanToolExecutionLedger planLedger, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (reviewMiddlewareFactory == null || bindings == null || writeLeases == null
                || changes == null || planLedger == null || summaries == null) {
            throw new IllegalStateException("Ja Agent runtime dependencies are unavailable");
        }
        return new JaAgentRuntimeResolver(turnRuntimes, reviewMiddlewareFactory, models, bindings,
                bindingCodec, writeLeases,
                presentations, changes, summaries, List.of(planLedger), clock);
    }

    /**
     * Review middleware 由组合根提供给通用 Conversation Resolver，避免运行时包反向依赖 Review 实现。
     */
    @Bean(value = "jaReviewTurnRuntimeMiddlewareFactory", typed = true)
    public ReviewTurnRuntimeMiddlewareFactory reviewTurnRuntimeMiddlewareFactory(JsonValueCodec codec) {
        return new ReviewTurnRuntimeMiddlewareFactory(codec);
    }

    /**
     * Engine event 投影器由 Store 与 Ja codec 驱动，生命周期晚于 Store、早于 Engine 关闭。
     */
    @Bean(value = "jaAgentEventBridge", typed = true)
    public JaAgentEventBridge jaAgentEventBridge(
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            ExecutionEventCodec events, ExecutionRecordCodec records, ExecutionControlCodec controls,
            ExtensionFactRecordCodec extensionFacts, ToolPresentationCodec presentations,
            TurnChangeSetCodec changes, ToolCallPreviewReadPort previews,
            @Inject(value = "jaAttachmentUseCase", required = false)
            io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null || attachments == null) {
            throw new IllegalStateException("Ja Agent event projection dependencies are unavailable");
        }
        return lifecycle.own(new JaAgentEventBridge(store, events, records, controls,
                extensionFacts, presentations, changes, previews, attachments));
    }

    /**
     * Engine 只持久化统一 AgentStore；审批恢复监听器仅投影本次启动重挂的到期代际，不恢复 Driver。
     */
    @Bean(value = "jaAgentEngine", typed = true)
    public AgentEngine agentEngine(
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            @Inject(value = "jaAgentRuntimeResolver", required = false) JaAgentRuntimeResolver runtimes,
            @Inject(value = "jaInteractionUseCase", required = false) InteractionService interactions,
            @Inject(value = "jaAgentEventBridge", required = false) JaAgentEventBridge events,
            @Inject(value = "jaPlanCommittedEventListener", required = false)
            PlanCommittedEventListener planEvents,
            @Inject(value = "jaApprovalExpiryRecoveryListener", required = false)
            ApprovalExpiryRecoveryListener approvalExpiry) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null || runtimes == null || interactions == null || events == null || planEvents == null
            || approvalExpiry == null) {
            throw new IllegalStateException("Ja Agent Engine dependencies are unavailable");
        }
        AgentEngine engine = new AgentEngineBuilder().store(store).runtimeResolver(runtimes)
                .extensions(registry -> {
                    registry.registerEventListener(interactions);
                    registry.registerEventListener(events);
                    registry.registerEventListener(planEvents);
                    registry.registerEventListener(approvalExpiry);
                })
                .parallelism(8).maxOutstandingExecutions(64).maxOutstandingPerThread(8).build();
        try {
            interactions.bindEngine(engine);
            events.bindEngine(engine);
            return lifecycle.own(engine);
        } catch (RuntimeException | Error failure) {
            try {
                engine.close();
            } catch (RuntimeException closeFailure) {
                if (failure != closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * 先注册持久化到期观察者，再等待启动扫描生成 Task claim，避免 Engine 事件早于恢复监听而漏接。
     */
    @Bean(value = "jaApprovalExpiryRecoveryListener", typed = true)
    public ApprovalExpiryRecoveryListener approvalExpiryRecoveryListener(
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            ExecutionControlCodec controls,
            ExecutionEventCodec events,
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository tasks,
            Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null || tasks == null) {
            throw new IllegalStateException("Ja approval expiry recovery dependencies are unavailable");
        }
        return lifecycle.own(new ApprovalExpiryRecoveryListener(store, controls, events, tasks, clock));
    }

    /**
     * 发布无状态工作区策略，使应用服务和聚焦测试共享同一身份规则。
     */
    @Bean(value = "jaWorkspacePolicy", typed = true)
    public WorkspacePolicy workspacePolicy() {
        return new WorkspacePolicy();
    }

    /**
     * session workspace 只归 Host 发布的 Ja Home；AOT 使用临时占位根且不解析无关的数据目录。
     */
    @Bean(value = "jaWorkspaceDirectoryPort", typed = true)
    public WorkspaceDirectoryPort workspaceDirectoryPort() {
        Path home = AotSideEffectGuard.processing() ? aotHomePath() : resolveHomePath();
        return new NioWorkspaceDirectoryAdapter(home);
    }

    /**
     * 用窄端口把工作区预热委派给 Turn Resolver，不暴露配置租约或 MCP adapter。
     */
    @Bean(value = "jaWorkspacePreparationPort", typed = true)
    public WorkspacePreparationPort workspacePreparationPort(TurnRuntimeResolver runtimeResolver) {
        return runtimeResolver::prepareWorkspace;
    }

    /**
     * 将持久化后的信任事实交给配置入站端口，workspace 不依赖文件适配器。
     */
    @Bean(value = "jaWorkspaceTrustPort", typed = true)
    public WorkspaceTrustPort workspaceTrustPort(ConfigurationWorkspaceTrustUseCase configuration) {
        return (root, trust) -> configuration.synchronize(root, trust == Workspace.Trust.TRUSTED);
    }

    /**
     * AOT 只遍历组件图，不构造依赖 History Store 的 Workspace 服务；运行时缺 History 时失败关闭。
     */
    @Bean(value = "jaWorkspaceUseCase", typed = true)
    public WorkspaceService workspaceService(
            @Inject(value = "jaHistory", required = false) MybatisHistoryService repository,
            WorkspaceDirectoryPort directories,
            WorkspacePreparationPort preparation,
            WorkspaceTrustPort trust,
            WorkspacePolicy policy,
            Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (repository == null) {
            throw new IllegalStateException("Ja History repository is unavailable");
        }
        return new WorkspaceService(repository, directories, preparation, trust, policy, clock);
    }

    /**
     * 发布只处理相对路径元数据的 NIO 端口；正文读取继续由现有文件 Tool 独占。
     */
    @Bean(value = "jaWorkspacePathPort", typed = true)
    public WorkspacePathPort workspacePathPort() {
        return new NioWorkspacePathAdapter();
    }

    /**
     * AOT 不构造依赖持久 Workspace 的路径服务；运行时仍复用同一路径 owner。
     */
    @Bean(value = "jaWorkspacePathService", typed = true)
    public WorkspacePathService workspacePathService(
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
            WorkspacePathPort paths) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        return new WorkspacePathService(workspaces, paths);
    }

    /**
     * 直接采用 Core 的严格 JSON 边界，冻结精度与解析策略；不在宿主维护第二套值转换规则。
     */
    @Bean(value = "jaJsonValueCodec", typed = true)
    public JsonValueCodec jsonValueCodec(ObjectMapper mapper) {
        return new JacksonJsonValueCodec(mapper);
    }

    /**
     * AOT 不恢复 Plan 状态或打开 Task 回调；真实启动先完成本地维护，再对账持久 ledger；代际按 Bean 名接线。
     */
    @Bean(value = "jaPlanStartupRecovery", typed = true)
    public PlanStartupRecovery planStartupRecovery(MybatisPlanRepository plans, Clock clock,
                                                   @Inject(value = "jaRuntimeProcessGeneration", required = true) RuntimeProcessGeneration generation,
                                                   StartupMaintenanceService startupMaintenance) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        startupMaintenance.requireInitialized();
        PlanStartupRecovery recovery = new PlanStartupRecovery(plans, clock, generation.value());
        recovery.recover();
        return recovery;
    }


    /**
     * Plan 订阅只观察已提交事实，不启动执行或改变计划生命周期。
     */
    @Bean(value = "jaPlanEventRegistry", typed = true)
    public PlanEventRegistry planEventRegistry() {
        return new PlanEventRegistry();
    }

    /**
     * 问答服务借用持久投影，时间与执行控制由后置绑定的唯一 Engine 提供。
     */
    @Bean(value = "jaInteractionUseCase", typed = true)
    public InteractionService interactionService(InteractionRepository repository) {
        return new InteractionService(repository);
    }

    /**
     * Approval 只结算 AgentStore 的持久等待与 Ja 操作回执，不保留内存 pending owner。
     */
    @Bean(value = "jaApprovalUseCase", typed = true)
    public JaApprovalService approvalUseCase(
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            ExecutionControlCodec controls,
            @Inject(value = "jaAgentEngine", required = false) AgentEngine engine,
            JaOperationReceiptRepository receipts) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null || engine == null) {
            throw new IllegalStateException("Ja approval dependencies are unavailable");
        }
        return new JaApprovalService(store, controls, engine, receipts::readClientOperation);
    }

    /**
     * 以当前交互服务显式构造内置澄清工具，不通过通用能力目录注册或排序。
     */
    @Bean(value = "jaInteractionToolFactory", typed = true)
    public InteractionToolFactory interactionToolFactory(InteractionService interactions) {
        return new InteractionToolFactory(interactions);
    }





    /** 将 Conversation TurnService 收敛为 Plan 应用层的生命周期端口。 */
    @Bean(value = "jaPlanTurnPort", typed = true)
    public PlanTurnPort planTurnPort(@Inject(value = "jaTurnService", required = false) TurnService turns) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (turns == null) {
            throw new IllegalStateException("Ja Turn service is unavailable");
        }
        return new PlanTurnAcl(turns);
    }

    /** 将 Collaboration 的续接投影收敛为 Plan 自有端口。 */
    @Bean(value = "jaPlanTaskPort", typed = true)
    public PlanTaskPort planTaskPort(@Inject(value = "jaTaskUseCase", required = false) TaskCoordinator tasks) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (tasks == null) {
            throw new IllegalStateException("Ja Task coordinator is unavailable");
        }
        return new PlanTaskAcl(tasks);
    }

    /** 将 Workspace owner 的路径绑定收敛为 Plan 所需的最小值对象。 */
    @Bean(value = "jaPlanWorkspacePort", typed = true)
    public PlanWorkspacePort planWorkspacePort(
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
            ConversationProjectionRepository conversations) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        return new PlanWorkspaceAcl(workspaces, conversations);
    }

    /** 将 Conversation 投影转换为 Plan 自有 Thread/Turn 绑定 DTO。 */
    @Bean(value = "jaPlanConversationPort", typed = true)
    public PlanConversationPort planConversationPort(ConversationProjectionRepository conversations) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        return new PlanConversationAcl(conversations);
    }

    /**
     * Plan 的显式执行通过唯一 TurnService 建立 hidden Turn；AOT 只声明依赖元数据不创建执行器，
     * 因而这些端口可选注入，真实启动仍由此工厂校验并在缺失时失败关闭。
     */
    @Bean(value = "jaPlanExecutionTurnAdapter", typed = true)
    public PlanExecutionTurnAdapter planExecutionTurnAdapter(
            @Inject(value = "jaPlanTurnPort", required = false) PlanTurnPort turns,
            @Inject(value = "jaPlanConversationPort", required = false) PlanConversationPort conversations,
            @Inject(value = "jaPlanWorkspacePort", required = false) PlanWorkspacePort workspaces,
            MybatisPlanRepository plans, ObjectMapper mapper, Clock clock,
            @Inject(value = "jaPlanTaskPort", required = false) PlanTaskPort tasks) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (turns == null || workspaces == null || tasks == null || conversations == null) {
            throw new IllegalStateException("Ja Plan execution dependencies are unavailable");
        }
        return new PlanExecutionTurnAdapter(turns, conversations, workspaces, plans, mapper, clock, tasks);
    }

    /**
     * 独立 Plan 只使用真实无工具模型验收，确定性前置门不能代替最终完成判断。
     */
    @Bean(value = "jaPlanExecutionCoordinator", typed = true)
    public PlanExecutionCoordinator planExecutionCoordinator(MybatisPlanRepository plans,
                                                             @Inject(value = "jaPlanExecutionTurnAdapter", required = false) PlanExecutionTurnAdapter turns, Clock clock,
                                                             @Inject(value = "jaRuntimePlanEvaluatorAdapter", required = false)
                                                             io.github.kongweiguang.ja.plan.application.service.RuntimePlanEvaluatorAdapter evaluator) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (turns == null || evaluator == null) {
            throw new IllegalStateException("Ja Plan execution dependencies are unavailable");
        }
        return new PlanExecutionCoordinator(plans, turns, clock, evaluator);
    }

    /**
     * Plan 验收只读 Ja 会话投影并绑定真实审计端口，失败不能降级为无账本的实现。
     */
    @Bean(value = "jaRuntimePlanEvaluatorAdapter", typed = true)
    public io.github.kongweiguang.ja.plan.application.service.RuntimePlanEvaluatorAdapter runtimePlanEvaluatorAdapter(
            ModelPort models, TurnRuntimeResolver runtimes, ConversationProjectionRepository conversations,
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
            ObjectMapper mapper, Clock clock,
            io.github.kongweiguang.ja.plan.application.gateway.PlanEvaluationAuditPort audit) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null) {
            throw new IllegalStateException("Ja workspace service is unavailable");
        }
        return new io.github.kongweiguang.ja.plan.application.service.RuntimePlanEvaluatorAdapter(
                models, runtimes, conversations, workspaces, mapper, clock, audit);
    }




    /**
     * 复用真实 Provider、Thread CAS 与独立 usage ledger 组装标题服务；AOT 分析不得创建后台线程。
     */
    @Bean(value = "jaAutomaticThreadTitleScheduler", typed = true)
    public AutomaticThreadTitleScheduler automaticThreadTitles(
            ModelPort models,
            @Inject(value = "jaHistory", required = false) ThreadUseCase threads,
            @Inject(value = "jaAutomaticTitleUsage", required = false)
            AutomaticTitleUsagePort usage, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (threads == null || usage == null) {
            throw new IllegalStateException("Ja automatic title dependencies are unavailable");
        }
        return new AutomaticThreadTitleService(models, threads, usage, clock);
    }

    /**
     * Engine 是唯一执行器；AOT 时只校验组件图、不创建依赖数据库的 Engine 与 Task 输入端口，
     * 真实启动仍要求这些依赖齐全后才发布 Turn facade。
     */
    @Bean(value = "jaTurnService", typed = true)
    public TurnService turnService(
            @Inject(value = "jaAgentEngine", required = false) AgentEngine engine,
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            ExecutionRecordCodec records, ExecutionControlCodec controls,
            JaOperationReceiptRepository receipts, ConversationProjectionRepository projections,
            JaExecutionContexts contexts, JaAdmissionParticipants admissions,
            @Inject(value = "jaInitialContextSeedSource", required = false)
            InitialContextSeedSource initialContextSeeds,
            StartupMaintenanceService startupMaintenance,
            @Inject(value = "jaPendingQueueInputSource", required = false) PendingQueueInputSource pendingQueueInputs,
            @Inject(value = "jaAgentEventBridge", required = false) AgentExecutionEvents events,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments,
            @Inject(value = "jaWorkspacePathService", required = false)
            WorkspaceReferenceValidator workspaceReferences,
            @Inject(value = "jaConversationWorkspaceBindingResolver", required = false)
            WorkspaceBindingResolver workspaces,
            TurnRuntimeResolver runtimeResolver,
            @Inject(value = "jaAutomaticThreadTitleScheduler", required = false)
            AutomaticThreadTitleScheduler automaticTitles, Clock clock,
            InteractionService interactions,
            @Inject(value = "jaApprovalUseCase", required = false) JaApprovalService approvals,
            @Inject(value = "jaProcessSessionUseCase", required = false) ProcessSessionUseCase processes,
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaceOwner) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        startupMaintenance.requireInitialized();
        if (engine == null || store == null || events == null || workspaces == null || workspaceOwner == null
            || workspaceReferences == null || automaticTitles == null || approvals == null || processes == null) {
            throw new IllegalStateException("Ja Turn execution dependencies are unavailable");
        }
        if (initialContextSeeds == null || pendingQueueInputs == null) {
            throw new IllegalStateException("Ja Task input dependencies are unavailable");
        }
        // ACL 在组合根建立，TurnService 只接收 Conversation 自有摘要和引用端口。
        TurnService service = new TurnService(engine, store, records, controls, receipts, projections,
                contexts, admissions, initialContextSeeds, pendingQueueInputs, events,
                new ConversationAttachmentAcl(attachments),
                new ConversationWorkspaceReferenceAcl(workspaceReferences), workspaces,
                runtimeResolver, automaticTitles, clock, workspaceOwner);
        service.bindProcessSessions(processes);
        io.github.kongweiguang.ja.conversation.application.interaction.InteractionService.ResumeScheduler resume =
                service.resumeScheduler();
        interactions.bindResumeScheduler(resume);
        approvals.bindResumeScheduler(resume);
        return lifecycle.ownShutdownFence(service);
    }

    /**
     * 一个持久运行时代际只拥有一份活进程注册表；正常 Turn 结束不关闭，退出统一逆序回收。
     */
    @Bean(value = "jaProcessSessionUseCase", typed = true)
    public ProcessSessionUseCase processSessions(
                                                @Inject(value = "jaRuntimeProcessGeneration", required = true)
                                                RuntimeProcessGeneration generation,
                                                ProcessSessionLedger ledger, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        return lifecycle.own(new ProcessSessionRegistry(generation.value(), ledger, clock));
    }

    /**
     * 审查复用唯一 Turn 应用端口与附件授权边界，只持有冻结报告投影，不构造第二个 Agent 循环。
     */
    @Bean(value = "jaReviewUseCase", typed = true)
    public ReviewUseCase reviews(@Inject(value = "jaHistory", required = false) ThreadUseCase threads,
                                 @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
                                 @Inject(value = "jaTurnService", required = false) TurnService turns,
                                 @Inject(value = "jaAttachmentUseCase", required = true)
                                 io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments,
                                 ObjectMapper mapper, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (threads == null || workspaces == null || turns == null) {
            throw new IllegalStateException("Ja Review runtime dependencies are unavailable");
        }
        return new ReviewService(threads, workspaces, turns, attachments, mapper, clock);
    }

    /**
     * 发布唯一 Plan 应用端口；RPC 与编译期 Extension 共用该实例，从而让 UI 命令和 Agent Tool
     * 都经过相同的 revision、批准和幂等门，不产生旁路状态。代际依赖按注册 Bean 名接线，满足 AOT 参数解析约束。
     */
    @Bean(value = "jaPlanUseCase", typed = true)
    public PlanUseCase planService(MybatisPlanRepository repository,
                                   io.github.kongweiguang.ja.plan.application.gateway.PlanCanonicalizer canonical,
                                   Clock clock,
                                   @Inject(value = "jaRuntimeProcessGeneration", required = true) RuntimeProcessGeneration generation,
                                   @Inject(value = "jaPlanExecutionCoordinator", required = false)
                                   PlanExecutionCoordinator planExecutions, PlanEventRegistry planEvents,
                                   PlanCommittedPublisher committedPublisher,
                                   @Inject(value = "jaTaskUseCase", required = false) TaskCoordinator tasks,
                                   @Inject(value = "jaPlanWorkspacePort", required = false)
                                   PlanWorkspacePort workspaces) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (planExecutions == null || tasks == null || workspaces == null) {
            throw new IllegalStateException("Ja Plan execution dependencies are unavailable");
        }
        PlanService service = new PlanService(repository, canonical, clock, generation.value(),
                planExecutions, planEvents, committedPublisher, workspaces);
        planExecutions.bindPlanCommitObserver(planId -> service.publishPlanCommittedAfterExecution(planId));
        tasks.bindSideChatOwnerController(service::stopOwners);
        return service;
    }

    /**
     * Store listener 与 Plan 用例共享同一持久序号去重器，避免重复发布已提交业务事件。
     */
    @Bean(value = "jaPlanCommittedPublisher", typed = true)
    public PlanCommittedPublisher planCommittedPublisher(MybatisPlanRepository plans,
                                                         PlanEventRegistry planEvents) {
        return new PlanCommittedPublisher(plans, planEvents);
    }

    /**
     * 仅在 Engine 提交后读取确切的 Plan facts，并将结果交给只读提交事件发布器。
     */
    @Bean(value = "jaPlanCommittedEventListener", typed = false)
    public PlanCommittedEventListener planCommittedEventListener(
            @Inject(value = "jaAgentStore", required = false) AgentStore store,
            ExtensionFactRecordCodec extensionFacts, MybatisPlanRepository plans,
            PlanCommittedPublisher publisher) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null) {
            throw new IllegalStateException("Ja AgentStore is unavailable");
        }
        return new PlanCommittedEventListener(store, extensionFacts, plans, publisher);
    }

    /**
     * 直接委托 Plan 用例构造其工具，避免组合根维护第二份能力目录。
     */
    @Bean(value = "jaPlanToolFactory", typed = true)
    public PlanToolFactory planToolFactory(
            @Inject(value = "jaPlanUseCase", required = false) PlanUseCase plans,
            ObjectMapper mapper, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (plans == null) {
            throw new IllegalStateException("Ja Plan service is unavailable");
        }
        return new PlanToolFactory(plans, mapper, clock);
    }

    /**
     * Task 复用唯一 TurnService 作为普通运行与 Child reserve/submit owner；AOT 只声明注入元数据，不创建业务 Bean，
     * 而 Solon 在调用工厂前解析依赖，因此可缺席项允许注入缺省，真实运行时仍须在创建服务前明确失败关闭。
     */
    @Bean(value = "jaTaskUseCase", typed = true)
    public TaskCoordinator taskCoordinator(
            @Inject(value = "jaTaskRepository", required = false)
            MybatisTaskRepository repository,
            @Inject(value = "jaHistory", required = false)
            MybatisHistoryService threads,
            @Inject(value = "jaTurnService", required = false) TurnService turns,
            @Inject(value = "jaWorkspaceUseCase", required = false)
            WorkspaceUseCase workspaces, Clock clock,
            TaskAgentToolGateway taskTools,
            SubagentPolicyRepository subagentPolicies,
            @Inject(value = "jaTaskAdmissionGateway", required = false) TaskAdmissionGateway admissions,
            JsonValueCodec json,
            ConversationBoundary conversation) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (admissions == null) {
            throw new IllegalStateException("Ja Task admission gateway is unavailable");
        }
        if (repository == null || threads == null || turns == null || workspaces == null) {
            throw new IllegalStateException("Ja Task runtime dependencies are unavailable");
        }
        TaskMailboxPort mailboxes = repository;
        TaskCoordinator coordinator = new TaskCoordinator(repository, mailboxes, threads, turns, turns,
                workspaces, clock, subagentPolicies, admissions, json, conversation);
        turns.bindCancellationListener(coordinator);
        // TurnService 是 shutdown fence，必须在其停止接纳前先收口临时侧聊。
        turns.bindPreShutdownHook(coordinator::closeTemporarySideChats);
        taskTools.bind(coordinator);
        taskTools.bindThreads(threads);
        return AotSideEffectGuard.processing() ? coordinator : lifecycle.own(coordinator);
    }

    /**
     * 临时侧聊清理且 Engine、Task owner 装配完成后再扫描，确保过期 wait 在 RPC 可见前恢复到期投影。
     */
    @Bean(value = "jaApprovalExpiryStartupRecovery", typed = true)
    public ApprovalExpiryStartupRecovery approvalExpiryStartupRecovery(
            StartupMaintenanceService maintenance,
            @Inject(value = "jaApprovalExpiryRecoveryListener", required = false)
            ApprovalExpiryRecoveryListener listener,
            @Inject(value = "jaAgentEngine", required = false) AgentEngine engine,
            @Inject(value = "jaHistory", required = false) ThreadUseCase threads,
            @Inject(value = "jaTaskUseCase", required = false) TaskCoordinator tasks) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (listener == null || engine == null || threads == null || tasks == null) {
            throw new IllegalStateException("Ja approval expiry startup dependencies are unavailable");
        }
        return new ApprovalExpiryStartupRecovery(maintenance, listener, engine, threads, tasks);
    }

    /**
     * 将 Task admission 的 Conversation 事务实现收口到基础设施 ACL，避免应用服务直接拼装参与者。
     */
    @Bean(value = "jaTaskAdmissionGateway", typed = true)
    public TaskAdmissionGateway taskAdmissionGateway(
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository repository,
            JaAdmissionParticipants admissions, ExecutionRecordCodec records, ExecutionCursorCodec cursors) {
        if (AotSideEffectGuard.processing() || repository == null) {
            return null;
        }
        return new TaskAdmissionGatewayAdapter(repository, admissions, records, cursors);
    }

    /**
     * 公开本域需要的偏好值转换契约，实际枚举映射仍由 Conversation ACL 适配器持有。
     */
    @Bean(value = "jaConversationBoundary", typed = true)
    public ConversationBoundary conversationBoundary() {
        return new ConversationBoundaryAdapter();
    }

    /**
     * AOT 不创建数据库仓储，因此容许缺少 mailbox 端口；真实运行时仍要求该依赖存在。
     */
    @Bean(value = "jaPendingQueueInputSource", typed = true)
    public PendingQueueInputSource pendingQueueInputSource(
            @Inject(value = "jaTaskRepository", required = false) TaskMailboxPort mailboxes,
            JsonValueCodec json) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (mailboxes == null) {
            throw new IllegalStateException("Ja Task mailbox is unavailable");
        }
        return new TaskMailboxQueueInputSource(mailboxes, json);
    }

    /**
     * 首轮上下文先读 Task 创建快照，再读 fork 快照；两类 child 身份互斥且都不回查来源正文。
     */
    @Bean(value = "jaInitialContextSeedSource", typed = true)
    public InitialContextSeedSource initialContextSeedSource(
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository tasks,
            @Inject(value = "jaHistory", required = false) MybatisHistoryService history) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (tasks == null || history == null) {
            throw new IllegalStateException("Ja initial context seed repositories are unavailable");
        }
        TaskInitialContextSeedSource taskSeeds = new TaskInitialContextSeedSource(tasks);
        return threadId -> taskSeeds.findForFirstTurn(threadId).or(() -> history.findForFirstTurn(threadId));
    }

    /**
     * 手动压缩与普通 Turn 共用 Engine 和内存环境 owner，避免共享后台遗漏准入上下文绑定。
     */
    @Bean(value = "jaManualContextCompactionService", typed = true)
    public io.github.kongweiguang.ja.conversation.application.loop.ManualContextCompactionService manualContextCompactionService(
            ConversationProjectionRepository conversations,
            @Inject(value = "jaAgentStore", required = false)
            AgentStore store,
            ExecutionEventCodec events,
            @Inject(value = "jaAgentEngine", required = false) AgentEngine engine,
            JaExecutionContexts contexts, Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (store == null || engine == null) {
            throw new IllegalStateException("Ja Agent Engine dependencies are unavailable");
        }
        return new io.github.kongweiguang.ja.conversation.application.loop.ManualContextCompactionService(
                conversations, store, events, engine, contexts, clock);
    }

    /**
     * 发布与具体 Provider 解耦的摘要工厂，组合阶段不得提前打开模型请求。
     */
    @Bean(value = "jaSummaryModelFactory", typed = false)
    public SummaryModel.Factory summaryModelFactory(
            @Inject(value = "jaModelAdapterFactory", required = false) ModelAdapterFactory modelFactory) {
        if (AotSideEffectGuard.processing()) {
            return binding -> {
                throw new IllegalStateException("summary model is unavailable during AOT processing");
            };
        }
        if (modelFactory == null) {
            throw new IllegalStateException("Ja Provider factory bean is unavailable");
        }
        return modelFactory;
    }

    /**
     * 先要求启动审批到期恢复扫描完成，再基于精确运行时 Bean 身份发布 RPC 工厂；AOT 只登记类型，
     * 不创建用户运行时门面。
     */
    @Bean(value = "jaRuntimeServicesFactory", typed = true)
    public RuntimeServicesFactory runtimeServicesFactory(
            @Inject(value = "jaWorkspaceUseCase", required = false) WorkspaceUseCase workspaces,
            @Inject(value = "jaWorkspacePathService", required = false)
            WorkspacePathSearchUseCase workspacePathSearch,
            @Inject(value = "jaHistory", required = false) MybatisHistoryService threads,
            @Inject(value = "jaThreadMcpUseCase", required = false) ThreadMcpUseCase threadMcp,
            @Inject(value = "jaTurnService", required = false) TurnService turns,
            @Inject(value = "jaManualContextCompactionService", required = false)
            io.github.kongweiguang.ja.conversation.application.api.ContextCompactionUseCase compactions,
            @Inject(value = "jaApprovalUseCase", required = false) ApprovalUseCase approvals,
            @Inject(value = "jaCatalogUseCase", required = false) CatalogUseCase catalog,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase attachments,
            @Inject(value = "jaAttachmentUseCase", required = true)
            io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase attachmentPreviews,
            @Inject(value = "jaTaskUseCase", required = false) TaskUseCase tasks,
            @Inject(value = "jaPlanUseCase", required = false) PlanUseCase plans,
            io.github.kongweiguang.ja.conversation.application.api.InteractionUseCase interactions,
            @Inject(value = "jaProcessSessionUseCase", required = false) ProcessSessionUseCase processes,
            @Inject(value = "jaReviewUseCase", required = false) ReviewUseCase reviews,
            @Inject(value = "jaApprovalExpiryStartupRecovery", required = false)
            ApprovalExpiryStartupRecovery approvalExpiryRecovery,
            @Inject(value = "jaJsonValueCodec", required = true) JsonValueCodec jsonValueCodec) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (workspaces == null || workspacePathSearch == null || threads == null || threadMcp == null
            || turns == null || compactions == null || approvals == null || catalog == null
            || tasks == null || plans == null || processes == null || reviews == null
            || approvalExpiryRecovery == null) {
            throw new IllegalStateException("Ja RPC runtime dependencies are unavailable");
        }
        return new RuntimeServicesFactory(
                workspaces, workspacePathSearch, threads, threadMcp, turns, compactions, approvals, catalog,
                attachments, attachmentPreviews, tasks, plans, interactions, processes, reviews,
                approvalExpiryRecovery,
                jsonValueCodec, lifecycle::close);
    }

    /**
     * 即使前序资源关闭失败也继续逆序关闭已成功创建的 Bean，以尽可能释放全部资源。
     */
    @Destroy
    public void close() {
        lifecycle.close();
    }

    /**
     * 组合期代理只解决 Bean 构造环，不缓存、重试或重新解释任何运行时决策。
     * 单次绑定防止局部重建把正在执行的 Turn 切换到另一个配置 Owner。
     */
    static final class DeferredTurnRuntimeResolver implements TurnRuntimeResolver {
        private volatile TurnRuntimeResolver delegate;

        /**
         * 完整 Resolver 只允许在同一组合代际绑定一次。
         */
        synchronized void bind(TurnRuntimeResolver resolver) {
            if (delegate != null) {
                throw new IllegalStateException("turn runtime resolver was already bound");
            }
            delegate = java.util.Objects.requireNonNull(resolver, "resolver");
        }

        /**
         * 解析不添加代理层缓存，保留真实 Resolver 的短租约和失败语义。
         */
        @Override
        public io.github.kongweiguang.ja.conversation.application.gateway.RuntimeLease resolve(
                io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest request) {
            return requireDelegate().resolve(request);
        }

        /**
         * 工作区预热必须到达同一真实 Resolver，不建立第二份 catalog 状态。
         */
        @Override
        public void prepareWorkspace(Path workspaceRoot) {
            requireDelegate().prepareWorkspace(workspaceRoot);
        }

        /**
         * 延迟绑定同样转发准入前预算读取，不能回退到完整工具环境解析。
         */
        @Override
        public io.github.kongweiguang.ja.agent.domain.turn.TurnLimits resolveLimits(
                io.github.kongweiguang.ja.conversation.application.gateway.TurnRuntimeRequest request) {
            return requireDelegate().resolveLimits(request);
        }

        /**
         * 默认模型投影与 Turn 解析共用委托，避免组合代际不一致。
         */
        @Override
        public java.util.Optional<DefaultModelSelection> defaultModelSelection(Path workspaceRoot) {
            return requireDelegate().defaultModelSelection(workspaceRoot);
        }

        /**
         * 若 Solon 未完成绑定就发生调用，显式拒绝而不返回空能力。
         */
        private TurnRuntimeResolver requireDelegate() {
            TurnRuntimeResolver current = delegate;
            if (current == null) {
                throw new IllegalStateException("turn runtime resolver is not ready");
            }
            return current;
        }
    }
}



