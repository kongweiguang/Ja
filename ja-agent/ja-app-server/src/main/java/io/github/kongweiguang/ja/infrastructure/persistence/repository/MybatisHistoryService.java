// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.context.checkpoint.StoreBackedCheckpointStore;
import io.github.kongweiguang.ja.agent.domain.error.StorageException;
import io.github.kongweiguang.ja.agent.domain.pagination.CursorPage;
import io.github.kongweiguang.ja.agent.domain.turn.TurnState;
import io.github.kongweiguang.ja.agent.domain.context.InitialContextSeed;
import io.github.kongweiguang.ja.agent.domain.UserContent;
import io.github.kongweiguang.ja.agent.domain.model.UserContentBlock;
import io.github.kongweiguang.ja.agent.domain.model.TextContent;
import io.github.kongweiguang.ja.agent.domain.model.AttachmentContent;
import io.github.kongweiguang.ja.agent.domain.model.ResourceReferenceContent;
import io.github.kongweiguang.ja.agent.domain.model.SkillReferenceContent;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFact;
import io.github.kongweiguang.ja.agent.domain.execution.ExecutionFacts;
import io.github.kongweiguang.ja.agent.infrastructure.json.JacksonJsonValueCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.JdbcTransactionResources;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore.ConversationKey;
import io.github.kongweiguang.ja.agent.application.gateway.store.CheckpointStore;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.PersistenceCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.InitialContextSeedCodec;
import io.github.kongweiguang.ja.foundation.runtime.AppServerIdentity;
import io.github.kongweiguang.ja.conversation.domain.model.AttachmentSummary;
import io.github.kongweiguang.ja.conversation.domain.model.CollaborationMode;
import io.github.kongweiguang.ja.conversation.domain.model.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.model.SubagentPolicy;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.model.ThreadUsageSummary;
import io.github.kongweiguang.ja.conversation.domain.model.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.model.UserInputHistory;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.model.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.model.permission.AccessMode;
import io.github.kongweiguang.ja.conversation.application.api.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.application.api.ProcessSessionUseCase;
import io.github.kongweiguang.ja.conversation.domain.model.ProcessSessionScope;
import io.github.kongweiguang.ja.conversation.application.gateway.InitialContextSeedSource;
import io.github.kongweiguang.ja.conversation.application.gateway.JaThreadDefinition;
import io.github.kongweiguang.ja.conversation.application.gateway.SubagentPolicySource;
import io.github.kongweiguang.ja.collaboration.application.gateway.TaskRepository;
import io.github.kongweiguang.ja.collaboration.domain.model.TaskModels;
import io.github.kongweiguang.ja.collaboration.infrastructure.persistence.InitialContextSeedAttachmentMapper;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.AttachmentRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ThreadForkRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.store.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.AgentStoreConnectionScope;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.catalog.application.gateway.ThreadMcpThreadPort;
import io.github.kongweiguang.ja.workspace.domain.model.Workspace;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceBusyMessage;
import io.github.kongweiguang.ja.workspace.domain.model.WorkspaceFailure;
import io.github.kongweiguang.ja.workspace.application.gateway.WorkspaceRepository;
import org.apache.ibatis.session.SqlSessionFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Workspace 出站仓储与 Thread 入站端口的单库实现；分页和快照不依赖进程级活动状态。
 */
