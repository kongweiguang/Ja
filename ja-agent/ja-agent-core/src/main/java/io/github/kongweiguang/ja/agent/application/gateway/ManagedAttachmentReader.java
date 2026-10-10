// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Objects;

/**
 * Conversation 消费者拥有的受管附件读取端口；bootstrap 负责桥接附件入站用例，避免业务切片互相反向依赖。
 */
@FunctionalInterface
public interface ManagedAttachmentReader {
    /**
     * 在当前 Thread 授权范围内读取一个有界字节窗口；结果 offset 必须等于请求 offset，且不能超过 maxBytes。
     */
    ReadResult read(ReadRequest request);

    /**
     * 提供读取工具的宿主可保留引用降级；只接收原生资料的宿主应返回false，
     * 使能力变化或消费时总量超限在Provider派发前明确失败，避免把未传输资料当成已读取。
     */
    default boolean allowsReferenceFallback() {
        return true;
    }

    /**
     * 先通过最小 range 取得已授权元数据，避免仅为能力判断就把整个附件载入内存。
     */
    default Descriptor inspect(String attachmentId, String threadId) {
        ReadResult result = read(new ReadRequest(attachmentId, threadId, 0, 4));
        return new Descriptor(result.attachmentId(), result.displayName(), result.sizeBytes(),
                result.mediaKind(), result.mediaType());
    }

    /**
     * 仅为已通过模型与 Codec 双门的图片/PDF组装完整 Base64；逐片解码后统一编码，避免拼接带填充的 Base64 分片。
     * 每片必须严格匹配请求偏移且不超过 64 KiB，避免适配器错位或超额响应绕过读取边界。
     */
    default NativeReadResult readNative(NativeReadRequest request) {
        Objects.requireNonNull(request, "request");
        Descriptor descriptor = inspect(request.attachmentId(), request.threadId());
        if (!descriptor.mediaKind().matches("image|pdf") || descriptor.sizeBytes() < 1
            || descriptor.sizeBytes() > request.maxBytes()) {
            throw new IllegalArgumentException("attachment is outside native routing capability");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.toIntExact(descriptor.sizeBytes()));
        int chunkLimit = 64 * 1024;
        int encodedChunkLimit = Math.multiplyExact((chunkLimit + 2) / 3, 4);
        long offset = 0;
        while (offset < descriptor.sizeBytes()) {
            ReadResult part = read(new ReadRequest(request.attachmentId(), request.threadId(), offset, chunkLimit));
            if (!descriptor.matches(part) || !"base64".equals(part.encoding())
                || part.offsetBytes() != offset || part.nextOffsetBytes() <= offset
                || part.nextOffsetBytes() - offset > chunkLimit || part.content().length() > encodedChunkLimit) {
                throw new IllegalStateException("managed attachment changed during native read");
            }
            byte[] decoded = Base64.getDecoder().decode(part.content());
            if (decoded.length != part.nextOffsetBytes() - part.offsetBytes()) {
                throw new IllegalStateException("managed attachment range is inconsistent");
            }
            bytes.writeBytes(decoded);
            offset = part.nextOffsetBytes();
            if (part.endOfFile() != (offset == descriptor.sizeBytes())) {
                throw new IllegalStateException("managed attachment EOF is inconsistent");
            }
        }
        byte[] content = bytes.toByteArray();
        if (content.length != descriptor.sizeBytes()) {
            throw new IllegalStateException("managed attachment size is inconsistent");
        }
        return new NativeReadResult(descriptor, Base64.getEncoder().encodeToString(content));
    }

