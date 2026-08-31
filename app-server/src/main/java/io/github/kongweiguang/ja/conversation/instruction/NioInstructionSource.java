// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.instruction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AGENTS 文件系统适配器；精确名称、严格 UTF-8 和 realpath containment 在一个边界内完成。
 */
public final class NioInstructionSource {
    private static final String OVERRIDE_FILE = "AGENTS.override.md";
    private static final String DEFAULT_FILE = "AGENTS.md";

    /**
     * 查找距离 workspace 最近的 Git 根；不存在时返回 workspace 本身，禁止向无关父目录扩散规则。
     */
    Path projectBoundary(Path workspaceRoot) throws IOException {
        Path workspace = canonicalDirectory(workspaceRoot);
        for (Path candidate = workspace; candidate != null; candidate = candidate.getParent()) {
            if (findExact(candidate, ".git").isPresent()) return candidate.toRealPath();
        }
        return workspace;
    }

    /**
     * 读取目录中优先级最高的精确文件名；override 存在但损坏时失败关闭，不回退默认文件。
     */
    Observation observe(Path directory, Path boundary, String displayPrefix, int specificity) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Observation.missing();
            Path canonicalBoundary = canonicalDirectory(boundary);
            Path canonicalDirectory = canonicalDirectory(directory);
            if (!canonicalDirectory.startsWith(canonicalBoundary)) {
                return Observation.failure("AGENTS_DIRECTORY_OUTSIDE_BOUNDARY:" + safePath(directory));
            }
            Optional<Path> selected = findExact(canonicalDirectory, OVERRIDE_FILE);
            if (selected.isEmpty()) selected = findExact(canonicalDirectory, DEFAULT_FILE);
            if (selected.isEmpty()) return Observation.missing();
            Path file = selected.orElseThrow();
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return Observation.failure("AGENTS_NOT_REGULAR_FILE:" + safePath(file));
            }
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(canonicalBoundary)) {
                return Observation.failure("AGENTS_REALPATH_OUTSIDE_BOUNDARY:" + safePath(file));
            }
            byte[] bytes = Files.readAllBytes(realFile);
            String content = decodeStrictUtf8(bytes);
            String display = displayPrefix == null || displayPrefix.isBlank()
                    ? realFile.toString() : displayPrefix + "/" + realFile.getFileName();
            return Observation.present(new Document(realFile, display, content,
                    sha256(bytes), specificity));
        } catch (CharacterCodingException failure) {
            return Observation.failure("AGENTS_INVALID_UTF8:" + safePath(directory));
        } catch (IOException | SecurityException failure) {
            return Observation.failure("AGENTS_READ_FAILED:" + safePath(directory));
        }
    }

    /**
     * 返回 broad-to-specific 目录链，调用方据此保持更具体规则最后出现。
     */
    List<Path> chain(Path root, Path leaf) throws IOException {
        Path canonicalRoot = canonicalDirectory(root);
        Path canonicalLeaf = canonicalDirectory(leaf);
        if (!canonicalLeaf.startsWith(canonicalRoot)) {
            throw new IOException("instruction directory escapes project boundary");
        }
        List<Path> result = new ArrayList<>();
        Path current = canonicalRoot;
        result.add(current);
        for (Path segment : canonicalRoot.relativize(canonicalLeaf)) {
            current = current.resolve(segment);
            result.add(current);
        }
        return List.copyOf(result);
    }

    /**
     * Tool 路径只用于发现 workspace 内规则；URI 或 workspace 外路径返回 empty，不改变 Tool 自身访问策略。
     */
    Optional<Path> targetDirectory(Path workspaceRoot, String rawPath) throws IOException {
        if (rawPath == null || rawPath.isBlank() || rawPath.contains("://")) return Optional.empty();
        Path workspace = canonicalDirectory(workspaceRoot);
        final Path parsed;
        try {
            Path candidate = Path.of(rawPath);
            parsed = (candidate.isAbsolute() ? candidate : workspace.resolve(candidate)).normalize();
        } catch (RuntimeException failure) {
            throw new IOException("tool path is invalid", failure);
        }
        if (!parsed.startsWith(workspace)) return Optional.empty();
        Path directory = Files.isDirectory(parsed, LinkOption.NOFOLLOW_LINKS)
                ? parsed : parsed.getParent();
        if (directory == null) return Optional.empty();
        Path existing = nearestExisting(directory);
        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(workspace)) {
            throw new IOException("tool path real ancestor escapes workspace");
        }
        return Optional.of(directory.toAbsolutePath().normalize());
    }

    /** workspace 相对目录统一使用 `/`，根目录由调用方排除，不持久化为特殊 sentinel。 */
    String relativeDirectory(Path workspaceRoot, Path directory) throws IOException {
        Path workspace = canonicalDirectory(workspaceRoot);
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(workspace)) throw new IOException("scope escapes workspace");
        return workspace.relativize(normalized).toString().replace('\\', '/');
    }

    /** 必须是已存在目录，避免通过尚未落盘路径伪造 containment 事实。 */
    private static Path canonicalDirectory(Path directory) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Path real = directory.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("instruction boundary is not a directory");
        }
        return real;
    }

    /** Windows 默认大小写不敏感，因此必须枚举目录项并逐字符核对文件名。 */
    private static Optional<Path> findExact(Path directory, String expectedName) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Path fileName = entry.getFileName();
                if (fileName != null && fileName.toString().equals(expectedName)) return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** 尚未创建的 write 目标回退到最近存在祖先，仍能验证 junction/symlink containment。 */
    private static Path nearestExisting(Path path) throws IOException {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) throw new IOException("tool path has no existing ancestor");
        return current;
    }

    /** Decoder 显式 REPORT，禁止 JVM replacement character 把损坏指令伪装成有效正文。 */
    private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /** 指令 revision 使用稳定 SHA-256，不复用平台相关 hashCode。 */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 诊断路径只做有界显示，不包含正文或异常消息。 */
    private static String safePath(Path path) {
        String value = path == null ? "unknown" : path.toAbsolutePath().normalize().toString();
        return value.length() <= 512 ? value : value.substring(value.length() - 512);
    }

    /** 单个有效 AGENTS 文档及其内容摘要。 */
    record Document(Path path, String displayPath, String content, String digest, int specificity) {
        /** 冻结非空字段，避免 Session revision 与随后渲染观察到不同事实。 */
        Document {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(displayPath, "displayPath");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(digest, "digest");
        }
    }

    /** 读取结果把缺失与损坏分开；只有损坏会令 Session fail closed。 */
    record Observation(Document document, String diagnostic) {
        /** 返回无文件的正常状态。 */
        static Observation missing() {
            return new Observation(null, null);
        }

        /** 返回冻结文档。 */
        static Observation present(Document document) {
            return new Observation(Objects.requireNonNull(document, "document"), null);
        }

        /** 返回不包含异常正文的稳定失败诊断。 */
        static Observation failure(String diagnostic) {
            return new Observation(null, Objects.requireNonNull(diagnostic, "diagnostic"));
        }

        /** 只有读取失败才需要副作用 fail closed。 */
        boolean failed() {
            return diagnostic != null;
        }
    }
}
