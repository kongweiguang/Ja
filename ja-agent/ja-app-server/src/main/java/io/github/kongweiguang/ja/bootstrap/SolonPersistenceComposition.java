// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.attachment.infrastructure.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.attachment.application.service.AttachmentService;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.ConversationProjectionRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.InstructionScopeRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.JaOperationReceiptRepository;
import io.github.kongweiguang.ja.conversation.application.gateway.ProcessSessionLedger;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.process.JdbcProcessSessionLedger;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicySource;
import io.github.kongweiguang.ja.conversation.application.gateway.ToolCallPreviewReadPort;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.MybatisPlanRepository;
import io.github.kongweiguang.ja.plan.infrastructure.persistence.MybatisPlanEvaluationAuditRepository;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAutomaticTitleUsageRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationProjectionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInstructionScopeRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInteractionRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisJaOperationReceiptRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisSubagentPolicyRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisToolCallPreviewRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.startup.StartupMaintenanceService;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.solon.MybatisAdapter;
import org.apache.ibatis.solon.integration.MybatisAdapterManager;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 从官方具名 MyBatis-Solon Adapter 组合持久化消费者。
 * Adapter 注册表查询保留在 Bean 方法内部，因为 Solon AOT 可能在数据源监听器发布具名
 * Factory 前检查 Bean 参数。
 */
@Component(index = 100)
public final class SolonPersistenceComposition {
    private final Supplier<SqlSessionFactory> namedSessions;

    /**
     * 使用官方 MyBatis-Solon 注册表，同时保留 Solon 所需的无参组件构造方式。
     */
    public SolonPersistenceComposition() {
        this(SolonPersistenceComposition::lookupNamedSessions);
    }

    /**
     * 允许测试验证 Factory 身份，而不修改 MyBatis 进程级注册表。
     */
    SolonPersistenceComposition(Supplier<SqlSessionFactory> namedSessions) {
        this.namedSessions = Objects.requireNonNull(namedSessions, "namedSessions");
    }

    /**
     * 只读取 MyBatis-Solon 已注册的具名 Factory，避免组合根在注册缺失时主动构造 Adapter 并解析 Mapper；
     * 未注册时交由运行时依赖校验失败关闭。
     */
    static SqlSessionFactory lookupNamedSessions() {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        return adapter == null ? null : adapter.getFactory();
    }

