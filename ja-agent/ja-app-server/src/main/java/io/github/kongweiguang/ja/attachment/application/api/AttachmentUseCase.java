// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.application.api;

import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 受管附件的唯一入站端口；调用方只能使用 opaque identity，不能提交任意物理路径。
 */
public interface AttachmentUseCase {
    /**
     * 从 Rust-owned staging 导入一份 24 小时草稿。
     */
    AttachmentMetadata importDraft(ImportRequest request);

    /**
     * 从受信应用内存内容导入已检测类型的图片草稿；调用方不能指定路径或直接授予 Thread 可见性。
     */
    AttachmentMetadata importMcpImage(String workspaceId, String displayName, String mediaType, byte[] content);

    /**
     * 丢弃尚未绑定的草稿；绑定或过期附件必须失败关闭。
     */
    AttachmentMetadata discard(String attachmentId, Instant discardedAt);

    /**
     * 从当前 Thread 可见绑定中读取最多 64 KiB 的有界片段。
     */
    ReadResult read(ReadRequest request);

    /**
     * 最多读取 10 个当前 Thread 已绑定附件的受授权元数据，不打开 blob 或物化附件正文。
     */
    List<AttachmentMetadata> readAuthorizedMetadata(String threadId, List<String> attachmentIds);

    /**
     * 立即执行一次草稿过期与 orphan 回收，供启动和测试使用。
     */
    void collectGarbage();

    /**
     * Rust 私有 RPC 提供的完整预期事实；ingressToken 不会进入返回值或持久化。
     *
     * @param ingressToken 附件导入的临时授权令牌
     * @param workspaceId 工作区稳定标识
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param sha256 内容的 SHA-256 摘要
     * @param importedAt 导入时间
     */
    record ImportRequest(String ingressToken,
                         String workspaceId,
                         String displayName,
                         long sizeBytes,
                         String sha256,
                         Instant importedAt) {
        /**
         * 入口只接受 Rust 随机 token 和产品固定上限，Java 随后重新读取并验证实际文件。
         */
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

    /**
     * Tool 读取请求同时带入执行上下文的 Thread 身份，防止跨会话猜测 attachmentId。
     *
     * @param attachmentId 附件标识
     * @param threadId 所属对话的稳定标识
     * @param offsetBytes 读取起点的字节偏移
     * @param maxBytes 单次读取的字节上限
     */
    record ReadRequest(String attachmentId,
                       String threadId,
                       long offsetBytes,
                       int maxBytes) {
        /**
         * 最大窗口固定为 64 KiB，offset 使用字节以支持 binary range。
         */
        public ReadRequest {
            Objects.requireNonNull(attachmentId, "attachmentId");
            Objects.requireNonNull(threadId, "threadId");
            if (offsetBytes < 0 || maxBytes < 4 || maxBytes > 64 * 1024) {
                throw new IllegalArgumentException("invalid attachment range");
            }
        }
    }

    /**
     * 读取结果只带安全元数据和有界内容；binary 使用 Base64，绝不返回物理路径。
     *
     * @param metadata 元数据
     * @param offsetBytes 读取起点的字节偏移
     * @param nextOffsetBytes 下次读取的字节偏移
     * @param endOfFile 是否到达文件末尾
     * @param encoding 编码
     * @param content 内容
     */
    record ReadResult(AttachmentMetadata metadata,
                      long offsetBytes,
                      long nextOffsetBytes,
                      boolean endOfFile,
                      String encoding,
                      String content) {
        /**
         * 结果的 offset 必须单调，encoding 只允许两种已实现 codec。
         */
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
