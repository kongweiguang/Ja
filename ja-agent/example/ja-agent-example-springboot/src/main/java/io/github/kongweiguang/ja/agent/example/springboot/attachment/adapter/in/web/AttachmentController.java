// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.attachment.adapter.in.web;

import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentException;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.port.out.AttachmentRepository.AttachmentContent;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ThreadService;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * 附件 API 只接受服务端 Actor 与已归属 Thread；请求正文不提供 tenant 或 user 身份字段。
 */
@RestController
@RequestMapping("/api/chat/attachments")
public final class AttachmentController {
    private final AttachmentService attachments;
    private final ThreadService threads;
    private final CurrentActorProvider actors;

    /** 在 HTTP 边界读取一次身份并显式传给服务，异步附件读取仍使用 Core 冻结的执行身份。 */
    public AttachmentController(AttachmentService attachments, ThreadService threads, CurrentActorProvider actors) {
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.threads = Objects.requireNonNull(threads, "threads");
        this.actors = Objects.requireNonNull(actors, "actors");
    }

    /** 同一 Thread 与操作键同文件重试返回原 metadata；读入异常保留为 cause，但 HTTP 只暴露固定分类。 */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentMetadata upload(@RequestParam("threadId") String threadId,
                                     @RequestParam("operationKey") String operationKey,
                                     @RequestParam("file") MultipartFile file) {
        CurrentActor actor = actors.currentActor();
        threads.requireOwned(actor, threadId);
        try {
            return attachments.upload(actor, threadId, operationKey, file.getOriginalFilename(), file.getBytes());
        } catch (IOException unreadableUpload) {
            throw new AttachmentException(AttachmentException.Reason.INVALID, unreadableUpload);
        }
    }

    /** 批量读取同一 Thread 的 metadata，不读取或返回 bytea 文件正文。 */
    @PostMapping("/metadata")
    public MetadataResponse metadata(@RequestBody MetadataCommand command) {
        CurrentActor actor = actors.currentActor();
        threads.requireOwned(actor, command.threadId());
        return new MetadataResponse(attachments.metadata(actor, command.threadId(), command.attachmentIds()));
    }

    /** 幂等丢弃仍为草稿的附件；已进入持久输入的附件由存储层拒绝清理。 */
    @PostMapping("/discard")
    public DiscardResponse discard(@RequestBody DiscardCommand command) {
        CurrentActor actor = actors.currentActor();
        threads.requireOwned(actor, command.threadId());
        return new DiscardResponse(attachments.discard(actor, command.threadId(), command.attachmentIds()));
    }

    /** 内容只在显式预览/下载请求中物化，并同时校验当前 Actor、Thread 与附件三者归属。 */
    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable("attachmentId") String attachmentId,
                                          @RequestParam("threadId") String threadId,
                                          @RequestParam(value = "download", defaultValue = "false") boolean download) {
        CurrentActor actor = actors.currentActor();
        threads.requireOwned(actor, threadId);
        AttachmentContent stored = attachments.content(actor, threadId, attachmentId);
        AttachmentMetadata metadata = stored.metadata();
        ContentDisposition disposition = ContentDisposition.builder(download ? "attachment" : "inline")
                .filename(metadata.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.mediaType()))
                .contentLength(metadata.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(stored.content());
    }

    /**
     * metadata 查询固定在线程内按附件 ID 批量读取，不接收可伪造的用户或租户身份。
     *
     * @param threadId 元数据查询所属的 Thread 标识
     * @param attachmentIds 本次查询的附件标识集合
     */
    public record MetadataCommand(String threadId,
                                  List<String> attachmentIds) {
        /** 校验批量边界并复制 ID 列表，避免调用方在命令创建后改变授权查询范围。 */
        public MetadataCommand {
            requireBatch(threadId, attachmentIds);
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * discard 与 metadata 使用相同的 Thread 范围和有界 ID 列表。
     *
     * @param threadId 草稿附件所属的 Thread 标识
     * @param attachmentIds 本次尝试丢弃的附件标识集合
     */
    public record DiscardCommand(String threadId,
                                 List<String> attachmentIds) {
        /** 校验批量边界并复制 ID 列表，避免丢弃命令在创建后被调用方改写。 */
        public DiscardCommand {
            requireBatch(threadId, attachmentIds);
            attachmentIds = List.copyOf(attachmentIds);
        }
    }

    /**
     * 统一的批量响应包装，保留前端简单的 items/discardedIds 结构。
     *
     * @param items 按请求范围返回的附件元数据
     */
    public record MetadataResponse(List<AttachmentMetadata> items) {
        /** 防止返回对象与服务层临时列表共享可变状态。 */
        public MetadataResponse {
            items = List.copyOf(items);
        }
    }

    /**
     * discard 返回服务端确认的附件身份，用于响应丢失后的幂等回读。
     *
     * @param discardedIds 已由存储层确认丢弃的附件标识
     */
    public record DiscardResponse(List<String> discardedIds) {
        /** 防止序列化前调用方改写已确认的身份集合。 */
        public DiscardResponse {
            discardedIds = List.copyOf(discardedIds);
        }
    }

    /** 拒绝缺少线程、空 ID 或过大的批量请求，使复制阶段不会把无效输入转成意外 NPE。 */
    private static void requireBatch(String threadId, List<String> attachmentIds) {
        if (threadId == null || threadId.isBlank() || attachmentIds == null || attachmentIds.size() > 2_560
                || attachmentIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid attachment batch");
        }
    }
}
