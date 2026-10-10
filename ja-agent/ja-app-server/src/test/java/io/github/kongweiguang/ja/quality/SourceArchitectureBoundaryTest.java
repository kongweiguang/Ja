// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** 以 JDK 25 AST 检查 Agent Core 源码依赖，补足常量内联后字节码规则看不到的越层引用。 */
final class SourceArchitectureBoundaryTest {
    private static final String AGENT_ROOT = "io.github.kongweiguang.ja.agent";
    private static final String APPLICATION = AGENT_ROOT + ".application";
    private static final String DOMAIN = AGENT_ROOT + ".domain";
    private static final String EXTENSION = AGENT_ROOT + ".extension";
    private static final String INFRASTRUCTURE = AGENT_ROOT + ".infrastructure";
    private static final String ADAPTER = AGENT_ROOT + ".adapter";
    private static final String JA_ROOT = "io.github.kongweiguang.ja";
    private static final String NETWORKNT_VALIDATION = JA_ROOT + ".validation.networknt";
    private static final String EXAMPLE_ROOT = JA_ROOT + ".agent.example.springboot";
    private static final List<String> EXAMPLE_CONTEXTS = List.of(
            "agent", "identity", "attachment", "conversation", "experience");
    private static final String FOUNDATION = JA_ROOT + ".foundation";
    private static final List<String> BUSINESS_DOMAINS = List.of(
            "conversation", "configuration", "workspace", "catalog", "plan", "attachment",
            "collaboration");
    private static final List<String> STANDARD_LAYERS = List.of(
            "interfaces", "application", "domain", "infrastructure", "bootstrap");
    private static final List<String> FRAMEWORK_AND_DATABASE_PACKAGES = List.of(
            "org.noear", "org.springframework", "org.xerial", "org.postgresql", "org.apache.ibatis",
            "org.mybatis", "org.flywaydb", "org.sqlite");

    /** 内存源码覆盖普通、静态、通配导入及限定常量，并排除注释、字符串与相似包段误报。 */
    @Test
    void inMemorySourcesReportOnlyForbiddenDependencies() throws IOException {
        List<JavaFileObject> fixtures = List.of(
                new MemorySource("app/IllegalImports.java", """
                        package io.github.kongweiguang.ja.agent.application.fixture;
                        import io.github.kongweiguang.ja.agent.infrastructure.Hidden;
                        import io.github.kongweiguang.ja.agent.adapter.internal.*;
                        import static io.github.kongweiguang.ja.agent.infrastructure.ControlPayloadCodec.FLAG;
                        import static org.springframework.context.RuntimeFlags.*;
                        import static io.github.kongweiguang.ja.agent.infrastructure.persistence.Outer.Inner.FLAG;
                        final class IllegalImports {
                            // io.github.kongweiguang.ja.agent.infrastructure.Hidden.FLAG
                            String text = "io.github.kongweiguang.ja.agent.infrastructure.Hidden.FLAG";
                            io.github.kongweiguang.ja.agent.infrastructureish.Helper infrastructureLike;
                            io.github.kongweiguang.ja.agent.adapterial.Helper adapterLike;
                            org.springframeworkish.context.Context frameworkLike;
                            org.postgresqlish.Driver databaseLike;
                            int value = io.github.kongweiguang.ja.agent.infrastructure.ControlPayloadCodec.FLAG;
                            int nestedValue = io.github.kongweiguang.ja.agent.infrastructure.persistence.Outer.Inner.FLAG;
                        }
                        """),
                new MemorySource("domain/IllegalDependency.java", """
                        package io.github.kongweiguang.ja.agent.domain.fixture;
                        import java.util.List;
                        import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
                        import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
                        final class IllegalDependency {
                            String text = "io.github.kongweiguang.ja.agent.application.gateway.AgentTool";
                            Object jdkValue = java.util.Objects.requireNonNull("valid");
                            io.github.kongweiguang.ja.agent.application.gateway.AgentTool qualifiedTool;
                            Object ordinaryCall = Objects.requireNonNull("valid");
                        }
                        """),
                new MemorySource("domain/SharedValue.java", """
                        package io.github.kongweiguang.ja.agent.domain.fixture;
                        import io.github.kongweiguang.ja.agent.shared.Hashing;
                        final class SharedValue { Hashing value; }
                        """),
                new MemorySource("extension/IllegalDependency.java", """
                        package io.github.kongweiguang.ja.agent.application.gateway.fixture;
                        import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
                        final class IllegalDependency { }
                        """),
                new MemorySource("applicationish/SimilarSourcePackage.java", """
                        package io.github.kongweiguang.ja.agent.applicationish.fixture;
                        import io.github.kongweiguang.ja.agent.infrastructure.Hidden;
                        final class SimilarSourcePackage { }
                        """));

        List<String> actual = scanMemorySources(fixtures).stream()
                .map(violation -> violation.source() + ":" + violation.line())
                .toList();
        assertEquals(List.of(
                "app/IllegalImports.java:2",
                "app/IllegalImports.java:3",
                "app/IllegalImports.java:4",
                "app/IllegalImports.java:5",
                "app/IllegalImports.java:6",
                "app/IllegalImports.java:14",
                "app/IllegalImports.java:15",
                "domain/IllegalDependency.java:4",
                "domain/IllegalDependency.java:8",
                "domain/SharedValue.java:2",
                "extension/IllegalDependency.java:2"), actual);
    }

