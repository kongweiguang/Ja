// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.port.in.ContextCompactionEvent;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.json.JacksonJsonValues;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TaskRecords;
import io.github.kongweiguang.ja.task.domain.TaskModels;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 三种真实 Turn admission 共享冻结上下文初始化，不把偏好版本或消息数量当作执行身份。 */
public final class TaskContextInheritancePersistence {
    /**
     * 当前 Turn 已插入同一事务；先做有界存在性查询，后续 Turn 不再读取或解析大 seed。
     * Goal/Plan 不需要伪造 UserContent 或 TurnAdmission，就能使用相同模型历史初始化。
     */
    public static long injectSeedIfFirstTurn(PersistenceMappers mapper, ObjectMapper objectMapper,
                                             String threadId, String turnId, Instant requestedAt,
                                             long startOrdinal) {
        if (mapper.agent().hasOtherTurns(threadId, turnId)) return startOrdinal;
        TaskRecords.ContextSeedRow row = mapper.tasks().selectContextSeed(threadId);
        if (row == null || !"EFFECTIVE_CONTEXT".equals(row.inheritanceMode())) return startOrdinal;
        TaskJsonCodec json = new TaskJsonCodec(objectMapper);
        String fingerprint = json.fingerprint(row.parentThreadId(), row.parentTurnId(), row.parentRevision(),
                row.inheritanceMode(), row.taskBriefJson(), row.effectiveContextJson(),
                row.referencesJson(), row.permissionCeilingJson());
        if (!fingerprint.equals(row.fingerprint())) throw invalid("effective context fingerprint mismatch");
        TaskModels.ContextSeed seed = new TaskModels.ContextSeed(row.contextSeedId(), row.parentThreadId(),
                row.parentTurnId(), row.parentRevision(), TaskModels.InheritanceMode.EFFECTIVE_CONTEXT,
                row.taskBriefJson() == null ? null : json.readContent(row.taskBriefJson()),
                json.readObject(row.effectiveContextJson()), json.readArray(row.referencesJson()),
                json.readObject(row.permissionCeilingJson()), row.fingerprint(), Instant.parse(row.createdAt()));
        return inject(mapper, objectMapper, seed, threadId, turnId, requestedAt, startOrdinal);
    }

    /** 原子 Child+Turn admission 也使用相同规则；BRIEF_ONLY 永远不会复制父历史。 */
    public static long injectIfFirstTurn(PersistenceMappers mapper, ObjectMapper objectMapper,
                                         TaskModels.ContextSeed seed, String threadId, String turnId,
                                         Instant requestedAt, long startOrdinal,
                                         String parentThreadId, long parentRevision, String fingerprint) {
        if (seed.inheritanceMode() != TaskModels.InheritanceMode.EFFECTIVE_CONTEXT
                || mapper.agent().hasOtherTurns(threadId, turnId)) return startOrdinal;
        if (!seed.parentThreadId().equals(parentThreadId) || seed.parentRevision() != parentRevision
                || !seed.fingerprint().equals(fingerprint)) throw invalid("effective context identity mismatch");
        return inject(mapper, objectMapper, seed, threadId, turnId, requestedAt, startOrdinal);
    }

