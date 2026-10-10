// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.application;

import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 附件应用服务复用同一能力快照，并把输入引用写入 Core Store participant 的现有事务。
 */
@Service
public final class AttachmentService implements ManagedAttachmentReader {
    private final AttachmentRepository store;
    private final AttachmentCapabilities capabilities;
    private final ExecutionRecordCodec records;
    private final AgentInitialQueuedInputsCodec initialInputs = new AgentInitialQueuedInputsCodec();

    /**
     * Reader 与 HTTP 服务共用 Store 和模型 capability，避免恢复时依赖请求线程上下文。
     */
    public AttachmentService(AttachmentRepository store, AttachmentCapabilities capabilities,
                             ExecutionRecordCodec records) {
        this.store = Objects.requireNonNull(store, "store");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.records = Objects.requireNonNull(records, "records");
    }

    /**
     * 本示例没有 read_attachment 工具，只承诺将资料原生字节送入 Provider；超出当前模型请求能力时必须失败，
     * 避免把未实际传输的附件引用伪装成已解读资料。
     */
    @Override
    public boolean allowsReferenceFallback() {
        return false;
    }

    /**
     * 验证真实格式、扩展名和所选 Codec 边界后，以稳定操作键保存不可变文件字节。
     */
    public AttachmentMetadata upload(CurrentActor actor, String threadId, String operationKey,
                                     String originalFileName, byte[] content) {
        validateOperationKey(operationKey);
        byte[] bytes = Objects.requireNonNull(content, "content");
        if (bytes.length == 0) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        MediaFile media = detectMedia(bytes);
        capabilities.requireSupported(media.mediaType(), bytes.length);
        String fileName = safeFileName(originalFileName);
        requireMatchingExtension(fileName, media.extension());
        return store.upload(actor, threadId, operationKey, fileName, media.mediaType(), sha256(bytes), bytes);
    }

    /**
     * 按 Actor 与 Thread 批量投影元数据，目录读取不会触碰文件 bytea。
     */
    public List<AttachmentMetadata> metadata(CurrentActor actor, String threadId, List<String> attachmentIds) {
        return store.metadata(actor, threadId, attachmentIds);
    }