    /**
     * 将附件授权、缺失、损坏或底层 IO 失败收敛为消费者自有的稳定边界；调用方只按可恢复性分流，
     * 不依赖附件切片的异常类型，也不会把物理路径或存储文本带入队列问题。
     */
    final class ReadFailure extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 保留本地 cause 供诊断，公开消息固定为脱敏文本。
         */
        public ReadFailure(Throwable cause) {
            super("managed attachment read failed", Objects.requireNonNull(cause, "cause"));
        }
    }

    /**
     * Tool 只能提供附件 identity 与 range，Thread identity 由冻结执行上下文注入。
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
         * 限制单次读取窗口；身份只遵守通用标识规则，Ja 前缀由宿主授权边界校验。
         */
        public ReadRequest {
            attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
            threadId = ContractChecks.identifier(threadId, "threadId");
            if (offsetBytes < 0 || offsetBytes > 100L * 1024 * 1024
                || maxBytes < 4 || maxBytes > 64 * 1024) {
                throw new IllegalArgumentException("invalid managed attachment read request");
            }
        }
    }

    /**
     * 返回 Tool 展示所需的脱敏元数据与有界内容，不包含 Workspace、hash、路径或 token。
     *
     * @param attachmentId 附件标识
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     * @param offsetBytes 读取起点的字节偏移
     * @param nextOffsetBytes 下次读取的字节偏移
     * @param endOfFile 是否到达文件末尾
     * @param encoding 编码
     * @param content 内容
     */
    record ReadResult(String attachmentId,
                      String displayName,
                      long sizeBytes,
                      String mediaKind,
                      String mediaType,
                      long offsetBytes,
                      long nextOffsetBytes,
                      boolean endOfFile,
                      String encoding,
                      String content) {
        /**
         * 结果闭集与附件服务一致，防止 bridge 通过自由字符串伪造媒体能力。
         */
        public ReadResult {
            attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
            Descriptor.validate(attachmentId, displayName, sizeBytes, mediaKind, mediaType);
            if (offsetBytes < 0 || nextOffsetBytes < offsetBytes || nextOffsetBytes > sizeBytes
                || !Objects.requireNonNull(encoding, "encoding").matches("utf-8|base64")
                || content == null) {
                throw new IllegalArgumentException("invalid managed attachment read result");
            }
        }
    }

    /**
     * 请求上限来自具体 Codec 声明，且永远不能超过当前 JSON 原生载荷闭集的 50 MiB 上限。
     *
     * @param attachmentId 附件标识
     * @param threadId 所属对话的稳定标识
     * @param maxBytes 单次读取的字节上限
     */
    record NativeReadRequest(String attachmentId,
                             String threadId,
                             long maxBytes) {
        /**
         * 读取权限由宿主按通用会话身份确认，这里仅限制单次原生载荷上限。
         */
        public NativeReadRequest {
            attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
            threadId = ContractChecks.identifier(threadId, "threadId");
            if (maxBytes < 1 || maxBytes > 50L * 1024 * 1024) {
                throw new IllegalArgumentException("invalid native attachment read request");
            }
        }
    }

    /**
     * 原生请求只取得脱敏描述与不可变 Base64，不泄漏路径或内容摘要。
     *
     * @param descriptor 描述信息
     * @param base64Data Base64 编码的二进制内容
     */
    record NativeReadResult(Descriptor descriptor,
                            String base64Data) {
        /**
         * 读取实现必须同时返回经过同一授权窗口验证的描述与载荷。
         */
        public NativeReadResult {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(base64Data, "base64Data");
        }
    }

    /**
     * 提供能力路由需要的最小元数据，不包含 Workspace、hash 或物理路径。
     *
     * @param attachmentId 附件标识
     * @param displayName 面向用户的显示名称
     * @param sizeBytes 内容大小，单位为字节
     * @param mediaKind 媒体分类
     * @param mediaType 内容的 MIME 类型
     */
    record Descriptor(String attachmentId,
                      String displayName,
                      long sizeBytes,
                      String mediaKind,
                      String mediaType) {
        /**
         * 复用公开读取结果的闭集并限制 native 路由只观察必要字段。
         */
        public Descriptor {
            attachmentId = ContractChecks.identifier(attachmentId, "attachmentId");
            validate(attachmentId, displayName, sizeBytes, mediaKind, mediaType);
        }

        /**
         * 描述字段在 range 与 native 两个结果间共享同一闭集，避免公开接口上的私有静态方法被分析器误判为死代码。
         */
        static void validate(String attachmentId, String displayName, long sizeBytes,
                             String mediaKind, String mediaType) {
            if (displayName == null || displayName.isBlank()
                || displayName.length() > 512 || sizeBytes < 0 || sizeBytes > 100L * 1024 * 1024
                || !Objects.requireNonNull(mediaKind, "mediaKind").matches("text|image|pdf|binary")
                || mediaType == null || mediaType.isBlank() || mediaType.length() > 128) {
                throw new IllegalArgumentException("invalid managed attachment descriptor");
            }
        }

        /**
         * 多片读取必须保持同一脱敏元数据，防止数据库或 blob 在组装中途漂移。
         */
        private boolean matches(ReadResult result) {
            return attachmentId.equals(result.attachmentId()) && displayName.equals(result.displayName())
                   && sizeBytes == result.sizeBytes() && mediaKind.equals(result.mediaKind())
                   && mediaType.equals(result.mediaType());
        }
    }
}
