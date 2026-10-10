// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.review.interfaces.tools;

import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.domain.json.JsonArray;
import io.github.kongweiguang.ja.agent.domain.json.JsonBoolean;
import io.github.kongweiguang.ja.agent.domain.json.JsonNull;
import io.github.kongweiguang.ja.agent.domain.json.JsonNumber;
import io.github.kongweiguang.ja.agent.domain.json.JsonObject;
import io.github.kongweiguang.ja.agent.domain.json.JsonObjects;
import io.github.kongweiguang.ja.agent.domain.json.JsonText;
import io.github.kongweiguang.ja.agent.domain.json.JsonValue;
import io.github.kongweiguang.ja.agent.domain.tool.ToolOutcome;
import io.github.kongweiguang.ja.conversation.domain.model.ReviewContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将快照读取证据和报告回执保存在持久中间件状态中，避免模型文本单独结束 Review Turn；
 * 回执与证明它的 Tool artifact 由同一 Store 事务提交。
 */
public final class ReviewCompletionMiddleware implements AgentMiddleware {
    public static final String NAMESPACE = "ja.review.completion";
    private static final String CAPTURE = "capture";
    private static final String REPORT = "report";
    private static final int MAX_TRACKED_SIDES = 4_000;
    private static final int MAX_TRACKED_RANGES = 5_000;

    private final ReviewContext context;
    private final JsonValueCodec codec;

