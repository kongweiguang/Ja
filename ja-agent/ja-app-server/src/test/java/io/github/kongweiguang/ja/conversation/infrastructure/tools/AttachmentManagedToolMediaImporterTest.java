// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.infrastructure.tools;

import io.github.kongweiguang.ja.attachment.application.api.AttachmentUseCase;
import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证媒体适配器保留附件 owner 返回的授权身份与 MIME 元数据。 */
final class AttachmentManagedToolMediaImporterTest {
    /** 受管附件 owner 的图片草稿映射为最小 ToolMediaReference。 */
    @Test
    void mapsImportedDraftToToolMediaReference() {
        byte[] content = {1, 2, 3};
        Instant now = Instant.now();
        AtomicReference<List<Object>> observed = new AtomicReference<>();
        AttachmentMetadata expected = metadata(now, "ws_fixture", "capture.png", "image/png", 3,
                AttachmentMetadata.MediaKind.IMAGE, AttachmentMetadata.Status.DRAFT);
        AttachmentUseCase attachments = attachmentUseCase((workspaceId, name, mediaType, bytes) -> {
            observed.set(List.of(workspaceId, name, mediaType, bytes.length));
            return expected;
        });

        ToolMediaReference reference = new AttachmentManagedToolMediaImporter(attachments)
                .importImage("ws_fixture", "capture.png", "image/png", content);

        assertEquals(List.of("ws_fixture", "capture.png", "image/png", 3), observed.get());
        assertEquals(new ToolMediaReference("att_image", "image/png", "capture.png", 3), reference);
    }

    /** 非图片或已绑定的附件记录不能被改投影成新 ToolResult 媒体。 */
    @Test
    void rejectsMetadataOutsideImportContract() {
        Instant now = Instant.now();
        AttachmentUseCase attachments = attachmentUseCase((workspaceId, name, mediaType, bytes) ->
                metadata(now, workspaceId, name, mediaType, bytes.length,
                        AttachmentMetadata.MediaKind.IMAGE, AttachmentMetadata.Status.BOUND));

        assertThrows(IllegalStateException.class, () -> new AttachmentManagedToolMediaImporter(attachments)
                .importImage("ws_fixture", "capture.png", "image/png", new byte[]{1}));
    }

    /** 只实现本适配器会调用的应用入口，意外触碰其它附件能力立即使测试失败。 */
    private static AttachmentUseCase attachmentUseCase(ImageImporter importer) {
        return (AttachmentUseCase) Proxy.newProxyInstance(AttachmentUseCase.class.getClassLoader(),
                new Class<?>[]{AttachmentUseCase.class}, (proxy, method, arguments) -> {
                    if ("importMcpImage".equals(method.getName())) {
                        return importer.importImage((String) arguments[0], (String) arguments[1],
                                (String) arguments[2], (byte[]) arguments[3]);
                    }
                    throw new AssertionError("unexpected attachment operation: " + method.getName());
                });
    }

    /** 工具导入 fixture 只模拟消息绑定，Thread owner 由 canonical 参数显式留空。 */
    private static AttachmentMetadata metadata(Instant now, String workspaceId, String name, String mediaType,
                                              long sizeBytes, AttachmentMetadata.MediaKind kind,
                                              AttachmentMetadata.Status status) {
        return new AttachmentMetadata("att_image", workspaceId, name, sizeBytes, "a".repeat(64), kind,
                mediaType, status, now, now.plusSeconds(3600),
                status == AttachmentMetadata.Status.BOUND ? "item_bound" : null, null);
    }

    /** 测试注入的极窄图片导入行为。 */
    @FunctionalInterface
    private interface ImageImporter {
        /** 返回指定附件元数据，并暴露输入以检查适配器的参数转发。 */
        AttachmentMetadata importImage(String workspaceId, String displayName, String mediaType, byte[] content);
    }
}
