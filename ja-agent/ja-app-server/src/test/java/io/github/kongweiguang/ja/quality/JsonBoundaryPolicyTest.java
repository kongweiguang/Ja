// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结业务、端口、持久化与 MyBatis 的严格 JSON/行类型边界，阻止弱类型契约回流。 */
final class JsonBoundaryPolicyTest {
    private static final List<String> BOUNDARY_PACKAGES = List.of(
            ".domain.", ".port.", ".agent.api.", ".agent.extension.",
            ".infrastructure.persistence.");

    /** 直接验证拒绝规则，避免生产源码暂时没有弱类型签名时，失效的门禁仍然通过。 */
    @Test
    void rejectsRawAndNestedWeakSignatures() {
        List<String> failures = new ArrayList<>();
        for (Type weak : List.<Type>of(Object.class, JsonNode.class, List.class, Map.class, Set.class,
                new TypeReference<List<Map<String, Object>>>() {}.getType())) {
            int previousCount = failures.size();
            verifyType("fixture", weak, failures);
            assertTrue(failures.size() > previousCount, () -> "门禁漏过弱类型：" + weak.getTypeName());
        }
    }

    /** 泛型容器、嵌套 JSON 值及泛型数组保留具体参数时应通过，不能将容器本身误判为原始类型。 */
    @Test
    void acceptsConcreteNestedSignatures() {
        List<String> failures = new ArrayList<>();
        verifyTypes("fixture", new Type[]{String.class, JsonObject.class,
                new TypeReference<Map<String, List<JsonObject>>>() {}.getType(),
                new TypeReference<List<JsonObject>[]>() {}.getType()}, failures);
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** 公开业务签名必须保留具体数据类型；编译器桥接及私有接口辅助方法不属于调用方契约。 */
    @Test
    void publicBusinessPortAndPersistenceSignaturesAreStronglyTyped() throws Exception {
        List<String> failures = new ArrayList<>();
        List<Class<?>> classes = boundaryClasses();
        assertTrue(classes.stream().anyMatch(type -> type.getName().equals(
                "io.github.kongweiguang.ja.agent.domain.json.JsonObject")),
                "强类型门禁必须检查已打包或目录形式的 Core 生产类型");
        for (Class<?> type : classes) {
            if (!Modifier.isPublic(type.getModifiers()) && !type.isInterface()) {
                continue;
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    verifyTypes(type.getName() + " constructor", constructor.getGenericParameterTypes(), failures);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || isLanguageGeneratedObjectContract(method)) {
                    continue;
                }
                if (Modifier.isPublic(method.getModifiers())) {
                    verifyType(type.getName() + "#" + method.getName() + " return",
                            method.getGenericReturnType(), failures);
                    verifyTypes(type.getName() + "#" + method.getName() + " parameter",
                            method.getGenericParameterTypes(), failures);
                }
            }
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    verifyType(type.getName() + "#" + field.getName(), field.getGenericType(), failures);
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> "弱类型 JSON 边界违规：\n" + String.join("\n", failures));
    }

