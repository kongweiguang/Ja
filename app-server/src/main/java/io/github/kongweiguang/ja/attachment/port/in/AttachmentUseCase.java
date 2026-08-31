// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.port.in;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;

import java.time.Instant;
import java.util.Objects;

/**
 * 受管附件的唯一入站端口；调用方只能使用 opaque identity，不能提交任意物理路径。
 */
public interface AttachmentUseCase {
    /** 从 Rust-owned staging 导入一份 24 小时草稿。 */
    AttachmentMetadata importDraft(ImportRequest request);

    /** 丢弃尚未绑定的草稿；绑定或过期附件必须失败关闭。 */
    AttachmentMetadata discard(String attachmentId, Instant discardedAt);

    /** 从当前 Thread 可见绑定中读取最多 64 KiB 的有界片段。 */
    ReadResult read(ReadRequest request);

    /** 立即执行一次草稿过期与 orphan 回收，供启动和测试使用。 */
    void collectGarbage();

    /** Rust 私有 RPC 提供的完整预期事实；ingressToken 不会进入返回值或持久化。 */
    record ImportRequest(String ingressToken, String workspaceId, String displayName,
                         long sizeBytes, String sha256, Instant importedAt) {
        /** 入口只接受 Rust 随机 token 和产品固定上限，Java 随后重新读取并验证实际文件。 */
        public ImportRequest {
            if (ingressToken == null || !ingressToken.matches("[0-9a-f]{32}")) {
                throw new IllegalArgumentException("invalid ingress token");
            }
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(displayName, "displayName");
            if (sizeBytes < 0 || sizeBytes > 100L * 1024 * 1024
                || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid import content identity");
            }
            Objects.requireNonNull(importedAt, "importedAt");
        }
    }

    /** Tool 读取请求同时带入执行上下文的 Thread 身份，防止跨会话猜测 attachmentId。 */
    record ReadRequest(String attachmentId, String threadId, long offsetBytes, int maxBytes) {
        /** 最大窗口固定为 64 KiB，offset 使用字节以支持 binary range。 */
        public ReadRequest {
            Objects.requireNonNull(attachmentId, "attachmentId");
            Objects.requireNonNull(threadId, "threadId");
            if (offsetBytes < 0 || maxBytes < 4 || maxBytes > 64 * 1024) {
                throw new IllegalArgumentException("invalid attachment range");
            }
        }
    }

    /** 读取结果只带安全元数据和有界内容；binary 使用 Base64，绝不返回物理路径。 */
    record ReadResult(AttachmentMetadata metadata, long offsetBytes, long nextOffsetBytes,
                      boolean endOfFile, String encoding, String content) {
        /** 结果的 offset 必须单调，encoding 只允许两种已实现 codec。 */
        public ReadResult {
            Objects.requireNonNull(metadata, "metadata");
            if (offsetBytes < 0 || nextOffsetBytes < offsetBytes
                || !Objects.equals(encoding, "utf-8") && !Objects.equals(encoding, "base64")
                || content == null) {
                throw new IllegalArgumentException("invalid attachment read result");
            }
        }
    }
}
