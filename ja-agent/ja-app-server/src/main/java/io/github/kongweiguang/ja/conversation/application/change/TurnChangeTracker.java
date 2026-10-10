// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.change;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.conversation.domain.model.TurnChangeSet;
import io.github.kongweiguang.ja.foundation.filesystem.PathIdentities;
import io.github.kongweiguang.ja.foundation.filesystem.Utf8Fingerprint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单 Turn 的 Java 权威修改 tracker：只吸收已提交的精确收据，终态冻结时才计算一次 Diff。
 */
public final class TurnChangeTracker {
    public static final int MAX_FILES = 256;
    public static final int MAX_LOGICAL_LINES = 20_000;
    public static final long MAX_TEXT_BYTES = 16L * 1024 * 1024;
    public static final int MAX_DIFF_BYTES = 2 * 1024 * 1024;
    private static final Duration DIFF_TIMEOUT = Duration.ofMillis(100);
    private static final ThreadPoolExecutor DIFF_EXECUTOR = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), daemonFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    private final Object monitor = new Object();
    private final Path workspaceRoot;
    private final String workspaceId;
    private final FileAttributeReader fileAttributeReader;
    private final Map<String, TrackedFile> tracked = new LinkedHashMap<>();
    private final EnumSet<TurnChangeSet.IncompleteReason> reasons;
    private boolean frozen;

    /**
     * 测试通过窄文件属性端口验证 IO 失败，同时仍要求提供真实 Workspace 身份。
     */
    TurnChangeTracker(Path workspaceRoot, String workspaceId, FileAttributeReader fileAttributeReader) {
        this(workspaceRoot, workspaceId, EnumSet.noneOf(TurnChangeSet.IncompleteReason.class), fileAttributeReader);
    }

    /**
     * 固定工作区物理根和持久身份；不扫描文件树，也不启动 watcher。
     */
    private TurnChangeTracker(Path workspaceRoot, String workspaceId,
                              EnumSet<TurnChangeSet.IncompleteReason> initialReasons,
                              FileAttributeReader fileAttributeReader) {
        this.workspaceRoot = PathIdentities.normalized(workspaceRoot);
        this.workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
        if (!this.workspaceId.startsWith("ws_")) {
            throw new IllegalArgumentException("invalid workspaceId");
        }
        this.fileAttributeReader = Objects.requireNonNull(fileAttributeReader, "fileAttributeReader");
        reasons = initialReasons.clone();
    }

    /**
     * 新 Turn 固定物理根和工作区身份，后续只接受该身份下持久化的收据。
     */
    public static TurnChangeTracker fresh(Path workspaceRoot, String workspaceId) {
        return new TurnChangeTracker(workspaceRoot, workspaceId, EnumSet.noneOf(TurnChangeSet.IncompleteReason.class),
                TurnChangeTracker::readAttributesNoFollow);
    }

    /**
     * 从同一执行已提交的受限状态恢复累计器；不重新扫描 Workspace，也不把进程重启本身视为丢失事实。
     */
    public static TurnChangeTracker restore(Path workspaceRoot, String workspaceId, DurableSnapshot snapshot) {
        DurableSnapshot saved = Objects.requireNonNull(snapshot, "snapshot");
        TurnChangeTracker tracker = new TurnChangeTracker(workspaceRoot, workspaceId,
                saved.incompleteReasons().isEmpty()
                        ? EnumSet.noneOf(TurnChangeSet.IncompleteReason.class)
                        : EnumSet.copyOf(saved.incompleteReasons()), TurnChangeTracker::readAttributesNoFollow);
        if (!tracker.workspaceId.equals(saved.workspaceId())) {
            throw new IllegalArgumentException("change tracker state belongs to another workspace");
        }
        for (DurableFile file : saved.files()) {
            WorkspaceMutationFactCodec.validateRelativePath(file.path());
            Path target = tracker.workspaceRoot.resolve(file.path()).normalize();
            if (!target.startsWith(tracker.workspaceRoot) || target.equals(tracker.workspaceRoot)) {
                throw new IllegalArgumentException("persisted change path leaves the workspace");
            }
            TrackedFile value = new TrackedFile(file.path(), file.beforeExists(), file.beforeText(),
                    file.beforeSha256(), file.afterExists(), file.afterText(), file.afterSha256());
            if (value.netZero() || tracker.tracked.putIfAbsent(value.path(), value) != null) {
                throw new IllegalArgumentException("persisted change state contains a duplicate or net-zero file");
            }
        }
        if (overBudget(tracker.tracked)) {
            throw new IllegalArgumentException("persisted change state exceeds tracker limits");
        }
        return tracker;
    }

    /**
     * 正常路径使用 java-diff-utils；100ms 超时或有界队列饱和时退回内容精确的整文件替换。
     */
    private static DiffContent diff(TrackedFile file) {
        TextLines before = logicalLines(file.beforeText());
        TextLines after = logicalLines(file.afterText());
        String eofMarker = eofMarker(before.lines(), after.lines());
        List<String> encodedBefore = before.encoded(eofMarker);
        List<String> encodedAfter = after.encoded(eofMarker);
        long additions = encodedAfter.size();
        long deletions = encodedBefore.size();
        String relative = file.path();
        TurnChangeSet.FileStatus status = !file.beforeExists() ? TurnChangeSet.FileStatus.ADDED
                : !file.afterExists() ? TurnChangeSet.FileStatus.DELETED : TurnChangeSet.FileStatus.MODIFIED;
        Future<List<String>> future = null;
        try {
            future = DIFF_EXECUTOR.submit(() -> UnifiedDiffUtils.generateUnifiedDiff(
                    file.beforeExists() ? "a/" + relative : "/dev/null",
                    file.afterExists() ? "b/" + relative : "/dev/null", encodedBefore,
                    DiffUtils.diff(encodedBefore, encodedAfter, TurnChangeTracker::equalLinesUnlessInterrupted), 3));
            List<String> unified = future.get(DIFF_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            additions = unified.stream().filter(line -> line.startsWith("+") && !line.startsWith("+++")).count();
            deletions = unified.stream().filter(line -> line.startsWith("-") && !line.startsWith("---")).count();
            String text = renderUnified(unified, eofMarker);
            return diffContent(relative, status, additions, deletions, text);
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        } catch (TimeoutException failure) {
            cancelDiff(future);
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        } catch (InterruptedException interrupted) {
            cancelDiff(future);
            Thread.currentThread().interrupt();
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        } catch (ExecutionException failure) {
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        }
    }

    /**
     * Myers 比较回调逐行检查线程中断，使超时取消能够退出计算而非只取消 Future 状态。
     */
    private static boolean equalLinesUnlessInterrupted(String first, String second) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("diff computation interrupted");
        }
        return first.equals(second);
    }

    /**
     * 超时或调用线程取消时同时中断运行任务、移除仍排队的 Future，避免保留过期输入。
     */
    private static void cancelDiff(Future<?> future) {
        if (future == null) {
            return;
        }
        future.cancel(true);
        if (future instanceof Runnable task) {
            DIFF_EXECUTOR.remove(task);
        }
    }

    /**
     * 用不会与正文碰撞的内部后缀表达 EOF newline 身份，使 diff 算法把 `a` 与 `a\n` 视为真实修改；
     * 后缀只存在于计算期，最终统一转换为标准 `No newline` 标记。
     */
    private static String eofMarker(List<String> before, List<String> after) {
        int ordinal = 0;
        while (true) {
            String candidate = "\u0000JA_EOF_" + ordinal++;
            if (before.stream().noneMatch(line -> line.contains(candidate))
                && after.stream().noneMatch(line -> line.contains(candidate))) {
                    return candidate;
                }
        }
    }

    /**
     * 将 java-diff-utils 的内部 EOF 后缀替换为标准 unified diff 行，不把实现哨兵泄漏到 artifact。
     */
    private static String renderUnified(List<String> unified, String eofMarker) {
        StringBuilder rendered = new StringBuilder();
        for (String line : unified) {
            appendDiffLine(rendered, line, eofMarker);
        }
        return rendered.toString();
    }

    /**
     * EOF 哨兵必须紧随所属 +/-/context 行输出标记，保证应用补丁时仍能恢复精确字节。
     */
    private static void appendDiffLine(StringBuilder target, String line, String eofMarker) {
        if (line.endsWith(eofMarker)) {
            target.append(line, 0, line.length() - eofMarker.length()).append('\n')
                    .append("\\ No newline at end of file\n");
        } else {
            target.append(line).append('\n');
        }
    }

    /**
     * 单文件超过聚合上限时保留统计并显式 truncated，不把不完整正文混入 artifact。
     */
    private static DiffContent diffContent(String path, TurnChangeSet.FileStatus status,
                                           long additions, long deletions, String diff) {
        boolean truncated = diff.getBytes(StandardCharsets.UTF_8).length > MAX_DIFF_BYTES;
        TurnChangeSet.FileChange change = new TurnChangeSet.FileChange(path, status, additions, deletions,
                false, truncated);
        return new DiffContent(change, truncated ? "" : diff);
    }

    /**
     * fallback 仍生成标准 unified header/hunk，并完整保留所有逻辑行，不尝试启发式最小匹配。
     */
    private static String wholeFileDiff(TrackedFile file) {
        return wholeFileDiff(file.path(), file.beforeExists(), file.afterExists(),
                file.beforeText(), file.afterText());
    }

    /**
     * 整文件 renderer 与最小 diff 共用 EOF 模型；作为纯函数也让预算/超时分支能被确定性验证。
     */
    static String wholeFileDiff(String path, boolean beforeExists, boolean afterExists,
                                String beforeText, String afterText) {
        TextLines beforeTextLines = logicalLines(beforeText);
        TextLines afterTextLines = logicalLines(afterText);
        String eofMarker = eofMarker(beforeTextLines.lines(), afterTextLines.lines());
        List<String> before = beforeTextLines.encoded(eofMarker);
        List<String> after = afterTextLines.encoded(eofMarker);
        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(beforeExists ? "a/" + path : "/dev/null").append('\n');
        diff.append("+++ ").append(afterExists ? "b/" + path : "/dev/null").append('\n');
        diff.append("@@ -1,").append(before.size()).append(" +1,").append(after.size()).append(" @@\n");
        before.forEach(line -> appendDiffLine(diff, "-" + line, eofMarker));
        after.forEach(line -> appendDiffLine(diff, "+" + line, eofMarker));
        return diff.toString();
    }

    /**
     * 总文件、逻辑行及写前写后 UTF-8 正文三项预算在接受新状态前统一核对。
     */
    private static boolean overBudget(Map<String, TrackedFile> files) {
        if (files.size() > MAX_FILES) {
            return true;
        }
        long lines = 0;
        long bytes = 0;
        for (TrackedFile file : files.values()) {
            lines += logicalLineCount(file.beforeText()) + logicalLineCount(file.afterText());
            if (lines > MAX_LOGICAL_LINES) {
                return true;
            }
            long beforeBytes = Utf8Fingerprint.byteLength(file.beforeText());
            if (beforeBytes > MAX_TEXT_BYTES - bytes) {
                return true;
            }
            bytes += beforeBytes;
            long afterBytes = Utf8Fingerprint.byteLength(file.afterText());
            if (afterBytes > MAX_TEXT_BYTES - bytes) {
                return true;
            }
            bytes += afterBytes;
        }
        return false;
    }

    /**
     * 按 LF 语义计数而不创建 String 数组，超限的大型 mutation 在准入阶段直接降级为 partial。
     */
    private static long logicalLineCount(String value) {
        if (value.isEmpty()) {
            return 0L;
        }
        long separators = 0L;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '\n') {
                separators++;
            }
        }
        return value.endsWith("\n") ? separators : separators + 1L;
    }

    /**
     * 生产属性读取固定 NOFOLLOW_LINKS，防止检查阶段跟随 symlink 或 junction。
     */
    private static BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 只按 LF 切分并保留行尾 CR 与末尾空行，使 CRLF/LF 和文件末尾换行变化不会被归一化丢失。
     */
    private static TextLines logicalLines(String value) {
        if (value.isEmpty()) {
            return new TextLines(List.of(), false);
        }
        List<String> lines = new ArrayList<>(List.of(value.split("\\n", -1)));
        boolean terminated = value.endsWith("\n");
        if (terminated) {
            lines.removeLast();
        }
        return new TextLines(List.copyOf(lines), terminated);
    }

    /**
     * UUID 去连字符后恰为 32 个小写十六进制字符。
     */
    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 为全局 Diff executor 使用 daemon worker，避免独立关闭时阻塞 App Server 退出。
     */
    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "ja-turn-diff");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 固定 UTF-8 SHA-256，冻结 artifact 与 SQLite 校验使用同一摘要。
     */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 在未知 Tool 的 started 事实提交后永久追加原因；重复原因不会制造额外状态。
     */
    public void markIncomplete(TurnChangeSet.IncompleteReason reason) {
        synchronized (monitor) {
            requireMutable();
            reasons.add(Objects.requireNonNull(reason, "reason"));
        }
    }

    /**
     * 只吸收 Store 已提交的收据或观察失败，并与持久参与者复用同一闭集解码规则。
     */
    public void applyCommittedWorkspaceFact(String namespace, String type, int schemaVersion, JsonObject payload) {
        synchronized (monitor) {
            requireMutable();
            WorkspaceMutationFactCodec.DecodedFact decoded;
            try {
                decoded = WorkspaceMutationFactCodec.decode(namespace, type, schemaVersion,
                        Objects.requireNonNull(payload, "payload"));
            } catch (RuntimeException malformed) {
                reasons.add(TurnChangeSet.IncompleteReason.CAPTURE_FAILED);
                return;
            }
            switch (decoded) {
                case WorkspaceMutationFactCodec.MutationReceipt receipt -> applyReceipt(receipt);
                case WorkspaceMutationFactCodec.ObservationFailure failure -> applyObservationFailure(failure);
            }
        }
    }

    /**
     * 在 Store 已确认的收据上验证路径、修改链和累计投影预算。
     */
    private void applyReceipt(WorkspaceMutationFactCodec.MutationReceipt receipt) {
        if (!workspaceId.equals(receipt.workspaceId())) {
            reasons.add(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
            return;
        }
        Path target = workspaceRoot.resolve(receipt.relativePath()).normalize();
        if (!target.startsWith(workspaceRoot) || target.equals(workspaceRoot)) {
            reasons.add(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
            return;
        }
        Confinement confinement = receipt.confined() ? Confinement.accepted(receipt.relativePath())
                : confinedRelative(target);
        if (confinement.failure() != null) {
            reasons.add(confinement.failure());
            return;
        }
        String relative = confinement.relative();
        TrackedFile existing = tracked.get(relative);
        if (existing != null && (existing.afterExists != receipt.beforeExists()
                                 || !existing.afterSha256.equals(receipt.beforeSha256())
                                 || !existing.afterText.equals(receipt.beforeText()))) {
            reasons.add(TurnChangeSet.IncompleteReason.MUTATION_CHAIN_BROKEN);
            return;
        }
        TrackedFile candidate = existing == null
                ? new TrackedFile(relative, receipt.beforeExists(), receipt.beforeText(), receipt.beforeSha256(),
                receipt.afterExists(), receipt.afterText(), receipt.afterSha256())
                : existing.withPostimage(receipt.afterExists(), receipt.afterText(), receipt.afterSha256());
        Map<String, TrackedFile> proposed = new LinkedHashMap<>(tracked);
        if (candidate.netZero()) {
            proposed.remove(relative);
        }
        else {
            proposed.put(relative, candidate);
        }
        if (proposed.equals(tracked)) {
            return;
        }
        if (overBudget(proposed)) {
            reasons.add(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED);
            return;
        }
        tracked.clear();
        tracked.putAll(proposed);
    }

    /**
     * 观察失败只能降低完整性，跨 Workspace 收据不得冒充当前 Turn 的事实。
     */
    private void applyObservationFailure(WorkspaceMutationFactCodec.ObservationFailure failure) {
        if (!workspaceId.equals(failure.workspaceId())) {
            reasons.add(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
            return;
        }
        boolean outsideWorkspace = failure.reason()
                == io.github.kongweiguang.ja.conversation.application.gateway.JaTool.MutationObservationFailure.OUTSIDE_WORKSPACE;
        reasons.add(outsideWorkspace ? TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE
                : TurnChangeSet.IncompleteReason.CAPTURE_FAILED);
    }

    /**
     * 执行产生写入但 Tool 结果事务没有确认时只降级，不把收据当作已确认修改。
     */
    public void markCommitUnconfirmed() {
        markIncomplete(TurnChangeSet.IncompleteReason.COMMIT_UNCONFIRMED);
    }

    /**
     * 返回不含 Diff 和冻结副作用的有界累计状态，供中间件与工具结果事实同事务持久化。
     */
    public DurableSnapshot durableSnapshot() {
        synchronized (monitor) {
            List<DurableFile> files = tracked.values().stream().sorted(Comparator.comparing(TrackedFile::path))
                    .map(value -> new DurableFile(value.path(), value.beforeExists(), value.beforeText(),
                            value.beforeSha256(), value.afterExists(), value.afterText(), value.afterSha256()))
                    .toList();
            return new DurableSnapshot(workspaceId, reasons, files);
        }
    }

    /**
     * 唯一终态 owner 冻结当前净差异；文件 Diff 只在这里计算一次，运行期间不物化预览正文。
     */
    public Frozen freeze() {
        return freezeInternal(null);
    }

    /**
     * 以调用方冻结的执行身份生成确定 artifactId，恢复重试时同一变更载荷不会产生新引用。
     */
    public Frozen freeze(String stableIdentity) {
        String identity = ContractChecks.text(stableIdentity, "stableIdentity", 4_096, false);
        return freezeInternal(identity);
    }

    /**
     * 构造完整终态后才封闭 tracker，避免 Diff 或 ChangeSet 构造失败留下不可恢复的冻结状态。
     */
    private Frozen freezeInternal(String stableIdentity) {
        synchronized (monitor) {
            if (frozen) {
                throw new IllegalStateException("turn change tracker is already frozen");
            }
            FrozenDraft selected = snapshot();
            String aggregate = selected.aggregateDiff();
            String artifactId = aggregate.isEmpty() ? null : stableIdentity == null
                                                             ? "artifact_" + compactId()
                                                             : "artifact_" + sha256(
                                                                     stableIdentity + "\u0000" + sha256(aggregate));
            TurnChangeSet base = selected.changeSet();
            TurnChangeSet changeSet = new TurnChangeSet(base.state(), base.incompleteReasons(), base.files(),
                    base.stats(), artifactId);
            Frozen result = new Frozen(changeSet, aggregate.isEmpty() ? null : aggregate,
                    aggregate.isEmpty() ? null : sha256(aggregate),
                    aggregate.isEmpty() ? null : (long) aggregate.getBytes(StandardCharsets.UTF_8).length);
            frozen = true;
            return result;
        }
    }

    /**
     * 从已确认 pre/postimage 计算文件级和聚合 Diff；预算降级只改变完整性，不泄漏正文。
     */
    private FrozenDraft snapshot() {
        EnumSet<TurnChangeSet.IncompleteReason> snapshotReasons = reasons.clone();
        List<SnapshotFile> files = new ArrayList<>();
        int aggregateBytes = 0;
        for (TrackedFile file : tracked.values().stream().sorted(Comparator.comparing(TrackedFile::path)).toList()) {
            DiffContent content = diff(file);
            if (content.change().truncated()) {
                snapshotReasons.add(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED);
            }
            aggregateBytes = Math.addExact(aggregateBytes, content.diff().getBytes(StandardCharsets.UTF_8).length);
            if (aggregateBytes > MAX_DIFF_BYTES) {
                snapshotReasons.add(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED);
                content = new DiffContent(content.changeWithTruncation(), "");
            }
            files.add(new SnapshotFile(content.change(), content.diff()));
        }
        List<TurnChangeSet.FileChange> changes = files.stream().map(SnapshotFile::change).toList();
        long additions = changes.stream().mapToLong(TurnChangeSet.FileChange::additions).sum();
        long deletions = changes.stream().mapToLong(TurnChangeSet.FileChange::deletions).sum();
        boolean truncated = changes.stream().anyMatch(TurnChangeSet.FileChange::truncated);
        TurnChangeSet.State state = snapshotReasons.isEmpty()
                ? TurnChangeSet.State.COMPLETE : TurnChangeSet.State.PARTIAL;
        TurnChangeSet value = new TurnChangeSet(state, snapshotReasons, changes,
                new TurnChangeSet.Stats(changes.size(), additions, deletions, 0, truncated), null);
        String aggregate = files.stream().map(SnapshotFile::diff).filter(valueDiff -> !valueDiff.isEmpty())
                .reduce("", String::concat);
        return new FrozenDraft(value, aggregate);
    }

    /**
     * 路径必须处于物理 Workspace 内且任一已存在分段都不能是 symlink/junction/reparse point。
     */
    private Confinement confinedRelative(Path target) {
        Path normalized = PathIdentities.normalized(target);
        if (!normalized.startsWith(workspaceRoot)) {
            return Confinement.failed(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
        }
        Path cursor = workspaceRoot;
        try {
            if (Files.isSymbolicLink(cursor)) {
                return Confinement.failed(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
            }
            for (Path part : workspaceRoot.relativize(normalized)) {
                cursor = cursor.resolve(part);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                BasicFileAttributes attributes = fileAttributeReader.read(cursor);
                if (Files.isSymbolicLink(cursor) || attributes.isOther()) {
                    return Confinement.failed(TurnChangeSet.IncompleteReason.OUTSIDE_WORKSPACE);
                }
            }
            return Confinement.accepted(workspaceRoot.relativize(normalized).toString().replace('\\', '/'));
        } catch (IOException failure) {
            return Confinement.failed(TurnChangeSet.IncompleteReason.CAPTURE_FAILED);
        }
    }

    /**
     * 冻结后任何新 Tool 事实都表示调用顺序错误，禁止静默污染已提交 ChangeSet。
     */
    private void requireMutable() {
        if (frozen) {
            throw new IllegalStateException("turn change tracker is frozen");
        }
    }

    /**
     * 文件属性读取是唯一会抛出受检 IO 失败的 containment 副作用边界。
     */
    @FunctionalInterface
    interface FileAttributeReader {
        /**
         * 调用方必须按 NOFOLLOW 语义读取，异常由 tracker 映射为 capture_failed。
         */
        BasicFileAttributes read(Path path) throws IOException;
    }

    /**
     * 终态快照属于本 tracker 的 Ja 投影，不再经已无生产消费者的通用 ChangeTracker SPI。
     *
     * @param changeSet 变更集合
     * @param unifiedDiff 统一格式的差异文本
     * @param sha256 内容的 SHA-256 摘要
     * @param byteLength 数据长度，单位为字节
     */
    public record Frozen(TurnChangeSet changeSet,
                         String unifiedDiff,
                         String sha256,
                         Long byteLength) {
        /**
         * 保证摘要、Diff 与 artifact 引用同时存在或同时缺失，避免持久层接收不完整组合。
         */
        public Frozen {
            Objects.requireNonNull(changeSet, "changeSet");
            if (byteLength != null && byteLength < 0) {
                throw new IllegalArgumentException("invalid byteLength");
            }
            boolean hasDiff = unifiedDiff != null;
            if (hasDiff == (sha256 == null) || hasDiff == (byteLength == null)
                || hasDiff == (changeSet.artifactId() == null)) {
                throw new IllegalArgumentException("change artifact fields are inconsistent");
            }
        }
    }

    /**
     * 文本行与末尾换行身份分离，避免把 EOF newline 误建模成额外空逻辑行。
     *
     * @param lines 文本行列表
     * @param terminated 是否已终止
     */
    private record TextLines(List<String> lines,
                             boolean terminated) {
        /**
         * 仅对无末尾换行的最后一行追加计算期哨兵；空文件没有可标注行。
         */
        private List<String> encoded(String marker) {
            if (terminated || lines.isEmpty()) {
                return lines;
            }
            List<String> encoded = new ArrayList<>(lines);
            encoded.set(encoded.size() - 1, encoded.getLast() + marker);
            return List.copyOf(encoded);
        }
    }

    /**
     * 路径判定把失败原因与相对路径互斥建模，调用方只推进一次完整性 revision。
     *
     * @param relative 相对路径
     * @param failure 失败
     */
    private record Confinement(String relative,
                               TurnChangeSet.IncompleteReason failure) {
        /**
         * 接受结果必须携带非空规范相对路径。
         */
        private static Confinement accepted(String relative) {
            return new Confinement(relative, null);
        }

        /**
         * 失败结果不携带路径，防止调用方误用不可信 identity。
         */
        private static Confinement failed(TurnChangeSet.IncompleteReason reason) {
            return new Confinement(null, Objects.requireNonNull(reason, "reason"));
        }
    }

    /**
     * 单文件只保存本轮首次 preimage 与最新已确认 postimage。
     *
     * @param path 路径
     * @param beforeExists 变更前是否存在
     * @param beforeText 变更前的文本
     * @param beforeSha256 变更前内容的 SHA-256 摘要
     * @param afterExists 变更后是否存在
     * @param afterText 变更后的文本
     * @param afterSha256 变更后内容的 SHA-256 摘要
     */
    private record TrackedFile(String path,
                               boolean beforeExists,
                               String beforeText,
                               String beforeSha256,
                               boolean afterExists,
                               String afterText,
                               String afterSha256) {
        /**
         * 替换 postimage 时保留首次 preimage，实现同文件多次写入的净差异。
         */
        private TrackedFile withPostimage(boolean exists, String text, String hash) {
            return new TrackedFile(path, beforeExists, beforeText, beforeSha256, exists, text, hash);
        }

        /**
         * 恢复原状时从 tracker 删除该文件，使净零不产生摘要。
         */
        private boolean netZero() {
            return beforeExists == afterExists && beforeSha256.equals(afterSha256)
                   && beforeText.equals(afterText);
        }
    }

    /**
     * 单文件累计状态只保留首次 preimage 与最后一次已提交 postimage，不重复存放收据历史。
     *
     * @param path 路径
     * @param beforeExists 变更前是否存在
     * @param beforeText 变更前的文本
     * @param beforeSha256 变更前内容的 SHA-256 摘要
     * @param afterExists 变更后是否存在
     * @param afterText 变更后的文本
     * @param afterSha256 变更后内容的 SHA-256 摘要
     */
    public record DurableFile(String path,
                              boolean beforeExists,
                              String beforeText,
                              String beforeSha256,
                              boolean afterExists,
                              String afterText,
                              String afterSha256) {
        /**
         * 校验持久状态所需的内容身份，使恢复不依赖再次读取已变化的物理文件。
         */
        public DurableFile {
            path = Objects.requireNonNull(path, "path");
            beforeText = Objects.requireNonNull(beforeText, "beforeText");
            beforeSha256 = Objects.requireNonNull(beforeSha256, "beforeSha256");
            afterText = Objects.requireNonNull(afterText, "afterText");
            afterSha256 = Objects.requireNonNull(afterSha256, "afterSha256");
            if ((!beforeExists && !beforeText.isEmpty()) || (!afterExists && !afterText.isEmpty())
                || !Utf8Fingerprint.of(beforeText).sha256().equals(beforeSha256)
                || !Utf8Fingerprint.of(afterText).sha256().equals(afterSha256)) {
                throw new IllegalArgumentException("persisted change file identity is invalid");
            }
        }
    }

    /**
     * Tracker 的恢复值不含文件树快照或 Diff，只序列化已核实收据和完整性原因。
     *
     * @param workspaceId 工作区稳定标识
     * @param incompleteReasons 结果不完整的原因
     * @param files 文件列表
     */
    public record DurableSnapshot(String workspaceId,
                                  Set<TurnChangeSet.IncompleteReason> incompleteReasons,
                                  List<DurableFile> files) {
        /**
         * 冻结集合并核对容量，避免损坏状态在恢复时绕过正常收据预算。
         */
        public DurableSnapshot {
            workspaceId = ContractChecks.identifier(workspaceId, "workspaceId");
            incompleteReasons = Objects.requireNonNull(incompleteReasons, "incompleteReasons").isEmpty()
                    ? Set.of() : Set.copyOf(EnumSet.copyOf(incompleteReasons));
            files = List.copyOf(Objects.requireNonNull(files, "files"));
            if (files.size() > MAX_FILES || files.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("persisted change state exceeds tracker limits");
            }
        }
    }

    /**
     * SnapshotFile 把公开统计与只在冻结计算中的正文绑定为一个不可变值。
     *
     * @param change 变更
     * @param diff 差异
     */
    private record SnapshotFile(TurnChangeSet.FileChange change,
                                String diff) {
        /**
         * 防止集合冻结后正文被 null 破坏。
         */
        public SnapshotFile {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(diff, "diff");
        }
    }

    /**
     * 冻结计算的中间值只在 monitor 内存在，不形成运行中可读取快照。
     *
     * @param changeSet 变更集合
     * @param aggregateDiff 聚合差异
     */
    private record FrozenDraft(TurnChangeSet changeSet,
                               String aggregateDiff) {
    }


    /**
     * Diff 计算结果在单点决定是否截断。
     *
     * @param change 变更
     * @param diff 差异
     */
    private record DiffContent(TurnChangeSet.FileChange change,
                               String diff) {
        /**
         * 聚合超限时保留精确统计并把文件显式标成 truncated。
         */
        private TurnChangeSet.FileChange changeWithTruncation() {
            return new TurnChangeSet.FileChange(change.path(), change.status(), change.additions(),
                    change.deletions(), false, true);
        }
    }
}
