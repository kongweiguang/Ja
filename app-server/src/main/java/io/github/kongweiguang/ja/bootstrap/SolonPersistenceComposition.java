// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.attachment.adapter.out.persistence.MybatisAttachmentRepository;
import io.github.kongweiguang.ja.attachment.adapter.out.storage.ManagedAttachmentStore;
import io.github.kongweiguang.ja.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.infrastructure.aot.AotSideEffectGuard;
import io.github.kongweiguang.ja.infrastructure.persistence.database.JaDatabase;
import io.github.kongweiguang.ja.infrastructure.persistence.recovery.StartupRecoveryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisCheckpointStore;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisAutomaticTitleUsageRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisConversationRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.task.MybatisTaskRepository;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisHistoryService;
import io.github.kongweiguang.ja.infrastructure.persistence.repository.MybatisInstructionScopeRepository;
import io.github.kongweiguang.ja.goal.adapter.out.persistence.MybatisGoalRepository;
import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;
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
import java.time.Clock;
import java.nio.file.Path;
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
     * 在 MyBatis 注册全部 XML Mapper 后发布最终 ConversationRepository；具名 Adapter 未发布时运行时查询
     * 必须失败关闭。DataSource 参数仅作为顺序栅栏，其发布会触发官方插件的具名 Adapter 注册。
     */
    @Bean(value = "jaConversationRepository", typed = true)
    public MybatisConversationRepository agentStore(@Inject(value = "ja", required = true) DataSource dataSource,
                                                    ObjectMapper mapper,
                                                    RuntimeResourceLifecycle lifecycle) {
        MybatisConversationRepository store = new MybatisConversationRepository(requireNamedSessions(), mapper);
        return AotSideEffectGuard.processing() ? store : lifecycle.own(store);
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

    /** 附件关系与其它会话事实复用唯一具名 Factory，Turn admission 才能在同一事务绑定草稿。 */
    @Bean(value = "jaAttachmentRepository", typed = true)
    public MybatisAttachmentRepository attachmentRepository(
            @Inject(value = "ja", required = true) DataSource dataSource) {
        return new MybatisAttachmentRepository(requireNamedSessions());
    }

    /** Task、Conversation 与 Workspace 写声明复用同一个具名 Factory，SQLite 仍由 App Server 独占。 */
    @Bean(value = "jaTaskRepository", typed = true)
    public MybatisTaskRepository taskRepository(
            @Inject(value = "ja", required = true) DataSource dataSource,
            ObjectMapper mapper, RuntimeResourceLifecycle lifecycle) {
        MybatisTaskRepository repository = new MybatisTaskRepository(requireNamedSessions(), mapper);
        return AotSideEffectGuard.processing() ? repository : lifecycle.own(repository);
    }

    /**
     * Goal 聚合复用唯一具名 SQLite Factory；Repository 内部继续拥有 CAS、幂等和不可变版本事务，
     * 组合根只发布端口身份，避免 transport 或 extension 取得 Mapper。
     */
    @Bean(value = "jaGoalRepository", typed = true)
    public MybatisGoalRepository goalRepository(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisGoalRepository(requireNamedSessions(), mapper);
    }

    /**
     * JaDatabase lease 是旧 sidecar 已死亡的权威证明；只有该 owner 存在后，才在一个事务中分配
     * 新代际并废弃全部遗留活动 claim，禁止用进程时间或 Bean 创建顺序猜测身份。
     */
    @Bean(value = "jaRuntimeProcessGeneration", typed = true)
    public RuntimeProcessGeneration runtimeProcessGeneration(
            @Inject(value = "ja", required = true) DataSource dataSource,
            @Inject(required = false) JaDatabase database,
            MybatisTaskRepository taskRepository, Clock clock) {
        if (AotSideEffectGuard.processing()) return RuntimeProcessGeneration.aotPlaceholder();
        if (database == null) throw new IllegalStateException("Ja database bean is unavailable");
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
        if (AotSideEffectGuard.processing()) return new AotAttachmentUseCase();
        ManagedAttachmentStore blobs = new ManagedAttachmentStore(
                publishedDirectory("ja.run-dir"), publishedDirectory("ja.data-dir"));
        return lifecycle.own(new AttachmentService(repository, blobs, clock));
    }

    /**
     * 追加式结构化 Checkpoint 复用同一个具名 Factory；生产事务边界由 Adapter 所有的
     * Factory 上的 MybatisUnitOfWork 提供，具名 DataSource 依赖防止此 Bean 抢先于插件注册。
     */
    @Bean(value = "jaCheckpointStore", typed = true)
    public MybatisCheckpointStore checkpointStore(
            @Inject(value = "ja", required = true) DataSource dataSource, ObjectMapper mapper) {
        return new MybatisCheckpointStore(requireNamedSessions(), mapper);
    }

    /**
     * 等 ConversationRepository、具名 DataSource 与 Session Factory 都成为真实 Solon 依赖后，
     * 再发布单数据库的 workspace、Thread 与 History 查询边界。
     */
    @Bean(value = "jaHistory", typed = true)
    public MybatisHistoryService history(@Inject(value = "ja", required = true) DataSource dataSource,
                                         MybatisConversationRepository store,
                                         ObjectMapper mapper,
                                         Clock clock) {
        return new MybatisHistoryService(requireNamedSessions(), store, mapper, clock);
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
     * 只在 Host 发布的真实运行代际中绑定 WAL 关闭并执行恢复；AOT 只接收元数据 DataSource。
     * 运行时仍必须查询唯一数据库 Owner，若前序 index=0 组合未发布则失败关闭；依赖具名
     * DataSource 也保证恢复晚于 MyBatis Adapter 注册。
     */
    @Bean(value = "jaStartupRecovery", typed = true)
    @SuppressWarnings("PMD.CloseResource")
    public StartupRecoveryService startupRecovery(
            @Inject(value = "ja", required = true) DataSource dataSource, Clock clock,
            RuntimeProcessGeneration processGeneration) {
        SqlSessionFactory sessions = requireNamedSessions();
        StartupRecoveryService recovery = new StartupRecoveryService(sessions, clock);
        if (AotSideEffectGuard.processing()) return recovery;
        JaDatabase database = Solon.context() == null ? null : Solon.context().getBean(JaDatabase.class);
        if (database == null) throw new IllegalStateException("Ja database bean is unavailable");
        database.bindWalCheckpoint(sessions);
        recovery.recover();
        return recovery;
    }

    /**
     * 读取为 `ja` DataSource 注册的精确 Factory，不从此组合组件触发 Adapter 构造或数据源 IO。
     */
    private static SqlSessionFactory lookupNamedSessions() {
        MybatisAdapter adapter = MybatisAdapterManager.getOnly("ja");
        if (adapter == null && Solon.context() != null) {
            org.noear.solon.core.BeanWrap dataSource = Solon.context().getWrap("ja");
            // DataSource 注入与插件注册表都是发布订阅者；官方延迟访问器在同一个 Wrap 上
            // 封闭两者的回调顺序竞争。
            if (dataSource != null) adapter = MybatisAdapterManager.get(dataSource);
        }
        return adapter == null ? null : adapter.getFactory();
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
        if (sessions != null) return sessions;
        throw new IllegalStateException("Ja named SqlSessionFactory bean is unavailable");
    }

    /** 只解析 StdioApplication 已发布的绝对目录，不从 cwd、环境变量或数据库父级回退。 */
    private static Path publishedDirectory(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Ja attachment directory was not published by the Host");
        }
        Path directory = Path.of(value).toAbsolutePath().normalize();
        if (!directory.isAbsolute()) {
            throw new IllegalStateException("Ja attachment directory must be absolute");
        }
        return directory;
    }

    /** Native Image 元数据分析占位；真实运行时分支永不创建此对象。 */
    private static final class AotAttachmentUseCase implements AttachmentUseCase,
            io.github.kongweiguang.ja.attachment.port.in.AttachmentPreviewUseCase {
        /** AOT 不允许导入文件。 */
        @Override public AttachmentMetadata importDraft(ImportRequest request) { throw unavailable(); }

        /** AOT 不允许改变草稿状态。 */
        @Override public AttachmentMetadata discard(String attachmentId, Instant discardedAt) { throw unavailable(); }

        /** AOT 不允许读取用户内容。 */
        @Override public ReadResult read(ReadRequest request) { throw unavailable(); }

        /** AOT 不运行后台清理或访问构建主机目录。 */
        @Override public void collectGarbage() { throw unavailable(); }

        /** AOT 不建立用户附件预览 session。 */
        @Override public PreviewDescriptor openPreview(PreviewOpenRequest request) { throw unavailable(); }

        /** AOT 不读取预览内容。 */
        @Override public PreviewReadResult readPreview(PreviewReadRequest request) { throw unavailable(); }

        /** AOT 不保存 session，因此关闭也拒绝进入运行时语义。 */
        @Override public void closePreview(String previewSessionId) { throw unavailable(); }

        /** 统一标明占位对象只能参与元数据发现。 */
        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("attachment use case is unavailable during AOT processing");
        }
    }
}
