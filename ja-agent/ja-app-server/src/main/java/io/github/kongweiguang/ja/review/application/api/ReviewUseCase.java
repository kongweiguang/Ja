// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.application.api;

import io.github.kongweiguang.ja.conversation.domain.model.ConversationTurnEventSink;
import io.github.kongweiguang.ja.conversation.domain.model.TurnResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 将冻结 Git 快照审查复用为普通 Turn，并通过既有 Tool artifact 保存报告。
 */
public interface ReviewUseCase {
    /**
     * 以普通 Agent admission 启动只读审查，operation identity 仍由 turn/start receipt 权威保存。
     *
     * @throws ReviewFailure 当目标、准入或冻结快照不满足 Review 用例约束时
     */
    Accepted start(StartRequest request, ConversationTurnEventSink sink, String operationKey,
                   String requestFingerprint);

    /**
     * 读取 Turn 当前状态；仅已完成且存在完整报告 artifact 时返回 report。
     *
     * @throws ReviewFailure 当目标 Turn 或已提交报告不可读取时
     */
    ReadResult read(ReadRequest request);

    /**
     * Review 启动在 Wire 之外补充稳定 Turn ID，保证通知订阅可先于 Engine admission 登记。
     *
     * @param threadId 目标会话身份
     * @param turnId 本次审查的稳定执行身份
     * @param expectedThreadRevision 准入时必须匹配的会话修订号
     * @param snapshotAttachmentId 已冻结快照的受管附件身份
     */
    record StartRequest(String threadId,
                        String turnId,
                        long expectedThreadRevision,
                        String snapshotAttachmentId) {
        /**
         * 冻结线程、执行和附件身份，客户端不能借此提交路径或模型运行配置覆盖。
         */
        public StartRequest {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            snapshotAttachmentId = identifier(snapshotAttachmentId, "att_", "snapshotAttachmentId");
            if (expectedThreadRevision < 0) {
                throw new IllegalArgumentException("invalid expectedThreadRevision");
            }
        }
    }

    /**
     * 普通 Turn admission 的受理回执，调用方据此继续订阅状态而不把受理当作审查完成。
     *
     * @param threadId 已受理执行所属的会话身份
     * @param turnId 已受理的稳定执行身份
     * @param threadRevision 准入提交后的会话修订号
     * @param queued 是否进入会话队列等待执行
     * @param completion 普通 Turn 的完成句柄
     */
    record Accepted(String threadId,
                    String turnId,
                    long threadRevision,
                    boolean queued,
                    CompletionStage<TurnResult> completion) {
        /**
         * 保留普通 Turn 完成句柄供 RPC 清理通知绑定，同时只公开 admission 的确切身份、版本和队列状态。
         */
        public Accepted {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            Objects.requireNonNull(completion, "completion");
            if (threadRevision < 0) {
                throw new IllegalArgumentException("invalid threadRevision");
            }
        }
    }

    /**
     * 指定同一 Thread 的稳定 Turn 身份读取状态与可选冻结报告。
     *
     * @param threadId 目标会话身份
     * @param turnId 要查询状态与报告的执行身份
     */
    record ReadRequest(String threadId,
                       String turnId) {
        /**
         * 两个身份都须是显式参数，避免从当前 Thread 或最近 Turn 隐式选取目标。
         */
        public ReadRequest {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
        }
    }

    /**
     * 状态沿用现有小写 Turn 枚举；有效 artifact 落库前不附带报告。
     *
     * @param threadId 状态所属的会话身份
     * @param turnId 被读取的执行身份
     * @param status 当前 Turn 状态
     * @param report 已完成且通过校验的报告；尚无报告时为 null
     */
    record ReadResult(String threadId,
                      String turnId,
                      String status,
                      Report report) {
        /**
         * 保留终态与报告的实际关系；完成态无报告由 Service 拒绝，而非在 DTO 中伪装空发现。
         */
        public ReadResult {
            threadId = identifier(threadId, "thr_", "threadId");
            turnId = identifier(turnId, "turn_", "turnId");
            if (!List.of("queued", "running", "waiting_approval", "waiting_input", "suspended",
                    "completed", "failed", "cancelled").contains(status)) {
                throw new IllegalArgumentException("invalid review status");
            }
            if (report != null && !"completed".equals(status)) {
                throw new IllegalArgumentException("non-completed review cannot contain a report");
            }
        }
    }

