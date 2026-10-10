// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.aot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.domain.context.CheckpointUsage;
import io.github.kongweiguang.ja.agent.domain.context.SummaryDocument;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PlanRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkRecords;
import io.github.kongweiguang.ja.bootstrap.App;
import io.github.kongweiguang.ja.bootstrap.SolonPersistenceComposition;
import io.github.kongweiguang.ja.bootstrap.SolonRuntimeComposition;
import io.github.kongweiguang.ja.quality.TestModulePaths;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.noear.solon.annotation.Import;

/** 验证 Native Image 元数据中的 Ja 类型、MyBatis 代理、record 与精确资源都可达。 */
final class NativeReachabilityMetadataTest {
    private static final String PROXY_METADATA_PATH =
            "src/native/resources/META-INF/native-image/io/github/kongweiguang/ja/"
                    + "reachability-metadata.json";
    private static final String REFLECTION_METADATA_PATH =
            "src/main/resources/META-INF/native-image/io.github.kongweiguang/ja-app-server/"
                    + "reflect-config.json";
    private static final String SOLON_COMPOSITION_METADATA_PATH =
            "src/native/resources/META-INF/native-image/io/github/kongweiguang/ja/"
                    + "solon-composition-reflect-config.json";
    private static final String RESOURCE_METADATA_PATH =
            "src/main/resources/META-INF/native-image/io.github.kongweiguang/ja-app-server/"
                    + "resource-config.json";
    private static final Pattern TYPE_HANDLER_PATTERN = Pattern.compile(
            "typeHandler\\s*=\\s*[\"']?([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)");

