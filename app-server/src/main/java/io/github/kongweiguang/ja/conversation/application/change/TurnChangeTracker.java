// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.application.change;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import io.github.kongweiguang.ja.conversation.domain.TurnChangeSet;
import io.github.kongweiguang.ja.conversation.port.out.AgentTool;
import io.github.kongweiguang.ja.foundation.filesystem.PathIdentities;

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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
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
    private final FileAttributeReader fileAttributeReader;
    private final Map<String, TrackedFile> tracked = new LinkedHashMap<>();
    private final EnumSet<TurnChangeSet.IncompleteReason> reasons;
    private boolean frozen;

    /** Fresh Turn 从可证明的 complete/0 revision 开始。 */
    public static TurnChangeTracker fresh(Path workspaceRoot) {
        return new TurnChangeTracker(workspaceRoot, EnumSet.noneOf(TurnChangeSet.IncompleteReason.class),
                TurnChangeTracker::readAttributesNoFollow);
    }

    /** 显式恢复跨越进程边界，旧副作用无法重新归属，因此从 partial/recovery_boundary 开始。 */
    public static TurnChangeTracker resumed(Path workspaceRoot) {
        return new TurnChangeTracker(workspaceRoot,
                EnumSet.of(TurnChangeSet.IncompleteReason.RECOVERY_BOUNDARY),
                TurnChangeTracker::readAttributesNoFollow);
    }

    /** 包内构造器替换窄文件属性端口，使 IO 失败分支无需引入生产模式开关即可确定性验证。 */
    TurnChangeTracker(Path workspaceRoot, FileAttributeReader fileAttributeReader) {
        this(workspaceRoot, EnumSet.noneOf(TurnChangeSet.IncompleteReason.class), fileAttributeReader);
    }

    /** 固定工作区物理根、初始完整性和属性读取边界；不扫描文件树，也不启动 watcher。 */
    private TurnChangeTracker(Path workspaceRoot, EnumSet<TurnChangeSet.IncompleteReason> initialReasons,
                              FileAttributeReader fileAttributeReader) {
        this.workspaceRoot = PathIdentities.normalized(workspaceRoot);
        this.fileAttributeReader = Objects.requireNonNull(fileAttributeReader, "fileAttributeReader");
        reasons = initialReasons.clone();
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
     * Tool 结果事务成功后才吸收精确收据；链断裂时保留此前确认 postimage，不混入外部修改。
     */
    public void apply(AgentTool.MutationReceipt receipt) {
        synchronized (monitor) {
            requireMutable();
            Objects.requireNonNull(receipt, "receipt");
            Confinement confinement = receipt.confinedWorkspaceRoot() != null
                    && sameWorkspaceIdentity(receipt.confinedWorkspaceRoot(), workspaceRoot)
                    ? Confinement.accepted(receipt.confinedRelativePath())
                    : confinedRelative(receipt.path());
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
            if (candidate.netZero()) proposed.remove(relative);
            else proposed.put(relative, candidate);
            if (proposed.equals(tracked)) return;
            if (overBudget(proposed)) {
                reasons.add(TurnChangeSet.IncompleteReason.LIMIT_EXCEEDED);
                return;
            }
            tracked.clear();
            tracked.putAll(proposed);
        }
    }

    /** 执行产生写入但 Tool 结果事务没有确认时只降级，不把收据当作已确认修改。 */
    public void markCommitUnconfirmed() {
        markIncomplete(TurnChangeSet.IncompleteReason.COMMIT_UNCONFIRMED);
    }

    /**
     * 唯一终态 owner 冻结当前净差异；文件 Diff 只在这里计算一次，运行期间不物化预览正文。
     */
    public Frozen freeze() {
        synchronized (monitor) {
            if (frozen) throw new IllegalStateException("turn change tracker is already frozen");
            frozen = true;
            FrozenDraft selected = snapshot();
            String aggregate = selected.aggregateDiff();
            String artifactId = aggregate.isEmpty() ? null : "artifact_" + compactId();
            TurnChangeSet base = selected.changeSet();
            TurnChangeSet changeSet = new TurnChangeSet(base.state(), base.incompleteReasons(), base.files(),
                    base.stats(), artifactId);
            return new Frozen(changeSet, aggregate.isEmpty() ? null : aggregate,
                    aggregate.isEmpty() ? null : sha256(aggregate),
                    aggregate.isEmpty() ? null : (long) aggregate.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    /** 从已确认 pre/postimage 计算文件级和聚合 Diff；预算降级只改变完整性，不泄漏正文。 */
    private FrozenDraft snapshot() {
        EnumSet<TurnChangeSet.IncompleteReason> snapshotReasons = reasons.clone();
        List<SnapshotFile> files = new ArrayList<>();
        int aggregateBytes = 0;
        for (TrackedFile file : tracked.values().stream().sorted(Comparator.comparing(TrackedFile::path)).toList()) {
            DiffContent content = diff(file);
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

    /** 正常路径使用 java-diff-utils；100ms 超时或有界队列饱和时退回内容精确的整文件替换。 */
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
        try {
            Future<List<String>> future = DIFF_EXECUTOR.submit(() -> UnifiedDiffUtils.generateUnifiedDiff(
                    file.beforeExists() ? "a/" + relative : "/dev/null",
                    file.afterExists() ? "b/" + relative : "/dev/null", encodedBefore,
                    DiffUtils.diff(encodedBefore, encodedAfter), 3));
            List<String> unified = future.get(DIFF_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            additions = unified.stream().filter(line -> line.startsWith("+") && !line.startsWith("+++")).count();
            deletions = unified.stream().filter(line -> line.startsWith("-") && !line.startsWith("---")).count();
            String text = renderUnified(unified, eofMarker);
            return diffContent(relative, status, additions, deletions, text);
        } catch (java.util.concurrent.RejectedExecutionException | TimeoutException failure) {
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
        } catch (ExecutionException failure) {
            return diffContent(relative, status, additions, deletions, wholeFileDiff(file));
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
                    && after.stream().noneMatch(line -> line.contains(candidate))) return candidate;
        }
    }

    /** 将 java-diff-utils 的内部 EOF 后缀替换为标准 unified diff 行，不把实现哨兵泄漏到 artifact。 */
    private static String renderUnified(List<String> unified, String eofMarker) {
        StringBuilder rendered = new StringBuilder();
        for (String line : unified) appendDiffLine(rendered, line, eofMarker);
        return rendered.toString();
    }

    /** EOF 哨兵必须紧随所属 +/-/context 行输出标记，保证应用补丁时仍能恢复精确字节。 */
    private static void appendDiffLine(StringBuilder target, String line, String eofMarker) {
        if (line.endsWith(eofMarker)) {
            target.append(line, 0, line.length() - eofMarker.length()).append('\n')
                    .append("\\ No newline at end of file\n");
        } else {
            target.append(line).append('\n');
        }
    }

    /** 单文件超过聚合上限时保留统计并显式 truncated，不把不完整正文混入 artifact。 */
    private static DiffContent diffContent(String path, TurnChangeSet.FileStatus status,
                                           long additions, long deletions, String diff) {
        boolean truncated = diff.getBytes(StandardCharsets.UTF_8).length > MAX_DIFF_BYTES;
        TurnChangeSet.FileChange change = new TurnChangeSet.FileChange(path, status, additions, deletions,
                false, truncated);
        return new DiffContent(change, truncated ? "" : diff);
    }

    /** fallback 仍生成标准 unified header/hunk，并完整保留所有逻辑行，不尝试启发式最小匹配。 */
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

    /** 总文件、逻辑行及写前写后 UTF-8 正文三项预算在接受新状态前统一核对。 */
    private static boolean overBudget(Map<String, TrackedFile> files) {
        if (files.size() > MAX_FILES) return true;
        long lines = 0;
        long bytes = 0;
        for (TrackedFile file : files.values()) {
            lines += logicalLines(file.beforeText()).lines().size() + logicalLines(file.afterText()).lines().size();
            bytes += file.beforeText().getBytes(StandardCharsets.UTF_8).length;
            bytes += file.afterText().getBytes(StandardCharsets.UTF_8).length;
            if (lines > MAX_LOGICAL_LINES || bytes > MAX_TEXT_BYTES) return true;
        }
        return false;
    }

    /** 路径必须处于物理 Workspace 内且任一已存在分段都不能是 symlink/junction/reparse point。 */
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
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
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
     * 收据根来自 WorkspaceBoundary 的物理路径，而 tracker 可能持有 Rust Host 传入的
     * namespaced/8.3 词法路径；用文件身份比较这两个受信根，避免把合法别名误降级为越界，
     * 同时不改变普通 Tool 路径的写入准入规则。
     */
    private static boolean sameWorkspaceIdentity(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException failure) {
            return PathIdentities.normalized(first).equals(PathIdentities.normalized(second));
        }
    }

    /** 生产属性读取固定 NOFOLLOW_LINKS，防止检查阶段跟随 symlink 或 junction。 */
    private static BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    /** 冻结后任何新 Tool 事实都表示调用顺序错误，禁止静默污染已提交 ChangeSet。 */
    private void requireMutable() {
        if (frozen) throw new IllegalStateException("turn change tracker is frozen");
    }

    /**
     * 只按 LF 切分并保留行尾 CR 与末尾空行，使 CRLF/LF 和文件末尾换行变化不会被归一化丢失。
     */
    private static TextLines logicalLines(String value) {
        if (value.isEmpty()) return new TextLines(List.of(), false);
        List<String> lines = new ArrayList<>(List.of(value.split("\\n", -1)));
        boolean terminated = value.endsWith("\n");
        if (terminated) lines.removeLast();
        return new TextLines(List.copyOf(lines), terminated);
    }

    /** 文本行与末尾换行身份分离，避免把 EOF newline 误建模成额外空逻辑行。 */
    private record TextLines(List<String> lines, boolean terminated) {
        /** 仅对无末尾换行的最后一行追加计算期哨兵；空文件没有可标注行。 */
        private List<String> encoded(String marker) {
            if (terminated || lines.isEmpty()) return lines;
            List<String> encoded = new ArrayList<>(lines);
            encoded.set(encoded.size() - 1, encoded.getLast() + marker);
            return List.copyOf(encoded);
        }
    }

    /** 路径判定把失败原因与相对路径互斥建模，调用方只推进一次完整性 revision。 */
    private record Confinement(String relative, TurnChangeSet.IncompleteReason failure) {
        /** 接受结果必须携带非空规范相对路径。 */
        private static Confinement accepted(String relative) { return new Confinement(relative, null); }
        /** 失败结果不携带路径，防止调用方误用不可信 identity。 */
        private static Confinement failed(TurnChangeSet.IncompleteReason reason) {
            return new Confinement(null, Objects.requireNonNull(reason, "reason"));
        }
    }

    /** 文件属性读取是唯一会抛出受检 IO 失败的 containment 副作用边界。 */
    @FunctionalInterface
    interface FileAttributeReader {
        /** 调用方必须按 NOFOLLOW 语义读取，异常由 tracker 映射为 capture_failed。 */
        BasicFileAttributes read(Path path) throws IOException;
    }

    /** UUID 去连字符后恰为 32 个小写十六进制字符。 */
    private static String compactId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 为全局 Diff executor 使用 daemon worker，避免独立关闭时阻塞 App Server 退出。 */
    private static ThreadFactory daemonFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "ja-turn-diff");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 固定 UTF-8 SHA-256，冻结 artifact 与 SQLite 校验使用同一摘要。 */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 单文件只保存本轮首次 preimage 与最新已确认 postimage。 */
    private record TrackedFile(String path, boolean beforeExists, String beforeText, String beforeSha256,
                               boolean afterExists, String afterText, String afterSha256) {
        /** 替换 postimage 时保留首次 preimage，实现同文件多次写入的净差异。 */
        private TrackedFile withPostimage(boolean exists, String text, String hash) {
            return new TrackedFile(path, beforeExists, beforeText, beforeSha256, exists, text, hash);
        }

        /** 恢复原状时从 tracker 删除该文件，使净零不产生摘要。 */
        private boolean netZero() {
            return beforeExists == afterExists && beforeSha256.equals(afterSha256)
                    && beforeText.equals(afterText);
        }
    }

    /** SnapshotFile 把公开统计与只在冻结计算中的正文绑定为一个不可变值。 */
    private record SnapshotFile(TurnChangeSet.FileChange change, String diff) {
        /** 防止集合冻结后正文被 null 破坏。 */
        public SnapshotFile {
            Objects.requireNonNull(change, "change");
            Objects.requireNonNull(diff, "diff");
        }
    }

    /** 冻结计算的中间值只在 monitor 内存在，不形成运行中可读取快照。 */
    private record FrozenDraft(TurnChangeSet changeSet, String aggregateDiff) { }

    /** 终态事务输入包含摘要、聚合正文、摘要和精确长度。 */
    public record Frozen(TurnChangeSet changeSet, String unifiedDiff, String sha256, Long byteLength) { }

    /** Diff 计算结果在单点决定是否截断。 */
    private record DiffContent(TurnChangeSet.FileChange change, String diff) {
        /** 聚合超限时保留精确统计并把文件显式标成 truncated。 */
        private TurnChangeSet.FileChange changeWithTruncation() {
            return new TurnChangeSet.FileChange(change.path(), change.status(), change.additions(),
                    change.deletions(), false, true);
        }
    }
}
