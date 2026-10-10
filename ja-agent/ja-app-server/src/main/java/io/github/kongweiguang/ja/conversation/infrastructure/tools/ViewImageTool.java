// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool.Invocation;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryFactory;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceBoundaryPort;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Workspace 内受支持的静态图片导入为 ToolResult 媒体，避免图片字节被文本化或绕过附件 owner。
 */
final class ViewImageTool extends ToolSupport {
    private static final int MAX_IMAGE_BYTES = 50_000_000;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};

    private final WorkspaceBoundaryFactory workspaceBoundaries;
    private final ManagedToolMediaImporter mediaImporter;

    /**
     * 图片读取必须同时具备 Workspace ACL 与受管附件导入能力，避免把路径或存储细节跨层传递。
     */
    ViewImageTool(WorkspaceBoundaryFactory workspaceBoundaries, ManagedToolMediaImporter mediaImporter) {
        super(new ToolSpec("view_image", "Read a workspace image and return its pixels to the model",
                objectSchema(Map.of(
                        "path", requiredStringProperty("Workspace-relative path to a PNG, JPEG, GIF, or WebP image.")),
                        List.of("path"))));
        this.workspaceBoundaries = Objects.requireNonNull(workspaceBoundaries, "workspaceBoundaries");
        this.mediaImporter = Objects.requireNonNull(mediaImporter, "mediaImporter");
    }

    /**
     * 图片读取不改变 Workspace 内容；附件草稿只在本次 ToolResult 提交时取得 Thread 可见性。
     */
    @Override
    public ToolSideEffect sideEffect() {
        return ToolSideEffect.READ_ONLY;
    }

    /**
     * 受管附件草稿不属于 Workspace 文件变化，因此可准确声明无 Workspace mutation。
     */
    @Override
    public JaTool.WorkspaceMutationMode workspaceMutationMode() {
        return JaTool.WorkspaceMutationMode.NONE;
    }

    /**
     * 只读取当前 Workspace 中通过 ACL 复核且具有文件名的普通文件，避免根路径成为无名媒体引用。
     */
    @Override
    ToolResult executeChecked(Invocation invocation, JaTool.ExecutionContext context, CancellationToken token)
            throws IOException {
        String requestedPath = requiredString(invocation, "path", Integer.MAX_VALUE,
                "must be a non-empty workspace-relative image path");
        WorkspaceBoundaryPort boundary = workspaceBoundaries.open(context.workspaceRoot());
        Path admitted = boundary.existing(requestedPath);
        Path fileName = admitted.getFileName();
        if (fileName == null) {
            throw failure(Failure.PATH_IS_DIRECTORY);
        }
        byte[] image = readImage(admitted, boundary, token, context.deadline());
        String mediaType = mediaType(image);
        String displayName = fileName.toString();
        ToolMediaReference reference = mediaImporter.importImage(
                context.workspaceId(), displayName, mediaType, image);
        return new ToolResult(ToolOutcome.SUCCEEDED,
                "Loaded workspace image " + boundary.relative(admitted) + " for model inspection.",
                Optional.empty(), null).withMedia(List.of(reference));
    }

    /**
     * 以固定字节预算读取并在 IO 前后复核 Workspace 与文件身份，限制大文件和竞态替换的影响。
     */
    private static byte[] readImage(Path admitted, WorkspaceBoundaryPort boundary,
                                    CancellationToken token, Instant deadline) throws IOException {
        Path verified = boundary.revalidateExisting(admitted);
        BasicFileAttributes before = Files.readAttributes(verified, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (before.isDirectory()) {
            throw failure(Failure.PATH_IS_DIRECTORY);
        }
        if (!before.isRegularFile() || before.isSymbolicLink()) {
            throw failure(Failure.PATH_NOT_REGULAR_FILE);
        }
        if (before.size() > MAX_IMAGE_BYTES) {
            throw failure(Failure.FILE_TOO_LARGE);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(before.size(), 64 * 1024L));
        byte[] buffer = new byte[16 * 1024];
        try (InputStream input = Files.newInputStream(verified, LinkOption.NOFOLLOW_LINKS)) {
            while (true) {
                token.throwIfCancellationRequested();
                throwIfDeadlineExceeded(deadline);
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                if ((long) output.size() + count > MAX_IMAGE_BYTES) {
                    throw failure(Failure.FILE_TOO_LARGE);
                }
                output.write(buffer, 0, count);
            }
        }
        Path revalidated = boundary.revalidateExisting(verified);
        BasicFileAttributes after = Files.readAttributes(revalidated, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!verified.equals(revalidated)
            || before.size() != after.size()
            || !before.lastModifiedTime().equals(after.lastModifiedTime())
            || before.fileKey() != null && !before.fileKey().equals(after.fileKey())) {
            throw new IOException("workspace_image_changed_during_read");
        }
        return output.toByteArray();
    }

    /**
     * MIME 类型必须由图片签名决定；只允许和受管附件存储、Provider 编码器一致的格式闭集。
     */
    private static String mediaType(byte[] image) throws IOException {
        if (image.length >= PNG_SIGNATURE.length
            && Arrays.equals(Arrays.copyOf(image, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            return "image/png";
        }
        if (image.length >= 3 && (image[0] & 0xff) == 0xff
            && (image[1] & 0xff) == 0xd8 && (image[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (image.length >= 6 && image[0] == 'G' && image[1] == 'I' && image[2] == 'F'
            && image[3] == '8' && (image[4] == '7' || image[4] == '9') && image[5] == 'a') {
            return "image/gif";
        }
        if (image.length >= 12 && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F'
            && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "image/webp";
        }
        throw failure(Failure.FILE_NOT_IMAGE);
    }
}
