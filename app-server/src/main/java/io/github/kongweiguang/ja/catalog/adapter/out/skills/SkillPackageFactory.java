// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在有界、失败关闭的来源策略下发现 Skill 元数据，并按 locator 实时读取资源。
 *
 * <p>发现阶段只打开 SKILL.md 的 frontmatter，不枚举或物化包内辅助资源；读取阶段重新执行
 * containment、重解析点、大小和 UTF-8 校验，使正文保持实时而安全边界不退化。
 */
final class SkillPackageFactory {
    private static final String SKILL_DOCUMENT = "SKILL.md";
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern FRONTMATTER_LINE = Pattern.compile("([a-z][a-z0-9-]*):[ \\t]*(.*)");
    private static final Set<String> REQUIRED_FRONTMATTER_FIELDS = Set.of("name", "description");
    private static final Set<String> OPTIONAL_FRONTMATTER_FIELDS = Set.of(
            "version", "license", "compatibility", "metadata", "allowed-tools");
    // 当前不随应用分发默认 Skill；保留目录来源槽位，与设置中的来源分类一致。
    private static final List<BuiltinRegistration> BUILTINS = List.of();

    /**
     * 禁止实例化无状态 Factory，避免它演变为第二个目录或正文缓存所有者。
     */
    private SkillPackageFactory() {
        // locator 生命周期由 JaSkillSources 拥有，Factory 不保留任何文件内容。
    }

    /**
     * 从显式注册表发现内置 Skill；只读取各注册项的 SKILL.md frontmatter，不枚举 classpath。
     */
    static List<LocatedSkill> discoverBuiltins() throws IOException {
        List<LocatedSkill> skills = new ArrayList<>(BUILTINS.size());
        for (BuiltinRegistration registration : BUILTINS) {
            String skillResource = registration.resources().get(SKILL_DOCUMENT);
            if (skillResource == null) {
                throw new IOException("builtin_skill_document_missing");
            }
            Frontmatter frontmatter = parseFrontmatter(readClasspathFrontmatter(skillResource));
            if (!registration.name().equals(frontmatter.name())) {
                throw new IOException("skill_name_directory_mismatch");
            }
            SkillCatalog.SkillDescriptor descriptor = new SkillCatalog.SkillDescriptor(
                    frontmatter.name(), frontmatter.description(), SkillCatalog.Source.BUNDLED);
            skills.add(new LocatedSkill(frontmatter.name(), descriptor,
                    new ClasspathLocator(registration.resources())));
        }
        return List.copyOf(skills);
    }

