// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Java tracker 冻结并随 Turn 终态原子持久化的净文件修改事实。 */
public record TurnChangeSet(State state, Set<IncompleteReason> incompleteReasons,
                            List<FileChange> files, Stats stats, String artifactId) {
    /** complete 不允许携带原因；partial 至少有一个闭集原因，统计必须与文件明细一致。 */
    public TurnChangeSet {
        Objects.requireNonNull(state, "state");
        incompleteReasons = Objects.requireNonNull(incompleteReasons, "incompleteReasons").isEmpty()
                ? Set.of() : Set.copyOf(EnumSet.copyOf(incompleteReasons));
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        Objects.requireNonNull(stats, "stats");
        if (files.size() > 256 || stats.files() != files.size()) {
            throw new IllegalArgumentException("change set file count mismatch");
        }
        if ((state == State.COMPLETE) != incompleteReasons.isEmpty()) {
            throw new IllegalArgumentException("change set completeness mismatch");
        }
        long additions = files.stream().mapToLong(FileChange::additions).sum();
        long deletions = files.stream().mapToLong(FileChange::deletions).sum();
        if (stats.additions() != additions || stats.deletions() != deletions || stats.binaryFiles() != 0) {
            throw new IllegalArgumentException("change set stats mismatch");
        }
        if (artifactId != null && !artifactId.matches("artifact_[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("invalid artifactId");
        }
    }

    /** 运行预览与冻结结果共享唯一完整性二态。 */
    public enum State {
        /** 所有本轮工作区写入均可证明并已确认。 */
        COMPLETE,
        /** 至少存在一个闭集原因使当前结果只能代表已确认部分。 */
        PARTIAL
    }

    /** partial 原因是协议闭集，重复原因由 Set 去重且不推进 revision。 */
    public enum IncompleteReason {
        /** 已执行无法观察工作区副作用的 Tool。 */
        UNKNOWN_MUTATOR,
        /** 后续收据 preimage 与前一已确认 postimage 不一致。 */
        MUTATION_CHAIN_BROKEN,
        /** 收据路径越过 Workspace 物理边界或穿过重解析点。 */
        OUTSIDE_WORKSPACE,
        /** 新修改会超过文件、正文、行数或 Diff 聚合预算。 */
        LIMIT_EXCEEDED,
        /** 文件属性或 Diff 捕获发生无法安全归类的失败。 */
        CAPTURE_FAILED,
        /** 文件已写入但对应 Tool 结果事务未确认。 */
        COMMIT_UNCONFIRMED,
        /** 恢复进程无法证明崩溃前副作用的完整归属。 */
        RECOVERY_BOUNDARY
    }

    /** 精确文本 tracker 首版只产生新增、修改和删除，不推断 rename。 */
    public enum FileStatus {
        /** 本轮前不存在而当前存在。 */
        ADDED,
        /** 本轮前后均存在但 UTF-8 正文不同。 */
        MODIFIED,
        /** 本轮前存在而当前不存在。 */
        DELETED
    }

    /** 冻结文件只公开工作区相对路径与净行统计，不包含正文或 per-session fileId。 */
    public record FileChange(String path, FileStatus status, long additions, long deletions,
                             boolean binary, boolean truncated) {
        /** 路径与数值在 Java 边界再次收紧，禁止绝对路径和父级逃逸进入历史。 */
        public FileChange {
            path = relative(path);
            Objects.requireNonNull(status, "status");
            if (additions < 0 || deletions < 0 || binary) {
                throw new IllegalArgumentException("invalid text file change");
            }
        }
    }

    /** 统计沿用跨端稳定字段形状；当前精确文本实现的 binaryFiles 恒为零。 */
    public record Stats(long files, long additions, long deletions, long binaryFiles, boolean truncated) {
        /** 所有统计都必须非负，二进制数不能超过文件数。 */
        public Stats {
            if (files < 0 || additions < 0 || deletions < 0 || binaryFiles < 0 || binaryFiles > files) {
                throw new IllegalArgumentException("invalid change set stats");
            }
        }
    }

    /** Fresh Turn 的零修改终态也必须是明确 complete，而不是缺失记录。 */
    public static TurnChangeSet emptyComplete() {
        return new TurnChangeSet(State.COMPLETE, Set.of(), List.of(), new Stats(0, 0, 0, 0, false), null);
    }

    /** 只接受规范 `/` 分隔相对路径，避免物理位置越过 RPC 或 SQLite。 */
    private static String relative(String value) {
        if (value == null || value.isBlank() || value.length() > 4_096 || value.indexOf('\\') >= 0
                || value.startsWith("/") || value.matches("(?i)^[a-z]:.*")
                || java.util.Arrays.asList(value.split("/", -1)).contains("..")
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid change path");
        }
        return value;
    }
}
