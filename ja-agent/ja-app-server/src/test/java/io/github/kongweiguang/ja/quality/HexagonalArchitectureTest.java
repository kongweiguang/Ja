// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.application.api.ConfigurationUseCase;
import org.junit.jupiter.api.Test;

/**
 * 用可执行规则冻结 Ja 应用与可复用 Agent Core 的依赖方向；规则检查生产字节码，
 * 避免包结构迁移后仅靠旧路径名称误判模块边界。
 */
@AnalyzeClasses(
        packages = "io.github.kongweiguang.ja",
        importOptions = {ImportOption.DoNotIncludeTests.class,
                HexagonalArchitectureTest.ProductionClassesOnly.class})
final class HexagonalArchitectureTest {
    /** Maven 隔离目录与 classifier=test 的归档都不一定表现为标准 test-classes 路径，必须一并排除。 */
    public static final class ProductionClassesOnly implements ImportOption {
        /** 只导入生产 classes；普通测试输出、test-jar 和 Solon AOT 字节码均不属于模块生产边界。 */
        @Override
        public boolean includes(Location location) {
            return !location.contains("test-classes")
                    && !location.contains("-tests.jar")
                    && !location.contains("solon-aot");
        }
    }

    /** conversation 领域模型可使用通用 Agent 值，但不能依赖 Ja 适配或框架实现。 */
    @ArchTest
    static final ArchRule CONVERSATION_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..conversation.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.agent.domain..",
                    "io.github.kongweiguang.ja.conversation.domain.model..");

    /** workspace 领域模型只能依赖自身、JDK 与 foundation，路径策略不得反向耦合适配器。 */
    @ArchTest
    static final ArchRule WORKSPACE_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..workspace.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.agent.domain..",
                    "io.github.kongweiguang.ja.workspace.domain.model..");

    /** configuration 领域模型只能依赖自身、JDK 与 foundation，配置格式由 adapter 解释。 */
    @ArchTest
    static final ArchRule CONFIGURATION_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..configuration.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.agent.domain..",
                    "io.github.kongweiguang.ja.configuration.domain.model..");

    /** catalog 领域模型只能依赖自身、JDK 与 foundation，MCP SDK 只能停留在出站适配器。 */
    @ArchTest
    static final ArchRule CATALOG_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..catalog.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.agent.domain..",
                    "io.github.kongweiguang.ja.catalog.domain.model..");

    /** Core 领域只依赖 JDK 与自身值，避免扩展、HTTP、JSON 框架或存储技术进入规则。 */
    @ArchTest
    static final ArchRule AGENT_CORE_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..agent.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.agent.domain..");

    /** 可复用内核完全隔离 Ja 应用包，保证其他 Java 项目仅依赖 Maven core 即可运行。 */
    @ArchTest
    static final ArchRule AGENT_CORE_HAS_NO_JA_APPLICATION_DEPENDENCIES = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.agent..")
            .should().dependOnClassesThat().haveNameMatching(
                    "io\\.github\\.kongweiguang\\.ja\\.(?!agent(?:\\.|$)).+");

    /** Spring Boot 只用于独立 example 工程，Ja 应用与可复用内核均不能借示例引入框架 API。 */
    @ArchTest
    static final ArchRule JA_APPLICATION_HAS_NO_SPRING_DEPENDENCIES = noClasses()
            .that().haveNameMatching("io\\.github\\.kongweiguang\\.ja\\.(?!agent(?:\\.|$)).+")
            .should().dependOnClassesThat().resideInAPackage("org.springframework..");

    /** Core 主模块不得带框架或数据库驱动；按 local/PG 扩展专属包名排除实现，避免前缀误判。 */
    @ArchTest
    static final ArchRule AGENT_CORE_HAS_NO_APPLICATION_FRAMEWORK_OR_DATABASE_DRIVER = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.agent..")
            .and().resideOutsideOfPackage("..agent.store.local..")
            .and().resideOutsideOfPackage("..agent.store.pg..")
            .and().resideOutsideOfPackage("..agent.skills.local..")
            .and().resideOutsideOfPackage("..agent.skills.pg..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.noear..", "org.springframework..", "org.xerial..",
                    "org.postgresql..", "org.apache.ibatis..", "org.mybatis..",
                    "org.flywaydb..", "org.sqlite..");

    /** 只有存储扩展契约可携带业务要求的同连接事务句柄，core 编排和领域保持 JDBC 无感知。 */
    @ArchTest
    static final ArchRule AGENT_CORE_JDBC_ACCESS_STAYS_IN_STORE_CONTRACT = noClasses()
            .that().resideInAnyPackage(
                    "io.github.kongweiguang.ja.agent.application.api..",
                    "io.github.kongweiguang.ja.agent.domain..",
                    "io.github.kongweiguang.ja.agent.application..",
                    "io.github.kongweiguang.ja.agent.infrastructure..",
                    "io.github.kongweiguang.ja.agent.application.gateway..")
            .and().haveNameNotMatching(
                    "io\\.github\\.kongweiguang\\.ja\\.agent\\.extension\\.store\\.AgentStore(?:\\$.*)?")
            .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..");

    /** Core application 只能经扩展契约调用实现，避免 HTTP、持久化和供应商适配细节反灌编排层。 */
    @ArchTest
    static final ArchRule AGENT_CORE_APPLICATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.agent.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.github.kongweiguang.ja.agent.adapter..",
                    "io.github.kongweiguang.ja.agent.infrastructure..",
                    "org.noear..", "org.springframework..", "org.xerial..",
                    "org.postgresql..", "org.apache.ibatis..", "org.mybatis..");

    /** 扩展契约只依赖核心值与 JDK，文件路径等适配细节应由扩展实现而非 SPI 类型引入。 */
    @ArchTest
    static final ArchRule AGENT_EXTENSION_CONTRACTS_DO_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.agent.application.gateway..")
            .should().dependOnClassesThat().resideInAPackage("io.github.kongweiguang.ja.agent.infrastructure..");

    /** Ja 的会话偏好、宿主上下文与 Plan/Workspace 策略不能成为可复用 Core 类型。 */
    @ArchTest
    static final ArchRule JA_FEATURE_PORTS_ARE_NOT_DECLARED_IN_AGENT_CORE = classes()
            .that().haveSimpleName("PlanToolPolicy")
            .or().haveSimpleName("TaskMailboxPort")
            .or().haveSimpleName("McpGateway")
            .or().haveSimpleName("McpToolExposure")
            .or().haveSimpleName("TurnMcpOwner")
            .or().haveSimpleName("NativeExecutionSnapshot")
            .or().haveSimpleName("ThreadPreferences")
            .or().haveSimpleName("CollaborationMode")
            .or().haveSimpleName("TurnOrigin")
            .or().haveSimpleName("WorkspaceWriteClaimPort")
            .or().haveSimpleName("WorkspaceWriteLeaseCoordinator")
            .or().haveSimpleName("TurnExecutionPlan")
            .or().haveSimpleName("ConversationRepository")
            .should().resideOutsideOfPackage("io.github.kongweiguang.ja.agent..");

    /** 架构门禁直接扫描生产 classpath，避免 Maven 模块拆分后误以为只分析了 app-server。 */
    @Test
    void architectureBoundaryImportsAgentCoreProductionBytecode() {
        JavaClasses agentProduction = new ClassFileImporter()
                .withImportOption(new ProductionClassesOnly())
                .importPackages("io.github.kongweiguang.ja.agent");
        boolean includesAgentEngine = false;
        for (JavaClass type : agentProduction) {
            if (type.getName().equals("io.github.kongweiguang.ja.agent.application.api.AgentEngine")) {
                includesAgentEngine = true;
                break;
            }
        }
        if (!includesAgentEngine) {
            throw new AssertionError("Agent Core production classes were not imported for architecture checks");
        }
    }

    /** Ja application 只能编排领域与端口；Core 的 application 由专属规则按可复用边界检查。 */
    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_IMPLEMENTATIONS = noClasses()
            .that().resideInAPackage("..application..")
            .and().resideOutsideOfPackage("io.github.kongweiguang.ja.agent.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..transport..", "..bootstrap..",
                    "..infrastructure..", "..platform..");

    /**
     * 业务基础设施只能消费公开应用契约或 gateway，禁止直接依赖应用服务实现；ACL 的跨域调用由公开 API 收口。
     */
    @ArchTest
    static final ArchRule OUTBOUND_ADAPTERS_DO_NOT_DEPEND_ON_BOOTSTRAP_OR_TRANSPORT = noClasses()
            .that().resideInAnyPackage(
                    "..conversation.infrastructure..", "..configuration.infrastructure..",
                    "..workspace.infrastructure..", "..catalog.infrastructure..",
                    "..attachment.infrastructure..", "..plan.infrastructure..",
                    "..collaboration.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("..bootstrap..");

    /** 配置基础设施只能面向领域与 gateway，禁止再次消费任何入站用例。 */
    @ArchTest
    static final ArchRule CONFIGURATION_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND_PORTS = noClasses()
            .that().resideInAPackage("..configuration.infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("..configuration.application.api..");

    /** 配置入站用例的生产实现必须位于 application，防止 bootstrap 或 adapter 冒充应用层。 */
    @ArchTest
    static final ArchRule CONFIGURATION_USE_CASE_IMPLEMENTATIONS_ARE_APPLICATION_SERVICES = classes()
            .that().areAssignableTo(ConfigurationUseCase.class).and().areNotInterfaces()
            .should().resideInAPackage("..configuration.application..");

    /** 配置代际入站用例同样只能由应用服务实现，租约适配不得泄漏到文件适配器。 */
    @ArchTest
    static final ArchRule CONFIGURATION_GENERATION_IMPLEMENTATIONS_ARE_APPLICATION_SERVICES = classes()
            .that().areAssignableTo(ConfigurationGenerationUseCase.class).and().areNotInterfaces()
            .should().resideInAPackage("..configuration.application..");

    /** transport 不能越过入站端口调用业务实现；公共并发与 JsonValue 桥接依赖由职责谓词单独审查。 */
    private static final DescribedPredicate<JavaClass> TRANSPORT_IMPLEMENTATION_DEPENDENCY =
            new DescribedPredicate<>("Ja application services, adapters, and private infrastructure") {
                /** 按目标类型真实职责判断，避免 core 包迁移把公共取消原语误判为 Ja 用例实现。 */
                @Override
                public boolean test(JavaClass dependency) {
                    return isTransportImplementationDependency(dependency);
                }
            };

    /** 旧 Ja 架构将基础并发原语放在 foundation，Jackson JSON 桥也属于协议值工具，可被 transport 共用。 */
    private static boolean isTransportImplementationDependency(JavaClass dependency) {
        String name = dependency.getName();
        String packageName = dependency.getPackageName();
        if (isSharedTransportConcurrencyType(name)) {
            return false;
        }
        if (name.equals("io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValues")) {
            return false;
        }
        if (hasPackageSegment(packageName, "application")) {
            return hasPackageSegment(packageName, "service")
                    || hasPackageSegment(packageName, "adapter");
        }
        return hasPackageSegment(packageName, "adapter")
                || hasPackageSegment(packageName, "infrastructure")
                || hasPackageSegment(packageName, "platform")
                || hasPackageSegment(packageName, "bootstrap");
    }

    /** 只放行 transport 当前依赖的基础并发与关闭原语，避免整包例外掩盖未来的业务服务。 */
    private static boolean isSharedTransportConcurrencyType(String name) {
        for (String type : new String[]{
                "BoundedVirtualExecutor", "CancellationSource", "CancellationToken", "DeadlineCloseable",
                "DeadlineCloseCoordinator", "ShutdownDeadline"}) {
            String prefix = "io.github.kongweiguang.ja.agent.application.concurrent." + type;
            if (name.equals(prefix) || name.startsWith(prefix + "$")) {
                return true;
            }
        }
        return false;
    }

    /** 匹配完整包段及其子包，避免把 bootstrapper 等相似名称当作 bootstrap 组合根。 */
    private static boolean hasPackageSegment(String packageName, String segment) {
        return packageName.equals(segment)
                || packageName.startsWith(segment + ".")
                || packageName.contains("." + segment + ".")
                || packageName.endsWith("." + segment);
    }

    /** JA-RPC 可复用 core 的基础并发原语；业务用例、适配器与持久化仍只能经入站端口组合。 */
    @ArchTest
    static final ArchRule TRANSPORT_DOES_NOT_DEPEND_ON_IMPLEMENTATIONS = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.transport..")
            .should().dependOnClassesThat(TRANSPORT_IMPLEMENTATION_DEPENDENCY);

    /** 配置业务入口只能声明在 configuration.application.api，防止 transport 再创建混合业务接口。 */
    @ArchTest
    static final ArchRule CONFIGURATION_USE_CASE_IS_DECLARED_IN_OWNED_PORT = classes()
            .that().areInterfaces().and().areAssignableTo(ConfigurationUseCase.class)
            .should().resideInAPackage("..configuration.application.api..");

    /** 配置代际租约入口同样由配置域所有，Catalog transport 不得声明转发接口。 */
    @ArchTest
    static final ArchRule CONFIGURATION_GENERATION_USE_CASE_IS_DECLARED_IN_OWNED_PORT = classes()
            .that().areInterfaces().and().areAssignableTo(ConfigurationGenerationUseCase.class)
            .should().resideInAPackage("..configuration.application.api..");

    /** bootstrap 不得重新引入已删除的配置 RPC 混合适配器。 */
    @ArchTest
    static final ArchRule BOOTSTRAP_HAS_NO_CONFIGURATION_RPC_ADAPTER = noClasses()
            .should().haveFullyQualifiedName(
                    "io.github.kongweiguang.ja.bootstrap.ConfigurationRpcAdapter");

    /** 配置失败分类由 transport 直接映射，bootstrap 不得再次承担错误翻译。 */
    @ArchTest
    static final ArchRule BOOTSTRAP_DOES_NOT_MAP_CONFIGURATION_ERRORS = noClasses()
            .that().resideInAPackage("..bootstrap..")
            .should().dependOnClassesThat().areAssignableTo(ConfigurationError.class);

    /** conversation 的接口合同集中在 application，防止新类型落回含混的裸接口命名空间。 */
    @ArchTest
    static final ArchRule CONVERSATION_PORT_ROOT_IS_EMPTY = noClasses()
            .should().resideInAPackage("io.github.kongweiguang.ja.conversation.interfaces");

    /** conversation gateway 不得消费入站 API，避免基础设施反向驱动 transport。 */
    @ArchTest
    static final ArchRule CONVERSATION_OUT_DOES_NOT_DEPEND_ON_IN = noClasses()
            .that().resideInAPackage("..conversation.application.gateway..")
            .should().dependOnClassesThat().resideInAPackage("..conversation.application.api..");

    /** Networknt 技术适配只依赖 JDK、Networknt、Jackson 和自身，确保其不会形成业务包回路。 */
    @ArchTest
    static final ArchRule NETWORKNT_VALIDATION_HAS_CLOSED_TECHNICAL_DEPENDENCIES = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.validation.networknt..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "com.networknt.schema..", "tools.jackson..",
                    "io.github.kongweiguang.ja.validation.networknt..");

    /** conversation API 只依赖领域与 foundation，不得携带 Provider、Repository 或 Tool gateway。 */
    @ArchTest
    static final ArchRule CONVERSATION_IN_DOES_NOT_DEPEND_ON_OUT = noClasses()
            .that().resideInAPackage("..conversation.application.api..")
            .should().dependOnClassesThat().resideInAPackage("..conversation.application.gateway..");

    /**
     * Agent 执行循环只能面向通用领域与扩展契约，新增 Ja 功能时不能把具体业务域重新引入内核。
     */
    @ArchTest
    static final ArchRule AGENT_CORE_LOOP_DOES_NOT_DEPEND_ON_FEATURE_DOMAINS = noClasses()
            .that().resideInAPackage("..agent.application.loop..")
            .should().dependOnClassesThat().resideInAnyPackage("..plan..", "..workspace..");

    /**
     * conversation 内部按 domain、application、port 与 adapter 单向协作；顶层无环规则无法发现
     * 同一领域内部的回流，因此单独冻结这条 DDD 边界。
     */
    @ArchTest
    static final ArchRule CONVERSATION_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.conversation.(*)..")
            .should().beFreeOfCycles();

    /**
     * Provider、Tool、Skill、Middleware 与事件契约统一由 agent.extension 唯一拥有，
     * Ja 应用只实现这些扩展点，不再在 conversation 端口复制另一套 SPI。
     */
    @ArchTest
    static final ArchRule AGENT_EXTENSION_CONTRACTS_BELONG_TO_AGENT_EXTENSION = classes()
            .that().haveSimpleName("AgentExtension")
            .or().haveSimpleName("AgentMiddleware")
            .or().haveSimpleName("AgentEventListener")
            .or().haveSimpleName("ExtensionRegistry")
            .or().haveSimpleName("SkillSource")
            .or().haveSimpleName("ModelPort")
            .or().haveSimpleName("AgentTool")
            .or().haveSimpleName("ToolArgumentValidator")
            .or().haveSimpleName("ConversationRepository")
            .should().resideInAPackage("io.github.kongweiguang.ja.agent.application.gateway..");

    /** bootstrap 只能作为最外层组合根，任何其它生产包都不得反向依赖它。 */
    @ArchTest
    static final ArchRule BOOTSTRAP_HAS_NO_INBOUND_DEPENDENCIES = noClasses()
            .that().resideOutsideOfPackage("..bootstrap..")
            .should().dependOnClassesThat().resideInAPackage("..bootstrap..");

    /** Provider 策略、共享流基础设施与摘要适配不得形成叶子包回路，避免再次退化为单一巨型包。 */
    @ArchTest
    static final ArchRule PROVIDER_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.agent.infrastructure.model.(*)..")
            .should().beFreeOfCycles();

    /** RPC 协议值是最内层 Wire 合同，不得反向依赖 Handler 或连接生命周期。 */
    @ArchTest
    static final ArchRule RPC_PROTOCOL_DOES_NOT_DEPEND_ON_HANDLERS_OR_RUNTIME = noClasses()
            .that().resideInAPackage("..transport.rpc.protocol..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..transport.rpc.handler..", "..transport.rpc.runtime..");

    /** 数据库、Mapper、Repository、事务与恢复职责必须无环，避免 SQL 细节重新渗入生命周期层。 */
    @ArchTest
    static final ArchRule PERSISTENCE_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.infrastructure.persistence.(*)..")
            .should().beFreeOfCycles();

    /** MCP 代际、运行时、会话、传输与共享值按单向依赖协作，不允许 SDK 生命周期反向进入代际。 */
    @ArchTest
    static final ArchRule MCP_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.catalog.infrastructure.mcp.(*)..")
            .should().beFreeOfCycles();

    /** 配置叶子实现不得反向调用运行时 Facade，组合与关闭所有权始终停留在根适配器。 */
    @ArchTest
    static final ArchRule CONFIGURATION_LEAVES_DO_NOT_DEPEND_ON_RUNTIME_FACADE = noClasses()
            .that().resideInAnyPackage(
                    "..configuration.infrastructure.document..",
                    "..configuration.infrastructure.generation..",
                    "..configuration.infrastructure.security..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "io.github.kongweiguang.ja.configuration.infrastructure.ConfigurationRuntimeAdapter");

    /** checkpoint 与 summary 值可互相组合，但都不能反向持有 compaction 编排实现。 */
    @ArchTest
    static final ArchRule CONTEXT_VALUES_DO_NOT_DEPEND_ON_COMPACTION = noClasses()
            .that().resideInAnyPackage(
                    "..agent.application.context.checkpoint..",
                    "..agent.application.context.summary..")
            .should().dependOnClassesThat().resideInAPackage(
                    "..agent.application.context.compaction..");

    /** 顶层责任包必须无环，避免局部端口化后仍通过另一业务域形成隐藏回路。 */
    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.(*)..")
            .should().beFreeOfCycles();
}

