// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.infrastructure.persistence.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.conversation.domain.ThreadSnapshot;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestProfile;
import io.github.kongweiguang.ja.conversation.domain.ProviderRequestUsage;
import io.github.kongweiguang.ja.conversation.domain.InputQueue;
import io.github.kongweiguang.ja.conversation.domain.ThreadPreferences;
import io.github.kongweiguang.ja.conversation.domain.ThreadSummary;
import io.github.kongweiguang.ja.conversation.domain.ThreadDiscovery;
import io.github.kongweiguang.ja.conversation.domain.TurnSummary;
import io.github.kongweiguang.ja.conversation.domain.turn.TurnState;
import io.github.kongweiguang.ja.conversation.port.in.ThreadUseCase;
import io.github.kongweiguang.ja.conversation.port.out.ConversationRepository;
import io.github.kongweiguang.ja.foundation.error.StorageException;
import io.github.kongweiguang.ja.foundation.pagination.CursorPage;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceRecords;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceMappers;
import io.github.kongweiguang.ja.infrastructure.persistence.mapper.PersistenceCodec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workspace 出站仓储与 Thread 入站端口的单库实现；分页和快照不依赖进程级活动状态。
 */
public final class MybatisHistoryService implements WorkspaceRepository, ThreadUseCase {
    private static final int MAX_PAGE = 500;
    // 与首版 schema 和 TurnChangeTracker 的冻结上限共同构成持久 artifact 的硬边界。
    private static final int MAX_CHANGE_SET_ARTIFACT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHANGE_SET_FILES = 256;
    private static final Pattern HUNK_HEADER = Pattern.compile(
            "^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(?: .*)?$");
    private final MybatisUnitOfWork transactions;
    private final MybatisConversationRepository agentStore;
    private final ToolPresentationCodec presentations;
    private final TurnChangeSetCodec changeSets;
    private final PersistenceCodec codec;
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
        codec = new PersistenceCodec(objectMapper);
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
        codec = new PersistenceCodec(objectMapper);
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
     * Thread 创建完全使用显式 workspace 与完整偏好输入，不读取 active 状态。
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
        ThreadCursor key = decodeThreadCursor(cursor);
        return transactions.required(mapper -> activeThreadPage(mapper.history().selectThreadPage(
                new PersistenceRecords.ThreadPage(
                        workspaceId, key == null ? null : key.pinned(),
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
        return transactions.required(mapper -> searchThreadPage(mapper.history().searchThreadPage(
                new PersistenceRecords.ThreadSearch(workspaceId, normalized,
                        key == null ? null : key.time(), key == null ? null : key.id(), limit + 1)), limit));
    }

    /**
     * 混合页面按提交时间、语义和 Tool ordinal 排序，随机 identity 只用于最终去重；
     * Mapper 从游标指向的持久条目还原完整排序键，保证正文先于同批工具且跨页不丢不重。
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
            List<PersistenceRecords.TurnRow> turnRows = mapper.agent().selectTurns(threadId);
            List<ThreadSnapshot.Turn> turns = turnRows.stream()
                    .map(this::snapshotTurn).toList();
            List<ThreadSnapshot.Item> items = rows.stream().map(this::snapshotItem).toList();
            PersistenceRecords.ContextUsageRow usageRow = mapper.history().selectLatestContextUsage(threadId);
            ThreadSnapshot.ContextUsage contextUsage = usageRow == null ? null
                    : contextUsage(usageRow, mapper.checkpoint().selectCheckpoint(threadId));
            PersistenceRecords.TurnRow queueTurn = turnRows.stream()
                    .filter(turn -> !io.github.kongweiguang.ja.conversation.domain.turn.TurnState
                            .valueOf(requiredText(turn.state(), "state")).terminal())
                    .findFirst().orElse(null);
            InputQueue inputQueue = queueTurn == null ? null : inputQueue(mapper, queueTurn);
            return Optional.of(new ThreadSnapshot(thread(row), turns, items, contextUsage, inputQueue, next));
        });
    }

    /** thread/read 从同一 SQLite 快照恢复首个非终态 Turn 的完整队列，事件丢失也不会丢状态。 */
    private InputQueue inputQueue(PersistenceMappers mapper, PersistenceRecords.TurnRow turn) {
        List<InputQueue.QueuedInput> queued = mapper.agent().selectPendingInputs(turn.turnId()).stream()
                .map(row -> new InputQueue.QueuedInput(requiredText(row.inputId(), "input_id"),
                        requiredText(row.turnId(), "turn_id"), codec.readUserContent(
                                requiredText(row.contentJson(), "content_json")),
                        InputQueue.Kind.valueOf(requiredText(row.kind(), "kind")),
                        codec.readAttachmentSummaries(requiredText(row.attachmentsJson(), "attachments_json")),
                        InputQueue.Status.valueOf(requiredText(row.validationStatus(), "validation_status")),
                        row.issueErrorCode() == null ? null : new InputQueue.Issue(
                                row.issueErrorCode(), requiredText(row.issueMessage(), "issue_message"),
                                Objects.requireNonNull(row.issueRetryable(), "issue_retryable")),
                        row.inputRevision(),
                        Instant.parse(requiredText(row.createdAt(), "created_at"))))
                .toList();
        return new InputQueue(turn.turnId(), turn.inputQueueRevision(), turn.acceptingInputs(), queued);
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
                            preferences.collaborationMode().name(),
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
     * 置顶时间由 App Server 时钟唯一分配；取消置顶写入 null，二者都经过 active+revision CAS。
     */
    @Override
    public ThreadSummary pinThread(String threadId, boolean pinned, long expectedThreadRevision) {
        return transactions.required(mapper -> {
            String occurredAt = clock.instant().toString();
            int changed = mapper.history().compareAndSetThreadPin(new PersistenceRecords.ThreadPinCas(
                    threadId, pinned ? occurredAt : null, expectedThreadRevision, occurredAt));
            if (changed != 1) throw conflict();
            return thread(mapper.history().selectThread(threadId));
        });
    }

    /**
     * 已读边界只推进到事务开始时的最新成功/失败 Turn；无 Turn、实时态、取消态或已读都不改 revision。
     */
    @Override
    public ThreadSummary markThreadSeen(String threadId, long expectedThreadRevision) {
        Objects.requireNonNull(threadId, "threadId");
        if (expectedThreadRevision < 0) throw new IllegalArgumentException("invalid thread revision");
        return transactions.required(mapper -> {
            int changed = mapper.history().compareAndSetThreadSeen(new PersistenceRecords.ThreadSeenCas(
                    threadId, expectedThreadRevision, clock.instant().toString()));
            PersistenceRecords.ThreadRow row = mapper.history().selectThread(threadId);
            if (changed == 1) return thread(row);
            if (row == null) throw conflict();
            TurnState state = row.latestTurnStatus() == null ? null
                    : TurnState.valueOf(requiredText(row.latestTurnStatus(), "latest_turn_status"));
            if (state == null || state != TurnState.COMPLETED && state != TurnState.FAILED
                || row.latestTurnSeen()) {
                return thread(row);
            }
            throw conflict();
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
        return transactions.required(mapper -> {
            String occurredAt = clock.instant().toString();
            String source = (placeholderOnly
                    ? ThreadPreferences.TitleSource.AUTO : ThreadPreferences.TitleSource.MANUAL).name();
            int changed = mapper.history().compareAndSetThreadTitle(new PersistenceRecords.ThreadTitleCas(
                    threadId, title, source, expectedRevision, occurredAt, placeholderOnly));
            if (changed != 1) return Optional.empty();
            mapper.tasks().advanceTitleProjection(threadId, occurredAt);
            return Optional.of(thread(mapper.history().selectThread(threadId)));
        });
    }

    /**
     * archive 只允许 idle Thread，revision CAS 与生命周期标记同事务。
     */
    @Override
    public ThreadSummary archiveThread(String threadId, long expectedThreadRevision) {
        return lifecycle(threadId, expectedThreadRevision, false);
    }

    /** 已归档 Thread 只能经显式 restore CAS 回到 active，且不会恢复旧置顶顺序。 */
    @Override
    public ThreadSummary restoreThread(String threadId, long expectedThreadRevision) {
        return transactions.required(mapper -> {
            if (mapper.history().restoreThread(new PersistenceRecords.ThreadRestore(
                    threadId, expectedThreadRevision, clock.instant().toString())) != 1) throw conflict();
            return thread(mapper.history().selectThread(threadId));
        });
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
                        row.cancelExpectedThreadRevision() != null)));
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
        if (row == null) return Optional.empty();
        return extractChangeSetFile(row, filePath);
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
            if (!before.text().startsWith("--- ")) throw malformedChangeSet();
            cursor = before.next();
            ParsedLine after = line(bytes, cursor);
            if (!after.text().startsWith("+++ ")) throw malformedChangeSet();
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
                        if (!previousBody) throw malformedChangeSet();
                        previousBody = false;
                        cursor = body.next();
                        continue;
                    }
                    if (body.text().isEmpty()) throw malformedChangeSet();
                    char prefix = body.text().charAt(0);
                    if (prefix == ' ') {
                        beforeLines++;
                        afterLines++;
                    } else if (prefix == '-') beforeLines++;
                    else if (prefix == '+') afterLines++;
                    else throw malformedChangeSet();
                    if (beforeLines > counts.before() || afterLines > counts.after()) throw malformedChangeSet();
                    previousBody = true;
                    cursor = body.next();
                }
                if (cursor < bytes.length) {
                    ParsedLine marker = line(bytes, cursor);
                    if ("\\ No newline at end of file".equals(marker.text())) cursor = marker.next();
                }
                if (cursor < bytes.length && !startsWith(bytes, cursor, "@@ ")
                        && !startsWith(bytes, cursor, "--- ")) throw malformedChangeSet();
            }
            if (!sawHunk || files.putIfAbsent(filePath, new ByteRange(fileStart, cursor)) != null) {
                throw malformedChangeSet();
            }
            if (files.size() > MAX_CHANGE_SET_FILES) throw malformedChangeSet();
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
        if (range == null) return Optional.empty();
        byte[] selected = java.util.Arrays.copyOfRange(indexed.bytes(), range.start(), range.end());
        return Optional.of(new ChangeSetArtifactFile(indexed.artifactId(), filePath, selected.length,
                sha256(selected), Base64.getEncoder().encodeToString(selected)));
    }

