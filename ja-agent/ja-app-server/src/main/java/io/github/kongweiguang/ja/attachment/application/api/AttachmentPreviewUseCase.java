// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.application.api;

import io.github.kongweiguang.ja.attachment.domain.model.AttachmentMetadata;

import java.util.Objects;

/**
 * 附件预览的受控入站端口；session 只代表短期读取授权，不公开内容寻址或物理存储事实。
 */
public interface AttachmentPreviewUseCase {
    /**
     * 依据当前 Workspace 草稿或 Thread 绑定建立五分钟空闲 session。
     */
    PreviewDescriptor openPreview(PreviewOpenRequest request);

    /**
     * 从已授权 session 读取最多 64 KiB，文本预览总量由服务限制为前 1 MiB。
     */
    PreviewReadResult readPreview(PreviewReadRequest request);

    /**
     * 幂等关闭 session；有效但已关闭的 identity 不泄露其历史存在性。
     */
    void closePreview(String previewSessionId);

    /**
     * 首版只允许图片与严格 UTF-8 文本，避免为未实现格式提供假入口。
     */
    enum PreviewKind {
        /**
         * 图片内容由 Rust 继续执行安全解码、衍生物预算与受控协议发布。
         */
        IMAGE,
        /**
         * 严格 UTF-8 文本由 Java 截断并按完整码点分段，再交给只读编辑器。
         */
        TEXT
    }

    /**
     * Wire 的 `draft` 与 `thread` 分支在领域端保持封闭，新增授权类型必须显式改动调用方。
     */
    sealed interface Authorization permits DraftAuthorization, ThreadAuthorization {
    }

    /**
     * 打开请求使用显式 tagged authorization，避免 nullable 字段形成含混授权组合。
     *
     * @param attachmentId 附件标识
     * @param authorization 授权
     */
    record PreviewOpenRequest(String attachmentId,
                              Authorization authorization) {
        /**
         * 附件 identity 与授权对象都必须存在，具体可见性由 repository 权威判断。
         */
        public PreviewOpenRequest {
            Objects.requireNonNull(attachmentId, "attachmentId");
            Objects.requireNonNull(authorization, "authorization");
        }
    }

    /**
     * DRAFT 预览必须同时匹配当前打开 Workspace，不能仅凭 attachmentId 建立 session。
     *
     * @param workspaceId 工作区稳定标识
     */
    record DraftAuthorization(String workspaceId) implements Authorization {
        /**
         * Workspace identity 由 transport 先验证当前打开状态，领域层仍要求非空。
         */
        public DraftAuthorization {
            Objects.requireNonNull(workspaceId, "workspaceId");
        }
    }

    /**
     * Thread 预览覆盖其排队预留与消息附件，猜中 attachmentId 仍不能跨会话访问。
     *
     * @param threadId 所属对话的稳定标识
     */
    record ThreadAuthorization(String threadId) implements Authorization {
        /**
         * Thread identity 由 transport 做格式校验，领域层保留非空不变量。
         */
        public ThreadAuthorization {
            Objects.requireNonNull(threadId, "threadId");
        }
    }

    /**
     * 打开结果只发布 UI 所需安全元数据，不得增加 hash、blob key 或路径。
     *
     * @param previewSessionId 预览会话标识
     * @param attachmentId 附件标识
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param previewKind 预览类型
     */
    record PreviewDescriptor(String previewSessionId,
                             String attachmentId,
                             String displayName,
                             long sizeBytes,
                             AttachmentMetadata.MediaKind mediaKind,
                             String mediaType,
                             PreviewKind previewKind) {
        /**
         * 预览描述必须自洽，调用方无需依赖附件持久化领域对象。
         */
        public PreviewDescriptor {
            Objects.requireNonNull(previewSessionId, "previewSessionId");
            Objects.requireNonNull(attachmentId, "attachmentId");
            Objects.requireNonNull(displayName, "displayName");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("invalid preview size");
            }
            Objects.requireNonNull(mediaKind, "mediaKind");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(previewKind, "previewKind");
        }
    }

    /**
     * 分段输入独立限制 offset 和窗口，防止 session 退化为任意大对象读取。
     *
     * @param previewSessionId 预览会话标识
     * @param offsetBytes 读取起点的字节偏移
     * @param limitBytes 上限字节数
     */
    record PreviewReadRequest(String previewSessionId,
                              long offsetBytes,
                              int limitBytes) {
        /**
         * UTF-8 最长码点需要四字节窗口，64 KiB 是跨层固定上限。
         */
        public PreviewReadRequest {
            Objects.requireNonNull(previewSessionId, "previewSessionId");
            if (offsetBytes < 0 || limitBytes < 4 || limitBytes > 64 * 1024) {
                throw new IllegalArgumentException("invalid preview range");
            }
        }
    }

    /**
     * 内容统一用 Base64 传给 Rust，避免二进制进入 JSON 字符串或路径能力泄漏到 WebView。
     *
     * @param previewSessionId 预览会话标识
     * @param offsetBytes 读取起点的字节偏移
     * @param nextOffsetBytes 下次读取的字节偏移
     * @param contentBase64 Base64 编码的内容
     * @param eof 是否到达输入末尾
     * @param truncated 结果是否被截断
     */
    record PreviewReadResult(String previewSessionId,
                             long offsetBytes,
                             long nextOffsetBytes,
                             String contentBase64,
                             boolean eof,
                             boolean truncated) {
        /**
         * offset 单调且内容非空约束由服务保证，EOF 允许零长度结束段。
         */
        public PreviewReadResult {
            Objects.requireNonNull(previewSessionId, "previewSessionId");
            if (offsetBytes < 0 || nextOffsetBytes < offsetBytes) {
                throw new IllegalArgumentException("invalid preview result range");
            }
            Objects.requireNonNull(contentBase64, "contentBase64");
        }
    }
}