    /** Mapper XML 必须使用 constructor resultMap，且已删除的 Map Codec/行辅助类不能恢复。 */
    @Test
    void mybatisAndDeletedCompatibilityContractsCannotReturn() throws Exception {
        Path module = TestModulePaths.appServerRoot();
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(module.resolve("src/main/resources"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".xml")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.matches("(?s).*resultType\\s*=\\s*[\"']map[\"'].*")) {
                    failures.add(file + " 禁止 resultType=map");
                }
            }
        }
        for (String deleted : List.of("StructuredArgumentsCodec.java", "PersistenceRows.java")) {
            boolean found = false;
            for (Path sourceRoot : TestModulePaths.javaSourceRoots()) {
                try (Stream<Path> files = Files.walk(sourceRoot)) {
                    found |= files.anyMatch(path -> path.getFileName().toString().equals(deleted));
                }
            }
            if (found) {
                failures.add(deleted + " 已删除兼容契约不得恢复");
            }
        }
        assertTrue(failures.isEmpty(), () -> "MyBatis/兼容层门禁违规：\n" + String.join("\n", failures));
    }

    /** 所有模块的严格 JSON 模型都必须经显式 Adapter 转换，避免新增扩展引入隐式 record 反射。 */
    @Test
    void strictJsonValuesDoNotUseJacksonBeanIntrospection() throws Exception {
        List<Path> sourceRoots = TestModulePaths.javaSourceRoots().stream()
                .filter(TestModulePaths::isProductionSourceRoot).toList();
        List<String> failures = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (content.matches("(?s).*valueToTree\\s*\\((?:(?!;).)*(?:arguments|inputSchema)\\s*\\(.*")) {
                        failures.add(file + " 严格 JSON 值禁止通过 valueToTree 隐式反射");
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> "Native JSON Adapter 门禁违规：\n" + String.join("\n", failures));
    }

    /** 同时枚举目录与 Reactor 已打包的生产 JAR，避免 verify 顺序改变后漏检 Core 类型。 */
    private static List<Class<?>> boundaryClasses() throws Exception {
        List<Class<?>> result = new ArrayList<>();
        Set<String> classNames = new LinkedHashSet<>();
        for (Path classes : classRoots()) {
            Path packageRoot = classes.resolve("io/github/kongweiguang/ja");
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(packageRoot)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                        .filter(path -> !path.getFileName().toString().equals("module-info.class")).toList()) {
                    String relative = classes.relativize(file).toString();
                    String name = relative.substring(0, relative.length() - ".class".length())
                            .replace(java.io.File.separatorChar, '.');
                    if (BOUNDARY_PACKAGES.stream().anyMatch(name::contains)) {
                        classNames.add(name);
                    }
                }
            }
        }
        Enumeration<URL> packages = JsonBoundaryPolicyTest.class.getClassLoader()
                .getResources("io/github/kongweiguang/ja");
        while (packages.hasMoreElements()) {
            URL location = packages.nextElement();
            if (!"jar".equals(location.getProtocol())) {
                continue;
            }
            JarURLConnection connection = (JarURLConnection) location.openConnection();
            if (connection.getJarFileURL().getPath().endsWith("-tests.jar")) {
                continue;
            }
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                for (JarEntry entry : jar.stream().filter(item -> !item.isDirectory())
                        .filter(item -> item.getName().startsWith("io/github/kongweiguang/ja/"))
                        .filter(item -> item.getName().endsWith(".class")).toList()) {
                    String name = entry.getName().substring(0, entry.getName().length() - ".class".length())
                            .replace('/', '.');
                    if (BOUNDARY_PACKAGES.stream().anyMatch(name::contains)) {
                        classNames.add(name);
                    }
                }
            }
        }
        for (String name : classNames) {
            result.add(Class.forName(name, false, JsonBoundaryPolicyTest.class.getClassLoader()));
        }
        return result;
    }

    /**
     * 通过运行时类路径枚举 reactor 中所有 Ja 模块的 class root，不能只扫 JsonValue 所在的 core。
     */
    private static List<Path> classRoots() throws Exception {
        String packagePath = "io/github/kongweiguang/ja";
        Enumeration<URL> packageLocations = JsonBoundaryPolicyTest.class.getClassLoader()
                .getResources(packagePath);
        Set<Path> roots = new LinkedHashSet<>();
        while (packageLocations.hasMoreElements()) {
            URL location = packageLocations.nextElement();
            if (!"file".equals(location.getProtocol())) {
                continue;
            }
            Path packageDirectory = Path.of(location.toURI());
            Path root = packageDirectory;
            for (int segment = 0; segment < 4; segment++) {
                root = root.getParent();
            }
            if (root != null && !root.toString().contains("test-classes") && Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalStateException("Ja production classes are missing from test classpath");
        }
        return List.copyOf(roots);
    }

    /** 对一个签名中的所有参数执行相同递归检查，数组与嵌套泛型不能绕过门禁。 */
    private static void verifyTypes(String owner, Type[] types, List<String> failures) {
        for (Type type : types) {
            verifyType(owner, type, failures);
        }
    }

    /** 递归展开反射 Type，拒绝裸对象、动态节点和缺少具体参数的原始泛型，包括嵌套容器。 */
    private static void verifyType(String owner, Type type, List<String> failures) {
        if (type instanceof Class<?> raw) {
            if (raw == Object.class || raw.getName().equals("com.fasterxml.jackson.databind.JsonNode")) {
                failures.add(owner + " 暴露 " + raw.getTypeName());
            }
            if (raw.getTypeParameters().length > 0) {
                failures.add(owner + " 泛型缺少具体类型参数：" + raw.getTypeName());
            }
            if (raw.isArray()) {
                verifyType(owner, raw.getComponentType(), failures);
            }
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Map.class && arguments.length == 2 && arguments[1] == Object.class) {
                failures.add(owner + " 暴露 " + parameterized.getTypeName());
            }
            // ParameterizedType 已声明容器参数，只递归数据参数，避免将合法容器重新当作原始类型。
            verifyTypes(owner, arguments, failures);
            return;
        }
        if (type instanceof GenericArrayType array) {
            verifyType(owner, array.getGenericComponentType(), failures);
            return;
        }
        if (type instanceof WildcardType wildcard) {
            verifyTypes(owner, wildcard.getUpperBounds(), failures);
            verifyTypes(owner, wildcard.getLowerBounds(), failures);
            return;
        }
        // 泛型变量的隐式 Object 上界不是公开的弱类型数据契约，调用方仍由具体类型参数约束。
    }

    /** record 自动生成的 equals(Object) 是 Java 语言对象契约，不代表业务值使用裸 Object。 */
    private static boolean isLanguageGeneratedObjectContract(Method method) {
        return method.getName().equals("equals")
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == Object.class
                && method.getReturnType() == boolean.class;
    }
}