    /** 扫描 Agent Core 的全部 main Java 源码，违规诊断包含真实文件路径和行号。 */
    @Test
    void coreProductionSourcesRespectDependencyBoundaries() throws IOException {
        Path coreSourceRoot = TestModulePaths.appServerRoot().getParent()
                .resolve("ja-agent-core/src/main/java");
        assertTrue(Files.isDirectory(coreSourceRoot), () -> "Agent Core 源码目录不存在：" + coreSourceRoot);
        List<Path> sources;
        try (Stream<Path> files = Files.walk(coreSourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(sources.isEmpty(), "Agent Core 必须包含 main Java 源码");
        List<Violation> violations = scanPaths(sources);
        assertTrue(violations.isEmpty(), () -> "Agent Core 源码依赖越层：\n"
                + String.join("\n", violations.stream().map(Violation::message).toList()));
    }

    /**
     * 只扫描当前已落地的 domain 与 foundation 源码，冻结四层最内边界和公共工具的依赖方向；
     * application/adapter 的跨域桥仍由组合根逐步迁移，严格跨域回归由内存夹具覆盖。
     */
    @Test
    void appServerDomainAndFoundationSourcesRespectDependencyBoundaries() throws IOException {
        Path sourceRoot = TestModulePaths.appServerRoot().resolve("src/main/java");
        assertTrue(Files.isDirectory(sourceRoot), () -> "App Server 源码目录不存在：" + sourceRoot);
        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java"))
                    .filter(SourceArchitectureBoundaryTest::isDomainOrFoundationSource)
                    .sorted().toList();
        }
        assertFalse(sources.isEmpty(), "App Server 必须包含 domain/foundation 源码");
        List<Violation> violations = scanPaths(sources);
        assertTrue(violations.isEmpty(), () -> "App Server domain/foundation 依赖越层：\n"
                + String.join("\n", violations.stream().map(Violation::message).toList()));
    }

    /** 验证器是独立技术适配，只允许 JDK、Networknt、Jackson 与自身类型依赖。 */
    @Test
    void networkntValidationAdapterUsesClosedTechnicalDependencies() throws IOException {
        Path sourceRoot = TestModulePaths.appServerRoot().resolve("src/main/java")
                .resolve(NETWORKNT_VALIDATION.replace('.', '/'));
        assertTrue(Files.isDirectory(sourceRoot), () -> "Networknt 验证器源码目录不存在：" + sourceRoot);
        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(sources.isEmpty(), "Networknt 验证器技术包必须包含生产源码");
        List<Violation> violations = scanPaths(sources);
        assertTrue(violations.isEmpty(), () -> "Networknt 验证器引入闭集外依赖：\n"
                + String.join("\n", violations.stream().map(Violation::message).toList()));
    }

    /** 负夹具锁定技术包不得反向依赖业务或额外框架，防止闭集规则被误删。 */
    @Test
    void networkntValidationAdapterRejectsBusinessAndFrameworkDependencies() throws IOException {
        List<JavaFileObject> fixtures = List.of(new MemorySource("validation/IllegalDependency.java", """
                package io.github.kongweiguang.ja.validation.networknt.fixture;
                import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
                import org.slf4j.Logger;
                final class IllegalDependency { TurnUseCase useCase; Logger logger; }
                """));

        List<String> violations = scanMemorySources(fixtures).stream()
                .map(violation -> violation.source() + ":" + violation.line() + ":" + violation.reason())
                .toList();

        assertEquals(List.of(
                "validation/IllegalDependency.java:2:Networknt 验证器只能依赖 JDK、Networknt、Jackson 与自身",
                "validation/IllegalDependency.java:3:Networknt 验证器只能依赖 JDK、Networknt、Jackson 与自身"),
                violations);
    }

    /** 扫描独立 Spring example 的真实源码，防止 application/domain 把技术实现重新带回用例层。 */
    @Test
    void springExampleSourcesRespectDependencyBoundaries() throws IOException {
        Path exampleSourceRoot = TestModulePaths.appServerRoot().getParent()
                .resolve("example/ja-agent-example-springboot/src/main/java");
        assertTrue(Files.isDirectory(exampleSourceRoot), () -> "Spring example 源码目录不存在：" + exampleSourceRoot);
        List<Path> sources;
        try (Stream<Path> files = Files.walk(exampleSourceRoot)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
        assertFalse(sources.isEmpty(), "Spring example 必须包含 main Java 源码");
        List<Violation> violations = scanPaths(sources);
        assertTrue(violations.isEmpty(), () -> "Spring example 依赖越层：\n"
                + String.join("\n", violations.stream().map(Violation::message).toList()));
    }

    /** 使用当前 Spring Boot 根包固定负例，避免包名迁移后真实源码与夹具一起绕过分层检查。 */
    @Test
    void springExampleFixturesRejectImplementationDependencies() throws IOException {
        List<JavaFileObject> fixtures = List.of(
                new MemorySource("example/application/CoreInfrastructure.java", """
                        package io.github.kongweiguang.ja.agent.example.springboot.experience.application.fixture;
                        import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
                        final class CoreInfrastructure { ControlPayloadCodec value; }
                        """),
                new MemorySource("example/domain/ExampleInfrastructure.java", """
                        package io.github.kongweiguang.ja.agent.example.springboot.experience.domain.fixture;
                        import io.github.kongweiguang.ja.agent.example.springboot.experience.infrastructure.json.JacksonExperienceJsonCodec;
                        final class ExampleInfrastructure { JacksonExperienceJsonCodec value; }
                        """));

        List<Violation> violations = scanMemorySources(fixtures);
        assertEquals(List.of(
                "example/application/CoreInfrastructure.java:2:example application/domain 不得依赖 Core infrastructure "
                        + "或 example adapter/infrastructure",
                "example/domain/ExampleInfrastructure.java:2:example application/domain 不得依赖 Core infrastructure "
                        + "或 example adapter/infrastructure"),
                violations.stream().map(violation -> violation.source() + ":" + violation.line() + ":"
                        + violation.reason()).toList());
    }

    /**
     * foundation 可以组合两个不可变持久化身份键，但不能因此获得 Store 端口、快照或应用服务；
     * 正负夹具共同约束精确值类型边界，防止共享身份迁移演变成整包豁免。
     */
    @Test
    void foundationIdentityKeysRemainValuesRatherThanStoragePorts() throws IOException {
        List<JavaFileObject> fixtures = List.of(
                new MemorySource("foundation/IdentityKeys.java", """
                        package io.github.kongweiguang.ja.foundation.runtime.fixture;
                        import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
                        import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ExecutionKey;
                        final class IdentityKeys { ConversationKey conversation; ExecutionKey execution; }
                        """),
                new MemorySource("foundation/CoreStorePort.java", """
                        package io.github.kongweiguang.ja.foundation.runtime.fixture;
                        import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
                        final class CoreStorePort { AgentStore store; }
                        """),
                new MemorySource("foundation/CoreStoreSnapshot.java", """
                        package io.github.kongweiguang.ja.foundation.runtime.fixture;
                        import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationSnapshot;
                        final class CoreStoreSnapshot { ConversationSnapshot snapshot; }
                        """),
                new MemorySource("foundation/CoreApplication.java", """
                        package io.github.kongweiguang.ja.foundation.runtime.fixture;
                        import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
                        final class CoreApplication { AgentEngine engine; }
                        """));

        List<String> actual = scanMemorySources(fixtures).stream()
                .map(violation -> violation.source() + ":" + violation.line() + ":" + violation.reason())
                .toList();
        assertEquals(List.of(
                "foundation/CoreApplication.java:2:foundation 公共工具不得依赖业务域或框架实现",
                "foundation/CoreStorePort.java:2:foundation 公共工具不得依赖业务域或框架实现",
                "foundation/CoreStoreSnapshot.java:2:foundation 公共工具不得依赖业务域或框架实现"), actual);
    }

    /**
     * 以最小夹具覆盖四层方向、跨域隔离和 foundation 公共工具边界；生产迁移中的桥接代码不参与豁免。
     */
    @Test
    void inMemorySourcesReportFourLayerCrossDomainAndFoundationViolations() throws IOException {
        List<JavaFileObject> fixtures = List.of(
                new MemorySource("domain/CrossDomain.java", """
                        package io.github.kongweiguang.ja.conversation.domain.model.fixture;
                        import io.github.kongweiguang.ja.configuration.domain.model.ConfigurationError;
                        final class CrossDomain { ConfigurationError value; }
                        """),
                new MemorySource("domain/DomainToApplication.java", """
                        package io.github.kongweiguang.ja.conversation.domain.model.fixture;
                        import io.github.kongweiguang.ja.conversation.application.service.TurnService;
                        final class DomainToApplication { TurnService value; }
                        """),
                new MemorySource("application/ApplicationToAdapter.java", """
                        package io.github.kongweiguang.ja.conversation.application.fixture;
                        import io.github.kongweiguang.ja.conversation.infrastructure.ja_tool_call.BuiltInTools;
                        final class ApplicationToAdapter { BuiltInTools value; }
                        """),
                new MemorySource("port/InboundToOutbound.java", """
                        package io.github.kongweiguang.ja.conversation.port.in.fixture;
                        import io.github.kongweiguang.ja.conversation.port.out.JaTool;
                        final class InboundToOutbound { JaTool value; }
                        """),
                new MemorySource("adapter/OutboundToInbound.java", """
                        package io.github.kongweiguang.ja.conversation.infrastructure.fixture;
                        import io.github.kongweiguang.ja.conversation.application.api.TurnUseCase;
                        final class OutboundToInbound { TurnUseCase value; }
                        """),
                new MemorySource("adapter/InboundToOutbound.java", """
                        package io.github.kongweiguang.ja.conversation.interfaces.fixture;
                        import io.github.kongweiguang.ja.conversation.infrastructure.ja_tool_call.BuiltInTools;
                        final class InboundToOutbound { BuiltInTools value; }
                        """),
                new MemorySource("foundation/FoundationToDomain.java", """
                        package io.github.kongweiguang.ja.foundation.fixture;
                        import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
                        final class FoundationToDomain { ThreadSnapshot value; }
                        """),
                new MemorySource("collaboration/interfaces/DirectDomain.java", """
                        package io.github.kongweiguang.ja.collaboration.interfaces.fixture;
                        import io.github.kongweiguang.ja.conversation.port.out.JaTool;
                        final class DirectDomain { JaTool value; }
                        """),
                new MemorySource("collaboration/application/gateway/PublicContract.java", """
                        package io.github.kongweiguang.ja.collaboration.application.gateway.fixture;
                        import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
                        final class PublicContract { ThreadSummary value; }
                        """),
                new MemorySource("collaboration/application/service/DirectDomain.java", """
                        package io.github.kongweiguang.ja.collaboration.application.service.fixture;
                        import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
                        final class DirectDomain { ThreadSummary value; }
                        """),
                new MemorySource("collaboration/infrastructure/acl/AllowedAcl.java", """
                        package io.github.kongweiguang.ja.collaboration.infrastructure.acl.fixture;
                        import io.github.kongweiguang.ja.conversation.port.out.JaTool;
                        final class AllowedAcl { JaTool value; }
                        """),
                new MemorySource("collaboration/adapter/LegacyLayer.java", """
                        package io.github.kongweiguang.ja.collaboration.adapter.fixture;
                        final class LegacyLayer { }
                        """),
                new MemorySource("foundation/FrameworkTool.java", """
                        package io.github.kongweiguang.ja.foundation.fixture;
                        import org.example.Framework;
                        final class FrameworkTool { Framework value; }
                        """),
                new MemorySource("task/LegacyTask.java", """
                        package io.github.kongweiguang.ja.task.fixture;
                        final class LegacyTask { }
                        """),
                new MemorySource("goal/LegacyGoal.java", """
                        package io.github.kongweiguang.ja.goal.fixture;
                        final class LegacyGoal { }
                        """));

        List<String> actual = scanMemorySources(fixtures).stream()
                .map(violation -> violation.source() + ":" + violation.line() + ":" + violation.reason())
                .toList();
        assertEquals(List.of(
                "adapter/InboundToOutbound.java:2:interfaces 不得依赖 infrastructure 或 bootstrap",
                "application/ApplicationToAdapter.java:2:application 不得依赖 adapter/transport/bootstrap/infrastructure/platform",
                "collaboration/adapter/LegacyLayer.java:1:collaboration 必须使用 interfaces/application/domain/infrastructure/bootstrap 分层",
                "collaboration/application/service/DirectDomain.java:2:跨域依赖必须经本域 infrastructure.acl 转换后访问公开 API",
                "collaboration/interfaces/DirectDomain.java:2:跨域依赖必须经本域 infrastructure.acl 转换后访问公开 API",
                "domain/CrossDomain.java:2:业务域之间必须通过组合根桥接，domain 不得直接引用其它业务域",
                "domain/DomainToApplication.java:2:domain 只能依赖 JDK、foundation、Agent domain 与本域 domain",
                "foundation/FoundationToDomain.java:2:foundation 公共工具不得依赖业务域或框架实现",
                "foundation/FrameworkTool.java:2:foundation 公共工具不得依赖业务域或框架实现",
                "goal/LegacyGoal.java:1:旧 Goal 包已删除，不得重新声明",
                "port/InboundToOutbound.java:2:port.in 不得依赖 port.out、application 或 adapter",
                "task/LegacyTask.java:1:旧 task 包已删除，不得重新声明"), actual);
    }

    /** 使用 JDK 25 JavacTask 只解析语法树，不解析符号或装配业务 classpath。 */
    private static List<Violation> scanMemorySources(List<? extends JavaFileObject> sources) throws IOException {
        JavaCompiler compiler = requireCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            return parseAndScan(compiler, files, sources);
        }
    }

    /** 从真实路径创建 Javac 文件对象，并在同一文件管理器生命周期内完成 parse。 */
    private static List<Violation> scanPaths(List<Path> paths) throws IOException {
        JavaCompiler compiler = requireCompiler();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            return parseAndScan(compiler, files, files.getJavaFileObjectsFromPaths(paths));
        }
    }