    /** 恢复时会重建中间件，因此只接收持久准入的快照附件身份，不缓存其他 Turn 状态。 */
    public ReviewCompletionMiddleware(ReviewContext context, JsonValueCodec codec) {
        this.context = Objects.requireNonNull(context, "context");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** 持久扩展状态按 owner 命名空间索引，重启后必须继续使用同一稳定身份。 */
    @Override
    public String id() {
        return NAMESPACE;
    }

    /** 持久快照或报告回执结构发生不兼容变化时，必须提升 schema 版本以拒绝误读旧状态。 */
    @Override
    public int stateSchemaVersion() {
        return 1;
    }

    /** 只有快照清单已写入持久状态后才放行读取或报告，避免本轮证据脱离可恢复身份。 */
    @Override
    public ExtensionDecision<ToolDecision> beforeTool(ToolContext tool) {
        String name = tool.invocation().toolName();
        if (!name.equals("review_snapshot_read") && !name.equals("review_report_submit")) {
            return ExtensionDecision.unchanged(ToolDecision.proceed(tool.invocation().arguments()),
                    tool.extensionState());
        }
        java.util.Optional<Capture> captured = capture(tool.extensionState());
        if (captured.isEmpty()) {
            return ExtensionDecision.unchanged(ToolDecision.reject("REVIEW_SNAPSHOT_REQUIRED",
                    "List the frozen review snapshot before reading files or submitting a report."),
                    tool.extensionState());
        }
        if (name.equals("review_report_submit")) {
            try {
                Set<FileIdentity> declared = fileIdentities(array(tool.invocation().arguments(), "filesReviewed", 2_000));
                if (!declared.equals(fullyReadFiles(captured.orElseThrow()))) {
                    return ExtensionDecision.unchanged(ToolDecision.reject("REVIEW_COVERAGE_UNVERIFIED",
                            "The reported path and layer pairs do not match fully read snapshot content."),
                            tool.extensionState());
                }
            } catch (RuntimeException invalid) {
                return ExtensionDecision.unchanged(ToolDecision.reject("REVIEW_COVERAGE_UNVERIFIED",
                        "The reported path and layer pairs could not be verified against durable snapshot reads."),
                        tool.extensionState());
            }
        }
        return ExtensionDecision.unchanged(ToolDecision.proceed(tool.invocation().arguments()),
                tool.extensionState());
    }

    /** 记录工具实际返回的读取区间和报告身份；无效回执清除报告，意外运行时故障仍保留原异常分类。 */
    @Override
    public ExtensionDecision<ToolResultProjection> projectToolResult(ToolResultContext result) {
        String name = result.invocation().toolName();
        JsonObject next = result.extensionState().value();
        if (name.equals("review_snapshot_list") && result.outcome() == ToolOutcome.SUCCEEDED) {
            try {
                Capture captured = parseList(codec.decodeObject(result.content()));
                next = withCapture(next, captured.toValue());
                next = withoutReport(next);
            } catch (RuntimeException invalid) {
                next = withoutCaptureAndReport(next);
            }
        } else if (name.equals("review_snapshot_read") && result.outcome() == ToolOutcome.SUCCEEDED) {
            try {
                Capture current = requiredCapture(result.extensionState());
                Capture updated = recordRead(current, codec.decodeObject(result.content()));
                next = withCapture(next, updated.toValue());
                next = withoutReport(next);
            } catch (RuntimeException invalid) {
                next = withoutReport(next);
            }
        } else if (name.equals("review_report_submit")) {
            JsonObject cleared = withoutReport(next);
            if (result.outcome() == ToolOutcome.SUCCEEDED && result.artifactContent().isPresent()) {
                try {
                    Capture current = requiredCapture(result.extensionState());
                    JsonObject artifact = codec.decodeObject(result.artifactContent().orElseThrow());
                    next = withReport(cleared, reportReceipt(current, artifact,
                            result.artifactContent().orElseThrow()));
                } catch (IllegalArgumentException invalid) {
                    next = cleared;
                }
            } else {
                next = cleared;
            }
        }
        ToolResultProjection projection = new ToolResultProjection(result.content(), result.structuredContent(),
                result.artifactContent());
        return new ExtensionDecision<>(projection, ExtensionStatePatch.replace(result.extensionState(), next),
                List.of());
    }

    /** 只有报告 artifact 和匹配的覆盖状态共同提交后才允许 Review Turn 完成。 */
    @Override
    public ExtensionDecision<CompletionDecision> beforeCompletion(CompletionContext completion) {
        boolean committed = capture(completion.extensionState()).flatMap(value ->
                report(completion.extensionState()).filter(receipt -> receiptMatches(value, receipt))).isPresent();
        if (committed) {
            return ExtensionDecision.unchanged(CompletionDecision.complete(), completion.extensionState());
        }
        return ExtensionDecision.unchanged(CompletionDecision.continueWith(
                "Submit a validated review report for the frozen snapshot before giving a final answer. "
                        + "Read the available file sides completely, then call review_report_submit with the exact "
                        + "path and layer pairs that were fully reviewed."), completion.extensionState());
    }

    /** 将清单和后续读取证据绑定到准入时冻结的附件，禁止切换到其他快照继续审查。 */
    private Capture parseList(JsonObject value) {
        requireKeys(value, Set.of("snapshotId", "snapshotHash", "revision", "coverage", "files"));
        String snapshotId = text(value, "snapshotId");
        String snapshotHash = digest(value, "snapshotHash");
        String revision = digest(value, "revision");
        JsonObject coverage = object(value.get("coverage"), "coverage");
        boolean complete = bool(coverage, "complete");
        int fileCount = integer(coverage, "files", 0, 2_000);
        List<ManifestFile> files = new ArrayList<>();
        Set<FileIdentity> identities = new HashSet<>();
        for (JsonValue raw : array(value, "files", 2_000).values()) {
            JsonObject file = object(raw, "snapshot file");
            requireKeys(file, Set.of("path", "layer", "oldPath", "status", "binary", "hasBase", "hasCurrent",
                    "hasPatch"));
            String path = text(file, "path");
            String layer = layer(file, "layer");
            if (!identities.add(new FileIdentity(path, layer))) {
                throw new IllegalArgumentException("duplicate review snapshot file");
            }
            boolean binary = bool(file, "binary");
            boolean hasBase = bool(file, "hasBase");
            boolean hasCurrent = bool(file, "hasCurrent");
            boolean hasPatch = bool(file, "hasPatch");
            if (binary && (hasBase || hasCurrent || hasPatch)) {
                throw new IllegalArgumentException("binary review file has text sides");
            }
            List<String> sides = new ArrayList<>();
            if (!binary) {
                if (hasPatch) {
                    sides.add("patch");
                } else {
                    if (hasBase) {
                        sides.add("base");
                    }
                    if (hasCurrent) {
                        sides.add("current");
                    }
                }
            }
            files.add(new ManifestFile(path, layer, List.copyOf(sides)));
        }
        if (files.size() != fileCount || !snapshotId.matches("rev_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("review snapshot list identity is inconsistent");
        }
        return new Capture(context.snapshotAttachmentId(), snapshotId, snapshotHash, revision, complete,
                fileCount, List.copyOf(files), List.of());
    }

    /** 逐项核对成功读取是否属于持久清单，并合并重叠区间而保留尚未读取的空隙。 */
    private Capture recordRead(Capture capture, JsonObject value) {
        requireKeys(value, Set.of("snapshotId", "snapshotHash", "path", "layer", "side", "requiredSides",
                "offsetCharacters", "totalCharacters", "content", "nextOffsetCharacters"));
        if (!capture.snapshotId().equals(text(value, "snapshotId"))
            || !capture.snapshotHash().equals(digest(value, "snapshotHash"))) {
            throw new IllegalArgumentException("review read belongs to another snapshot");
        }
        String path = text(value, "path");
        String layer = layer(value, "layer");
        String side = text(value, "side");
        ManifestFile file = capture.manifest().stream()
                .filter(candidate -> candidate.path().equals(path) && candidate.layer().equals(layer))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("review read is outside manifest"));
        List<String> requiredSides = texts(array(value, "requiredSides", 3));
        if (!file.requiredSides().equals(requiredSides) || !requiredSides.contains(side)) {
            throw new IllegalArgumentException("review read side does not match manifest");
        }
        int offset = integer(value, "offsetCharacters", 0, 10_000_000);
        int total = integer(value, "totalCharacters", 0, 10_000_000);
        JsonValue contentValue = value.get("content");
        if (!(contentValue instanceof JsonText contentText) || contentText.value().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid review content");
        }
        String content = contentText.value();
        int count = content.codePointCount(0, content.length());
        int end = Math.addExact(offset, count);
        if (end > total) {
            throw new IllegalArgumentException("review read range exceeds total");
        }
        JsonValue nextOffset = value.get("nextOffsetCharacters");
        if (end == total ? nextOffset != JsonNull.INSTANCE
                : integer(nextOffset, "nextOffsetCharacters", 0, 10_000_000) != end) {
            throw new IllegalArgumentException("review read continuation is inconsistent");
        }
        Map<SideIdentity, ReadState> reads = new LinkedHashMap<>();
        for (ReadState current : capture.reads()) {
            reads.put(new SideIdentity(current.path(), current.layer(), current.side()), current);
        }
        SideIdentity identity = new SideIdentity(path, layer, side);
        ReadState previous = reads.get(identity);
        if (previous != null && (previous.total() != total || !previous.requiredSides().equals(requiredSides))) {
            throw new IllegalArgumentException("review read side changed");
        }
        List<Range> ranges = new ArrayList<>(previous == null ? List.of() : previous.ranges());
        boolean emptyRead = previous != null && previous.emptyRead();
        if (count == 0 && total == 0) {
            emptyRead = true;
        } else if (count > 0) {
            ranges.add(new Range(offset, end));
        }
        List<Range> merged = merge(ranges);
        reads.put(identity, new ReadState(path, layer, side, requiredSides, total, merged, emptyRead));
        int rangeCount = reads.values().stream().mapToInt(read -> read.ranges().size()).sum();
        if (reads.size() > MAX_TRACKED_SIDES || rangeCount > MAX_TRACKED_RANGES) {
            throw new IllegalArgumentException("review read tracking budget exceeded");
        }
        return new Capture(capture.attachmentId(), capture.snapshotId(), capture.snapshotHash(), capture.revision(),
                capture.complete(), capture.fileCount(), capture.manifest(), List.copyOf(reads.values()));
    }

    /** 逐一核验报告与持久读取侧，并记录 artifact 摘要供完成钩子回读校验。 */
    private JsonObject reportReceipt(Capture capture, JsonObject artifact, String encoded) {
        requireKeys(artifact, Set.of("reportId", "snapshotId", "snapshotHash", "revision", "summary", "coverage",
                "findings"));
        String reportId = text(artifact, "reportId");
        if (!reportId.matches("report_[0-9a-f]{24}")
            || !capture.snapshotId().equals(text(artifact, "snapshotId"))
            || !capture.snapshotHash().equals(digest(artifact, "snapshotHash"))
            || !capture.revision().equals(digest(artifact, "revision"))) {
            throw new IllegalArgumentException("review report identity differs from its frozen snapshot");
        }
        text(artifact, "summary");
        array(artifact, "findings", 2_000);
        JsonObject coverage = object(artifact.get("coverage"), "report coverage");
        requireKeys(coverage, Set.of("complete", "files", "bytes", "maxFiles", "maxBytes", "partialReasons",
                "filesReviewed", "reviewComplete"));
        if (bool(coverage, "complete") != capture.complete()
            || integer(coverage, "files", 0, 2_000) != capture.fileCount()) {
            throw new IllegalArgumentException("review coverage differs from its frozen snapshot");
        }
        Set<FileIdentity> declared = fileIdentities(array(coverage, "filesReviewed", 2_000));
        Set<FileIdentity> verified = fullyReadFiles(capture);
        if (!declared.equals(verified)) {
            throw new IllegalArgumentException("review report coverage is not supported by durable reads");
        }
        boolean reviewComplete = capture.complete() && declared.size() == capture.fileCount();
        if (bool(coverage, "reviewComplete") != reviewComplete) {
            throw new IllegalArgumentException("review completion claim is inconsistent");
        }
        return JsonObjects.builder().putText("attachmentId", capture.attachmentId())
                .putText("snapshotId", capture.snapshotId()).putText("snapshotHash", capture.snapshotHash())
                .putText("reportId", reportId).putText("artifactHash", sha256(encoded))
                .put("filesReviewed", identitiesValue(declared)).build();
    }

    /** 回读时再次核对附件、快照摘要和文件层身份，避免陈旧或串会话回执伪装为当前结果。 */
    private boolean receiptMatches(Capture capture, JsonObject receipt) {
        try {
            requireKeys(receipt, Set.of("attachmentId", "snapshotId", "snapshotHash", "reportId", "artifactHash",
                    "filesReviewed"));
            if (!context.snapshotAttachmentId().equals(text(receipt, "attachmentId"))
                || !capture.attachmentId().equals(text(receipt, "attachmentId"))
                || !capture.snapshotId().equals(text(receipt, "snapshotId"))
                || !capture.snapshotHash().equals(digest(receipt, "snapshotHash"))
                || !text(receipt, "reportId").matches("report_[0-9a-f]{24}")
                || !digest(receipt, "artifactHash").matches("[0-9a-f]{64}")) {
                return false;
            }
            return fileIdentities(array(receipt, "filesReviewed", 2_000)).equals(fullyReadFiles(capture));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** 仅把所需文本侧均完整读取的路径和层列为可报告项，不信任模型自报覆盖范围。 */
    private static Set<FileIdentity> fullyReadFiles(Capture capture) {
        Map<SideIdentity, ReadState> reads = new HashMap<>();
        capture.reads().forEach(read -> reads.put(new SideIdentity(read.path(), read.layer(), read.side()), read));
        Set<FileIdentity> result = new LinkedHashSet<>();
        for (ManifestFile file : capture.manifest()) {
            if (!file.requiredSides().isEmpty() && file.requiredSides().stream().allMatch(side -> {
                ReadState read = reads.get(new SideIdentity(file.path(), file.layer(), side));
                return read != null && read.complete();
            })) {
                result.add(new FileIdentity(file.path(), file.layer()));
            }
        }
        return Set.copyOf(result);
    }

    /** 合并重叠或相邻读取区间，防止重复分页扩大覆盖量，同时保留真实未读间隙。 */
    private static List<Range> merge(List<Range> input) {
        List<Range> sorted = input.stream().sorted(Comparator.comparingInt(Range::start)).toList();
        List<Range> merged = new ArrayList<>();
        for (Range range : sorted) {
            if (merged.isEmpty() || merged.getLast().end() < range.start()) {
                merged.add(range);
            } else {
                Range previous = merged.removeLast();
                merged.add(new Range(previous.start(), Math.max(previous.end(), range.end())));
            }
        }
        return List.copyOf(merged);
    }

    /** 先完整验证持久清单，再供读取和完成钩子使用；损坏状态不会被当作有效证据。 */
    private java.util.Optional<Capture> capture(ExtensionStateView state) {
        JsonValue value = state.value().get(CAPTURE);
        if (value == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(parseCapture(object(value, CAPTURE)));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }

    /** 缺少状态代表没有已验证快照；损坏的持久值按不可用处理，不回退到模型输入。 */
    private Capture requiredCapture(ExtensionStateView state) {
        return capture(state).orElseThrow(() -> new IllegalArgumentException("review snapshot was not captured"));
    }

    /** 只解码结构正确的持久回执，格式异常时保持未完成状态。 */
    private java.util.Optional<JsonObject> report(ExtensionStateView state) {
        JsonValue value = state.value().get(REPORT);
        if (value == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(object(value, REPORT));
        } catch (RuntimeException invalid) {
            return java.util.Optional.empty();
        }
    }

    /** 恢复时仅从中间件持久记录重建清单和读取证明，不重新读取工作区。 */
    private Capture parseCapture(JsonObject value) {
        requireKeys(value, Set.of("attachmentId", "snapshotId", "snapshotHash", "revision", "complete", "fileCount",
                "manifest", "reads"));
        String attachmentId = text(value, "attachmentId");
        if (!context.snapshotAttachmentId().equals(attachmentId)) {
            throw new IllegalArgumentException("review capture belongs to another attachment");
        }
        String snapshotId = text(value, "snapshotId");
        String snapshotHash = digest(value, "snapshotHash");
        String revision = digest(value, "revision");
        boolean complete = bool(value, "complete");
        int fileCount = integer(value, "fileCount", 0, 2_000);
        List<ManifestFile> manifest = parseManifest(array(value, "manifest", 2_000));
        if (manifest.size() != fileCount || !snapshotId.matches("rev_[0-9a-f]{24}")) {
            throw new IllegalArgumentException("invalid persisted review capture");
        }
        List<ReadState> reads = parseReads(array(value, "reads", MAX_TRACKED_SIDES), manifest);
        return new Capture(attachmentId, snapshotId, snapshotHash, revision, complete, fileCount, manifest, reads);
    }

    /** 精确恢复路径与层的清单，拒绝重复身份和清单未声明的文本侧。 */
    private static List<ManifestFile> parseManifest(JsonArray values) {
        List<ManifestFile> result = new ArrayList<>();
        Set<FileIdentity> identities = new HashSet<>();
        for (JsonValue raw : values.values()) {
            JsonObject item = object(raw, "manifest file");
            requireKeys(item, Set.of("path", "layer", "requiredSides"));
            String path = text(item, "path");
            String layer = layer(item, "layer");
            List<String> sides = texts(array(item, "requiredSides", 3));
            if (sides.stream().anyMatch(side -> !Set.of("base", "current", "patch").contains(side))
                || sides.stream().distinct().count() != sides.size()
                || sides.contains("patch") && sides.size() != 1
                || !identities.add(new FileIdentity(path, layer))) {
                throw new IllegalArgumentException("invalid review manifest entry");
            }
            result.add(new ManifestFile(path, layer, sides));
        }
        return List.copyOf(result);
    }

    /** 恢复合并区间并确认每个读取侧属于对应清单项，防止持久状态跨文件拼接。 */
    private static List<ReadState> parseReads(JsonArray values, List<ManifestFile> manifest) {
        List<ReadState> result = new ArrayList<>();
        Set<SideIdentity> identities = new HashSet<>();
        int rangeCount = 0;
        for (JsonValue raw : values.values()) {
            JsonObject item = object(raw, "read side");
            requireKeys(item, Set.of("path", "layer", "side", "requiredSides", "total", "ranges", "emptyRead"));
            String path = text(item, "path");
            String layer = layer(item, "layer");
            String side = text(item, "side");
            List<String> sides = texts(array(item, "requiredSides", 3));
            ManifestFile file = manifest.stream().filter(candidate -> candidate.path().equals(path)
                    && candidate.layer().equals(layer)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("read side is outside manifest"));
            SideIdentity identity = new SideIdentity(path, layer, side);
            if (!file.requiredSides().equals(sides) || !sides.contains(side) || !identities.add(identity)) {
                throw new IllegalArgumentException("invalid persisted read side");
            }
            int total = integer(item, "total", 0, 10_000_000);
            List<Range> ranges = new ArrayList<>();
            for (JsonValue rangeValue : array(item, "ranges", MAX_TRACKED_RANGES).values()) {
                JsonObject range = object(rangeValue, "range");
                requireKeys(range, Set.of("start", "end"));
                int start = integer(range, "start", 0, 10_000_000);
                int end = integer(range, "end", start, 10_000_000);
                if (end == start || end > total) {
                    throw new IllegalArgumentException("invalid persisted review range");
                }
                ranges.add(new Range(start, end));
            }
            List<Range> merged = merge(ranges);
            if (!merged.equals(ranges)) {
                throw new IllegalArgumentException("persisted review ranges are not canonical");
            }
            boolean emptyRead = bool(item, "emptyRead");
            if (emptyRead && total != 0 || total == 0 && !ranges.isEmpty()) {
                throw new IllegalArgumentException("invalid empty review read");
            }
            rangeCount += ranges.size();
            result.add(new ReadState(path, layer, side, sides, total, List.copyOf(ranges), emptyRead));
        }
        if (rangeCount > MAX_TRACKED_RANGES) {
            throw new IllegalArgumentException("persisted review range budget exceeded");
        }
        return List.copyOf(result);
    }

    /** 将报告回执独立保存为状态成员，使回执与 artifact 共用 Core 提交事务。 */
    private static JsonObject withReport(JsonObject state, JsonObject report) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>(state.members());
        values.put(REPORT, report);
        return new JsonObject(values);
    }

    /** 新清单替换旧快照证明时清除旧报告，避免沿用旧覆盖水位完成当前审查。 */
    private static JsonObject withCapture(JsonObject state, JsonObject capture) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>(state.members());
        values.put(CAPTURE, capture);
        return new JsonObject(values);
    }

    /** 新报告尝试先移除旧回执，只有新 artifact 通过校验并提交后才可再次完成。 */
    private static JsonObject withoutReport(JsonObject state) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>(state.members());
        values.remove(REPORT);
        return new JsonObject(values);
    }

    /** 清除损坏清单对应的旧回执，避免完成钩子继续使用过期成功结果。 */
    private static JsonObject withoutCaptureAndReport(JsonObject state) {
        LinkedHashMap<String, JsonValue> values = new LinkedHashMap<>(state.members());
        values.remove(CAPTURE);
        values.remove(REPORT);
        return new JsonObject(values);
    }

    /**
     * 将快照身份、完整度、文件清单和读取区间绑定为受限持久状态，不保存快照正文。
     *
     * @param attachmentId 本次 Review 绑定的受管快照附件
     * @param snapshotId 冻结快照身份
     * @param snapshotHash 冻结快照内容摘要
     * @param revision 快照来源修订号
     * @param complete 快照是否完整
     * @param fileCount 快照清单文件数
     * @param manifest 不含正文的文件层清单
     * @param reads 已经持久确认的分页读取区间
     */
    private record Capture(String attachmentId,
                           String snapshotId,
                           String snapshotHash,
                           String revision,
                           boolean complete,
                           int fileCount,
                           List<ManifestFile> manifest,
                           List<ReadState> reads) {
        /** 构造时复制集合，保证后续状态转换不会修改先前已提交的快照。 */
        private Capture {
            manifest = List.copyOf(manifest);
            reads = List.copyOf(reads);
        }

        /** 将恢复所需证据编码为同一命名空间值，使修订号与快照身份保持一致。 */
        private JsonObject toValue() {
            List<JsonValue> files = manifest.stream().map(file -> (JsonValue) JsonObjects.builder()
                    .putText("path", file.path()).putText("layer", file.layer())
                    .put("requiredSides", textArray(file.requiredSides())).build()).toList();
            List<JsonValue> readValues = reads.stream().map(read -> (JsonValue) read.toValue()).toList();
            return JsonObjects.builder().putText("attachmentId", attachmentId).putText("snapshotId", snapshotId)
                    .putText("snapshotHash", snapshotHash).putText("revision", revision)
                    .putBoolean("complete", complete).putNumber("fileCount", fileCount)
                    .put("manifest", new JsonArray(files)).put("reads", new JsonArray(readValues)).build();
        }
    }

    /**
     * 一个不可变文件层及其完成审查所需文本侧；不存正文以限制中间件状态体积。
     *
     * @param path 冻结快照中的相对路径
     * @param layer 文件所属的变更层
     * @param requiredSides 完整覆盖该文件层所需读取的文本侧
     */
    private record ManifestFile(String path,
                                String layer,
                                List<String> requiredSides) {
    }

    /**
     * 用独立字段表达路径与层身份，避免依赖分隔符拼接造成歧义。
     *
     * @param path 冻结快照中的相对路径
     * @param layer 文件所属的变更层
     */
    private record FileIdentity(String path,
                                String layer) {
    }

    /**
     * 将分页读取按路径、层和文本侧分别计量，不能让一个侧的读取覆盖另一个侧。
     *
     * @param path 冻结快照中的相对路径
     * @param layer 文件所属的变更层
     * @param side 正在读取的快照文本侧
     */
    private record SideIdentity(String path,
                                String layer,
                                String side) {
    }

    /**
     * 使用半开 code point 区间记录实际返回给模型的正文，避免 UTF-16 偏移破坏 Unicode 边界。
     *
     * @param start 区间起点，包含
     * @param end 区间终点，不包含
     */
    private record Range(int start,
                         int end) {
    }

    /**
     * 单个快照侧的持久读取证据；完整性由实际区间推导，不采信模型提交的覆盖声明。
     *
     * @param path 冻结快照中的相对路径
     * @param layer 文件所属的变更层
     * @param side 正在计量的快照文本侧
     * @param requiredSides 文件层完成审查所需的全部文本侧
     * @param total 该侧的总 code point 数
     * @param ranges 已持久化的读取区间
     * @param emptyRead 正文为空时是否已执行过读取
     */
    private record ReadState(String path,
                             String layer,
                             String side,
                             List<String> requiredSides,
                             int total,
                             List<Range> ranges,
                             boolean emptyRead) {
        /** 空正文必须有实际读取记录，非空正文则要求单个合并区间完整覆盖总长度。 */
        private boolean complete() {
            return total == 0 ? emptyRead : ranges.size() == 1 && ranges.getFirst().start() == 0
                    && ranges.getFirst().end() == total;
        }

        /** 只序列化归一化区间而不保存正文，控制每个 Turn 的持久状态增长。 */
        private JsonObject toValue() {
            List<JsonValue> rangeValues = ranges.stream().map(range -> (JsonValue) JsonObjects.builder()
                    .putNumber("start", range.start()).putNumber("end", range.end()).build()).toList();
            return JsonObjects.builder().putText("path", path).putText("layer", layer).putText("side", side)
                    .put("requiredSides", textArray(requiredSides)).putNumber("total", total)
                    .put("ranges", new JsonArray(rangeValues)).putBoolean("emptyRead", emptyRead).build();
        }
    }

    /** 只接受 JSON object，遇到未知形状立即失败关闭以免错误状态被信任。 */
    private static JsonObject object(JsonValue value, String field) {
        if (!(value instanceof JsonObject object)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return object;
    }

    /** 读取必需文本并拒绝空值和 NUL，避免持久证据携带不可见分隔内容。 */
    private static String text(JsonObject value, String field) {
        JsonValue member = value.get(field);
        if (!(member instanceof JsonText text) || text.value().isEmpty() || text.value().indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return text.value();
    }

    /** 只接受完整小写 SHA-256，避免摘要截断或大小写折叠造成身份别名。 */
    private static String digest(JsonObject value, String field) {
        String result = text(value, field);
        if (!result.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }

    /** 读取指定范围内的精确整数，拒绝小数和溢出以保护分页游标语义。 */
    private static int integer(JsonObject value, String field, int minimum, int maximum) {
        return integer(value.get(field), field, minimum, maximum);
    }

    /** 对直接传入的 JSON 数值执行同一整数边界校验，避免调用路径改变解析语义。 */
    private static int integer(JsonValue raw, String field, int minimum, int maximum) {
        if (!(raw instanceof JsonNumber number)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        try {
            int value = number.value().intValueExact();
            if (value < minimum || value > maximum) {
                throw new IllegalArgumentException("invalid " + field);
            }
            return value;
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("invalid " + field, invalid);
        }
    }

    /** 只接受 JSON boolean，不把字符串或数字转换成持久覆盖状态。 */
    private static boolean bool(JsonObject value, String field) {
        JsonValue member = value.get(field);
        if (!(member instanceof JsonBoolean result)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result.value();
    }

    /** 先限制数组元素数再迭代，避免模型输入或损坏持久状态突破预算。 */
    private static JsonArray array(JsonObject value, String field, int maximum) {
        JsonValue member = value.get(field);
        if (!(member instanceof JsonArray result) || result.values().size() > maximum) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }

    /** 将文件层限制在 JA-RPC Review 契约的四种快照来源内。 */
    private static String layer(JsonObject value, String field) {
        String result = text(value, field);
        if (!Set.of("staged", "unstaged", "untracked", "comparison").contains(result)) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return result;
    }

    /** 只解析文本元素并拒绝重复侧名称，避免同一覆盖证据被重复计数。 */
    private static List<String> texts(JsonArray value) {
        List<String> result = new ArrayList<>();
        for (JsonValue item : value.values()) {
            if (!(item instanceof JsonText text) || text.value().isEmpty() || !result.add(text.value())) {
                throw new IllegalArgumentException("invalid text array");
            }
        }
        return List.copyOf(result);
    }

    /** 严格解析报告与覆盖记录共用的路径/层二元组，拒绝重复文件身份。 */
    private static Set<FileIdentity> fileIdentities(JsonArray value) {
        Set<FileIdentity> result = new LinkedHashSet<>();
        for (JsonValue raw : value.values()) {
            JsonObject item = object(raw, "reviewed file");
            requireKeys(item, Set.of("path", "layer"));
            if (!result.add(new FileIdentity(text(item, "path"), layer(item, "layer")))) {
                throw new IllegalArgumentException("duplicate reviewed file");
            }
        }
        return Set.copyOf(result);
    }

    /** 按稳定顺序序列化路径/层身份，使恢复记录及其摘要保持确定性。 */
    private static JsonArray identitiesValue(Set<FileIdentity> identities) {
        List<JsonValue> values = identities.stream().sorted(Comparator.comparing(FileIdentity::path)
                .thenComparing(FileIdentity::layer)).map(identity -> (JsonValue) JsonObjects.builder()
                .putText("path", identity.path()).putText("layer", identity.layer()).build()).toList();
        return new JsonArray(values);
    }

    /** 将必需文本侧编码为共享 JSON 表示，保证写入与恢复使用同一字段形状。 */
    private static JsonArray textArray(List<String> values) {
        return new JsonArray(values.stream().map(JsonText::new).map(JsonValue.class::cast).toList());
    }

    /** 要求对象字段严格闭合，避免新增字段或模型自加字段改变证据含义。 */
    private static void requireKeys(JsonObject value, Set<String> expected) {
        if (!value.members().keySet().equals(expected)) {
            throw new IllegalArgumentException("unexpected JSON fields");
        }
    }

    /** 对 Tool 实际持久化的 UTF-8 artifact 字节求摘要，不接受模型自报的摘要值。 */
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
