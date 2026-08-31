// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.catalog.adapter.out.CatalogRevisionHasher;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在目录的有界、失败关闭文件策略下生成一个 Skill 来源层。
 *
 * <p>本组件拥有文件系统与文档边界，使目录仅关注优先级和 Turn 快照发布。
 * 返回前会在内存中冻结精确包 Map，因此后续编辑、文件替换和链接都不能改变
 * {@link JaSkillSources} 已准入的快照。
 */
final class SkillPackageFactory {
    private static final String SKILL_DOCUMENT = "SKILL.md";
    private static final String SKILL_REVISION_PREFIX = "skill_";
    private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern FRONTMATTER_LINE = Pattern.compile("([a-z][a-z0-9-]*):[ \\t]*(.*)");
    private static final Set<String> REQUIRED_FRONTMATTER_FIELDS = Set.of("name", "description");
    private static final Set<String> OPTIONAL_FRONTMATTER_FIELDS = Set.of(
            "version", "license", "compatibility", "metadata", "allowed-tools");
    private static final List<BuiltinRegistration> BUILTINS = List.of(
            new BuiltinRegistration("coding", Map.of(SKILL_DOCUMENT, "/skills/coding/SKILL.md")));

    /**
     * 禁止实例化无状态 Factory，避免它演变为第二个可变目录或缓存所有者。
     */
    private SkillPackageFactory() {
        // 静态边界不持有可变状态，目录所有权始终只属于 JaSkillSources。
    }

    /**
     * 从显式注册表生成内置 Skill，不枚举 classpath 目录。
     *
     * <p>Native Image 不提供可移植的嵌入资源目录发现，因此经审查的注册表是内置名称与路径的唯一来源。
     */
    static List<FrozenSkill> loadBuiltins() throws IOException {
        List<FrozenSkill> skills = new ArrayList<>(BUILTINS.size());
        for (BuiltinRegistration registration : BUILTINS) {
            LinkedHashMap<String, String> documents = new LinkedHashMap<>();
            for (Map.Entry<String, String> resource : registration.resources().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                int maxBytes = SKILL_DOCUMENT.equals(resource.getKey())
                        ? JaSkillSources.MAX_SKILL_DOCUMENT_BYTES : JaSkillSources.MAX_FILE_BYTES;
                documents.put(resource.getKey(), readClasspathUtf8(resource.getValue(), maxBytes));
            }
            skills.add(parsePackage(registration.name(), SkillCatalog.Source.BUNDLED, documents));
        }
        return List.copyOf(skills);
    }