    /** Native 闭世界不保证扫描 bootstrap 包；入口必须显式导入运行时与持久化组合根。 */
    @Test
    void appExplicitlyImportsNativeCompositionRoots() {
        Import imports = App.class.getAnnotation(Import.class);
        assertNotNull(imports, "Native application entry must declare Solon imports");
        assertEquals(Set.of(SolonRuntimeComposition.class, SolonPersistenceComposition.class),
                Set.of(imports.classes()), "both runtime and persistence compositions must be imported");
    }
    /** 静态清单不维护第二份 Mapper 集合，打包必须保留 Solon AOT 实际生成的代理配置。 */
    @Test
    void mapperProxiesComeFromSolonAot() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(PROXY_METADATA_PATH)));
        assertTrue(root.path("reflection").isArray());
        assertEquals(0, root.path("reflection").size(),
                "Ja static metadata must not duplicate mapper proxies generated from the Solon registry");

        String pom = Files.readString(TestModulePaths.appServerRoot().resolve("pom.xml"));
        assertTrue(pom.contains("<goal>process-aot</goal>"),
                "the app-server build must generate dynamic mapper proxies from its actual AOT bean graph");
        assertTrue(!pom.contains("bootstrap/proxy-config.json"),
                "the package phase must preserve Solon's generated proxy registrations for Native Image");
    }

    /**
     * Windows 进程观察器依赖 Kernel32 的四个精确 downcall；把参数布局与捕获 last-error 的选项固定在同一门禁，
     * 避免 JVM 可运行而 Native 首次执行管理员进程查询时才因缺少 foreign registration 失败。
     * @author kongweiguang
     */
    @Test
    void windowsProcessObservationDowncallsHaveNativeRegistrations() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(PROXY_METADATA_PATH)));
        Set<String> registrations = new HashSet<>();
        for (JsonNode downcall : root.path("foreign").path("downcalls")) {
            List<String> parameters = new ArrayList<>();
            for (JsonNode parameter : downcall.path("parameterTypes")) {
                parameters.add(parameter.asText());
            }
            registrations.add(downcall.path("returnType").asText() + "("
                    + String.join(",", parameters) + ")/captureCallState="
                    + downcall.path("options").path("captureCallState").asBoolean());
        }

        assertTrue(registrations.containsAll(Set.of(
                        "void*(jint,jint,jint)/captureCallState=true",
                        "jint(void*,void*,void*,void*,void*)/captureCallState=true",
                        "jint(void*,jint)/captureCallState=true",
                        "jint(void*)/captureCallState=true")),
                "Windows process lifecycle downcalls must all be registered with captured call state");
    }

    /**
     * Core codec record 需要构造器与访问方法，MyBatis constructor resultMap 则需要 Plan 行构造器；
     * fork 回执与冻结 seed 的行同样动态构造，不能仅在 JVM 自定义 Factory 中验证其可用性。
     * 分别闭合两类元数据，避免 Native 只在恢复摘要、用量或命中对应 SQL 时才失败。
     */
    @Test
    void registersEveryPersistenceRecordForReflection() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(REFLECTION_METADATA_PATH)));
        Set<String> records = new HashSet<>();
        Set<String> constructors = new HashSet<>();
        for (JsonNode entry : root) {
            String name = entry.path("name").asText();
            if (entry.path("allDeclaredConstructors").asBoolean()) {
                constructors.add(name);
            }
            if (entry.path("allDeclaredConstructors").asBoolean()
                    && entry.path("allDeclaredMethods").asBoolean()) {
                        records.add(name);
                    }
        }

        for (Class<?> owner : new Class<?>[]{PersistenceRecords.class, AttachmentRecords.class,
                ThreadForkRecords.class, SummaryDocument.class}) {
            if (owner.isRecord()) {
                assertTrue(records.contains(owner.getName()),
                        () -> "missing Native record reflection metadata for " + owner.getName());
            }
            for (Class<?> candidate : owner.getDeclaredClasses()) {
                if (!candidate.isRecord()) {
                    continue;
                }
                assertTrue(records.contains(candidate.getName()),
                        () -> "missing Native record reflection metadata for " + candidate.getName());
            }
        }
        for (Class<?> candidate : new Class<?>[]{CheckpointUsage.class}) {
            assertTrue(candidate.isRecord(), () -> candidate.getName() + " must stay a record");
            assertTrue(records.contains(candidate.getName()),
                    () -> "missing Native record reflection metadata for " + candidate.getName());
        }
        for (Class<?> candidate : PlanRecords.class.getDeclaredClasses()) {
            if (!candidate.isRecord()) {
                continue;
            }
            assertTrue(constructors.contains(candidate.getName()),
                    () -> "missing Native constructor metadata for MyBatis row " + candidate.getName());
        }
    }

    /**
     * 审查报告由 artifact 动态回读；Native 必须登记其完整 record 图，不能只证明 Turn 和报告写入成功。
     * 限定四种持久报告类型，不扩大整个 Review 用例或其它请求的反射范围。
     */
    @Test
    void registersFrozenReviewReportForJacksonReflection() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(REFLECTION_METADATA_PATH)));
        Set<String> records = new HashSet<>();
        for (JsonNode entry : root) {
            if (entry.path("allDeclaredConstructors").asBoolean()
                && entry.path("allDeclaredMethods").asBoolean()) {
                records.add(entry.path("name").asText());
            }
        }
        for (Class<?> candidate : new Class<?>[]{ReviewUseCase.Report.class, ReviewUseCase.Coverage.class,
                ReviewUseCase.ReviewedFile.class, ReviewUseCase.Finding.class}) {
            assertTrue(candidate.isRecord(), () -> candidate.getName() + " must stay a record");
            assertTrue(records.contains(candidate.getName()),
                    () -> "missing Native report reflection metadata for " + candidate.getName());
        }
    }

    /** MyBatis 的动态反射元数据应来自 Solon AOT，Ja 静态配置只登记 Ja 自有闭世界入口。 */
    @Test
    void mybatisReflectionMetadataComesFromSolonAot() throws Exception {
        Set<String> requiredConstructors = Set.of(
                "org.apache.ibatis.javassist.util.proxy.ProxyFactory",
                "org.apache.ibatis.scripting.xmltags.XMLLanguageDriver");
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(SOLON_COMPOSITION_METADATA_PATH)));
        Set<String> staticEntries = new HashSet<>();
        for (JsonNode entry : root) {
            String name = entry.path("name").asText();
            if (requiredConstructors.contains(name)) {
                staticEntries.add(name);
            }
        }
        assertTrue(staticEntries.isEmpty(),
                "MyBatis reflection entries must not duplicate the Solon AOT generated source");

        String pom = Files.readString(TestModulePaths.appServerRoot().resolve("pom.xml"));
        assertTrue(pom.contains("<artifactId>solon-maven-plugin</artifactId>"),
                "the app-server build must use Solon's AOT metadata generator");
        assertTrue(pom.contains("<phase>prepare-package</phase>")
                        && pom.contains("<goal>process-aot</goal>"),
                "Solon AOT must run after app classes compile and before Native Image packaging");

        URL generated = NativeReachabilityMetadataTest.class.getClassLoader().getResource(
                "META-INF/native-image/io/github/kongweiguang/ja/bootstrap/reflect-config.json");
        if (generated != null) {
            JsonNode generatedRoot = new ObjectMapper().readTree(Files.readString(Path.of(generated.toURI())));
            Set<String> generatedConstructors = new HashSet<>();
            for (JsonNode entry : generatedRoot) {
                String name = entry.path("name").asText();
                if (requiredConstructors.contains(name) && entry.path("allDeclaredConstructors").asBoolean()) {
                    generatedConstructors.add(name);
                }
            }
            assertEquals(requiredConstructors, generatedConstructors,
                    "Solon AOT output must retain MyBatis's startup-time reflective constructors");
        }
        for (String name : requiredConstructors) {
            assertTrue(Class.forName(name, false,
                    NativeReachabilityMetadataTest.class.getClassLoader()).getDeclaredConstructors().length > 0,
                    () -> "MyBatis's AOT-probed type must exist in the resolved runtime: " + name);
        }
    }

    /**
     * 显式反射配置中的 Ja 类型必须都能从应用运行时类路径装载，防止移动 Java 包后留下失效名字。
     */
    @Test
    void everyJaNativeMetadataTypeIsReachable() throws IOException, ClassNotFoundException {
        ObjectMapper mapper = new ObjectMapper();
        Set<String> names = new HashSet<>();
        List<Path> metadataFiles = nativeMetadataFiles();
        assertTrue(!metadataFiles.isEmpty(), "Native metadata JSON files must exist");
        for (Path metadataFile : metadataFiles) {
            collectJaTypes(mapper.readTree(Files.readString(metadataFile)), names);
        }
        assertTrue(!names.isEmpty(), "Native metadata must include Ja runtime types");
        for (String name : names) {
            Class.forName(name, false, NativeReachabilityMetadataTest.class.getClassLoader());
        }
    }

    /** XML 按类名动态创建 TypeHandler，必须同时保留类和构造器的 Native 反射元数据。 */
    @Test
    void everyMapperXmlTypeHandlerIsNativeConstructible()
            throws IOException, ClassNotFoundException, NoSuchMethodException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(REFLECTION_METADATA_PATH)));
        Map<String, JsonNode> constructors = new HashMap<>();
        for (JsonNode entry : root) {
            constructors.put(entry.path("name").asText(), entry);
        }

        Set<String> handlers = mapperXmlTypeHandlers();
        assertTrue(!handlers.isEmpty(), "Mapper XML must reference at least one explicit type handler");
        for (String name : handlers) {
            JsonNode entry = constructors.get(name);
            assertNotNull(entry, "Native metadata must register Mapper XML type handler " + name);
            assertTrue(entry.path("allDeclaredConstructors").asBoolean(),
                    "Native metadata must retain constructors for Mapper XML type handler " + name);
            Class<?> type = Class.forName(name, false, NativeReachabilityMetadataTest.class.getClassLoader());
            type.getDeclaredConstructor();
        }
    }

    /**
     * 按参数签名定位 Solon 工厂方法，并固定 YAML loader 的反射入口；Solon 通过类名加载扩展，
     * Native 闭世界分析不会沿普通 Java 调用发现它，缺少构造器元数据会让 app.yml 启动失败。
     */
    @Test
    void solonCompositionMetadataMatchesDeclaredMethods() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(SOLON_COMPOSITION_METADATA_PATH)));
        String propsLoaderName = "org.noear.solon.extend.impl.PropsLoaderExt";
        JsonNode propsLoader = null;
        Set<String> reflectivelyConstructibleCompositions = new HashSet<>();
        for (JsonNode type : root) {
            String name = type.path("name").asText();
            if (propsLoaderName.equals(name)) {
                propsLoader = type;
            }
            if (type.path("allDeclaredConstructors").asBoolean()) {
                reflectivelyConstructibleCompositions.add(name);
            }
            Class<?> owner = Class.forName(name, false,
                    NativeReachabilityMetadataTest.class.getClassLoader());
            for (JsonNode method : type.path("methods")) {
                List<Class<?>> parameterTypes = new ArrayList<>();
                for (JsonNode parameter : method.path("parameterTypes")) {
                    parameterTypes.add(Class.forName(parameter.asText(), false,
                            NativeReachabilityMetadataTest.class.getClassLoader()));
                }
                owner.getDeclaredMethod(method.path("name").asText(), parameterTypes.toArray(Class<?>[]::new));
            }
        }
        assertNotNull(propsLoader, "Native metadata must register Solon's reflective YAML loader");
        assertTrue(propsLoader.path("allDeclaredConstructors").asBoolean(),
                "Solon must be able to instantiate its YAML loader in the Native image");
        assertTrue(Class.forName(propsLoaderName, false,
                NativeReachabilityMetadataTest.class.getClassLoader()).getDeclaredConstructors().length > 0,
                "the configured YAML loader must exist in the resolved Solon runtime");
        assertTrue(reflectivelyConstructibleCompositions.contains(SolonRuntimeComposition.class.getName()),
                "Solon must be able to reflectively construct its runtime composition in Native Image");
        assertTrue(reflectivelyConstructibleCompositions.contains(SolonPersistenceComposition.class.getName()),
                "Solon must be able to reflectively construct its persistence composition in Native Image");
    }

    /** 确认 Native metadata 保留 Mapper、共享 Core schema、构建信息及 JSON Schema 资源。 */
    @Test
    void everyExactNativeResourcePatternIsReachable() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(modulePath(RESOURCE_METADATA_PATH)));
        List<String> resources = new ArrayList<>();
        JsonNode includes = root.path("resources").path("includes");
        collectExactMapperResources(includes, resources);
        String coreSchema = "io/github/kongweiguang/ja/agent/store/local/infrastructure/schema.sql";
        String coreSchemaPattern = "io/github/kongweiguang/ja/agent/store/local/infrastructure/schema\\.sql";
        boolean coreSchemaConfigured = false;
        for (JsonNode include : includes) {
            if (coreSchemaPattern.equals(include.path("pattern").asText())) {
                coreSchemaConfigured = true;
                resources.add(coreSchema);
                break;
            }
        }
        assertTrue(coreSchemaConfigured, "Native metadata must include the schema shared with Core Store");
        Set<String> expectedSchemaResources = Set.of(
                "draft/2020-12/schema",
                "draft/2020-12/meta/core",
                "draft/2020-12/meta/applicator",
                "draft/2020-12/meta/unevaluated",
                "draft/2020-12/meta/validation",
                "draft/2020-12/meta/meta-data",
                "draft/2020-12/meta/format-annotation",
                "draft/2020-12/meta/content");
        Set<String> configuredSchemaResources = new HashSet<>();
        for (JsonNode include : includes) {
            String pattern = include.path("pattern").asText();
            if (expectedSchemaResources.contains(pattern)) {
                configuredSchemaResources.add(pattern);
            }
        }
        assertEquals(expectedSchemaResources, configuredSchemaResources,
                "Native metadata must include the validator's complete offline 2020-12 meta-schema set");
        resources.addAll(expectedSchemaResources);
        assertTrue(!resources.isEmpty(), "Native resource metadata must retain explicit runtime resources");
        for (String resource : resources) {
            assertNotNull(NativeReachabilityMetadataTest.class.getClassLoader().getResource(resource),
                    () -> "Native resource metadata points to missing application resource " + resource);
        }
        assertNotNull(NativeReachabilityMetadataTest.class.getClassLoader().getResource("ja-build.properties"),
                "version filtering must provide the build resource named by Native metadata");
    }

    /**
     * 递归收集各 Native JSON 中的 Ja 类型名；metadata 版本间字段形状不同，因此以文本叶子为稳定边界。
     */
    private static void collectJaTypes(JsonNode node, Set<String> names) {
        if (node.isTextual()) {
            String value = node.textValue();
            if (value.startsWith("io.github.kongweiguang.ja.")) {
                names.add(value);
            }
            return;
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectJaTypes(child, names));
        }
    }

    /** 只解析 Ja Mapper 的字面资源路径，避免把通配规则误当成可逐文件验证的资源。 */
    private static void collectExactMapperResources(JsonNode includes, List<String> resources) {
        for (JsonNode include : includes) {
            String pattern = include.path("pattern").asText();
            if (!pattern.startsWith("io/github/kongweiguang/ja/") || !pattern.endsWith("\\.xml")) {
                continue;
            }
            resources.add(pattern.substring(0, pattern.length() - "\\.xml".length()) + ".xml");
        }
    }

    /** 扫描 MyBatis 特殊占位符和 XML resultMap 属性，覆盖两种类名配置入口。 */
    private static Set<String> mapperXmlTypeHandlers() throws IOException {
        Path mapperRoot = TestModulePaths.appServerRoot().resolve(
                "src/main/resources/io/github/kongweiguang/ja/infrastructure/persistence/mapper");
        Set<String> names = new HashSet<>();
        try (Stream<Path> paths = Files.walk(mapperRoot)) {
            for (Path mapper : paths.filter(path -> path.toString().endsWith(".xml")).toList()) {
                Matcher matcher = TYPE_HANDLER_PATTERN.matcher(Files.readString(mapper));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
        }
        return Set.copyOf(names);
    }

    /**
     * 扫描应用自身两处受版本控制的 Native 配置目录，让新增 JSON 元数据自动进入可达性校验。
     */
    private static List<Path> nativeMetadataFiles() throws IOException {
        Path module = TestModulePaths.appServerRoot();
        List<Path> roots = List.of(
                module.resolve("src/main/resources/META-INF/native-image"),
                module.resolve("src/native/resources/META-INF/native-image"));
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                files.addAll(paths.filter(path -> path.toString().endsWith(".json")).toList());
            }
        }
        return List.copyOf(files);
    }

    /**
     * Maven 可从仓库根、Java 根或 app-server 根启动；固定解析新目录，不搜索旧路径。
     */
    private static Path modulePath(String relativePath) {
        Path source = TestModulePaths.appServerRoot().resolve(relativePath);
        if (!Files.isRegularFile(source)) {
            throw new IllegalStateException("missing metadata source " + source);
        }
        return source;
    }
}
