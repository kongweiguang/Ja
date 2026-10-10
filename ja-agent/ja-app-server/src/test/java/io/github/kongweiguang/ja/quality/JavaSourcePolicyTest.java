// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.quality;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/** 使用 JDK 25 AST 执行作者、中文 Javadoc、record 排版、枚举语义与显式类型门禁。 */
final class JavaSourcePolicyTest {
    private static final Pattern HAN = Pattern.compile("[\\p{IsHan}]");
    private static final Pattern VAR_LAMBDA_DECLARATION = Pattern.compile(
            "(?s)\\bvar\\b(?:\\s|/\\*.*?\\*/|//[^\\r\\n]*[\\r\\n])+\\S+\\s*$");
    private static final List<String> FORBIDDEN_DOC_TEMPLATES = List.of(
            "负责封装本作用域的稳定职责与领域约束",
            "对应的领域操作，并保持状态、资源与调用边界",
            "创建实例时固定依赖与不可变约束，避免运行期间发生职责漂移",
            "集中维护 Windows 句柄、ACL 与 FFM 资源约束",
            "场景下的领域约束与可观察结果");
    private static final String AUTHOR = "// @author kongweiguang";

    /** 扫描 ja-agent reactor 所有已有模块的源码，避免新扩展绕过作者、注释与类型约束。 */
    @Test
    void maintainedJavaSourcesUseChineseSemanticDocumentation() throws Exception {
        List<Path> roots = TestModulePaths.javaSourceRoots();
        List<Path> sources = maintainedSources();

        List<String> failures = new ArrayList<>();
        for (Path source : sources) {
            verifyAuthor(source, failures);
        }
        List<Path> productionRoots = roots.stream().filter(TestModulePaths::isProductionSourceRoot).toList();
        verifyProductionLogging(productionRoots, failures);
        verifyJavadocs(sources, failures, false);
        assertTrue(failures.isEmpty(), () -> "Java 源码策略违规：\n" + String.join("\n", failures));
    }

    /** 单独报告所有模块的 record 规则，使通用源码注释问题不会掩盖字段与排版的验证结果。 */
    @Test
    void maintainedRecordsUseDocumentedAlignedComponents() throws IOException {
        List<String> failures = new ArrayList<>();
        verifyJavadocs(maintainedSources(), failures, true);
        assertTrue(failures.isEmpty(), () -> "Record 源码策略违规：\n" + String.join("\n", failures));
    }

