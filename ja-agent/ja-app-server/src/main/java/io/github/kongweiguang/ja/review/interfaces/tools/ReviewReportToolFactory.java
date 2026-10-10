// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.domain.concurrent.CancellationToken;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjectBuilder;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSideEffect;
import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.review.application.api.ReviewUseCase;
import io.github.kongweiguang.ja.conversation.application.gateway.JaTool;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 为一次普通 Review Turn 固定快照读取与报告提交工具；所有可读内容均来自已绑定的冻结附件。
 */
public final class ReviewReportToolFactory {
    private static final int MAX_READ_CHARACTERS = 60_000;
    private static final int MAX_REPORT_BYTES = 3 * 1024 * 1024;

    /**
     * 工具工厂由静态契约与每 Turn 的附件身份组成，不持有跨 Turn 的审查缓存。
     */
    private ReviewReportToolFactory() {
    }

    /**
     * 两个读取入口与报告提交共享一次延迟快照物化；先由快照访问器校验依赖，再构造不抛异常的工具子类。
     */
    public static List<JaTool> createTools(ReviewContext context, ManagedAttachmentReader attachments,
                                           JsonValueCodec codec) {
        SnapshotAccess access = new SnapshotAccess(context, attachments, codec);
        return List.of(new SnapshotListTool(access, codec), new SnapshotReadTool(access, codec),
                new SubmitReportTool(access, codec));
    }

    /**
     * 构造不需要批准且不观察工作区写入的可信审查工具；模型参数仍由每个 Tool 的闭集语义复核。
     */
    private abstract static class ReviewTool implements JaTool {
        private final ToolSpec spec;
        private final JsonValueCodec codec;

        /**
         * codec 已由外层快照访问器校验，Schema 由私有子类本地创建，使基类构造不因防御校验提前抛出异常。
         */
        ReviewTool(ToolSpec spec, JsonValueCodec codec) {
            this.spec = spec;
            this.codec = codec;
        }

        /**
         * Review Tool 不执行仓库外部副作用，审批策略由能力声明而不是工具名称推导。
         */
        @Override
        public final ToolSideEffect sideEffect() {
            return ToolSideEffect.READ_ONLY;
        }

        /**
         * Review Tool 不会写入 Workspace，审查 Turn 的变更投影应保持空。
         */
        @Override
        public final WorkspaceMutationMode workspaceMutationMode() {
            return WorkspaceMutationMode.NONE;
        }

        /**
         * 只读快照与报告结算属于本地受信 Review 流程，不要求模型为每次读取请求审批。
         */
        @Override
        public final ApprovalRequirement approvalRequirement() {
            return ApprovalRequirement.TRUSTED_INTERNAL;
        }

        /**
         * Review 不借用 Plan 内部状态写入权限，也不允许在规划阶段调用审查工具。
         */
        @Override
        public final PlanAccess planAccess() {
            return PlanAccess.DISALLOWED;
        }

        /**
         * 返回已经冻结的 Tool 契约，避免提供者请求间的定义漂移。
         */
        @Override
        public final ToolSpec spec() {
            return spec;
        }