    /**
     * 校验 Host 发布的原始目录必须绝对；先转绝对路径会掩盖错误并把 cwd 变成隐式存储根。
     */
    private static Path publishedDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Ja attachment directory was not published by the Host");
        }
        Path directory = Path.of(value).normalize();
        if (!directory.isAbsolute()) {
            throw new IllegalStateException("Ja attachment directory must be absolute");
        }
        return directory;
    }

    /**
     * Engine 接入仅读取 Ja Thread/Turn 投影；该查询与旧执行写入 Repository 分开发布。
     */
    @Bean(value = "jaConversationProjectionRepository", typed = true)
    public ConversationProjectionRepository conversationProjectionRepository(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisConversationProjectionRepository(requireNamedSessions());
    }

    /**
     * RPC 回执仅用于只读核对；写入继续由 AgentStore participant 在原事务内提交。
     */
    @Bean(value = "jaOperationReceiptRepository", typed = true)
    public JaOperationReceiptRepository operationReceiptRepository(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisJaOperationReceiptRepository(requireNamedSessions());
    }

    /**
     * 进程事实复用具名 SQLite owner；组合根不接收裸 Factory，以保持 Solon AOT 与真实启动一致。
     */
    @Bean(value = "jaProcessSessionLedger", typed = true)
    public ProcessSessionLedger processSessionLedger(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new JdbcProcessSessionLedger(requireNamedSessions());
    }

    /**
     * Runtime event bridge 按明确事件水位读取模型原生提案批次，不以当前 tools 行倒推历史。
     */
    @Bean(value = "jaToolCallPreviewReadPort", typed = true)
    public ToolCallPreviewReadPort toolCallPreviewReadPort(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisToolCallPreviewRepository(requireNamedSessions(), mapper);
    }

    /**
     * 将全局配置 Owner 适配为 Thread 创建所需的只读策略端口；不产生第二条配置存储链路。
     */
    @Bean(value = "jaSubagentPolicySource", typed = true)
    public SubagentPolicySource subagentPolicySource(ConfigurationGenerationUseCase configurations) {
        return new ConfigurationSubagentPolicySource(configurations);
    }

    /**
     * Runtime Tool 与 Task admission 共用同一 SQLite 策略事实读取边界。
     */
    @Bean(value = "jaSubagentPolicyRepository", typed = true)
    public MybatisSubagentPolicyRepository subagentPolicyRepository(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisSubagentPolicyRepository(requireNamedSessions());
    }

    /**
     * 问答与 Conversation 使用同一具名 Factory，回答才能与 Tool 游标原子结算。
     */
    @Bean(value = "jaInteractionRepository", typed = true)
    public MybatisInteractionRepository interactionRepository(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisInteractionRepository(requireNamedSessions(), mapper);
    }

    /**
     * 验收模型请求在同一数据库持久记录 intent 与真实用量，启动不能回退为内存审计。
     */
    @Bean(value = "jaPlanEvaluationAudit", typed = true)
    public MybatisPlanEvaluationAuditRepository planEvaluationAuditRepository(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisPlanEvaluationAuditRepository(requireNamedSessions(), mapper);
    }

    /**
     * 自动标题 Provider 调用使用独立 durable ledger；明确 bean 名与运行时组合根契约一致，
     * 真实进程缺失时不得退化为内存或日志实现。
     */
    @Bean(value = "jaAutomaticTitleUsage", typed = true)
    public MybatisAutomaticTitleUsageRepository automaticTitleUsage(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisAutomaticTitleUsageRepository(requireNamedSessions());
    }

    /**
     * 附件关系与其它会话事实复用唯一具名 Factory，Turn admission 才能在同一事务绑定草稿。
     */
    @Bean(value = "jaAttachmentRepository", typed = true)
    public MybatisAttachmentRepository attachmentRepository(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisAttachmentRepository(requireNamedSessions());
    }

    /**
     * Task 快照只读组合根持有的 raw AgentStore；仓储借用该实例并共享唯一 SQLite Factory。
     */
    @Bean(value = "jaTaskRepository", typed = true)
    public MybatisTaskRepository taskRepository(
            @Inject(value = "ja", required = true) DataSource dataSource,
            ObjectMapper mapper,
            @Inject(value = "jaRawAgentStore", required = false) AgentStore rawAgentStore,
            @Inject(value = "jaExecutionRecordCodec", required = false) ExecutionRecordCodec records,
            @Inject(value = "jaJsonValueCodec", required = false) JsonValueCodec jsonValues,
            Clock clock, RuntimeResourceLifecycle lifecycle) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (rawAgentStore == null || records == null || jsonValues == null) {
            throw new IllegalStateException("Ja Task snapshot dependencies are unavailable");
        }
        MybatisTaskRepository repository = new MybatisTaskRepository(requireNamedSessions(), mapper,
                rawAgentStore, records, jsonValues, clock);
        return lifecycle.own(repository);
    }

    /**
     * Plan 聚合复用唯一具名 SQLite Factory；Repository 内部继续拥有 CAS、幂等和不可变版本事务，
     * 组合根只发布端口身份，避免 transport 或 extension 取得 Mapper。
     */
    @Bean(value = "jaPlanRepository", typed = true)
    public MybatisPlanRepository planRepository(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisPlanRepository(requireNamedSessions(), mapper);
    }

    /**
     * JaDatabase lease 是旧 sidecar 已死亡的权威证明；只有该 owner 存在后，才在一个事务中分配
     * 新代际并废弃全部遗留活动 claim，禁止用进程时间或 Bean 创建顺序猜测身份。AOT 只允许缺少
     * Task 仓储以生成类型占位，真实运行时仍对 Database 和仓储依赖失败关闭。
     */
    @Bean(value = "jaRuntimeProcessGeneration", typed = true)
    public RuntimeProcessGeneration runtimeProcessGeneration(
            @Inject(value = "ja", required = true) DataSource dataSource,
            @Inject(required = false) JaDatabase database,
            @Inject(value = "jaTaskRepository", required = false) MybatisTaskRepository taskRepository,
            Clock clock) {
        if (AotSideEffectGuard.processing()) {
            return RuntimeProcessGeneration.aotPlaceholder();
        }
        if (database == null) {
            throw new IllegalStateException("Ja database bean is unavailable");
        }
        if (taskRepository == null) {
            throw new IllegalStateException("Ja Task repository is unavailable");
        }
        return RuntimeProcessGeneration.allocate(taskRepository, clock.instant());
    }

    /**
     * 真实运行时只使用 Host 发布的 run/data 目录并立即启动恢复 GC；AOT 元数据阶段不得访问
     * 构建主机文件系统，故只发布永远不会被调用的类型占位边界。
     */
    @Bean(value = "jaAttachmentUseCase", typed = true)
    public AttachmentUseCase attachments(
            @Inject(value = "ja", required = true) DataSource dataSource,
            MybatisAttachmentRepository repository, Clock clock,
            RuntimeResourceLifecycle lifecycle) {
        if (AotSideEffectGuard.processing()) {
            return new AotAttachmentUseCase();
        }
        ManagedAttachmentStore blobs = new ManagedAttachmentStore(
                publishedDirectory("ja.run-dir"), publishedDirectory("ja.data-dir"));
        return lifecycle.own(new AttachmentService(repository, blobs, clock));
    }

    /**
     * 等 Ja Thread 策略与具名 Session Factory 都可用后发布 History 边界；AOT 的 AgentStore 为空，
     * 因而只跳过数据库服务构造，真实运行缺少唯一 Store 时仍明确失败。进程生命周期单次绑定在
     * History 发布之前，归档、删除、恢复与 fork 不会遗漏进程收口或 Core 快照 owner。
     */
    @Bean(value = "jaHistory", typed = true)
    public MybatisHistoryService history(@Inject(value = "ja", required = true) DataSource dataSource,
                                         @Inject(required = true) SubagentPolicySource policySource,
                                         @Inject(value = "jaAgentStore", required = false) AgentStore agentStore,
                                         ObjectMapper mapper,
                                         Clock clock,
                                         @Inject(value = "jaProcessSessionUseCase", required = false)
                                         io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase processes,
                                         @Inject(value = "jaTaskRepository", required = false)
                                         MybatisTaskRepository taskSnapshots) {
        if (AotSideEffectGuard.processing()) {
            return null;
        }
        if (agentStore == null) {
            throw new IllegalStateException("Ja AgentStore is unavailable");
        }
        if (taskSnapshots == null) {
            throw new IllegalStateException("Ja Task snapshot repository is unavailable");
        }
        MybatisHistoryService history = new MybatisHistoryService(
                requireNamedSessions(), agentStore, policySource, mapper, clock);
        history.bindProcessSessions(processes);
        history.bindThreadForkSnapshots(taskSnapshots);
        return history;
    }

    /**
     * Instruction scope 与会话历史共享唯一具名 Factory，保证嵌套 AGENTS 发现可以跨进程恢复，
     * 同时不引入第二套 SQLite owner 或事务边界。
     */
    @Bean(value = "jaInstructionScopeRepository", typed = true)
    public InstructionScopeRepository instructionScopes(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisInstructionScopeRepository(requireNamedSessions());
    }

    /**
     * 启动只维护 WAL 关闭与临时侧聊清理；已提交 Agent 执行由 Engine 显式恢复，绝不在开机时派发。
     */
    @Bean(value = "jaStartupMaintenance", typed = true)
    public StartupMaintenanceService startupMaintenance(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        if (AotSideEffectGuard.processing()) {
            return StartupMaintenanceService.metadataOnly();
        }
        SqlSessionFactory sessions = requireNamedSessions();
        JaDatabase database = Solon.context() == null ? null : Solon.context().getBean(JaDatabase.class);
        if (database == null) {
            throw new IllegalStateException("Ja database bean is unavailable");
        }
        return StartupMaintenanceService.initialize(database, sessions);
    }

    /**
     * 把缺失运行时 Adapter 转为稳定启动错误，同时允许 Solon AOT 使用无连接 MyBatis
     * 占位对象完成元数据发现。
     */
    private SqlSessionFactory requireNamedSessions() {
        if (AotSideEffectGuard.processing()) {
            return new SqlSessionFactoryBuilder().build(new Configuration());
        }
        SqlSessionFactory sessions = namedSessions.get();
        if (sessions != null) {
            return sessions;
        }
        throw new IllegalStateException("Ja named SqlSessionFactory bean is unavailable");
    }

    /**
     * Native Image 元数据分析占位；真实运行时分支永不创建此对象。
     */
    private static final class AotAttachmentUseCase implements AttachmentUseCase,
            io.github.kongweiguang.ja.attachment.application.api.AttachmentPreviewUseCase {
        /**
         * 统一标明占位对象只能参与元数据发现。
         */
        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("attachment use case is unavailable during AOT processing");
        }

        /**
         * AOT 不允许导入文件。
         */
        @Override
        public AttachmentMetadata importDraft(ImportRequest request) {
            throw unavailable();
        }

        /**
         * AOT 不允许导入模型返回的图片内容。
         */
        @Override
        public AttachmentMetadata importMcpImage(String workspaceId, String displayName,
                                                 String mediaType, byte[] content) {
            throw unavailable();
        }

        /**
         * AOT 不允许改变草稿状态。
         */
        @Override
        public AttachmentMetadata discard(String attachmentId, Instant discardedAt) {
            throw unavailable();
        }

        /**
         * AOT 不允许读取用户内容。
         */
        @Override
        public ReadResult read(ReadRequest request) {
            throw unavailable();
        }

        /**
         * AOT 不访问持久附件关系，防止元数据查询触碰真实用户数据。
         */
        @Override
        public java.util.List<AttachmentMetadata> readAuthorizedMetadata(
                String threadId, java.util.List<String> attachmentIds) {
            throw unavailable();
        }

        /**
         * AOT 不运行后台清理或访问构建主机目录。
         */
        @Override
        public void collectGarbage() {
            throw unavailable();
        }

        /**
         * AOT 不建立用户附件预览 session。
         */
        @Override
        public PreviewDescriptor openPreview(PreviewOpenRequest request) {
            throw unavailable();
        }

        /**
         * AOT 不读取预览内容。
         */
        @Override
        public PreviewReadResult readPreview(PreviewReadRequest request) {
            throw unavailable();
        }

        /**
         * AOT 不保存 session，因此关闭也拒绝进入运行时语义。
         */
        @Override
        public void closePreview(String previewSessionId) {
            throw unavailable();
        }
    }
}

