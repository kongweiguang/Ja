// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.conversation.instruction;

import io.github.kongweiguang.ja.conversation.port.out.InstructionScopeRepository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Thread 级 AGENTS catalog；Session 冻结发现范围，但每次 refresh 都从文件系统重建正文和 revision。
 */
public final class AgentInstructionCatalog {
    static final int GUIDANCE_BUDGET_BYTES = 24 * 1024;
    private final InstructionScopeRepository scopes;
    private final NioInstructionSource source;
    private final Clock clock;

    /** 生产构造器使用 NIO 作为唯一 AGENTS 文件读取实现。 */
    public AgentInstructionCatalog(InstructionScopeRepository scopes, Clock clock) {
        this(scopes, new NioInstructionSource(), clock);
    }

    /** 可注入文件边界只用于聚焦测试，Session 编排语义仍由 catalog 独占。 */
    AgentInstructionCatalog(InstructionScopeRepository scopes, NioInstructionSource source, Clock clock) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 打开 Thread session 时同时恢复持久 scope；项目规则仅在冻结 trusted=true 时进入候选集。
     */
    public Session open(SessionRequest request) {
        Objects.requireNonNull(request, "request");
        return new Session(request);
    }

    /** Session 必需事实；workspaceRoot 同时是 Tool 相对路径和初始 cwd 的唯一基准。 */
    public record SessionRequest(String threadId, Path workspaceRoot, Path jaHome, boolean trusted) {
        /** 拒绝空身份和相对边界，防止持久 scope 被绑定到模糊工作区。 */
        public SessionRequest {
            if (threadId == null || threadId.isBlank()) {
                throw new IllegalArgumentException("threadId is required");
            }
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(jaHome, "jaHome");
        }
    }

