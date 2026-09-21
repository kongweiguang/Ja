// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.domain;

import java.util.List;
import java.util.Objects;

/**
 * Tool 面向客户端的安全展示事实；原始参数与模型上下文不得借此 DTO 越过 JA-RPC 边界。
 */
public record ToolPresentation(
        Kind kind,
        String title,
        Status status,
        String inputPreview,
        String outputPreview,
        String summary,
        List<InteractionAnswerView> interactionAnswers,
        List<String> relativePaths,
        String command,
        String relativeCwd,
        String stdout,
        String stderr,
        Integer exitCode,
        Long durationMs,
        boolean truncated,
        String artifactId) {
    private static final int MAX_PREVIEW = 32_768;
    private static final int MAX_SUMMARY = 1_024;

    /**
     * 在领域边界限制所有可展示文本和路径数量，避免 transport 或历史读取重新接触无界 Tool 数据。
     */
    public ToolPresentation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        title = text(title, "title", 512, false);
        inputPreview = optionalText(inputPreview, "inputPreview", MAX_PREVIEW);
        outputPreview = optionalText(outputPreview, "outputPreview", MAX_PREVIEW);
        summary = optionalText(summary, "summary", MAX_SUMMARY);
        interactionAnswers = List.copyOf(Objects.requireNonNull(interactionAnswers, "interactionAnswers"));
        if (interactionAnswers.size() > 3) throw new IllegalArgumentException("too many interaction answers");
        command = optionalText(command, "command", MAX_PREVIEW);
        relativeCwd = optionalText(relativeCwd, "relativeCwd", 4_096);
        stdout = optionalText(stdout, "stdout", MAX_PREVIEW);
        stderr = optionalText(stderr, "stderr", MAX_PREVIEW);
        relativePaths = List.copyOf(Objects.requireNonNull(relativePaths, "relativePaths"));
        if (relativePaths.size() > 64) throw new IllegalArgumentException("too many relative paths");
        for (String path : relativePaths) text(path, "relativePath", 4_096, false);
        if (durationMs != null && durationMs < 0) throw new IllegalArgumentException("invalid durationMs");
        if (artifactId != null) identifier(artifactId, "artifact_", "artifactId");
    }

    /** 普通 Tool 不携带问答展示事实；保留紧凑构造入口，避免每个投影器重复传入空集合。 */
    public ToolPresentation(Kind kind, String title, Status status, String inputPreview,
                            String outputPreview, String summary, List<String> relativePaths,
                            String command, String relativeCwd, String stdout, String stderr,
                            Integer exitCode, Long durationMs, boolean truncated, String artifactId) {
        this(kind, title, status, inputPreview, outputPreview, summary, List.of(), relativePaths,
                command, relativeCwd, stdout, stderr, exitCode, durationMs, truncated, artifactId);
    }

    /**
     * 问答展示只保存用户可见的题目与答案文案，不携带 questionId、optionId 或模型原始结果。
     * 多选答案保留独立条目，避免 UI 再次解析带分隔符的摘要文本。
     */
    public record InteractionAnswerView(String question, List<String> answers, boolean skipped) {
        /** 文案与数量在领域边界有界，防止历史快照把任意 Tool 结果扩散到 WebView。 */
        public InteractionAnswerView {
            question = text(question, "interaction question", 4_096, false);
            answers = List.copyOf(Objects.requireNonNull(answers, "answers"));
            if (answers.size() > 64) throw new IllegalArgumentException("too many interaction answer labels");
            for (String answer : answers) text(answer, "interaction answer", 4_096, false);
            if (skipped && !answers.isEmpty()) {
                throw new IllegalArgumentException("skipped interaction cannot contain answers");
            }
        }
    }

    /** Tool 类别使用固定公开词汇，未知扩展统一归为 MCP。 */
    public enum Kind {
        /** 只读文件或受管资源。 */
        READ,
        /** 基于既有内容执行精确编辑。 */
        EDIT,
        /** 创建或完整覆写文件。 */
        WRITE,
        /** 在受控工作目录执行 Shell 命令。 */
        SHELL,
        /** 由外部 MCP Server 提供的扩展 Tool。 */
        MCP
    }

    /** Tool 展示状态与执行状态分离，避免客户端从缺失结果猜测生命周期。 */
    public enum Status {
        /** 已登记但尚未开始执行。 */
        PENDING,
        /** Tool 正在执行。 */
        RUNNING,
        /** Tool 已暂停并等待用户审批。 */
        WAITING_APPROVAL,
        /** Tool 已成功完成。 */
        SUCCESS,
        /** Tool 以稳定错误结果终止。 */
        ERROR,
        /** Tool 因 Turn 取消而终止。 */
        CANCELLED
    }

    /** 校验可空文本，同时拒绝控制字符和 NUL。 */
    private static String optionalText(String value, String field, int maximum) {
        return value == null ? null : text(value, field, maximum, true);
    }

    /** 可见文本允许换行，但拒绝除换行和制表符外的控制字符。 */
    private static String text(String value, String field, int maximum, boolean allowEmpty) {
        if (value == null || value.length() > maximum || !allowEmpty && value.isBlank()
            || value.chars().anyMatch(character -> Character.isISOControl(character)
                && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    /** Artifact 使用独立 opaque identity，不能由客户端提交物理路径。 */
    private static void identifier(String value, String prefix, String field) {
        if (!value.startsWith(prefix) || value.length() > 128
            || !value.substring(prefix.length()).matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