    /**
     * 冻结审查结果；snapshot identity 与 coverage 由服务端从受管附件派生并写入。
     *
     * @param reportId 冻结报告身份
     * @param snapshotId 报告所绑定的快照身份
     * @param snapshotHash 快照内容摘要
     * @param revision 快照来源修订号
     * @param summary 审查结论摘要
     * @param coverage 实际扫描与模型声明的覆盖范围
     * @param findings 经快照证据校验的发现列表
     */
    record Report(String reportId,
                  String snapshotId,
                  String snapshotHash,
                  String revision,
                  String summary,
                  Coverage coverage,
                  List<Finding> findings) {
        /**
         * 限制公开结果身份和集合规模；完整序列化字节预算在 Tool 与 RPC 边界再次执行。
         */
        public Report {
            reportId = digestIdentity(reportId, "report_");
            snapshotId = snapshotIdentity(snapshotId);
            snapshotHash = digest(snapshotHash, "snapshotHash");
            revision = digest(revision, "revision");
            summary = bounded(summary, 4_096, "summary", true);
            Objects.requireNonNull(coverage, "coverage");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
            if (findings.size() > 2_000) {
                throw new IllegalArgumentException("too many review findings");
            }
        }
    }

    /**
     * 快照能力边界与模型实际声明覆盖并排返回，部分扫描永远不能冒充完整审查。
     *
     * @param complete 快照是否完整覆盖了 Git 输入
     * @param files 快照文件数
     * @param bytes 快照正文总字节数
     * @param maxFiles 扫描器允许的文件数上限
     * @param maxBytes 扫描器允许的总字节数上限
     * @param partialReasons 未完整覆盖的固定原因
     * @param filesReviewed 模型明确声明已审查的文件层
     * @param reviewComplete 模型声明是否覆盖全部完整快照文件
     */
    record Coverage(boolean complete,
                    int files,
                    int bytes,
                    int maxFiles,
                    int maxBytes,
                    List<String> partialReasons,
                    List<ReviewedFile> filesReviewed,
                    boolean reviewComplete) {
        /**
         * 使用 Rust 快照构建器的固定预算闭集，避免 Java 对上游扫描范围作不同解释。
         */
        public Coverage {
            partialReasons = List.copyOf(Objects.requireNonNull(partialReasons, "partialReasons"));
            filesReviewed = List.copyOf(Objects.requireNonNull(filesReviewed, "filesReviewed"));
            if (files < 0 || files > 2_000 || bytes < 0 || bytes > 67_108_864
                || maxFiles != 2_000 || maxBytes != 67_108_864 || partialReasons.size() > 6
                || filesReviewed.size() > 2_000 || complete != partialReasons.isEmpty()
                || reviewComplete != (complete && filesReviewed.size() == files)) {
                throw new IllegalArgumentException("invalid review coverage");
            }
            if (partialReasons.stream().anyMatch(reason -> !List.of("binary_file", "non_utf8", "file_limit",
                    "byte_limit", "content_unavailable", "patch_unavailable").contains(reason))) {
                throw new IllegalArgumentException("invalid review partial reason");
            }
        }
    }

    /**
     * 一项模型声明已经检查的冻结快照文件层，服务端按 snapshot allowlist 校验路径与层。
     *
     * @param path 冻结快照中的相对文件路径
     * @param layer 文件所属的 Git 变更层
     */
    record ReviewedFile(String path,
                        String layer) {
        /**
         * 不把自由路径当成权限；结构检查和快照成员检查由报告 Tool 串联完成。
         */
        public ReviewedFile {
            path = boundedPath(path, "reviewed path");
            layer = ReviewUseCase.layer(layer);
        }
    }

