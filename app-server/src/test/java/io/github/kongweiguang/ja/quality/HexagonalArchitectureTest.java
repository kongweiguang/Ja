// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.kongweiguang.ja.configuration.domain.ConfigurationError;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationGenerationUseCase;
import io.github.kongweiguang.ja.configuration.port.in.ConfigurationUseCase;

/**
 * 用可执行规则冻结单模块六边形架构；规则检查生产字节码而不依赖包名约定的人工评审，
 * 从而让后续重构在引入反向依赖或顶层包环时立即失败。
 */
@AnalyzeClasses(
        packages = "io.github.kongweiguang.ja",
        importOptions = {ImportOption.DoNotIncludeTests.class,
                HexagonalArchitectureTest.ProductionClassesOnly.class})
final class HexagonalArchitectureTest {
    /**
     * Maven 隔离输出目录不一定以 target/test-classes 结尾，因此显式排除测试与 Solon AOT 生成字节码。
     */
    public static final class ProductionClassesOnly implements ImportOption {
        /** 只导入生产 classes；不同构建目录名仍通过 test-classes/solon-aot 片段稳定识别。 */
        @Override
        public boolean includes(Location location) {
            return !location.contains("test-classes") && !location.contains("solon-aot");
        }
    }

    /** conversation 领域模型只能依赖自身、JDK 与 foundation，避免框架类型进入业务状态。 */
    @ArchTest
    static final ArchRule CONVERSATION_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..conversation.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.conversation.domain..");

    /** workspace 领域模型只能依赖自身、JDK 与 foundation，路径策略不得反向耦合适配器。 */
    @ArchTest
    static final ArchRule WORKSPACE_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..workspace.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.workspace.domain..");

    /** configuration 领域模型只能依赖自身、JDK 与 foundation，配置格式由 adapter 解释。 */
    @ArchTest
    static final ArchRule CONFIGURATION_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..configuration.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.configuration.domain..");

    /** catalog 领域模型只能依赖自身、JDK 与 foundation，MCP SDK 只能停留在出站适配器。 */
    @ArchTest
    static final ArchRule CATALOG_DOMAIN_IS_PURE = noClasses()
            .that().resideInAPackage("..catalog.domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages(
                    "java..", "javax..", "io.github.kongweiguang.ja.foundation..",
                    "io.github.kongweiguang.ja.catalog.domain..");

