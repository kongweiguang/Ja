// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** 集中解析 reactor 根、Java 根与 App Server 根，避免质量门禁依赖 Maven 的启动目录。 */
public final class TestModulePaths {
    /**
     * 从支持的三种 Maven 启动目录找到 App Server；候选范围固定，防止测试误扫到其它 checkout。
     */
    public static Path appServerRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : List.of(current, current.resolve("ja-agent/ja-app-server"),
                current.resolve("ja-app-server"))) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))
                    && Files.isDirectory(candidate.resolve("src/test/java"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot resolve ja-agent/ja-app-server from " + current);
    }

    /**
     * 读取 Maven 声明的完整 Reactor，并纳入生产、测试、native 与独立测试支撑源码，避免新增模块绕过规则。
     */
    public static List<Path> javaSourceRoots() {
        Path reactorRoot = appServerRoot().getParent().toAbsolutePath().normalize();
        List<Path> modules = new ArrayList<>();
        collectReactorModules(reactorRoot, reactorRoot, modules, new HashSet<>());
        List<Path> roots = new ArrayList<>();
        for (Path module : modules) {
            for (String sourceSet : List.of("main", "native", "test", "test/support")) {
                Path sourceRoot = module.resolve("src").resolve(sourceSet).resolve("java").normalize();
                if (Files.isDirectory(sourceRoot)) {
                    roots.add(sourceRoot);
                }
            }
        }
        if (roots.isEmpty()) {
            throw new IllegalStateException("ja-agent Java source roots are missing");
        }
        return List.copyOf(roots);
    }

    /** main 与 native 源码都属于生产实现，必须遵守生产日志和 JSON 边界策略。 */
    public static boolean isProductionSourceRoot(Path sourceRoot) {
        String sourceSet = sourceRoot.getParent().getFileName().toString();
        return "main".equals(sourceSet) || "native".equals(sourceSet);
    }

    /**
     * 递归展开当前 POM 的直属模块；路径越界、重复模块或缺失 POM 都使质量门禁失败关闭。
     */
    private static void collectReactorModules(Path moduleRoot, Path reactorRoot,
                                              List<Path> modules, Set<Path> visited) {
        Path normalizedModule = moduleRoot.toAbsolutePath().normalize();
        Path pom = normalizedModule.resolve("pom.xml");
        if (!normalizedModule.startsWith(reactorRoot) || !visited.add(normalizedModule)) {
            throw new IllegalStateException("invalid or repeated ja-agent module: " + normalizedModule);
        }
        if (!Files.isRegularFile(pom)) {
            throw new IllegalStateException("ja-agent module has no pom.xml: " + pom);
        }
        modules.add(normalizedModule);
        Element moduleGroup = directChild(parsePom(pom).getDocumentElement(), "modules");
        if (moduleGroup == null) {
            return;
        }
        for (Node child = moduleGroup.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element module && "module".equals(module.getLocalName())) {
                String relativePath = module.getTextContent().trim();
                if (relativePath.isEmpty()) {
                    throw new IllegalStateException("ja-agent POM contains an empty module path: " + pom);
                }
                collectReactorModules(normalizedModule.resolve(relativePath), reactorRoot, modules, visited);
            }
        }
    }

    /**
     * 禁用 DTD、外部实体与外部 Schema，防止质量测试读取 Maven POM 时访问本机或网络资源。
     */
    private static org.w3c.dom.Document parsePom(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(pom.toFile());
        } catch (IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException failure) {
            throw new IllegalStateException("cannot read ja-agent module POM: " + pom, failure);
        }
    }

    /** 只读取 POM 根节点的直属 modules，忽略 profile、插件等其它 XML 中同名元素。 */
    private static Element directChild(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    /** 防止静态路径工具被误当成有生命周期状态的对象。 */
    private TestModulePaths() {
    }
}