    /** 聚合模型可见历史中的持久输入来源；历史条数不由宿主裁切，只向策略提供已引用附件。 */
    public List<AttachmentMetadata> referencedMetadata(AgentIdentity identity, List<String> attachmentIds) {
        AgentIdentity frozen = Objects.requireNonNull(identity, "identity");
        if (attachmentIds == null || attachmentIds.stream().anyMatch(Objects::isNull)) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(attachmentIds));
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentMetadata> metadata = store.metadata(
                new CurrentActor(frozen.tenantId(), frozen.userId()), frozen.threadId(), ids);
        for (AttachmentMetadata item : metadata) {
            if (!item.referenced()) {
                throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
            }
            capabilities.requireSupported(item.mediaType(), item.sizeBytes());
        }
        return metadata;
    }

    /**
     * 只在明确的内容预览请求中物化一份文件，并保留其权威 metadata。
     */
    public AttachmentRepository.AttachmentContent content(CurrentActor actor, String threadId, String attachmentId) {
        return store.content(actor, threadId, attachmentId);
    }

    /**
     * 仅允许删除尚未进入持久输入的草稿；重复请求将同样返回成功结果。
     */
    public List<String> discard(CurrentActor actor, String threadId, List<String> attachmentIds) {
        return store.discard(actor, threadId, attachmentIds);
    }

    /**
     * 提交前检查 owner、Thread、模型声明、Codec MIME 和总字节边界；附件缺失时不接受输入。
     */
    public List<AttachmentMetadata> validateInput(AgentIdentity identity, List<String> attachmentIds) {
        AgentIdentity frozen = Objects.requireNonNull(identity, "identity");
        List<String> ids = List.copyOf(Objects.requireNonNull(attachmentIds, "attachmentIds"));
        if (ids.size() > capabilities.maxAttachments() || ids.stream().distinct().count() != ids.size()) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<AttachmentMetadata> metadata = store.metadata(
                new CurrentActor(frozen.tenantId(), frozen.userId()), frozen.threadId(), ids);
        long total = 0;
        for (AttachmentMetadata item : metadata) {
            capabilities.requireSupported(item.mediaType(), item.sizeBytes());
            total = Math.addExact(total, item.sizeBytes());
        }
        if (total > capabilities.maxTotalBytes()) {
            throw new AttachmentException(AttachmentException.Reason.REQUEST_TOO_LARGE);
        }
        return metadata;
    }

    /**
     * Store participant 从刚提交的 Core 输入记录提取附件 ID，并借用 Core 持有的连接标记持久引用；
     * 该连接由 transaction resource owner 在所有 participant 完成后关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public void onExecutionCommit(AgentStoreTransactionContext context) {
        AgentStoreTransactionContext transaction = Objects.requireNonNull(context, "context");
        if (transaction.records().isEmpty()) {
            return;
        }
        List<String> attachmentIds = new ArrayList<>();
        AgentIdentity identity = identity(transaction.execution());
        for (AgentStore.RecordWrite write : transaction.records()) {
            if ("agent.user.message".equals(write.recordType())) {
                append(attachmentIds, records.decodeUser(asStored(write)).content());
            } else if ("agent.input.message".equals(write.recordType())) {
                append(attachmentIds, records.decodeQueuedUser(asStored(write)).content());
            } else if (AgentInitialQueuedInputsCodec.RECORD_TYPE.equals(write.recordType())) {
                String operationKey = transaction.operation().map(AgentStore.OperationIdentity::operationKey)
                        .orElseThrow(() -> new IllegalStateException("initial queued inputs lack an operation identity"));
                initialInputs.decode(write, transaction.execution(), operationKey).forEach(input ->
                        append(attachmentIds, input.content()));
            }
        }
        if (attachmentIds.isEmpty()) {
            return;
        }
        try {
            Connection connection = transaction.resources().unwrap(Connection.class);
            store.reference(connection, identity, attachmentIds);
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("attachment reference commit failed", failure);
        }
    }

    /**
     * 对企业笔记 participant 复用只读的同连接 owner 检查，且只认已进入持久输入的来源。
     */
    public List<AttachmentMetadata> requireOwned(Connection connection, AgentIdentity identity,
                                                  Collection<String> attachmentIds) {
        try {
            return store.requireOwned(connection, identity, attachmentIds);
        } catch (SQLException failure) {
            throw new AgentStore.StoreParticipantException("attachment source verification failed", failure);
        }
    }

    /**
     * Core 调用只携带 Thread 与 attachment ID；DB join 解析唯一 owner，不读取 HTTP ThreadLocal。
     */
    @Override
    public ReadResult read(ReadRequest request) {
        try {
            AttachmentRepository.ReadSlice slice = store.read(request);
            AttachmentMetadata metadata = slice.metadata();
            byte[] bytes = slice.content();
            long next = request.offsetBytes() + bytes.length;
            return new ReadResult(metadata.id(), metadata.fileName(), metadata.sizeBytes(), mediaKind(metadata.mediaType()),
                    metadata.mediaType(), request.offsetBytes(), next, next == metadata.sizeBytes(), "base64",
                    Base64.getEncoder().encodeToString(bytes));
        } catch (RuntimeException failure) {
            throw new ManagedAttachmentReader.ReadFailure(failure);
        }
    }

    /**
     * 将已冻结的 Store execution key 转回完整 owner 身份，user ID 仍是异步准入时的原值。
     */
    private static AgentIdentity identity(AgentStore.ExecutionKey execution) {
        return new AgentIdentity(execution.conversation().tenantId(), execution.conversation().threadId(),
                execution.turnId(), execution.conversation().userId());
    }

    /**
     * Store transaction 还未分配 sequence 时使用只读占位序号复用 Core 的严格记录解码器。
     */
    private static AgentStore.StoredRecord asStored(AgentStore.RecordWrite write) {
        return new AgentStore.StoredRecord(write.recordId(), write.recordType(), write.payloadSchemaVersion(),
                1, write.payload(), write.occurredAt());
    }

    /**
     * 只提取 Core 明确标记的附件内容块，不从普通正文或用户文件名中猜测引用。
     */
    private static void append(List<String> target, UserContent content) {
        for (UserContentBlock block : content.blocks()) {
            if (block instanceof AttachmentContent attachment) {
                target.add(attachment.attachmentId());
            }
        }
    }

    /**
     * 稳定操作键不接受空白、控制字符或过长值，便于上传响应丢失后安全复用。
     */
    private static void validateOperationKey(String operationKey) {
        if (operationKey == null || !operationKey.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
    }

    /**
     * 丢弃客户端伪造路径，只保留一段 UTF-8 文件名用于下载和 UI 展示。
     */
    private static String safeFileName(String original) {
        if (original == null || original.isBlank() || original.length() > 2_048) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        String base = original.replace('\\', '/');
        int separator = base.lastIndexOf('/');
        String name = (separator < 0 ? base : base.substring(separator + 1)).strip();
        if (name.isEmpty() || name.length() > 512 || name.chars().anyMatch(Character::isISOControl)) {
            throw new AttachmentException(AttachmentException.Reason.INVALID);
        }
        return name;
    }

    /**
     * 已知扩展名必须与实测 MIME 一致，防止把真实 PDF 包装成图片名进入错误预览器。
     */
    private static void requireMatchingExtension(String fileName, String detectedExtension) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        String alternate = "jpg".equals(detectedExtension) ? "jpeg" : detectedExtension;
        if (Set.of("png", "jpg", "jpeg", "gif", "webp", "pdf").contains(extension)
                && !detectedExtension.equals(extension) && !alternate.equals(extension)) {
            throw new AttachmentException(AttachmentException.Reason.UNSUPPORTED);
        }
    }

    /**
     * 识别受支持格式并验证文件结构；用户提供的 MIME header 不参与路由判定。
     */
    private static MediaFile detectMedia(byte[] bytes) {
        if (isPng(bytes)) {
            return new MediaFile("image/png", "png");
        }
        if (isJpeg(bytes)) {
            return new MediaFile("image/jpeg", "jpg");
        }
        if (isGif(bytes)) {
            return new MediaFile("image/gif", "gif");
        }
        if (isWebp(bytes)) {
            return new MediaFile("image/webp", "webp");
        }
        if (isPdf(bytes)) {
            return new MediaFile("application/pdf", "pdf");
        }
        throw new AttachmentException(AttachmentException.Reason.UNSUPPORTED);
    }

    /**
     * 校验 PNG chunk 边界、CRC、非空 IHDR/IDAT 与文件末尾 IEND，拒绝仅伪造签名的内容。
     */
    private static boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (!startsWith(bytes, signature) || bytes.length < 45) {
            return false;
        }
        int cursor = signature.length;
        boolean header = false;
        boolean imageData = false;
        while (cursor + 12 <= bytes.length) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, cursor, 4).getInt());
            if (length > bytes.length - cursor - 12L) {
                return false;
            }
            int typeOffset = cursor + 4;
            String type = new String(bytes, typeOffset, 4, StandardCharsets.US_ASCII);
            int dataOffset = cursor + 8;
            int dataLength = Math.toIntExact(length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(bytes, typeOffset, Math.addExact(4, dataLength));
            long expected = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, dataOffset + dataLength, 4).getInt());
            if (crc.getValue() != expected) {
                return false;
            }
            if (!header) {
                if (!"IHDR".equals(type) || dataLength != 13
                        || ByteBuffer.wrap(bytes, dataOffset, 8).getLong() == 0) {
                    return false;
                }
                header = true;
            } else if ("IHDR".equals(type)) {
                return false;
            }
            if ("IDAT".equals(type) && dataLength > 0) {
                imageData = true;
            }
            cursor += Math.toIntExact(length + 12);
            if ("IEND".equals(type)) {
                return dataLength == 0 && imageData && cursor == bytes.length;
            }
        }
        return false;
    }

    /**
     * JDK ImageIO 只读 JPEG 头部维度，并要求 SOI/SOF/SOS 与最终 EOI 完整存在。
     */
    private static boolean isJpeg(byte[] bytes) {
        return bytes.length > 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[bytes.length - 2]) == 0xff && unsigned(bytes[bytes.length - 1]) == 0xd9
                && imageHeader(bytes, "JPEG");
    }

    /**
     * GIF 头、逻辑屏幕、JDK reader 元数据和终止块均存在时才接受。
     */
    private static boolean isGif(byte[] bytes) {
        boolean signature = bytes.length >= 14 && (asciiAt(bytes, 0, "GIF87a") || asciiAt(bytes, 0, "GIF89a"));
        return signature && unsigned(bytes[bytes.length - 1]) == 0x3b
                && imageHeader(bytes, "GIF");
    }

    /**
     * WebP 逐段校验 RIFF 长度、chunk padding 与真实 VP8/VP8L/VP8X 图像头。
     */
    private static boolean isWebp(byte[] bytes) {
        if (bytes.length < 25 || !asciiAt(bytes, 0, "RIFF") || !asciiAt(bytes, 8, "WEBP")
                || littleEndianUnsigned(bytes, 4) + 8 != bytes.length) {
            return false;
        }
        int cursor = 12;
        boolean image = false;
        while (cursor + 8 <= bytes.length) {
            String type = new String(bytes, cursor, 4, StandardCharsets.US_ASCII);
            long length = littleEndianUnsigned(bytes, cursor + 4);
            long end = cursor + 8L + length;
            if (end > bytes.length || end + (length & 1) > bytes.length) {
                return false;
            }
            int payload = cursor + 8;
            if ("VP8X".equals(type)) {
                if (length != 10) {
                    return false;
                }
            } else if ("VP8L".equals(type)) {
                if (length < 5 || unsigned(bytes[payload]) != 0x2f) {
                    return false;
                }
                image = true;
            } else if ("VP8 ".equals(type)) {
                if (length < 10 || unsigned(bytes[payload + 3]) != 0x9d
                        || unsigned(bytes[payload + 4]) != 0x01 || unsigned(bytes[payload + 5]) != 0x2a) {
                    return false;
                }
                int width = ((unsigned(bytes[payload + 7]) & 0x3f) << 8) | unsigned(bytes[payload + 6]);
                int height = ((unsigned(bytes[payload + 9]) & 0x3f) << 8) | unsigned(bytes[payload + 8]);
                if (width == 0 || height == 0) {
                    return false;
                }
                image = true;
            }
            cursor = Math.toIntExact(end + (length & 1));
        }
        return image && cursor == bytes.length;
    }

    /**
     * PDF 至少包含受限版本头、EOF、startxref 数字及指向 xref table/stream 的实际偏移。
     */
    private static boolean isPdf(byte[] bytes) {
        if (bytes.length < 32 || !new String(bytes, 0, Math.min(bytes.length, 16), StandardCharsets.US_ASCII)
                .matches("(?s)^%PDF-1\\.[0-9].*|(?s)^%PDF-2\\.0.*")) {
            return false;
        }
        int tailStart = Math.max(0, bytes.length - 2_048);
        String tail = new String(bytes, tailStart, bytes.length - tailStart, StandardCharsets.ISO_8859_1);
        int eof = tail.lastIndexOf("%%EOF");
        int start = tail.lastIndexOf("startxref", eof < 0 ? tail.length() : eof);
        if (eof < 0 || start < 0 || tail.length() - eof > 1_024) {
            return false;
        }
        String offsetText = tail.substring(start + "startxref".length(), eof).trim();
        if (!offsetText.matches("[0-9]{1,12}")) {
            return false;
        }
        long offset;
        try {
            offset = Long.parseLong(offsetText);
        } catch (NumberFormatException invalid) {
            return false;
        }
        if (offset < 0 || offset >= bytes.length) {
            return false;
        }
        int sampleLength = Math.min(512, bytes.length - Math.toIntExact(offset));
        String xref = new String(bytes, Math.toIntExact(offset), sampleLength, StandardCharsets.ISO_8859_1).stripLeading();
        return xref.startsWith("xref") || xref.contains("/Type /XRef") || xref.contains("/Type/XRef");
    }

    /**
     * 只初始化图像 Reader 元数据，不解压图片像素以避免文件头检查导致大块分配。
     */
    private static boolean imageHeader(byte[] bytes, String expectedFormat) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                    return false;
                }
                reader.setInput(input, true, true);
                return reader.getWidth(0) > 0 && reader.getHeight(0) > 0;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException invalidImage) {
            return false;
        }
    }

    /**
     * 将 MIME 映射到 Core Reader 的 image/pdf 闭集，不暴露任意类型。
     */
    private static String mediaKind(String mediaType) {
        return "application/pdf".equals(mediaType) ? "pdf" : "image";
    }

    /**
     * 以 JDK SHA-256 对完整原始字节计算上传重试指纹。
     */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    /**
     * 比较 ASCII magic bytes 时不依赖平台默认字符集。
     */
    private static boolean asciiAt(byte[] bytes, int offset, String expected) {
        return offset >= 0 && offset + expected.length() <= bytes.length
                && expected.equals(new String(bytes, offset, expected.length(), StandardCharsets.US_ASCII));
    }

    /**
     * 校验固定字节签名前缀；避免 ByteBuffer 默认字节序参与格式识别。
     */
    private static boolean startsWith(byte[] bytes, byte[] expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (bytes[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把有符号 Java byte 映射为文件格式字段定义的无符号值。
     */
    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    /**
     * 以 little-endian 读取 RIFF 的 32 位长度字段。
     */
    private static long littleEndianUnsigned(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    /**
     * 文件校验后用于持久 metadata 的最小格式描述。
     *
     * @param mediaType 校验通过且持久化的媒体类型
     * @param extension 与媒体类型对应的安全文件扩展名
     */
    private record MediaFile(String mediaType,
                             String extension) {
    }
}
