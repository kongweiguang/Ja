// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.DocTrees;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** 使用 JDK 25 AST 执行作者、中文 Javadoc、枚举语义与显式类型门禁。 */
final class JavaSourcePolicyTest {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final List<String> FORBIDDEN_DOC_TEMPLATES = List.of(
            "负责封装本作用域的稳定职责与领域约束",
            "对应的领域操作，并保持状态、资源与调用边界",
            "创建实例时固定依赖与不可变约束，避免运行期间发生职责漂移",
            "集中维护 Windows 句柄、ACL 与 FFM 资源约束",
            "场景下的领域约束与可观察结果");
    private static final String AUTHOR = "// @author kongweiguang";

    /**
     * 仅扫描 App Server 的生产与测试源码根；依赖可达性已由真实 Native Image
     * 和 sidecar smoke 验证，不再为已删除的隔离 spike 保留特殊扫描路径。
     */
    @Test
    void maintainedJavaSourcesUseChineseSemanticDocumentation() throws Exception {
        Path module = Path.of("").toAbsolutePath().normalize();
        List<Path> roots = List.of(
                module.resolve("src/main/java"),
                module.resolve("src/test/java"));
        List<Path> sources = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("target" + java.io.File.separator))
                        .forEach(sources::add);
            }
        }

        List<String> failures = new ArrayList<>();
        for (Path source : sources) {
            verifyAuthor(source, failures);
        }
        verifyProductionLogging(roots.get(0), failures);
        verifyJavadocs(sources, failures);
        assertTrue(failures.isEmpty(), () -> "Java 源码策略违规：\n" + String.join("\n", failures));
    }

    /** 作者标识固定在首行，使自动生成物排除与人工文件归属判断保持确定。 */
    private static void verifyAuthor(Path source, List<String> failures) throws IOException {
        try (java.io.BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            if (!AUTHOR.equals(reader.readLine())) failures.add(source + ":1 缺少首行作者标识");
        }
    }

    /**
     * App Server 的 stderr 不能被 JUL 默认 ConsoleHandler 污染，否则标题等异步降级会破坏
     * sidecar 的零 stderr 运行合同；生产日志统一进入已配置文件 appender 的 SLF4J 边界。
     */
    private static void verifyProductionLogging(Path productionRoot, List<String> failures) throws IOException {
        try (Stream<Path> files = Files.walk(productionRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    if (source.contains("java.util.logging")) {
                        failures.add(path + ": 禁止生产代码使用 java.util.logging，避免写入 sidecar stderr");
                    }
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /**
     * 只执行 parse 而不做类型分析；门禁因此不依赖业务 classpath，也能在编译错误时给出注释诊断。
     */
    private static void verifyJavadocs(List<Path> sources, List<String> failures) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("必须使用完整 JDK 25 运行源码门禁");
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("--release", "25", "-proc:none"), null, units);
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                new DocumentationScanner(unit, docs, failures).scan(new TreePath(unit), null);
            }
        }
    }

    /** AST 访问器只检查人工声明，不会把 record 自动访问器或编译器合成构造器计入。 */
    private static final class DocumentationScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final DocTrees docs;
        private final List<String> failures;

        /** 保存当前编译单元与 DocTrees，使每条失败都能定位到稳定源码行。 */
        private DocumentationScanner(CompilationUnitTree unit, DocTrees docs, List<String> failures) {
            this.unit = unit;
            this.docs = docs;
            this.failures = failures;
        }

        /** 所有具名类型都必须用中文解释职责；匿名实现不制造没有可维护名称的类型注释。 */
        @Override
        public Void visitClass(ClassTree node, Void unused) {
            if (!node.getSimpleName().isEmpty()) requireChineseDoc(getCurrentPath(), "类型 " + node.getSimpleName());
            return super.visitClass(node, unused);
        }

        /** 每个显式函数与构造器都必须解释设计约束，不能用英文或空注释掩盖行为边界。 */
        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            requireChineseDoc(getCurrentPath(), "函数 " + node.getName());
            return super.visitMethod(node, unused);
        }

        /**
         * 所有变量声明都拒绝 var，包括局部变量、资源和 lambda 参数；枚举常量仍按初始化器形状
         * 单独检查中文语义，避免把普通缓存字段误判为常量。
         */
        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (node.getType() != null && "var".contentEquals(node.getType().toString())) {
                long position = docs.getSourcePositions().getStartPosition(unit, node);
                long line = position < 0 ? 1 : unit.getLineMap().getLineNumber(position);
                failures.add(unit.getSourceFile().getName() + ":" + line + " 禁止使用 var，必须声明精确类型");
            }
            TreePath parent = getCurrentPath().getParentPath();
            if (parent != null && parent.getLeaf().getKind() == Tree.Kind.ENUM
                    && node.getInitializer() != null
                    && node.getInitializer().getKind() == Tree.Kind.NEW_CLASS) {
                requireChineseDoc(getCurrentPath(), "枚举常量 " + node.getName());
            }
            return super.visitVariable(node, unused);
        }

        /**
         * Javadoc 必须真实包含汉字；读取原始文本而不调用 DocCommentTree.toString()，因为 JDK 25
         * 会把非 ASCII 字符转义为 Unicode 序列，导致中文规则产生系统性误报。
         */
        private void requireChineseDoc(TreePath path, String subject) {
            String comment = docs.getDocComment(path);
            long position = docs.getSourcePositions().getStartPosition(unit, path.getLeaf());
            long line = position < 0 ? 1 : unit.getLineMap().getLineNumber(position);
            if (comment == null || !HAN.matcher(comment).find()) {
                failures.add(unit.getSourceFile().getName() + ":" + line
                        + " " + subject + " 缺少中文 Javadoc");
                return;
            }
            if (FORBIDDEN_DOC_TEMPLATES.stream().anyMatch(comment::contains)) {
                failures.add(unit.getSourceFile().getName() + ":" + line
                        + " " + subject + " 使用了无业务语义的模板 Javadoc");
            }
        }
    }
}