    /** header 的 before/after 只能表达同一路径或单侧 `/dev/null`，其它组合均视为损坏。 */
    private static String headerPath(String beforeHeader, String afterHeader) {
        String before = beforeHeader.substring(4);
        String after = afterHeader.substring(4);
        if ("/dev/null".equals(before) && after.startsWith("b/")) return after.substring(2);
        if ("/dev/null".equals(after) && before.startsWith("a/")) return before.substring(2);
        if (before.startsWith("a/") && after.startsWith("b/")
                && before.substring(2).equals(after.substring(2))) return before.substring(2);
        throw malformedChangeSet();
    }

    /** hunk 计数是文件边界的权威依据；超出 int 或不完整 header 均失败关闭。 */
    private static HunkCounts hunkCounts(String header) {
        Matcher matcher = HUNK_HEADER.matcher(header);
        if (!matcher.matches()) throw malformedChangeSet();
        try {
            return new HunkCounts(matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)),
                    matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4)));
        } catch (NumberFormatException invalid) {
            throw malformedChangeSet();
        }
    }

    /** 字节扫描保留精确换行范围；所有持久 artifact 都必须以完整 LF 行结束。 */
    private static ParsedLine line(byte[] bytes, int start) {
        if (start < 0 || start >= bytes.length) throw malformedChangeSet();
        int end = start;
        while (end < bytes.length && bytes[end] != '\n') end++;
        if (end == bytes.length) throw malformedChangeSet();
        return new ParsedLine(new String(bytes, start, end - start, StandardCharsets.UTF_8), end + 1);
    }

    /** ASCII 结构前缀直接在 UTF-8 字节上比较，避免为边界探测重复解码正文。 */
    private static boolean startsWith(byte[] bytes, int offset, String prefix) {
        if (offset < 0 || offset + prefix.length() > bytes.length) return false;
        for (int index = 0; index < prefix.length(); index++) {
            if (bytes[offset + index] != (byte) prefix.charAt(index)) return false;
        }
        return true;
    }

    /** artifact 路径只是内部 key，但仍拒绝绝对、反斜杠、控制字符和父目录形状。 */
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

    /** 损坏 artifact 不允许以部分文件索引继续服务。 */
    private static IllegalArgumentException malformedChangeSet() {
        return new IllegalArgumentException("change set artifact is malformed");
    }

    /** 超时或关闭通过线程中断协作停止 SQL 后的 CPU 解析，避免迟到任务继续占用读取许可。 */
    private static void requireChangeSetReadActive() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("change set read interrupted");
        }
    }

    /** 每次物化正文都复算摘要，不让持久损坏或陈旧应用缓存绕过完整性门。 */
    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 文件范围使用 aggregate UTF-8 字节绝对偏移，选中后复制成独立返回正文。 */
    private record ByteRange(int start, int end) { }
    /** 单行同时保留已解码文本和下一行字节起点，避免字符数被误作 byte offset。 */
    private record ParsedLine(String text, int next) { }
    /** hunk 只需要两侧逻辑行计数来确认下一文件 header 的合法位置。 */
    private record HunkCounts(int before, int after) { }
    /** 单次请求的临时解析结果不跨请求保存，方法返回后即可回收聚合正文与索引。 */
    private record IndexedChangeSet(String artifactId, byte[] bytes, Map<String, ByteRange> files) { }

    /**
     * 生命周期操作先检查 active Turn，再执行一次 CAS，不把竞争失败解释为成功。
     */
    private ThreadSummary lifecycle(String threadId, long expectedRevision, boolean delete) {
        return transactions.required(mapper -> {
            if (mapper.history().countActiveTurns(threadId) != 0) {
                throw new StorageException(StorageException.Code.INVALID_STATE,
                        "active thread cannot change lifecycle");
            }
            if (mapper.history().updateThreadLifecycle(new PersistenceRecords.ThreadLifecycle(
                    threadId, expectedRevision, clock.instant().toString(), delete)) != 1) throw conflict();
            return delete ? null : thread(mapper.history().selectThread(threadId));
        });
    }

    /**
     * snapshot 只投影公开文本、Tool 和审批，文件/Git 状态由 Rust Review 实时计算。
     */
    private ThreadSnapshot.Item snapshotItem(PersistenceRecords.SnapshotItemRow row) {
        String kind = requiredText(row.itemKind(), "item_kind");
        Instant createdAt = Instant.parse(requiredText(row.createdAt(), "created_at"));
        String persistedItemId = requiredText(row.itemId(), "item_id");
        String itemId = "message".equals(kind) && "THREAD_MESSAGE".equals(row.messageKind())
                ? persistedItemId : snapshotItemId(kind, persistedItemId);
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
     * message 中多个 text blocks 保序拼接；Tool blocks 由 tools 表单独投影。
     */
    private ThreadSnapshot.Item messageItem(PersistenceRecords.SnapshotItemRow row, Instant createdAt,
                                            String itemId) {
        String messageKind = requiredText(row.messageKind(), "message_kind");
        if ("USER_INPUT".equals(messageKind)) {
            return new ThreadSnapshot.UserInputItem(itemId, createdAt,
                    requiredText(row.turnId(), "turn_id"), codec.readUserContent(
                            requiredText(row.blocksJson(), "blocks_json")),
                    codec.readAttachmentSummaries(requiredText(row.attachmentsJson(), "attachments_json")));
        }
        if ("THREAD_MESSAGE".equals(messageKind)) {
            return new ThreadSnapshot.ThreadMessageItem(itemId, createdAt,
                    requiredText(row.turnId(), "turn_id"), requiredText(row.sourceThreadId(), "source_thread_id"),
                    requiredText(row.sourceTitle(), "source_title"), requiredText(row.publicText(), "public_text"));
        }
        ThreadSnapshot.TextKind kind;
        try {
            kind = ThreadSnapshot.TextKind.valueOf(messageKind);
        } catch (IllegalArgumentException invalid) {
            throw new StorageException(StorageException.Code.INVALID_STATE, "invalid timeline message kind");
        }
        return new ThreadSnapshot.TextItem(itemId, createdAt, requiredText(row.turnId(), "turn_id"),
                kind, requiredValue(row.publicText(), "public_text"),
                row.modelRound() == null ? null : Math.toIntExact(row.modelRound()));
    }

    /**
     * 从同一数据库快照恢复计量；新 checkpoint 使旧输入占用失效，但只投影 UNKNOWN，绝不改写计费账本。
     */
    private ThreadSnapshot.ContextUsage contextUsage(PersistenceRecords.ContextUsageRow row,
                                                     PersistenceRecords.CheckpointRow checkpoint) {
        try {
            ProviderRequestUsage.Certainty certainty = ProviderRequestUsage.Certainty.valueOf(
                    requiredText(row.certainty(), "certainty"));
            Instant measuredAt = Instant.parse(requiredText(row.occurredAt(), "occurred_at"));
            // 相同时间精度无法证明 usage 晚于 checkpoint，保守失效，等待下一次可靠计量恢复占用。
            if (checkpoint != null && !Instant.parse(requiredText(checkpoint.createdAt(), "created_at"))
                    .isBefore(measuredAt)) {
                certainty = ProviderRequestUsage.Certainty.UNKNOWN;
            }
            ProviderRequestProfile profile = codec.readProviderRequestProfile(
                    requiredText(row.profileJson(), "profile_json"));
            io.github.kongweiguang.ja.conversation.domain.model.ModelUsage usage =
                    certainty == ProviderRequestUsage.Certainty.UNKNOWN ? null
                            : new io.github.kongweiguang.ja.conversation.domain.model.ModelUsage(
                                    row.inputTokens(), row.outputTokens(), row.totalTokens());
            ProviderRequestUsage request = new ProviderRequestUsage(
                    requiredText(row.requestId(), "request_id"), row.requestOrdinal(),
                    Math.toIntExact(row.modelRound()), ProviderRequestUsage.Purpose.valueOf(
                            requiredText(row.purpose(), "purpose")), certainty, profile, usage);
            return new ThreadSnapshot.ContextUsage(requiredText(row.turnId(), "turn_id"), request,
                    measuredAt);
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
        return PersistenceRowProjections.threadSummary(row);
    }

    /** 历史 Turn 只投影 Operation 生命周期；请求级模型事实由 Context Usage 提供。 */
    private ThreadSnapshot.Turn snapshotTurn(PersistenceRecords.TurnRow row) {
        return new ThreadSnapshot.Turn(requiredText(row.turnId(), "turn_id"),
                requiredText(row.state(), "state").toLowerCase(Locale.ROOT),
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

    /** 搜索保留归档项并继续使用纯更新时间 keyset，不让置顶改变恢复入口的结果顺序。 */
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
                ThreadDiscovery.Status.valueOf(requiredText(row.status(), "status")));
    }

    /** active Thread 游标冻结完整排序元组，跨置顶/普通分组翻页不会重复或遗漏。 */
    private static String encodeThreadCursor(int pinned, String sortTime, String updatedAt, String id) {
        String value = pinned + "\n" + sortTime + "\n" + updatedAt + "\n" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 严格解析完整排序元组，拒绝缺失字段或越界分组值，避免跨页重复和遗漏。 */
    private static ThreadCursor decodeThreadCursor(String cursor) {
        if (cursor == null) return null;
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                    .split("\n", -1);
            if (parts.length != 4 || parts[3].isBlank()) {
                throw new IllegalArgumentException();
            }
            int pinned = Integer.parseInt(parts[0]);
            if (pinned < 0 || pinned > 1) throw new IllegalArgumentException();
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

    /** 保存 active Thread 的四段稳定排序边界。 */
    private record ThreadCursor(int pinned, String sortTime, String updatedAt, String id) {
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