    /** 只把当前最内层 domain 与 foundation 交给生产扫描，避免把迁移中的组合桥当作层规则豁免。 */
    private static boolean isDomainOrFoundationSource(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/foundation/") || normalized.matches(".*/(?:conversation|configuration|workspace|catalog|plan)/domain/.*\\.java");
    }

    /** 用 parse-only AST 检查 import 与最大限定名节点，并保留 Javac 语法错误的源码位置。 */
    private static List<Violation> parseAndScan(JavaCompiler compiler, StandardJavaFileManager files,
                                                 Iterable<? extends JavaFileObject> sources) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                List.of("--release", "25", "-proc:none"), null, sources);
        Trees trees = Trees.instance(task);
        List<Violation> violations = new ArrayList<>();
        for (CompilationUnitTree unit : task.parse()) {
            String sourcePackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            String packageReason = packageViolation(sourcePackage);
            if (packageReason != null) {
                violations.add(new Violation(unit.getSourceFile().getName(), 1, sourcePackage, packageReason));
            }
            new DependencyScanner(unit, trees, violations).scan(new TreePath(unit), null);
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String source = diagnostic.getSource() == null ? "javac" : diagnostic.getSource().getName();
                violations.add(new Violation(source, diagnostic.getLineNumber(), "syntax", diagnostic.getMessage(Locale.ROOT)));
            }
        }
        violations.sort(Comparator.comparing(Violation::source)
                .thenComparingLong(Violation::line).thenComparing(Violation::dependency));
        return List.copyOf(violations);
    }

    /** 要求完整 JDK，避免 JRE 或精简镜像静默跳过编译器 API。 */
    private static JavaCompiler requireCompiler() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("源码架构门禁必须使用完整 JDK 25");
        }
        return compiler;
    }

    /** 按架构边界归类源码依赖；共享身份只借用明确的 Core 值类型，不开放持久化端口。 */
    private static String forbiddenReason(String sourcePackage, String dependency) {
        if (inPackage(sourcePackage, NETWORKNT_VALIDATION)) {
            boolean allowed = isJdkOrPackage(dependency, NETWORKNT_VALIDATION)
                    || inPackage(dependency, "com.networknt.schema")
                    || inPackage(dependency, "tools.jackson");
            if (!allowed) {
                return "Networknt 验证器只能依赖 JDK、Networknt、Jackson 与自身";
            }
        }
        String exampleLayer = exampleLayer(sourcePackage);
        if (exampleLayer != null) {
            boolean coreInfrastructure = inPackage(dependency, INFRASTRUCTURE);
            boolean exampleImplementation = dependency.startsWith(EXAMPLE_ROOT + ".")
                    && hasAnyPackage(dependency, ".adapter.", ".infrastructure.");
            if ((exampleLayer.equals("application") || exampleLayer.equals("domain"))
                    && (coreInfrastructure || exampleImplementation)) {
                return "example application/domain 不得依赖 Core infrastructure 或 example adapter/infrastructure";
            }
            if (exampleLayer.equals("domain")
                    && (isSqlPackage(dependency) || FRAMEWORK_AND_DATABASE_PACKAGES.stream()
                    .anyMatch(prefix -> inPackage(dependency, prefix)))) {
                return "example domain 必须保持纯领域依赖";
            }
        }
        String sourceDomain = businessDomain(sourcePackage);
        String sourceLayer = businessLayer(sourcePackage);
        if (sourceDomain != null && sourceLayer != null) {
            String dependencyDomain = businessDomain(dependency);
            if (dependencyDomain != null && !sourceDomain.equals(dependencyDomain)
                    && !isAllowedCrossDomainAcl(sourcePackage, dependency)) {
                if (sourceDomain.equals("collaboration")) {
                    return "跨域依赖必须经本域 infrastructure.acl 转换后访问公开 API";
                }
                return "业务域之间必须通过组合根桥接，" + sourceLayer + " 不得直接引用其它业务域";
            }
            if (sourceLayer.equals("domain")) {
                boolean allowed = inPackage(dependency, "java") || inPackage(dependency, "javax")
                        || inPackage(dependency, FOUNDATION) || inPackage(dependency, AGENT_ROOT + ".domain")
                        || inPackage(dependency, JA_ROOT + "." + sourceDomain + ".domain");
                if (!allowed) {
                    return "domain 只能依赖 JDK、foundation、Agent domain 与本域 domain";
                }
            } else if (sourceLayer.equals("application")
                    && hasAnyPackage(dependency, ".adapter.", ".transport.", ".bootstrap.",
                            ".infrastructure.", ".platform.")) {
                return "application 不得依赖 adapter/transport/bootstrap/infrastructure/platform";
            } else if (sourceLayer.equals("port.in")
                    && hasAnyPackage(dependency, ".port.out", ".application.", ".adapter.")) {
                return "port.in 不得依赖 port.out、application 或 adapter";
            } else if (sourceLayer.equals("port.out")
                    && hasAnyPackage(dependency, ".port.in", ".application.", ".adapter.in")) {
                return "port.out 不得依赖 port.in、application 或 adapter.in";
            } else if (sourceLayer.equals("adapter.out")
                    && hasAnyPackage(dependency, ".port.in", ".adapter.in", ".transport.", ".bootstrap.")) {
                return "adapter.out 不得依赖 port.in 或 adapter.in";
            } else if (sourceLayer.equals("adapter.in")
                    && hasAnyPackage(dependency, ".adapter.out", ".transport.", ".bootstrap.")) {
                return "adapter.in 不得依赖 adapter.out、transport 或 bootstrap";
            } else if (sourceLayer.equals("interfaces")
                    && hasAnyPackage(dependency, ".infrastructure.", ".bootstrap.")) {
                return "interfaces 不得依赖 infrastructure 或 bootstrap";
            }
        }
        if (inPackage(sourcePackage, FOUNDATION)
                && !isJdkOrPackage(dependency, FOUNDATION)
                && !inPackage(dependency, AGENT_ROOT + ".domain")
                && !isSharedPersistenceIdentityKey(dependency)) {
            return "foundation 公共工具不得依赖业务域或框架实现";
        }
        if (inPackage(sourcePackage, APPLICATION)) {
            if (inPackage(dependency, INFRASTRUCTURE) || inPackage(dependency, ADAPTER)) {
                return "application 不得依赖 Agent infrastructure/adapter";
            }
            if (isSqlPackage(dependency)) {
                return "application 不得依赖 JDBC";
            }
            for (String prefix : FRAMEWORK_AND_DATABASE_PACKAGES) {
                if (inPackage(dependency, prefix)) {
                    return "application 不得依赖框架或数据库驱动";
                }
            }
        }
        if (inPackage(sourcePackage, EXTENSION) && inPackage(dependency, INFRASTRUCTURE)) {
            return "extension 不得依赖 Agent infrastructure";
        }
        if (inPackage(sourcePackage, DOMAIN)) {
            boolean allowed = inPackage(dependency, "java") || inPackage(dependency, "javax")
                    || inPackage(dependency, DOMAIN);
            if (!allowed || isSqlPackage(dependency)) {
                return "domain 仅可依赖 JDK 与 Agent domain";
            }
        }
        return null;
    }

    /**
     * App Server 的共享主体只需要 Core 会话与执行键；精确匹配嵌套 record，
     * 避免外层 AgentStore 或同包其它应用类型获得 foundation 依赖资格。
     */
    private static boolean isSharedPersistenceIdentityKey(String dependency) {
        String owner = AGENT_ROOT + ".application.gateway.store.AgentStore.";
        return dependency.equals(owner + "ConversationKey") || dependency.equals(owner + "ExecutionKey");
    }

    /** 只允许本域 application gateway 合同或 infrastructure.acl 通过外部公开类型完成跨域转换。 */
    private static boolean isAllowedCrossDomainAcl(String sourcePackage, String dependency) {
        if (sourcePackage.contains(".infrastructure.acl.")) {
            return hasAnyPackage(dependency, ".port.in", ".port.out", ".interfaces.", ".application.api",
                    ".application.gateway");
        }
        return sourcePackage.matches(".*\\.application\\.(api|gateway)(\\..*)?")
                && hasAnyPackage(dependency, ".application.api", ".application.gateway", ".domain.model");
    }

    /** 示例上下文来自已确认的模块划分；层级和依赖方向仍逐包校验，bootstrap 仅作为组合根放行。 */
    private static String packageViolation(String sourcePackage) {
        if (inPackage(sourcePackage, JA_ROOT + ".task") || inPackage(sourcePackage, JA_ROOT + ".goal")) {
            String label = sourcePackage.contains(".goal") ? "Goal" : "task";
            return "旧 " + label + " 包已删除，不得重新声明";
        }
        if (sourcePackage.startsWith(EXAMPLE_ROOT + ".")) {
            String remainder = sourcePackage.substring((EXAMPLE_ROOT + ".").length());
            String[] segments = remainder.split("\\.");
            if (segments.length > 1 && EXAMPLE_CONTEXTS.contains(segments[0])
                    && !List.of("application", "domain", "port", "adapter", "infrastructure", "bootstrap")
                    .contains(segments[1])) {
                return "example context 必须使用 application/domain/port/adapter/infrastructure/bootstrap 分层";
            }
            if (segments.length > 0 && !EXAMPLE_CONTEXTS.contains(segments[0])
                    && !"bootstrap".equals(segments[0])) {
                return "example production package must belong to agent, identity, attachment, conversation, "
                        + "experience or bootstrap context";
            }
        }
        if (!sourcePackage.startsWith(JA_ROOT + ".collaboration.")) {
            return null;
        }
        String remainder = sourcePackage.substring((JA_ROOT + ".collaboration.").length());
        String layer = remainder.contains(".") ? remainder.substring(0, remainder.indexOf('.')) : remainder;
        return STANDARD_LAYERS.contains(layer)
                ? null : "collaboration 必须使用 interfaces/application/domain/infrastructure/bootstrap 分层";
    }

    /** 识别当前业务域的根包，避免把 foundation、transport 等横向包误判为业务域。 */
    private static String businessDomain(String packageName) {
        String prefix = JA_ROOT + ".";
        if (!packageName.startsWith(prefix)) {
            return null;
        }
        String remainder = packageName.substring(prefix.length());
        int separator = remainder.indexOf('.');
        String candidate = separator < 0 ? remainder : remainder.substring(0, separator);
        return BUSINESS_DOMAINS.contains(candidate) ? candidate : null;
    }

    /** 对每个已确认业务上下文执行相同分层依赖检查，避免独立模块绕过 Core adapter 边界。 */
    private static String exampleLayer(String packageName) {
        if (!packageName.startsWith(EXAMPLE_ROOT + ".")) {
            return null;
        }
        String[] segments = packageName.substring((EXAMPLE_ROOT + ".").length()).split("\\.");
        if (segments.length < 2 || !EXAMPLE_CONTEXTS.contains(segments[0])) {
            return null;
        }
        return segments[1];
    }

    /** 读取标准四层与历史 hexagonal 映射；port 与 adapter 必须再带 in/out 方向。 */
    private static String businessLayer(String packageName) {
        String domain = businessDomain(packageName);
        if (domain == null) {
            return null;
        }
        String remainder = packageName.substring((JA_ROOT + "." + domain + ".").length());
        if (remainder.equals("domain") || remainder.startsWith("domain.")) {
            return "domain";
        }
        if (remainder.equals("application") || remainder.startsWith("application.")) {
            return "application";
        }
        if (remainder.equals("interfaces") || remainder.startsWith("interfaces.")) {
            return "interfaces";
        }
        if (remainder.equals("infrastructure") || remainder.startsWith("infrastructure.")) {
            return "infrastructure";
        }
        if (remainder.equals("bootstrap") || remainder.startsWith("bootstrap.")) {
            return "bootstrap";
        }
        if (remainder.equals("port.in") || remainder.startsWith("port.in.")) {
            return "port.in";
        }
        if (remainder.equals("port.out") || remainder.startsWith("port.out.")) {
            return "port.out";
        }
        if (remainder.equals("adapter.in") || remainder.startsWith("adapter.in.")) {
            return "adapter.in";
        }
        if (remainder.equals("adapter.out") || remainder.startsWith("adapter.out.")) {
            return "adapter.out";
        }
        return null;
    }

    /** 对已分类的 package 做 JDK/公共工具判断，拒绝把任意 Ja 实现伪装成基础依赖。 */
    private static boolean isJdkOrPackage(String dependency, String packageName) {
        return inPackage(dependency, "java") || inPackage(dependency, "javax")
                || inPackage(dependency, packageName);
    }

    /** 只按完整包段匹配层边界，避免 adapterial、platformish 等相似名称误报。 */
    private static boolean hasAnyPackage(String dependency, String... fragments) {
        String boundedDependency = "." + dependency + ".";
        for (String fragment : fragments) {
            String token = fragment;
            if (token.startsWith(".")) {
                token = token.substring(1);
            }
            if (token.endsWith(".")) {
                token = token.substring(0, token.length() - 1);
            }
            if (boundedDependency.contains("." + token + ".")) {
                return true;
            }
        }
        return false;
    }

    /** 精确匹配包段边界，避免 applicationish、infrastructureLike 等名称落入规则。 */
    private static boolean inPackage(String name, String packageName) {
        return name.equals(packageName) || name.startsWith(packageName + ".");
    }

    /** SQL API 虽来自 JDK，也只允许现有 HexagonalArchitectureTest 指定的 Store 合同使用。 */
    private static boolean isSqlPackage(String dependency) {
        return inPackage(dependency, "java.sql") || inPackage(dependency, "javax.sql");
    }

    /**
     * 报告可定位到源节点的架构违规，依赖字段保留触发策略的原始限定名。
     *
     * @param source 来源
     * @param line 行号
     * @param dependency 依赖
     * @param reason 原因说明
     */
    private record Violation(String source,
                             long line,
                             String dependency,
                             String reason) {
        /** 以稳定路径、行号和依赖名组成无需解析器上下文的失败信息。 */
        private String message() {
            return source + ":" + line + " 禁止引用 " + dependency + "（" + reason + "）";
        }
    }

    /** 只解析 import 与最大限定选择节点，避免重复报告同一全限定常量的每一层 MemberSelect。 */
    private static final class DependencyScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final List<Violation> violations;

        /** 固定当前 source package 与位置映射，使不同 JavacTask 的节点不会交叉解释。 */
        private DependencyScanner(CompilationUnitTree unit, Trees trees, List<Violation> violations) {
            this.unit = unit;
            this.trees = trees;
            this.violations = violations;
        }

        /** 普通、静态和 wildcard import 都由 ImportTree 直接读取，不依赖 import 的符号解析。 */
        @Override
        public Void visitImport(ImportTree node, Void unused) {
            check(getCurrentPath(), node.getQualifiedIdentifier().toString());
            return null;
        }

        /** 检查最外层限定名，以覆盖 import 常量内联后只剩源码全限定引用的场景。 */
        @Override
        public Void visitMemberSelect(MemberSelectTree node, Void unused) {
            TreePath parent = getCurrentPath().getParentPath();
            boolean continuesQualifiedName = parent != null
                    && parent.getLeaf() instanceof MemberSelectTree parentSelect
                    && parentSelect.getExpression() == node;
            if (!continuesQualifiedName && isQualifiedTypeReference(node)) {
                check(getCurrentPath(), node.toString());
            }
            return super.visitMemberSelect(node, unused);
        }

        /** 只接受包限定类型本身或其直接成员，避免把流式调用与 lambda 接收式当成依赖。 */
        private static boolean isQualifiedTypeReference(MemberSelectTree node) {
            if (isQualifiedTypeName(node.toString())) {
                return true;
            }
            return node.getExpression() instanceof MemberSelectTree expression
                    && isQualifiedTypeName(expression.toString());
        }

        /** 以类型名必须位于全限定名末段来区分类型与任意方法、字段调用链。 */
        private static boolean isQualifiedTypeName(String name) {
            String[] segments = name.split("\\.");
            if (segments.length < 3) {
                return false;
            }
            int firstTypeSegment = -1;
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                if (!isJavaIdentifier(segment)) {
                    return false;
                }
                if (firstTypeSegment < 0 && Character.isUpperCase(segment.charAt(0))) {
                    firstTypeSegment = index;
                }
            }
            if (firstTypeSegment < 2) {
                return false;
            }
            for (int index = 0; index < firstTypeSegment; index++) {
                if (!Character.isLowerCase(segments[index].charAt(0))) {
                    return false;
                }
            }
            for (int index = firstTypeSegment; index < segments.length; index++) {
                if (!Character.isUpperCase(segments[index].charAt(0))) {
                    return false;
                }
            }
            return true;
        }

        /** 拒绝调用表达式文本，仅将 Java 标识符段组成的名称交给包/类型策略。 */
        private static boolean isJavaIdentifier(String segment) {
            if (segment.isEmpty() || !Character.isJavaIdentifierStart(segment.charAt(0))) {
                return false;
            }
            for (int index = 1; index < segment.length(); index++) {
                if (!Character.isJavaIdentifierPart(segment.charAt(index))) {
                    return false;
                }
            }
            return true;
        }

        /** 按源文件声明包选择策略，常量名仅作为诊断上下文而不参与包段匹配。 */
        private void check(TreePath path, String dependency) {
            String sourcePackage = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            String reason = forbiddenReason(sourcePackage, dependency);
            if (reason == null) {
                return;
            }
            long position = trees.getSourcePositions().getStartPosition(unit, path.getLeaf());
            long line = position < 0 ? 1 : unit.getLineMap().getLineNumber(position);
            violations.add(new Violation(unit.getSourceFile().getName(), line, dependency, reason));
        }
    }

    /** 可定位的内存 Java 源保持 JavaFileObject 契约，使夹具与真实源码共用同一 JavacTask。 */
    private static final class MemorySource extends SimpleJavaFileObject {
        private final String name;
        private final String source;

        /** 使用稳定的 memory URI 与路径名称，断言可验证 AST 返回的失败位置。 */
        private MemorySource(String name, String source) {
            super(URI.create("memory:///" + name), Kind.SOURCE);
            this.name = name;
            this.source = source;
        }

        /** 返回夹具的纯源码文本，不访问文件系统或为类型解析提供虚构 classpath。 */
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }

        /** 让测试诊断使用可读且稳定的相对路径。 */
        @Override
        public String getName() {
            return name;
        }
    }
}