    /** 复用已登记的 main/test 根，避免按构建输出或其它 checkout 扩大源码检查范围。 */
    private static List<Path> maintainedSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : TestModulePaths.javaSourceRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !path.toString().contains("target" + java.io.File.separator))
                        .forEach(sources::add);
            }
        }
        return sources;
    }

    /** 泛型逗号、组件注解和嵌套 record 由 AST 区分，不能按文本逗号推断字段数量。 */
    @Test
    void acceptsAlignedAnnotatedGenericAndNestedRecords() throws IOException {
        String header = "record Sample<T>(";
        String source = "/** 类型说明。\n * @param first 首个映射\n * @param second 次个值\n */\n"
                + header + "@Deprecated java.util.Map<String, java.util.List<T>> first,\n"
                + " ".repeat(header.length()) + """
                    String second) {
                        /** 空状态标记。 */
                        record Empty() {}
                    }
                    """;
        List<String> failures = verifyFixture(source);
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    /** 类型说明不能代替字段说明，过期字段名、重复标签和空白或英文说明都必须被拒绝。 */
    @Test
    void rejectsMissingStaleDuplicateAndNonChineseComponentDocs() throws IOException {
        String header = "record Sample(";
        String source = """
            /** 类型说明。
             * @param first English only
             * @param first 重复说明
             * @param stale 过期字段
             */
            """
                + header + "String first,\n" + " ".repeat(header.length()) + "String second) {}\n";
        List<String> failures = verifyFixture(source);
        assertTrue(failures.stream().anyMatch(value -> value.contains("first 缺少中文字段说明")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("second 缺少中文字段说明")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("重复 @param first")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("不存在的字段 stale")));
        assertFalse(verifyFixture("""
            /** 类型说明。
             * @param value
             */
            record Single(String value) {}
            """).isEmpty());
    }

    /** 多字段逐行对齐，首字段留在声明行；单字段和无字段状态不强制制造续行。 */
    @Test
    void rejectsInlineOrMisalignedComponentsButAllowsSingleAndEmptyRecords() throws IOException {
        String comment = "/** 类型说明。\n * @param first 首个值\n * @param second 次个值\n */\n";
        assertTrue(verifyFixture(comment + "record Sample(String first, String second) {}\n")
                .stream().anyMatch(value -> value.contains("逐字段换行")));
        assertTrue(verifyFixture(comment + "record Sample(String first,\n    String second) {}\n")
                .stream().anyMatch(value -> value.contains("逐字段换行")));
        assertTrue(verifyFixture(comment + "record Sample(\n    String first,\n    String second) {}\n")
                .stream().anyMatch(value -> value.contains("首字段")));
        assertTrue(verifyFixture("""
            /** 类型说明。
             * @param value 文本值
             */
            record Single(String value) {}
            /** 空状态标记。 */
            record Empty() {}
            """).isEmpty());
    }

    /** 局部、循环、资源及显式 lambda 的 var 均须拒绝，隐式 lambda 与文本中的 var 保持合法。 */
    @Test
    void rejectsInferredLocalTypesWithoutRejectingImplicitLambdaParameters() throws IOException {
        List<String> failures = verifyFixture("""
                /** 使用显式类型记录测试输入，字符串中的 var 不参与判断。 */
                class Sample {
                    /** 六类真实推断声明共同覆盖语法位置，避免文本扫描漏掉没有等号的变量。 */
                    void run() throws Exception {
                        var value = "var inside text";
                        final var next = value;
                        java.util.function.Function<String, String> identity = item -> item;
                        java.util.function.Function<String, String> namedVar = var -> var;
                        try (var reader = new java.io.StringReader(next)) {
                            identity.apply(value);
                        }
                        for (var index = 0; index < 1; index++) {
                            identity.apply(value);
                        }
                        for (var item : java.util.List.of(value)) {
                            identity.apply(item);
                        }
                        java.util.function.Function<String, String> explicit = (var /* 类型位置 */ item) -> item;
                    }
                }
                """, false);
        assertTrue(failures.size() == 6, () -> String.join("\n", failures));
        assertTrue(failures.stream().allMatch(value -> value.contains("禁止使用 var")));
    }

    /** 语法损坏不能被 parse 的错误恢复吞掉后冒充源码策略通过。 */
    @Test
    void rejectsMalformedSourcesBeforeInspectingRecoveredSyntaxTrees() throws IOException {
        List<String> failures = verifyFixture("/** 类型说明。 */ class Sample { void broken( }", false);
        assertTrue(failures.stream().anyMatch(failure -> failure.contains("Java 语法错误")),
                () -> String.join("\n", failures));
    }

    /** 内存源码只验证真实 Java 语法树，避免门禁回归夹具污染被扫描的源码目录。 */
    private static List<String> verifyFixture(String source) throws IOException {
        return verifyFixture(source, true);
    }

    /** 普通声明与 record 共享同一 AST 扫描器，让回归夹具覆盖真实门禁的两个模式。 */
    private static List<String> verifyFixture(String source, boolean recordsOnly) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<String> failures = new ArrayList<>();
        JavaFileObject fixture = new SimpleJavaFileObject(URI.create("string:///Sample.java"),
                JavaFileObject.Kind.SOURCE) {
            /** 返回冻结的夹具内容，使 DocTrees 与位置检查读取同一份文本。 */
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "25", "-proc:none"), null, List.of(fixture));
            DocTrees docs = DocTrees.instance(task);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            if (appendSyntaxFailures(diagnostics, failures)) {
                return failures;
            }
            for (CompilationUnitTree unit : parsed) {
                new DocumentationScanner(unit, docs, failures, source, recordsOnly).scan(new TreePath(unit), null);
            }
        }
        return failures;
    }

    /** 收集真实编译器 ERROR 并停止 AST 策略扫描，避免恢复节点的无效位置导致漏检或误判。 */
    private static boolean appendSyntaxFailures(DiagnosticCollector<JavaFileObject> diagnostics,
                                                List<String> failures) {
        boolean invalid = false;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                String sourceName = diagnostic.getSource() == null ? "Java" : diagnostic.getSource().getName();
                failures.add(sourceName + ":" + diagnostic.getLineNumber() + " Java 语法错误："
                        + diagnostic.getMessage(Locale.ROOT));
                invalid = true;
            }
        }
        return invalid;
    }

    /** 作者标识固定在首行，使自动生成物排除与人工文件归属判断保持确定。 */
    private static void verifyAuthor(Path source, List<String> failures) throws IOException {
        try (java.io.BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            if (!AUTHOR.equals(reader.readLine())) {
                failures.add(source + ":1 缺少首行作者标识");
            }
        }
    }

    /**
     * 生产代码统一使用已配置的 SLF4J owner，避免 JUL 默认处理器绕过日志格式、级别与脱敏边界。
     */
    private static void verifyProductionLogging(List<Path> productionRoots, List<String> failures)
            throws IOException {
        try {
            for (Path productionRoot : productionRoots) {
                try (Stream<Path> files = Files.walk(productionRoot)) {
                    files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                        try {
                            String source = Files.readString(path, StandardCharsets.UTF_8);
                            if (source.contains("java.util.logging")) {
                                failures.add(path + ": 禁止生产代码使用 java.util.logging，必须复用统一日志边界");
                            }
                        } catch (IOException failure) {
                            throw new java.io.UncheckedIOException(failure);
                        }
                    });
                }
            }
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    /**
     * 只执行 parse 而不做类型分析；字段与通用注释规则共用解析入口，均不依赖业务 classpath。
     */
    private static void verifyJavadocs(List<Path> sources, List<String> failures, boolean recordsOnly)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("必须使用完整 JDK 25 运行源码门禁");
        }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sources);
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics,
                    List.of("--release", "25", "-proc:none"), null, units);
            DocTrees docs = DocTrees.instance(task);
            Iterable<? extends CompilationUnitTree> parsed = task.parse();
            if (appendSyntaxFailures(diagnostics, failures)) {
                return;
            }
            for (CompilationUnitTree unit : parsed) {
                String source = unit.getSourceFile().getCharContent(true).toString();
                new DocumentationScanner(unit, docs, failures, source, recordsOnly).scan(new TreePath(unit), null);
            }
        }
    }

    /** AST 访问器只检查人工声明，不会把 record 自动访问器或编译器合成构造器计入。 */
    private static final class DocumentationScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final DocTrees docs;
        private final List<String> failures;
        private final String source;
        private final boolean recordsOnly;

        /** 保存编译单元、DocTrees 和同一份源码，使注释与字段排版都定位到真实声明。 */
        private DocumentationScanner(CompilationUnitTree unit, DocTrees docs, List<String> failures,
                                     String source, boolean recordsOnly) {
            this.unit = unit;
            this.docs = docs;
            this.failures = failures;
            this.source = source;
            this.recordsOnly = recordsOnly;
        }

        /** 具名类型解释职责；record 还必须在声明上方逐项说明组件并保持统一排版。 */
        @Override
        public Void visitClass(ClassTree node, Void unused) {
            if (!node.getSimpleName().isEmpty() && (!recordsOnly || node.getKind() == Tree.Kind.RECORD)) {
                requireChineseDoc(getCurrentPath(), "类型 " + node.getSimpleName());
            }
            if (recordsOnly && node.getKind() == Tree.Kind.RECORD) {
                verifyRecordComponents(node);
            }
            return super.visitClass(node, unused);
        }

        /**
         * parse 阶段 record 的非静态 VariableTree 就是头部组件；泛型参数与正文静态常量均不计入。
         * 注释从 DocTrees 的原始位置读取，避免 JDK 25 的 Unicode 转义影响中文检查。
         */
        private void verifyRecordComponents(ClassTree node) {
            List<VariableTree> components = node.getMembers().stream()
                    .filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
                    .filter(field -> !field.getModifiers().getFlags().contains(Modifier.STATIC)).toList();
            Map<String, String> descriptions = new LinkedHashMap<>();
            DocCommentTree comment = docs.getDocCommentTree(getCurrentPath());
            if (comment != null) {
                for (DocTree tag : comment.getBlockTags()) {
                    if (!(tag instanceof ParamTree parameter) || parameter.isTypeParameter()) {
                        continue;
                    }
                    String name = parameter.getName().getName().toString();
                    int start = (int) docs.getSourcePositions().getStartPosition(unit, comment, tag);
                    int end = (int) docs.getSourcePositions().getEndPosition(unit, comment, tag);
                    String text = start < 0 || end < start ? "" : source.substring(start, end)
                            .replaceFirst("^@param\\s+\\S+\\s*", "");
                    if (descriptions.putIfAbsent(name, text) != null) {
                        recordFailure(node, "重复 @param " + name);
                    }
                    if (components.stream().noneMatch(field -> field.getName().contentEquals(name))) {
                        recordFailure(node, "@param 指向不存在的字段 " + name);
                    }
                }
            }
            for (VariableTree component : components) {
                String name = component.getName().toString();
                String text = descriptions.get(name);
                if (text == null || !HAN.matcher(text).find()) {
                    recordFailure(node, name + " 缺少中文字段说明 @param " + name);
                }
            }
            if (components.size() < 2) {
                return;
            }
            int firstStart = (int) docs.getSourcePositions().getStartPosition(unit, components.getFirst());
            int open = source.lastIndexOf('(', firstStart);
            if (firstStart != open + 1) {
                recordFailure(node, "首字段必须紧接声明行左括号");
            }
            int lineStart = source.lastIndexOf('\n', open) + 1;
            String separator = ",\\r?\\n" + " ".repeat(open + 1 - lineStart);
            for (int index = 1; index < components.size(); index++) {
                int previousEnd = (int) docs.getSourcePositions().getEndPosition(unit, components.get(index - 1));
                int currentStart = (int) docs.getSourcePositions().getStartPosition(unit, components.get(index));
                if (!source.substring(previousEnd, currentStart).matches(separator)) {
                    recordFailure(node, "多字段必须逐字段换行，并与首字段对齐");
                }
            }
        }

        /** 将字段诊断归到 record 声明行，便于一次修复同一声明的注释与排版。 */
        private void recordFailure(ClassTree node, String detail) {
            long position = docs.getSourcePositions().getStartPosition(unit, node);
            long line = position < 0 ? 1 : unit.getLineMap().getLineNumber(position);
            failures.add(unit.getSourceFile().getName() + ":" + line + " record "
                    + node.getSimpleName() + " " + detail);
        }

        /** 每个显式函数与构造器都必须解释设计约束，不能用英文或空注释掩盖行为边界。 */
        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            if (!recordsOnly) {
                requireChineseDoc(getCurrentPath(), "函数 " + node.getName());
            }
            return super.visitMethod(node, unused);
        }

        /**
         * 所有变量声明都拒绝 var，包括局部变量、资源和 lambda 参数；枚举常量仍按初始化器形状
         * 单独检查中文语义，避免把普通缓存字段误判为常量。
         */
        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (recordsOnly) {
                return super.visitVariable(node, unused);
            }
            TreePath parent = getCurrentPath().getParentPath();
            boolean inferredLocalType = node.getType() == null && parent != null
                    && switch (parent.getLeaf().getKind()) {
                        case BLOCK, FOR_LOOP, ENHANCED_FOR_LOOP, TRY, CASE -> true;
                        case LAMBDA_EXPRESSION -> declaresVarLambdaParameter(node);
                        default -> false;
                    };
            if (inferredLocalType || node.getType() != null && "var".contentEquals(node.getType().toString())) {
                long position = docs.getSourcePositions().getStartPosition(unit, node);
                long line = position < 0 ? 1 : unit.getLineMap().getLineNumber(position);
                failures.add(unit.getSourceFile().getName() + ":" + line + " 禁止使用 var，必须声明精确类型");
            }
            if (parent != null && parent.getLeaf().getKind() == Tree.Kind.ENUM
                    && node.getInitializer() != null
                    && node.getInitializer().getKind() == Tree.Kind.NEW_CLASS) {
                requireChineseDoc(getCurrentPath(), "枚举常量 " + node.getName());
            }
            return super.visitVariable(node, unused);
        }

        /**
         * Javac 将显式 var 与隐式 lambda 类型都置空；只检查 AST 定位的参数声明，
         * 区分类型关键字与名为 var 的合法参数，允许类型和参数之间的注释。
         */
        private boolean declaresVarLambdaParameter(VariableTree node) {
            long start = docs.getSourcePositions().getStartPosition(unit, node);
            long end = docs.getSourcePositions().getEndPosition(unit, node);
            return start >= 0 && end >= start
                    && VAR_LAMBDA_DECLARATION.matcher(source.substring((int) start, (int) end)).find();
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
