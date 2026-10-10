// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.skill.SkillCatalog;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.application.gateway.AgentPromptSession;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.RecoveryEvidence;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool.MutationObservationFailure;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool.MutationReceipt;
import io.github.kongweiguang.ja.foundation.filesystem.PathIdentities;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;
import io.github.kongweiguang.ja.foundation.search.NativeSearchToolResolver;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryPort;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 装配固定 Workspace 与附件 Tool；宿主进程能力由 ProcessToolFactory 独立拥有。
 */
public final class BuiltInTools {
    private static final int MAX_READ_TEXT_CHARACTERS = 4_000_000;
    private static final int MAX_READ_TEXT_BYTES = MAX_READ_TEXT_CHARACTERS * 4;
    private static final int MAX_READ_RESULT_BYTES = Utf8ToolTextBudget.MAX_MODEL_VISIBLE_TEXT_BYTES;
    private static final int MAX_READ_NOTICE_BYTES = 256;
    private static final int MAX_READ_BODY_BYTES = MAX_READ_RESULT_BYTES - MAX_READ_NOTICE_BYTES;
    private static final int MAX_READ_RESULT_LINES = 2_000;

    /**
     * 禁止绕过工厂拼装会漂移的内置 Tool 集。
     */
    private BuiltInTools() {
    }

    /**
     * 将 Skill 与受管媒体能力显式注入；进程生命周期由独立 ProcessSessionUseCase 管理。
     */
    public static ToolRegistry create(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                                      SkillCatalog skillCatalog,
                                      SkillCatalog.Catalog skillCatalogView, AgentPromptSession promptSession,
                                      ManagedAttachmentReader attachments,
                                      ManagedToolMediaImporter mediaImporter) {
        return create(workspaceRoot, workspaceBoundaries, skillCatalog, skillCatalogView, promptSession,
                attachments, mediaImporter, System.getenv());
    }

    /**
     * 媒体引用在 Tool 边界导入；搜索工具使用冻结环境，进程生命周期由独立注册表持有。
     */
    public static ToolRegistry create(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                                      SkillCatalog skillCatalog,
                                      SkillCatalog.Catalog skillCatalogView, AgentPromptSession promptSession,
                                      ManagedAttachmentReader attachments,
                                      ManagedToolMediaImporter mediaImporter,
                                      Map<String, String> environment) {
        return create(workspaceRoot, workspaceBoundaries, skillCatalog, skillCatalogView, promptSession,
                attachments, mediaImporter, BuiltInTools::atomicWrite,
                NativeSearchToolResolver.system(environment));
    }

    /**
     * 测试同时显式注入媒体导入端口与 mutation IO 替身；生产仍使用受检原子写实现。
     */
    static ToolRegistry create(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                               SkillCatalog skillCatalog,
                               SkillCatalog.Catalog skillCatalogView, AgentPromptSession promptSession,
                               ManagedAttachmentReader attachments,
                               ManagedToolMediaImporter mediaImporter,
                               MutationWriter mutationWriter) {
        return create(workspaceRoot, workspaceBoundaries, skillCatalog, skillCatalogView, promptSession,
                attachments, mediaImporter, mutationWriter, NativeSearchToolResolver.system());
    }