public final class MybatisHistoryService implements WorkspaceRepository, ThreadUseCase, ThreadMcpThreadPort,
        InitialContextSeedSource {
    private static final AppServerIdentity APP_IDENTITY = AppServerIdentity.local();
    private static final int MAX_PAGE = 500;
    // 与首版 schema 和 TurnChangeTracker 的冻结上限共同构成持久 artifact 的硬边界。
    private static final int MAX_CHANGE_SET_ARTIFACT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHANGE_SET_FILES = 256;
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");
    private final MybatisUnitOfWork transactions;
    private final AgentStore agentStore;
    private final SubagentPolicySource subagentPolicySource;
    private final ToolPresentationCodec presentations;
    private final TurnChangeSetCodec changeSets;
    private final PersistenceCodec codec;
    private final TurnExecutionStateCodec executionCursors;
    private final StoreBackedCheckpointStore checkpoints;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AtomicReference<ProcessSessionUseCase> processSessions = new AtomicReference<>();
    private final AtomicReference<TaskRepository> threadForkSnapshots = new AtomicReference<>();
    private final ThreadForkSeedCodec threadForkSeeds;
    private final InitialContextSeedCodec contextMessageCodec;

    /**
     * History 的元数据版本统一由 JaAgentStore 推进，避免 UI 写入与执行 revision 分叉。
     */
    public MybatisHistoryService(SqlSessionFactory sessions, AgentStore agentStore,
                                 SubagentPolicySource subagentPolicySource, ObjectMapper objectMapper, Clock clock) {
        this(new MybatisUnitOfWork(sessions), agentStore, subagentPolicySource, objectMapper, clock);
    }

    /**
     * 聚焦测试替换 MyBatis 事务 owner，AgentStore 与 mapper 仍共享同一测试数据库。
     */
    public MybatisHistoryService(SqlSessionFactory sessions, AgentStore agentStore,
                                 SubagentPolicySource subagentPolicySource, ObjectMapper objectMapper, Clock clock,
                                 MybatisUnitOfWork.SessionOwner owner) {
        this(new MybatisUnitOfWork(sessions, owner), agentStore, subagentPolicySource, objectMapper, clock);
    }

    /**
     * 只在入口选择事务 owner，其余依赖共用一处初始化，避免测试与生产的历史解码规则漂移。
     */
    private MybatisHistoryService(MybatisUnitOfWork transactions, AgentStore agentStore,
                                  SubagentPolicySource subagentPolicySource, ObjectMapper objectMapper, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.agentStore = Objects.requireNonNull(agentStore, "agentStore");
        this.subagentPolicySource = Objects.requireNonNull(subagentPolicySource, "subagentPolicySource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        threadForkSeeds = new ThreadForkSeedCodec(objectMapper);
        contextMessageCodec = new InitialContextSeedCodec(new JacksonJsonValueCodec(objectMapper));
        presentations = new ToolPresentationCodec(objectMapper);
        changeSets = new TurnChangeSetCodec(objectMapper);
        codec = new PersistenceCodec(objectMapper);
        executionCursors = new TurnExecutionStateCodec(objectMapper);
        checkpoints = new StoreBackedCheckpointStore(this.agentStore, new StorePayloadCodec(objectMapper));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * ProcessSession owner 在组合根发布后绑定一次；删除与归档共用此唯一资源生命周期边界。
     */
    public void bindProcessSessions(ProcessSessionUseCase sessions) {
        if (!processSessions.compareAndSet(null, Objects.requireNonNull(sessions, "sessions"))) {
            throw new IllegalStateException("process sessions were already bound");
        }
    }

    /**
     * Fork 只借用 Task owner 的 Core 快照冻结能力，不把 Task 状态或写入口带入新 Thread。
     */
    public void bindThreadForkSnapshots(TaskRepository snapshots) {
        if (!threadForkSnapshots.compareAndSet(null, Objects.requireNonNull(snapshots, "snapshots"))) {
            throw new IllegalStateException("thread fork snapshots were already bound");
        }
    }

    /**
     * 附件展示字段必须来自严格 JSON 字符串，不能把数字或 null 转成文本默认值。
     */
    private static String requiredAttachmentText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.textValue();
    }

    /**
     * 单次严格解析标准 unified diff：按 hunk 行数识别边界，正文中的 `---` 不会被误判成下一文件。
     */
    private static IndexedChangeSet indexChangeSet(PersistenceRecords.ChangeSetArtifactRow row) {
        byte[] bytes = row.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != row.byteLength() || bytes.length > MAX_CHANGE_SET_ARTIFACT_BYTES
            || !sha256(bytes).equals(row.sha256())) {
            throw new IllegalArgumentException("change set artifact length is invalid");
        }
        Map<String, ByteRange> files = new LinkedHashMap<>();
        int cursor = 0;
        while (cursor < bytes.length) {
            requireChangeSetReadActive();
            int fileStart = cursor;
            ParsedLine before = line(bytes, cursor);
            if (!before.text().startsWith("--- ")) {
                throw malformedChangeSet();
            }
            cursor = before.next();
            ParsedLine after = line(bytes, cursor);
            if (!after.text().startsWith("+++ ")) {
                throw malformedChangeSet();
            }
            cursor = after.next();
            String filePath = headerPath(before.text(), after.text());
            validateArtifactPath(filePath);
            boolean sawHunk = false;
            while (cursor < bytes.length && !startsWith(bytes, cursor, "--- ")) {
                requireChangeSetReadActive();
                ParsedLine hunk = line(bytes, cursor);
                HunkCounts counts = hunkCounts(hunk.text());
                sawHunk = true;
                cursor = hunk.next();
                int beforeLines = 0;
                int afterLines = 0;
                boolean previousBody = false;
                while (beforeLines < counts.before() || afterLines < counts.after()) {
                    requireChangeSetReadActive();
                    ParsedLine body = line(bytes, cursor);
                    if ("\\ No newline at end of file".equals(body.text())) {
                        if (!previousBody) {
                            throw malformedChangeSet();
                        }
                        previousBody = false;
                        cursor = body.next();
                        continue;
                    }
                    if (body.text().isEmpty()) {
                        throw malformedChangeSet();
                    }
                    char prefix = body.text().charAt(0);
                    if (prefix == ' ') {
                        beforeLines++;
                        afterLines++;
                    } else if (prefix == '-') {
                        beforeLines++;
                    }
                    else if (prefix == '+') {
                        afterLines++;
                    }
                    else {
                        throw malformedChangeSet();
                    }
                    if (beforeLines > counts.before() || afterLines > counts.after()) {
                        throw malformedChangeSet();
                    }
                    previousBody = true;
                    cursor = body.next();
                }
                if (cursor < bytes.length) {
                    ParsedLine marker = line(bytes, cursor);
                    if ("\\ No newline at end of file".equals(marker.text())) {
                        cursor = marker.next();
                    }
                }
                if (cursor < bytes.length && !startsWith(bytes, cursor, "@@ ")
                    && !startsWith(bytes, cursor, "--- ")) {
                        throw malformedChangeSet();
                    }
            }
            if (!sawHunk || files.putIfAbsent(filePath, new ByteRange(fileStart, cursor)) != null) {
                throw malformedChangeSet();
            }
            if (files.size() > MAX_CHANGE_SET_FILES) {
                throw malformedChangeSet();
            }
        }
        return new IndexedChangeSet(row.artifactId(), bytes, Map.copyOf(files));
    }

    /**
     * 每次请求只保留本次解析结果，并复制目标文件字节后立即释放聚合 artifact；文件摘要覆盖返回正文，
     * Rust 因而可以在 Base64 解码后独立验证身份、长度和内容完整性。
     */
    private static Optional<ChangeSetArtifactFile> extractChangeSetFile(
            PersistenceRecords.ChangeSetArtifactRow row, String filePath) {
        IndexedChangeSet indexed = indexChangeSet(row);
        ByteRange range = indexed.files().get(filePath);
        if (range == null) {
            return Optional.empty();
        }
        byte[] selected = java.util.Arrays.copyOfRange(indexed.bytes(), range.start(), range.end());
        return Optional.of(new ChangeSetArtifactFile(indexed.artifactId(), filePath, selected.length,
                sha256(selected), Base64.getEncoder().encodeToString(selected)));
    }

    /**
     * header 的 before/after 只能表达同一路径或单侧 `/dev/null`，其它组合均视为损坏。
     */
    private static String headerPath(String beforeHeader, String afterHeader) {
        String before = beforeHeader.substring(4);
        String after = afterHeader.substring(4);
        if ("/dev/null".equals(before) && after.startsWith("b/")) {
            return after.substring(2);
        }
        if ("/dev/null".equals(after) && before.startsWith("a/")) {
            return before.substring(2);
        }
        if (before.startsWith("a/") && after.startsWith("b/")
            && before.substring(2).equals(after.substring(2))) {
                return before.substring(2);
            }
        throw malformedChangeSet();
    }

    /**
     * hunk 计数是文件边界的权威依据；超出 int 或不完整 header 均失败关闭。
     */
    private static HunkCounts hunkCounts(String header) {
        Matcher matcher = HUNK_HEADER.matcher(header);
        if (!matcher.matches()) {
            throw malformedChangeSet();
        }
        try {
            return new HunkCounts(matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)),
                    matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4)));
        } catch (NumberFormatException invalid) {
            throw malformedChangeSet(invalid);
        }
    }

    /**
     * 字节扫描保留精确换行范围；所有持久 artifact 都必须以完整 LF 行结束。
     */
    private static ParsedLine line(byte[] bytes, int start) {
        if (start < 0 || start >= bytes.length) {
            throw malformedChangeSet();
        }
        int end = start;
        while (end < bytes.length && bytes[end] != '\n') {
            end++;
        }
        if (end == bytes.length) {
            throw malformedChangeSet();
        }
        return new ParsedLine(new String(bytes, start, end - start, StandardCharsets.UTF_8), end + 1);
    }

    /**
     * ASCII 结构前缀直接在 UTF-8 字节上比较，避免为边界探测重复解码正文。
     */
    private static boolean startsWith(byte[] bytes, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > bytes.length) {
            return false;
        }
        for (int index = 0; index < prefix.length(); index++) {
            if (bytes[offset + index] != (byte) prefix.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    /**
     * artifact 路径只是内部 key，但仍拒绝绝对、反斜杠、控制字符和父目录形状。
     */
    private static void validateArtifactPath(String filePath) {
        if (filePath == null || filePath.isBlank() || filePath.length() > 4_096
            || filePath.startsWith("/") || filePath.endsWith("/") || filePath.indexOf('\\') >= 0
            || filePath.indexOf('\0') >= 0 || filePath.indexOf('\r') >= 0 || filePath.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("invalid artifact file path");
        }
        for (String segment : filePath.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("invalid artifact file path");
            }
        }
    }

    /**
     * 损坏 artifact 不允许以部分文件索引继续服务。
     */
    private static IllegalArgumentException malformedChangeSet() {
        return new IllegalArgumentException("change set artifact is malformed");
    }

    /**
     * hunk 数值格式错误仍使用固定脱敏文案，并保留解析失败供诊断。
     */
    private static IllegalArgumentException malformedChangeSet(Throwable cause) {
        return new IllegalArgumentException("change set artifact is malformed", cause);
    }

    /**
     * 超时或关闭通过线程中断协作停止 SQL 后的 CPU 解析，避免迟到任务继续占用读取许可。
     */
    private static void requireChangeSetReadActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("change set read interrupted");
        }
    }

    /**
     * 每次物化正文都复算摘要，不让持久损坏或陈旧应用缓存绕过完整性门。
     */
    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 读取字符串字段并拒绝数字、布尔和 JSON null 等宽松强转。
     */
    private static String text(com.fasterxml.jackson.databind.JsonNode object, String field) {
        com.fasterxml.jackson.databind.JsonNode value = object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.textValue();
    }

    /**
     * 画像仅 reasoning 档位可空，其余类型都必须严格符合 JSON 字符串。
     */
    private static String nullableText(com.fasterxml.jackson.databind.JsonNode object, String field) {
        com.fasterxml.jackson.databind.JsonNode value = object.get(field);
        if (value == null || !(value.isTextual() || value.isNull())) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.isNull() ? null : value.textValue();
    }

    /**
     * token 限额必须是整数节点并能无损落入 Java int。
     */
    private static int integer(com.fasterxml.jackson.databind.JsonNode object, String field) {
        com.fasterxml.jackson.databind.JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.intValue();
    }

    /**
     * 消息复用实时事件的 Ja 身份，其它表以条目种类隔离主键；SQL 仍按原始主键排序和编码游标，
     * 避免恢复快照改写消息来源身份，同时保持 keyset 分页边界。
     */
    private static String snapshotItemId(String sourceKind, String sourceId) {
        return SnapshotItemIdentity.of(sourceKind, sourceId);
    }

    /**
     * 将 Workspace 行转换为领域对象，并在边界处校验必需列。
     */
    private static Workspace workspace(PersistenceRecords.WorkspaceRow row) {
        return new Workspace(requiredText(row.workspaceId(), "workspace_id"),
                Path.of(requiredText(row.rootPath(), "root_path")), requiredText(row.displayName(), "display_name"),
                Workspace.Trust.valueOf(requiredText(row.trust(), "trust").toUpperCase(Locale.ROOT)),
                Workspace.Kind.valueOf(requiredText(row.kind(), "kind").toUpperCase(Locale.ROOT)),
                row.revision());
    }

    /**
     * 将删除标记排除后的 Thread 行投影为稳定列表摘要。
     */
    private static ThreadSummary thread(PersistenceRecords.ThreadRow row) {
        return PersistenceRowProjections.threadSummary(row);
    }

    /**
     * 将数据库状态一次解析为领域闭集，未知状态统一作为持久化损坏拒绝展示。
     */
    private static TurnState parseTurnState(String state) {
        try {
            return TurnState.valueOf(state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid turn state", failure);
        }
    }

    /**
     * 多取一条后生成 keyset cursor，避免 offset 在并发插入时漂移。
     */
    private static CursorPage<Workspace> workspacePage(List<PersistenceRecords.WorkspaceRow> rows, int limit) {
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            PersistenceRecords.WorkspaceRow last = rows.getLast();
            next = encode(requiredText(last.updatedAt(), "updated_at"),
                    requiredText(last.workspaceId(), "workspace_id"));
        }
        return new CursorPage<>(rows.stream().map(MybatisHistoryService::workspace).toList(), next);
    }

    /**
     * Thread 分页独立保持自己的稳定身份键，避免用动态列探测复用 Workspace 逻辑。
     */
    private static CursorPage<ThreadSummary> activeThreadPage(List<PersistenceRecords.ThreadRow> rows, int limit) {
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            PersistenceRecords.ThreadRow last = rows.getLast();
            String updatedAt = requiredText(last.updatedAt(), "updated_at");
            next = encodeThreadCursor(last.pinnedAt() == null ? 0 : 1,
                    last.pinnedAt() == null ? updatedAt : last.pinnedAt(), updatedAt,
                    requiredText(last.threadId(), "thread_id"));
        }
        return new CursorPage<>(rows.stream().map(MybatisHistoryService::thread).toList(), next);
    }

    /**
     * 搜索保留归档项并继续使用纯更新时间 keyset，不让置顶改变恢复入口的结果顺序。
     */
    private static CursorPage<ThreadSummary> searchThreadPage(List<PersistenceRecords.ThreadRow> rows, int limit) {
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            PersistenceRecords.ThreadRow last = rows.getLast();
            next = encode(requiredText(last.updatedAt(), "updated_at"),
                    requiredText(last.threadId(), "thread_id"));
        }
        return new CursorPage<>(rows.stream().map(MybatisHistoryService::thread).toList(), next);
    }

    /**
     * 发现页只根据 SQL 返回的排序列生成 opaque cursor，并在返回前完成领域投影校验。
     */
    private static CursorPage<ThreadDiscovery> discoveryPage(
            List<PersistenceRecords.ThreadDiscoveryRow> rows, int limit) {
        String next = null;
        if (rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
            PersistenceRecords.ThreadDiscoveryRow last = rows.getLast();
            next = encode(requiredText(last.updatedAt(), "updated_at"),
                    requiredText(last.threadId(), "thread_id"));
        }
        return new CursorPage<>(rows.stream().map(MybatisHistoryService::threadDiscovery).toList(), next);
    }

    /**
     * 将 discovery SQL 行转为不含正文和配置的可见最小投影，未知状态立即失败关闭。
     */
    private static ThreadDiscovery threadDiscovery(PersistenceRecords.ThreadDiscoveryRow row) {
        return new ThreadDiscovery(requiredText(row.threadId(), "thread_id"),
                requiredText(row.title(), "title"),
                ThreadDiscovery.Kind.valueOf(requiredText(row.kind(), "kind")),
                requiredText(row.workspaceId(), "workspace_id"),
                ThreadDiscovery.Status.valueOf(requiredText(row.status(), "status")), row.latestTurnId());
    }

    /**
     * active Thread 游标冻结完整排序元组，跨置顶/普通分组翻页不会重复或遗漏。
     */
    private static String encodeThreadCursor(int pinned, String sortTime, String updatedAt, String id) {
        String value = pinned + "\n" + sortTime + "\n" + updatedAt + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 严格解析完整排序元组，拒绝缺失字段或越界分组值，避免跨页重复和遗漏。
     */
    private static ThreadCursor decodeThreadCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\n", -1);
            if (parts.length != 4 || parts[3].isBlank()) {
                throw new IllegalArgumentException();
            }
            int pinned = Integer.parseInt(parts[0]);
            if (pinned < 0 || pinned > 1) {
                throw new IllegalArgumentException();
            }
            Instant.parse(parts[1]);
            Instant.parse(parts[2]);
            return new ThreadCursor(pinned, parts[1], parts[2], parts[3]);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid thread history cursor", failure);
        }
    }

    /**
     * 将数据库过量读取限制在协议允许的固定上界内。
     */
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new IllegalArgumentException("invalid history page limit");
        }
    }

    /**
     * 将时间与稳定身份编码为不透明 Base64URL 游标，不暴露 SQL 分页细节。
     */
    private static String encode(String time, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((time + "\n" + id)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * cursor 严格解码为 timestamp/id；不接受旧格式。
     */
    private static Cursor decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new IllegalArgumentException();
            }
            Instant.parse(parts[0]);
            return new Cursor(parts[0], parts[1]);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid history cursor", failure);
        }
    }

    /**
     * 将 revision 竞争统一映射为可识别的 CAS 冲突。
     */
    private static StorageException conflict() {
        return new StorageException(
                StorageException.Code.CAS_CONFLICT, "history revision is stale");
    }

    /**
     * Store 冲突映射为稳定 CAS 结果时保留底层事务诊断。
     */
    private static StorageException conflict(Throwable cause) {
        return new StorageException(
                StorageException.Code.CAS_CONFLICT, "history revision is stale", cause);
    }

    /**
     * 工作区身份与物理根唯一绑定，冲突时不复用旧 ID 或修改原行。
     */
    private static StorageException identityConflict() {
        return new StorageException(
                StorageException.Code.STORAGE_CONFLICT, "workspace identity changed");
    }

    /**
     * 构造不携带 SQL 或本地路径的稳定未找到错误。
     */
    private static StorageException notFound(String value) {
        return new StorageException(
                StorageException.Code.NOT_FOUND, value + " was not found");
    }

    /**
     * 必需标识与枚举文本同时拒绝 NULL 和空白，损坏行不能进入领域投影。
     */
    private static String requiredText(String value, String column) {
        if (value == null || value.isBlank()) {
            throw corrupted(column);
        }
        return value;
    }

    /**
     * insert CAS 失败保持在存储错误分类内，不泄露 SQL 细节。
     */
    private static void requireInserted(int changed, String message) {
        if (changed != 1) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT, message);
        }
    }

    /**
     * 必需但允许空字符串的正文列只拒绝 SQL NULL，避免改变空 Tool 结果语义。
     */
    private static String requiredValue(String value, String column) {
        if (value == null) {
            throw corrupted(column);
        }
        return value;
    }

    /**
     * 必需整数使用引用类型承接 JDBC NULL，再在投影边界显式失败而不是默认为零。
     */
    private static long requiredNumber(Long value, String column) {
        if (value == null) {
            throw corrupted(column);
        }
        return value;
    }

    /**
     * 将缺失必需列统一归类为损坏数据，不向调用方泄漏 SQL 与 Mapper 细节。
     */
    private static StorageException corrupted(String column) {
        return new StorageException(StorageException.Code.INVALID_STATE,
                "persistence row is missing required column " + column);
    }

    /**
     * Rust 已验证的绝对根目录只用于注册，不触碰目录内容。
     */
    @Override
    public Workspace register(Workspace.Registration request) {
        Objects.requireNonNull(request, "request");
        return transactions.required(mapper -> {
            PersistenceRecords.WorkspaceRow byId = mapper.history().selectWorkspace(request.workspaceId());
            if (byId != null) {
                if (!request.root().toString().equals(requiredText(byId.rootPath(), "root_path"))) {
                    throw identityConflict();
                }
                return workspace(byId);
            }
            if (mapper.history().selectWorkspaceByRoot(request.root().toString()) != null) {
                throw identityConflict();
            }
            requireInserted(mapper.history().insertWorkspace(new PersistenceRecords.WorkspaceInsert(
                            request.workspaceId(), request.root().toString(), request.displayName(),
                            request.trust().name(), request.kind().name(),
                            request.occurredAt().toString())),
                    "workspace insert lost");
            return workspace(mapper.history().selectWorkspace(request.workspaceId()));
        });
    }

    /**
     * kind 条件直接进入 SQL keyset page，null 表示列出全部且不读取进程级 current workspace。
     */
    @Override
    public CursorPage<Workspace> list(String cursor, int limit, Workspace.Kind kind) {
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> workspacePage(mapper.history().selectWorkspacePage(
                new PersistenceRecords.WorkspacePage(
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1,
                        kind == null ? null : kind.name())), limit));
    }

    /**
     * 直接读取权威 root/id，确保重启恢复不信任过期 registry。
     */
    @Override
    public Optional<Workspace> findById(String workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return transactions.required(mapper -> Optional.ofNullable(mapper.history().selectWorkspace(workspaceId))
                .map(MybatisHistoryService::workspace));
    }

    /**
     * 按规范根查询已登记项目或会话目录的通用身份，避免调用方扫描列表或把路径重新解释为新目录。
     */
    @Override
    public Optional<Workspace> findByRoot(Path canonicalRoot) {
        Path canonical = Objects.requireNonNull(canonicalRoot, "canonicalRoot").toAbsolutePath().normalize();
        return transactions.required(mapper -> Optional.ofNullable(
                        mapper.history().selectWorkspaceByRoot(canonical.toString()))
                .map(MybatisHistoryService::workspace));
    }

    /**
     * trust 更新在同一事务读取 revision 并精确 CAS；竞争者赢时不自动重试。
     */
    @Override
    public Workspace updateTrust(String workspaceId, Workspace.Trust trust) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(trust, "trust");
        return transactions.required(mapper -> {
            PersistenceRecords.WorkspaceRow row = mapper.history().selectWorkspace(workspaceId);
            if (row == null) {
                throw notFound("workspace");
            }
            long revision = row.revision();
            if (mapper.history().compareAndSetWorkspaceTrust(new PersistenceRecords.WorkspaceTrustCas(
                    workspaceId, trust.name(), revision, clock.instant().toString())) != 1) {
                        throw conflict();
                    }
            return workspace(mapper.history().selectWorkspace(workspaceId));
        });
    }

    /**
     * 先编码时间线来源阶段再编码排序键，使不可变继承前缀始终排在本地追加历史之前。
     *
     * @author kongweiguang
     */
    private static String encodeTimelineCursor(TimelineCursor cursor) {
        String value = cursor.phase() == TimelinePhase.INHERITED
                ? "i\n" + cursor.ordinal()
                : "n\n" + cursor.time() + "\n" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码继承序号或本地时间线键；通用列表仍使用独立的时间戳游标，避免两类排序规则混用。
     *
     * @author kongweiguang
     */
    private static TimelineCursor decodeTimelineCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\n", -1);
            if (parts.length == 2 && "i".equals(parts[0])) {
                long ordinal = Long.parseLong(parts[1]);
                if (ordinal < 0) {
                    throw new IllegalArgumentException();
                }
                return new TimelineCursor(TimelinePhase.INHERITED, null, null, ordinal);
            }
            if (parts.length == 3 && "n".equals(parts[0]) && !parts[2].isBlank()) {
                Instant.parse(parts[1]);
                return new TimelineCursor(TimelinePhase.NATIVE, parts[1], parts[2], 0);
            }
            throw new IllegalArgumentException();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid thread history cursor", failure);
        }
    }

    /**
     * 在同一 SQLite 事务内检查 revision、根唯一性和持久阻塞摘要，再 CAS 原行以保留历史外键；
     * 仅变更名称时不查忙碌状态。忙碌属于业务拒绝，先作为事务结果带出，再于事务边界外抛出，
     * 避免生产事务 owner 将 WorkspaceFailure 误包装成存储故障。
     */
    @Override
    public Workspace updateProject(String workspaceId,
                                   long expectedRevision,
                                   Path root,
                                   String displayName,
                                   boolean rootChanged,
                                   Instant occurredAt) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Path canonicalRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("invalid workspace revision");
        }
        WorkspaceUpdateOutcome outcome = transactions.required(mapper -> {
            PersistenceRecords.WorkspaceRow row = mapper.history().selectWorkspace(workspaceId);
            if (row == null) {
                return new WorkspaceUpdateOutcome(null, new WorkspaceFailure(
                        WorkspaceFailure.Code.DIRECTORY_UNAVAILABLE, "workspace is unavailable"));
            }
            if (!Workspace.Kind.PROJECT.name().equals(row.kind())) {
                return new WorkspaceUpdateOutcome(null, new WorkspaceFailure(
                        WorkspaceFailure.Code.IDENTITY_CONFLICT, "only project workspaces can be updated"));
            }
            if (row.revision() != expectedRevision) {
                return new WorkspaceUpdateOutcome(null, updateConflict());
            }
            if (rootChanged) {
                PersistenceRecords.WorkspaceRow rootOwner = mapper.history()
                        .selectWorkspaceByRoot(canonicalRoot.toString());
                if (rootOwner != null && !workspaceId.equals(rootOwner.workspaceId())) {
                    return new WorkspaceUpdateOutcome(null, updateConflict());
                }
                List<PersistenceRecords.WorkspaceBusyBlockerRow> busyRows = mapper.history()
                        .selectWorkspaceBusyBlockers(workspaceId);
                if (!busyRows.isEmpty()) {
                    List<WorkspaceBusyMessage.Blocker> blockers = busyRows.stream()
                            .map(value -> new WorkspaceBusyMessage.Blocker(value.threadTitle(),
                                    value.blockerKind(), value.blockerState(), value.occurrences()))
                            .toList();
                    return new WorkspaceUpdateOutcome(null, new WorkspaceFailure(
                            WorkspaceFailure.Code.WORKSPACE_BUSY,
                            WorkspaceBusyMessage.durableWork(blockers, busyRows.getFirst().totalBlockers())));
                }
            }
            int changed = mapper.history().compareAndSetWorkspaceUpdate(new PersistenceRecords.WorkspaceUpdateCas(
                    workspaceId, canonicalRoot.toString(), displayName, rootChanged, expectedRevision,
                    occurredAt.toString()));
            if (changed != 1) {
                return new WorkspaceUpdateOutcome(null, updateConflict());
            }
            return new WorkspaceUpdateOutcome(workspace(mapper.history().selectWorkspace(workspaceId)), null);
        });
        if (outcome.failure() != null) {
            throw outcome.failure();
        }
        return outcome.workspace();
    }

    /**
     * 事务回调返回 Workspace 业务结果，供领域拒绝在生产事务 owner 外映射为 RPC 错误。
     *
     * @param workspace 已提交更新后的 Workspace；业务失败时为空
     * @param failure 在事务边界外抛出的业务拒绝；更新成功时为空
     * @author kongweiguang
     */
    private record WorkspaceUpdateOutcome(Workspace workspace,
                                          WorkspaceFailure failure) {
    }

    /**
     * 统一把 stale revision、root uniqueness 和最后 CAS 竞争映射为用户可重读后重试的 Workspace conflict。
     */
    private static WorkspaceFailure updateConflict() {
        return new WorkspaceFailure(WorkspaceFailure.Code.UPDATE_CONFLICT,
                "workspace changed; read the latest revision before retrying");
    }

    /**
     * unregister 物理删除 Workspace 前清理其侧聊回执；回执清理与 owner CAS 共用一笔事务。
     */
    @Override
    public void unregister(String workspaceId, long expectedRevision) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("invalid workspace revision");
        }
        transactions.required(mapper -> {
            mapper.sideChatPurges().deleteWorkspaceCloseReceiptMembers(workspaceId);
            mapper.sideChatPurges().deleteWorkspaceCloseReceipts(workspaceId);
            if (mapper.history().deleteWorkspace(
                    new PersistenceRecords.WorkspaceDelete(workspaceId, expectedRevision)) != 1) {
                        throw conflict();
                    }
            return null;
        });
    }

    /**
     * Thread 与 Store conversation 一起初始化；JDBC 故障在参与者边界包装，避免 Core 依赖具体驱动。
     */
    @Override
    public ThreadSummary createThread(ThreadSummary.Creation request) {
        Objects.requireNonNull(request, "request");
        SubagentPolicy policy = Objects.requireNonNull(subagentPolicySource.current(),
                "subagent policy source returned null");
        AgentStore.ConversationKey conversation = APP_IDENTITY.conversation(request.threadId());
        agentStore.initializeConversation(new AgentStore.ConversationInitialization(conversation, request.occurredAt()),
                transaction -> AgentStoreConnectionScope.participate(JdbcTransactionResources.connection(transaction), () -> transactions.required(mapper -> {
                    if (mapper.history().selectWorkspace(request.workspaceId()) == null) {
                        throw notFound("workspace");
                    }
                    JaThreadDefinition definition = new JaThreadDefinition(request.threadId(), request.workspaceId(),
                            request.title(), request.preferences(), request.occurredAt());
                    requireInserted(mapper.history().insertThread(ThreadPersistenceMapping.toInsert(definition)),
                            "thread insert lost");
                    requireInserted(mapper.subagentPolicies().insert(new PersistenceRecords.SubagentPolicyInsert(
                            definition.threadId(), policy.enabled(), policy.providerId(), policy.modelId(),
                            policy.reasoningLevel(), definition.createdAt().toString())), "subagent policy insert lost");
                    return null;
                })));
        return readThreadSummary(request.threadId());
    }

    /**
     * 冻结所选来源回复后才创建 child；只借用组合根持有的 TaskRepository 快照能力，回执、Thread、Core conversation、
     * seed 与附件授权必须共同提交。
     */
    @SuppressWarnings("PMD.CloseResource") // TaskRepository 是组合根持有的共享 owner，本方法只借用快照能力，不接管其关闭权。
    @Override
    public ThreadUseCase.ThreadForkResult forkThread(ThreadUseCase.ThreadForkRequest request) {
        Objects.requireNonNull(request, "request");
        String fingerprint = threadForkFingerprint(request);
        ThreadUseCase.ThreadForkResult existing = readThreadForkOperation(request, fingerprint);
        if (existing != null) {
            return existing;
        }
        TaskRepository snapshots = threadForkSnapshots.get();
        if (snapshots == null) {
            throw new IllegalStateException("thread fork snapshot repository is unavailable");
        }
        TaskModels.ThreadForkContextSnapshot frozen = snapshots.freezeThreadForkContext(
                request.sourceThreadId(), request.sourceItemId(), request.expectedThreadRevision());
        requireForkSnapshot(request, frozen);
        Instant createdAt = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        String childThreadId = forkThreadId(request.operationKey());
        SubagentPolicy policy = Objects.requireNonNull(subagentPolicySource.current(),
                "subagent policy source returned null");
        AtomicReference<ThreadUseCase.ThreadForkResult> created = new AtomicReference<>();
        try {
            AgentStore.ConversationKey conversation = APP_IDENTITY.conversation(childThreadId);
            agentStore.initializeConversation(new AgentStore.ConversationInitialization(conversation, createdAt),
                    transaction -> AgentStoreConnectionScope.participate(
                            JdbcTransactionResources.connection(transaction), () -> transactions.required(mapper -> {
                                ThreadUseCase.ThreadForkResult receipt = forkOperation(mapper, request, fingerprint);
                                if (receipt != null) {
                                    created.set(receipt);
                                    return null;
                                }
                                PersistenceRecords.ThreadRow sourceRow = mapper.history().selectThread(
                                        request.sourceThreadId());
                                if (sourceRow == null || sourceRow.deletedAt() != null) {
                                    throw new StorageException(StorageException.Code.NOT_FOUND,
                                            "thread fork source is unavailable");
                                }
                                if (sourceRow.revision() != request.expectedThreadRevision()
                                    || mapper.threadForks().claimSourceRevision(
                                            request.sourceThreadId(), request.expectedThreadRevision()) != 1) {
                                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                                            "thread fork source revision changed");
                                }
                                if (mapper.history().selectWorkspace(sourceRow.workspaceId()) == null) {
                                    throw new StorageException(StorageException.Code.NOT_FOUND,
                                            "thread fork workspace is unavailable");
                                }
                                ThreadSummary source = thread(sourceRow);
                                ThreadPreferences sourcePreferences = source.preferences();
                                ThreadPreferences childPreferences = new ThreadPreferences(
                                        sourcePreferences.providerId(), sourcePreferences.modelId(),
                                        sourcePreferences.reasoningLevel(), sourcePreferences.accessMode(),
                                        sourcePreferences.collaborationMode(), ThreadPreferences.TitleSource.PLACEHOLDER);
                                JaThreadDefinition child = new JaThreadDefinition(childThreadId,
                                        source.workspaceId(), source.title() + "（分叉）", childPreferences, createdAt);
                                requireInserted(mapper.history().insertThread(ThreadPersistenceMapping.toInsert(child)),
                                        "thread fork child insert lost");
                                requireInserted(mapper.subagentPolicies().insert(new PersistenceRecords.SubagentPolicyInsert(
                                                child.threadId(), policy.enabled(), policy.providerId(), policy.modelId(),
                                                policy.reasoningLevel(), createdAt.toString())),
                                        "thread fork policy insert lost");
                                InitialContextSeed seed = frozen.initialContextSeed();
                                List<String> displayAttachmentIds = collectForkHistoryAttachmentIds(
                                        mapper, request.sourceThreadId(), request.sourceItemId(), frozen);
                                Map<String, String> attachmentMap = cloneForkAttachments(mapper, childThreadId,
                                        request.sourceThreadId(), source.workspaceId(), seed,
                                        frozen.contextMessages(), displayAttachmentIds, createdAt);
                                InitialContextSeed childSeed = seed == null ? null
                                        : InitialContextSeedAttachmentMapper.remap(seed, attachmentMap);
                                freezeForkContextMessages(mapper, childThreadId, frozen.contextSourceThreadId(),
                                        frozen.contextSourceItemId(), frozen.contextMessages(), attachmentMap);
                                freezeForkHistory(mapper, childThreadId, request.sourceThreadId(),
                                        request.sourceItemId(), frozen, attachmentMap, createdAt);
                                requireInserted(mapper.threadForks().insertSeed(new ThreadForkRecords.SeedInsert(
                                                childThreadId, frozen.contextSourceThreadId(), frozen.sourceTurnId(),
                                                frozen.contextSourceItemId(), request.expectedThreadRevision(),
                                                threadForkSeeds.encode(childSeed),
                                                createdAt.toString())),
                                        "thread fork seed insert lost");
                                ThreadSummary childSummary = new ThreadSummary(childThreadId, source.workspaceId(),
                                        child.title(), source.workspaceKind(), childPreferences,
                                        ThreadSummary.Status.ACTIVE, false, null, true, 0, createdAt, createdAt);
                                ThreadUseCase.ThreadForkResult result = new ThreadUseCase.ThreadForkResult(childSummary,
                                        new ThreadUseCase.ThreadForkSource(request.sourceThreadId(),
                                                request.sourceItemId(), request.expectedThreadRevision()));
                                requireInserted(mapper.threadForks().insertOperation(
                                                new ThreadForkRecords.OperationInsert(request.operationKey(), fingerprint,
                                                        encodeThreadForkResult(result), createdAt.toString())),
                                        "thread fork operation insert lost");
                                created.set(result);
                                return null;
                            })));
            return Objects.requireNonNull(created.get(), "thread fork receipt was not produced");
        } catch (RuntimeException failure) {
            ThreadUseCase.ThreadForkResult committed = readThreadForkOperation(request, fingerprint);
            if (committed != null) {
                return committed;
            }
            throw failure;
        }
    }

    /**
     * 只查分叉操作账本并校验来源 owner 后返回回执，防止仅凭操作键发现其他 Thread 的新会话。
     *
     * @author kongweiguang
     */
    @Override
    public Optional<ThreadUseCase.ThreadForkResult> readThreadForkOperation(String sourceThreadId,
                                                                            String operationKey) {
        Objects.requireNonNull(sourceThreadId, "sourceThreadId");
        Objects.requireNonNull(operationKey, "operationKey");
        return transactions.required(mapper -> {
            ThreadForkRecords.OperationRow row = mapper.threadForks().selectOperation(operationKey);
            if (row == null) {
                return Optional.empty();
            }
            try {
                ThreadUseCase.ThreadForkResult receipt = decodeThreadForkResult(row.resultJson());
                if (!sourceThreadId.equals(receipt.source().threadId())
                    || !forkThreadId(operationKey).equals(receipt.thread().threadId())) {
                    throw new StorageException(StorageException.Code.CAS_CONFLICT,
                            "thread fork receipt owner conflicts");
                }
                return Optional.of(receipt);
            } catch (StorageException failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "thread fork receipt is invalid", failure);
            }
        });
    }

    /**
     * 仅在目标 Thread 尚无 Turn 时读取冻结 seed；附件已换成目标身份，不再依赖来源生命周期。
     */
    @Override
    public Optional<InitialContextSeed> findForFirstTurn(String threadId) {
        String id = Objects.requireNonNull(threadId, "threadId");
        return transactions.required(mapper -> {
            ThreadForkRecords.SeedRow row = mapper.threadForks().selectSeed(id);
            if (row == null || !mapper.agent().selectTurns(id).isEmpty()) {
                return Optional.empty();
            }
            Optional<InitialContextSeed> seed = threadForkSeeds.decode(row.seedJson());
            if (seed.isPresent() && (!row.sourceThreadId().equals(seed.orElseThrow().sourceIdentity().threadId())
                || !row.sourceTurnId().equals(seed.orElseThrow().sourceIdentity().turnId()))) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "thread fork seed source identity is invalid");
            }
            return seed;
        });
    }

    /**
     * 只复制来源 Thread 已授权的 BOUND 附件，并在同一事务内登记目标独立身份和授权关系。
     */
    private static Map<String, String> cloneForkAttachments(PersistenceMappers mapper, String childThreadId,
                                                            String sourceThreadId, String workspaceId,
                                                            InitialContextSeed seed,
                                                            List<io.github.kongweiguang.ja.agent.domain.context.ContextMessage> contextMessages,
                                                            List<String> displayAttachmentIds,
                                                            Instant createdAt) {
        Map<String, String> attachmentMap = new LinkedHashMap<>();
        Set<String> referenced = new java.util.LinkedHashSet<>();
        if (seed != null) {
            referenced.addAll(InitialContextSeedAttachmentMapper.attachmentIds(seed));
        }
        referenced.addAll(InitialContextSeedAttachmentMapper.attachmentIds(contextMessages));
        referenced.addAll(displayAttachmentIds);
        int ordinal = 0;
        for (String sourceAttachmentId : referenced) {
            AttachmentRecords.AttachmentRow source = mapper.attachments().selectThreadAttachment(
                    sourceAttachmentId, sourceThreadId);
            if (source == null || !"BOUND".equals(source.status()) || source.blobSha256() == null
                || source.contentSha256() == null || !workspaceId.equals(source.workspaceId())) {
                throw new StorageException(StorageException.Code.NOT_FOUND,
                        "thread fork source attachment is unavailable");
            }
            String childAttachmentId = "att_" + java.util.UUID.randomUUID().toString().replace("-", "");
            String now = createdAt.toString();
            requireInserted(mapper.attachments().insertForkAttachment(new AttachmentRecords.ForkAttachmentInsert(
                            childAttachmentId, workspaceId, source.blobSha256(), source.contentSha256(),
                            source.displayName(), source.sizeBytes(), source.mediaKind(), source.mediaType(),
                            now, source.expiresAt(), now)),
                    "thread fork attachment insert lost");
            requireInserted(mapper.threadForks().insertAttachment(new ThreadForkRecords.AttachmentInsert(
                            childThreadId, childAttachmentId, sourceThreadId, sourceAttachmentId, ordinal++, now)),
                    "thread fork attachment authorization insert lost");
            attachmentMap.put(sourceAttachmentId, childAttachmentId);
        }
        return Map.copyOf(attachmentMap);
    }

    /**
     * 将未压缩的中性模型消息写入目标 owner；即使有效 Seed 使用摘要检查点，后续分叉仍可从任一继承答复精确截断。
     *
     * @author kongweiguang
     */
    private void freezeForkContextMessages(PersistenceMappers mapper, String childThreadId,
                                           String sourceThreadId, String sourceItemId,
                                           List<io.github.kongweiguang.ja.agent.domain.context.ContextMessage> messages,
                                           Map<String, String> attachmentMap) {
        for (io.github.kongweiguang.ja.agent.domain.context.ContextMessage original : messages) {
            io.github.kongweiguang.ja.agent.domain.context.ContextMessage remapped =
                    InitialContextSeedAttachmentMapper.remapMessage(original, attachmentMap);
            requireInserted(mapper.threadForks().insertContextMessage(new ThreadForkRecords.ContextMessageInsert(
                            childThreadId, remapped.ordinal(), sourceThreadId, sourceItemId, remapped.messageId(),
                            contextMessageCodec.encodeMessageValue(remapped))),
                    "thread fork context message insert lost");
        }
    }

    /**
     * 只从所选公开历史前缀收集附件句柄，并保持来源 keyset 顺序以稳定授权副本顺序。
     *
     * @author kongweiguang
     */
    private List<String> collectForkHistoryAttachmentIds(PersistenceMappers mapper, String sourceThreadId,
                                                         String sourceItemId,
                                                         TaskModels.ThreadForkContextSnapshot frozen) {
        LinkedHashMap<String, Boolean> references = new LinkedHashMap<>();
        ThreadForkRecords.HistoryItemRow inheritedBoundary = mapper.threadForks()
                .selectHistoryItem(sourceThreadId, sourceItemId);
        if (inheritedBoundary != null) {
            Long after = null;
            while (true) {
                List<ThreadForkRecords.HistoryItemRow> page = mapper.threadForks().selectHistoryItems(
                        sourceThreadId, after, inheritedBoundary.ordinal() + 1, 128, false);
                if (page.isEmpty()) {
                    break;
                }
                for (ThreadForkRecords.HistoryItemRow row : page) {
                    collectSnapshotAttachmentIds(readSnapshotItemRow(row.itemJson()), references);
                }
                after = page.getLast().ordinal();
                if (after >= inheritedBoundary.ordinal()) {
                    break;
                }
            }
            return List.copyOf(references.keySet());
        }
        forEachForkSnapshotItem(mapper, sourceThreadId, sourceItemId, frozen,
                row -> collectSnapshotAttachmentIds(row, references));
        return List.copyOf(references.keySet());
    }

    /**
     * 按 Core 持久因果身份分页扫描本地公开前缀，再用冻结模型上下文排除边界之后的消息、工具与审批，
     * 因而不依赖可能相同或固定的时间戳判定先后。
     *
     * @author kongweiguang
     */
    private void forEachForkSnapshotItem(PersistenceMappers mapper, String sourceThreadId, String sourceItemId,
                                         TaskModels.ThreadForkContextSnapshot frozen,
                                         java.util.function.Consumer<PersistenceRecords.SnapshotItemRow> action) {
        ForkCutoff cutoff = forkCutoff(frozen.contextMessages());
        Long cursorTurn = null;
        Long cursorMessage = null;
        Integer cursorSemantic = null;
        Long cursorOrdinal = null;
        Long cursorTimeline = null;
        String cursorItem = null;
        boolean boundaryFound = false;
        while (!boundaryFound) {
            List<PersistenceRecords.SnapshotItemRow> page = mapper.history().selectForkSnapshotItems(
                    new PersistenceRecords.ForkSnapshotPage(sourceThreadId, sourceItemId, cursorTurn, cursorMessage,
                            cursorSemantic, cursorOrdinal, cursorTimeline, cursorItem, 128));
            if (page.isEmpty()) {
                break;
            }
            for (PersistenceRecords.SnapshotItemRow row : page) {
                if (!includeForkSnapshotItem(row, cutoff)) {
                    continue;
                }
                action.accept(row);
                if (sourceItemId.equals(row.itemId())) {
                    boundaryFound = true;
                    break;
                }
            }
            PersistenceRecords.SnapshotItemRow last = page.getLast();
            cursorTurn = last.turnSequence();
            cursorMessage = last.contextMessageOrdinal();
            cursorSemantic = last.semanticOrder();
            cursorOrdinal = last.ordinalOrder();
            cursorTimeline = last.timelineSequence();
            cursorItem = last.itemId();
        }
        if (!boundaryFound) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "fork boundary is absent from its frozen causal history");
        }
    }

    /**
     * 从已截断的中性历史建立身份集合；只有在同一 Turn 中具有持久结果的 Tool 调用才允许进入继承展示。
     *
     * @author kongweiguang
     */
    private static ForkCutoff forkCutoff(
            List<io.github.kongweiguang.ja.agent.domain.context.ContextMessage> messages) {
        Set<String> messageIds = new java.util.HashSet<>();
        Map<String, Long> messageOrdinals = new LinkedHashMap<>();
        Set<ForkToolIdentity> calls = new java.util.HashSet<>();
        Set<ForkToolIdentity> results = new java.util.HashSet<>();
        for (io.github.kongweiguang.ja.agent.domain.context.ContextMessage message : messages) {
            String itemId = io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds.toItemId(
                    message.messageId());
            messageIds.add(itemId);
            Long previousOrdinal = messageOrdinals.putIfAbsent(itemId, message.ordinal());
            if (previousOrdinal != null && previousOrdinal.longValue() != message.ordinal()) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "frozen context contains a duplicate message identity");
            }
            message.toolCallIds().forEach(callId -> calls.add(new ForkToolIdentity(message.turnId(), callId)));
            message.toolResultIds().forEach(callId -> results.add(new ForkToolIdentity(message.turnId(), callId)));
        }
        calls.retainAll(results);
        return new ForkCutoff(Set.copyOf(messageIds), Map.copyOf(messageOrdinals), Set.copyOf(calls));
    }

    /**
     * 因果分页后再按模型上下文身份过滤；没有消息身份的 mailbox 过程项才依赖查询提供的持久 Turn 与时间线边界。
     *
     * @author kongweiguang
     */
    private static boolean includeForkSnapshotItem(PersistenceRecords.SnapshotItemRow row, ForkCutoff cutoff) {
        if ("message".equals(row.itemKind())) {
            return row.contextMessageId() == null || cutoff.messageItemIds().contains(
                    io.github.kongweiguang.ja.conversation.domain.model.JaMessageIds.toItemId(
                            row.contextMessageId()));
        }
        return ("tool_call".equals(row.itemKind()) || "approval".equals(row.itemKind()))
                && row.callId() != null && cutoff.closedToolCallIds()
                        .contains(new ForkToolIdentity(row.turnId(), row.callId()));
    }

    /**
     * 仅收集公开消息与 Tool 展示中的媒体附件，不扫描文本预览或工具产物，避免扩大复制范围。
     *
     * @author kongweiguang
     */
    private void collectSnapshotAttachmentIds(PersistenceRecords.SnapshotItemRow row,
                                              Map<String, Boolean> references) {
        if ("message".equals(row.itemKind()) && row.attachmentsJson() != null) {
            JsonNode attachments = readJsonNode(row.attachmentsJson());
            if (attachments.isArray()) {
                attachments.forEach(value -> {
                    JsonNode id = value.get("attachmentId");
                    if (id != null && id.isTextual()) {
                        references.putIfAbsent(id.textValue(), Boolean.TRUE);
                    }
                });
            }
        }
        if ("tool_call".equals(row.itemKind()) && row.presentationJson() != null) {
            presentations.read(row.presentationJson()).media().stream()
                    .map(io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference::attachmentId)
                    .forEach(id -> references.putIfAbsent(id, Boolean.TRUE));
        }
    }

    /**
     * 先冻结继承前缀再复制来源本地历史，并保留每项完整正文；Turn 状态按所选答复时点投影，防止来源之后完成的状态泄漏。
     * 两种继承分页共用单行重映射与插入边界，分页上限和来源答复身份仍由各自分支确定。
     *
     * @author kongweiguang
     */
    private void freezeForkHistory(PersistenceMappers mapper, String childThreadId, String sourceThreadId,
                                   String sourceItemId, TaskModels.ThreadForkContextSnapshot frozen,
                                   Map<String, String> attachmentMap, Instant createdAt) {
        long ordinal = 0;
        ThreadForkRecords.HistoryItemRow inheritedBoundary = mapper.threadForks()
                .selectHistoryItem(sourceThreadId, sourceItemId);
        if (inheritedBoundary != null) {
            PersistenceRecords.SnapshotItemRow boundaryItem = readSnapshotItemRow(inheritedBoundary.itemJson());
            if (!"FINAL_ANSWER".equals(boundaryItem.messageKind())
                    || !frozen.sourceTurnId().equals(inheritedBoundary.sourceTurnId())) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "inherited fork boundary identity is inconsistent");
            }
            Instant boundaryAt = Instant.parse(boundaryItem.createdAt());
            Long after = null;
            while (true) {
                List<ThreadForkRecords.HistoryItemRow> page = mapper.threadForks().selectHistoryItems(
                        sourceThreadId, after, inheritedBoundary.ordinal() + 1, 128, false);
                if (page.isEmpty()) {
                    break;
                }
                for (ThreadForkRecords.HistoryItemRow row : page) {
                    insertInheritedForkHistoryItem(mapper, childThreadId, ordinal++, row, attachmentMap,
                            inheritedBoundary.sourceTurnId(), boundaryAt, createdAt,
                            "inherited fork history insert lost");
                }
                after = page.getLast().ordinal();
                if (after >= inheritedBoundary.ordinal()) {
                    break;
                }
            }
            return;
        }

        ForkCutoff cutoff = forkCutoff(frozen.contextMessages());
        PersistenceRecords.SnapshotItemRow boundaryItem = mapper.history()
                .selectSnapshotItem(sourceThreadId, sourceItemId);
        if (boundaryItem == null || !"FINAL_ANSWER".equals(boundaryItem.messageKind())
                || !frozen.sourceTurnId().equals(boundaryItem.turnId())) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "fork boundary is not a persisted final answer");
        }
        Instant boundaryAt = Instant.parse(boundaryItem.createdAt());
        Long afterOrdinal = null;
        while (true) {
            List<ThreadForkRecords.HistoryItemRow> page = mapper.threadForks().selectHistoryItems(
                    sourceThreadId, afterOrdinal, null, 128, false);
            if (page.isEmpty()) {
                break;
            }
            for (ThreadForkRecords.HistoryItemRow row : page) {
                insertInheritedForkHistoryItem(mapper, childThreadId, ordinal++, row, attachmentMap,
                        frozen.sourceTurnId(), boundaryAt, createdAt, "fork inherited history insert lost");
            }
            afterOrdinal = page.getLast().ordinal();
        }

        Map<String, PersistenceRecords.TurnRow> turns = mapper.agent().selectTurns(sourceThreadId).stream()
                .filter(PersistenceRecords.TurnRow::currentPath)
                .collect(java.util.stream.Collectors.toMap(PersistenceRecords.TurnRow::turnId, value -> value));
        long[] nextOrdinal = {ordinal};
        forEachForkSnapshotItem(mapper, sourceThreadId, sourceItemId, frozen, original -> {
            String fullContent = fullPublicItemContent(mapper, sourceThreadId, original);
            Long contextThrough = "FINAL_ANSWER".equals(original.messageKind())
                    ? cutoff.contextMessageOrdinals().get(original.contextMessageId()) : null;
            if ("FINAL_ANSWER".equals(original.messageKind()) && contextThrough == null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "fork answer is not backed by the frozen Core message boundary");
            }
            PersistenceRecords.SnapshotItemRow item = remapSnapshotItem(original, attachmentMap, true, childThreadId);
            PersistenceRecords.TurnRow sourceTurn = turns.get(original.turnId());
            if (sourceTurn == null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "fork history item has no source Turn metadata");
            }
            String turnJson = inheritedTurnJson(writeFrozenTurn(snapshotTurn(mapper, sourceTurn, false)),
                    childThreadId, sourceTurn.turnId(), frozen.sourceTurnId(), boundaryAt);
            requireInserted(mapper.threadForks().insertHistoryItem(new ThreadForkRecords.HistoryItemInsert(
                            childThreadId, nextOrdinal[0]++, item.itemId(), sourceThreadId, original.itemId(),
                            sourceTurn.turnId(), item.turnId(), contextThrough, writeJson(item),
                            fullContent, turnJson, createdAt.toString())),
                    "fork history item insert lost");
        });
    }

    /**
     * 继承行统一重映射到目标 owner 后事务写入，保留来源正文和上下文水位；边界 Turn 由调用方显式传入，
     * 防止共享插入逻辑误用嵌套分叉或本地历史的不同截断身份。
     *
     * @author kongweiguang
     */
    private void insertInheritedForkHistoryItem(PersistenceMappers mapper, String childThreadId, long ordinal,
                                               ThreadForkRecords.HistoryItemRow row,
                                               Map<String, String> attachmentMap, String boundaryTurnId,
                                               Instant boundaryAt, Instant createdAt, String failureMessage) {
        PersistenceRecords.SnapshotItemRow item = remapSnapshotItem(
                readSnapshotItemRow(row.itemJson()), attachmentMap, true, childThreadId);
        requireInserted(mapper.threadForks().insertHistoryItem(new ThreadForkRecords.HistoryItemInsert(
                        childThreadId, ordinal, item.itemId(), row.sourceThreadId(), row.sourceItemId(),
                        row.sourceTurnId(), item.turnId(), row.contextThroughOrdinal(),
                        writeJson(item), row.fullContent(), inheritedTurnJson(row.turnJson(), childThreadId,
                                row.sourceTurnId(), boundaryTurnId, boundaryAt), createdAt.toString())),
                failureMessage);
    }

    /**
     * 构造目标可独立读取的公开投影：条目、Turn、调用和审批身份改归目标 owner，来源顺序元数据仍用于展示及嵌套分叉截断。
     *
     * @author kongweiguang
     */
    private PersistenceRecords.SnapshotItemRow remapSnapshotItem(PersistenceRecords.SnapshotItemRow row,
                                                                 Map<String, String> attachmentMap,
                                                                 boolean inherited,
                                                                 String childThreadId) {
        String attachments = row.attachmentsJson();
        if (attachments != null) {
            JsonNode parsed = readJsonNode(attachments);
            if (parsed.isArray()) {
                for (JsonNode value : parsed) {
                    JsonNode id = value.get("attachmentId");
                    if (id != null && id.isTextual()) {
                        String target = attachmentMap.get(id.textValue());
                        if (target == null) {
                            throw new StorageException(StorageException.Code.INVALID_STATE,
                                    "fork attachment mapping is incomplete");
                        }
                        ((com.fasterxml.jackson.databind.node.ObjectNode) value).put("attachmentId", target);
                    }
                }
                attachments = parsed.toString();
            }
        }
        String presentation = row.presentationJson();
        if (presentation != null) {
            io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation value =
                    presentations.read(presentation);
            List<io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference> media = value.media().stream()
                    .map(reference -> new io.github.kongweiguang.ja.agent.domain.model.ToolMediaReference(
                            requiredAttachmentMap(attachmentMap, reference.attachmentId()), reference.mediaType(),
                            reference.displayName(), reference.sizeBytes())).toList();
            presentation = presentations.write(new io.github.kongweiguang.ja.conversation.domain.model.ToolPresentation(
                    value.kind(), value.title(), value.status(), value.inputPreview(), value.outputPreview(), value.summary(),
                    value.interactionAnswers(), value.relativePaths(), value.command(), value.relativeCwd(), value.stdout(),
                    value.stderr(), value.exitCode(), value.durationMs(), value.truncated(),
                    inherited ? null : value.artifactId(), inherited ? null : value.recovery(), media));
        }
        String targetTurnId = ThreadForkIdentity.turnId(childThreadId, row.turnId());
        String targetCallId = row.callId() == null ? null
                : ThreadForkIdentity.callId(childThreadId, row.turnId(), row.callId());
        String targetApprovalId = row.approvalId() == null ? null
                : ThreadForkIdentity.approvalId(childThreadId, row.approvalId());
        return new PersistenceRecords.SnapshotItemRow(ThreadForkIdentity.itemId(childThreadId, row.itemId()),
                row.itemKind(), row.createdAt(), targetTurnId,
                targetCallId, row.toolName(), row.toolOrdinal(), row.toolState(), row.messageKind(), row.previewText(),
                row.totalCharacters(), row.modelRound(), presentation, targetApprovalId, row.decision(), row.expiresAt(),
                attachments, row.contextReferencesJson(), row.sourceThreadId(), row.sourceTitle(), row.contextMessageId(),
                row.contextMessageOrdinal(), row.timelineSequence(), row.turnSequence(), row.semanticOrder(),
                row.ordinalOrder());
    }

    /**
     * 按所选答复时点生成 Turn 投影：边界 Turn 变为只读完成事实，较早 Turn 仅在全部生命周期时间早于边界时保留终态；
     * ChangeSet 与可变活动版本不属于子 Thread，显式树编码也避免依赖反射元数据。
     *
     * @author kongweiguang
     */
    private String inheritedTurnJson(String serializedTurn, String childThreadId, String sourceTurnId,
                                     String boundarySourceTurnId, Instant boundaryAt) {
        try {
            ThreadSnapshot.Turn turn = readFrozenTurn(serializedTurn);
            TurnState state = TurnState.valueOf(turn.status().toUpperCase(Locale.ROOT));
            boolean boundaryTurn = sourceTurnId.equals(boundarySourceTurnId);
            String status;
            Instant updatedAt;
            Instant completedAt;
            String errorCode;
            long mutationVersion;
            int modelRound;
            if (boundaryTurn) {
                if (turn.requestedAt().isAfter(boundaryAt)) {
                    throw new IllegalArgumentException("fork boundary predates its source Turn");
                }
                status = TurnState.COMPLETED.name().toLowerCase(Locale.ROOT);
                updatedAt = boundaryAt;
                completedAt = boundaryAt;
                errorCode = null;
                mutationVersion = 0;
                modelRound = 0;
            } else {
                if (!state.terminal() || turn.completedAt() == null
                        || turn.requestedAt().isAfter(boundaryAt)
                        || turn.updatedAt().isAfter(boundaryAt)
                        || turn.completedAt().isAfter(boundaryAt)) {
                    throw new IllegalArgumentException("prior inherited Turn was not terminal at fork boundary");
                }
                status = state.name().toLowerCase(Locale.ROOT);
                updatedAt = turn.updatedAt();
                completedAt = turn.completedAt();
                errorCode = turn.errorCode();
                mutationVersion = turn.mutationVersion();
                modelRound = turn.modelRound();
            }
            String sourceMessageId = turn.sourceMessageId() == null ? null
                    : ThreadForkIdentity.itemId(childThreadId, turn.sourceMessageId());
            ThreadSnapshot.Turn inherited = new ThreadSnapshot.Turn(
                    ThreadForkIdentity.turnId(childThreadId, turn.turnId()), status, turn.requestedAt(),
                    updatedAt, completedAt, errorCode, null, mutationVersion,
                    modelRound, sourceMessageId, ThreadSnapshot.Origin.INHERITED);
            return writeFrozenTurn(inherited);
        } catch (RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "frozen Turn metadata is invalid", failure);
        }
    }

    /**
     * 将有限 Turn 投影显式编码为 JSON 树，使时间保持 ISO 文本且不依赖全局模块或 record 反射；来源 ChangeSet 不归子 Thread 所有。
     *
     * @author kongweiguang
     */
    private String writeFrozenTurn(ThreadSnapshot.Turn turn) {
        com.fasterxml.jackson.databind.node.ObjectNode document = objectMapper.createObjectNode();
        document.put("turnId", turn.turnId());
        document.put("status", turn.status());
        document.put("requestedAt", turn.requestedAt().toString());
        document.put("updatedAt", turn.updatedAt().toString());
        if (turn.completedAt() == null) {
            document.putNull("completedAt");
        } else {
            document.put("completedAt", turn.completedAt().toString());
        }
        if (turn.errorCode() == null) {
            document.putNull("errorCode");
        } else {
            document.put("errorCode", turn.errorCode());
        }
        document.putNull("changeSet");
        document.put("mutationVersion", turn.mutationVersion());
        document.put("modelRound", turn.modelRound());
        if (turn.sourceMessageId() == null) {
            document.putNull("sourceMessageId");
        } else {
            document.put("sourceMessageId", turn.sourceMessageId());
        }
        document.put("origin", turn.origin().name());
        return document.toString();
    }

    /**
     * 只读取明确定义的冻结字段并按 ISO-8601 解析时间，避免反射，并使持久边界不依赖应用 ObjectMapper 的 Java 时间模块配置。
     *
     * @author kongweiguang
     */
    private ThreadSnapshot.Turn readFrozenTurn(String serialized) {
        JsonNode document = readJsonNode(serialized);
        if (!document.isObject()) {
            throw new IllegalArgumentException("frozen Turn must be a JSON object");
        }
        JsonNode changeSet = document.get("changeSet");
        if (changeSet != null && !changeSet.isNull()) {
            throw new IllegalArgumentException("frozen Turn cannot own a source ChangeSet");
        }
        String turnId = frozenTurnText(document, "turnId", false);
        String status = frozenTurnText(document, "status", false);
        Instant requestedAt = Instant.parse(frozenTurnText(document, "requestedAt", false));
        Instant updatedAt = Instant.parse(frozenTurnText(document, "updatedAt", false));
        String completedAtValue = frozenTurnText(document, "completedAt", true);
        String errorCode = frozenTurnText(document, "errorCode", true);
        String sourceMessageId = frozenTurnText(document, "sourceMessageId", true);
        String originValue = frozenTurnText(document, "origin", false);
        JsonNode mutationVersion = document.get("mutationVersion");
        JsonNode modelRound = document.get("modelRound");
        if (mutationVersion == null || !mutationVersion.isIntegralNumber() || !mutationVersion.canConvertToLong()
            || modelRound == null || !modelRound.isIntegralNumber() || !modelRound.canConvertToInt()) {
            throw new IllegalArgumentException("frozen Turn numeric fields are invalid");
        }
        return new ThreadSnapshot.Turn(turnId, status, requestedAt, updatedAt,
                completedAtValue == null ? null : Instant.parse(completedAtValue), errorCode, null,
                mutationVersion.longValue(), modelRound.intValue(), sourceMessageId,
                ThreadSnapshot.Origin.valueOf(originValue));
    }

    /**
     * 非空冻结标量必须实际编码为 JSON 字符串；拒绝宽松强转，避免损坏历史行悄然改变身份或生命周期字段。
     *
     * @author kongweiguang
     */
    private static String frozenTurnText(JsonNode document, String field, boolean nullable) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException("frozen Turn field is missing: " + field);
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("frozen Turn field is not text: " + field);
        }
        return value.textValue();
    }

    /**
     * 冻结时读取一次完整公开正文，使目标后续分页不再依赖 Core 或 mailbox 来源记录仍然存在。
     *
     * @author kongweiguang
     */
    private String fullPublicItemContent(PersistenceMappers mapper, String sourceThreadId,
                                         PersistenceRecords.SnapshotItemRow item) {
        if (!"message".equals(item.itemKind())) {
            return null;
        }
        PersistenceRecords.ItemContentReferenceRow reference = mapper.history()
                .selectItemContentReference(sourceThreadId, item.itemId());
        if (reference == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "fork history body reference is unavailable");
        }
        if (reference.mailboxContentJson() != null) {
            return codec.readUserContent(reference.mailboxContentJson()).text();
        }
        AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                new ConversationKey(reference.coreTenantId(), reference.coreThreadId(), reference.ownerUserId()),
                reference.coreTurnId());
        AgentStore.StoredRecord record = agentStore.readRecord(execution, reference.coreRecordId())
                .orElseThrow(() -> new StorageException(StorageException.Code.INVALID_STATE,
                        "fork source body record is unavailable"));
        return publicRecordText(record, reference.kind());
    }

    /**
     * 在重映射附件身份前拒绝重复键和尾随 token，防止解析歧义影响冻结历史的授权句柄。
     *
     * @author kongweiguang
     */
    private JsonNode readJsonNode(String json) {
        try {
            return objectMapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "frozen history JSON is invalid", failure);
        }
    }

    /**
     * 严格解码此前冻结的公开行后再由调用方重映射 owner，未知字段或尾随内容均视为存储损坏。
     *
     * @author kongweiguang
     */
    private PersistenceRecords.SnapshotItemRow readSnapshotItemRow(String json) {
        try {
            return objectMapper.reader().with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(json, PersistenceRecords.SnapshotItemRow.class);
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "frozen snapshot item is invalid", failure);
        }
    }

    /**
     * 序列化目标自有冻结行使用的稳定 JSON 投影；编码失败视为持久状态错误而非可忽略展示问题。
     *
     * @author kongweiguang
     */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "frozen history JSON cannot be encoded", failure);
        }
    }

    /**
     * 每个公开媒体引用都必须映射到目标授权身份；缺失映射时失败关闭，不能回退暴露旧 Thread 的句柄。
     *
     * @author kongweiguang
     */
    private static String requiredAttachmentMap(Map<String, String> values, String sourceId) {
        String targetId = values.get(sourceId);
        if (targetId == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "fork attachment mapping is incomplete");
        }
        return targetId;
    }

    /**
     * 在写入目标前复核冻结上下文对应调用方指定的 Thread、回复与 revision，拒绝静默换源。
     */
    private static void requireForkSnapshot(ThreadUseCase.ThreadForkRequest request,
                                            TaskModels.ThreadForkContextSnapshot frozen) {
        if (!request.sourceThreadId().equals(frozen.parentThreadId())
            || !request.sourceItemId().equals(frozen.sourceItemId())
            || request.expectedThreadRevision() != frozen.parentRevision()) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "thread fork source revision changed");
        }
        InitialContextSeed seed = frozen.initialContextSeed();
        if (!frozen.contextSourceThreadId().equals(seed.sourceIdentity().threadId())
            || !frozen.sourceTurnId().equals(seed.sourceIdentity().turnId())) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "thread fork seed source identity changed");
        }
    }

    /**
     * 只摘要不可变请求元组，使同一操作键不能被重用到另一来源或 revision。
     */
    private static String threadForkFingerprint(ThreadUseCase.ThreadForkRequest request) {
        String canonical = request.sourceThreadId() + "\0" + request.sourceItemId() + "\0"
                + request.expectedThreadRevision();
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 从幂等键派生稳定目标身份，使并发重试汇聚到同一 Thread，避免留下孤立 Store。
     */
    private static String forkThreadId(String operationKey) {
        String canonical = "thread-fork\0" + operationKey;
        return "thr_" + sha256(canonical.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    /**
     * 重试时先于 Core 初始化读取持久回执，以覆盖首次提交成功但响应未送达的情况。
     */
    private ThreadUseCase.ThreadForkResult readThreadForkOperation(ThreadUseCase.ThreadForkRequest request,
                                                                   String fingerprint) {
        return transactions.required(mapper -> forkOperation(mapper, request, fingerprint));
    }

    /**
     * 联合校验回执来源、请求摘要和目标初始状态，区分异参重用与持久事实损坏；已有存储错误原样传播以保留分类。
     */
    private ThreadUseCase.ThreadForkResult forkOperation(PersistenceMappers mapper,
                                                          ThreadUseCase.ThreadForkRequest request,
                                                          String fingerprint) {
        ThreadForkRecords.OperationRow row = mapper.threadForks().selectOperation(request.operationKey());
        if (row == null) {
            return null;
        }
        if (!fingerprint.equals(row.requestFingerprint())) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "thread fork operation key conflicts");
        }
        try {
            ThreadUseCase.ThreadForkResult result = decodeThreadForkResult(row.resultJson());
            ThreadUseCase.ThreadForkSource source = result.source();
            if (!request.sourceThreadId().equals(source.threadId())
                || !request.sourceItemId().equals(source.itemId())
                || request.expectedThreadRevision() != source.threadRevision()
                || !forkThreadId(request.operationKey()).equals(result.thread().threadId())
                || result.thread().revision() != 0 || result.thread().status() != ThreadSummary.Status.ACTIVE
                || result.thread().pinned() || result.thread().latestTurnStatus() != null
                || result.thread().latestTurnId() != null || !result.thread().latestTurnSeen()) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "thread fork receipt source is invalid");
            }
            return result;
        } catch (StorageException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "thread fork receipt is invalid", failure);
        }
    }

    /**
     * 将回执压成标量文档，避免公共投影中的 Java 时间类型依赖全局 Jackson 模块。
     */
    private String encodeThreadForkResult(ThreadUseCase.ThreadForkResult result) {
        try {
            return objectMapper.writeValueAsString(ThreadForkReceiptDocument.from(result));
        } catch (IOException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "thread fork receipt cannot be encoded", failure);
        }
    }

    /**
     * 从标量回执重建领域记录，让 JSON 解码与领域身份、状态校验保持分层。
     */
    private ThreadUseCase.ThreadForkResult decodeThreadForkResult(String json) throws IOException {
        ThreadForkReceiptDocument document = objectMapper.readValue(json, ThreadForkReceiptDocument.class);
        ThreadPreferences preferences = new ThreadPreferences(document.providerId(), document.modelId(),
                document.reasoningLevel(), AccessMode.valueOf(document.accessMode()),
                CollaborationMode.valueOf(document.collaborationMode()),
                ThreadPreferences.TitleSource.valueOf(document.titleSource()));
        ThreadSummary thread = new ThreadSummary(document.threadId(), document.workspaceId(), document.title(),
                document.workspaceKind(), preferences, ThreadSummary.Status.valueOf(document.status()),
                document.pinned(), document.latestTurnStatus() == null ? null : TurnState.valueOf(document.latestTurnStatus()),
                document.latestTurnSeen(), document.revision(), Instant.parse(document.createdAt()),
                Instant.parse(document.updatedAt()), document.latestTurnId());
        ThreadUseCase.ThreadForkSource source = new ThreadUseCase.ThreadForkSource(document.sourceThreadId(),
                document.sourceItemId(), document.sourceThreadRevision());
        return new ThreadUseCase.ThreadForkResult(thread, source);
    }

    /**
     * 持久回执只使用稳定标量、枚举名和 ISO 时间，重试结果不依赖应用级序列化配置。
     *
     * @param threadId 目标 Thread 标识
     * @param workspaceId 目标所属 Workspace 标识
     * @param title 创建时的标题
     * @param workspaceKind 创建时冻结的 Workspace 类型
     * @param providerId 创建时冻结的 Provider 标识
     * @param modelId 创建时冻结的模型标识
     * @param reasoningLevel 创建时冻结的推理级别
     * @param accessMode 创建时冻结的访问模式
     * @param collaborationMode 创建时冻结的协作模式
     * @param titleSource 创建时的标题来源
     * @param status 创建时的 Thread 状态
     * @param pinned 创建时的置顶状态
     * @param latestTurnStatus 创建时最新 Turn 状态
     * @param latestTurnSeen 创建时最新 Turn 是否已读
     * @param revision 创建时的 Thread revision
     * @param createdAt Thread 创建时间
     * @param updatedAt 创建时的更新时间
     * @param latestTurnId 创建时最新 Turn 标识
     * @param sourceThreadId 冻结来源 Thread 标识
     * @param sourceItemId 冻结来源回复条目标识
     * @param sourceThreadRevision 冻结来源 revision
     */
    private record ThreadForkReceiptDocument(String threadId,
                                             String workspaceId,
                                             String title,
                                             String workspaceKind,
                                             String providerId,
                                             String modelId,
                                             String reasoningLevel,
                                             String accessMode,
                                             String collaborationMode,
                                             String titleSource,
                                             String status,
                                             boolean pinned,
                                             String latestTurnStatus,
                                             boolean latestTurnSeen,
                                             long revision,
                                             String createdAt,
                                             String updatedAt,
                                             String latestTurnId,
                                             String sourceThreadId,
                                             String sourceItemId,
                                             long sourceThreadRevision) {
        /**
         * 首次将公开投影压成持久标量，使同操作重试返回准确创建快照。
         *
         * @param result Thread fork 创建结果
         * @return 可稳定序列化的回执文档
         */
        private static ThreadForkReceiptDocument from(ThreadUseCase.ThreadForkResult result) {
            ThreadSummary thread = result.thread();
            ThreadPreferences preferences = thread.preferences();
            return new ThreadForkReceiptDocument(thread.threadId(), thread.workspaceId(), thread.title(),
                    thread.workspaceKind(), preferences.providerId(), preferences.modelId(),
                    preferences.reasoningLevel(), preferences.accessMode().name(),
                    preferences.collaborationMode().name(), preferences.titleSource().name(),
                    thread.status().name(), thread.pinned(),
                    thread.latestTurnStatus() == null ? null : thread.latestTurnStatus().name(),
                    thread.latestTurnSeen(), thread.revision(), thread.createdAt().toString(),
                    thread.updatedAt().toString(), thread.latestTurnId(), result.source().threadId(),
                    result.source().itemId(), result.source().threadRevision());
        }
    }

    /**
     * Thread 列表在 SQL 内按 Workspace 过滤，保证 keyset 游标只属于一个项目。
     */
    @Override
    public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        checkLimit(limit);
        ThreadCursor key = decodeThreadCursor(cursor);
        return transactions.required(mapper -> activeThreadPage(mapper.history().selectThreadPage(
                new PersistenceRecords.ThreadPage(
                        workspaceId, key == null ? null : key.pinned(),
                        key == null ? null : key.sortTime(), key == null ? null : key.updatedAt(),
                        key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * 无项目导航跨多个 SESSION workspace 聚合，同时保留 pinned/update keyset 和完整 Thread 摘要。
     */
    @Override
    public CursorPage<ThreadSummary> listSessionThreads(String cursor, int limit) {
        checkLimit(limit);
        ThreadCursor key = decodeThreadCursor(cursor);
        return transactions.required(mapper -> activeThreadPage(mapper.history().selectSessionThreadPage(
                new PersistenceRecords.SessionThreadPage(key == null ? null : key.pinned(),
                        key == null ? null : key.sortTime(), key == null ? null : key.updatedAt(),
                        key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * 全局发现由一次 SQL 合并主 Thread 与两种 Child，使用同一更新时间/身份游标避免内存拼接漂移。
     */
    @Override
    public CursorPage<ThreadDiscovery> discoverThreads(ThreadDiscovery.Query query) {
        Objects.requireNonNull(query, "query");
        String normalized = query.query() == null ? "" : query.query().strip().toLowerCase(Locale.ROOT);
        Cursor key = decode(query.cursor());
        return transactions.required(mapper -> discoveryPage(mapper.threadDiscoveries().selectPage(
                        new PersistenceRecords.ThreadDiscoveryPage(query.workspaceId(), normalized,
                                key == null ? null : key.time(), key == null ? null : key.id(), query.limit() + 1)),
                query.limit()));
    }

    /**
     * USER timeline 提供倒序索引，全文匹配从其精确 Core 引用读取；超出编辑预算的输入明确标记截断。
     */
    @Override
    public CursorPage<UserInputHistory> searchUserInputs(String query, String cursor, int limit) {
        String normalized = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid input history query");
        }
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> {
            List<PersistenceRecords.InputHistoryRow> rows = mapper.history().selectInputHistoryPage(
                    new PersistenceRecords.InputHistoryPage(
                    normalized, key == null ? null : key.time(), key == null ? null : key.id(), limit + 1));
            boolean more = rows.size() > limit;
            List<PersistenceRecords.InputHistoryRow> visible = rows.subList(0, Math.min(limit, rows.size()));
            String next = null;
            if (more) {
                PersistenceRecords.InputHistoryRow last = visible.getLast();
                next = encode(last.createdAt(), last.itemId());
            }
            return new CursorPage<>(visible.stream().map(row -> new UserInputHistory(
                    row.itemId(), row.threadId(), row.publicText(), row.createdAt(), row.truncated())).toList(), next);
        });
    }

    /**
     * 查询词在应用层使用 Locale.ROOT 归一化；SQLite 只执行有界 contains 与既有 keyset。
     */
    @Override
    public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String normalized = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread search query");
        }
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> searchThreadPage(mapper.history().searchThreadPage(
                new PersistenceRecords.ThreadSearch(workspaceId, normalized,
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * 在所有 SESSION workspace 中搜索主会话标题，不按共享 ID 或创建时间猜测目录类型。
     */
    @Override
    public CursorPage<ThreadSummary> searchSessionThreads(String query, String cursor, int limit) {
        String normalized = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread search query");
        }
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> searchThreadPage(mapper.history().searchSessionThreadPage(
                new PersistenceRecords.SessionThreadSearch(normalized,
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * 混合页面按提交时间、语义和 Tool ordinal 排序，随机 identity 只用于最终去重；
     * Mapper 从游标指向的持久条目还原完整排序键，保证正文先于同批工具且跨页不丢不重。
     */
    @Override
    public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
        return readThreadPage(threadId, cursor, limit, false);
    }

    /**
     * 从末端按持久复合键倒序取一页，再翻回旧到新；这样首屏不经由逐页扫描才能到最新内容。
     */
    @Override
    public Optional<ThreadSnapshot> readThreadLatest(String threadId, String beforeCursor, int limit) {
        return readThreadPage(threadId, beforeCursor, limit, true);
    }

    /**
     * 两种方向只改变 keyset；Store checkpoint 必须与 Thread revision 一致后才能混合投影。
     */
    private Optional<ThreadSnapshot> readThreadPage(String threadId, String cursor, int limit, boolean latest) {
        checkLimit(limit);
        TimelineCursor key = decodeTimelineCursor(cursor);
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null) {
                return Optional.empty();
            }
            // 响应最多允许 200 个 Turn；为真实最新、待处理和最近用量 Turn 预留身份位置。
            int itemLimit = latest ? Math.min(limit, 197) : limit;
            TimelinePage page = timelinePage(mapper, threadId, key, itemLimit, latest);
            List<PersistenceRecords.SnapshotItemRow> nativeRows = page.entries().stream()
                    .filter(entry -> entry.nativeItem() != null).map(TimelineEntry::nativeItem).toList();
            PersistenceRecords.ContextUsageRow usageRow = mapper.history().selectLatestContextUsage(threadId);
            List<String> selectedTurnIds = new ArrayList<>(nativeRows.stream()
                    .map(PersistenceRecords.SnapshotItemRow::turnId).distinct().toList());
            if (usageRow != null) {
                selectedTurnIds.add(requiredText(usageRow.turnId(), "turn_id"));
            }
            // MyBatis OGNL 只读取显式布尔值，不能在 Native Image 对 JDK immutable List 反射调用 size()。
            List<String> boundTurnIds = new ArrayList<>(new java.util.LinkedHashSet<>(selectedTurnIds));
            List<PersistenceRecords.TurnRow> turnRows = latest
                    ? mapper.agent().selectSnapshotTurns(threadId, boundTurnIds, !boundTurnIds.isEmpty())
                    : mapper.agent().selectTurns(threadId);
            List<PersistenceRecords.TurnRow> currentPathRows = turnRows.stream()
                    .filter(PersistenceRecords.TurnRow::currentPath).toList();
            Set<String> supersededErrorTurns = Set.copyOf(
                    mapper.history().selectSupersededErrorTurnIds(threadId));
            Map<String, ThreadSnapshot.Turn> inheritedTurns = new LinkedHashMap<>();
            for (TimelineEntry entry : page.entries()) {
                if (entry.inheritedItem() != null) {
                    ThreadSnapshot.Turn inheritedTurn = readInheritedTurn(entry.inheritedItem().turnJson());
                    inheritedTurns.putIfAbsent(inheritedTurn.turnId(), inheritedTurn);
                }
            }
            List<ThreadSnapshot.Turn> turns = new ArrayList<>(inheritedTurns.values());
            turns.addAll(currentPathRows.stream()
                    .map(rowValue -> snapshotTurn(mapper, rowValue, supersededErrorTurns.contains(rowValue.turnId())))
                    .toList());
            List<ThreadSnapshot.Item> items = page.entries().stream().map(entry -> entry.inheritedItem() != null
                    ? new ThreadSnapshot.OriginItem(snapshotItem(readSnapshotItemRow(entry.inheritedItem().itemJson())))
                    : snapshotItem(entry.nativeItem())).toList();
            ThreadSnapshot.ContextUsage contextUsage = usageRow == null ? null
                    : contextUsage(usageRow, latestCheckpoint(threadId, row.ownerUserId(), row.revision()));
            PersistenceRecords.TurnRow queueTurn = turnRows.stream()
                    .filter(turn -> !io.github.kongweiguang.ja.agent.domain.turn.TurnState
                            .valueOf(requiredText(turn.state(), "state")).terminal())
                    .findFirst().orElse(null);
            InputQueue inputQueue = queueTurn == null ? null : inputQueue(mapper, queueTurn);
            return Optional.of(new ThreadSnapshot(thread(row), turns, items, contextUsage, inputQueue, page.nextCursor()));
        });
    }

    /**
     * 用阶段游标合并不可变继承前缀与本地 SQL 时间线；最新读取先分页本地后缀，再回退到继承项，
     * 并反转数据库倒序结果以保留语义位置与 Tool 序号组成的完整顺序。
     *
     * @author kongweiguang
     */
    private TimelinePage timelinePage(PersistenceMappers mapper, String threadId, TimelineCursor cursor,
                                      int limit, boolean latest) {
        if (!latest) {
            List<TimelineEntry> entries = new ArrayList<>(limit + 1);
            if (cursor == null || cursor.phase() == TimelinePhase.INHERITED) {
                Long after = cursor == null ? null : cursor.ordinal();
                List<ThreadForkRecords.HistoryItemRow> inherited = mapper.threadForks().selectHistoryItems(
                        threadId, after, null, limit + 1, false);
                int selected = Math.min(limit, inherited.size());
                inherited.subList(0, selected).forEach(value -> entries.add(TimelineEntry.inherited(value)));
                if (inherited.size() > limit) {
                    return new TimelinePage(entries, encodeTimelineCursor(new TimelineCursor(
                            TimelinePhase.INHERITED, null, null, inherited.get(limit - 1).ordinal())));
                }
                if (entries.size() < limit) {
                    int remaining = limit + 1 - entries.size();
                    List<PersistenceRecords.SnapshotItemRow> nativeItems = mapper.history().selectSnapshotItems(
                            new PersistenceRecords.SnapshotPage(threadId, null, null, remaining));
                    for (PersistenceRecords.SnapshotItemRow item : nativeItems) {
                        entries.add(TimelineEntry.nativeItem(item));
                    }
                    if (entries.size() > limit) {
                        List<TimelineEntry> visible = new ArrayList<>(entries.subList(0, limit));
                        PersistenceRecords.SnapshotItemRow lastNative = visible.getLast().nativeItem();
                        return new TimelinePage(visible,
                                encodeTimelineCursor(new TimelineCursor(TimelinePhase.NATIVE,
                                        lastNative.createdAt(), lastNative.itemId(), 0)));
                    }
                } else {
                    List<PersistenceRecords.SnapshotItemRow> probe = mapper.history().selectSnapshotItems(
                            new PersistenceRecords.SnapshotPage(threadId, null, null, 1));
                    if (!probe.isEmpty()) {
                        ThreadForkRecords.HistoryItemRow lastInherited = inherited.getLast();
                        return new TimelinePage(entries, encodeTimelineCursor(new TimelineCursor(
                                TimelinePhase.INHERITED, null, null, lastInherited.ordinal())));
                    }
                }
                return new TimelinePage(entries, null);
            }
            List<PersistenceRecords.SnapshotItemRow> nativeItems = mapper.history().selectSnapshotItems(
                    new PersistenceRecords.SnapshotPage(threadId, cursor.time(), cursor.id(), limit + 1));
            boolean more = nativeItems.size() > limit;
            List<PersistenceRecords.SnapshotItemRow> selected = more
                    ? nativeItems.subList(0, limit) : nativeItems;
            selected.forEach(value -> entries.add(TimelineEntry.nativeItem(value)));
            String next = more ? encodeTimelineCursor(new TimelineCursor(TimelinePhase.NATIVE,
                    selected.getLast().createdAt(), selected.getLast().itemId(), 0)) : null;
            return new TimelinePage(entries, next);
        }

        if (cursor != null && cursor.phase() == TimelinePhase.INHERITED) {
            List<ThreadForkRecords.HistoryItemRow> inherited = mapper.threadForks().selectHistoryItems(
                    threadId, null, cursor.ordinal(), limit + 1, true);
            boolean more = inherited.size() > limit;
            List<ThreadForkRecords.HistoryItemRow> selected = more
                    ? inherited.subList(0, limit) : inherited;
            List<TimelineEntry> entries = new ArrayList<>(selected.size());
            selected.stream().sorted(java.util.Comparator.comparingLong(ThreadForkRecords.HistoryItemRow::ordinal))
                    .forEach(value -> entries.add(TimelineEntry.inherited(value)));
            String next = more ? encodeTimelineCursor(new TimelineCursor(TimelinePhase.INHERITED,
                    null, null, selected.getLast().ordinal())) : null;
            return new TimelinePage(entries, next);
        }

        List<PersistenceRecords.SnapshotItemRow> nativeDescending = mapper.history().selectSnapshotItemsLatest(
                new PersistenceRecords.SnapshotPage(threadId,
                        cursor == null ? null : cursor.time(), cursor == null ? null : cursor.id(), limit + 1));
        boolean moreNative = nativeDescending.size() > limit;
        List<PersistenceRecords.SnapshotItemRow> selectedNative = moreNative
                ? nativeDescending.subList(0, limit) : nativeDescending;
        String next = null;
        List<ThreadForkRecords.HistoryItemRow> selectedInherited = List.of();
        if (moreNative) {
            PersistenceRecords.SnapshotItemRow oldestNative = selectedNative.getLast();
            next = encodeTimelineCursor(new TimelineCursor(TimelinePhase.NATIVE,
                    oldestNative.createdAt(), oldestNative.itemId(), 0));
        } else if (selectedNative.size() < limit) {
            int remaining = limit - selectedNative.size();
            List<ThreadForkRecords.HistoryItemRow> inheritedDescending = mapper.threadForks().selectHistoryItems(
                    threadId, null, null, remaining + 1, true);
            boolean moreInherited = inheritedDescending.size() > remaining;
            selectedInherited = moreInherited
                    ? inheritedDescending.subList(0, remaining) : inheritedDescending;
            if (moreInherited) {
                next = encodeTimelineCursor(new TimelineCursor(TimelinePhase.INHERITED,
                        null, null, selectedInherited.getLast().ordinal()));
            }
        } else {
            List<ThreadForkRecords.HistoryItemRow> newestInherited = mapper.threadForks().selectHistoryItems(
                    threadId, null, null, 1, true);
            if (!newestInherited.isEmpty()) {
                next = encodeTimelineCursor(new TimelineCursor(TimelinePhase.INHERITED,
                        null, null, newestInherited.getFirst().ordinal() + 1));
            }
        }
        List<TimelineEntry> entries = new ArrayList<>(selectedInherited.size() + selectedNative.size());
        selectedInherited.stream().sorted(java.util.Comparator.comparingLong(ThreadForkRecords.HistoryItemRow::ordinal))
                .forEach(value -> entries.add(TimelineEntry.inherited(value)));
        List<PersistenceRecords.SnapshotItemRow> chronologicalNative = new ArrayList<>(selectedNative);
        java.util.Collections.reverse(chronologicalNative);
        chronologicalNative.forEach(value -> entries.add(TimelineEntry.nativeItem(value)));
        return new TimelinePage(entries, next);
    }

    /**
     * 仅恢复终态的继承展示元数据；继承 Turn 没有执行 owner，不能重新进入运行时活动 Turn 集合。
     *
     * @author kongweiguang
     */
    private ThreadSnapshot.Turn readInheritedTurn(String serialized) {
        try {
            ThreadSnapshot.Turn turn = readFrozenTurn(serialized);
            TurnState state = TurnState.valueOf(turn.status().toUpperCase(Locale.ROOT));
            if (turn.origin() != ThreadSnapshot.Origin.INHERITED || turn.changeSet() != null || !state.terminal()) {
                throw new IllegalArgumentException("inherited Turn projection contains native operations");
            }
            return turn;
        } catch (RuntimeException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "inherited Turn metadata is invalid", failure);
        }
    }

    /**
     * 汇总读取与 Thread 存在性检查置于同一 SQLite 事务，避免已删除 Thread 的迟到响应返回
     * 看似有效的账本；它不读取消息、Tool 或 checkpoint 正文。
     */
    @Override
    public Optional<ThreadUsageSummary> readThreadUsageSummary(String threadId) {
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow thread = mapper.history().selectThread(threadId);
            if (thread == null) {
                return Optional.empty();
            }
            PersistenceRecords.ThreadUsageSummaryRow row = mapper.history().selectThreadUsageSummary(threadId);
            if (row == null) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                    "usage summary aggregate is unavailable");
            }
            return Optional.of(new ThreadUsageSummary(thread.revision(), row.requestCount(),
                    row.measuredRequestCount(), row.newInputRequestCount(), row.newInputTokens(),
                    row.outputRequestCount(), row.outputTokens(), row.totalRequestCount(), row.totalTokens(),
                    row.cacheReadRequestCount(), row.cacheReadTokens(), row.cacheWriteRequestCount(),
                    row.cacheWriteTokens(), row.cacheCompleteRequestCount(), row.cacheCompleteInputTokens(),
                    row.cacheCompleteReadTokens()));
        });
    }

    /**
     * thread/read 从同一 SQLite 快照恢复首个非终态 Turn 的完整队列，事件丢失也不会丢状态。
     */
    private InputQueue inputQueue(PersistenceMappers mapper, PersistenceRecords.TurnRow turn) {
        List<InputQueue.QueuedInput> queued = mapper.agent().selectPendingInputs(turn.turnId()).stream()
                .map(row -> new InputQueue.QueuedInput(requiredText(row.inputId(), "input_id"),
                        requiredText(row.turnId(), "turn_id"), codec.readUserContent(
                        requiredText(row.contentJson(), "content_json")),
                        InputQueue.Kind.valueOf(requiredText(row.kind(), "kind")),
                        readAttachmentSummaries(requiredText(row.attachmentsJson(), "attachments_json")),
                        InputQueue.Status.valueOf(requiredText(row.validationStatus(), "validation_status")),
                        row.issueErrorCode() == null ? null : new InputQueue.Issue(
                                row.issueErrorCode(), requiredText(row.issueMessage(), "issue_message"),
                                Objects.requireNonNull(row.issueRetryable(), "issue_retryable")),
                        row.inputRevision(),
                        Instant.parse(requiredText(row.createdAt(), "created_at"))))
                .toList();
        return new InputQueue(turn.turnId(), turn.inputQueueRevision(), turn.acceptingInputs(), queued);
    }

    /**
     * Ja 的安全附件摘要属于展示投影；严格解码防止畸形尺寸被 Jackson 静默折成零。
     */
    private List<AttachmentSummary> readAttachmentSummaries(String json) {
        Set<String> expectedFields = Set.of("attachmentId", "displayName", "sizeBytes", "mediaKind", "mediaType");
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
            if (root == null || !root.isArray() || root.size() > 10) {
                throw new IllegalArgumentException("invalid attachment summary array");
            }
            List<AttachmentSummary> result = new ArrayList<>(root.size());
            for (JsonNode item : root) {
                if (!item.isObject() || item.size() != expectedFields.size()) {
                    throw new IllegalArgumentException("invalid attachment summary object");
                }
                java.util.Iterator<String> fieldNames = item.fieldNames();
                while (fieldNames.hasNext()) {
                    if (!expectedFields.contains(fieldNames.next())) {
                        throw new IllegalArgumentException("unknown attachment summary field");
                    }
                }
                JsonNode sizeBytes = item.get("sizeBytes");
                if (sizeBytes == null || !sizeBytes.isIntegralNumber() || !sizeBytes.canConvertToLong()) {
                    throw new IllegalArgumentException("invalid attachment size");
                }
                result.add(new AttachmentSummary(
                        requiredAttachmentText(item, "attachmentId"),
                        requiredAttachmentText(item, "displayName"), sizeBytes.longValue(),
                        requiredAttachmentText(item, "mediaKind"), requiredAttachmentText(item, "mediaType")));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "cannot decode attachment summaries", failure);
        }
    }

    /**
     * 人工标题取得永久所有权；CAS 失败不会被误报为自动结果竞争。
     */
    @Override
    public ThreadSummary renameThread(String threadId, String title, long expectedThreadRevision) {
        return updateTitle(threadId, title, expectedThreadRevision, false)
                .orElseThrow(MybatisHistoryService::conflict);
    }

    /**
     * 偏好更新只影响下一轮；Store CAS 和 Ja 偏好行在同一连接提交并共享 revision。
     */
    @Override
    public ThreadSummary updatePreferences(String threadId, ThreadPreferences preferences,
                                           long expectedThreadRevision) {
        Objects.requireNonNull(preferences, "preferences");
        Instant occurredAt = clock.instant();
        reviseThread(threadId, expectedThreadRevision, occurredAt, false, mapper -> {
            int changed = mapper.history().compareAndSetThreadPreferences(
                    new PersistenceRecords.ThreadPreferencesCas(threadId, preferences.providerId(),
                            preferences.modelId(), preferences.reasoningLevel(), preferences.accessMode().name(),
                            preferences.collaborationMode().name(),
                            expectedThreadRevision, occurredAt.toString()));
            if (changed != 1) {
                throw conflict();
            }
            return true;
        });
        return readThreadSummary(threadId);
    }

    /**
     * 自动标题只从 placeholder 赢一次；Store revision CAS 与标题及 Child Task 投影共用事务。
     */
    @Override
    public boolean writeAutomaticTitle(String threadId, String title, long expectedThreadRevision) {
        return updateTitle(threadId, title, expectedThreadRevision, true).isPresent();
    }

    /**
     * 置顶时间由 App Server 时钟唯一分配；状态 CAS 与 Store/Thread revision 在同一事务推进。
     */
    @Override
    public ThreadSummary pinThread(String threadId, boolean pinned, long expectedThreadRevision) {
        Instant occurredAt = clock.instant();
        reviseThread(threadId, expectedThreadRevision, occurredAt, false, mapper -> {
            int changed = mapper.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    threadId, pinned ? occurredAt.toString() : null,
                    expectedThreadRevision, occurredAt.toString()));
            if (changed != 1) {
                throw conflict();
            }
            return true;
        });
        return readThreadSummary(threadId);
    }

    /**
     * 已读边界只推进到最新成功/失败 Turn；无可确认事实时回滚空 revision，保留原幂等 no-op。
     */
    @Override
    public ThreadSummary markThreadSeen(String threadId, long expectedThreadRevision) {
        Objects.requireNonNull(threadId, "threadId");
        if (expectedThreadRevision < 0) {
            throw new IllegalArgumentException("invalid thread revision");
        }
        Optional<ThreadSummary> replayed = readSeenReplay(threadId, expectedThreadRevision);
        if (replayed.isPresent()) {
            return replayed.orElseThrow();
        }
        Instant occurredAt = clock.instant();
        reviseThread(threadId, expectedThreadRevision, occurredAt, false, mapper -> {
            int changed = mapper.history().compareAndSetThreadSeen(new PersistenceRecords.ThreadSeenCas(
                    threadId, expectedThreadRevision, occurredAt.toString()));
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (changed == 1) {
                return true;
            }
            if (row == null) {
                throw conflict();
            }
            TurnState state = row.latestTurnStatus() == null ? null
                    : TurnState.valueOf(requiredText(row.latestTurnStatus(), "latest_turn_status"));
            if (state == null || state != TurnState.COMPLETED && state != TurnState.FAILED
                || row.latestTurnSeen()) {
                return false;
            }
            throw conflict();
        });
        return readThreadSummary(threadId);
    }

    /**
     * 已读边界单调递增；旧 revision 的重放仅在当前最新终态仍已读时作为无写入成功返回。
     */
    private Optional<ThreadSummary> readSeenReplay(String threadId, long requestedRevision) {
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null || row.deletedAt() != null) {
                throw conflict();
            }
            AgentStore.ConversationSnapshot conversation = agentStore.loadConversation(
                    APP_IDENTITY.conversation(threadId, row.ownerUserId())).orElseThrow(MybatisHistoryService::conflict);
            if (row.revision() != conversation.revision()) {
                throw conflict();
            }
            if (requestedRevision >= row.revision()) {
                return Optional.empty();
            }
            TurnState state = row.latestTurnStatus() == null ? null
                    : TurnState.valueOf(requiredText(row.latestTurnStatus(), "latest_turn_status"));
            if (row.latestTurnSeen() && (state == TurnState.COMPLETED || state == TurnState.FAILED)) {
                return Optional.of(thread(row));
            }
            return Optional.empty();
        });
    }

    /**
     * 人工和系统标题共用事务边界；人工路径保持外部精确 revision CAS，自动路径把 revision
     * 解释为安全下界并以 placeholder 来源作为真正所有权 CAS，避免读后重试窗口。Child
     * 展示名来自 Thread，因此标题提交还在同一事务推进 Task revision，隔离提交前已读出的旧摘要；
     * 根 Thread 没有 Task projection，该更新自然为零行且不改变普通对话语义。
     */
    private Optional<ThreadSummary> updateTitle(String threadId, String title, long expectedRevision,
                                                boolean placeholderOnly) {
        if (title == null || title.isBlank() || title.length() > 512 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread title");
        }
        Instant occurredAt = clock.instant();
        boolean changed;
        try {
            changed = reviseThread(threadId, expectedRevision, occurredAt, placeholderOnly, mapper -> {
                String source = (placeholderOnly
                        ? ThreadPreferences.TitleSource.AUTO : ThreadPreferences.TitleSource.MANUAL).name();
                int rows = mapper.history().compareAndSetThreadTitle(new PersistenceRecords.ThreadTitleCas(
                        threadId, title, source, expectedRevision, occurredAt.toString(), placeholderOnly));
                if (rows != 1) {
                    if (placeholderOnly) {
                        return false;
                    }
                    throw conflict();
                }
                mapper.tasks().advanceTitleProjection(threadId, occurredAt.toString());
                return true;
            });
        } catch (StorageException failure) {
            if (!placeholderOnly || failure.code() != StorageException.Code.CAS_CONFLICT) {
                throw failure;
            }
            return Optional.empty();
        }
        return changed ? Optional.of(readThreadSummary(threadId)) : Optional.empty();
    }

    /**
     * archive 只允许 idle Thread；生命周期 CAS 与通用会话 revision 同一 Store 事务提交。
     */
    @Override
    public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) {
        return lifecycle(threadId, expectedThreadRevision, false);
    }

    /**
     * 已归档 Thread 只能经显式 Store/Thread CAS 恢复，且不会恢复旧置顶顺序。
     */
    @Override
    public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) {
        Instant occurredAt = clock.instant();
        reviseThread(threadId, expectedThreadRevision, occurredAt, false, mapper -> {
            if (mapper.history().restoreThread(new PersistenceRecords.ThreadRestore(
                    threadId, expectedThreadRevision, occurredAt.toString())) != 1) {
                        throw conflict();
                    }
            return true;
        });
        ThreadSummary restored = readThreadSummary(threadId);
        reopenThreadProcesses(restored);
        return restored;
    }

    /**
     * delete 是数据库 tombstone，不删除消息、文件或 workspace 目录。
     */
    @Override
    public void deleteThread(String threadId, long expectedThreadRevision) {
        lifecycle(threadId, expectedThreadRevision, true);
    }

    /**
     * turnId 在 V1 schema 全局唯一，因此 cancellation 不需要 current thread 隐式替代值。
     */
    @Override
    public Optional<TurnSummary> findTurn(String turnId) {
        return transactions.required(mapper -> Optional.ofNullable(mapper.agent().selectTurnById(turnId)).map(row ->
                new TurnSummary(requiredText(row.threadId(), "thread_id"), requiredText(row.turnId(), "turn_id"),
                        requiredText(row.state(), "state"), requiredNumber(row.threadRevision(), "thread_revision"),
                        requiredNumber(row.mutationVersion(), "mutation_version"),
                        row.cancelRequestedAt() != null)));
    }

    /**
     * Tool artifact 使用 code point 游标，因此任意 offset 都不会落在 surrogate pair 中间。
     */
    @Override
    public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                       String artifactId, int offsetCharacters,
                                                       int limitCharacters) {
        if (offsetCharacters < 0 || limitCharacters < 1 || limitCharacters > 65_536) {
            throw new IllegalArgumentException("invalid artifact page");
        }
        return transactions.required(mapper -> Optional.ofNullable(mapper.agent().selectToolArtifact(
                new PersistenceRecords.ToolArtifactKey(threadId, turnId, callId, artifactId))).map(row -> {
            String content = row.content();
            int total = Math.toIntExact(row.characterLength());
            if (content.codePointCount(0, content.length()) != total) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "tool artifact length does not match stored content");
            }
            int startPoint = Math.min(offsetCharacters, total);
            int endPoint = Math.min(total, startPoint + limitCharacters);
            int start = content.offsetByCodePoints(0, startPoint);
            int end = content.offsetByCodePoints(0, endPoint);
            return new TextArtifactPage(row.artifactId(), startPoint,
                    endPoint < total ? endPoint : null, total, endPoint < total, content.substring(start, end));
        }));
    }

    /**
     * 当前条目和 owner 由一条精确 SQL 共同校验；正文只从该条目引用的事实读取，拒绝旧路径与无关身份。
     */
    @Override
    public Optional<ItemContentPage> readItemContent(String threadId, String itemId,
                                                      int offsetCharacters, int limitCharacters) {
        if (offsetCharacters < 0 || limitCharacters < 1 || limitCharacters > 65_536) {
            throw new IllegalArgumentException("invalid item content page");
        }
        return transactions.required(mapper -> {
            ThreadForkRecords.HistoryItemRow inherited = mapper.threadForks().selectHistoryItem(threadId, itemId);
            if (inherited != null) {
                if (inherited.fullContent() == null) {
                    return Optional.empty();
                }
                PersistenceRecords.SnapshotItemRow item = readSnapshotItemRow(inherited.itemJson());
                return Optional.of(itemContentPage(itemId, inherited.fullContent(),
                        Objects.requireNonNull(item.totalCharacters(), "total_characters"),
                        offsetCharacters, limitCharacters));
            }
            PersistenceRecords.ItemContentReferenceRow reference = mapper.history()
                    .selectItemContentReference(threadId, itemId);
            if (reference == null || !APP_IDENTITY.userId().equals(reference.ownerUserId())) {
                return Optional.empty();
            }
            String text;
            if (reference.mailboxContentJson() != null) {
                text = codec.readUserContent(reference.mailboxContentJson()).text();
            } else {
                AgentStore.ExecutionKey execution = new AgentStore.ExecutionKey(
                        new ConversationKey(reference.coreTenantId(), reference.coreThreadId(),
                                reference.ownerUserId()), reference.coreTurnId());
                Optional<AgentStore.StoredRecord> found = agentStore.readRecord(execution, reference.coreRecordId());
                if (found.isEmpty()) {
                    return Optional.empty();
                }
                text = publicRecordText(found.orElseThrow(), reference.kind());
            }
            return Optional.of(itemContentPage(itemId, text, reference.totalCharacters(),
                    offsetCharacters, limitCharacters));
        });
    }

    /**
     * 使用统一的 Unicode 码点边界分页读取正文，使本地与继承文本在 owner 不同时仍有一致偏移语义。
     *
     * @author kongweiguang
     */
    private static ItemContentPage itemContentPage(String itemId, String text, int expectedTotal,
                                                   int offsetCharacters, int limitCharacters) {
            int total = text.codePointCount(0, text.length());
            if (total != expectedTotal) {
                throw new StorageException(StorageException.Code.INVALID_STATE, "item content length is inconsistent");
            }
            if (offsetCharacters > total) {
                throw new IllegalArgumentException("item content offset is past end");
            }
            int next = offsetCharacters + Math.min(limitCharacters, total - offsetCharacters);
            String page = text.substring(text.offsetByCodePoints(0, offsetCharacters), text.offsetByCodePoints(0, next));
            return new ItemContentPage(itemId, offsetCharacters,
                    next < total ? next : null, total, next < total, page);
    }

    /**
     * 公开文本类型采用闭集；原生 reasoning、Tool 内容与私有 Provider 数据不能借正文接口导出。
     */
    private String publicRecordText(AgentStore.StoredRecord record, String kind) {
        StorePayloadCodec records = new StorePayloadCodec(objectMapper);
        if ("USER_INPUT".equals(kind)) {
            return records.decodeUser(record).content().text();
        }
        ExecutionFact fact = records.decode(record);
        if ("REASONING_SUMMARY".equals(kind)) {
            if (fact instanceof ExecutionFacts.ReasoningSummary summary) {
                return summary.text();
            }
            if (fact instanceof ExecutionFacts.AssistantMessage assistant && assistant.reasoningSummary() != null) {
                return assistant.reasoningSummary();
            }
        } else if (("ASSISTANT_PROGRESS".equals(kind) || "FINAL_ANSWER".equals(kind))
                   && fact instanceof ExecutionFacts.AssistantMessage assistant) {
            return assistant.publicText();
        }
        throw new StorageException(StorageException.Code.INVALID_STATE, "item content reference has wrong record type");
    }

    /**
     * 每次点击都按完整身份读取持久 artifact，并在一次严格解析后只返回目标文件；不保留正文或索引缓存。
     */
    @Override
    public Optional<ChangeSetArtifactFile> readChangeSetArtifact(String threadId, String turnId,
                                                                 String artifactId, String filePath) {
        validateArtifactPath(filePath);
        PersistenceRecords.ChangeSetArtifactRow row = transactions.required(mapper ->
                mapper.history().selectChangeSetArtifact(new PersistenceRecords.ChangeSetArtifactKey(
                        threadId, turnId, artifactId)));
        if (row == null) {
            return Optional.empty();
        }
        return extractChangeSetFile(row, filePath);
    }

    /**
     * 生命周期操作先检查 active Turn，再执行一次 CAS，不把竞争失败解释为成功；进程用例是共享 owner，仅关闭目标 Thread 资源。
     */
    @SuppressWarnings("PMD.CloseResource") // ProcessSessionUseCase 由运行时组合根持有，本方法不关闭共享用例 owner。
    private ThreadSummary lifecycle(String threadId, long expectedRevision, boolean delete) {
        PersistenceRecords.ThreadRow owner = requireIdleLifecycleTarget(threadId, expectedRevision);
        ProcessSessionUseCase sessions = processSessions.get();
        ProcessSessionScope scope = sessions == null ? null
                : new ProcessSessionScope(owner.workspaceId(), owner.threadId(), sessions.generation());
        if (sessions != null) {
            closeThreadProcesses(sessions, scope);
        }
        try {
            Instant occurredAt = clock.instant();
            reviseThread(threadId, expectedRevision, occurredAt, false, mapper -> {
                if (mapper.history().countActiveTurns(threadId) != 0) {
                    throw new StorageException(StorageException.Code.INVALID_STATE,
                            "active thread cannot change lifecycle");
                }
                if (mapper.history().updateThreadLifecycle(new PersistenceRecords.ThreadLifecycle(
                        threadId, expectedRevision, occurredAt.toString(), delete)) != 1) {
                    throw conflict();
                }
                return true;
            });
            return delete ? null : readThreadSummary(threadId);
        } catch (RuntimeException failure) {
            if (sessions != null) {
                reopenAfterLifecycleFailure(sessions, scope, failure);
            }
            throw failure;
        }
    }

    /**
     * 在触碰原生进程前校验 Ja/Core 双 revision 和活动 Turn，stale 或 busy 请求不得关闭资源。
     */
    private PersistenceRecords.ThreadRow requireIdleLifecycleTarget(String threadId, long expectedRevision) {
        PersistenceRecords.ThreadRow owner = transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null || row.deletedAt() != null || row.revision() != expectedRevision) {
                throw conflict();
            }
            if (mapper.history().countActiveTurns(threadId) != 0) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "active thread cannot change lifecycle");
            }
            return row;
        });
        AgentStore.ConversationSnapshot conversation = agentStore.loadConversation(
                APP_IDENTITY.conversation(threadId, owner.ownerUserId())).orElseThrow(MybatisHistoryService::conflict);
        if (conversation.revision() != expectedRevision) {
            throw conflict();
        }
        return owner;
    }

    /**
     * closeThread 先建立 Thread launch fence 再回收现有进程；真实清理失败时生命周期 CAS 不会运行。
     */
    private static void closeThreadProcesses(ProcessSessionUseCase sessions, ProcessSessionScope scope) {
        try {
            sessions.closeThread(scope);
        } catch (RuntimeException failure) {
            reopenAfterLifecycleFailure(sessions, scope, failure);
            throw failure;
        }
    }

    /**
     * 失败补偿只重新开放后续进程准入，不尝试恢复已终止进程，并保留双重故障证据。
     */
    private static void reopenAfterLifecycleFailure(ProcessSessionUseCase sessions,
                                                    ProcessSessionScope scope,
                                                    RuntimeException failure) {
        try {
            sessions.reopenThread(scope);
        } catch (RuntimeException reopenFailure) {
            if (reopenFailure != failure) {
                failure.addSuppressed(reopenFailure);
            }
        }
    }

    /**
     * 只在 restore CAS 成功后移除旧进程准入 fence；借用共享进程用例开放准入，历史进程仍保留其已结束事实。
     */
    @SuppressWarnings("PMD.CloseResource") // ProcessSessionUseCase 由运行时组合根持有，本方法只借用它更新 Thread 准入。
    private void reopenThreadProcesses(ThreadSummary thread) {
        ProcessSessionUseCase sessions = processSessions.get();
        if (sessions != null) {
            sessions.reopenThread(new ProcessSessionScope(
                    thread.workspaceId(), thread.threadId(), sessions.generation()));
        }
    }

    /**
     * Store revision 与 Thread 元数据使用同一连接 CAS；SQL 故障在参与者边界包装，no-op 原样触发回滚。
     */
    private boolean reviseThread(String threadId, long expectedRevision, Instant occurredAt,
                                 boolean allowAdvancedThreadRevision, ThreadMutation mutation) {
        PersistenceRecords.ThreadRow owner = transactions.required(mapper -> mapper.history().selectThread(threadId));
        if (owner == null || owner.deletedAt() != null) {
            throw conflict();
        }
        AgentStore.ConversationKey conversation = APP_IDENTITY.conversation(threadId, owner.ownerUserId());
        AgentStore.ConversationSnapshot current = agentStore.loadConversation(conversation)
                .orElseThrow(MybatisHistoryService::conflict);
        long storeRevision = current.revision();
        if (allowAdvancedThreadRevision ? storeRevision < expectedRevision : storeRevision != expectedRevision) {
            throw conflict();
        }
        try {
            agentStore.reviseConversation(new AgentStore.ConversationRevision(
                    conversation, storeRevision, occurredAt), transaction -> {
                if (!conversation.equals(transaction.conversation())
                    || transaction.expectedRevision() != storeRevision) {
                    throw new IllegalStateException("AgentStore revised a different Ja conversation revision");
                }
                AgentStoreConnectionScope.participate(JdbcTransactionResources.connection(transaction), () -> transactions.required(mapper -> {
                    if (!mutation.apply(mapper)) {
                        throw new ThreadMutationSkipped();
                    }
                    return null;
                }));
            });
            return true;
        } catch (ThreadMutationSkipped skipped) {
            return false;
        } catch (AgentStore.StoreConflictException conflict) {
            throw conflict(conflict);
        }
    }

    /**
     * 读取事务参与者完成 revision 镜像后的 Thread，避免返回尚未提交的旧版本。
     */
    private ThreadSummary readThreadSummary(String threadId) {
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null || row.deletedAt() != null) {
                throw notFound("thread");
            }
            return thread(row);
        });
    }

    /**
     * snapshot 只投影公开文本、Tool 和审批，文件/Git 状态由 Rust Review 实时计算。
     */
    private ThreadSnapshot.Item snapshotItem(PersistenceRecords.SnapshotItemRow row) {
        String kind = requiredText(row.itemKind(), "item_kind");
        Instant createdAt = Instant.parse(requiredText(row.createdAt(), "created_at"));
        String persistedItemId = requiredText(row.itemId(), "item_id");
        String itemId = "message".equals(kind) ? persistedItemId : snapshotItemId(kind, persistedItemId);
        return switch (kind) {
            case "message" -> messageItem(row, createdAt, itemId);
            case "tool_call" -> new ThreadSnapshot.ToolItem(itemId, createdAt,
                    requiredText(row.turnId(), "turn_id"), ThreadSnapshot.ToolKind.TOOL_CALL,
                    requiredText(row.callId(), "call_id"),
                    requiredText(row.toolName(), "tool_name"),
                    presentations.read(requiredText(row.presentationJson(), "presentation_json")),
                    Math.toIntExact(requiredNumber(row.toolOrdinal(), "tool_ordinal")));
            case "approval" -> new ThreadSnapshot.ApprovalItem(itemId, createdAt,
                    requiredText(row.approvalId(), "approval_id"), requiredText(row.turnId(), "turn_id"),
                    requiredText(row.callId(), "call_id"), requiredText(row.toolName(), "tool_name"),
                    "Tool requires approval", row.decision(),
                    Instant.parse(requiredText(row.expiresAt(), "expires_at")));
            default -> throw new StorageException(StorageException.Code.INVALID_STATE,
                    "unknown snapshot item kind");
        };
    }

    /**
     * 快照只恢复可见的有界预览和有序引用元数据，不解码或物化完整 Core 消息。
     */
    private ThreadSnapshot.Item messageItem(PersistenceRecords.SnapshotItemRow row, Instant createdAt, String itemId) {
        String messageKind = requiredText(row.messageKind(), "kind");
        String preview = requiredValue(row.previewText(), "preview_text");
        int total = Objects.requireNonNull(row.totalCharacters(), "total_characters");
        if ("USER_INPUT".equals(messageKind)) {
            List<AttachmentSummary> attachments = readAttachmentSummaries(
                    requiredText(row.attachmentsJson(), "attachments_json"));
            return new ThreadSnapshot.UserInputItem(itemId, createdAt, requiredText(row.turnId(), "turn_id"),
                    previewUserContent(row.contextReferencesJson(), attachments, preview), attachments, total);
        }
        if ("THREAD_MESSAGE".equals(messageKind)) {
            return new ThreadSnapshot.ThreadMessageItem(itemId, createdAt, requiredText(row.turnId(), "turn_id"),
                    requiredText(row.sourceThreadId(), "source_thread_id"), requiredText(row.sourceTitle(), "source_title"),
                    preview, total);
        }
        return new ThreadSnapshot.TextItem(itemId, createdAt, requiredText(row.turnId(), "turn_id"),
                ThreadSnapshot.TextKind.valueOf(messageKind), preview, total,
                row.modelRound() == null ? null : Math.toIntExact(row.modelRound()));
    }

    /**
     * 引用序号必须连续并只恢复资源或 Skill 身份；附件和正文保持 UserContent 的规范顺序。
     */
    private UserContent previewUserContent(String referencesJson, List<AttachmentSummary> attachments, String preview) {
        try {
            JsonNode references = objectMapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(referencesJson);
            if (references == null || !references.isArray() || references.size() > 64) {
                throw new IllegalArgumentException("invalid context references");
            }
            List<UserContentBlock> blocks = new ArrayList<>();
            for (JsonNode reference : references) {
                if (!reference.path("ordinal").isIntegralNumber() || reference.path("ordinal").intValue() != blocks.size()) {
                    throw new IllegalArgumentException("invalid context reference order");
                }
                String kind = requiredAttachmentText(reference, "kind");
                if ("resource".equals(kind) && reference.size() == 5) {
                    blocks.add(new ResourceReferenceContent(requiredAttachmentText(reference, "sourceId"),
                            requiredAttachmentText(reference, "resourceId"), requiredAttachmentText(reference, "resourceKind")));
                } else if ("skill".equals(kind) && reference.size() == 3) {
                    blocks.add(new SkillReferenceContent(requiredAttachmentText(reference, "skillId")));
                } else {
                    throw new IllegalArgumentException("invalid context reference");
                }
            }
            attachments.forEach(attachment -> blocks.add(new AttachmentContent(attachment.attachmentId())));
            if (!preview.isEmpty()) {
                blocks.add(new TextContent(preview));
            }
            return new UserContent(blocks);
        } catch (java.io.IOException | IllegalArgumentException invalid) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid snapshot input metadata", invalid);
        }
    }

    /**
     * 从同一数据库快照选择最新完整请求估算或更晚 checkpoint；本地预算始终与 Provider 计量分开恢复。
     */
    private ThreadSnapshot.ContextUsage contextUsage(PersistenceRecords.ContextUsageRow row,
                                                     CheckpointStore.ContextCheckpoint checkpoint) {
        try {
            ProviderRequestUsage.Certainty certainty = ProviderRequestUsage.Certainty.valueOf(
                    requiredText(row.certainty(), "certainty"));
            Instant measuredAt = Instant.parse(requiredText(row.occurredAt(), "occurred_at"));
            Instant checkpointAt = checkpoint == null ? null
                    : checkpoint.createdAt();
            // 同刻也视为 checkpoint 已改变上下文；用其持久 envelope 预算恢复本地估算，而非旧 Provider 用量。
            boolean checkpointInvalidatesUsage = checkpointAt != null && !checkpointAt.isBefore(measuredAt);
            if (checkpointInvalidatesUsage) {
                certainty = ProviderRequestUsage.Certainty.UNKNOWN;
            }
            Long estimatedTokens = ProviderRequestUsage.Certainty.UNKNOWN == certainty
                    ? row.estimatedContextTokens() : null;
            Instant estimatedAt = estimatedTokens == null ? null : measuredAt;
            if (checkpointInvalidatesUsage) {
                estimatedTokens = (long) checkpoint.estimatedTokens();
                estimatedAt = checkpointAt;
            }
            ProviderRequestProfile profile = providerProfile(
                    requiredText(row.profileJson(), "profile_json"));
            io.github.kongweiguang.ja.agent.domain.model.ModelUsage usage =
                    certainty == ProviderRequestUsage.Certainty.UNKNOWN ? null
                            : new io.github.kongweiguang.ja.agent.domain.model.ModelUsage(
                            row.inputTokens(), row.outputTokens(), row.totalTokens(),
                            row.cacheReadTokens(), row.cacheWriteTokens(),
                            io.github.kongweiguang.ja.agent.domain.model.ModelUsage.InputAccounting
                                    .valueOf(requiredText(row.inputAccounting(), "input_accounting")));
            ProviderRequestUsage request = new ProviderRequestUsage(
                    requiredText(row.modelRequestId(), "model_request_id"), row.modelRequestOrdinal(),
                    Math.toIntExact(row.modelRound()), ProviderRequestUsage.Purpose.valueOf(
                    requiredText(row.purpose(), "purpose")), certainty, profile, usage);
            return new ThreadSnapshot.ContextUsage(requiredText(row.turnId(), "turn_id"), request,
                    measuredAt, estimatedTokens, estimatedAt);
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid context usage", failure);
        }
    }

    /**
     * 严格读取 Ja 自有请求画像；缺字段或出现未知字段都拒绝历史投影，避免用默认配置改写审计事实。
     */
    private ProviderRequestProfile providerProfile(String json) {
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
            Set<String> expected = Set.of("providerId", "modelId", "api", "upstreamModel",
                    "requestedReasoning", "effectiveReasoning", "accessMode", "collaborationMode",
                    "configGeneration", "promptRevision", "toolCatalogRevision",
                    "contextWindowTokens", "maxOutputTokens");
            if (root == null || !root.isObject() || root.size() != expected.size()) {
                throw new IllegalArgumentException("invalid provider profile object");
            }
            java.util.Iterator<String> names = root.fieldNames();
            while (names.hasNext()) {
                if (!expected.contains(names.next()))
                    {
                        throw new IllegalArgumentException("unknown provider profile field");
                    }
            }
            return new ProviderRequestProfile(text(root, "providerId"), text(root, "modelId"),
                    text(root, "api"), text(root, "upstreamModel"), nullableText(root, "requestedReasoning"),
                    nullableText(root, "effectiveReasoning"), AccessMode.valueOf(text(root, "accessMode")),
                    CollaborationMode.valueOf(text(root, "collaborationMode")), text(root, "configGeneration"),
                    text(root, "promptRevision"), text(root, "toolCatalogRevision"),
                    integer(root, "contextWindowTokens"), integer(root, "maxOutputTokens"));
        } catch (IOException | IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "invalid provider request profile", failure);
        }
    }

    /**
     * 只接受与 Ja Thread 同 revision 的通用 checkpoint 快照，拒绝跨提交版本拼接历史。
     */
    private CheckpointStore.ContextCheckpoint latestCheckpoint(String threadId, String ownerUserId,
                                                               long threadRevision) {
        AgentStore.ConversationSnapshot snapshot = agentStore.loadConversation(
                APP_IDENTITY.conversation(threadId, ownerUserId)).orElseThrow(
                () -> new StorageException(StorageException.Code.INVALID_STATE,
                        "thread has no authoritative AgentStore conversation"));
        if (snapshot.revision() != threadRevision) {
            throw new StorageException(StorageException.Code.CAS_CONFLICT,
                    "thread and AgentStore conversation revisions differ");
        }
        return checkpoints.read(snapshot).checkpoint().orElse(null);
    }

    /**
     * 历史 Turn 只投影业务状态；来源消息与实时事件共享身份，活动轮次读取同事务的权威执行游标。
     */
    private ThreadSnapshot.Turn snapshotTurn(PersistenceMappers mapper, PersistenceRecords.TurnRow row,
                                             boolean suppressError) {
        String state = requiredText(row.state(), "state").toLowerCase(Locale.ROOT);
        TurnState lifecycle = parseTurnState(state);
        return new ThreadSnapshot.Turn(requiredText(row.turnId(), "turn_id"),
                state,
                Instant.parse(requiredText(row.requestedAt(), "requested_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")),
                row.completedAt() == null ? null : Instant.parse(row.completedAt()),
                suppressError ? null : row.errorCode(),
                row.changeSetJson() == null ? null : changeSets.read(row.changeSetJson()),
                requiredNumber(row.mutationVersion(), "mutation_version"),
                lifecycle.terminal() ? 0 : executionModelRound(mapper, row),
                row.sourceMessageId() == null ? null : SnapshotItemIdentity.of("message", row.sourceMessageId()));
    }

    /**
     * UNKNOWN usage 可以先于模型正文提交，不能将请求轮次冒充为已完成轮次；按同一读取事务中的
     * Turn mutation 和执行游标严格解码，避免跨提交拼接公开流基线。
     */
    private int executionModelRound(PersistenceMappers mapper, PersistenceRecords.TurnRow row) {
        PersistenceRecords.ThreadRow thread = mapper.history().selectThread(row.threadId());
        if (thread == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "turn owner Thread is missing");
        }
        String cursor = mapper.history().selectExecutionCursor(APP_IDENTITY.tenantId(), row.threadId(),
                row.turnId(), thread.ownerUserId(),
                row.mutationVersion(), requiredText(row.state(), "state"), executionCursors.schemaVersion());
        if (cursor == null) {
            throw new StorageException(StorageException.Code.INVALID_STATE,
                    "turn execution cursor does not match history projection");
        }
        return executionCursors.read(executionCursors.schemaVersion(), new JacksonJsonValueCodec(objectMapper).decodeObject(cursor)).common().modelRound();
    }

    /**
     * 同步更新 Thread 元数据；false 仅用于需要回滚空 Store revision 的合法 no-op。
     */
    @FunctionalInterface
    private interface ThreadMutation {
        /**
         * 返回 false 会中止外层 Store 事务，确保 no-op 不推进任何一侧 revision。
         */
        boolean apply(PersistenceMappers mapper);
    }

    /**
     * 文件范围使用 aggregate UTF-8 字节绝对偏移，选中后复制成独立返回正文。
     *
     * @param start 起始位置
     * @param end 结束位置
     */
    private record ByteRange(int start,
                             int end) {
    }

    /**
     * 单行同时保留已解码文本和下一行字节起点，避免字符数被误作 byte offset。
     *
     * @param text 文本内容
     * @param next 下一执行步骤
     */
    private record ParsedLine(String text,
                              int next) {
    }

    /**
     * hunk 只需要两侧逻辑行计数来确认下一文件 header 的合法位置。
     *
     * @param before 变更前的行数
     * @param after 变更后的行数
     */
    private record HunkCounts(int before,
                              int after) {
    }

    /**
     * 单次请求的临时解析结果不跨请求保存，方法返回后即可回收聚合正文与索引。
     *
     * @param artifactId 产物标识
     * @param bytes 二进制数据
     * @param files 文件列表
     */
    private record IndexedChangeSet(String artifactId,
                                    byte[] bytes,
                                    Map<String, ByteRange> files) {
    }

    /**
     * 事务回调确认无需写入时使用，触发 AgentStore 原子回滚而不暴露为持久化故障。
     */
    private static final class ThreadMutationSkipped extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        /**
         * 该控制流异常不保留栈和根因，避免把合法 no-op 记录为失败。
         */
        private ThreadMutationSkipped() {
            super(null, null, false, false);
        }
    }

    /**
     * 保存已经严格校验的 keyset 分页位置，禁止携带旧 offset 语义。
     *
     * @param time 时间
     * @param id 当前实体的稳定标识
     */
    private record Cursor(String time,
                          String id) {
    }

    /**
     * 保存 active Thread 的四段稳定排序边界。
     *
     * @param pinned 固定
     * @param sortTime 排序时间
     * @param updatedAt 最后更新时间
     * @param id 当前实体的稳定标识
     */
    private record ThreadCursor(int pinned,
                                String sortTime,
                                String updatedAt,
                                String id) {
    }

    /**
     * 区分不可变分叉前缀与本地写入历史，使两个阶段分别使用适合自身的 keyset 顺序。
     *
     * @author kongweiguang
     */
    private enum TimelinePhase {
        /** 由来源冻结并复制到当前 Thread 的历史前缀。 */
        INHERITED,
        /** 当前 Thread 创建后写入的本地历史后缀。 */
        NATIVE
    }

    /**
     * 只携带当前阶段对应的 key；混用序号和时间戳会使本地后缀追加后分页位置漂移。
     *
     * @param phase 当前时间线来源阶段
     * @param time 本地阶段使用的创建时间键
     * @param id 本地阶段使用的稳定条目标识
     * @param ordinal 继承阶段使用的目标内顺序号
     * @author kongweiguang
     */
    private record TimelineCursor(TimelinePhase phase,
                                  String time,
                                  String id,
                                  long ordinal) {
        /**
         * 构造时拒绝混合键形状，使内部生成的游标即使绕过解码入口也满足对应阶段的不变量。
         *
         * @author kongweiguang
         */
        private TimelineCursor {
            Objects.requireNonNull(phase, "phase");
            if (phase == TimelinePhase.INHERITED) {
                if (time != null || id != null || ordinal < 0) {
                    throw new IllegalArgumentException("invalid inherited timeline cursor");
                }
            } else if (time == null || id == null || id.isBlank() || ordinal != 0) {
                throw new IllegalArgumentException("invalid native timeline cursor");
            }
        }
    }

    /**
     * 页面项只引用一种存储来源，避免公开投影把冻结继承行误当成本地可变行。
     *
     * @param inheritedItem child-owned 的冻结继承历史行
     * @param nativeItem 当前 Thread 的本地快照行
     * @author kongweiguang
     */
    private record TimelineEntry(ThreadForkRecords.HistoryItemRow inheritedItem,
                                 PersistenceRecords.SnapshotItemRow nativeItem) {
        /**
         * 显式建立继承来源分支，避免合并处通过空值约定猜测行来源。
         *
         * @author kongweiguang
         */
        private static TimelineEntry inherited(ThreadForkRecords.HistoryItemRow item) {
            return new TimelineEntry(Objects.requireNonNull(item, "item"), null);
        }

        /**
         * 显式建立本地来源分支，避免合并处通过空值约定猜测行来源。
         *
         * @author kongweiguang
         */
        private static TimelineEntry nativeItem(PersistenceRecords.SnapshotItemRow item) {
            return new TimelineEntry(null, Objects.requireNonNull(item, "item"));
        }

        /**
         * 强制且仅有一个来源行，防止后续投影分支静默漏项或重复计数。
         *
         * @author kongweiguang
         */
        private TimelineEntry {
            if ((inheritedItem == null) == (nativeItem == null)) {
                throw new IllegalArgumentException("timeline entry must have one backing row");
            }
        }
    }

    /**
     * 返回有界的正序时间线切片和阶段续页游标，不把 mapper 行暴露给领域投影。
     *
     * @param entries 已按公开时间线顺序合并的条目
     * @param nextCursor 下一页游标；无后续内容时为空
     * @author kongweiguang
     */
    private record TimelinePage(List<TimelineEntry> entries,
                                String nextCursor) {
        /**
         * 复制合并结果，保证游标计算与公开投影观察同一稳定页面。
         *
         * @author kongweiguang
         */
        private TimelinePage {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * 保存所选中性消息前缀及已冻结结果闭合的 Tool 调用集合，供展示项过滤与附件扫描共享。
     *
     * @param messageItemIds 前缀中模型消息对应的公开条目标识
     * @param contextMessageOrdinals 消息条目标识到模型上下文顺序号的映射
     * @param closedToolCallIds 在同一 Turn 中已有结果的 Tool 调用身份
     * @author kongweiguang
     */
    private record ForkCutoff(Set<String> messageItemIds,
                              Map<String, Long> contextMessageOrdinals,
                              Set<ForkToolIdentity> closedToolCallIds) {
        /**
         * 复制集合使 keyset 过滤与附件扫描共用的边界不会被调用方后续修改。
         *
         * @author kongweiguang
         */
        private ForkCutoff {
            messageItemIds = Set.copyOf(Objects.requireNonNull(messageItemIds, "messageItemIds"));
            contextMessageOrdinals = Map.copyOf(Objects.requireNonNull(contextMessageOrdinals,
                    "contextMessageOrdinals"));
            closedToolCallIds = Set.copyOf(Objects.requireNonNull(closedToolCallIds, "closedToolCallIds"));
        }
    }

    /**
     * Provider 调用标识按执行 Turn 隔离；组合两者可防止较早答案中的复用 ID 误闭合后续未完成调用。
     *
     * @param turnId 调用所属的执行 Turn
     * @param callId Provider 在该 Turn 内分配的调用标识
     * @author kongweiguang
     */
    private record ForkToolIdentity(String turnId,
                                    String callId) {
        /**
         * 缺少任一身份部分都不能扩大跨 Turn 的 Tool 纳入范围。
         *
         * @author kongweiguang
         */
        private ForkToolIdentity {
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(callId, "callId");
        }
    }
}

