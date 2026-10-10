// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.application.gateway.ManagedToolMediaImporter;
import io.github.kongweiguang.ja.conversation.domain.model.TurnOrigin;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.workspace.infrastructure.filesystem.WorkspaceBoundaryFactoryAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 Workspace 图片读取只能产出签名识别的受管媒体引用。 */
final class ViewImageToolTest {
    @TempDir
    Path workspace;

    /** 图片像素字节必须原样进入附件导入端口，并以 ToolResult media 引用回传。 */
    @Test
    void importsWorkspaceImageAsTypedToolMedia() throws IOException {
        Path imagePath = workspace.resolve("screenshots/capture.png");
        Files.createDirectories(imagePath.getParent());
        byte[] image = png();
        Files.write(imagePath, image);
        AtomicReference<ImportCall> imported = new AtomicReference<>();
        ManagedToolMediaImporter importer = (workspaceId, displayName, mediaType, content) -> {
            imported.set(new ImportCall(workspaceId, displayName, mediaType, content.clone()));
            return new ToolMediaReference("att_image", mediaType, displayName, content.length);
        };
        ViewImageTool tool = new ViewImageTool(new WorkspaceBoundaryFactoryAdapter(), importer);

        JaTool.ToolResult result = tool.execute(invocation("screenshots/capture.png"), context(),
                CancellationToken.none()).toCompletableFuture().join();

        assertEquals(ToolOutcome.SUCCEEDED, result.outcome());
        assertEquals("image/png", result.media().getFirst().mediaType());
        assertEquals("capture.png", result.media().getFirst().displayName());
        assertEquals("Loaded workspace image screenshots/capture.png for model inspection.", result.content());
        assertEquals("ws_fixture", imported.get().workspaceId());
        assertEquals("image/png", imported.get().mediaType());
        assertArrayEquals(image, imported.get().content());
        assertEquals(JaTool.WorkspaceMutationMode.NONE, tool.workspaceMutationMode());
    }

    /** 非图片或伪装内容必须在附件导入前拒绝，避免把任意文件写入受管媒体存储。 */
    @Test
    void rejectsNonImageContentWithoutCallingImporter() throws IOException {
        Files.writeString(workspace.resolve("notes.txt"), "not an image");
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ViewImageTool tool = new ViewImageTool(new WorkspaceBoundaryFactoryAdapter(),
                (workspaceId, displayName, mediaType, content) -> {
                    called.set(true);
                    return new ToolMediaReference("att_image", mediaType, displayName, content.length);
                });

        JaTool.ToolResult result = tool.execute(invocation("notes.txt"), context(),
                CancellationToken.none()).toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("file_not_image", result.errorCode());
        assertFalse(called.get());
    }

    /** Workspace 边界必须在读取字节与创建附件草稿前拒绝越界路径。 */
    @Test
    void rejectsPathsOutsideWorkspace() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ViewImageTool tool = new ViewImageTool(new WorkspaceBoundaryFactoryAdapter(),
                (workspaceId, displayName, mediaType, content) -> {
                    called.set(true);
                    return new ToolMediaReference("att_image", mediaType, displayName, content.length);
                });

        JaTool.ToolResult result = tool.execute(invocation("../outside.png"), context(),
                CancellationToken.none()).toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("tool_access_denied", result.errorCode());
        assertFalse(called.get());
    }

    /** 文件系统根没有文件名，必须在读取和受管附件导入前明确拒绝。 */
    @Test
    void rejectsFilesystemRootWithoutImportingIt() {
        Path fileSystemRoot = workspace.toAbsolutePath().getRoot();
        assertNotNull(fileSystemRoot);
        assertNull(fileSystemRoot.getFileName());
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        ViewImageTool tool = new ViewImageTool(new WorkspaceBoundaryFactoryAdapter(),
                (workspaceId, displayName, mediaType, content) -> {
                    called.set(true);
                    return new ToolMediaReference("att_image", mediaType, displayName, content.length);
                });

        JaTool.ToolResult result = tool.execute(invocation("."), context(fileSystemRoot),
                CancellationToken.none()).toCompletableFuture().join();

        assertEquals(ToolOutcome.FAILED, result.outcome());
        assertEquals("path_is_directory", result.errorCode());
        assertFalse(called.get());
    }

    /** 使用包含 PNG magic 与 IHDR 签名的固定载荷，避免依赖平台图像解码器。 */
    private static byte[] png() {
        byte[] image = new byte[24];
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, image, 0, signature.length);
        image[11] = 13;
        image[12] = 'I';
        image[13] = 'H';
        image[14] = 'D';
        image[15] = 'R';
        return image;
    }

    /** 固定调用身份和 Workspace，验证路径与图片内容只在受控上下文内流转。 */
    private JaTool.ExecutionContext context() {
        return context(workspace.toAbsolutePath());
    }

    /** 固定调用身份并允许边界测试把物理根作为 Workspace 根，不扩大任何文件访问权限。 */
    private JaTool.ExecutionContext context(Path workspaceRoot) {
        AgentIdentity identity = new AgentIdentity("tenant_fixture", "thr_fixture", "turn_fixture", "user_fixture");
        String callId = "call_view_image";
        return new JaTool.ExecutionContext(identity, callId, identity.toolCallKey(callId),
                identity.threadId(), identity.turnId(), workspaceRoot, AccessMode.FULL_ACCESS,
                "cfg_fixture", Instant.now().plusSeconds(15), "ws_fixture", null, null, TurnOrigin.USER);
    }

    /** 构造只包含相对 Workspace 路径的 Tool 调用，不伪造额外权限属性。 */
    private static AgentTool.Invocation invocation(String path) {
        return new AgentTool.Invocation("call_view_image", "view_image",
                JsonObjects.builder().putText("path", path).build(), 0);
    }

    /**
     * 复制导入实参快照，测试可观察实际 bytes 而不与被测数组共享可变引用。
     *
     * @param workspaceId 媒体导入所属的 Workspace 标识
     * @param displayName 导入媒体的展示名称
     * @param mediaType 导入媒体的 MIME 类型
     * @param content 导入的媒体正文副本
     */
    private record ImportCall(String workspaceId,
                              String displayName,
                              String mediaType,
                              byte[] content) {
        /** 测试记录冻结 byte[]，避免后续校验观察到调用方修改。 */
        private ImportCall {
            content = Arrays.copyOf(content, content.length);
        }
    }
}