    /**
     * 按字典序枚举来源的直接 Skill 目录，并只读取每个 SKILL.md 的 frontmatter。
     *
     * <p>单个损坏包被排除而不阻断同源其它条目；来源根本身不安全时仍失败关闭，避免把边界故障误判为空。
     */
    @SuppressWarnings("PMD.EmptyCatchBlock")
    static List<LocatedSkill> discoverFilesystemSource(Path root, SkillCatalog.Source source)
            throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        rejectLinkOrReparse(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill_source_not_directory");
        }
        Path realRoot = absolute.toRealPath();
        List<Path> children = directChildren(absolute);
        List<LocatedSkill> skills = new ArrayList<>(children.size());
        for (Path child : children) {
            try {
                rejectLinkOrReparse(child);
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                requireContained(realRoot, child);
                skills.add(discoverFilesystemPackage(child, realRoot, source));
            } catch (IOException | ArithmeticException | InvalidPathException invalidPackage) {
                // 发现只发布完整、严格解析的元数据；失败包不能留下 locator 或扩大可见 Skill 集。
            }
        }
        return List.copyOf(skills);
    }

    /**
     * 从已解析 locator 实时读取一个资源，并在返回前按调用方字符预算安全截断。
     */
    static ResourceContent readResource(LocatedSkill skill, String resourcePath, int maxCharacters)
            throws IOException {
        Objects.requireNonNull(skill, "skill");
        String content;
        if (skill.locator() instanceof ClasspathLocator classpath) {
            content = readClasspathResource(skill.name(), classpath, resourcePath);
        } else if (skill.locator() instanceof FilesystemLocator filesystem) {
            content = readFilesystemResource(skill.name(), filesystem, resourcePath);
        } else {
            throw new IOException("skill_locator_unsupported");
        }
        int end = safeCharacterBoundary(content, maxCharacters);
        return new ResourceContent(content.substring(0, end), end < content.length());
    }

    /**
     * 对来源根的直接条目施加硬上限；不进入 Skill 子目录，因此发现成本与辅助资源数量、大小无关。
     */
    private static List<Path> directChildren(Path root) throws IOException {
        List<Path> children = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path child : entries) {
                if (children.size() == JaSkillSources.MAX_SKILLS_PER_SOURCE) {
                    throw new IOException("skill_source_count_limit");
                }
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(SkillPackageFactory::fileName));
        return children;
    }

    /**
     * 只验证具名目录与主文档 locator，并把本次解析出的元数据和来源根绑定起来。
     */
    private static LocatedSkill discoverFilesystemPackage(
            Path skillRoot, Path sourceRealRoot, SkillCatalog.Source source) throws IOException {
        String directoryName = requireSkillName(fileName(skillRoot));
        Path skillDocument = skillRoot.resolve(SKILL_DOCUMENT);
        rejectLinkOrReparse(skillDocument);
        requireContained(sourceRealRoot, skillDocument);
        if (!Files.isRegularFile(skillDocument, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill_document_missing");
        }
        Frontmatter frontmatter = parseFrontmatter(readFilesystemFrontmatter(skillDocument));
        if (!directoryName.equals(frontmatter.name())) {
            throw new IOException("skill_name_directory_mismatch");
        }
        SkillCatalog.SkillDescriptor descriptor = new SkillCatalog.SkillDescriptor(
                frontmatter.name(), frontmatter.description(), source);
        return new LocatedSkill(frontmatter.name(), descriptor,
                new FilesystemLocator(skillRoot.toAbsolutePath().normalize(), sourceRealRoot));
    }

    /**
     * 内置资源只能读取显式注册路径；请求未注册资源时不回退到 classpath 任意路径。
     */
    private static String readClasspathResource(
            String skillName, ClasspathLocator locator, String resourcePath) throws IOException {
        String classpathResource = locator.resources().get(resourcePath);
        if (classpathResource == null) {
            throw new IllegalArgumentException("skill_resource_not_found");
        }
        int maxBytes = SKILL_DOCUMENT.equals(resourcePath)
                ? JaSkillSources.MAX_SKILL_DOCUMENT_BYTES : JaSkillSources.MAX_FILE_BYTES;
        String content = readClasspathUtf8(classpathResource, maxBytes);
        return SKILL_DOCUMENT.equals(resourcePath)
                ? requireCurrentSkillBody(skillName, content) : enforceTextBudget(content);
    }

    /**
     * 文件系统 read 从原始 locator 逐段复核目录、链接、重解析点和 containment，再打开最终普通文件。
     */
    private static String readFilesystemResource(
            String skillName, FilesystemLocator locator, String resourcePath) throws IOException {
        Path sourceRoot = locator.sourceRealRoot();
        Path skillRoot = locator.skillRoot();
        rejectLinkOrReparse(sourceRoot);
        rejectLinkOrReparse(skillRoot);
        if (!Files.isDirectory(skillRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill_root_not_directory");
        }
        requireContained(sourceRoot, skillRoot);

        Path relative;
        try {
            relative = Path.of(resourcePath.replace('/', java.io.File.separatorChar));
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("skill_resource_path_invalid", invalid);
        }
        if (relative.isAbsolute() || relative.getNameCount() > JaSkillSources.MAX_DIRECTORY_DEPTH) {
            throw new IllegalArgumentException("skill_resource_path_invalid");
        }
        Path target = skillRoot.resolve(relative).normalize();
        if (!target.startsWith(skillRoot)) {
            throw new IllegalArgumentException("skill_resource_path_invalid");
        }
        validateResourceChain(skillRoot, relative, sourceRoot);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("skill_resource_not_found");
        }

        int maxBytes = SKILL_DOCUMENT.equals(resourcePath)
                ? JaSkillSources.MAX_SKILL_DOCUMENT_BYTES : JaSkillSources.MAX_FILE_BYTES;
        long size = Files.size(target);
        if (size < 0 || size > maxBytes) {
            throw new IOException("skill_resource_size_limit");
        }
        String content = readFilesystemUtf8(target, size, maxBytes);
        validateResourceChain(skillRoot, relative, sourceRoot);
        return SKILL_DOCUMENT.equals(resourcePath)
                ? requireCurrentSkillBody(skillName, content) : enforceTextBudget(content);
    }

    /**
     * 从 Skill 根逐段检查请求路径；任何中间段不是目录或出现 reparse 都失败关闭。
     */
    private static void validateResourceChain(Path skillRoot, Path relative, Path sourceRealRoot)
            throws IOException {
        Path current = skillRoot;
        for (int index = 0; index < relative.getNameCount(); index++) {
            current = current.resolve(relative.getName(index));
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("skill_resource_not_found");
            }
            rejectLinkOrReparse(current);
            requireContained(sourceRealRoot, current);
            if (index + 1 < relative.getNameCount()
                && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("skill_resource_parent_not_directory");
            }
        }
    }

    /**
     * SKILL.md 每次读取都重新解析当前 frontmatter，并保持目录身份不变后才返回实时正文。
     */
    private static String requireCurrentSkillBody(String expectedName, String document) throws IOException {
        Frontmatter current = parseFrontmatter(document);
        if (!expectedName.equals(current.name())) {
            throw new IOException("skill_name_directory_mismatch");
        }
        return enforceTextBudget(current.body());
    }

    /**
     * 单次 read 仍执行原有字符和保守 token 上限，避免渐进读取成为无界提示注入入口。
     */
    private static String enforceTextBudget(String content) throws IOException {
        if (content.length() > JaSkillSources.MAX_SKILL_CHARACTERS
            || conservativeTokenCount(content) > JaSkillSources.MAX_SKILL_TOKENS) {
            throw new IOException("skill_text_budget_limit");
        }
        return content;
    }

    /**
     * frontmatter 必须从首字节开始；顶层键独占一行，仅 block scalar 和 metadata 可缩进续行。
     */
    private static Frontmatter parseFrontmatter(String document) throws IOException {
        if (document == null || !document.startsWith("---\n")) {
            throw new IOException("skill_frontmatter_missing");
        }
        int end = document.indexOf("\n---\n", 4);
        if (end < 0) {
            throw new IOException("skill_frontmatter_unclosed");
        }
        String header = document.substring(4, end);
        if (header.isEmpty()) {
            throw new IOException("skill_frontmatter_empty");
        }
        Map<String, String> fields = new HashMap<>();
        String[] lines = header.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            if (Character.isWhitespace(line.charAt(0))) {
                throw new IOException("skill_frontmatter_indentation_invalid");
            }
            Matcher matcher = FRONTMATTER_LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IOException("skill_frontmatter_line_invalid");
            }
            String key = matcher.group(1);
            if ((!REQUIRED_FRONTMATTER_FIELDS.contains(key) && !OPTIONAL_FRONTMATTER_FIELDS.contains(key))
                || fields.containsKey(key)) {
                throw new IOException("skill_frontmatter_field_invalid");
            }
            String raw = matcher.group(2);
            List<String> continuation = new ArrayList<>();
            while (index + 1 < lines.length
                   && (lines[index + 1].isBlank()
                       || Character.isWhitespace(lines[index + 1].charAt(0)))) {
                continuation.add(lines[++index]);
            }
            fields.put(key, frontmatterValue(key, raw, continuation));
        }
        String name = requireSkillName(fields.get("name"));
        String description = fields.get("description");
        if (description == null || description.isBlank() || description.length() > 1024
            || description.indexOf('\0') >= 0) {
            throw new IOException("skill_description_invalid");
        }
        return new Frontmatter(name, description, document.substring(end + 5));
    }

    /**
     * 必填字段提取成模型可见标量；可选标准字段只做安全结构校验，不映射为权限。
     */
    private static String frontmatterValue(String key, String raw, List<String> continuation)
            throws IOException {
        if ("metadata".equals(key)) {
            validateMetadata(raw, continuation);
            return "metadata";
        }
        if (!continuation.isEmpty()) {
            if (!Set.of("|", "|-", "|+", ">", ">-", ">+").contains(raw)) {
                throw new IOException("skill_frontmatter_continuation_invalid");
            }
            return blockScalar(raw, continuation);
        }
        return strictScalar(raw);
    }

    /**
     * metadata 允许标准 YAML 的行内 map 或缩进 map，但内容不被解释或执行。
     */
    private static void validateMetadata(String raw, List<String> continuation) throws IOException {
        if (!raw.isBlank()) {
            if (!continuation.isEmpty() || !raw.startsWith("{") || !raw.endsWith("}")) {
                throw new IOException("skill_metadata_invalid");
            }
            return;
        }
        if (continuation.stream().allMatch(String::isBlank)) {
            throw new IOException("skill_metadata_invalid");
        }
        for (String line : continuation) {
            if (!line.isBlank() && (!Character.isWhitespace(line.charAt(0))
                                    || !line.stripLeading().contains(":"))) {
                throw new IOException("skill_metadata_invalid");
            }
        }
    }

    /**
     * 仅实现 Agent Skills 元数据需要的 YAML block scalar，拒绝无缩进内容和空值。
     */
    private static String blockScalar(String marker, List<String> continuation) throws IOException {
        List<String> values = new ArrayList<>();
        for (String line : continuation) {
            if (line.isBlank()) {
                values.add("");
            } else if (!Character.isWhitespace(line.charAt(0))) {
                throw new IOException("skill_frontmatter_indentation_invalid");
            } else {
                values.add(line.stripLeading());
            }
        }
        String separator = marker.startsWith(">") ? " " : "\n";
        String value = String.join(separator, values).strip();
        if (value.isEmpty()) {
            throw new IOException("skill_frontmatter_scalar_invalid");
        }
        return value;
    }

    /**
     * 只接受普通或无转义引号标量，并排除 YAML 可执行特性以保持解析确定性。
     */
    private static String strictScalar(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("skill_frontmatter_scalar_invalid");
        }
        String value = raw;
        if ((raw.startsWith("\"") && raw.endsWith("\""))
            || (raw.startsWith("'") && raw.endsWith("'"))) {
            value = raw.substring(1, raw.length() - 1);
            if (value.indexOf(raw.charAt(0)) >= 0 || value.indexOf('\\') >= 0) {
                throw new IOException("skill_frontmatter_scalar_escape_invalid");
            }
        } else if (raw.startsWith("\"") || raw.startsWith("'") || raw.contains(" #")
                   || raw.startsWith("[") || raw.startsWith("{") || raw.startsWith("&")
                   || raw.startsWith("*") || raw.startsWith("!") || raw.startsWith("|")
                   || raw.startsWith(">")) {
            throw new IOException("skill_frontmatter_scalar_invalid");
        }
        value = value.strip();
        if (value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IOException("skill_frontmatter_scalar_invalid");
        }
        return value;
    }

    /**
     * 文件系统发现只读取闭合 frontmatter 前缀；正文和辅助文件都不会进入发现内存。
     */
    private static String readFilesystemFrontmatter(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            String frontmatter = readFrontmatterPrefix(input, "skill_frontmatter_size_limit");
            rejectLinkOrReparse(path);
            return frontmatter;
        }
    }

    /**
     * classpath 发现与文件系统使用同一 frontmatter 预算，避免内置和外部来源产生解析差异。
     */
    private static String readClasspathFrontmatter(String resourcePath) throws IOException {
        try (InputStream input = SkillPackageFactory.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("builtin_skill_resource_missing");
            }
            return readFrontmatterPrefix(input, "builtin_skill_frontmatter_size_limit");
        }
    }

    /**
     * 逐字节读取到首个 YAML 闭合标记后立即停止，确保大正文不会在发现阶段被物化。
     */
    private static String readFrontmatterPrefix(InputStream input, String sizeError) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
        int[] tail = new int[5];
        boolean closed = false;
        for (int value = input.read(); value >= 0; value = input.read()) {
            if (output.size() == JaSkillSources.MAX_SKILL_DOCUMENT_BYTES) {
                throw new IOException(sizeError);
            }
            output.write(value);
            int length = output.size();
            tail[(length - 1) % tail.length] = value;
            if (endsWithFrontmatterDelimiter(tail, length)) {
                closed = true;
                break;
            }
        }
        if (!closed) {
            throw new IOException("skill_frontmatter_unclosed");
        }
        return decodeAndNormalize(output.toByteArray());
    }

    /**
     * 用固定五字节滚动窗口识别 LF、CRLF 和 CR 闭合行；CRLF 在末尾 CR 即可闭合，
     * 随后的规范化会补成 LF，避免 Windows checkout 因原始换行漏掉边界。
     */
    private static boolean endsWithFrontmatterDelimiter(int[] tail, int length) {
        return length >= tail.length
                && (tail[(length - 5) % tail.length] == '\n' || tail[(length - 5) % tail.length] == '\r')
                && tail[(length - 4) % tail.length] == '-'
                && tail[(length - 3) % tail.length] == '-'
                && tail[(length - 2) % tail.length] == '-'
                && (tail[(length - 1) % tail.length] == '\n'
                    || tail[(length - 1) % tail.length] == '\r');
    }

    /**
     * 有界读取单个文件，并确认读取期间大小未变化，避免返回半写入字节。
     */
    private static String readFilesystemUtf8(Path path, long expectedSize, int maxBytes) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(maxBytes + 1);
        }
        if (bytes.length != expectedSize || bytes.length > maxBytes || Files.size(path) != expectedSize) {
            throw new IOException("skill_file_changed_during_read");
        }
        return decodeAndNormalize(bytes);
    }

    /**
     * 以与文件系统相同的编码和大小策略实时读取显式注册的 Native Image 资源。
     */
    private static String readClasspathUtf8(String resourcePath, int maxBytes) throws IOException {
        try (InputStream input = SkillPackageFactory.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("builtin_skill_resource_missing");
            }
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length == 0 || bytes.length > maxBytes) {
                throw new IOException("builtin_skill_resource_size_invalid");
            }
            return decodeAndNormalize(bytes);
        }
    }

    /**
     * 拒绝 BOM 和非法 UTF-8，并规范化换行与 Unicode，保证模型看到稳定文本表示。
     */
    private static String decodeAndNormalize(byte[] bytes) throws IOException {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
            && bytes[2] == (byte) 0xBF) {
            throw new IOException("skill_utf8_bom_forbidden");
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return Normalizer.normalize(decoded.replace("\r\n", "\n").replace('\r', '\n'),
                    Normalizer.Form.NFC);
        } catch (CharacterCodingException exception) {
            throw new IOException("skill_utf8_invalid", exception);
        }
    }

    /**
     * 以码点数作为保守且与 Provider 无关的 token 预算近似值。
     */
    private static int conservativeTokenCount(String content) {
        return content.codePointCount(0, content.length());
    }

    /**
     * 截断不切开 surrogate pair，避免合法 UTF-8 经 Java 字符预算后变成非法 Unicode。
     */
    private static int safeCharacterBoundary(String content, int maxCharacters) {
        int end = Math.min(content.length(), maxCharacters);
        if (end > 0 && end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))
            && Character.isLowSurrogate(content.charAt(end))) {
            end--;
        }
        return end;
    }

    /**
     * 在枚举或读取前拒绝符号链接及非普通重解析节点。
     */
    private static void rejectLinkOrReparse(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("skill_link_forbidden");
        }
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Path noFollow = path.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path followed = path.toRealPath();
        if (attributes.isOther() || !noFollow.equals(followed)) {
            throw new IOException("skill_reparse_point_forbidden");
        }
    }

    /**
     * 真实路径 containment 阻断 junction 与替换逃逸，同时不向异常暴露物理路径。
     */
    private static void requireContained(Path realRoot, Path candidate) throws IOException {
        if (!candidate.toRealPath().startsWith(realRoot)) {
            throw new IOException("skill_path_escape");
        }
    }

    /**
     * 路径必须指向具名目录项；文件系统根没有文件名，因此在排序和标识解析前显式拒绝。
     */
    private static String fileName(Path path) {
        Path name = Objects.requireNonNull(path, "path").getFileName();
        if (name == null) {
            throw new IllegalArgumentException("skill_path_name_missing");
        }
        return name.toString();
    }

    /**
     * 对目录和 frontmatter 强制同一个可移植 lower-kebab 身份。
     */
    private static String requireSkillName(String name) throws IOException {
        if (name == null || name.length() > 64 || !SAFE_NAME.matcher(name).matches()) {
            throw new IOException("skill_name_invalid");
        }
        return name;
    }

    /**
     * 描述显式内置注册项；资源表只保存 classpath locator，不缓存任何正文。
     */
    private record BuiltinRegistration(String name, Map<String, String> resources) {
        /**
         * 复制显式 locator 表，避免注册信息在进程内被意外修改。
         */
        private BuiltinRegistration {
            Objects.requireNonNull(name, "name");
            resources = Map.copyOf(resources);
        }
    }

    /**
     * 保存已严格解析的发现元数据及实时资源 locator。
     */
    static record LocatedSkill(String name, SkillCatalog.SkillDescriptor descriptor, ResourceLocator locator) {
        /**
         * locator 与公开 descriptor 同时必填，但不得包含已读取正文。
         */
        LocatedSkill {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(locator, "locator");
        }
    }

    /**
     * 标记允许的两类原始资源定位方式，禁止实现方塞入正文缓存。
     */
    private sealed interface ResourceLocator permits ClasspathLocator, FilesystemLocator {
    }

    /**
     * 内置 locator 仅保存经审查的逻辑资源名到 classpath 路径映射。
     */
    private record ClasspathLocator(Map<String, String> resources) implements ResourceLocator {
        /**
         * 复制映射以固定可访问集合，不固定其指向资源的读取结果。
         */
        private ClasspathLocator {
            resources = Map.copyOf(resources);
        }
    }

    /**
     * 文件 locator 保存 Skill 目录与发现时已验证的来源 real root，不保存目录内容。
     */
    private record FilesystemLocator(Path skillRoot, Path sourceRealRoot) implements ResourceLocator {
        /**
         * 路径在创建前已通过 containment；read 仍会重新验证，防止发现后的替换与链接漂移。
         */
        private FilesystemLocator {
            skillRoot = skillRoot.toAbsolutePath().normalize();
            sourceRealRoot = sourceRealRoot.toAbsolutePath().normalize();
        }
    }

    /**
     * Factory 向目录返回单次实时读取结果，正文不会存入长期 catalog 注册表。
     */
    static record ResourceContent(String content, boolean truncated) {
    }

    /**
     * 保存解析后的必要 frontmatter 与当前正文；仅存在于单次 discover/read 调用栈。
     */
    private record Frontmatter(String name, String description, String body) {
    }
}
