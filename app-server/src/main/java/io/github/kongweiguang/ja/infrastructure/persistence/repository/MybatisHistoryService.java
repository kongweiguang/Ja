// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.TurnRuntimeSnapshot;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.ToolPresentationCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.TurnChangeSetCodec;
import io.github.kongweiguang.ja.infrastructure.persistence.transaction.MybatisUnitOfWork;
import io.github.kongweiguang.ja.workspace.domain.Workspace;
import io.github.kongweiguang.ja.workspace.port.out.WorkspaceRepository;
import org.apache.ibatis.session.SqlSessionFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Workspace 出站仓储与 Thread 入站端口的单库实现；分页和快照不依赖进程级活动状态。
 */
public final class MybatisHistoryService implements WorkspaceRepository, ThreadUseCase {
    private static final int MAX_PAGE = 500;
    private final MybatisUnitOfWork transactions;
    private final MybatisConversationRepository agentStore;
    private final ToolPresentationCodec presentations;
    private final TurnChangeSetCodec changeSets;
    private final Clock clock;

    /**
     * 所有依赖均由 Solon composition 注入并共享同一 datasource 生命周期。
     */
    public MybatisHistoryService(SqlSessionFactory sessions, MybatisConversationRepository agentStore,
                                 ObjectMapper objectMapper, Clock clock) {
        transactions = new MybatisUnitOfWork(sessions);
        this.agentStore = Objects.requireNonNull(agentStore, "agentStore");
        Objects.requireNonNull(objectMapper, "objectMapper");
        presentations = new ToolPresentationCodec(objectMapper);
        changeSets = new TurnChangeSetCodec(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * focused test 仍使用相同 Mapper/XML，只把提交 owner 切为测试 session。
     */
    public MybatisHistoryService(SqlSessionFactory sessions, MybatisConversationRepository agentStore,
                                 ObjectMapper objectMapper, Clock clock,
                                 MybatisUnitOfWork.SessionOwner owner) {
        transactions = new MybatisUnitOfWork(sessions, owner);
        this.agentStore = Objects.requireNonNull(agentStore, "agentStore");
        Objects.requireNonNull(objectMapper, "objectMapper");
        presentations = new ToolPresentationCodec(objectMapper);
        changeSets = new TurnChangeSetCodec(objectMapper);
        this.clock = Objects.requireNonNull(clock, "clock");
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
                            request.trust().name(), request.occurredAt().toString())),
                    "workspace insert lost");
            return workspace(mapper.history().selectWorkspace(request.workspaceId()));
        });
    }

    /**
     * workspace 列表是全局 keyset page，不读取进程级 current workspace。
     */
    @Override
    public CursorPage<Workspace> list(String cursor, int limit) {
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> workspacePage(mapper.history().selectWorkspacePage(
                new PersistenceRecords.WorkspacePage(
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1)), limit));
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
     * 直接按 canonical root 查询 SQLite，使 Java owner 无需扫描分页或改写 trust 即可重开
     * 通用工作区（general workspace）。
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
            if (row == null) throw notFound("workspace");
            long revision = row.revision();
            if (mapper.history().compareAndSetWorkspaceTrust(new PersistenceRecords.WorkspaceTrustCas(
                    workspaceId, trust.name(), revision, clock.instant().toString())) != 1) throw conflict();
            return workspace(mapper.history().selectWorkspace(workspaceId));
        });
    }

    /**
     * unregister 只删注册记录；存在未删除 Thread 或 stale revision 时 fail closed。
     */
    @Override
    public void unregister(String workspaceId, long expectedRevision) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("invalid workspace revision");
        }
        transactions.required(mapper -> {
            if (mapper.history().deleteWorkspace(
                    new PersistenceRecords.WorkspaceDelete(workspaceId, expectedRevision)) != 1) throw conflict();
            return null;
        });
    }

    /**
     * Thread 创建完全使用显式 workspace 与 v3 偏好输入，不读取 active 状态。
     */
    @Override
    public ThreadSummary createThread(ThreadSummary.Creation request) {
        agentStore.createThread(new ConversationRepository.ThreadDefinition(request.threadId(), request.workspaceId(),
                request.title(), request.preferences(), request.occurredAt()));
        return transactions.required(mapper -> thread(mapper.history().selectThread(request.threadId())));
    }

    /**
     * Thread 列表在 SQL 内按 Workspace 过滤，保证 keyset 游标只属于一个项目。
     */
    @Override
    public CursorPage<ThreadSummary> listThreads(String workspaceId, String cursor, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> threadPage(mapper.history().selectThreadPage(
                new PersistenceRecords.ThreadPage(
                        workspaceId, key == null ? null : key.time(),
                        key == null ? null : key.id(), limit + 1)), limit));
    }

    /** 查询词在应用层使用 Locale.ROOT 归一化；SQLite 只执行有界 contains 与既有 keyset。 */
    @Override
    public CursorPage<ThreadSummary> searchThreads(String workspaceId, String query, String cursor, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String normalized = Objects.requireNonNull(query, "query").strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 256 || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread search query");
        }
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> threadPage(mapper.history().searchThreadPage(
                new PersistenceRecords.ThreadSearch(workspaceId, normalized,
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * authoritative snapshot 混合页面按 committed timestamp+itemId 唯一排序，不重放事件日志。
     */
    @Override
    public Optional<ThreadSnapshot> readThread(String threadId, String cursor, int limit) {
        checkLimit(limit);
        Cursor key = decode(cursor);
        return transactions.required(mapper -> {
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (row == null) return Optional.empty();
            List<PersistenceRecords.SnapshotItemRow> rows = mapper.history().selectSnapshotItems(
                    new PersistenceRecords.SnapshotPage(threadId, key == null ? null : key.time(),
                            key == null ? null : key.id(), limit + 1));
            String next = null;
            if (rows.size() > limit) {
                rows = new ArrayList<>(rows.subList(0, limit));
                PersistenceRecords.SnapshotItemRow last = rows.getLast();
                next = encode(requiredText(last.createdAt(), "created_at"),
                        requiredText(last.itemId(), "item_id"));
            }
            List<ThreadSnapshot.Turn> turns = mapper.agent().selectTurns(threadId).stream()
                    .map(this::snapshotTurn).toList();
            List<ThreadSnapshot.Item> items = rows.stream().map(this::snapshotItem).toList();
            PersistenceRecords.ContextUsageRow usageRow = mapper.history().selectLatestContextUsage(threadId);
            ThreadSnapshot.ContextUsage contextUsage = usageRow == null ? null : contextUsage(usageRow);
            return Optional.of(new ThreadSnapshot(thread(row), turns, items, contextUsage, next));
        });
    }

    /** 人工标题取得永久所有权；CAS 失败不会被误报为自动结果竞争。 */
    @Override
    public ThreadSummary renameThread(String threadId, String title, long expectedThreadRevision) {
        return updateTitle(threadId, title, expectedThreadRevision, false)
                .orElseThrow(MybatisHistoryService::conflict);
    }

    /** 偏好更新只影响下一轮，Thread revision 是外部并发唯一门。 */
    @Override
    public ThreadSummary updatePreferences(String threadId, ThreadPreferences preferences,
                                           long expectedThreadRevision) {
        Objects.requireNonNull(preferences, "preferences");
        return transactions.required(mapper -> {
            int changed = mapper.history().compareAndSetThreadPreferences(
                    new PersistenceRecords.ThreadPreferencesCas(threadId, preferences.providerId(),
                            preferences.modelId(), preferences.reasoningLevel(), preferences.accessMode().name(),
                            expectedThreadRevision, clock.instant().toString()));
            if (changed != 1) throw conflict();
            return thread(mapper.history().selectThread(threadId));
        });
    }

    /**
     * 自动标题只从 placeholder 状态赢一次；后续 Turn 推进 revision 不影响首次标题，
     * 但低于首次成功 Turn revision、人工标题、删除状态都会由单条 SQL 原子拒绝。
     */
    @Override
    public boolean writeAutomaticTitle(String threadId, String title, long expectedThreadRevision) {
        return updateTitle(threadId, title, expectedThreadRevision, true).isPresent();
    }

    /**
     * 人工和系统标题共用事务边界；人工路径保持外部精确 revision CAS，自动路径把 revision
     * 解释为安全下界并以 placeholder 来源作为真正所有权 CAS，避免读后重试窗口。
     */
    private Optional<ThreadSummary> updateTitle(String threadId, String title, long expectedRevision,
                                                boolean placeholderOnly) {
        if (title == null || title.isBlank() || title.length() > 512 || title.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid thread title");
        }
        return transactions.required(mapper -> {
            String source = titleSourceStorage(placeholderOnly
                    ? ThreadPreferences.TitleSource.AUTO : ThreadPreferences.TitleSource.MANUAL);
            int changed = mapper.history().compareAndSetThreadTitle(new PersistenceRecords.ThreadTitleCas(
                    threadId, title, source, expectedRevision, clock.instant().toString(), placeholderOnly));
            return changed == 1 ? Optional.of(thread(mapper.history().selectThread(threadId))) : Optional.empty();
        });
    }

    /**
     * archive 只允许 idle Thread，revision CAS 与生命周期标记同事务。
     */
    @Override
    public void archiveThread(String threadId, long expectedThreadRevision) {
        lifecycle(threadId, expectedThreadRevision, false);
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
                        requiredText(row.state(), "state"), requiredNumber(row.threadRevision(), "thread_revision"))));
    }

    /** Rust 捕获结果只允许在终态 Turn 上提交一次；Java 重新核对 Workspace、统计、UTF-8 长度与 SHA-256。 */
    @Override
    public io.github.kongweiguang.ja.conversation.domain.TurnChangeSet commitChangeSet(ChangeSetCommit request) {
        Objects.requireNonNull(request, "request");
        var supplied = Objects.requireNonNull(request.changeSet(), "changeSet");
        if (supplied.artifactId() != null) throw new IllegalArgumentException("artifactId is server owned");
        validateStats(supplied);
        byte[] diff = request.unifiedDiff() == null ? null
                : request.unifiedDiff().getBytes(StandardCharsets.UTF_8);
        if ((diff == null) != (request.sha256() == null || request.byteLength() == null)) {
            throw new IllegalArgumentException("change set artifact fields must be paired");
        }
        if (diff != null && (diff.length > 2 * 1024 * 1024 || request.byteLength() != diff.length
            || !sha256(request.unifiedDiff()).equals(request.sha256()))) {
            throw new IllegalArgumentException("change set artifact integrity mismatch");
        }
        String artifactId = diff == null ? null
                : "artifact_" + java.util.UUID.randomUUID().toString().replace("-", "");
        var committed = new io.github.kongweiguang.ja.conversation.domain.TurnChangeSet(
                supplied.state(), supplied.reason(), supplied.files(), supplied.stats(), artifactId);
        return transactions.required(mapper -> {
            PersistenceRecords.ChangeSetInsert insert = new PersistenceRecords.ChangeSetInsert(
                    request.threadId(), request.turnId(), request.workspaceId(), changeSets.write(committed),
                    artifactId, request.sha256(), request.byteLength(), request.unifiedDiff(), clock.instant().toString());
            if (artifactId != null && mapper.history().insertChangeSetArtifact(insert) != 1) throw conflict();
            if (mapper.history().insertChangeSet(insert) != 1) throw conflict();
            return committed;
        });
    }

    /** Tool artifact 使用 code point 游标，因此任意 offset 都不会落在 surrogate pair 中间。 */
    @Override
    public Optional<TextArtifactPage> readToolArtifact(String threadId, String turnId, String callId,
                                                       String artifactId, int offsetCharacters,
                                                       int limitCharacters) {
        if (offsetCharacters < 0 || limitCharacters < 1 || limitCharacters > 65_536) {
            throw new IllegalArgumentException("invalid artifact page");
        }
        return agentStore.readToolArtifact(threadId, turnId, callId, artifactId).map(row -> {
            String content = row.content();
            int total = Math.toIntExact(row.characterLength());
            int startPoint = Math.min(offsetCharacters, total);
            int endPoint = Math.min(total, startPoint + limitCharacters);
            int start = content.offsetByCodePoints(0, startPoint);
            int end = content.offsetByCodePoints(0, endPoint);
            return new TextArtifactPage(row.artifactId(), startPoint,
                    endPoint < total ? endPoint : null, total, endPoint < total, content.substring(start, end));
        });
    }

    /** Diff reader 验证 byte offset 位于 UTF-8 字符边界，并回退页尾以免切断 code point。 */
    @Override
    public Optional<BinaryTextArtifactPage> readChangeSetArtifact(String threadId, String turnId,
                                                                  String artifactId, int offsetBytes,
                                                                  int limitBytes) {
        if (offsetBytes < 0 || limitBytes < 1 || limitBytes > 65_536) {
            throw new IllegalArgumentException("invalid artifact page");
        }
        return transactions.required(mapper -> Optional.ofNullable(mapper.history().selectChangeSetArtifact(
                        new PersistenceRecords.ChangeSetArtifactKey(threadId, turnId, artifactId)))
                .map(row -> bytePage(row, offsetBytes, limitBytes)));
    }

    /** 明细与汇总必须一致；未知行数保持缺失且不参与总和。 */
    private static void validateStats(io.github.kongweiguang.ja.conversation.domain.TurnChangeSet value) {
        long additions = value.files().stream().mapToLong(file -> file.additions() == null ? 0 : file.additions()).sum();
        long deletions = value.files().stream().mapToLong(file -> file.deletions() == null ? 0 : file.deletions()).sum();
        long binary = value.files().stream().filter(
                io.github.kongweiguang.ja.conversation.domain.TurnChangeSet.FileChange::binary).count();
        if (value.stats().files() != value.files().size() || value.stats().additions() != additions
            || value.stats().deletions() != deletions || value.stats().binaryFiles() != binary
            || value.stats().truncated() != value.files().stream().anyMatch(
                    io.github.kongweiguang.ja.conversation.domain.TurnChangeSet.FileChange::truncated)) {
            throw new IllegalArgumentException("change set stats mismatch");
        }
    }

    /** UTF-8 byte 页只接受字符起点；页尾回退到完整字符并以 null 表示 EOF。 */
    private static BinaryTextArtifactPage bytePage(PersistenceRecords.ChangeSetArtifactRow row,
                                                   int offsetBytes, int limitBytes) {
        byte[] bytes = row.content().getBytes(StandardCharsets.UTF_8);
        if (bytes.length != row.byteLength() || offsetBytes > bytes.length
            || offsetBytes < bytes.length && (bytes[offsetBytes] & 0xC0) == 0x80) {
            throw new IllegalArgumentException("artifact byte offset is invalid");
        }
        int end = Math.min(bytes.length, offsetBytes + limitBytes);
        while (end > offsetBytes && end < bytes.length && (bytes[end] & 0xC0) == 0x80) end--;
        String content = new String(bytes, offsetBytes, end - offsetBytes, StandardCharsets.UTF_8);
        return new BinaryTextArtifactPage(row.artifactId(), offsetBytes, end < bytes.length ? end : null,
                bytes.length, end < bytes.length, content);
    }

    /**
     * 生命周期操作先检查 active Turn，再执行一次 CAS，不把竞争失败解释为成功。
     */
    private void lifecycle(String threadId, long expectedRevision, boolean delete) {
        transactions.required(mapper -> {
            if (mapper.history().countActiveTurns(threadId) != 0) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "active thread cannot change lifecycle");
            }
            if (mapper.history().updateThreadLifecycle(new PersistenceRecords.ThreadLifecycle(
                    threadId, expectedRevision, clock.instant().toString(), delete)) != 1) throw conflict();
            return null;
        });
    }

    /**
     * snapshot 只投影公开文本、Tool 和审批，文件/Git 状态由 Rust Review 实时计算。
     */
    private ThreadSnapshot.Item snapshotItem(PersistenceRecords.SnapshotItemRow row) {
        String kind = requiredText(row.itemKind(), "item_kind");
        Instant createdAt = Instant.parse(requiredText(row.createdAt(), "created_at"));
        String itemId = snapshotItemId(kind, requiredText(row.itemId(), "item_id"));
        return switch (kind) {
            case "message" -> textItem(row, createdAt, itemId);
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
            case "attachment" -> new ThreadSnapshot.AttachmentItem(itemId, createdAt,
                    requiredText(row.attachmentId(), "attachment_id"), requiredText(row.turnId(), "turn_id"),
                    requiredText(row.displayName(), "display_name"), requiredNumber(row.sizeBytes(), "size_bytes"),
                    requiredText(row.mediaKind(), "media_kind"), requiredText(row.mediaType(), "media_type"),
                    requiredText(row.attachmentState(), "attachment_state"));
            default -> throw new StorageException(StorageException.Code.INVALID_STATE,
                    "unknown snapshot item kind");
        };
    }

    /**
     * message 中多个 text blocks 保序拼接；Tool blocks 由 tools 表单独投影。
     */
    private ThreadSnapshot.TextItem textItem(PersistenceRecords.SnapshotItemRow row, Instant createdAt,
                                             String itemId) {
        ThreadSnapshot.TextKind kind;
        try {
            kind = ThreadSnapshot.TextKind.valueOf(requiredText(row.messageKind(), "message_kind"));
        } catch (IllegalArgumentException invalid) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid timeline message kind");
        }
        return new ThreadSnapshot.TextItem(itemId, createdAt, requiredText(row.turnId(), "turn_id"),
                kind, requiredValue(row.publicText(), "public_text"),
                row.modelRound() == null ? null : Math.toIntExact(row.modelRound()));
    }

    /**
     * 将不可变 Usage 行恢复为领域事实；任何越界或损坏值都关闭整个快照，避免显示伪造百分比。
     */
    private ThreadSnapshot.ContextUsage contextUsage(PersistenceRecords.ContextUsageRow row) {
        try {
            return new ThreadSnapshot.ContextUsage(requiredText(row.turnId(), "turn_id"),
                    Math.toIntExact(row.modelRound()), row.inputTokens(), row.outputTokens(), row.totalTokens(),
                    Instant.parse(requiredText(row.occurredAt(), "occurred_at")));
        } catch (ArithmeticException | IllegalArgumentException failure) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid context usage");
        }
    }

    /**
     * 将各表原生主键稳定投影为固定长度 JA-RPC ItemId。哈希同时纳入条目种类，避免不同表
     * 复用同一原生主键时碰撞；SQL 仍使用原始主键排序和编码游标，因此该公开身份不会改变
     * 既有 keyset 分页语义，也不要求迁移用户数据库。
     */
    private static String snapshotItemId(String sourceKind, String sourceId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceKind.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            byte[] hash = digest.digest(sourceId.getBytes(StandardCharsets.UTF_8));
            return "item_" + java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 返回 UTF-8 内容 SHA-256，用于验证 Rust 交付的冻结 diff 没有跨 IPC 漂移。 */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 将 Workspace 行转换为领域对象，并在边界处校验必需列。
     */
    private static Workspace workspace(PersistenceRecords.WorkspaceRow row) {
        return new Workspace(requiredText(row.workspaceId(), "workspace_id"),
                Path.of(requiredText(row.rootPath(), "root_path")), requiredText(row.displayName(), "display_name"),
                Workspace.Trust.valueOf(requiredText(row.trust(), "trust").toUpperCase(Locale.ROOT)), row.revision());
    }

    /**
     * 将删除标记排除后的 Thread 行投影为稳定列表摘要。
     */
    private static ThreadSummary thread(PersistenceRecords.ThreadRow row) {
        ThreadSummary.Status status = row.archivedAt() == null
                ? ThreadSummary.Status.ACTIVE : ThreadSummary.Status.ARCHIVED;
        return new ThreadSummary(requiredText(row.threadId(), "thread_id"),
                requiredText(row.workspaceId(), "workspace_id"), requiredText(row.title(), "title"),
                PersistenceRowProjections.threadPreferences(row), status, row.revision(),
                Instant.parse(requiredText(row.createdAt(), "created_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")));
    }

    /**
     * Domain/Wire 使用面向产品的 AUTO/MANUAL，SQLite V2 schema 使用稳定事实名
     * AUTOMATIC/USER；在唯一 persistence adapter 显式映射，避免枚举 name 泄漏成存储契约。
     */
    private static String titleSourceStorage(ThreadPreferences.TitleSource source) {
        return switch (Objects.requireNonNull(source, "source")) {
            case PLACEHOLDER -> "PLACEHOLDER";
            case AUTO -> "AUTOMATIC";
            case MANUAL -> "USER";
        };
    }

    /** 历史 Turn 投影不读取消息页 cursor，并拒绝任何缺失的运行快照列。 */
    private ThreadSnapshot.Turn snapshotTurn(PersistenceRecords.TurnRow row) {
        TurnRuntimeSnapshot runtime = PersistenceRowProjections.turnRuntime(row);
        return new ThreadSnapshot.Turn(requiredText(row.turnId(), "turn_id"),
                requiredText(row.state(), "state").toLowerCase(Locale.ROOT), runtime,
                Instant.parse(requiredText(row.requestedAt(), "requested_at")),
                Instant.parse(requiredText(row.updatedAt(), "updated_at")),
                row.completedAt() == null ? null : Instant.parse(row.completedAt()), row.errorCode(),
                row.changeSetJson() == null ? null : changeSets.read(row.changeSetJson()));
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
    private static CursorPage<ThreadSummary> threadPage(List<PersistenceRecords.ThreadRow> rows, int limit) {
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
     * 将数据库过量读取限制在协议允许的固定上界内。
     */
    private static void checkLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE) throw new IllegalArgumentException("invalid history page limit");
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
        if (cursor == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 2 || parts[1].isBlank()) throw new IllegalArgumentException();
            Instant.parse(parts[0]);
            return new Cursor(parts[0], parts[1]);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("invalid history cursor", failure);
        }
    }

    /**
     * 保存已经严格校验的 keyset 分页位置，禁止携带旧 offset 语义。
     */
    private record Cursor(String time, String id) {
    }

    /**
     * 将 revision 竞争统一映射为可识别的 CAS 冲突。
     */
    private static StorageException conflict() {
        return new StorageException(
                StorageException.Code.CAS_CONFLICT, "history revision is stale");
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

    /** 必需标识与枚举文本同时拒绝 NULL 和空白，损坏行不能进入领域投影。 */
    private static String requiredText(String value, String column) {
        if (value == null || value.isBlank()) throw corrupted(column);
        return value;
    }

    /**
     * insert CAS 失败保持在存储错误分类内，不泄露 SQL 细节。
     */
    private static void requireInserted(int changed, String message) {
        if (changed != 1) throw new StorageException(StorageException.Code.CAS_CONFLICT, message);
    }

    /** 必需但允许空字符串的正文列只拒绝 SQL NULL，避免改变空 Tool 结果语义。 */
    private static String requiredValue(String value, String column) {
        if (value == null) throw corrupted(column);
        return value;
    }

    /** 必需整数使用引用类型承接 JDBC NULL，再在投影边界显式失败而不是默认为零。 */
    private static long requiredNumber(Long value, String column) {
        if (value == null) throw corrupted(column);
        return value;
    }

    /** 将缺失必需列统一归类为损坏数据，不向调用方泄漏 SQL 与 Mapper 细节。 */
    private static StorageException corrupted(String column) {
        return new StorageException(StorageException.Code.INVALID_STATE,
                "persistence row is missing required column " + column);
    }
}
