// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.filesystem.PathIdentities;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;
import io.github.kongweiguang.ja.workspace.adapter.out.filesystem.WorkspaceBoundary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 装配固定文件 Tool，并只在进程预检成功时加入真实可用的 Shell Tool。 */
public final class BuiltInTools {
    private static final int MAX_TEXT_CHARACTERS = 4_000_000;
    private static final int MAX_TEXT_BYTES = MAX_TEXT_CHARACTERS * 4;

    /** 禁止绕过工厂拼装会漂移的内置 Tool 集。 */
    private BuiltInTools() {
    }

    /**
     * 将进程级 Shell 能力与 Turn 发现到的 Skill 元数据目录绑定；正文由 read 调用实时读取。
     */
    public static ToolRegistry create(Path workspaceRoot, SkillCatalog skillCatalog,
                                       SkillCatalog.Catalog skillCatalogView, ShellCapability shellCapability,
                                       AgentPromptSession promptSession, ManagedAttachmentReader attachments) {
        return create(workspaceRoot, skillCatalog, skillCatalogView, shellCapability, promptSession,
                attachments, BuiltInTools::atomicWrite);
    }

    /** 测试可替换唯一 mutation IO 边界以稳定构造写后路径竞态，生产始终传入受检原子写实现。 */
    static ToolRegistry create(Path workspaceRoot, SkillCatalog skillCatalog,
                               SkillCatalog.Catalog skillCatalogView, ShellCapability shellCapability,
                               AgentPromptSession promptSession, ManagedAttachmentReader attachments,
                               MutationWriter mutationWriter) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        MutationWriter writer = Objects.requireNonNull(mutationWriter, "mutationWriter");
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new ReadTool(root, skillCatalog, skillCatalogView, promptSession));
        tools.add(new ReadAttachmentTool(attachments));
        Objects.requireNonNull(shellCapability, "shellCapability").profile()
                .ifPresent(profile -> tools.add(new ShellTool(profile)));
        tools.add(new EditTool(root, writer));
        tools.add(new WriteTool(root, writer));
        return new ToolRegistry(tools);
    }

    /** 从当前执行上下文可见的受管附件读取有界文本或 Base64 字节。 */
    private static final class ReadAttachmentTool extends ToolSupport {
        private final ManagedAttachmentReader attachments;

        /** Tool 只持有受管端口；模型不能提交 Thread、Workspace、路径或物理摘要。 */
        private ReadAttachmentTool(ManagedAttachmentReader attachments) {
            super(new ToolSpec("read_attachment", "Read a bounded range from an attached file",
                    objectSchema(Map.of(
                            "attachmentId", property("string", "Opaque attachment identity."),
                            "offsetBytes", property("integer", "Zero-based byte offset; defaults to 0."),
                            "maxBytes", property("integer", "Maximum bytes from 4 to 65536.")),
                            List.of("attachmentId"))));
            this.attachments = Objects.requireNonNull(attachments, "attachments");
        }

        /** 附件读取不改变外部状态，显式声明只读以避免执行层继续按 Tool 名称维护白名单。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** 受管附件读取不会写工作区，因此可显式保持完整预览。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /** Thread identity 始终取冻结 ExecutionContext，猜中其它会话的 attachmentId 仍会失败关闭。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token) {
            String attachmentId = string(invocation, "attachmentId", 128);
            int offsetBytes = integer(invocation, "offsetBytes", 0, 0, 100 * 1024 * 1024);
            int maxBytes = integer(invocation, "maxBytes", 64 * 1024, 4, 64 * 1024);
            token.throwIfCancellationRequested();
            ManagedAttachmentReader.ReadResult result = attachments.read(new ManagedAttachmentReader.ReadRequest(
                    attachmentId, context.threadId(), offsetBytes, maxBytes));
            JsonObject metadata = JsonObjects.builder()
                    .putText("attachmentId", result.attachmentId())
                    .putText("displayName", result.displayName())
                    .putNumber("sizeBytes", result.sizeBytes())
                    .putText("mediaKind", result.mediaKind())
                    .putText("mediaType", result.mediaType())
                    .putText("encoding", result.encoding())
                    .putNumber("offsetBytes", result.offsetBytes())
                    .putNumber("nextOffsetBytes", result.nextOffsetBytes())
                    .putBoolean("endOfFile", result.endOfFile())
                    .build();
            return new ToolResult(ToolOutcome.SUCCEEDED, result.content(), Optional.of(metadata), null);
        }
    }

    /** 读取普通路径或 skill:// 资源；offset/limit 使用一基行号。 */
    private static final class ReadTool extends ToolSupport {
        private final Path workspaceRoot;
        private final SkillCatalog skills;
        private final SkillCatalog.Catalog catalog;
        private final AgentPromptSession promptSession;

        /** 固定普通文件路径基准与已发现 Skill locator；每次读取仍访问对应实时资源。 */
        private ReadTool(Path workspaceRoot, SkillCatalog skills, SkillCatalog.Catalog catalog,
                          AgentPromptSession promptSession) {
            super(new ToolSpec("read", "Read a UTF-8 file or skill:// resource",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, parent, or skill:// path."),
                            "offset", property("integer", "One-based first line; defaults to 1."),
                            "limit", property("integer", "Maximum lines; defaults to 2000.")), List.of("path"))));
            this.workspaceRoot = workspaceRoot;
            this.skills = Objects.requireNonNull(skills, "skills");
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.promptSession = Objects.requireNonNull(promptSession, "promptSession");
        }

        /** 文件或 Skill 读取没有外部写副作用；Prompt 激活仍由 Session 在内存中原子管理。 */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /** read 与 Skill 激活只读取文件/更新内存 Prompt，不写工作区。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * Skill URI 复用发现时选定的来源 locator 并实时读取；普通文件仍直接服从 OS 账户权限。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String rawPath = string(invocation, "path", 8_192);
            int offset = integer(invocation, "offset", 1, 1, Integer.MAX_VALUE);
            int limit = integer(invocation, "limit", 2_000, 1, 100_000);
            String content;
            JsonObject metadata;
            if (rawPath.startsWith("skill://")) {
                SkillAddress address = SkillAddress.parse(rawPath);
                SkillCatalog.SkillDocument document = skills.read(catalog,
                        new SkillCatalog.SkillReadRequest(address.name(), address.resource(), MAX_TEXT_CHARACTERS));
                if ("SKILL.md".equals(document.resourcePath())) {
                    AgentPromptSession.SkillActivation activation = promptSession.activateSkill(document);
                    JsonObject activationMetadata = JsonObjects.builder()
                            .putText("skill", document.skillName())
                            .build();
                    if (!activation.activated()) {
                        return new ToolResult(ToolOutcome.FAILED,
                                "Tool failed: " + activation.errorCode(), Optional.of(activationMetadata),
                                activation.errorCode());
                    }
                    return new ToolResult(ToolOutcome.SUCCEEDED, activation.receipt(),
                            Optional.of(activationMetadata), null);
                }
                content = document.content();
                metadata = JsonObjects.builder()
                        .putBoolean("skill_truncated", document.truncated())
                        .build();
            } else {
                Path path = resolve(workspaceRoot, rawPath);
                content = readUtf8File(path, token);
                metadata = JsonObjects.builder().putText("path", path.toString()).build();
            }
            LineSlice slice = slice(content, offset, limit);
            JsonObject rangeMetadata = JsonObjects.builder()
                    .putNumber("offset", offset)
                    .putNumber("lines", slice.lines())
                    .putBoolean("truncated", slice.truncated())
                    .build();
            return new ToolResult(ToolOutcome.SUCCEEDED, slice.content(),
                    Optional.of(merge(metadata, rangeMetadata)), null);
        }

    }

    /** 精确替换唯一 oldText，避免模糊 Patch 猜测和多处静默修改。 */
    private static final class EditTool extends ToolSupport {
        private final Path workspaceRoot;
        private final WorkspaceBoundary boundary;
        private final MutationWriter mutationWriter;

        /** Workspace 只提供相对路径起点，绝对路径与父级路径保持原生语义。 */
        private EditTool(Path workspaceRoot, MutationWriter mutationWriter) {
            super(new ToolSpec("edit", "Replace one unique text occurrence in a UTF-8 file",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, or parent path."),
                            "oldText", property("string", "Text that must occur exactly once."),
                            "newText", property("string", "Replacement text.")),
                            List.of("path", "oldText", "newText"))));
            this.workspaceRoot = workspaceRoot;
            this.boundary = new WorkspaceBoundary(workspaceRoot);
            this.mutationWriter = Objects.requireNonNull(mutationWriter, "mutationWriter");
        }

        /** edit 在成功后返回写前/写后 UTF-8 正文，因此可由 Java 精确归属。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /** 先确认唯一匹配再原子替换，失败时绝不产生部分写入。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            MutationTarget target = mutationTarget(workspaceRoot, boundary,
                    string(invocation, "path", 8_192), true);
            Path path = target.path();
            String oldText = string(invocation, "oldText", MAX_TEXT_CHARACTERS);
            String newText = optionalString(invocation, "newText", MAX_TEXT_CHARACTERS);
            if (newText == null) throw new IllegalArgumentException("invalid argument: newText");
            String current = Files.readString(path, StandardCharsets.UTF_8);
            int first = current.indexOf(oldText);
            if (first < 0) throw new IOException("old_text_not_found");
            if (current.indexOf(oldText, first + oldText.length()) >= 0) throw new IOException("old_text_not_unique");
            token.throwIfCancellationRequested();
            String updated = current.substring(0, first) + newText + current.substring(first + oldText.length());
            writeObserved(target, updated, mutationWriter);
            AgentTool.MutationReceipt receipt = target.receipt(true, current, updated);
            return new ToolResult(ToolOutcome.SUCCEEDED, "File edited successfully.",
                    Optional.empty(), null, Optional.of(receipt));
        }
    }

    /** 完整写入一个 UTF-8 文件，并通过同目录临时文件避免半写入。 */
    private static final class WriteTool extends ToolSupport {
        private final Path workspaceRoot;
        private final WorkspaceBoundary boundary;
        private final MutationWriter mutationWriter;

        /** Workspace 只提供相对路径起点，不建立目录授权边界。 */
        private WriteTool(Path workspaceRoot, MutationWriter mutationWriter) {
            super(new ToolSpec("write", "Atomically write a complete UTF-8 file",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, or parent path."),
                            "content", property("string", "Complete UTF-8 file content.")),
                            List.of("path", "content"))));
            this.workspaceRoot = workspaceRoot;
            this.boundary = new WorkspaceBoundary(workspaceRoot);
            this.mutationWriter = Objects.requireNonNull(mutationWriter, "mutationWriter");
        }

        /** write 完整掌握目标 UTF-8 pre/postimage，因此不需要扫描 Git 或工作区。 */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /** 创建父目录并原子替换目标；不向模型暴露 CAS、revision 或文件锁协议。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            MutationTarget target = mutationTarget(workspaceRoot, boundary,
                    string(invocation, "path", 8_192), false);
            Path path = target.path();
            String content = optionalString(invocation, "content", MAX_TEXT_CHARACTERS);
            if (content == null) throw new IllegalArgumentException("invalid argument: content");
            token.throwIfCancellationRequested();
            boolean beforeExists = Files.exists(path);
            String before = beforeExists ? readUtf8File(path, token) : "";
            writeObserved(target, content, mutationWriter);
            AgentTool.MutationReceipt receipt = target.receipt(beforeExists, before, content);
            return new ToolResult(ToolOutcome.SUCCEEDED, "File written successfully.",
                    Optional.empty(), null, Optional.of(receipt));
        }
    }

    /** 相对路径和 .. 只相对 Workspace 解析，绝对路径保持原样且不做 containment 检查。 */
    private static Path resolve(Path workspaceRoot, String rawPath) {
        Path candidate = Path.of(rawPath);
        return (candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate)).normalize();
    }

    /**
     * 工作区内写入改由物理 Boundary 返回受检路径；外部路径保留既有 FULL_ACCESS 语义但不携带
     * Workspace confinement 证明，后续 tracker 会将其显式归为 outside_workspace。
     */
    private static MutationTarget mutationTarget(Path workspaceRoot, WorkspaceBoundary boundary,
                                                 String rawPath, boolean mustExist) throws IOException {
        Path requested = resolve(workspaceRoot, rawPath);
        Optional<String> relative = workspaceRelative(workspaceRoot, requested);
        if (relative.isEmpty()) return new MutationTarget(requested, null, null);
        String relativePath = relative.orElseThrow();
        Path admitted = mustExist ? boundary.existing(relativePath) : boundary.target(relativePath);
        return new MutationTarget(admitted, boundary, relativePath);
    }

    /**
     * 把普通盘符、8.3 alias 与 namespaced 绝对路径归并到同一 Workspace 相对身份；
     * 只比较已存在祖先的文件 identity，避免用字符串前缀误判同一物理目录或放宽外部路径语义。
     */
    private static Optional<String> workspaceRelative(Path workspaceRoot, Path requested) throws IOException {
        Path rootIdentity = PathIdentities.normalized(workspaceRoot);
        Path targetIdentity = PathIdentities.normalized(requested);
        if (targetIdentity.startsWith(rootIdentity)) {
            return Optional.of(rootIdentity.relativize(targetIdentity).toString().replace('\\', '/'));
        }

        Path physicalRoot = workspaceRoot.toAbsolutePath().normalize().toRealPath();
        List<String> suffix = new ArrayList<>();
        Path cursor = targetIdentity;
        while (cursor != null) {
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSameFile(cursor, physicalRoot)) {
                return Optional.of(String.join("/", suffix));
            }
            Path name = cursor.getFileName();
            if (name == null) break;
            suffix.add(0, name.toString());
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    /**
     * 读取前固定普通文件边界，并在流读取时再次执行字节上限，避免文件增长绕过预检造成无界内存占用。
     */
    private static String readUtf8File(Path path, CancellationToken token) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.isDirectory()) throw ToolSupport.failure(ToolSupport.Failure.PATH_IS_DIRECTORY);
        if (!attributes.isRegularFile()) throw ToolSupport.failure(ToolSupport.Failure.PATH_NOT_REGULAR_FILE);
        if (attributes.size() > MAX_TEXT_BYTES) throw ToolSupport.failure(ToolSupport.Failure.FILE_TOO_LARGE);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(attributes.size(), 8_192L));
        byte[] buffer = new byte[8_192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                token.throwIfCancellationRequested();
                if (read == 0) continue;
                if ((long) bytes.size() + read > MAX_TEXT_BYTES) {
                    throw ToolSupport.failure(ToolSupport.Failure.FILE_TOO_LARGE);
                }
                bytes.write(buffer, 0, read);
            }
        }
        return decodeUtf8(bytes.toByteArray());
    }

    /**
     * 使用 REPORT 模式拒绝替换式解码，并复核字符契约与 NUL，防止二进制内容伪装为成功文本。
     */
    private static String decodeUtf8(byte[] bytes) throws IOException {
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidEncoding) {
            throw ToolSupport.failure(ToolSupport.Failure.FILE_NOT_UTF8);
        }
        if (content.length() > MAX_TEXT_CHARACTERS) {
            throw ToolSupport.failure(ToolSupport.Failure.FILE_TOO_LARGE);
        }
        if (content.indexOf('\0') >= 0) throw ToolSupport.failure(ToolSupport.Failure.FILE_NOT_TEXT);
        return content;
    }

    /**
     * 受约束写入开始后，任何路径安全或捕获失败都必须成为内部 observer；外部路径仍沿用普通 Tool 失败，
     * 因为它本就不能贡献 Workspace ChangeSet。
     */
    private static void writeObserved(MutationTarget target, String content, MutationWriter writer)
            throws IOException {
        if (target.boundary() == null) {
            writer.write(target.path(), content, null);
            return;
        }
        try {
            writer.write(target.path(), content, target.boundary());
            target.revalidateAfterWrite();
        } catch (SecurityException failure) {
            throw ToolSupport.mutationObservation(
                    AgentTool.MutationObservationFailure.OUTSIDE_WORKSPACE, failure);
        } catch (IOException failure) {
            throw ToolSupport.mutationObservation(
                    AgentTool.MutationObservationFailure.CAPTURE_FAILED, failure);
        }
    }

    /**
     * 临时文件通过 NOFOLLOW FileChannel 写入，并以 parent guard、fileKey 与最终字节逐层复核；
     * Windows 缺少可靠目录句柄，因此这些检查只缩小竞态窗口，失败必须由 observer 明确降级。
     */
    private static void atomicWrite(Path target, String content, WorkspaceBoundary boundary) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("file_parent_unavailable");
        Files.createDirectories(parent);
        WorkspaceBoundary.MutationGuard guard = boundary == null ? null : boundary.mutationGuard(target);
        Path temporary = Files.createTempFile(parent, ".ja-write-", ".tmp");
        boolean moved = false;
        try {
            if (guard != null) boundary.revalidateMutationGuard(guard);
            Object temporaryFileKey = fileKey(temporary);
            byte[] encoded = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
            }
            if (guard != null) {
                boundary.revalidateMutationGuard(guard);
                requireSameFileKey(temporaryFileKey, fileKey(temporary));
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            if (guard != null) {
                boundary.revalidateMutationGuard(guard);
                boundary.revalidateExisting(target);
                requireSameFileKey(temporaryFileKey, fileKey(target));
                if (!Arrays.equals(encoded, Files.readAllBytes(target))) {
                    throw new IOException("workspace_postimage_mismatch");
                }
            }
        } finally {
            if (!moved) deleteTemporary(temporary, boundary, guard);
        }
    }

    /**
     * 仅在 parent guard 仍成立时按词法路径删除临时文件；目录已被替换时宁可留下旧目录中的孤儿文件，
     * 也不能沿攻击者新建的 junction 删除工作区外同名目标。
     */
    private static void deleteTemporary(Path temporary, WorkspaceBoundary boundary,
                                        WorkspaceBoundary.MutationGuard guard) throws IOException {
        if (guard == null) {
            Files.deleteIfExists(temporary);
            return;
        }
        try {
            boundary.revalidateMutationGuard(guard);
        } catch (IOException | SecurityException ignored) {
            // Parent identity 已变化，词法路径不再具备删除权限。
            return;
        }
        Files.deleteIfExists(temporary);
    }

    /**
     * 读取 NOFOLLOW fileKey；Windows 文件系统 provider 不提供时返回 null，并由 parent 真实路径、
     * FileChannel 与最终字节复核兜底，不能把平台能力缺失误报为普通写入失败。
     */
    private static Object fileKey(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object key = attributes.fileKey();
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw new IOException("workspace_file_identity_unavailable");
        }
        return key;
    }

    /** fileKey 可用时 move 前后必须保持 identity；null 表示 provider 不支持而不是 identity 相等。 */
    private static void requireSameFileKey(Object expected, Object actual) {
        if (expected != null && !Objects.equals(expected, actual)) {
            throw new SecurityException("workspace_file_identity_changed");
        }
    }

    /** 唯一可注入 mutation IO 边界用于确定性竞态测试，不向生产调用方公开。 */
    @FunctionalInterface
    interface MutationWriter {
        /** 实现必须完成整文件替换；调用方负责写后 receipt 与 observer 收口。 */
        void write(Path target, String content, WorkspaceBoundary boundary) throws IOException;
    }

    /**
     * 受检目标把 IO 路径、Boundary 和稳定相对身份绑定在一起，写后复核成功前绝不生成 receipt。
     */
    private record MutationTarget(Path path, WorkspaceBoundary boundary, String relativePath) {
        /** 原子替换后再次确认叶子与父目录仍属于同一物理 Workspace。 */
        private void revalidateAfterWrite() throws IOException {
            if (boundary != null) boundary.revalidateExisting(path);
        }

        /** 只有受检 Workspace 路径携带 confinement 证明；外部路径仍使用无证明收据。 */
        private AgentTool.MutationReceipt receipt(boolean beforeExists, String before, String after) {
            return boundary == null
                    ? AgentTool.MutationReceipt.of(path, beforeExists, before, true, after)
                    : AgentTool.MutationReceipt.confined(boundary.root(), relativePath, path,
                            beforeExists, before, true, after);
        }
    }

    /** 生成固定行范围并报告剩余内容，避免 read 一次把无界文件塞进上下文。 */
    private static LineSlice slice(String content, int offset, int limit) {
        List<String> lines = content.lines().toList();
        int start = Math.min(lines.size(), offset - 1);
        int end = Math.min(lines.size(), start + limit);
        return new LineSlice(String.join("\n", lines.subList(start, end)), end - start, end < lines.size());
    }

    /** 合并少量强类型元数据，并由 Builder 拒绝冲突键，防止后写值静默覆盖原事实。 */
    private static JsonObject merge(JsonObject left, JsonObject right) {
        JsonObjectBuilder merged = JsonObjects.builder();
        left.members().forEach(merged::put);
        right.members().forEach(merged::put);
        return merged.build();
    }

    /** 解析 skill://name/resource，缺失资源名时默认读取 SKILL.md。 */
    private record SkillAddress(String name, String resource) {
        /** 严格拆分 URI，禁止把不完整地址回退到普通文件路径。 */
        private static SkillAddress parse(String uri) {
            String remainder = uri.substring("skill://".length());
            int slash = remainder.indexOf('/');
            String name = slash < 0 ? remainder : remainder.substring(0, slash);
            String resource = slash < 0 || slash == remainder.length() - 1
                    ? "SKILL.md" : remainder.substring(slash + 1);
            return new SkillAddress(name, resource);
        }
    }

    /** 保存 read 的有界投影，截断事实必须随内容一起返回。 */
    private record LineSlice(String content, int lines, boolean truncated) {
    }
}
