// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out.AttachmentRepository;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件服务门禁测试固定文件签名、模型能力和单输入与聚合来源两种不同批量边界。
 */
final class AttachmentServiceTest {
    private static final CurrentActor ACTOR = new CurrentActor("tenant_demo", "user_demo");
    private static final AgentIdentity IDENTITY = new AgentIdentity("tenant_demo", "thread_demo", "turn_demo", "user_demo");

    /** 宿主只承诺原生资料字节；模型或请求超限时不能把附件引用文本冒充成已读取资料。 */
    @Test
    void disablesReferenceFallbackForNativeOnlyExample() {
        AttachmentService service = service(new FakeRepository(), capabilities(2_048, 4_096));

        assertFalse(service.allowsReferenceFallback());
    }

    /** 有效 PNG 经过签名检测后保留原始字节，文件扩展名伪装不能进入 Repository。 */
    @Test
    void validatesDetectedMediaAndExtensionBeforeUpload() throws Exception {
        FakeRepository repository = new FakeRepository();
        AttachmentService service = service(repository, capabilities(2_048, 4_096));
        byte[] png = png();

        AttachmentMetadata uploaded = service.upload(ACTOR, "thread_demo", "upload_image", "evidence.png", png);

        assertEquals("image/png", uploaded.mediaType());
        assertArrayEquals(png, repository.uploadedBytes);
        assertEquals(1, repository.uploadCount);
        AttachmentException mismatch = assertThrows(AttachmentException.class,
                () -> service.upload(ACTOR, "thread_demo", "wrong_extension", "evidence.pdf", png));
        assertEquals(AttachmentException.Reason.UNSUPPORTED, mismatch.reason());
        assertEquals(1, repository.uploadCount);
    }

    /** JPEG、GIF 与真实编码 WebP 均识别为声明格式并保留字节；损坏内容和 MIME 后缀伪装不得入库。 */
    @Test
    void recognizesEncodedJpegGifAndWebpAndRejectsCorruptionAndSuffixForgery() throws Exception {
        FakeRepository repository = new FakeRepository();
        List<MediaFixture> fixtures = encodedMediaFixtures();
        List<String> mediaTypes = fixtures.stream().map(MediaFixture::mediaType).toList();
        AttachmentCapabilities supported = new AttachmentCapabilities(
                mediaTypes, mediaTypes, 2_048, 4_096, 10);
        AttachmentService service = service(repository, supported);

        for (int index = 0; index < fixtures.size(); index++) {
            MediaFixture fixture = fixtures.get(index);
            int uploadsBefore = repository.uploadCount;
            String suffixOperationKey = "suffix_" + index;
            String corruptOperationKey = "corrupt_" + index;
            AttachmentMetadata uploaded = service.upload(
                    ACTOR, "thread_demo", "encoded_" + index, fixture.fileName(), fixture.content());

            assertEquals(fixture.mediaType(), uploaded.mediaType(), fixture.fileName());
            assertArrayEquals(fixture.content(), repository.uploadedBytes, fixture.fileName());
            assertEquals(uploadsBefore + 1, repository.uploadCount, fixture.fileName());

            AttachmentException forgedSuffix = assertThrows(AttachmentException.class,
                    () -> service.upload(ACTOR, "thread_demo", suffixOperationKey,
                            "evidence.pdf", fixture.content()), fixture.fileName());
            assertEquals(AttachmentException.Reason.UNSUPPORTED, forgedSuffix.reason(), fixture.fileName());

            byte[] truncated = Arrays.copyOf(fixture.content(), fixture.content().length - 1);
            AttachmentException corrupt = assertThrows(AttachmentException.class,
                    () -> service.upload(ACTOR, "thread_demo", corruptOperationKey,
                            fixture.fileName(), truncated), fixture.fileName());
            assertEquals(AttachmentException.Reason.UNSUPPORTED, corrupt.reason(), fixture.fileName());
            assertEquals(uploadsBefore + 1, repository.uploadCount, fixture.fileName());
        }
    }

