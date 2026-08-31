// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import io.github.kongweiguang.ja.foundation.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 冻结业务、端口、持久化与 MyBatis 的严格 JSON/行类型边界，阻止弱类型契约回流。 */
final class JsonBoundaryPolicyTest {
    private static final List<String> BOUNDARY_PACKAGES = List.of(
            ".domain.", ".port.", ".infrastructure.persistence.");

    /** 公开类型和 Mapper 接口不得通过签名暴露裸 Object、JsonNode 或字符串到裸对象的 Map。 */
    @Test
    void publicBusinessPortAndPersistenceSignaturesAreStronglyTyped() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Class<?> type : boundaryClasses()) {
            if (!Modifier.isPublic(type.getModifiers()) && !type.isInterface()) continue;
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (Modifier.isPublic(constructor.getModifiers())) {
                    verifyTypes(type.getName() + " constructor", constructor.getGenericParameterTypes(), failures);
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (isLanguageGeneratedObjectContract(method)) continue;
                if (Modifier.isPublic(method.getModifiers()) || type.isInterface()) {
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
        Path module = Path.of("").toAbsolutePath().normalize();
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
            try (Stream<Path> files = Files.walk(module.resolve("src"))) {
                if (files.anyMatch(path -> path.getFileName().toString().equals(deleted))) {
                    failures.add(deleted + " 已删除兼容契约不得恢复");
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> "MyBatis/兼容层门禁违规：\n" + String.join("\n", failures));
    }

    /** 严格 JSON 模型必须经显式 Adapter 转成 Jackson 树，避免 Native Image 依赖隐式 record 反射。 */
    @Test
    void strictJsonValuesDoNotUseJacksonBeanIntrospection() throws Exception {
        Path sourceRoot = Path.of("").toAbsolutePath().normalize().resolve("src/main/java");
        List<String> failures = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (content.matches("(?s).*valueToTree\\s*\\((?:(?!;).)*(?:arguments|inputSchema)\\s*\\(.*")) {
                    failures.add(file + " 严格 JSON 值禁止通过 valueToTree 隐式反射");
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> "Native JSON Adapter 门禁违规：\n" + String.join("\n", failures));
    }

    /** 从当前生产 classes 目录发现目标包，避免维护一份容易遗漏的类型白名单。 */
    private static List<Class<?>> boundaryClasses() throws Exception {
        URI location = JsonValue.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path classes = Path.of(location);
        List<Class<?>> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes.resolve("io/github/kongweiguang/ja"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class"))
                    .filter(path -> !path.getFileName().toString().equals("module-info.class")).toList()) {
                String relative = classes.relativize(file).toString();
                String name = relative.substring(0, relative.length() - ".class".length())
                        .replace(java.io.File.separatorChar, '.');
                if (BOUNDARY_PACKAGES.stream().noneMatch(name::contains)) continue;
                result.add(Class.forName(name, false, JsonBoundaryPolicyTest.class.getClassLoader()));
            }
        }
        return result;
    }

    /** 对一个签名中的所有参数执行相同递归检查，数组与嵌套泛型不能绕过门禁。 */
    private static void verifyTypes(String owner, Type[] types, List<String> failures) {
        for (Type type : types) verifyType(owner, type, failures);
    }

    /** 递归展开反射 Type，精确拒绝裸 Object、JsonNode 及 Object 值 Map。 */
    private static void verifyType(String owner, Type type, List<String> failures) {
        if (type instanceof Class<?> raw) {
            if (raw == Object.class || raw.getName().equals("com.fasterxml.jackson.databind.JsonNode")) {
                failures.add(owner + " 暴露 " + raw.getTypeName());
            }
            if (raw.isArray()) verifyType(owner, raw.getComponentType(), failures);
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            Type[] arguments = parameterized.getActualTypeArguments();
            if (raw == Map.class && arguments.length == 2 && arguments[1] == Object.class) {
                failures.add(owner + " 暴露 " + parameterized.getTypeName());
            }
            verifyType(owner, raw, failures);
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