    /**
     * 按字典序枚举用户或工作区来源，并校验每个已存在条目。
     *
     * <p>可选根目录缺失时返回空；单个损坏或超限包不进入可用快照，不能阻断同源其它合法 Skill。
     * 来源根本身不可安全枚举时仍失败关闭，避免把边界故障误判成空目录。
     */
    @SuppressWarnings("PMD.EmptyCatchBlock")
    static List<FrozenSkill> loadFilesystemSource(Path root, SkillCatalog.Source source) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.notExists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        rejectLinkOrReparse(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("skill_source_not_directory");
        }
        Path realRoot = absolute.toRealPath();
        List<Path> children = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(absolute)) {
            for (Path child : entries) {
                if (children.size() == JaSkillSources.MAX_SKILLS_PER_SOURCE) {
                    throw new IOException("skill_source_count_limit");
                }
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(SkillPackageFactory::fileName));
        List<FrozenSkill> skills = new ArrayList<>(children.size());
        for (Path child : children) {
            try {
                rejectLinkOrReparse(child);
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                requireContained(realRoot, child);
                skills.add(loadFilesystemPackage(child, realRoot, source));
            } catch (IOException | ArithmeticException invalidPackage) {
                // Skill catalog 只公开最终解析成功的完整包；失败包不能留下部分资源或扩大权限。
            }
        }
        return List.copyOf(skills);
    }

    /**
     * 解析元数据前复制并校验包内所有文档。
     *
     * <p>读取期间即执行字节、字符、token、文件、条目和深度上限，避免不可信 Skill 在拒绝前耗尽内存或提示预算。
     */
    private static FrozenSkill loadFilesystemPackage(
            Path skillRoot, Path sourceRealRoot, SkillCatalog.Source source) throws IOException {
        String directoryName = requireSkillName(fileName(skillRoot));
        LinkedHashMap<String, String> documents = new LinkedHashMap<>();
        long totalBytes = 0;
        int totalCharacters = 0;
        int totalTokens = 0;
        List<Path> paths = new ArrayList<>();
        collectPackageEntries(skillRoot, sourceRealRoot, 0, paths);
        paths.sort(Comparator.comparing(path -> portableRelative(skillRoot, path)));
        for (Path path : paths) {
            rejectLinkOrReparse(path);
            requireContained(sourceRealRoot, path);
            if (skillRoot.relativize(path).getNameCount() > JaSkillSources.MAX_DIRECTORY_DEPTH) {
                throw new IOException("skill_directory_depth_limit");
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("skill_resource_not_regular_file");
            }
            if (documents.size() >= JaSkillSources.MAX_FILES_PER_SKILL) {
                throw new IOException("skill_file_count_limit");
            }
            String relativePath = portableRelative(skillRoot, path);
            long size = Files.size(path);
            if (SKILL_DOCUMENT.equals(relativePath)
                && size > JaSkillSources.MAX_SKILL_DOCUMENT_BYTES) {
                throw new IOException("skill_document_size_limit");
            }
            if (size > JaSkillSources.MAX_FILE_BYTES
                || totalBytes + size > JaSkillSources.MAX_TOTAL_BYTES) {
                throw new IOException("skill_byte_limit");
            }
            String content = readFilesystemUtf8(path, size);
            totalBytes += size;
            totalCharacters = Math.addExact(totalCharacters, content.length());
            totalTokens = Math.addExact(totalTokens, conservativeTokenCount(content));
            if (totalCharacters > JaSkillSources.MAX_SKILL_CHARACTERS
                || totalTokens > JaSkillSources.MAX_SKILL_TOKENS) {
                throw new IOException("skill_text_budget_limit");
            }
            documents.put(relativePath, content);
        }
        if (!documents.containsKey(SKILL_DOCUMENT)) {
            throw new IOException("skill_document_missing");
        }
        return parsePackage(directoryName, source, documents);
    }

    /**
     * 进入子目录前先完成节点准入，并在条目超过硬上限时立即失败。
     *
     * <p>DirectoryStream 确保 junction 等重解析节点在枚举后代前被检查，这是 Windows 上必须保持的安全顺序。
     */
    private static void collectPackageEntries(
            Path directory, Path sourceRealRoot, int depth, List<Path> entries) throws IOException {
        if (depth >= JaSkillSources.MAX_DIRECTORY_DEPTH) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                if (children.iterator().hasNext()) {
                    throw new IOException("skill_directory_depth_limit");
                }
            }
            return;
        }
        List<Path> children = new ArrayList<>();
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (entries.size() + children.size() == JaSkillSources.MAX_ENTRIES_PER_SKILL) {
                    throw new IOException("skill_entry_count_limit");
                }
                children.add(child);
            }
        }
        children.sort(Comparator.comparing(SkillPackageFactory::fileName));
        for (Path child : children) {
            rejectLinkOrReparse(child);
            requireContained(sourceRealRoot, child);
            entries.add(child);
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                collectPackageEntries(child, sourceRealRoot, depth + 1, entries);
            }
        }
    }

    /**
     * 路径必须指向具名目录项；文件系统根没有文件名，因此在进入排序或标识解析前显式拒绝。
     */
    private static String fileName(Path path) {
        Path name = Objects.requireNonNull(path, "path").getFileName();
        if (name == null) {
            throw new IllegalArgumentException("skill_path_name_missing");
        }
        return name.toString();
    }

    /**
     * 解析 Agent Skills 必填身份和受控可选元数据，并生成规范化文档 Map。
     *
     * <p>可选字段参与 revision 但不进入权限；重复键、未知字段及 YAML 可执行特性失败关闭，
     * 避免不同解析器形成不同提示或授权语义。
     */
    private static FrozenSkill parsePackage(
            String expectedName, SkillCatalog.Source source, Map<String, String> inputDocuments)
            throws IOException {
        Frontmatter frontmatter = parseFrontmatter(inputDocuments.get(SKILL_DOCUMENT));
        if (!expectedName.equals(frontmatter.name())) {
            throw new IOException("skill_name_directory_mismatch");
        }
        LinkedHashMap<String, String> documents = new LinkedHashMap<>();
        inputDocuments.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                documents.put(entry.getKey(), SKILL_DOCUMENT.equals(entry.getKey())
                        ? frontmatter.body() : entry.getValue()));
        String revision = skillRevision(
                frontmatter.name(), frontmatter.description(), frontmatter.header(), documents);
        SkillCatalog.SkillDescriptor descriptor = new SkillCatalog.SkillDescriptor(
                frontmatter.name(), frontmatter.description(), source, revision);
        return new FrozenSkill(frontmatter.name(), revision, descriptor, Map.copyOf(documents));
    }

    /**
     * 要求 frontmatter 从首字节开始；顶层键独占一行，仅 block scalar 和 metadata 可缩进续行。
     *
     * <p>字节边界已完成换行与 Unicode 规范化，解析器只处理稳定文本，避免平台表示差异改变摘要。
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
        return new Frontmatter(name, description, header, document.substring(end + 5));
    }

    /**
     * 必填字段提取成模型可见标量，可选标准字段只做安全结构校验且不映射为权限。
     *
     * <p>`allowed-tools` 与其它可选字段会参与包 revision，但不会进入 descriptor 或 Tool Policy，
     * 从类型边界上保证第三方 Skill 元数据不能扩大 Ja 授权。
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
     * 只接受普通或无转义引号标量，并排除 YAML 可执行特性。
     *
     * <p>拒绝复杂形式可保持解析确定性，也无需仅为 Skill 元数据引入通用 YAML Runtime 或 Native Image 反射面。
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
     * 有界读取一个文件，并确认读取期间文件大小未变化。
     *
     * <p>二次大小检查关闭常见的替换/写入竞态，避免解析器或版本摘要接触半写入字节。
     */
    private static String readFilesystemUtf8(Path path, long expectedSize) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(JaSkillSources.MAX_FILE_BYTES + 1);
        }
        if (bytes.length != expectedSize || bytes.length > JaSkillSources.MAX_FILE_BYTES) {
            throw new IOException("skill_file_changed_during_snapshot");
        }
        return decodeAndNormalize(bytes);
    }

    /**
     * 以相同编码策略读取一个显式注册的 Native Image 资源。
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
     * 拒绝 BOM 和非法 UTF-8，并在散列前规范化换行与 Unicode。
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
     * 真实路径包含校验阻断 junction 与替换逃逸，同时不暴露物理路径。
     */
    private static void requireContained(Path realRoot, Path candidate) throws IOException {
        if (!candidate.toRealPath().startsWith(realRoot)) {
            throw new IOException("skill_path_escape");
        }
    }

    /**
     * 在所有支持平台上生成斜杠分隔的逻辑资源名。
     */
    private static String portableRelative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
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
     * 在不透明 descriptor 命名空间中散列规范化包内容。
     *
     * <p>长度前缀防止文档名或值伪造分隔符歧义，使版本不受来源顺序与平台换行差异影响。
     */
    private static String skillRevision(
            String name, String description, String frontmatter, Map<String, String> documents) {
        CatalogRevisionHasher digest = new CatalogRevisionHasher("ja-skill-v1")
                .append(name)
                .append(description)
                .append(frontmatter);
        for (Map.Entry<String, String> entry : documents.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            digest.append(entry.getKey()).append(entry.getValue());
        }
        return SKILL_REVISION_PREFIX + digest.finish();
    }

    /**
     * 描述一个显式内置 Skill 注册项，不允许通过 classpath 扫描隐式扩展攻击面。
     */
    private record BuiltinRegistration(String name, Map<String, String> resources) {
        /**
         * 复制显式注册表，避免启动过程观察到意外修改。
         */
        private BuiltinRegistration {
            Objects.requireNonNull(name, "name");
            resources = Map.copyOf(resources);
        }
    }

    /**
     * 保存已经完成严格字段校验的 SKILL.md frontmatter 与正文。
     */
    private record Frontmatter(String name, String description, String header, String body) {
    }

    /**
     * Factory 生成的冻结包；其优先级与快照生命周期由目录拥有。
     */
    static record FrozenSkill(
            String name, String revision, SkillCatalog.SkillDescriptor descriptor,
            Map<String, String> documents) {
    }
}