    /**
     * 父消息写入模型 messages，刻意不写 Timeline；所有记录隶属当前真实 Turn，方便普通发送、
     * Goal/Plan、重启及压缩统一读取，事务失败时上下文和 Turn 一起回滚。
     */
    private static long inject(PersistenceMappers mapper, ObjectMapper objectMapper, TaskModels.ContextSeed seed,
                                String threadId, String turnId, Instant requestedAt, long startOrdinal) {
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (startOrdinal < 1) throw invalid("invalid context ordinal");
        PersistenceRecords.ThreadRow target = mapper.history().selectThread(threadId);
        PersistenceRecords.ThreadRow parent = mapper.history().selectThread(seed.parentThreadId());
        if (target == null || parent == null || !target.workspaceId().equals(parent.workspaceId()))
            throw invalid("effective context workspace mismatch");
        JsonNode context = JacksonJsonValues.toNode(objectMapper, seed.effectiveContext());
        if (!context.isObject() || context.path("schemaVersion").asInt(-1) != 1
                || !context.path("parentThreadId").asText().equals(seed.parentThreadId())
                || context.path("parentRevision").asLong(-1) != seed.parentRevision()
                || !context.path("messages").isArray()) throw invalid("effective context does not match seed");
        Set<String> allowedAttachments = new HashSet<>();
        for (JsonNode reference : JacksonJsonValues.toNode(objectMapper, seed.references())) {
            if ("attachment".equals(reference.path("kind").asText()))
                allowedAttachments.add(reference.path("attachmentId").asText());
        }
        long ordinal = startOrdinal;
        for (JsonNode inherited : context.path("messages")) {
            String role = inherited.path("role").asText();
            JsonNode blocks = inherited.path("blocks");
            if (!("USER".equals(role) || "ASSISTANT".equals(role) || "TOOL".equals(role)) || !blocks.isArray())
                throw invalid("effective context message has an invalid shape");
            validateAttachments(mapper, blocks, allowedAttachments, seed.parentThreadId(), target.workspaceId());
            requireChanged(mapper.agent().insertMessage(new PersistenceRecords.MessageInsert(
                    inheritedMessageId(seed.contextSeedId(), ordinal), threadId, turnId, ordinal,
                    role, compact(objectMapper, blocks), requestedAt.toString())), "inherited message insert lost");
            ordinal++;
        }
        JsonNode checkpoint = context.path("checkpoint");
        if (checkpoint.isObject()) {
            JsonNode summary = checkpoint.path("summary");
            JsonNode usage = checkpoint.path("usage");
            if (!summary.isObject() || !usage.isObject()) throw invalid("invalid inherited checkpoint");
            requireChanged(mapper.checkpoint().insertCheckpoint(new PersistenceRecords.CheckpointInsert(
                    inheritedCheckpointId(seed.contextSeedId()), threadId, 1, 0,
                    ordinal == startOrdinal ? 0 : startOrdinal, null, compact(objectMapper, summary),
                    checkpoint.path("estimatedTokens").asInt(0), seed.fingerprint(),
                    ContextCompactionEvent.STRATEGY_VERSION, compact(objectMapper, usage),
                    requestedAt.toString())), "inherited checkpoint insert lost");
        } else if (!checkpoint.isNull()) throw invalid("invalid inherited checkpoint shape");
        return ordinal;
    }

    /**
     * BOUND 附件仍保持原消息的唯一绑定；只接受冻结引用中且父 Thread 可读的同 Workspace 附件。
     * Child 读取授权由 AttachmentMapper 的有界 seed 祖先链提供，不复制 blob 或篡改原绑定。
     */
    private static void validateAttachments(PersistenceMappers mapper, JsonNode blocks,
                                             Set<String> allowed, String parentThreadId, String workspaceId) {
        for (JsonNode block : blocks) {
            if (!"attachment".equals(block.path("kind").asText())) continue;
            String id = block.path("attachmentId").asText();
            AttachmentRecords.AttachmentRow row = allowed.contains(id)
                    ? mapper.attachments().selectThreadAttachment(id, parentThreadId) : null;
            if (row == null || !"BOUND".equals(row.status()) || !workspaceId.equals(row.workspaceId()))
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "inherited attachment is outside the frozen parent scope");
        }
    }

    /** 身份由不可变 seed 与重编号 ordinal 派生，重试不产生第二份继承消息。 */
    private static String inheritedMessageId(String seedId, long ordinal) {
        return "item_ctx_" + seedId.substring("seed_".length()) + '_' + ordinal;
    }

    /** 每个 seed 只建立一个 checkpoint，不借用父 Thread 的 checkpoint identity。 */
    private static String inheritedCheckpointId(String seedId) {
        return "checkpoint_task_" + seedId.substring("seed_".length());
    }

    /** 继承内容保持原 blocks 的 JSON 形状，不降级为纯文本摘要或吞掉未知内容。 */
    private static String compact(ObjectMapper mapper, JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "cannot encode inherited context", failure);
        }
    }

    /** 单行写失败必须让调用方整体回滚，不能留下只有部分上下文的可执行 Turn。 */
    private static void requireChanged(int count, String message) {
        if (count != 1) throw new StorageException(StorageException.Code.CAS_CONFLICT, message);
    }

    /** 持久事实损坏只给稳定错误，不回显原始上下文或路径。 */
    private static StorageException invalid(String message) {
        return new StorageException(StorageException.Code.INVALID_STATE, message);
    }

    /** 纯事务扩展不创建独立运行时。 */
    private TaskContextInheritancePersistence() { }
}
