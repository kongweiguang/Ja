// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.transport.rpc.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;
import io.github.kongweiguang.ja.attachment.port.in.AttachmentUseCase;
import io.github.kongweiguang.ja.foundation.runtime.SidecarConfiguration;
import io.github.kongweiguang.ja.transport.rpc.protocol.JaRpcException;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcCommand;
import io.github.kongweiguang.ja.transport.rpc.protocol.RpcMethod;
import io.github.kongweiguang.ja.transport.rpc.runtime.RpcSession;
import io.github.kongweiguang.ja.transport.rpc.runtime.StdioWriter;
import io.github.kongweiguang.ja.transport.rpc.support.RpcTestBindings;
import io.github.kongweiguang.ja.transport.rpc.support.TestConfigurationPorts;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.in.WorkspaceUseCase;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static io.github.kongweiguang.ja.transport.rpc.runtime.RpcRuntimeTestAccess.markReady;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证附件 JA-RPC 只交换 opaque identity 与脱敏 metadata。 */
final class AttachmentHandlerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");

    /** 覆盖导入与丢弃成功投影，并证明 hash、token 与路径均不会出现在响应。 */
    @Test
    void mapsStrictLifecycleWithoutContentIdentityLeak() throws Exception {
        RecordingAttachments attachments = new RecordingAttachments();
        try (Harness harness = new Harness(attachments)) {
            ObjectNode imported = harness.invoke(RpcMethod.ATTACHMENT_IMPORT, MAPPER.createObjectNode()
                    .put("ingressToken", "0123456789abcdef0123456789abcdef")
                    .put("workspaceId", "ws_demo").put("displayName", "notes.txt").put("sizeBytes", 24)
                    .put("sha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"));
            Set<String> fields = new java.util.HashSet<>();
            imported.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("attachmentId", "workspaceId", "displayName", "sizeBytes", "mediaKind",
                    "mediaType", "state", "createdAt", "expiresAt", "boundMessageId"), fields);
            assertEquals("draft", imported.path("state").textValue());
            assertFalse(imported.toString().contains("sha256"));
            assertFalse(imported.toString().contains("ingressToken"));
            assertEquals("0123456789abcdef0123456789abcdef", attachments.imported.ingressToken());

            ObjectNode discarded = harness.invoke(RpcMethod.ATTACHMENT_DISCARD,
                    MAPPER.createObjectNode().put("attachmentId", "att_demo"));
            assertEquals("discarded", discarded.path("state").textValue());
        }
    }

    /** 旧路径、额外字段和非小写内容 identity 必须在调用附件端口前失败。 */
    @Test
    void rejectsPathExtraFieldAndMalformedIdentity() throws Exception {
        RecordingAttachments attachments = new RecordingAttachments();
        try (Harness harness = new Harness(attachments)) {
            ObjectNode withPath = MAPPER.createObjectNode().put("ingressToken", "0".repeat(32))
                    .put("workspaceId", "ws_demo").put("displayName", "notes.txt").put("sizeBytes", 24)
                    .put("sha256", "0".repeat(64)).put("path", "C:/private/notes.txt");
            assertThrows(JaRpcException.class, () -> harness.invoke(RpcMethod.ATTACHMENT_IMPORT, withPath));
            ObjectNode uppercase = withPath.deepCopy();
            uppercase.remove("path");
            uppercase.put("sha256", "A".repeat(64));
            assertThrows(JaRpcException.class, () -> harness.invoke(RpcMethod.ATTACHMENT_IMPORT, uppercase));
            assertEquals(null, attachments.imported);
        }
    }

    /** 记录端口调用并返回确定性领域事实，不读取任何真实 staging。 */
    private static final class RecordingAttachments implements AttachmentUseCase {
        private ImportRequest imported;

        /** 保存导入意图，响应只使用测试公开 metadata。 */
        @Override public AttachmentMetadata importDraft(ImportRequest request) {
            imported = request;
            return metadata(AttachmentMetadata.Status.DRAFT);
        }

        /** 丢弃投影进入明确终态且不保留 boundMessageId。 */
        @Override public AttachmentMetadata discard(String attachmentId, Instant discardedAt) {
            return metadata(AttachmentMetadata.Status.DISCARDED);
        }

        /** transport 不允许直接读取内容。 */
        @Override public ReadResult read(ReadRequest request) { throw new AssertionError("unexpected read"); }

        /** transport 不负责触发后台回收。 */
        @Override public void collectGarbage() { throw new AssertionError("unexpected garbage collection"); }

        /** 构造与 Wire 状态一致的脱敏领域事实。 */
        private static AttachmentMetadata metadata(AttachmentMetadata.Status status) {
            return new AttachmentMetadata("att_demo", "ws_demo", "notes.txt", 24, "0".repeat(64),
                    AttachmentMetadata.MediaKind.TEXT, "text/plain", status, NOW,
                    NOW.plusSeconds(86_400), null);
        }
    }

    /** 使用真实 session ready gate 和拒绝式非目标端口运行 Handler。 */
    private static final class Harness implements AutoCloseable {
        private final StdioWriter writer;
        private final RpcSession session;
        private final AttachmentHandler handler;

        /** 仅放行指定 Workspace identity 与附件端口，其余域调用立即失败。 */
        private Harness(AttachmentUseCase attachments) {
            Path root = Path.of(System.getProperty("java.io.tmpdir"), "ja-attachment-handler-test").toAbsolutePath();
            writer = new StdioWriter(new ByteArrayOutputStream(), MAPPER, 16_384);
            Workspace workspace = new Workspace("ws_demo", root.resolve("workspace"), "Demo",
                    Workspace.Trust.TRUSTED, 1);
            WorkspaceUseCase workspaces = (WorkspaceUseCase) Proxy.newProxyInstance(
                    AttachmentHandlerTest.class.getClassLoader(), new Class<?>[]{WorkspaceUseCase.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("requireOpenWorkspace")
                                && arguments != null && arguments.length == 1
                                && workspace.workspaceId().equals(arguments[0])) return workspace;
                        throw new AssertionError("unexpected workspace call: " + method.getName());
                    });
            SidecarConfiguration sidecar = new SidecarConfiguration(root.resolve("home"), root.resolve("data"),
                    root.resolve("run"), root.resolve("logs"));
            session = new RpcSession(sidecar, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), writer,
                    ignored -> RpcTestBindings.create(workspaces, null, null, null, null, attachments, null),
                    TestConfigurationPorts.unavailable(), 41);
            session.initialize();
            markReady(session, "f".repeat(32));
            handler = new AttachmentHandler(session);
        }

        /** 通过生产 command DTO 调用 Handler，保留异步接口与 deep-copy 边界。 */
        private ObjectNode invoke(RpcMethod method, ObjectNode params) {
            return handler.handle(new RpcCommand(method, params)).toCompletableFuture().join();
        }

        /** 先关闭 session owner，再关闭 stdout writer，避免后台通知残留。 */
        @Override public void close() {
            try { session.close(); } finally { writer.close(); }
        }
    }
}
