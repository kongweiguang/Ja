// @author kongweiguang
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web;

import io.github.kongweiguang.ja.agent.example.springboot.experience.application.ExperienceService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web.dto.NotesQuery;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceJsonCodec;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceToolCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.McpDirectory;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.in.web.dto.ExperienceToolView;
import io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core.EnterpriseDocumentMiddleware;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.Note;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.JsonSchemaSnapshot;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActor;
import io.github.kongweiguang.ja.agent.example.springboot.identity.CurrentActorProvider;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ChatNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Set;

/**
 * 管理接口只操作演示来源；模型工具始终经 Engine 而不被页面直接执行。
 */
@RestController
@RequestMapping("/api/chat/experience")
@ConditionalOnProperty(name = "agent.experience.enabled", havingValue = "true", matchIfMissing = true)
public class ExperienceController {
    private final ExperienceService experience;
    private final McpDirectory mcp;
    private final ExperienceToolCatalog tools;
    private final ExperienceJsonCodec json;
    private final CurrentActorProvider actors;
    private final AgentStore store;
    private final ExtensionFactRecordCodec factCodec;

    /**
     * 管理页与 Agent 注册共享 Spring 管理的无状态 codec，保证 HTTP Schema 与工具调用使用同一 JSON 语义；Controller 不接管其配置所有权。
     */
    public ExperienceController(ExperienceService experience, McpDirectory mcp, ExperienceToolCatalog tools,
                                ExperienceJsonCodec json, CurrentActorProvider actors, AgentStore store,
                                ExtensionFactRecordCodec factCodec) {
        this.experience = experience;
        this.mcp = mcp;
        this.tools = tools;
        this.json = json;
        this.actors = Objects.requireNonNull(actors, "actors");
        this.store = Objects.requireNonNull(store, "store");
        this.factCodec = Objects.requireNonNull(factCodec, "factCodec");
    }

    /**
     * Core 的 JsonValue 只在 HTTP 边界转为普通 Map，避免暴露 Jackson 2 类型给 Spring。
     */
    @PostMapping("/tools")
    public List<ExperienceToolView> tools() {
        return tools.definitions().stream().map(spec -> new ExperienceToolView(spec.name(), spec.description(),
                json.objectMap(spec.inputSchema()))).toList();
    }

    /**
     * 列表仅取描述和版本，正文延迟到用户编辑或 Core 读取。
     */
    @PostMapping("/skills")
    public List<SkillDescriptor> skills() {
        return experience.skills(skillContext(actors.currentActor()));
    }

    /**
     * 名称放正文，不把用户选择编码到路径。
     */
    @PostMapping("/skills/read")
    public ExperienceService.SkillView read(@RequestBody Map<String, String> command) {
        return experience.readSkill(skillContext(actors.currentActor()), command.get("name"));
    }

    /**
     * 原生 PG source CAS 防止覆盖并发编辑。
     */
    @PostMapping("/skills/write")
    public SkillDescriptor write(@RequestBody ExperienceService.SkillWrite command) {
        return experience.writeSkill(skillContext(actors.currentActor()), command);
    }

    /**
     * 删除由显式管理操作触发，服务仍校验包版本。
     */
    @PostMapping("/skills/delete")
    public Map<String, Boolean> delete(@RequestBody ExperienceService.SkillDelete command) {
        experience.deleteSkill(skillContext(actors.currentActor()), command);
        return Map.of("deleted", true);
    }

    /**
     * 目录已是可序列化的普通值，打开页签才发起真实握手，无需再次投影。
     */
    @PostMapping("/mcp")
    public List<McpDirectory.ServerView> mcp() {
        return mcp.catalog();
    }

    /** 严格限定 Notes 请求字段以拒绝身份注入和旧接口字段；owner 始终来自服务端 actor。 */
    @PostMapping("/notes")
    public List<NoteView> notes(@RequestBody Map<String, String> command) {
        if (command == null || !command.keySet().equals(Set.of("threadId"))) {
            throw new IllegalArgumentException("notes query accepts only threadId");
        }
        NotesQuery query = new NotesQuery(command.get("threadId"));
        CurrentActor actor = actors.currentActor();
        return experience.notes(actor.tenantId(), actor.userId(), query.threadId()).stream()
                .map(NoteView::from).toList();
    }

