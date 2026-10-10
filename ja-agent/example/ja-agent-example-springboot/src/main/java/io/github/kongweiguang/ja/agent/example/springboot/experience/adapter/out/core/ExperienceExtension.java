// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.experience.adapter.out.core;

import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionStatus;
import io.github.kongweiguang.ja.agent.domain.interaction.InteractionWait;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.ExperienceService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.Note;
import io.github.kongweiguang.ja.agent.example.springboot.experience.domain.NoteFingerprint;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceJsonCodec;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.ExperienceToolCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.gateway.McpDirectory;
import io.github.kongweiguang.ja.agent.application.gateway.AgentExtension;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

/**
 * 使用 Core 原生扩展协议展示真实工具、Skill、审批与澄清，不在 Controller 模拟模型循环。
 */
public final class ExperienceExtension implements AgentExtension, ExperienceToolCatalog {
    private final ExperienceService experience;
    private final McpDirectory mcp;
    private final AgentStore store;
    private final PostgresSkillSource skills;
    private final ExecutionControlCodec controlCodec;
    private final ExperienceJsonCodec jsonCodec;
    private final EnterpriseDocumentMiddleware documentMiddleware;

    /**
     * 捕获 Spring 管理的宿主依赖；共享 codec 使模型工具与 HTTP 边界采用同一转换语义，所有权仍归组合根。
     */
    public ExperienceExtension(ExperienceService experience, McpDirectory mcp, AgentStore store,
                               PostgresSkillSource skills, ExecutionControlCodec controlCodec,
                               ExperienceJsonCodec jsonCodec,
                               EnterpriseDocumentMiddleware documentMiddleware) {
        this.experience = Objects.requireNonNull(experience, "experience");
        this.mcp = Objects.requireNonNull(mcp, "mcp");
        this.store = Objects.requireNonNull(store, "store");
        this.skills = Objects.requireNonNull(skills, "skills");
        this.controlCodec = Objects.requireNonNull(controlCodec, "controlCodec");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.documentMiddleware = Objects.requireNonNull(documentMiddleware, "documentMiddleware");
    }

    /**
     * Schema 已检查类型，但宿主仍显式拒绝缺失或空值。
     */
    private static String text(JsonObject arguments, String name) {
        if (!(arguments.get(name) instanceof JsonText value) || value.value().isBlank())
            {
                throw new IllegalArgumentException("missing tool field " + name);
            }
        return value.value();
    }

    /**
     * 身份在构建时冻结，恢复仍使用同一组扩展。
     */
    @Override
    public String id() {
        return "spring-experience";
    }

    /**
     * 各能力独立工具名，审批声明按真实副作用分类。
     */
    @Override
    public void register(ExtensionRegistry registry) {
        registry.registerSkillSource(skills);
        definitions().forEach(spec -> registry.registerTool(new DemoTool(spec)));
        registry.registerMiddleware(documentMiddleware);
        registry.registerMiddleware(new Policy());
    }

    /**
     * 页面对工具目录使用相同定义，避免可见入口与模型能力漂移。
     */
    @Override
    public List<ToolSpec> definitions() {
        Map<String, Object> text = Map.of("type", "string", "minLength", 1);
        return List.of(
                spec("read_skill", "Read a PostgreSQL Skill by name.", Map.of("name", text), List.of("name")),
                spec("mcp_tools", "Discover actual MCP servers, tool names and input schemas before invoking mcp_call.", Map.of(), List.of()),
                spec("mcp_call", "Call a discovered MCP tool. argumentsJson is a JSON object encoded as a string. All HTTP MCP calls require approval.",
                        Map.of("server", text, "tool", text, "argumentsJson", text), List.of("server", "tool", "argumentsJson")),
                spec("save_note", "Persist a note in PostgreSQL for this Thread. Requires user approval before writing.",
                        Map.of("title", Map.of("type", "string", "minLength", 1, "maxLength", 200),
                                "body", Map.of("type", "string", "minLength", 1, "maxLength", 16_000),
                                "sourceAttachmentIds", Map.of("type", "array", "items", text)),
                        List.of("title", "body")),
                spec("list_notes", "Read notes that were actually saved for the current Thread.", Map.of(), List.of()),
                spec("request_user_input", "Ask the user a real question and wait for their answer before continuing.",
                        Map.of("prompt", text, "options", Map.of("type", "array", "items", text, "maxItems", 6)), List.of("prompt", "options")),
                spec("wait_seconds", "Wait for 1 to 30 seconds; cancellation interrupts this read-only demonstration tool.",
                        Map.of("seconds", Map.of("type", "integer", "minimum", 1, "maximum", 30)), List.of("seconds")));
    }

