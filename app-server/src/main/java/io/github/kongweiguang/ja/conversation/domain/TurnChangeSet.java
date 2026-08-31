// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.List;
import java.util.Objects;

/** Rust 在 Turn 基线与终态之间计算并由 App Server 权威持久化的文件变化事实。 */
public record TurnChangeSet(State state, String reason, List<FileChange> files, Stats stats, String artifactId) {
    /** unavailable 不得伪造空修改统计；available 的统计必须与文件明细一致。 */
    public TurnChangeSet {
        Objects.requireNonNull(state, "state");
        files = List.copyOf(Objects.requireNonNull(files, "files"));
        Objects.requireNonNull(stats, "stats");
        if (files.size() > 10_000 || stats.files() != files.size()) {
            throw new IllegalArgumentException("change set file count mismatch");
        }
        if (state == State.AVAILABLE && reason != null || state == State.UNAVAILABLE && reason == null) {
            throw new IllegalArgumentException("change set state and reason mismatch");
        }
        if (state == State.UNAVAILABLE && (!files.isEmpty() || artifactId != null)) {
            throw new IllegalArgumentException("unavailable change set cannot expose changes");
        }
        if (reason != null && !reason.matches("concurrent_turn|not_git|capture_failed|diff_too_large")) {
            throw new IllegalArgumentException("invalid change set reason");
        }
        if (artifactId != null && !artifactId.matches("artifact_[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("invalid artifactId");
        }
    }

    /** 区分已冻结的可靠差异与明确无法证明归属的 Turn。 */
    public enum State {
        /** 已持久化可复核的 Turn 差异。 */
        AVAILABLE,
        /** 当前工作区或捕获失败使差异无法可靠生成。 */
        UNAVAILABLE
    }

    /** 文件状态只表达 Turn 基线和终态之间可验证的变化类型。 */
    public enum FileStatus {
        /** Turn 期间新增文件。 */
        ADDED,
        /** Turn 期间修改既有文件。 */
        MODIFIED,
        /** Turn 期间删除既有文件。 */
        DELETED,
        /** Turn 期间重命名既有文件。 */
        RENAMED
    }

    /** 单文件只保存工作区相对路径与可验证统计，不接受绝对路径。 */
    public record FileChange(String path, String oldPath, FileStatus status, Long additions, Long deletions,
                             boolean binary, boolean truncated) {
        /** 路径和统计在 Java 边界再次校验，不能信任 Rust 调用方已做 containment。 */
        public FileChange {
            path = relative(path, "path");
            if (oldPath != null) oldPath = relative(oldPath, "oldPath");
            Objects.requireNonNull(status, "status");
            if ((status == FileStatus.RENAMED) != (oldPath != null)) {
                throw new IllegalArgumentException("rename oldPath mismatch");
            }
            if (additions != null && additions < 0 || deletions != null && deletions < 0) {
                throw new IllegalArgumentException("invalid line counts");
            }
            if (binary && (additions != null || deletions != null)) {
                throw new IllegalArgumentException("binary change cannot report line counts");
            }
        }
    }

    /** Turn 聚合统计是 Rust 捕获事实，Java 会在提交时与明细交叉验证。 */
    public record Stats(long files, long additions, long deletions, long binaryFiles, boolean truncated) {
        /** 所有计数必须非负且二进制数不能超过文件数。 */
        public Stats {
            if (files < 0 || additions < 0 || deletions < 0 || binaryFiles < 0 || binaryFiles > files) {
                throw new IllegalArgumentException("invalid change set stats");
            }
        }
    }

    /** 只接受规范相对路径词汇，拒绝盘符、根路径与父级逃逸。 */
    private static String relative(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 4_096 || value.indexOf('\\') >= 0
            || value.startsWith("/") || value.matches("(?i)^[a-z]:.*")
            || java.util.Arrays.asList(value.split("/", -1)).contains("..")
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