    /**
     * 按当前 actor 与指定执行读取有限页策略事实，Turn 和 owner 均从服务端身份构造。
     */
    @PostMapping("/policy/facts")
    public PolicyFactsPage policyFacts(@RequestBody PolicyFactsQuery query) {
        if (query.threadId() == null || query.threadId().isBlank()
                || query.turnId() == null || query.turnId().isBlank()
                || query.afterSequence() < 0 || query.limit() < 1 || query.limit() > 256) {
            throw new IllegalArgumentException("invalid policy fact query");
        }
        CurrentActor actor = actors.currentActor();
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(actor.tenantId(), query.threadId(), actor.userId()), query.turnId());
        AgentStore.ExecutionSnapshot execution = store.loadExecution(key)
                .orElseThrow(() -> new ChatNotFoundException("execution not found"));
        if (query.afterSequence() > execution.lastRecordSequence()) {
            throw new IllegalArgumentException("policy fact cursor is beyond execution history");
        }
        AgentStore.RecordPage page = store.readRecords(AgentStore.RecordQuery.withDefaultMaxBytes(key,
                query.afterSequence(), execution.lastRecordSequence(), query.limit()));
        List<PolicyFactView> facts = new ArrayList<>();
        for (AgentStore.StoredRecord record : page.records()) {
            if (!ExtensionFactRecordCodec.RECORD_TYPE.equals(record.recordType())) {
                continue;
            }
            AgentTool.ExtensionFact fact = factCodec.decode(record);
            if (EnterpriseDocumentMiddleware.NAMESPACE.equals(fact.namespace())
                    && fact.type().startsWith("document.")) {
                facts.add(new PolicyFactView(query.turnId(), fact.namespace(), fact.type(), fact.schemaVersion(),
                        json.objectMap(fact.payload()), record.occurredAt().toString()));
            }
        }
        return new PolicyFactsPage(facts, page.nextSequence(), page.hasMore());
    }

    /**
     * 将唯一服务端 actor 投影成 Core Skill 来源只读 tenant 上下文。
     */
    private static SkillRequestContext skillContext(CurrentActor actor) {
        return new SkillRequestContext(actor.tenantId(), Map.of());
    }

    /** 查询策略事实时使用客户端游标，不接受客户端身份或执行 owner。
     *
     * @param threadId 事实所属的 Thread 标识
     * @param turnId 事实所属的 Turn 标识
     * @param afterSequence 已读取的持久事实序号游标
     * @param limit 本页最多返回的事实数量
     */
    public record PolicyFactsQuery(String threadId,
                                   String turnId,
                                   long afterSequence,
                                   int limit) {
    }

    /** 按 Core envelope 原样暴露已提交的企业策略事实。
     *
     * @param turnId 产生该事实的 Turn 标识
     * @param namespace 区分策略事实集合的稳定命名空间
     * @param type 事实业务类型
     * @param schemaVersion 事实 payload 使用的版本
     * @param payload 已校验并复制的事实内容
     * @param occurredAt 事实持久化时的 ISO-8601 时间文本
     */
    public record PolicyFactView(String turnId,
                                 String namespace,
                                 String type,
                                 int schemaVersion,
                                 Map<String, Object> payload,
                                 String occurredAt) {
        /** 深度复制解码器返回的可变 Map/List，并冻结顶层 DTO，避免响应调用方修改策略事实快照。 */
        public PolicyFactView {
            payload = Map.copyOf(JsonSchemaSnapshot.copyOf(payload));
        }
    }

    /** 策略事实页的 cursor 对应 Store 的记录序号，不以过滤后行数推断。
     *
     * @param facts 当前页读取到的策略事实
     * @param nextSequence 下一页开始读取的持久序号
     * @param hasMore 水位内是否仍有下一页事实
     */
    public record PolicyFactsPage(List<PolicyFactView> facts,
                                  long nextSequence,
                                  boolean hasMore) {
        /** 固定返回集合避免 MVC 序列化期间 Store 页面被调用方修改。 */
        public PolicyFactsPage {
            facts = List.copyOf(facts);
        }
    }

    /** 面向页面的笔记投影不暴露 owner、正文指纹或内部来源关联行。
     *
     * @param callKey 已提交 note.write 工具调用的稳定身份
     * @param turnId 保存该笔记的 Turn 标识
     * @param title 已持久化的笔记标题
     * @param body 已持久化的笔记正文
     * @param sourceAttachmentIds 笔记引用的附件标识
     * @param createdAt 笔记创建时间的 ISO-8601 文本
     */
    public record NoteView(String callKey,
                           String turnId,
                           String title,
                           String body,
                           List<String> sourceAttachmentIds,
                           String createdAt) {
        /** 只返回不可变来源列表与明确 ISO-8601 时间文本，避免 MVC 对 domain Instant 做隐式配置假设。 */
        public NoteView {
            sourceAttachmentIds = List.copyOf(sourceAttachmentIds);
        }

        /** 由持久回执投影页面需要的字段，省略租户、用户和可猜测内容的参数摘要。 */
        private static NoteView from(Note note) {
            return new NoteView(note.callKey(), note.turnId(), note.title(), note.body(),
                    note.sourceAttachmentIds(), note.createdAt().toString());
        }
    }
}