        /**
         * 将参数与快照错误映射为稳定 ToolResult，底层存储诊断和附件内容不进入模型上下文。
         */
        final CompletionStage<ToolResult> executeSafe(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                                                      CancellationToken cancellation,
                                                      ToolAction action) {
            try {
                cancellation.throwIfCancellationRequested();
                JsonValue result = action.run(invocation, context, cancellation);
                String encoded = codec.encode(Objects.requireNonNull(result, "tool result"));
                return CompletableFuture.completedFuture(new ToolResult(ToolOutcome.SUCCEEDED, encoded,
                        Optional.empty(), null));
            } catch (java.util.concurrent.CancellationException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.CANCELLED,
                        "TOOL_CANCELLED", "Review operation was cancelled."));
            } catch (IllegalArgumentException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED,
                        "TOOL_ARGUMENTS_INVALID", "Review arguments or snapshot identity are invalid."));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED,
                        "TOOL_IO_FAILED", "The frozen review snapshot could not be read."));
            }
        }

        /**
         * Tool 失败只返回固定文本和错误码，避免把文件内容、路径或底层异常消息送回 Provider。
         */
        protected static ToolResult failed(ToolOutcome outcome, String code, String content) {
            return new ToolResult(outcome, content, Optional.empty(), code);
        }
    }

    /**
     * 列举快照身份、覆盖边界和每个冻结文件的可读侧，供模型规划有证据的审查范围。
     */
    private static final class SnapshotListTool extends ReviewTool {
        private final SnapshotAccess access;

        /**
         * 列表工具只共享快照读取器，不持有工作区读取端口。
         */
        private SnapshotListTool(SnapshotAccess access, JsonValueCodec codec) {
            super(new ToolSpec("review_snapshot_list",
                    "List files and coverage in the frozen review snapshot.", emptySchema()), codec);
            this.access = access;
        }

        /**
         * 列表每次从固定附件读取同一冻结快照，附件不可用时向模型返回可诊断的稳定失败。
         */
        @Override
        public CompletionStage<ToolResult> execute(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                                                    CancellationToken cancellationToken) {
            return executeSafe(invocation, context, cancellationToken, (call, execution, token) ->
                    access.load(execution.identity().threadId(), token).listValue());
        }
    }

    /**
     * 按冻结 path 与 layer 读取文本行区间；不接受 Workspace 路径或搜索引擎返回的实时内容。
     */
    private static final class SnapshotReadTool extends ReviewTool {
        private final SnapshotAccess access;

        /**
         * 读取入口固定共享快照访问器，路径身份必须同时带来源层以区分 staged 与 unstaged。
         */
        private SnapshotReadTool(SnapshotAccess access, JsonValueCodec codec) {
            super(new ToolSpec("review_snapshot_read",
                    "Read a bounded character range from one file in the frozen review snapshot.",
                    snapshotReadSchema()), codec);
            this.access = access;
        }

        /**
         * Tool 输入按 code point 分页，返回续读游标不会切断 supplementary Unicode 字符。
         */
        @Override
        public CompletionStage<ToolResult> execute(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                                                    CancellationToken cancellationToken) {
            return executeSafe(invocation, context, cancellationToken, (call, execution, token) -> {
                SnapshotDocument snapshot = access.load(execution.identity().threadId(), token);
                String path = requiredString(call.arguments(), "path", 32_768);
                String layer = requiredString(call.arguments(), "layer", 16);
                String side = requiredString(call.arguments(), "side", 16);
                int offset = integer(call.arguments(), "offsetCharacters", 0, 0, 10_000_000);
                int maximum = integer(call.arguments(), "maxCharacters", MAX_READ_CHARACTERS,
                        1, MAX_READ_CHARACTERS);
                SnapshotFile file = snapshot.file(path, layer);
                String content = file.content(side);
                int total = content.codePointCount(0, content.length());
                if (offset > total) {
                    throw new IllegalArgumentException("review range starts beyond file content");
                }
                int from = content.offsetByCodePoints(0, offset);
                int count = Math.min(maximum, total - offset);
                int to = content.offsetByCodePoints(from, count);
                JsonObjectBuilder result = JsonObjects.builder()
                        .putText("snapshotId", snapshot.snapshotId()).putText("snapshotHash", snapshot.snapshotHash())
                        .putText("path", path).putText("layer", layer).putText("side", side)
                        .put("requiredSides", textArray(file.requiredSides()))
                        .putNumber("offsetCharacters", offset).putNumber("totalCharacters", total)
                        .putText("content", content.substring(from, to));
                if (offset + count < total) {
                    result.putNumber("nextOffsetCharacters", offset + count);
                } else {
                    result.put("nextOffsetCharacters", JsonNull.INSTANCE);
                }
                return result.build();
            });
        }
    }

    /**
     * 接收模型审查声明并提交与快照位置、层和逐行证据完全配对的冻结报告 artifact。
     */
    private static final class SubmitReportTool extends ReviewTool {
        private final SnapshotAccess access;
        private final JsonValueCodec codec;

        /**
         * 报告提交不接收 snapshot identity，由当前 Turn 冻结的附件派生，阻止模型改绑另一份审查对象。
         */
        private SubmitReportTool(SnapshotAccess access, JsonValueCodec codec) {
            super(new ToolSpec("review_report_submit",
                    "Submit findings that are verified against the frozen review snapshot.", reportSchema()), codec);
            this.access = access;
            this.codec = codec;
        }

        /**
         * 只有完成全部位置与原文证据核对后才生成持久 artifact；失败提交不会创建半成品报告。
         */
        @Override
        public CompletionStage<ToolResult> execute(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                                                    CancellationToken cancellationToken) {
            try {
                cancellationToken.throwIfCancellationRequested();
                SnapshotDocument snapshot = access.load(context.identity().threadId(), cancellationToken);
                ReviewUseCase.Report report = report(invocation.arguments(), snapshot);
                JsonObject payload = reportValue(report);
                String encoded = codec.encode(payload);
                if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
                    return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED,
                            "TOOL_ARGUMENTS_INVALID", "Review report exceeds its storage budget."));
                }
                JsonObject structured = JsonObjects.builder().putText("reportId", report.reportId())
                        .putText("snapshotId", report.snapshotId()).putNumber("findings", report.findings().size())
                        .putBoolean("reviewComplete", report.coverage().reviewComplete()).build();
                ToolResult result = new ToolResult(ToolOutcome.SUCCEEDED,
                        "Review report submitted with " + report.findings().size() + " findings.",
                        Optional.of(structured), null).withArtifactContent(encoded);
                return CompletableFuture.completedFuture(result);
            } catch (java.util.concurrent.CancellationException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.CANCELLED,
                        "TOOL_CANCELLED", "Review operation was cancelled."));
            } catch (IllegalArgumentException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED,
                        "TOOL_ARGUMENTS_INVALID", "Review report contains invalid snapshot evidence."));
            } catch (RuntimeException failure) {
                return CompletableFuture.completedFuture(failed(ToolOutcome.FAILED,
                        "TOOL_IO_FAILED", "The frozen review snapshot could not be read."));
            }
        }

        /**
         * 报告位置和 evidence 使用输入文本逐行核对，任何不一致都不能进入存储 artifact。
         */
        private ReviewUseCase.Report report(JsonObject input, SnapshotDocument snapshot) {
            requireKeys(input, Set.of("summary", "filesReviewed", "findings"));
            String summary = requiredString(input, "summary", 4_096);
            List<ReviewUseCase.ReviewedFile> filesReviewed = reviewedFiles(input, snapshot);
            Set<String> reviewedIdentities = new HashSet<>();
            filesReviewed.forEach(file -> reviewedIdentities.add(identity(file.path(), file.layer())));
            List<ReviewUseCase.Finding> findings = findings(input, snapshot, reviewedIdentities);
            boolean completeReview = snapshot.coverage().complete()
                    && filesReviewed.size() == snapshot.files().size();
            ReviewUseCase.Coverage coverage = new ReviewUseCase.Coverage(snapshot.coverage().complete(),
                    snapshot.coverage().files(), snapshot.coverage().bytes(), snapshot.coverage().maxFiles(),
                    snapshot.coverage().maxBytes(), snapshot.coverage().partialReasons(), filesReviewed,
                    completeReview);
            String reportId = "report_" + digest(codec.encode(reportIdentity(snapshot, summary, coverage, findings)))
                    .substring(0, 24);
            return new ReviewUseCase.Report(reportId, snapshot.snapshotId(), snapshot.snapshotHash(),
                    snapshot.revision(), summary, coverage, findings);
        }

        /**
         * 只接受快照现存且身份唯一的 path/layer 对，并保留模型实际声明的扫描顺序。
         */
        private static List<ReviewUseCase.ReviewedFile> reviewedFiles(JsonObject input,
                                                                       SnapshotDocument snapshot) {
            Set<String> declared = new HashSet<>();
            List<ReviewUseCase.ReviewedFile> result = new ArrayList<>();
            for (JsonValue value : requiredArray(input, "filesReviewed", 2_000)) {
                JsonObject item = object(value, "reviewed file");
                requireKeys(item, Set.of("path", "layer"));
                String path = requiredString(item, "path", 32_768);
                String layer = requiredString(item, "layer", 16);
                snapshot.file(path, layer);
                if (!declared.add(identity(path, layer))) {
                    throw new IllegalArgumentException("duplicate reviewed file");
                }
                result.add(new ReviewUseCase.ReviewedFile(path, layer));
            }
            return List.copyOf(result);
        }

        /**
         * 为每条 finding 生成服务端身份，并确认坐标行和引用证据完全来自已声明审查的快照文件。
         */
        private static List<ReviewUseCase.Finding> findings(JsonObject input, SnapshotDocument snapshot,
                                                             Set<String> reviewed) {
            List<ReviewUseCase.Finding> findings = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonValue value : requiredArray(input, "findings", 2_000)) {
                JsonObject item = object(value, "finding");
                requireKeys(item, Set.of("severity", "title", "body", "path", "layer", "startLine",
                        "endLine", "side", "evidence"));
                String severity = requiredString(item, "severity", 2);
                String title = requiredString(item, "title", 512);
                String body = requiredString(item, "body", 16_384);
                String path = requiredString(item, "path", 32_768);
                String layer = requiredString(item, "layer", 16);
                int startLine = integer(item, "startLine", -1, 1, 10_000_000);
                int endLine = integer(item, "endLine", -1, startLine, 10_000_000);
                String side = requiredString(item, "side", 8);
                String evidence = requiredString(item, "evidence", 4_096);
                if (!reviewed.contains(identity(path, layer))) {
                    throw new IllegalArgumentException("finding is outside declared review coverage");
                }
                SnapshotFile file = snapshot.file(path, layer);
                verifyEvidence(file.content(side), startLine, endLine, evidence);
                String findingId = "finding_" + digest(severity + "\0" + title + "\0" + body + "\0"
                        + path + "\0" + layer + "\0" + startLine + "\0" + endLine + "\0" + side + "\0"
                        + evidence).substring(0, 24);
                if (!ids.add(findingId)) {
                    throw new IllegalArgumentException("duplicate review finding");
                }
                findings.add(new ReviewUseCase.Finding(findingId, severity, title, body, path, layer,
                        startLine, endLine, side, evidence));
            }
            return List.copyOf(findings);
        }

        /**
         * 行坐标按 LF 计数，evidence 必须精确匹配所选侧的完整闭区间而不能只匹配搜索片段。
         */
        private static void verifyEvidence(String content, int startLine, int endLine, String evidence) {
            String normalized = content.replace("\r\n", "\n");
            String[] lines = normalized.split("\n", -1);
            if (startLine < 1 || endLine > lines.length) {
                throw new IllegalArgumentException("finding line range is outside snapshot content");
            }
            String actual = String.join("\n", java.util.Arrays.copyOfRange(lines, startLine - 1, endLine));
            if (!actual.equals(evidence)) {
                throw new IllegalArgumentException("finding evidence does not match snapshot lines");
            }
        }
    }

    /**
     * 一次 Turn 内缓存已验证的不可变快照文档，避免分页读取每次重新物化整个 JSON 附件。
     */
    private static final class SnapshotAccess {
        private final ReviewContext context;
        private final ManagedAttachmentReader attachments;
        private final JsonValueCodec codec;
        private SnapshotDocument snapshot;

        /**
         * Tool 实例只绑定一个冻结附件，Thread 由执行上下文在访问时重新提供以执行授权检查。
         */
        private SnapshotAccess(ReviewContext context, ManagedAttachmentReader attachments, JsonValueCodec codec) {
            this.context = Objects.requireNonNull(context, "context");
            this.attachments = Objects.requireNonNull(attachments, "attachments");
            this.codec = Objects.requireNonNull(codec, "codec");
        }

        /**
         * 第一次读取时按 64 KiB range 导入受管附件并解析严格 JSON；后续调用复用同一只读快照。
         */
        private synchronized SnapshotDocument load(String threadId, CancellationToken cancellation) {
            if (snapshot != null) {
                return snapshot;
            }
            ManagedAttachmentReader.Descriptor descriptor = attachments.inspect(
                    context.snapshotAttachmentId(), threadId);
            if (!"text".equals(descriptor.mediaKind()) || descriptor.sizeBytes() < 1
                || descriptor.sizeBytes() > 100L * 1024 * 1024) {
                throw new IllegalArgumentException("review attachment is not a bounded text snapshot");
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(
                    Math.toIntExact(Math.min(descriptor.sizeBytes(), 64 * 1024L)));
            long offset = 0;
            while (offset < descriptor.sizeBytes()) {
                cancellation.throwIfCancellationRequested();
                ManagedAttachmentReader.ReadResult part = attachments.read(new ManagedAttachmentReader.ReadRequest(
                        context.snapshotAttachmentId(), threadId, offset, 64 * 1024));
                byte[] content = part.content().getBytes(StandardCharsets.UTF_8);
                if (!descriptor.attachmentId().equals(part.attachmentId())
                    || !descriptor.displayName().equals(part.displayName())
                    || descriptor.sizeBytes() != part.sizeBytes()
                    || !"text".equals(part.mediaKind()) || !"utf-8".equals(part.encoding())
                    || part.offsetBytes() != offset || part.nextOffsetBytes() <= offset
                    || part.nextOffsetBytes() - offset != content.length
                    || content.length > 64 * 1024 || bytes.size() + content.length > descriptor.sizeBytes()) {
                    throw new IllegalStateException("review attachment changed during range read");
                }
                bytes.writeBytes(content);
                offset = part.nextOffsetBytes();
                if (part.endOfFile() != (offset == descriptor.sizeBytes())) {
                    throw new IllegalStateException("review attachment EOF is inconsistent");
                }
            }
            if (bytes.size() != descriptor.sizeBytes()) {
                throw new IllegalStateException("review attachment size is inconsistent");
            }
            String encoded = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            JsonValue parsed = codec.decode(encoded);
            snapshot = SnapshotDocument.parse(object(parsed, "review snapshot"));
            return snapshot;
        }

    }

    /**
     * 已通过 schema、预算和身份交叉校验的 Rust 快照投影，只保留报告与读取工具所需数据。
     *
     * @param snapshotId 冻结快照身份
     * @param workspaceId 快照所属工作区身份
     * @param revision 快照来源修订号
     * @param snapshotHash 冻结快照内容摘要
     * @param coverage 扫描上限及实际覆盖范围
     * @param files 经白名单验证的快照文件集合
     */
    private record SnapshotDocument(String snapshotId,
                                    String workspaceId,
                                    String revision,
                                    String snapshotHash,
                                    SnapshotCoverage coverage,
                                    List<SnapshotFile> files) {
        /**
         * 从 Rust 冻结 envelope 逐层读取白名单字段，拒绝未知版本、重复文件身份和不一致预算。
         */
        private static SnapshotDocument parse(JsonObject value) {
            requireKeys(value, Set.of("version", "snapshotId", "workspaceId", "source", "revision",
                    "snapshotHash", "coverage", "files"));
            if (integer(value, "version", -1, 1, 1) != 1 || value.get("source") == null
                || value.get("source") == JsonNull.INSTANCE) {
                throw new IllegalArgumentException("unsupported review snapshot envelope");
            }
            String snapshotId = requiredString(value, "snapshotId", 64);
            String workspaceId = requiredString(value, "workspaceId", 128);
            String revision = requiredString(value, "revision", 64);
            String snapshotHash = requiredString(value, "snapshotHash", 64);
            if (!snapshotId.matches("rev_[0-9a-f]{24}") || !workspaceId.matches("ws_[A-Za-z0-9_-]{1,97}")
                || !revision.matches("[0-9a-f]{64}") || !snapshotHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid review snapshot identity");
            }
            SnapshotCoverage coverage = SnapshotCoverage.parse(object(value.get("coverage"), "coverage"));
            List<SnapshotFile> files = new ArrayList<>();
            Set<String> identities = new HashSet<>();
            for (JsonValue item : requiredArray(value, "files", 2_000)) {
                SnapshotFile file = SnapshotFile.parse(object(item, "snapshot file"));
                if (!identities.add(identity(file.path(), file.layer()))) {
                    throw new IllegalArgumentException("duplicate snapshot path and layer");
                }
                files.add(file);
            }
            if (files.size() != coverage.files()) {
                throw new IllegalArgumentException("snapshot file count does not match coverage");
            }
            return new SnapshotDocument(snapshotId, workspaceId, revision, snapshotHash, coverage, List.copyOf(files));
        }

        /**
         * 清单只投影快照元数据和文本侧可用性，不暴露物理路径或快照外文件。
         */
        private JsonObject listValue() {
            List<JsonValue> fileValues = files.stream().map(file -> (JsonValue) JsonObjects.builder()
                    .putText("path", file.path()).putText("layer", file.layer())
                    .put("oldPath", nullable(file.oldPath())).putText("status", file.status())
                    .putBoolean("binary", file.binary()).putBoolean("hasBase", file.baseContent() != null)
                    .putBoolean("hasCurrent", file.content() != null).putBoolean("hasPatch", file.patch() != null)
                    .build()).toList();
            return JsonObjects.builder().putText("snapshotId", snapshotId).putText("snapshotHash", snapshotHash)
                    .putText("revision", revision).put("coverage", coverage.value())
                    .put("files", new JsonArray(fileValues)).build();
        }

        /**
         * path 与 layer 同时匹配才能定位唯一证据文件，禁止模型用同路径的另一状态覆盖当前层。
         */
        private SnapshotFile file(String path, String layer) {
            return files.stream().filter(file -> file.path().equals(path) && file.layer().equals(layer))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("file is outside review snapshot"));
        }
    }

    /**
     * 受限 coverage 元数据沿用 capture 扫描的固定上限，并向模型明确标注部分快照原因。
     *
     * @param complete 快照是否覆盖完整输入
     * @param files 快照包含的文件数
     * @param bytes 快照正文总字节数
     * @param maxFiles 扫描器允许的文件数上限
     * @param maxBytes 扫描器允许的总字节数上限
     * @param partialReasons 未完整覆盖的固定原因集合
     */
    private record SnapshotCoverage(boolean complete,
                                    int files,
                                    int bytes,
                                    int maxFiles,
                                    int maxBytes,
                                    List<String> partialReasons) {
        /**
         * partialReason 必须属于 Rust capture 与 JA-RPC contract 共用闭集，避免 Java 自行发明覆盖状态。
         */
        private static SnapshotCoverage parse(JsonObject value) {
            requireKeys(value, Set.of("complete", "files", "bytes", "maxFiles", "maxBytes", "partialReasons"));
            boolean complete = booleanValue(value, "complete");
            int files = integer(value, "files", -1, 0, 2_000);
            int bytes = integer(value, "bytes", -1, 0, 64 * 1024 * 1024);
            int maxFiles = integer(value, "maxFiles", -1, 2_000, 2_000);
            int maxBytes = integer(value, "maxBytes", -1, 64 * 1024 * 1024, 64 * 1024 * 1024);
            List<String> reasons = requiredArray(value, "partialReasons", 6).stream()
                    .map(item -> requiredText(item, "partial reason", 64)).toList();
            Set<String> allowed = Set.of("binary_file", "non_utf8", "file_limit", "byte_limit",
                    "content_unavailable", "patch_unavailable");
            if (reasons.stream().anyMatch(reason -> !allowed.contains(reason))
                || reasons.stream().distinct().count() != reasons.size() || complete != reasons.isEmpty()) {
                throw new IllegalArgumentException("invalid review coverage facts");
            }
            return new SnapshotCoverage(complete, files, bytes, maxFiles, maxBytes, reasons);
        }

        /**
         * 统一序列化 coverage 视图，列表工具和最终报告不会使用不同的上限或 partial reason。
         */
        private JsonObject value() {
            return JsonObjects.builder().putBoolean("complete", complete).putNumber("files", files)
                    .putNumber("bytes", bytes).putNumber("maxFiles", maxFiles).putNumber("maxBytes", maxBytes)
                    .put("partialReasons", textArray(partialReasons)).build();
        }
    }

    /**
     * 单个 snapshot 文件的 base/current/patch 文本都源自同一 Rust envelope，坐标层维持独立身份。
     *
     * @param path 冻结快照中的相对路径
     * @param oldPath 重命名前的路径，不适用时为空
     * @param status 文件在 Git 快照中的状态
     * @param layer 文件所属的变更层
     * @param binary 文件是否为二进制内容
     * @param baseContent base 侧文本，不适用时为空
     * @param content current 侧文本，不适用时为空
     * @param patch 冻结差异文本，不适用时为空
     */
    private record SnapshotFile(String path,
                                String oldPath,
                                String status,
                                String layer,
                                boolean binary,
                                String baseContent,
                                String content,
                                String patch) {
        /**
         * 文件内容字段必须为严格文本或显式 null；status 沿用 Rust ReviewFileStatus 的序列化闭集，
         * 新增与冲突文件同样属于可审查输入，不能因客户端和服务端各自命名而拒绝整个快照。
         */
        private static SnapshotFile parse(JsonObject value) {
            requireKeys(value, Set.of("path", "oldPath", "status", "layer", "binary", "baseContent",
                    "content", "patch", "baseSha256", "contentSha256", "patchSha256"));
            String path = requiredString(value, "path", 32_768);
            String oldPath = optionalNullableString(value, "oldPath", 32_768);
            String status = requiredString(value, "status", 16);
            String layer = requiredString(value, "layer", 16);
            boolean binary = booleanValue(value, "binary");
            String base = optionalNullableString(value, "baseContent", 64 * 1024 * 1024);
            String current = optionalNullableString(value, "content", 64 * 1024 * 1024);
            String patch = optionalNullableString(value, "patch", 64 * 1024 * 1024);
            validateDigest(value, "baseSha256");
            validateDigest(value, "contentSha256");
            validateDigest(value, "patchSha256");
            if (path.isBlank() || path.codePoints().anyMatch(Character::isISOControl)
                || oldPath != null && (oldPath.isBlank() || oldPath.codePoints().anyMatch(Character::isISOControl))
                || !Set.of("added", "modified", "deleted", "renamed", "copied", "conflicted", "untracked")
                        .contains(status)
                || !Set.of("staged", "unstaged", "untracked", "comparison").contains(layer)
                || binary && (base != null || current != null || patch != null)) {
                throw new IllegalArgumentException("invalid frozen snapshot file");
            }
            return new SnapshotFile(path, oldPath, status, layer, binary, base, current, patch);
        }

        /**
         * side 是严格枚举，读取不支持的数据面时失败关闭，不回退到磁盘或另一侧文件内容。
         */
        private String content(String side) {
            String value = switch (side) {
                case "base" -> baseContent;
                case "current" -> content;
                case "patch" -> patch;
                default -> throw new IllegalArgumentException("invalid review snapshot side");
            };
            if (value == null) {
                throw new IllegalArgumentException("requested snapshot side has no text content");
            }
            return value;
        }

        /** 与 SnapshotAccess 共用 patch 优先规则，保证持久覆盖统计和实际可读侧一致。 */
        private List<String> requiredSides() {
            if (binary) {
                return List.of();
            }
            if (patch != null) {
                return List.of("patch");
            }
            List<String> sides = new ArrayList<>(2);
            if (baseContent != null) {
                sides.add("base");
            }
            if (content != null) {
                sides.add("current");
            }
            return List.copyOf(sides);
        }

        /**
         * Rust 的 optional file hashes 可以缺失，但一旦存在必须仍是完整摘要。
         */
        private static void validateDigest(JsonObject file, String field) {
            String digest = optionalNullableString(file, field, 64);
            if (digest != null && !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid snapshot file digest");
            }
        }
    }

    /**
     * 将领域报告编码成与 JA-RPC ReviewReport 同名同义的稳定 JSON 字段集合。
     */
    private static JsonObject reportValue(ReviewUseCase.Report report) {
        List<JsonValue> findings = report.findings().stream().map(finding -> (JsonValue)
                appendFindingFields(JsonObjects.builder().putText("findingId", finding.findingId()), finding)
                        .build()).toList();
        return JsonObjects.builder().putText("reportId", report.reportId()).putText("snapshotId", report.snapshotId())
                .putText("snapshotHash", report.snapshotHash()).putText("revision", report.revision())
                .putText("summary", report.summary()).put("coverage", coverageValue(report.coverage()))
                .put("findings", new JsonArray(findings)).build();
    }

    /**
     * 计算报告身份前先序列化 reportId 以外的冻结内容，摘要输入保留固定 record 字段顺序。
     */
    private static JsonObject reportIdentity(SnapshotDocument snapshot, String summary,
                                             ReviewUseCase.Coverage coverage,
                                             List<ReviewUseCase.Finding> findings) {
        List<JsonValue> findingValues = findings.stream().map(finding -> (JsonValue)
                appendFindingFields(JsonObjects.builder(), finding).build()).toList();
        return JsonObjects.builder().putText("snapshotId", snapshot.snapshotId())
                .putText("snapshotHash", snapshot.snapshotHash()).putText("revision", snapshot.revision())
                .putText("summary", summary).put("coverage", coverageValue(coverage))
                .put("findings", new JsonArray(findingValues)).build();
    }

    /**
     * coverage 与 filesReviewed 属于同一冻结契约；报告和身份摘要共用此路径，确保覆盖事实与字段顺序一致。
     */
    private static JsonObject coverageValue(ReviewUseCase.Coverage coverage) {
        List<JsonValue> reviewed = coverage.filesReviewed().stream().map(file -> (JsonValue) JsonObjects.builder()
                .putText("path", file.path()).putText("layer", file.layer()).build()).toList();
        return JsonObjects.builder().putBoolean("complete", coverage.complete())
                .putNumber("files", coverage.files()).putNumber("bytes", coverage.bytes())
                .putNumber("maxFiles", coverage.maxFiles()).putNumber("maxBytes", coverage.maxBytes())
                .put("partialReasons", textArray(coverage.partialReasons())).put("filesReviewed", new JsonArray(reviewed))
                .putBoolean("reviewComplete", coverage.reviewComplete()).build();
    }

    /**
     * 身份摘要排除仅存在于完整报告中的 findingId 以避免自引用；调用方先写专属字段，再追加顺序固定的契约。
     */
    private static JsonObjectBuilder appendFindingFields(JsonObjectBuilder builder, ReviewUseCase.Finding finding) {
        return builder.putText("severity", finding.severity()).putText("title", finding.title())
                .putText("body", finding.body()).putText("path", finding.path()).putText("layer", finding.layer())
                .putNumber("startLine", finding.startLine()).putNumber("endLine", finding.endLine())
                .putText("side", finding.side()).putText("evidence", finding.evidence());
    }

    /**
     * 报告 Tool 的空输入仍使用严格 object schema，不接受模型附带第二个 snapshot selector。
     */
    private static JsonObject emptySchema() {
        return objectSchema(Map.of(), List.of());
    }

    /**
     * Snapshot range 使用显式身份、side 和有界 code point 游标，避免路径被解释为磁盘读取授权。
     */
    private static JsonObject snapshotReadSchema() {
        return objectSchema(Map.of(
                "path", stringProperty("Exact path returned by review_snapshot_list.", 32_768),
                "layer", enumProperty("Exact file layer returned by review_snapshot_list.",
                        List.of("staged", "unstaged", "untracked", "comparison")),
                "side", enumProperty("Frozen text side to read.", List.of("base", "current", "patch")),
                "offsetCharacters", integerProperty("Zero-based Unicode code point offset.", 0, 10_000_000),
                "maxCharacters", integerProperty("Maximum response length.", 1, MAX_READ_CHARACTERS)),
                List.of("path", "layer", "side"));
    }

    /**
     * 提交格式只接受模型结果，不接收任何由调用端指定的 report、snapshot 或 finding identity。
     */
    private static JsonObject reportSchema() {
        JsonObject reviewedFile = objectSchema(Map.of("path", stringProperty("Reviewed snapshot path.", 32_768),
                "layer", enumProperty("Reviewed snapshot layer.", List.of("staged", "unstaged", "untracked", "comparison"))),
                List.of("path", "layer"));
        JsonObject finding = objectSchema(Map.of(
                "severity", enumProperty("Finding severity.", List.of("P0", "P1", "P2", "P3")),
                "title", stringProperty("Short finding title.", 512),
                "body", stringProperty("Why the finding matters.", 16_384),
                "path", stringProperty("Exact path returned by review_snapshot_list.", 32_768),
                "layer", enumProperty("Exact snapshot layer.", List.of("staged", "unstaged", "untracked", "comparison")),
                "startLine", integerProperty("First one-based evidence line.", 1, 10_000_000),
                "endLine", integerProperty("Last one-based evidence line.", 1, 10_000_000),
                "side", enumProperty("Whether line coordinates refer to base or current content.", List.of("base", "current")),
                "evidence", stringProperty("Exact full line text for the selected line range.", 4_096)),
                List.of("severity", "title", "body", "path", "layer", "startLine", "endLine", "side", "evidence"));
        JsonObject reviewedFiles = arrayProperty("Files actually inspected by the review.", reviewedFile, 2_000);
        JsonObject findings = arrayProperty("Verified findings; use an empty array when none were found.", finding, 2_000);
        return objectSchema(Map.of("summary", stringProperty("Concise result summary.", 4_096),
                "filesReviewed", reviewedFiles, "findings", findings),
                List.of("summary", "filesReviewed", "findings"));
    }

    /**
     * 对象字段和 required 集合由同一规格生成，避免远端 Provider schema 接受额外字段。
     */
    private static JsonObject objectSchema(Map<String, JsonObject> properties, List<String> required) {
        List<JsonValue> fields = required.stream().map(JsonText::new).map(JsonValue.class::cast).toList();
        return JsonObjects.builder().putText("type", "object").put("properties", new JsonObject(properties))
                .put("required", new JsonArray(fields)).putBoolean("additionalProperties", false).build();
    }

    /**
     * 构造一个类型固定的有界 JSON Schema 字符串属性。
     */
    private static JsonObject stringProperty(String description, int maximum) {
        return JsonObjects.builder().putText("type", "string").putText("description", description)
                .putNumber("minLength", 1).putNumber("maxLength", maximum).build();
    }

    /**
     * 通过 enum 显式冻结层、severity 和 side，避免模型发明无法定位的值。
     */
    private static JsonObject enumProperty(String description, List<String> values) {
        return JsonObjects.builder().putText("type", "string").putText("description", description)
                .put("enum", textArray(values)).build();
    }

    /**
     * 数值 Schema 与 execute 的相同闭区间同步，整型语义由 Tool 参数验证器再确认。
     */
    private static JsonObject integerProperty(String description, int minimum, int maximum) {
        return JsonObjects.builder().putText("type", "integer").putText("description", description)
                .putNumber("minimum", minimum).putNumber("maximum", maximum).build();
    }

    /**
     * 仅为 JSON array 声明长度范围，单项身份校验仍在本地 Tool 执行时完成。
     */
    private static JsonObject arrayProperty(String description, JsonObject itemSchema, int maximum) {
        return JsonObjects.builder().putText("type", "array").putText("description", description)
                .put("items", itemSchema).putNumber("maxItems", maximum).build();
    }

    /**
     * 将文本列表冻结成严格 JSON array，保留 Rust coverage reason 的原始顺序。
     */
    private static JsonArray textArray(List<String> values) {
        return new JsonArray(values.stream().map(JsonText::new).map(JsonValue.class::cast).toList());
    }

    /**
     * 固定层身份使用 NUL 分隔，保证任意合法路径不会和另一 path/layer 元组发生拼接碰撞。
     */
    private static String identity(String path, String layer) {
        return path + '\0' + layer;
    }

    /**
     * 报告内容摘要固定为 SHA-256，身份截短只用于查阅，不承担内容完整性验证。
     */
    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * 检查对象成员闭集；持久快照和模型报告都不能借未知字段改写既有业务解释。
     */
    private static void requireKeys(JsonObject object, Set<String> expected) {
        if (!object.members().keySet().equals(expected)) {
            throw new IllegalArgumentException("JSON object fields do not match contract");
        }
    }

    /**
     * 读取必需且非空的有界文本，长度按 Unicode code point 而非 UTF-16 单元计算。
     */
    private static String requiredString(JsonObject object, String field, int maximum) {
        JsonValue value = object.get(field);
        if (!(value instanceof JsonText text) || text.value().isEmpty()
            || text.value().codePointCount(0, text.value().length()) > maximum
            || text.value().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid review text field");
        }
        return text.value();
    }

    /**
     * 严格读取有界 JSON 文本，不进行弱类型转换，确保 Rust 覆盖事实异常时失败关闭。
     */
    private static String requiredText(JsonValue value, String field, int maximum) {
        if (!(value instanceof JsonText text) || text.value().isEmpty()
            || text.value().codePointCount(0, text.value().length()) > maximum
            || text.value().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return text.value();
    }

    /**
     * 读取可空快照文本字段；JSON null 与字段缺失仍保持可区分。
     */
    private static String optionalNullableString(JsonObject object, String field, int maximum) {
        JsonValue value = object.get(field);
        if (value == JsonNull.INSTANCE) {
            return null;
        }
        if (value == null || !(value instanceof JsonText text)
            || text.value().codePointCount(0, text.value().length()) > maximum || text.value().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid optional review text field");
        }
        return text.value();
    }

    /**
     * 读取有限元素数组，行数与文件数仍受双方协议预算保护。
     */
    private static List<JsonValue> requiredArray(JsonObject object, String field, int maximum) {
        JsonValue value = object.get(field);
        if (!(value instanceof JsonArray array) || array.values().size() > maximum) {
            throw new IllegalArgumentException("invalid review array field");
        }
        return array.values();
    }

    /**
     * 读取整数时拒绝小数、溢出和浮点值，保持 JSON Schema integer 与实际验收一致。
     */
    private static int integer(JsonObject object, String field, int defaultValue, int minimum, int maximum) {
        JsonValue value = object.get(field);
        if (value == null) {
            if (defaultValue >= minimum && defaultValue <= maximum) {
                return defaultValue;
            }
            throw new IllegalArgumentException("missing review integer field");
        }
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException("invalid review integer field");
        }
        int result;
        try {
            result = number.value().intValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("invalid review integer field", failure);
        }
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException("review integer is outside the contract range");
        }
        return result;
    }

    /**
     * 只接受 JSON boolean，不从文本和数值推导 coverage 完整性。
     */
    private static boolean booleanValue(JsonObject object, String field) {
        JsonValue value = object.get(field);
        if (!(value instanceof JsonBoolean bool)) {
            throw new IllegalArgumentException("invalid review boolean field");
        }
        return bool.value();
    }

    /** 读取嵌套 object 并保留强类型值；将固定字段位置写入内部诊断，Tool 边界会映射稳定错误。 */
    private static JsonObject object(JsonValue value, String field) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException("invalid review object field: " + field);
        }
        return object;
    }

    /**
     * JSON null 作为显式值输出，使文件重命名身份不与字段缺省状态混淆。
     */
    private static JsonValue nullable(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonText(value);
    }

    /**
     * 将审查工具动作限定在共享取消与错误映射边界内，避免各入口自行泄露底层读取诊断。
     */
    @FunctionalInterface
    private interface ToolAction {
        /**
         * 运行已通过模型 Schema 校验的业务动作，调用方仍需在动作内验证快照证据与身份绑定。
         */
        JsonValue run(AgentTool.Invocation invocation, JaTool.ExecutionContext context,
                      CancellationToken cancellation);
    }
}