    /**
     * Schema 通过结构化转换生成，所有工具参数都有明确闭集。
     */
    private ToolSpec spec(String name, String description, Map<String, ?> properties, List<String> required) {
        return new ToolSpec(name, description, object(Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false)));
    }

    /**
     * 使用已有 JsonValue 适配器，避免字符串拼接产生模型参数错误。
     */
    private JsonObject object(Object value) {
        return jsonCodec.object(value);
    }

    /**
     * 动态远程 Schema 无法声明成 OpenAI strict 闭集，边界用 JSON 字符串并交给结构化解析器。
     */
    private JsonObject mcpArguments(JsonObject arguments) {
        String value = text(arguments, "argumentsJson");
        if (value.length() > 32_768) {
            throw new IllegalArgumentException("MCP arguments limit");
        }
        try {
            return jsonCodec.object(value);
        } catch (IllegalArgumentException failure) {
            throw failure;
        }
    }

    /**
     * 统一序列化公开结果，不通过 toString 泄露宿主服务对象。
     */
    private String json(Object value) {
        return jsonCodec.write(value);
    }

    /**
     * 对已结算的澄清读取真实持久答案；固定执行快照水位并逐页检查取消和游标进展，不猜测用户 owner。
     */
    String answer(String callId, AgentIdentity identity, CancellationToken cancellation) {
        return readAnswer(store, controlCodec, jsonCodec, callId, identity, cancellation);
    }

    /**
     * 分离只读分页算法，使它可在不装配整个示例扩展的情况下验证长历史恢复。
     */
    static String readAnswer(AgentStore store, ExecutionControlCodec controlCodec, ExperienceJsonCodec jsonCodec,
                             String callId, AgentIdentity identity, CancellationToken cancellation) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(controlCodec, "controlCodec");
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(cancellation, "cancellation");
        AgentStore.ExecutionKey key = new AgentStore.ExecutionKey(
                new AgentStore.ConversationKey(identity.tenantId(), identity.threadId(), identity.userId()),
                identity.turnId());
        cancellation.throwIfCancellationRequested();
        long through = store.loadExecution(key).orElseThrow(() -> new IllegalStateException("execution not found"))
                .lastRecordSequence();
        long cursor = 0;
        while (true) {
            cancellation.throwIfCancellationRequested();
            AgentStore.RecordPage page = store.readRecords(
                    AgentStore.RecordQuery.withDefaultMaxBytes(key, cursor, through, 256));
            for (AgentStore.StoredRecord record : page.records()) {
                if (!record.recordType().equals(ExecutionControlCodec.WAIT_RECORD_TYPE)) {
                    continue;
                }
                InteractionWait wait = controlCodec.decodeWait(record);
                if (callId.equals(wait.toolCallId()) && wait.status() == InteractionStatus.ANSWERED)
                    {
                        return jsonCodec.write(wait.answers());
                    }
            }
            if (!page.hasMore()) {
                throw new IllegalStateException("answered wait not found");
            }
            if (page.nextSequence() <= cursor) {
                throw new IllegalStateException("wait record cursor did not advance");
            }
            cursor = page.nextSequence();
        }
    }

    /**
     * 工具实现只接收其固定 Spec；实际依赖保持在宿主扩展中。
     */
    private final class DemoTool implements AgentTool {
        private final ToolSpec spec;

        /**
         * 定义与注册时保持同一不可变对象，恢复核验不会猜测 Schema。
         */
        private DemoTool(ToolSpec spec) {
            this.spec = spec;
        }

        /**
         * 注册表和目录读取同一能力声明。
         */
        @Override
        public ToolSpec spec() {
            return spec;
        }

        /**
         * 本地写入和外部 MCP 调用保守声明副作用，其余只读。
         */
        @Override
        public ToolSideEffect sideEffect() {
            return spec.name().equals("save_note") || spec.name().equals("mcp_call") ? ToolSideEffect.EXTERNAL : ToolSideEffect.READ_ONLY;
        }

        /**
         * 明确只读工具无需审批；副作用工具仍由原生等待流程授权。
         */
        @Override
        public ApprovalRequirement approvalRequirement() {
            return sideEffect() == ToolSideEffect.READ_ONLY ? ApprovalRequirement.NOT_REQUIRED : ApprovalRequirement.REQUIRED;
        }

        /**
         * 在可能崩溃的 Started 调用前冻结规范参数摘要，后续裁决只核验已有业务回执。
         */
        @Override
        public Optional<RecoveryEvidence> prepareRecoveryEvidence(Invocation invocation, ExecutionContext context,
                                                                   CancellationToken cancellationToken) {
            if (!spec.name().equals("save_note")) {
                return Optional.empty();
            }
            cancellationToken.throwIfCancellationRequested();
            String title = text(invocation.arguments(), "title");
            String body = text(invocation.arguments(), "body");
            List<String> sources = sourceIds(invocation.arguments());
            String fingerprint = NoteFingerprint.of(title, body, sources);
            JsonObject evidence = object(Map.of("callKey", context.callKey(), "title", title, "body", body,
                    "sourceAttachmentIds", sources));
            return Optional.of(new RecoveryEvidence("note-write", 1, evidence, fingerprint));
        }

        /**
         * 仅按冻结 owner、callKey 和参数指纹查询数据库；无回执返回 NotApplied 供用户显式决定重试。
         */
        @Override
        public CompletionStage<RecoveryResolution> reconcile(Invocation invocation, ExecutionContext context,
                                                               Optional<RecoveryEvidence> evidence,
                                                               CancellationToken cancellationToken) {
            if (!spec.name().equals("save_note")) {
                return AgentTool.super.reconcile(invocation, context, evidence, cancellationToken);
            }
            cancellationToken.throwIfCancellationRequested();
            try {
                String title = text(invocation.arguments(), "title");
                String body = text(invocation.arguments(), "body");
                List<String> sources = sourceIds(invocation.arguments());
                String fingerprint = NoteFingerprint.of(title, body, sources);
                if (evidence.isPresent() && (!evidence.get().typeId().equals("note-write")
                        || evidence.get().schemaVersion() != 1
                        || !evidence.get().fingerprint().equals(fingerprint))) {
                    return CompletableFuture.completedFuture(new RecoveryResolution.Unknown("NOTE_EVIDENCE_MISMATCH"));
                }
                Optional<Note> persisted = experience.noteForRecovery(context.identity(), context.callKey());
                if (persisted.isEmpty()) {
                    return CompletableFuture.completedFuture(new RecoveryResolution.NotApplied(fingerprint));
                }
                Note note = persisted.get();
                if (!note.matches(context.identity().tenantId(), context.identity().userId(),
                        context.identity().threadId(), context.identity().turnId(), title, body, sources, fingerprint)) {
                    return CompletableFuture.completedFuture(new RecoveryResolution.Unknown("NOTE_RECEIPT_CONFLICT"));
                }
                ExecutionResult result = new ExecutionResult(ToolResult.success(noteResult(note)),
                        List.of(noteWriteFact(note)));
                return CompletableFuture.completedFuture(new RecoveryResolution.Applied(result, fingerprint));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(new RecoveryResolution.Unknown("NOTE_RECONCILE_FAILED"));
            }
        }

        /**
         * 成功只来自实际返回；返回的 ExecutionResult 将结算资源生命周期交给 Core，由 Core 在原子提交后释放。
         */
        @SuppressWarnings("PMD.CloseResource")
        @Override
        public CompletionStage<ExecutionResult> execute(Invocation invocation, ExecutionContext context, CancellationToken cancellation) {
            long started = System.nanoTime();
            cancellation.throwIfCancellationRequested();
            try {
                if (spec.name().equals("save_note")) {
                    ExecutionResult result = saveNote(invocation, context);
                    return CompletableFuture.completedFuture(result.withExecutionDurationMillis(
                            (System.nanoTime() - started) / 1_000_000));
                }
                String result = switch (spec.name()) {
                    case "read_skill" -> json(experience.readSkill(context.identity(), text(invocation.arguments(), "name")));
                    case "mcp_tools" -> json(mcp.catalog());
                    case "mcp_call" -> mcp.call(text(invocation.arguments(), "server"), text(invocation.arguments(), "tool"),
                            mcpArguments(invocation.arguments()), cancellation);
                    case "list_notes" -> json(experience.notes(context.identity().tenantId(),
                            context.identity().userId(), context.identity().threadId()));
                    case "request_user_input" -> answer(invocation.callId(), context.identity(), cancellation);
                    case "wait_seconds" -> waitFor(invocation, cancellation);
                    default -> throw new IllegalStateException("unregistered experience tool");
                };
                return CompletableFuture.completedFuture(ExecutionResult.of(ToolResult.success(result))
                        .withExecutionDurationMillis((System.nanoTime() - started) / 1_000_000));
            } catch (java.util.concurrent.CancellationException cancelled) {
                if (!spec.name().equals("wait_seconds")) {
                    throw cancelled;
                }
                return CompletableFuture.completedFuture(ExecutionResult.of(new ToolResult(ToolOutcome.CANCELLED,
                        "Local wait stopped.", Optional.empty(), "CANCELLED")));
            } catch (IllegalArgumentException failure) {
                return CompletableFuture.completedFuture(ExecutionResult.of(new ToolResult(ToolOutcome.FAILED, "Invalid tool arguments or MCP error.", Optional.empty(), "EXPERIENCE_ARGUMENTS_INVALID")));
            }
        }

        /**
         * 工具执行阶段只提出待保存内容；真正写入由 note.write participant 与原始 ToolResult 同事务完成。
         */
        private ExecutionResult saveNote(Invocation invocation, ExecutionContext context) {
            String title = text(invocation.arguments(), "title");
            String body = text(invocation.arguments(), "body");
            List<String> sources = sourceIds(invocation.arguments());
            String fingerprint = NoteFingerprint.of(title, body, sources);
            return new ExecutionResult(ToolResult.success(noteResult(context.callKey(), title, sources)),
                    List.of(noteWriteFact(context.callKey(), title, body, sources, fingerprint)));
        }

        /**
         * 只为 note.write 生成稳定扩展事实，participant 会用当前 Store Connection 核验来源并保存。
         */
        private AgentTool.ExtensionFact noteWriteFact(Note note) {
            return noteWriteFact(note.callKey(), note.title(), note.body(), note.sourceAttachmentIds(),
                    note.argumentFingerprint());
        }

        /**
         * 工具请求和恢复回执共同构造同一 schema 的 fact，participant 不需要猜测来源字段。
         */
        private AgentTool.ExtensionFact noteWriteFact(String callKey, String title, String body,
                                                       List<String> sources, String fingerprint) {
            return new AgentTool.ExtensionFact(EnterpriseDocumentMiddleware.NAMESPACE, "note.write", 1,
                    object(Map.of("callKey", callKey, "title", title, "body", body,
                            "sourceAttachmentIds", sources, "argumentFingerprint", fingerprint)));
        }

        /**
         * 生成与回执一一对应的模型结果；事务失败时 Core 不会将该成功内容提交或交给模型。
         */
        private String noteResult(Note note) {
            return noteResult(note.callKey(), note.title(), note.sourceAttachmentIds());
        }

        /**
         * 使用事务回执同形字段展示保存结果，避免将正文再复制进模型上下文。
         */
        private String noteResult(String callKey, String title, List<String> sources) {
            return json(Map.of("status", "saved", "callKey", callKey, "title", title,
                    "sourceAttachmentIds", sources));
        }

        /**
         * 读取 middleware 规范化后传入的来源数组；缺失或伪造重复 ID 在副作用前失败关闭。
         */
        private List<String> sourceIds(JsonObject arguments) {
            if (!(arguments.get("sourceAttachmentIds") instanceof JsonArray array)) {
                throw new IllegalArgumentException("invalid note sources");
            }
            List<String> values = new ArrayList<>();
            for (io.github.kongweiguang.ja.agent.domain.json.JsonValue value : array.values()) {
                if (!(value instanceof JsonText text) || text.value().isBlank()) {
                    throw new IllegalArgumentException("invalid note sources");
                }
                values.add(text.value());
            }
            if (values.stream().distinct().count() != values.size()) {
                throw new IllegalArgumentException("duplicate note sources");
            }
            return List.copyOf(values);
        }

        /**
         * 短间隔检查取消，避免中断标记带入 JDBC；调用方将已确认停止的本地等待结算为取消结果。
         */
        private String waitFor(Invocation invocation, CancellationToken cancellation) {
            int seconds = ((io.github.kongweiguang.ja.agent.domain.json.JsonNumber) invocation.arguments().get("seconds")).value().intValueExact();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(seconds).toNanos();
            try {
                while (System.nanoTime() < deadline) {
                    cancellation.throwIfCancellationRequested();
                    Thread.sleep(100);
                }
                cancellation.throwIfCancellationRequested();
                return "Waited " + seconds + " seconds.";
            } catch (InterruptedException interrupted) {
                cancellation.throwIfCancellationRequested();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("wait interrupted", interrupted);
            }
        }
    }

    /**
     * 中间件负责工具准入，副作用不在回调里执行，等待和回答全部由 Core 持久化。
     */
    private final class Policy implements AgentMiddleware {
        /**
         * 稳定策略身份保证恢复不会静默换一套授权规则。
         */
        @Override
        public String id() {
            return "spring-experience-policy";
        }

        /**
         * 增补能力使用原则，仍让 Provider 发起原生结构化工具调用。
         */
        @Override
        public ExtensionDecision<RequestPatch> beforeRequest(RequestContext context) {
            return ExtensionDecision.unchanged(RequestPatch.unchanged(), context.extensionState());
        }

        /**
         * 审批基于真实副作用，澄清问题来自冻结工具参数。
         */
        @Override
        public ExtensionDecision<ToolDecision> beforeTool(ToolContext context) {
            if (context.invocation().toolName().equals("request_user_input")) {
                JsonObject arguments = context.invocation().arguments();
                JsonArray labels = (io.github.kongweiguang.ja.agent.domain.json.JsonArray) arguments.get("options");
                ArrayList<Map<String, Object>> options = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < labels.values().size(); index++) {
                    String label = ((JsonText) labels.values().get(index)).value();
                    options.add(Map.of("optionId", "option_" + index, "label", label, "description", label, "recommended", false));
                }
                Map<String, Serializable> question = Map.of("questionId", "question_demo", "prompt", text(arguments, "prompt"), "type", options.isEmpty() ? "TEXT" : "SINGLE", "options", options, "required", true, "allowFreeText", options.isEmpty());
                return ExtensionDecision.unchanged(ToolDecision.waitFor(new WaitRequest("input", text(arguments, "prompt"), object(Map.of("questions", List.of(question))))), context.extensionState());
            }
            if (context.approvalRequirement() == AgentTool.ApprovalRequirement.REQUIRED) {
                return ExtensionDecision.unchanged(ToolDecision.waitFor(new WaitRequest("approval", "允许执行 " + context.invocation().toolName() + "？\n" + json(context.invocation().arguments()),
                        object(Map.of("expiresAt", Instant.now().plusSeconds(900).toString())))), context.extensionState());
            }
            return AgentMiddleware.super.beforeTool(context);
        }
    }
}