    /** 网关异常保留底层读取 cause 供诊断，但公开消息仍只能使用固定业务分类文案。 */
    @Test
    void preservesUploadReadCauseWithoutExposingItsDetails() {
        IOException source = new IOException("private upload source detail");

        AttachmentException failure = new AttachmentException(AttachmentException.Reason.INVALID, source);

        assertSame(source, failure.getCause());
        assertEquals(AttachmentException.Reason.INVALID, failure.reason());
        assertFalse(failure.getMessage().contains(source.getMessage()));
    }

    /** 客户端 MIME 与文件名均不可信，缺少有效结构的魔数内容必须在持久化前拒绝。 */
    @Test
    void rejectsSpoofedSignatureBeforePersistence() {
        FakeRepository repository = new FakeRepository();
        AttachmentService service = service(repository, capabilities(2_048, 4_096));

        AttachmentException failure = assertThrows(AttachmentException.class,
                () -> service.upload(ACTOR, "thread_demo", "spoofed_image", "valid.png", new byte[] {
                        (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}));

        assertEquals(AttachmentException.Reason.UNSUPPORTED, failure.reason());
        assertEquals(0, repository.uploadCount);
    }

    /** 单条 Core 输入维持附件数限制，历史来源聚合可超过单条 SQL 批次而不被宿主截断。 */
    @Test
    void separatesSingleInputLimitFromAggregatedReferencedSources() {
        FakeRepository repository = new FakeRepository();
        List<String> ids = new ArrayList<>();
        int historicalSourceCount = 2_561;
        for (int index = 0; index < historicalSourceCount; index++) {
            String id = "att_" + index;
            ids.add(id);
            repository.metadata.put(id, metadata(id, true, 100));
        }
        AttachmentService service = service(repository, capabilities(2_048, 4_096));

        assertEquals(historicalSourceCount, service.referencedMetadata(IDENTITY, ids).size());
        assertEquals(AttachmentException.Reason.INVALID,
                assertThrows(AttachmentException.class, () -> service.validateInput(IDENTITY, ids)).reason());
        assertEquals(AttachmentException.Reason.NOT_FOUND,
                assertThrows(AttachmentException.class,
                        () -> service.referencedMetadata(IDENTITY, List.of("att_0", "att_draft"))).reason());
    }

    /** 总量门返回可恢复的会话请求上限错误，提示先压缩或移除资料后重试。 */
    @Test
    void rejectsInputWhoseCombinedBytesExceedCodecLimit() {
        FakeRepository repository = new FakeRepository();
        repository.metadata.put("att_a", metadata("att_a", false, 3));
        repository.metadata.put("att_b", metadata("att_b", false, 3));
        AttachmentService service = service(repository, capabilities(4, 5));

        AttachmentException failure = assertThrows(AttachmentException.class,
                () -> service.validateInput(IDENTITY, List.of("att_a", "att_b")));

        assertEquals(AttachmentException.Reason.REQUEST_TOO_LARGE, failure.reason());
        assertEquals(413, failure.reason().status());
        assertTrue(failure.getMessage().contains("conversation attachments"));
        assertTrue(failure.getMessage().contains("Compress"));
    }

    /** 仅为测试生成合法的完整 PNG 文件，复用 JDK 编码器避免手写签名伪造样本。 */
    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    /** JPEG/GIF 由 JDK ImageIO 实际编码；WebP 字节来自 FFmpeg libwebp lossless 编码并固定在测试中。 */
    private static List<MediaFixture> encodedMediaFixtures() throws Exception {
        byte[] webp = Base64.getDecoder().decode("UklGRhwAAABXRUJQVlA4TA8AAAAvAUAAAAcQ9Y/+ByKi/wEA");
        return List.of(
                new MediaFixture("image/jpeg", "evidence.jpg", encodedImage("jpeg")),
                new MediaFixture("image/gif", "evidence.gif", encodedImage("gif")),
                new MediaFixture("image/webp", "evidence.webp", webp));
    }

    /** 使用 JDK 的真实编码器生成完整图像容器，避免测试仅通过手写文件签名。 */
    private static byte[] encodedImage(String format) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, bytes), format + " writer is unavailable in JDK 25");
        return bytes.toByteArray();
    }

    /** 用同一 Store port 构造被测服务，测试不启动 Spring 或 PostgreSQL。 */
    private static AttachmentService service(FakeRepository repository, AttachmentCapabilities capabilities) {
        return new AttachmentService(repository, capabilities, new StorePayloadCodec(new ObjectMapper()));
    }

    /** 构造显式的模型/Codec 交集，字节上限直接对应本例所测策略。 */
    private static AttachmentCapabilities capabilities(long maxSingle, long maxTotal) {
        return new AttachmentCapabilities(List.of("image/png"), List.of("image/png"), maxSingle, maxTotal, 10);
    }

    /**
     * 测试夹具的 MIME 与扩展名保持一一对应，能力列表显式声明本轮要验证的真实格式。
     *
     * @param mediaType 编码内容声明的媒体类型
     * @param fileName 与该媒体类型匹配的测试文件名
     * @param content 由真实编码器生成的完整媒体字节
     */
    private record MediaFixture(String mediaType,
                                String fileName,
                                byte[] content) { }

    /** 生成不可变元数据样例并按给定状态覆盖 draft 与已引用来源。 */
    private static AttachmentMetadata metadata(String id, boolean referenced, long size) {
        return new AttachmentMetadata(id, "thread_demo", "evidence.png", "image/png", size,
                "a".repeat(64), Instant.EPOCH, referenced);
    }

    /** 只实现应用服务测试实际访问的 Repository 行为，其余方法显式失败避免假装覆盖存储逻辑。 */
    private static final class FakeRepository implements AttachmentRepository {
        private final Map<String, AttachmentMetadata> metadata = new HashMap<>();
        private int uploadCount;
        private byte[] uploadedBytes;

        /** 记录有效上传的原始字节并返回对应 immutable metadata。 */
        @Override
        public AttachmentMetadata upload(CurrentActor actor, String threadId, String operationKey, String fileName,
                                         String mediaType, String sha256, byte[] content) {
            uploadCount++;
            uploadedBytes = content.clone();
            String id = "att_uploaded";
            AttachmentMetadata created = new AttachmentMetadata(id, threadId, fileName, mediaType, content.length,
                    sha256, Instant.EPOCH, false);
            metadata.put(id, created);
            return created;
        }

        /** 按请求顺序返回 metadata；越权或缺失资源使用同一 Not Found 语义。 */
        @Override
        public List<AttachmentMetadata> metadata(CurrentActor actor, String threadId, List<String> attachmentIds) {
            return attachmentIds.stream().map(id -> {
                AttachmentMetadata item = metadata.get(id);
                if (item == null || !item.threadId().equals(threadId)) {
                    throw new AttachmentException(AttachmentException.Reason.NOT_FOUND);
                }
                return item;
            }).toList();
        }

        /** 仅用于满足 port 契约，当前服务测试不读取单个 metadata。 */
        @Override
        public AttachmentMetadata metadata(CurrentActor actor, String attachmentId) {
            return metadata.get(attachmentId);
        }

        /** 内容物化仅由 JDBC 集成测试验证。 */
        @Override
        public AttachmentContent content(CurrentActor actor, String threadId, String attachmentId) {
            throw new UnsupportedOperationException();
        }

        /** 草稿丢弃只由真实数据库与 HTTP 集成测试验证。 */
        @Override
        public List<String> discard(CurrentActor actor, String threadId, List<String> attachmentIds) {
            throw new UnsupportedOperationException();
        }

        /** Store participant 的 fencing 与同连接写入由集成测试验证。 */
        @Override
        public void reference(Connection connection, AgentIdentity identity, Collection<String> attachmentIds)
                throws SQLException {
            throw new UnsupportedOperationException();
        }

        /** 笔记 participant 的来源归属由真实事务集成测试验证。 */
        @Override
        public List<AttachmentMetadata> requireOwned(Connection connection, AgentIdentity identity,
                                                     Collection<String> attachmentIds) throws SQLException {
            throw new UnsupportedOperationException();
        }

        /** Core Reader 的 range 与 Base64 契约由 Provider 集成测试覆盖。 */
        @Override
        public ReadSlice read(ManagedAttachmentReader.ReadRequest request) {
            throw new UnsupportedOperationException();
        }

        /** 测试不触发后台清理任务。 */
        @Override
        public void deleteExpiredDrafts() {
            throw new UnsupportedOperationException();
        }
    }
}