    /**
     * 报告中的单项发现，位置及 evidence 都须与冻结文件内容一致。
     *
     * @param findingId 稳定发现身份
     * @param severity 严重级别
     * @param title 发现标题
     * @param body 对问题影响的说明
     * @param path 冻结快照中的相对文件路径
     * @param layer 文件所属的 Git 变更层
     * @param startLine 证据起始行号
     * @param endLine 证据结束行号
     * @param side 行号对应的快照正文侧
     * @param evidence 与行号逐字匹配的冻结证据
     */
    record Finding(String findingId,
                   String severity,
                   String title,
                   String body,
                   String path,
                   String layer,
                   int startLine,
                   int endLine,
                   String side,
                   String evidence) {
        /**
         * 以 path 与 layer 联合定位快照文件，并保留由 Tool 交叉校验的证据行身份。
         */
        public Finding {
            findingId = digestIdentity(findingId, "finding_");
            if (!List.of("P0", "P1", "P2", "P3").contains(severity)) {
                throw new IllegalArgumentException("invalid finding severity");
            }
            title = bounded(title, 512, "finding title", false);
            body = bounded(body, 16_384, "finding body", false);
            path = boundedPath(path, "finding path");
            layer = ReviewUseCase.layer(layer);
            if (startLine < 1 || endLine < startLine || endLine > 10_000_000) {
                throw new IllegalArgumentException("invalid finding line range");
            }
            if (!List.of("base", "current").contains(side)) {
                throw new IllegalArgumentException("invalid finding side");
            }
            evidence = bounded(evidence, 4_096, "finding evidence", false);
        }
    }

    /**
     * 校验稳定身份和长度，协议前缀由调用点显式选择。
     */
    private static String identifier(String value, String prefix, String field) {
        Objects.requireNonNull(value, field);
        if (!value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 验证由服务端生成的短摘要身份，阻止外部自由字符串被解释为稳定报告 ID。
     */
    private static String digestIdentity(String value, String prefix) {
        Objects.requireNonNull(value, "digest identity");
        if (!value.matches(java.util.regex.Pattern.quote(prefix) + "[0-9a-f]{24}")) {
            throw new IllegalArgumentException("invalid digest identity");
        }
        return value;
    }

    /**
     * Rust snapshot identity 使用来源 revision 的短 hash 前缀。
     */
    private static String snapshotIdentity(String value) {
        Objects.requireNonNull(value, "snapshotId");
        if (!value.matches("rev_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("invalid snapshotId");
        }
        return value;
    }

    /**
     * 完整 SHA-256 仅接受小写十六进制，保证三端比较使用唯一表示。
     */
    private static String digest(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 字符上限按 Unicode code point 计算，避免 supplementary character 绕过协议长度。
     */
    private static String bounded(String value, int maxCodePoints, String field, boolean allowEmpty) {
        Objects.requireNonNull(value, field);
        if ((!allowEmpty && value.isBlank()) || value.codePointCount(0, value.length()) > maxCodePoints
            || value.indexOf('\0') >= 0 || value.codePoints().anyMatch(codePoint ->
                    Character.isISOControl(codePoint) && codePoint != '\n' && codePoint != '\r' && codePoint != '\t')) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /**
     * 文件路径使用上游快照同一字符预算并拒绝换行等控制字符，避免自由文本成为位置别名。
     */
    private static String boundedPath(String value, String field) {
        String path = bounded(value, 32_768, field, false);
        if (path.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return path;
    }

    /**
     * 固定 Review 支持的 snapshot 文件层闭集。
     */
    private static String layer(String value) {
        if (!List.of("staged", "unstaged", "untracked", "comparison").contains(value)) {
            throw new IllegalArgumentException("invalid review file layer");
        }
        return value;
    }
}
