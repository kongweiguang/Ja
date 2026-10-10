// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.application;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionContext;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentMetadata;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ChatNotFoundException;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.Note;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.NoteFingerprint;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.NoteRepository;
import io.github.kongweiguang.ja.agent.example.springboot.experience.port.out.SkillCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import io.github.kongweiguang.ja.agent.skills.pg.domain.SkillVersionConflictException;
import jakarta.annotation.PostConstruct;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Skill 管理与已提交笔记归档的应用边界；副作用写入由 Core 事务参与者结算。 */
public final class ExperienceService {
    private static final String DOCUMENT_NAMESPACE = "spring-document";
    private static final String NOTE_WRITE_TYPE = "note.write";
    private final SkillCatalog skills;
    private final NoteRepository notes;
    private final AttachmentService attachments;
    private final CurrentActorProvider actors;
    private final ExtensionFactRecordCodec factCodec;

    /**
     * 组合已验证的业务端口；当前 actor 只用于 HTTP 管理和演示播种，异步执行继续使用 Core 身份。
     */
    public ExperienceService(SkillCatalog skills, NoteRepository notes, AttachmentService attachments,
                             CurrentActorProvider actors, ExtensionFactRecordCodec factCodec) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.notes = Objects.requireNonNull(notes, "notes");
        this.attachments = Objects.requireNonNull(attachments, "attachments");
        this.actors = Objects.requireNonNull(actors, "actors");
        this.factCodec = Objects.requireNonNull(factCodec, "factCodec");
    }

    /**
     * 仅为当前服务端演示租户播种缺失 Skill；不会覆盖企业或用户已有同名正文。
     */
    @PostConstruct
    void seedDefaults() {
        SkillRequestContext context = skillContext(actors.currentActor().tenantId());
        seed(context, "brief-reply", "简短中文答复", "回答使用简体中文。先给结论，再给最多三条要点。不要省略真实错误或未完成事项。");
        seed(context, "tool-audit", "依据真实工具结果答复", "涉及计算、时间或笔记时使用实际工具。MCP 能力先用 mcp_tools 发现，再用 mcp_call 调用。只根据工具返回说明结果，不能把受理或审批当作已经执行。");
    }

    /**
     * 使用请求 owner 查询目录，避免应用服务持有固定 tenant 或跨租户 Skill 缓存。
     */
    public List<SkillDescriptor> skills(SkillRequestContext context) {
        return skills.list(Objects.requireNonNull(context, "context"));
    }

    /**
     * Core 工具依据已冻结 AgentIdentity 读取 Skill，不能依赖 HTTP actor 上下文。
     */
    public SkillView readSkill(AgentIdentity identity, String name) {
        return readSkill(skillContext(identity.tenantId()), name);
    }

    /**
     * 根据调用方 tenant 发现版本并只读取目标正文，避免陈旧描述符返回已替换内容。
     */
    public SkillView readSkill(SkillRequestContext context, String name) {
        SkillDescriptor descriptor = skills(context).stream().filter(value -> value.name().equals(name)).findFirst()
                .orElseThrow(() -> new ChatNotFoundException("skill not found"));
        String body = skills.read(context, descriptor);
        return new SkillView(descriptor.name(), descriptor.description(), descriptor.reference(),
                descriptor.version(), body);
    }

    /**
     * 整包更新必须引用当前 tenant 的版本，页面不能提交身份字段或覆盖并发修改。
     */
    public SkillDescriptor writeSkill(SkillRequestContext context, SkillWrite command) {
        if (command.body() == null || command.body().length() > 32_768) {
            throw new IllegalArgumentException("skill body limit");
        }
        return skills.write(context, command.name(), command.description(), command.body(), command.expectedVersion());
    }

    /**
     * 删除按当前 tenant 和版本 CAS，不清理用户目录或其它来源。
     */
    public void deleteSkill(SkillRequestContext context, SkillDelete command) {
        skills.delete(context, command.name(), command.expectedVersion());
    }

    /**
     * 在 Core 的 Store 提交中消费 note.write；Connection 仅在 participant 回调期间借用，事务与关闭仍归 Store。
     */
    @SuppressWarnings("PMD.CloseResource")
    public void onExecutionCommit(AgentStoreTransactionContext transaction) {
        Objects.requireNonNull(transaction, "transaction");
        AgentStore.ExecutionKey key = transaction.execution();
        AgentStore.ConversationKey conversation = key.conversation();
        AgentIdentity identity = new AgentIdentity(conversation.tenantId(), conversation.threadId(), key.turnId(),
                conversation.userId());
        Connection connection = transaction.resources().unwrap(Connection.class);
        for (AgentStore.RecordWrite record : transaction.records()) {
            if (!ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            AgentTool.ExtensionFact fact = factCodec.decode(new AgentStore.StoredRecord(record.recordId(),
                    record.recordType(), record.payloadSchemaVersion(), 1, record.payload(), record.occurredAt()));
            if (!DOCUMENT_NAMESPACE.equals(fact.namespace()) || !NOTE_WRITE_TYPE.equals(fact.type())) {
                continue;
            }
            persistNoteFact(connection, identity, fact);
        }
    }

    /**
     * 逐项校验事实 schema、冻结身份和规范参数；任一不符都让 Store 回滚整个 Core 事务。
     */
    private void persistNoteFact(Connection connection, AgentIdentity identity, AgentTool.ExtensionFact fact) {
        if (fact.schemaVersion() != 1) {
            throw new IllegalStateException("unsupported note.write fact version");
        }
        JsonObject payload = fact.payload();
        String callKey = requiredText(payload, "callKey");
        String title = requiredText(payload, "title");
        String body = requiredText(payload, "body");
        List<String> sourceIds = textArray(payload, "sourceAttachmentIds");
        String fingerprint = requiredText(payload, "argumentFingerprint");
        if (!NoteFingerprint.of(title, body, sourceIds).equals(fingerprint)) {
            throw new IllegalStateException("note.write fingerprint does not match its arguments");
        }
        try {
            List<AttachmentMetadata> ownedSources = attachments.requireOwned(connection, identity, sourceIds);
            if (ownedSources.size() != sourceIds.size()) {
                throw new IllegalStateException("note.write source set is incomplete");
            }
            notes.save(connection, identity, callKey, title, body, sourceIds, fingerprint);
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("note.write could not be committed", failure);
        }
    }

    /**
     * 恢复只查询同一冻结 owner 下的既有回执，不会通过普通 resume 重试写入。
     */
    public Optional<Note> noteForRecovery(AgentIdentity identity, String callKey) {
        return notes.findForRecovery(identity, callKey);
    }

    /**
     * 只返回当前服务端 actor 拥有的 Thread 笔记，最多读取 repository 规定的最近 100 条。
     */
    public List<Note> notes(String tenantId, String userId, String threadId) {
        return notes.list(tenantId, userId, threadId);
    }

    /**
     * Skill 来源只按 tenant 隔离；请求级变量维持空值，避免从模型或页面接受路径覆盖。
     */
    private static SkillRequestContext skillContext(String tenantId) {
        return new SkillRequestContext(tenantId, Map.of());
    }

    /**
     * 包已存在时保留原正文，并发创建只容忍明确版本冲突且再次确认包已经出现。
     */
    private void seed(SkillRequestContext context, String name, String description, String body) {
        if (skills(context).stream().anyMatch(value -> value.name().equals(name))) {
            return;
        }
        try {
            skills.write(context, name, description, body, 0);
        } catch (SkillVersionConflictException conflict) {
            if (skills(context).stream().noneMatch(value -> value.name().equals(name))) {
                throw conflict;
            }
        }
    }

    /**
     * 读取 note.write 的字符串字段时拒绝 JSON 类型漂移与空值，避免回滚后部分保存。
     */
    private static String requiredText(JsonObject payload, String field) {
        JsonValue value = payload.get(field);
        if (!(value instanceof JsonText text) || text.value().isBlank()) {
            throw new IllegalStateException("note.write fact has invalid " + field);
        }
        return text.value();
    }

    /**
     * 只接受去重且不超过 Core 附件调用上限的结构化 sourceAttachmentIds 列表。
     */
    private static List<String> textArray(JsonObject payload, String field) {
        JsonValue value = payload.get(field);
        if (!(value instanceof JsonArray array)) {
            throw new IllegalStateException("note.write fact has invalid " + field);
        }
        List<String> items = new ArrayList<>();
        for (JsonValue item : array.values()) {
            if (!(item instanceof JsonText text) || text.value().isBlank()) {
                throw new IllegalStateException("note.write fact has invalid " + field);
            }
            items.add(text.value());
        }
        if (items.stream().distinct().count() != items.size()) {
            throw new IllegalStateException("note.write fact has duplicate sources");
        }
        return List.copyOf(items);
    }

    /** Skill 编辑命令以当前版本 CAS，零仅用于创建。
     *
     * @param name Skill 稳定名称
     * @param description 展示给用户和模型的用途说明
     * @param body 本次要持久化的 Skill 正文
     * @param expectedVersion 客户端最近读取到的版本号
     */
    public record SkillWrite(String name,
                             String description,
                             String body,
                             long expectedVersion) {
    }

    /** Skill 删除只接受已观察版本，不借操作名猜测资源身份。
     *
     * @param name 要删除的 Skill 稳定名称
     * @param expectedVersion 客户端最近读取到的版本号
     */
    public record SkillDelete(String name,
                              long expectedVersion) {
    }

    /** 页面公开内容不含 Skill 来源路径或数据库凭据。
     *
     * @param name Skill 稳定名称
     * @param description 展示给用户和模型的用途说明
     * @param reference 可直接提交给 Core 的 Skill 来源引用
     * @param version 目录当前分配的版本号
     * @param body 页面读取时返回的 Skill 正文
     */
    public record SkillView(String name,
                            String description,
                            String reference,
                            long version,
                            String body) {
    }
}