    /**
     * JVM 行为测试可注入固定 fd/rg 路径；生产组合仍由系统 resolver 选择打包资源或宿主 PATH，
     * 这样搜索进程生命周期测试不会通过修改全局环境伪造安装状态。
     */
    static ToolRegistry create(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                               SkillCatalog skillCatalog,
                               SkillCatalog.Catalog skillCatalogView, AgentPromptSession promptSession,
                               ManagedAttachmentReader attachments,
                               ManagedToolMediaImporter mediaImporter,
                               MutationWriter mutationWriter, NativeSearchToolResolver searchResolver) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        WorkspaceBoundaryFactory boundaries = Objects.requireNonNull(workspaceBoundaries, "workspaceBoundaries");
        ManagedToolMediaImporter importer = Objects.requireNonNull(mediaImporter, "mediaImporter");
        MutationWriter writer = Objects.requireNonNull(mutationWriter, "mutationWriter");
        NativeSearchToolResolver resolver = Objects.requireNonNull(searchResolver, "searchResolver");
        List<JaTool> tools = new ArrayList<>();
        tools.add(new ReadTool(root, skillCatalog, skillCatalogView, promptSession));
        tools.add(new ReadAttachmentTool(attachments));
        tools.add(new ViewImageTool(boundaries, importer));
        tools.add(WorkspaceFileTools.grep(root, boundaries, resolver));
        tools.add(WorkspaceFileTools.find(root, boundaries, resolver));
        tools.add(WorkspaceFileTools.ls(root, boundaries));
        tools.add(new EditTool(root, boundaries, writer));
        tools.add(new WriteTool(root, boundaries, writer));
        return new ToolRegistry(tools);
    }

    /**
     * edit 与 write 共享不设字符上限的路径 Schema；实际目标仍由 WorkspaceBoundary 和文件系统校验。
     */
    private static JsonObject workspaceMutationPathProperty() {
        return ToolSupport.requiredStringProperty("Relative, absolute, or parent path.");
    }

    /**
     * 创建不限定正文长度的字符串 Schema；仅按业务含义可选地要求非空。
     */
    private static JsonObject unboundedStringProperty(String description, boolean nonEmpty) {
        JsonObjectBuilder property = JsonObjects.builder()
                .putText("type", "string")
                .putText("description", description);
        if (nonEmpty) {
            property.putNumber("minLength", 1);
        }
        return property.build();
    }

    /**
     * 相对路径和 .. 只相对 Workspace 解析，绝对路径保持原样且不做 containment 检查。
     */
    private static Path resolve(Path workspaceRoot, String rawPath) {
        Path candidate = Path.of(rawPath);
        return (candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate)).normalize();
    }

    /**
     * 工作区内写入改由物理 Boundary 返回受检路径；外部路径保留既有 FULL_ACCESS 语义但不携带
     * Workspace confinement 证明，后续 tracker 会将其显式归为 outside_workspace。
     */
    private static MutationTarget mutationTarget(Path workspaceRoot, WorkspaceBoundaryPort boundary,
                                                 String rawPath, boolean mustExist) throws IOException {
        Path requested = resolve(workspaceRoot, rawPath);
        Optional<String> relative = workspaceRelative(workspaceRoot, requested);
        if (relative.isEmpty()) {
            return new MutationTarget(requested, null, null);
        }
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
            if (name == null) {
                break;
            }
            suffix.add(0, name.toString());
            cursor = cursor.getParent();
        }
        return Optional.empty();
    }

    /**
     * 让 read/edit/write 共用同一 UTF-8、取消、Deadline 与文件边界规则，避免读旧文和普通读取语义漂移。
     */
    private static String readUtf8File(Path path, CancellationToken token, Instant deadline) throws IOException {
        return ToolSupport.readUtf8File(path, token, deadline, MAX_READ_TEXT_BYTES, MAX_READ_TEXT_CHARACTERS);
    }

    /**
     * 受约束写入开始后，任何路径安全或捕获失败都必须成为内部 observer；外部路径仍沿用普通 Tool 失败，
     * 因为它本就不能贡献 Workspace ChangeSet。
     */
    private static void writeObserved(MutationTarget target, String content, MutationWriter writer,
                                      CancellationToken token, Instant deadline) throws IOException {
        if (target.boundary() == null) {
            writer.write(target.path(), content, null, token, deadline);
            return;
        }
        try {
            writer.write(target.path(), content, target.boundary(), token, deadline);
            target.revalidateAfterWrite();
        } catch (SecurityException failure) {
            throw ToolSupport.mutationObservation(
                    MutationObservationFailure.OUTSIDE_WORKSPACE, failure);
        } catch (ToolSupport.DeadlineExceededException failure) {
            throw failure;
        } catch (IOException failure) {
            throw ToolSupport.mutationObservation(
                    MutationObservationFailure.CAPTURE_FAILED, failure);
        }
    }

    /**
     * 临时文件通过 NOFOLLOW writer 分段写入，并以 parent guard、fileKey 与 UTF-8 指纹逐层复核；
     * Windows 缺少可靠目录句柄，因此这些检查只缩小竞态窗口，失败必须由 observer 明确降级。
     */
    static void atomicWrite(Path target, String content, WorkspaceBoundaryPort boundary,
                            CancellationToken token, Instant deadline) throws IOException {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(deadline, "deadline");
        token.throwIfCancellationRequested();
        ToolSupport.throwIfDeadlineExceeded(deadline);
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("file_parent_unavailable");
        }
        Utf8Fingerprint expected = Utf8Fingerprint.of(content);
        Files.createDirectories(parent);
        token.throwIfCancellationRequested();
        ToolSupport.throwIfDeadlineExceeded(deadline);
        WorkspaceBoundaryPort.MutationGuard guard = boundary == null ? null : boundary.mutationGuard(target);
        Path temporary = Files.createTempFile(parent, ".ja-write-", ".tmp");
        boolean moved = false;
        Throwable primaryFailure = null;
        try {
            if (guard != null) {
                boundary.revalidateMutationGuard(guard);
            }
            Object temporaryFileKey = fileKey(temporary);
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                for (int offset = 0; offset < content.length();) {
                    token.throwIfCancellationRequested();
                    ToolSupport.throwIfDeadlineExceeded(deadline);
                    int length = Math.min(64 * 1024, content.length() - offset);
                    writer.write(content, offset, length);
                    offset += length;
                }
            }
            if (guard != null) {
                boundary.revalidateMutationGuard(guard);
                requireSameFileKey(temporaryFileKey, fileKey(temporary));
            }
            token.throwIfCancellationRequested();
            ToolSupport.throwIfDeadlineExceeded(deadline);
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
                if (!expected.equals(Utf8Fingerprint.of(target))) {
                    throw new IOException("workspace_postimage_mismatch");
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            if (!moved) {
                try {
                    deleteTemporary(temporary, boundary, guard);
                } catch (IOException cleanupFailure) {
                    if (primaryFailure == null) {
                        throw cleanupFailure;
                    }
                    primaryFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    /**
     * 仅在 parent guard 仍成立时按词法路径删除临时文件；目录已被替换时宁可留下旧目录中的孤儿文件，
     * 也不能沿攻击者新建的 junction 删除工作区外同名目标。
     */
    private static void deleteTemporary(Path temporary, WorkspaceBoundaryPort boundary,
                                        WorkspaceBoundaryPort.MutationGuard guard) throws IOException {
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
     * 读取 NOFOLLOW fileKey。JDK 只公开 provider-specific 的 {@code Object} 身份键，且允许返回 null；
     * Windows 文件系统 provider 不提供时由 parent 真实路径、FileChannel 与最终字节复核兜底，不能把平台
     * 能力缺失误报为普通写入失败。
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

    /**
     * fileKey 可用时 move 前后必须保持 identity；null 表示 provider 不支持而不是 identity 相等。
     */
    private static void requireSameFileKey(Object expected, Object actual) {
        if (expected != null && !Objects.equals(expected, actual)) {
            throw new SecurityException("workspace_file_identity_changed");
        }
    }

    /**
     * 对 read 结果同时施加行数和 UTF-8 字节预算；行偏移无法表示单行内续读，因此超长首行明确转交有界 Shell 查询。
     */
    private static LineSlice slice(String content, int offset, int limit) {
        List<String> lines = content.lines().toList();
        int start = Math.min(lines.size(), offset - 1);
        int requestedEnd = Math.min(lines.size(), start + limit);
        int lineEnd = Math.min(requestedEnd, start + MAX_READ_RESULT_LINES);
        Utf8ToolTextBudget result = new Utf8ToolTextBudget(MAX_READ_BODY_BYTES);
        int cursor = start;
        String termination = null;
        while (cursor < lineEnd) {
            String line = lines.get(cursor);
            if (!result.appendLine(line)) {
                int lineBytes = Utf8ToolTextBudget.utf8ByteLength(line);
                if (result.lineCount() == 0) {
                    return new LineSlice("[Line " + (cursor + 1) + " is " + lineBytes
                                         + " bytes and exceeds the 64KiB read result budget. Use shell to inspect a bounded range.]",
                            0, 0, lines.size(), true, "line_too_long");
                }
                termination = "byte_limit";
                break;
            }
            cursor++;
        }
        if (termination == null && cursor < requestedEnd) {
            termination = "line_limit";
        }
        if (termination == null && cursor < lines.size()) {
            termination = "requested_limit";
        }
        boolean truncated = cursor < lines.size();
        int nextOffset = truncated && !"line_too_long".equals(termination) ? cursor + 1 : 0;
        return new LineSlice(result.text(), cursor - start, nextOffset, lines.size(), truncated, termination);
    }

    /**
     * 将续读位置直接写入模型可见正文，避免调用方必须理解结构化 metadata 才能一次构造正确的下一次 read。
     */
    private static String withReadContinuation(LineSlice slice, int offset) {
        if (slice.nextOffset() == 0) {
            return slice.content();
        }
        int end = offset + slice.lines() - 1;
        return slice.content() + "\n\n[Showing lines " + offset + "-" + end + " of " + slice.totalLines()
               + ". Use offset=" + slice.nextOffset() + " to continue.]";
    }

    /**
     * 合并少量强类型元数据，并由 Builder 拒绝冲突键，防止后写值静默覆盖原事实。
     */
    private static JsonObject merge(JsonObject left, JsonObject right) {
        JsonObjectBuilder merged = JsonObjects.builder();
        left.members().forEach(merged::put);
        right.members().forEach(merged::put);
        return merged.build();
    }

    /**
     * 保存工作区相对目标与完整后像摘要，不把本地绝对路径或文件正文写入通用恢复事实。
     */
    private static RecoveryEvidence expectedTextEvidence(String relativePath, String content) {
        Utf8Fingerprint fingerprint = Utf8Fingerprint.of(content);
        JsonObject payload = JsonObjects.builder()
                .putText("relativePath", relativePath)
                .putNumber("byteLength", fingerprint.byteLength())
                .putText("sha256", fingerprint.sha256())
                .build();
        return new RecoveryEvidence("ja.workspace.expected-text", 1, payload, fingerprint.sha256());
    }

    /**
     * 唯一可注入 mutation IO 边界用于确定性竞态测试，不向生产调用方公开。
     */
    @FunctionalInterface
    interface MutationWriter {
        /**
         * 实现必须在每次暂存写入与提交前遵守取消和截止时间；成功返回代表替换已经提交。
         */
        void write(Path target, String content, WorkspaceBoundaryPort boundary,
                   CancellationToken token, Instant deadline) throws IOException;
    }

    /**
     * 从当前执行上下文可见的受管附件读取有界文本或 Base64 字节。
     */
    private static final class ReadAttachmentTool extends ToolSupport {
        private final ManagedAttachmentReader attachments;

        /**
         * Tool 只持有受管端口；模型不能提交 Thread、Workspace、路径或物理摘要。
         */
        private ReadAttachmentTool(ManagedAttachmentReader attachments) {
            super(new ToolSpec("read_attachment", "Read a bounded range from an attached file",
                    objectSchema(Map.of(
                                    "attachmentId", requiredStringProperty("Opaque attachment identity.", 128),
                                    "offsetBytes", integerProperty("Zero-based byte offset; defaults to 0.", 0,
                                            100 * 1024 * 1024),
                                    "maxBytes", integerProperty("Maximum bytes from 4 to 65536.", 4, 64 * 1024)),
                            List.of("attachmentId"))));
            this.attachments = Objects.requireNonNull(attachments, "attachments");
        }

        /**
         * 附件读取不改变外部状态，显式声明只读以避免执行层继续按 Tool 名称维护白名单。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /**
         * 受管附件读取不会写工作区，因此可显式保持完整预览。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * Thread identity 始终取冻结 ExecutionContext，猜中其它会话的 attachmentId 仍会失败关闭。
         */
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

    /**
     * 读取普通路径或 skill:// 资源；offset/limit 使用一基行号。
     */
    private static final class ReadTool extends ToolSupport {
        private final Path workspaceRoot;
        private final SkillCatalog skills;
        private final SkillCatalog.Catalog catalog;
        private final AgentPromptSession promptSession;

        /**
         * 固定普通文件路径基准与已发现 Skill locator；每次读取仍访问对应实时资源。
         */
        private ReadTool(Path workspaceRoot, SkillCatalog skills, SkillCatalog.Catalog catalog,
                         AgentPromptSession promptSession) {
            super(new ToolSpec("read", "Read a UTF-8 file or skill:// resource",
                    objectSchema(Map.of(
                                    "path", requiredStringProperty("Relative, absolute, parent, or skill:// path."),
                                    "offset", integerProperty("One-based first line; defaults to 1.", 1,
                                            Integer.MAX_VALUE),
                                    "limit", integerProperty("Requested lines; defaults to 2000. Results stay capped at 2000 lines and 64KiB.",
                                            1, 100_000)),
                            List.of("path"))));
            this.workspaceRoot = workspaceRoot;
            this.skills = Objects.requireNonNull(skills, "skills");
            this.catalog = Objects.requireNonNull(catalog, "catalog");
            this.promptSession = Objects.requireNonNull(promptSession, "promptSession");
        }

        /**
         * 文件或 Skill 读取没有外部写副作用；Prompt 激活仍由 Session 在内存中原子管理。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /**
         * read 与 Skill 激活只读取文件/更新内存 Prompt，不写工作区。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * Skill URI 复用发现时选定的来源 locator 并实时读取；普通文件直接服从 OS 账户权限，
         * 同时继承本次 Tool 的截止时间以约束阻塞读取。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            String rawPath = string(invocation, "path", Integer.MAX_VALUE);
            int offset = integer(invocation, "offset", 1, 1, Integer.MAX_VALUE);
            int limit = integer(invocation, "limit", 2_000, 1, 100_000);
            String content;
            JsonObject metadata;
            if (rawPath.startsWith("skill://")) {
                SkillAddress address = SkillAddress.parse(rawPath);
                io.github.kongweiguang.ja.agent.domain.skill.SkillDocument document = skills.read(catalog,
                        new SkillReadRequest(address.name(), address.resource(), MAX_READ_TEXT_CHARACTERS));
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
                content = BuiltInTools.readUtf8File(path, token, context.deadline());
                metadata = JsonObjects.builder().putText("path", path.toString()).build();
            }
            LineSlice slice = slice(content, offset, limit);
            JsonObjectBuilder rangeMetadata = JsonObjects.builder()
                    .putNumber("offset", offset)
                    .putNumber("lines", slice.lines())
                    .putNumber("totalLines", slice.totalLines())
                    .putBoolean("truncated", slice.truncated());
            if (slice.nextOffset() > 0) {
                rangeMetadata.putNumber("nextOffset", slice.nextOffset());
            }
            if (slice.termination() != null) {
                rangeMetadata.putText("termination", slice.termination());
            }
            return new ToolResult(ToolOutcome.SUCCEEDED, withReadContinuation(slice, offset),
                    Optional.of(merge(metadata, rangeMetadata.build())), null);
        }

    }

    /**
     * 聚合 edit/write 共同的受限工作区写入状态。使用不可继承值对象而非抽象 Tool 基类，既防止
     * 写入边界漂移，也避免可抛异常的可继承构造器暴露部分初始化实例。
     *
     * @param workspaceRoot 工作区根目录
     * @param boundary 边界
     * @param writer 写入器
     */
    private record MutationSupport(Path workspaceRoot,
                                   WorkspaceBoundaryPort boundary,
                                   MutationWriter writer) {
        /**
         * 在唯一创建点同时固定边界和 writer，调用方不能混配来自不同工作区的写入状态。
         */
        private MutationSupport(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                                MutationWriter writer) {
            this(workspaceRoot, Objects.requireNonNull(workspaceBoundaries, "workspaceBoundaries").open(workspaceRoot),
                    Objects.requireNonNull(writer, "mutationWriter"));
        }
    }

    /**
     * 在同一原始版本上精确替换多个互不重叠的文本块，减少模型往返同时保留唯一匹配与原子提交约束。
     */
    private static final class EditTool extends ToolSupport {
        private final MutationSupport mutation;

        /**
         * WorkspaceBoundary 校验归一化后的写入目标，Provider 参数保留操作系统可表示的路径长度。
         */
        private EditTool(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                         MutationWriter mutationWriter) {
            super(new ToolSpec("edit", "Replace unique, non-overlapping text occurrences in one UTF-8 file",
                    objectSchema(Map.of(
                                    "path", workspaceMutationPathProperty(),
                                    "edits", arrayProperty("One or more unique, non-overlapping replacements matched against the original file.",
                                            replacementSchema(), 1)),
                            List.of("path", "edits"))));
            this.mutation = new MutationSupport(workspaceRoot, workspaceBoundaries, mutationWriter);
        }

        /**
         * 让 Provider Schema 与执行端共享替换字段、文本类型和封闭对象约束，不另设替换数量门槛。
         */
        private static JsonObject replacementSchema() {
            return objectSchema(Map.of(
                            "oldText", unboundedStringProperty(
                                    "Text that must occur exactly once in the original file.", true),
                            "newText", unboundedStringProperty(
                                    "Replacement text; empty text removes the occurrence.", false)),
                    List.of("oldText", "newText"));
        }

        /**
         * 即使绕过 Schema 仍校验 edits 数组结构；协议载荷预算负责总量，原子文件写入负责安全提交。
         */
        private static List<Replacement> replacements(Invocation invocation) {
            JsonValue raw = invocation.arguments().members().get("edits");
            if (!(raw instanceof JsonArray(List<JsonValue> values1)) || values1.isEmpty()) {
                throw argument("edits", "must contain at least one replacement");
            }
            List<Replacement> replacements = new ArrayList<>(values1.size());
            for (JsonValue value : values1) {
                if (!(value instanceof JsonObject edit) || edit.members().size() != 2
                    || !edit.containsKey("oldText") || !edit.containsKey("newText")) {
                    throw argument("edits", "must contain only oldText and newText objects");
                }
                String oldText = replacementText(edit, "oldText", false);
                String newText = replacementText(edit, "newText", true);
                replacements.add(new Replacement(oldText, newText));
            }
            return List.copyOf(replacements);
        }

        /**
         * 读取 replacement 字段时保留 oldText 非空和 newText 可为空的不同语义。
         */
        private static String replacementText(JsonObject edit, String name, boolean allowEmpty) {
            JsonValue raw = edit.members().get(name);
            if (!(raw instanceof JsonText(String value)) || (!allowEmpty && value.isBlank())) {
                throw argument("edits", name + " must be a " + (allowEmpty ? "string" : "non-empty string"));
            }
            return value;
        }

        /**
         * 把未匹配、重复匹配和重叠归为可纠正的模型参数错误；它们描述的是 oldText 与文件内容不符，
         * 不应被公共边界当作磁盘 I/O 故障并生成运维诊断。
         */
        private static List<ReplacementMatch> locateReplacements(String current, List<Replacement> replacements) {
            List<ReplacementMatch> matches = new ArrayList<>(replacements.size());
            for (Replacement replacement : replacements) {
                int first = current.indexOf(replacement.oldText());
                if (first < 0) {
                    throw argument("edits", "oldText must occur exactly once in the current file");
                }
                if (current.indexOf(replacement.oldText(), first + 1) >= 0) {
                    throw argument("edits", "oldText must occur exactly once in the current file");
                }
                matches.add(new ReplacementMatch(first, first + replacement.oldText().length(),
                        replacement.newText()));
            }
            matches.sort(Comparator.comparingInt(ReplacementMatch::start));
            for (int index = 1; index < matches.size(); index++) {
                if (matches.get(index - 1).end() > matches.get(index).start()) {
                    throw argument("edits", "oldText matches must not overlap");
                }
            }
            return List.copyOf(matches);
        }

        /**
         * 根据已排序且不重叠的原始范围一次性生成 postimage，避免每次替换改变后续匹配位置。
         */
        private static String applyReplacements(String current, List<ReplacementMatch> matches) {
            StringBuilder updated = new StringBuilder(current.length());
            int cursor = 0;
            for (ReplacementMatch match : matches) {
                updated.append(current, cursor, match.start()).append(match.newText());
                cursor = match.end();
            }
            return updated.append(current, cursor, current.length()).toString();
        }

        /**
         * edit 在成功后返回写前/写后 UTF-8 正文，因此可由 Java 精确归属。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /**
         * edit 只在目标已被 Workspace boundary 证明且可从当前 preimage 确定唯一 postimage 时留下
         * 恢复证据。预读取或匹配失败时宁可交给用户裁决，也不依据不完整信息自动重放。
         */
        @Override
        public Optional<RecoveryEvidence> prepareRecoveryEvidence(
                Invocation invocation, ExecutionContext context, CancellationToken token) {
            try {
                MutationTarget target = mutationTarget(mutation.workspaceRoot(), mutation.boundary(),
                        string(invocation, "path", Integer.MAX_VALUE), true);
                if (target.boundary() == null) {
                    return Optional.empty();
                }
                String current = BuiltInTools.readUtf8File(target.path(), token, context.deadline());
                String updated = applyReplacements(current, locateReplacements(current, replacements(invocation)));
                return Optional.of(expectedTextEvidence(target.relativePath(), updated));
            } catch (IOException | IllegalArgumentException unavailable) {
                return Optional.empty();
            }
        }

        /**
         * 所有 edit 都先在同一 preimage 中定位，重叠、重复或缺失任一目标即失败，避免增量替换改变后续匹配语义。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            MutationTarget target = mutationTarget(mutation.workspaceRoot(), mutation.boundary(),
                    string(invocation, "path", Integer.MAX_VALUE), true);
            Path path = target.path();
            String current = BuiltInTools.readUtf8File(path, token, context.deadline());
            List<ReplacementMatch> matches = locateReplacements(current, replacements(invocation));
            token.throwIfCancellationRequested();
            String updated = applyReplacements(current, matches);
            writeObserved(target, updated, mutation.writer(), token, context.deadline());
            MutationReceipt receipt = target.receipt(true, current, updated);
            return new ToolResult(ToolOutcome.SUCCEEDED, "Successfully replaced " + matches.size()
                                                         + " block(s) in the file.",
                    Optional.empty(), null, List.of(), Optional.of(receipt), Optional.empty());
        }

        /**
         * 保留每项匹配前的原始文本和替换后文本，防止位置推导与内容替换耦合。
         *
         * @param oldText 原有文本
         * @param newText 新增文本
         */
        private record Replacement(String oldText,
                                   String newText) {
        }

        /**
         * 只保存已在原始文本中确认的范围，供排序、冲突判断和一次性 postimage 构造复用。
         *
         * @param start 起始位置
         * @param end 结束位置
         * @param newText 新增文本
         */
        private record ReplacementMatch(int start,
                                        int end,
                                        String newText) {
        }
    }

    /**
     * 完整写入一个 UTF-8 文件，并通过同目录临时文件避免半写入。
     */
    private static final class WriteTool extends ToolSupport {
        private final MutationSupport mutation;

        /**
         * Workspace 只提供相对路径起点，不建立目录授权边界。
         */
        private WriteTool(Path workspaceRoot, WorkspaceBoundaryFactory workspaceBoundaries,
                          MutationWriter mutationWriter) {
            super(new ToolSpec("write", "Atomically write a complete UTF-8 file",
                    objectSchema(Map.of(
                                    "path", workspaceMutationPathProperty(),
                                    "content", unboundedStringProperty("Complete UTF-8 file content.", false)),
                            List.of("path", "content"))));
            this.mutation = new MutationSupport(workspaceRoot, workspaceBoundaries, mutationWriter);
        }

        /**
         * write 完整掌握目标 UTF-8 pre/postimage，因此不需要扫描 Git 或工作区。
         */
        @Override
        public WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.EXACT_TEXT;
        }

        /**
         * write 的完整 postimage 已包含在受检参数中，因此无需额外读取文件；工作区外目标按计划保持
         * 结果未知，避免把绝对路径写入恢复事实或扩大自动核实范围。
         */
        @Override
        public Optional<RecoveryEvidence> prepareRecoveryEvidence(
                Invocation invocation, ExecutionContext context, CancellationToken token) {
            try {
                MutationTarget target = mutationTarget(mutation.workspaceRoot(), mutation.boundary(),
                        string(invocation, "path", Integer.MAX_VALUE), false);
                if (target.boundary() == null) {
                    return Optional.empty();
                }
                String content = optionalString(invocation, "content", Integer.MAX_VALUE);
                if (content == null) {
                    return Optional.empty();
                }
                token.throwIfCancellationRequested();
                return Optional.of(expectedTextEvidence(target.relativePath(), content));
            } catch (IOException | IllegalArgumentException unavailable) {
                return Optional.empty();
            }
        }

        /**
         * 创建父目录并原子替换目标；无法安全读取旧文件时仍完成写入，但将 Workbench 收据标为不完整。
         */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            MutationTarget target = mutationTarget(mutation.workspaceRoot(), mutation.boundary(),
                    string(invocation, "path", Integer.MAX_VALUE), false);
            Path path = target.path();
            String content = optionalString(invocation, "content", Integer.MAX_VALUE);
            if (content == null) {
                throw argument("content", "must be a string; empty text is allowed");
            }
            token.throwIfCancellationRequested();
            boolean beforeExists = Files.exists(path);
            String before = "";
            boolean preimageUnavailable = false;
            if (beforeExists) {
                try {
                    before = BuiltInTools.readUtf8File(path, token, context.deadline());
                } catch (ToolSupport.ToolFailure failure) {
                    if (!java.util.Set.of(ToolSupport.Failure.FILE_TOO_LARGE, ToolSupport.Failure.FILE_NOT_UTF8,
                            ToolSupport.Failure.FILE_NOT_TEXT).contains(failure.failure())) {
                        throw failure;
                    }
                    preimageUnavailable = true;
                }
            }
            writeObserved(target, content, mutation.writer(), token, context.deadline());
            if (preimageUnavailable) {
                return new ToolResult(ToolOutcome.SUCCEEDED, "File written successfully.", Optional.empty(), null,
                        List.of(), Optional.empty(), Optional.of(MutationObservationFailure.CAPTURE_FAILED));
            }
            MutationReceipt receipt = target.receipt(beforeExists, before, content);
            return new ToolResult(ToolOutcome.SUCCEEDED, "File written successfully.",
                    Optional.empty(), null, List.of(), Optional.of(receipt), Optional.empty());
        }
    }

    /**
     * 受检目标把 IO 路径、Boundary 和稳定相对身份绑定在一起，写后复核成功前绝不生成 receipt。
     *
     * @param path 路径
     * @param boundary 边界
     * @param relativePath 相对于工作区的路径
     */
    private record MutationTarget(Path path,
                                  WorkspaceBoundaryPort boundary,
                                  String relativePath) {
        /**
         * 原子替换后再次确认叶子与父目录仍属于同一物理 Workspace。
         */
        private void revalidateAfterWrite() throws IOException {
            if (boundary != null) {
                boundary.revalidateExisting(path);
            }
        }

        /**
         * 只有受检 Workspace 路径携带 confinement 证明；外部路径仍使用无证明收据。
         */
        private MutationReceipt receipt(boolean beforeExists, String before, String after) {
            return boundary == null
                    ? MutationReceipt.of(path, beforeExists, before, true, after)
                    : MutationReceipt.confined(boundary.root(), relativePath, path,
                    beforeExists, before, true, after);
        }
    }

    /**
     * 解析 skill://name/resource，缺失资源名时默认读取 SKILL.md。
     *
     * @param name 名称
     * @param resource 资源
     */
    private record SkillAddress(String name,
                                String resource) {
        /**
         * 严格拆分 URI，禁止把不完整地址回退到普通文件路径。
         */
        private static SkillAddress parse(String uri) {
            String remainder = uri.substring("skill://".length());
            int slash = remainder.indexOf('/');
            String name = slash < 0 ? remainder : remainder.substring(0, slash);
            String resource = slash < 0 || slash == remainder.length() - 1
                    ? "SKILL.md" : remainder.substring(slash + 1);
            return new SkillAddress(name, resource);
        }
    }

    /**
     * 保存 read 的有界投影和续读位置，调用方无需从不可靠的正文长度反推截断原因。
     *
     * @param content 内容
     * @param lines 文本行列表
     * @param nextOffset 下一偏移
     * @param totalLines 文本总行数
     * @param truncated 结果是否被截断
     * @param termination 终止信息
     */
    private record LineSlice(String content,
                             int lines,
                             int nextOffset,
                             int totalLines,
                             boolean truncated,
                             String termination) {
    }
}