    /** 对 Prompt assembler 只暴露已预算 guidance、revision 和失败状态，不泄露可变文件对象。 */
    public record Snapshot(String guidance, String revision, boolean unsafe, List<String> diagnostics) {
        /** 防御性复制诊断，确保单次模型请求观察到不可变视图。 */
        public Snapshot {
            Objects.requireNonNull(guidance, "guidance");
            Objects.requireNonNull(revision, "revision");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Tool preflight 的闭集结果；外部副作用 batch guard 可直接使用 revisionChanged。 */
    public record Preflight(Decision decision, boolean revisionChanged, Snapshot snapshot) {
        /** Session 始终返回同时刻 snapshot，禁止调用方再读取一次造成 TOCTOU。 */
        public Preflight {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** preflight 决策区分刷新、容量与文件安全失败，便于 Tool 返回稳定错误码。 */
    public enum Decision {
        /** Tool 可按既有权限继续。 */
        CONTINUE,

        /** edit/write 必须让模型先看到新 revision。 */
        REFRESH_REQUIRED,

        /** 第 257 个新嵌套 scope 被拒绝。 */
        SCOPE_LIMIT_EXCEEDED,

        /** 指令文件读取或 containment 失败，所有外部副作用必须失败关闭。 */
        UNSAFE
    }

    /**
     * Turn 级可变发现状态；所有公开方法同步，避免并行 Tool preflight 产生 scope/revision 撕裂。
     */
    public final class Session {
        private final SessionRequest request;
        private final Set<String> nestedScopes = new LinkedHashSet<>();
        private Snapshot snapshot;

        /** 构造阶段恢复 scope 并立即生成首个不可变快照。 */
        private Session(SessionRequest request) {
            this.request = request;
            if (request.trusted()) nestedScopes.addAll(scopes.list(request.threadId()));
            snapshot = rebuild();
        }

        /** 返回最近一次原子重建结果。 */
        public synchronized Snapshot snapshot() {
            return snapshot;
        }

        /**
         * 刷新全局、初始链和所有已知嵌套 scope；删除文件不会删除 scope，因此重新出现仍会恢复。
         */
        public synchronized Snapshot refresh() {
            snapshot = rebuild();
            return snapshot;
        }

        /**
         * read 可携带新规则结果继续；edit/write 在任何新/变更规则出现时先拒绝零副作用调用。
         */
        public synchronized Preflight preflight(String toolName, String rawPath) {
            String previousRevision = snapshot.revision();
            snapshot = rebuild();
            boolean changed = !previousRevision.equals(snapshot.revision());
            if (snapshot.unsafe()) return new Preflight(unsafeDecision(toolName), changed, snapshot);
            if (!request.trusted()) return new Preflight(decision(toolName, changed), changed, snapshot);
            Optional<Path> target;
            try {
                target = source.targetDirectory(request.workspaceRoot(), rawPath);
            } catch (IOException failure) {
                snapshot = withFailure(snapshot, "AGENTS_TARGET_CONTAINMENT_FAILED");
                return new Preflight(unsafeDecision(toolName), true, snapshot);
            }
            if (target.isEmpty()) return new Preflight(decision(toolName, changed), changed, snapshot);
            DiscoveryResult discovery = discoverNested(target.orElseThrow());
            if (discovery.limitReached()) {
                return new Preflight(Decision.SCOPE_LIMIT_EXCEEDED, changed, snapshot);
            }
            if (discovery.discovered() && !snapshot.unsafe()) {
                snapshot = rebuild();
                changed = !previousRevision.equals(snapshot.revision());
            }
            if (snapshot.unsafe()) return new Preflight(unsafeDecision(toolName), changed, snapshot);
            return new Preflight(decision(toolName, changed), changed, snapshot);
        }

        /** 读取仍可用于诊断损坏规则；edit/write 不能在失去适用指令时产生副作用。 */
        private Decision unsafeDecision(String toolName) {
            return "read".equals(toolName) ? Decision.CONTINUE : Decision.UNSAFE;
        }

        /** edit/write 是本 catalog 唯一直接阻断的 Tool；其它外部副作用由 batch revision guard 判定。 */
        private Decision decision(String toolName, boolean changed) {
            if (changed && ("edit".equals(toolName) || "write".equals(toolName))) {
                return Decision.REFRESH_REQUIRED;
            }
            return Decision.CONTINUE;
        }

        /** 只登记实际存在 AGENTS 的嵌套目录，空目录不会消耗 Thread 的 256 scope 配额。 */
        private DiscoveryResult discoverNested(Path targetDirectory) {
            try {
                Path workspace = request.workspaceRoot().toRealPath();
                Path inspectLeaf = nearestExistingDirectory(targetDirectory);
                boolean discovered = false;
                for (Path directory : source.chain(workspace, inspectLeaf)) {
                    String relative = source.relativeDirectory(workspace, directory);
                    if (relative.isEmpty() || nestedScopes.contains(relative)) continue;
                    NioInstructionSource.Observation observation = source.observe(
                            directory, workspace, relative, directory.getNameCount());
                    if (observation.failed()) {
                        snapshot = withFailure(snapshot, observation.diagnostic());
                        return new DiscoveryResult(discovered, false);
                    }
                    if (observation.document() == null) continue;
                    InstructionScopeRepository.Registration registration = scopes.register(
                            request.threadId(), relative, clock.instant());
                    if (registration == InstructionScopeRepository.Registration.LIMIT_REACHED) {
                        return new DiscoveryResult(discovered, true);
                    }
                    nestedScopes.add(relative);
                    discovered = true;
                }
                return new DiscoveryResult(discovered, false);
            } catch (IOException | RuntimeException failure) {
                snapshot = withFailure(snapshot, "AGENTS_DISCOVERY_FAILED");
                return new DiscoveryResult(false, false);
            }
        }

        /** 尚未创建的目标目录只检查现存祖先；后续写入新目录时再次 preflight 即可发现规则。 */
        private Path nearestExistingDirectory(Path target) throws IOException {
            Path current = target;
            while (current != null && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                current = current.getParent();
            }
            if (current == null) throw new IOException("target has no existing directory");
            return current;
        }

        /** 从当前文件事实完整重建，不复用过期正文。 */
        private Snapshot rebuild() {
            List<NioInstructionSource.Document> documents = new ArrayList<>();
            List<String> diagnostics = new ArrayList<>();
            boolean unsafe = false;
            NioInstructionSource.Observation global = source.observe(
                    request.jaHome(), request.jaHome(), "$JA_HOME", -1);
            unsafe |= collect(global, documents, diagnostics);
            if (!request.trusted()) {
                return render(documents, diagnostics, unsafe);
            }
            try {
                Path workspace = request.workspaceRoot().toRealPath();
                Path projectBoundary = source.projectBoundary(workspace);
                List<Path> initial = source.chain(projectBoundary, workspace);
                for (int index = 0; index < initial.size(); index++) {
                    Path directory = initial.get(index);
                    String display = projectBoundary.relativize(directory).toString().replace('\\', '/');
                    if (display.isEmpty()) display = ".";
                    unsafe |= collect(source.observe(directory, projectBoundary, display, index),
                            documents, diagnostics);
                }
                int nestedBase = initial.size();
                for (String relative : nestedScopes) {
                    Path directory = workspace.resolve(relative).normalize();
                    if (!directory.startsWith(workspace)) {
                        diagnostics.add("AGENTS_PERSISTED_SCOPE_INVALID:" + bounded(relative));
                        unsafe = true;
                        continue;
                    }
                    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                    unsafe |= collect(source.observe(directory, workspace, relative,
                                    nestedBase + Path.of(relative).getNameCount()), documents, diagnostics);
                }
            } catch (IOException | RuntimeException failure) {
                diagnostics.add("AGENTS_INITIAL_DISCOVERY_FAILED");
                unsafe = true;
            }
            return render(documents, diagnostics, unsafe);
        }

        /** Observation 失败只加入有界诊断，正文从不进入日志或异常。 */
        private boolean collect(NioInstructionSource.Observation observation,
                                List<NioInstructionSource.Document> documents,
                                List<String> diagnostics) {
            if (observation.document() != null) documents.add(observation.document());
            if (observation.failed()) {
                diagnostics.add(bounded(observation.diagnostic()));
                return true;
            }
            return false;
        }
    }

    /** 新 scope 发现与容量拒绝必须同时返回，避免调用方在拒绝后错误刷新。 */
    private record DiscoveryResult(boolean discovered, boolean limitReached) {
    }

    /**
     * 广泛规则按 global、Git root、浅层目录顺序丢弃；最后一个最具体文件才执行 UTF-8 安全截断。
     */
    private static Snapshot render(List<NioInstructionSource.Document> sourceDocuments,
                                   List<String> sourceDiagnostics, boolean unsafe) {
        List<NioInstructionSource.Document> documents = sourceDocuments.stream()
                .sorted(Comparator.comparingInt(NioInstructionSource.Document::specificity)
                        .thenComparing(NioInstructionSource.Document::displayPath))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<String> diagnostics = sourceDiagnostics.stream().map(AgentInstructionCatalog::bounded)
                .limit(32).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        while (documents.size() > 1
                && totalEncodedLength(renderGuidance(documents), diagnostics) > GUIDANCE_BUDGET_BYTES) {
            NioInstructionSource.Document omitted = documents.removeFirst();
            diagnostics.add("AGENTS_BUDGET_OMITTED:" + bounded(omitted.displayPath()));
            diagnostics = capDiagnostics(diagnostics);
        }
        String guidance = renderGuidance(documents);
        if (totalEncodedLength(guidance, diagnostics) > GUIDANCE_BUDGET_BYTES && !documents.isEmpty()) {
            NioInstructionSource.Document mostSpecific = documents.getLast();
            diagnostics.add("AGENTS_BUDGET_TRUNCATED:" + bounded(mostSpecific.displayPath()));
            documents.set(documents.size() - 1, truncate(mostSpecific, documents, diagnostics));
            guidance = renderGuidance(documents);
        }
        if (totalEncodedLength(guidance, diagnostics) > GUIDANCE_BUDGET_BYTES) {
            int diagnosticsBytes = diagnostics.stream().mapToInt(AgentInstructionCatalog::encodedLength).sum();
            guidance = utf8Prefix(guidance, Math.max(0, GUIDANCE_BUDGET_BYTES - diagnosticsBytes));
        }
        String revision = revision(sourceDocuments, sourceDiagnostics, unsafe, guidance);
        return new Snapshot(guidance, revision, unsafe, diagnostics);
    }

    /** 计算最后文件可用正文空间；循环收敛避免多字节边界或截断诊断造成一字节越界。 */
    private static NioInstructionSource.Document truncate(NioInstructionSource.Document document,
            List<NioInstructionSource.Document> documents, List<String> diagnostics) {
        int available = GUIDANCE_BUDGET_BYTES;
        String marker = "\n[AGENTS content truncated]";
        NioInstructionSource.Document candidate = document;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<NioInstructionSource.Document> copy = new ArrayList<>(documents);
            copy.set(copy.size() - 1, new NioInstructionSource.Document(document.path(),
                    document.displayPath(), "", document.digest(), document.specificity()));
            int overhead = totalEncodedLength(renderGuidance(copy), diagnostics) + encodedLength(marker);
            available = Math.max(0, GUIDANCE_BUDGET_BYTES - overhead);
            candidate = new NioInstructionSource.Document(document.path(), document.displayPath(),
                    utf8Prefix(document.content(), available) + marker,
                    document.digest(), document.specificity());
            copy.set(copy.size() - 1, candidate);
            if (totalEncodedLength(renderGuidance(copy), diagnostics) <= GUIDANCE_BUDGET_BYTES) break;
        }
        return candidate;
    }

    /** guidance 只包含 AGENTS 文档；diagnostics 由 Prompt assembler 在动态 System 最后一节渲染。 */
    private static String renderGuidance(List<NioInstructionSource.Document> documents) {
        StringBuilder result = new StringBuilder();
        for (NioInstructionSource.Document document : documents) {
            if (!result.isEmpty()) result.append('\n');
            result.append("<agents path=\"").append(document.displayPath()).append("\">\n")
                    .append(document.content()).append("\n</agents>");
        }
        return result.toString();
    }

    /** revision 覆盖全部文件 digest 和安全事实，即使宽泛文件因预算未进入 guidance 也能清除 continuation。 */
    private static String revision(List<NioInstructionSource.Document> documents,
                                   List<String> diagnostics, boolean unsafe, String guidance) {
        StringBuilder canonical = new StringBuilder(Boolean.toString(unsafe)).append('\n');
        documents.stream().sorted(Comparator.comparing(NioInstructionSource.Document::displayPath))
                .forEach(document -> canonical.append(document.displayPath()).append('\0')
                        .append(document.digest()).append('\n'));
        diagnostics.forEach(value -> canonical.append(value).append('\n'));
        canonical.append(guidance);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** 在现有 snapshot 上追加一次安全失败，同时立即改变 revision。 */
    private static Snapshot withFailure(Snapshot current, String diagnostic) {
        List<String> diagnostics = new ArrayList<>(current.diagnostics());
        diagnostics.add(bounded(diagnostic));
        diagnostics = capDiagnostics(diagnostics);
        String guidance = current.guidance();
        int diagnosticsBytes = totalEncodedLength("", diagnostics);
        guidance = utf8Prefix(guidance, Math.max(0, GUIDANCE_BUDGET_BYTES - diagnosticsBytes));
        String revision = revision(List.of(), diagnostics, true, guidance);
        return new Snapshot(guidance, revision, true, diagnostics);
    }

    /** 诊断严格有界，避免异常路径反向挤占 AGENTS 正文预算。 */
    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "AGENTS_UNKNOWN_FAILURE";
        return value.length() <= 256 ? value : value.substring(0, 256);
    }

    /** 返回 UTF-8 安全前缀，绝不切断 surrogate pair 或多字节 code point。 */
    private static String utf8Prefix(String value, int byteLimit) {
        if (byteLimit <= 0 || value.isEmpty()) return "";
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(byteLimit, value.length()));
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            if (output.size() + encoded.length > byteLimit) break;
            output.writeBytes(encoded);
            index += Character.charCount(codePoint);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** guidance 预算按真实 UTF-8 bytes 而非 UTF-16 char 计量。 */
    private static int encodedLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** guidance 与独立 diagnostics 共享同一个 24KiB 指导配额，防止调用方后加诊断越界。 */
    private static int totalEncodedLength(String guidance, List<String> diagnostics) {
        return encodedLength(guidance)
                + diagnostics.stream().mapToInt(value -> encodedLength(value) + 1).sum();
    }

    /** 超过 32 条时保留最早安全根因和最新预算/发现结果，避免诊断本身无界增长。 */
    private static List<String> capDiagnostics(List<String> diagnostics) {
        if (diagnostics.size() <= 32) return new ArrayList<>(diagnostics);
        List<String> capped = new ArrayList<>(32);
        capped.addAll(diagnostics.subList(0, 16));
        capped.addAll(diagnostics.subList(diagnostics.size() - 16, diagnostics.size()));
        return capped;
    }
}