    /** application 只能编排领域与端口，禁止直接消费任一适配器、传输层或基础设施实现。 */
    @ArchTest
    static final ArchRule APPLICATION_DOES_NOT_DEPEND_ON_IMPLEMENTATIONS = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapter..", "..transport..", "..bootstrap..",
                    "..infrastructure..", "..platform..");

    /**
     * 所有出站适配器只能实现 port.out，跨域协作必须经消费者自有出站端口在 bootstrap 桥接。
     */
    @ArchTest
    static final ArchRule OUTBOUND_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND_PORTS = noClasses()
            .that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..port.in..");

    /** 配置出站适配器只能面向领域与 port.out，禁止再次实现或消费任何入站用例。 */
    @ArchTest
    static final ArchRule CONFIGURATION_ADAPTERS_DO_NOT_DEPEND_ON_INBOUND_PORTS = noClasses()
            .that().resideInAPackage("..configuration.adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..configuration.port.in..");

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

    /** JA-RPC 入站层不得越过端口调用应用实现或出站实现，Wire DTO 仅停留在 transport。 */
    @ArchTest
    static final ArchRule TRANSPORT_DOES_NOT_DEPEND_ON_IMPLEMENTATIONS = noClasses()
            .that().resideInAPackage("io.github.kongweiguang.ja.transport..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapter..", "..infrastructure..",
                    "..platform..", "..bootstrap..");

    /** 配置业务入口只能声明在 configuration.port.in，防止 transport 再创建混合业务接口。 */
    @ArchTest
    static final ArchRule CONFIGURATION_USE_CASE_IS_DECLARED_IN_OWNED_PORT = classes()
            .that().areInterfaces().and().areAssignableTo(ConfigurationUseCase.class)
            .should().resideInAPackage("..configuration.port.in..");

    /** 配置代际租约入口同样由配置域所有，Catalog transport 不得声明转发端口。 */
    @ArchTest
    static final ArchRule CONFIGURATION_GENERATION_USE_CASE_IS_DECLARED_IN_OWNED_PORT = classes()
            .that().areInterfaces().and().areAssignableTo(ConfigurationGenerationUseCase.class)
            .should().resideInAPackage("..configuration.port.in..");

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

    /** conversation.port 只允许 in/out 子包，防止新类型再次落回含混的裸端口命名空间。 */
    @ArchTest
    static final ArchRule CONVERSATION_PORT_ROOT_IS_EMPTY = noClasses()
            .should().resideInAPackage("io.github.kongweiguang.ja.conversation.port");

    /** 出站 SPI 不得消费入站用例或完成合同，避免 Adapter 反向驱动 transport。 */
    @ArchTest
    static final ArchRule CONVERSATION_OUT_DOES_NOT_DEPEND_ON_IN = noClasses()
            .that().resideInAPackage("..conversation.port.out..")
            .should().dependOnClassesThat().resideInAPackage("..conversation.port.in..");

    /** 入站合同只依赖领域与 foundation，不得携带 Provider、Repository 或 Tool SPI。 */
    @ArchTest
    static final ArchRule CONVERSATION_IN_DOES_NOT_DEPEND_ON_OUT = noClasses()
            .that().resideInAPackage("..conversation.port.in..")
            .should().dependOnClassesThat().resideInAPackage("..conversation.port.out..");

    /**
     * Agent 执行循环只能经 conversation 自有端口调用其它领域，新增 Goal、Task 或后续能力时不得把
     * 具体领域类型重新引入核心循环，否则每次扩展仍需修改内核并破坏开闭原则。
     */
    @ArchTest
    static final ArchRule CONVERSATION_LOOP_DOES_NOT_DEPEND_ON_FEATURE_DOMAINS = noClasses()
            .that().resideInAPackage("..conversation.application.loop..")
            .should().dependOnClassesThat().resideInAnyPackage("..goal..", "..task..");

    /**
     * conversation 内部按 domain、application、port 与 adapter 单向协作；顶层无环规则无法发现
     * 同一领域内部的回流，因此单独冻结这条 DDD 边界。
     */
    @ArchTest
    static final ArchRule CONVERSATION_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.conversation.(*)..")
            .should().beFreeOfCycles();

    /**
     * 四类 Agent 扩展契约由 conversation 出站端口唯一拥有，具体领域只能实现这些端口，
     * 不得在 application 或业务域复制第二套同名 SPI。
     */
    @ArchTest
    static final ArchRule AGENT_EXTENSION_CONTRACTS_BELONG_TO_CONVERSATION_OUT_PORT = classes()
            .that().haveSimpleName("AgentCapability")
            .or().haveSimpleName("ContextTransform")
            .or().haveSimpleName("ToolPolicy")
            .or().haveSimpleName("ExecutionObserver")
            .should().resideInAPackage("..conversation.port.out..");

    /** bootstrap 只能作为最外层组合根，任何其它生产包都不得反向依赖它。 */
    @ArchTest
    static final ArchRule BOOTSTRAP_HAS_NO_INBOUND_DEPENDENCIES = noClasses()
            .that().resideOutsideOfPackage("..bootstrap..")
            .should().dependOnClassesThat().resideInAPackage("..bootstrap..");

    /** Provider 策略、共享流基础设施与摘要适配不得形成叶子包回路，避免再次退化为单一巨型包。 */
    @ArchTest
    static final ArchRule PROVIDER_RESPONSIBILITY_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.conversation.adapter.out.provider.(*)..")
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
            .matching("io.github.kongweiguang.ja.catalog.adapter.out.mcp.(*)..")
            .should().beFreeOfCycles();

    /** 配置叶子实现不得反向调用运行时 Facade，组合与关闭所有权始终停留在根适配器。 */
    @ArchTest
    static final ArchRule CONFIGURATION_LEAVES_DO_NOT_DEPEND_ON_RUNTIME_FACADE = noClasses()
            .that().resideInAnyPackage(
                    "..configuration.adapter.out.document..",
                    "..configuration.adapter.out.generation..",
                    "..configuration.adapter.out.security..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "io.github.kongweiguang.ja.configuration.adapter.out.ConfigurationRuntimeAdapter");

    /** checkpoint 与 summary 值可互相组合，但都不能反向持有 compaction 编排实现。 */
    @ArchTest
    static final ArchRule CONTEXT_VALUES_DO_NOT_DEPEND_ON_COMPACTION = noClasses()
            .that().resideInAnyPackage(
                    "..conversation.application.context.checkpoint..",
                    "..conversation.application.context.summary..")
            .should().dependOnClassesThat().resideInAPackage(
                    "..conversation.application.context.compaction..");

    /** 顶层责任包必须无环，避免局部端口化后仍通过另一业务域形成隐藏回路。 */
    @ArchTest
    static final ArchRule TOP_LEVEL_PACKAGES_ARE_ACYCLIC = slices()
            .matching("io.github.kongweiguang.ja.(*)..")
            .should().beFreeOfCycles();
}
