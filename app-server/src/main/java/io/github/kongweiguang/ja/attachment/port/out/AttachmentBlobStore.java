// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.attachment.port.out;

import io.github.kongweiguang.ja.attachment.domain.AttachmentMetadata;

import java.util.Objects;
import java.util.Set;

/**
 * 内容寻址文件端口；实现只能从受信根和 opaque identity 派生路径。
 */
public interface AttachmentBlobStore {
    /** 复核 staging 并导入内容寻址 blob，重复 hash 必须验证既有内容。 */
    ImportedBlob importStaged(String ingressToken, long expectedSize, String expectedSha256,
                              String displayName);

    /** 读取已由数据库授权的 blob 片段。 */
    byte[] readRange(String sha256, long offsetBytes, int maxBytes);

    /** 删除一个数据库已确认无引用的 blob；不存在视为幂等成功。 */
    void deleteBlob(String sha256);

    /** 清理不在数据库引用集合内且已越过保护期的 orphan 文件。 */
    void deleteOrphans(Set<String> referencedSha256);

    /** 实际内容复核后返回的安全元数据，不携带任何路径。 */
    record ImportedBlob(long sizeBytes, String sha256, AttachmentMetadata.MediaKind mediaKind,
                        String mediaType) { }

    /** 文件边界仅发布稳定、脱敏分类，应用层不能依赖具体 NIO 异常或路径文本。 */
    final class Failure extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        private final Code code;

        /** 创建不携带底层路径的稳定失败。 */
        public Failure(Code code) {
            this(code, null);
        }

        /** cause 只保留给本地诊断链，公开消息固定不包含用户文件事实。 */
        public Failure(Code code, Throwable cause) {
            super("managed attachment storage failed", cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        /** 返回应用层可穷举映射的错误分类。 */
        public Code code() {
            return code;
        }
    }

    /** 端口错误只区分恢复方式，不暴露 NIO、操作系统或物理位置。 */
    enum Code {
        /** 调用方提供的 token、摘要、大小或 range 不满足封闭契约。 */
        INVALID_REQUEST,
        /** staging 不存在、不是普通文件或 owner 根已被替换。 */
        SOURCE_UNAVAILABLE,
        /** staging 在复核窗口内变化或与 Rust 预期摘要不一致。 */
        SOURCE_CHANGED,
        /** 文件超过产品固定的单文件上限。 */
        TOO_LARGE,
        /** 既有内容寻址 blob 已损坏或被非法节点替换。 */
        BLOB_CORRUPT,
        /** 原子发布、读取或回收发生已脱敏 I/O 失败。 */
        IO
    }
}
