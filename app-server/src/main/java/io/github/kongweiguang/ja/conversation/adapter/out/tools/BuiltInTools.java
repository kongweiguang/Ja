// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.adapter.out.tools;

import io.github.kongweiguang.ja.conversation.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;
import io.github.kongweiguang.ja.conversation.port.out.AgentPromptSession;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.conversation.port.out.ManagedAttachmentReader;
import io.github.kongweiguang.ja.foundation.concurrent.CancellationToken;
import io.github.kongweiguang.ja.foundation.json.JsonObject;
import io.github.kongweiguang.ja.foundation.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.foundation.json.JsonObjects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 装配固定文件 Tool，并只在进程预检成功时加入真实可用的 Shell Tool。 */
public final class BuiltInTools {
    private static final int MAX_TEXT_CHARACTERS = 4_000_000;

    /** 禁止绕过工厂拼装会漂移的内置 Tool 集。 */
    private BuiltInTools() {
    }

    /**
     * 将进程级 Shell 能力与 Turn 级 Skill 快照绑定；Workspace 仅作为相对路径基准。
     */
    public static ToolRegistry create(Path workspaceRoot, SkillCatalog skillCatalog,
                                      SkillCatalog.SkillSnapshot skillSnapshot, ShellCapability shellCapability,
                                      AgentPromptSession promptSession, ManagedAttachmentReader attachments) {
        Path root = Objects.requireNonNull(workspaceRoot, "workspaceRoot").toAbsolutePath().normalize();
        List<AgentTool> tools = new ArrayList<>();
        tools.add(new ReadTool(root, skillCatalog, skillSnapshot, promptSession));
        tools.add(new ReadAttachmentTool(attachments));
        Objects.requireNonNull(shellCapability, "shellCapability").profile()
                .ifPresent(profile -> tools.add(new ShellTool(profile)));
        tools.add(new EditTool(root));
        tools.add(new WriteTool(root));
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
        private final SkillCatalog.SkillSnapshot snapshot;
        private final AgentPromptSession promptSession;

        /** 冻结路径基准和 Skill revision，执行期间不再发现目录。 */
        private ReadTool(Path workspaceRoot, SkillCatalog skills, SkillCatalog.SkillSnapshot snapshot,
                         AgentPromptSession promptSession) {
            super(new ToolSpec("read", "Read a UTF-8 file or skill:// resource",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, parent, or skill:// path."),
                            "offset", property("integer", "One-based first line; defaults to 1."),
                            "limit", property("integer", "Maximum lines; defaults to 2000.")), List.of("path"))));
            this.workspaceRoot = workspaceRoot;
            this.skills = Objects.requireNonNull(skills, "skills");
            this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
            this.promptSession = Objects.requireNonNull(promptSession, "promptSession");
        }

        /**
         * Skill URI 复用冻结 Catalog；文件路径直接服从 OS 账户权限，不施加 Workspace containment。
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
                SkillCatalog.SkillDocument document = skills.read(snapshot,
                        new SkillCatalog.SkillReadRequest(address.name(), address.resource(), MAX_TEXT_CHARACTERS));
                if ("SKILL.md".equals(document.resourcePath())) {
                    AgentPromptSession.SkillActivation activation = promptSession.activateSkill(document);
                    JsonObject activationMetadata = JsonObjects.builder()
                            .putText("revision", document.revision())
                            .putText("skill", document.skillName())
                            .build();
                    if (!activation.activated()) {
                        return new ToolResult(ToolOutcome.FAILED, "", Optional.of(activationMetadata),
                                activation.errorCode());
                    }
                    return new ToolResult(ToolOutcome.SUCCEEDED, activation.receipt(),
                            Optional.of(activationMetadata), null);
                }
                content = document.content();
                metadata = JsonObjects.builder()
                        .putText("revision", document.revision())
                        .putBoolean("skill_truncated", document.truncated())
                        .build();
            } else {
                Path path = resolve(workspaceRoot, rawPath);
                if (Files.size(path) > MAX_TEXT_CHARACTERS * 4L) throw new IOException("file_too_large");
                content = Files.readString(path, StandardCharsets.UTF_8);
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

        /** Workspace 只提供相对路径起点，绝对路径与父级路径保持原生语义。 */
        private EditTool(Path workspaceRoot) {
            super(new ToolSpec("edit", "Replace one unique text occurrence in a UTF-8 file",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, or parent path."),
                            "oldText", property("string", "Text that must occur exactly once."),
                            "newText", property("string", "Replacement text.")),
                            List.of("path", "oldText", "newText"))));
            this.workspaceRoot = workspaceRoot;
        }

        /** 先确认唯一匹配再原子替换，失败时绝不产生部分写入。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            Path path = resolve(workspaceRoot, string(invocation, "path", 8_192));
            String oldText = string(invocation, "oldText", MAX_TEXT_CHARACTERS);
            String newText = optionalString(invocation, "newText", MAX_TEXT_CHARACTERS);
            if (newText == null) throw new IllegalArgumentException("invalid argument: newText");
            String current = Files.readString(path, StandardCharsets.UTF_8);
            int first = current.indexOf(oldText);
            if (first < 0) throw new IOException("old_text_not_found");
            if (current.indexOf(oldText, first + oldText.length()) >= 0) throw new IOException("old_text_not_unique");
            token.throwIfCancellationRequested();
            atomicWrite(path, current.substring(0, first) + newText + current.substring(first + oldText.length()));
            return new ToolResult(ToolOutcome.SUCCEEDED, "edited " + path,
                    Optional.of(JsonObjects.builder().putText("path", path.toString()).build()), null);
        }
    }

    /** 完整写入一个 UTF-8 文件，并通过同目录临时文件避免半写入。 */
    private static final class WriteTool extends ToolSupport {
        private final Path workspaceRoot;

        /** Workspace 只提供相对路径起点，不建立目录授权边界。 */
        private WriteTool(Path workspaceRoot) {
            super(new ToolSpec("write", "Atomically write a complete UTF-8 file",
                    objectSchema(Map.of(
                            "path", property("string", "Relative, absolute, or parent path."),
                            "content", property("string", "Complete UTF-8 file content.")),
                            List.of("path", "content"))));
            this.workspaceRoot = workspaceRoot;
        }

        /** 创建父目录并原子替换目标；不向模型暴露 CAS、revision 或文件锁协议。 */
        @Override
        ToolResult executeChecked(Invocation invocation, ExecutionContext context, CancellationToken token)
                throws IOException {
            Path path = resolve(workspaceRoot, string(invocation, "path", 8_192));
            String content = optionalString(invocation, "content", MAX_TEXT_CHARACTERS);
            if (content == null) throw new IllegalArgumentException("invalid argument: content");
            token.throwIfCancellationRequested();
            atomicWrite(path, content);
            return new ToolResult(ToolOutcome.SUCCEEDED, "wrote " + path,
                    Optional.of(JsonObjects.builder().putText("path", path.toString())
                            .putNumber("characters", content.length()).build()), null);
        }
    }

    /** 相对路径和 .. 只相对 Workspace 解析，绝对路径保持原样且不做 containment 检查。 */
    private static Path resolve(Path workspaceRoot, String rawPath) {
        Path candidate = Path.of(rawPath);
        return (candidate.isAbsolute() ? candidate : workspaceRoot.resolve(candidate)).normalize();
    }

    /** 临时文件位于目标目录；不支持 ATOMIC_MOVE 时仍使用单次 replace。 */
    private static void atomicWrite(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("file_parent_unavailable");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".ja-write-", ".tmp");
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
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
