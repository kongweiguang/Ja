// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.catalog.adapter.out.CatalogRevisionHasher;
import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 具备确定性优先级与快照绑定读取能力的 Ja Skill 目录。
 *
 * <p>Package Factory 负责文件系统与文档策略；本类只解析来源优先级、发布不可变快照，
 * 并确保面向 Turn 的读取路径不再访问磁盘。
 */
public final class JaSkillSources implements SkillCatalog {
    static final int MAX_SKILLS_PER_SOURCE = 128;
    static final int MAX_FILES_PER_SKILL = 64;
    static final int MAX_ENTRIES_PER_SKILL = 256;
    static final int MAX_DIRECTORY_DEPTH = 8;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_SKILL_DOCUMENT_BYTES = 64 * 1024;
    static final int MAX_TOTAL_BYTES = 4 * 1024 * 1024;
    static final int MAX_SKILL_CHARACTERS = 1_000_000;
    static final int MAX_SKILL_TOKENS = 250_000;

    private static final String WORKSPACE_SKILLS_PATH = ".agents/skills";
    private static final String SNAPSHOT_REVISION_PREFIX = "skills_";

    /* 弱键只在 Turn 仍持有快照期间保留过滤后的冻结字节。 */
    private final Map<SkillSnapshot, FrozenSnapshot> frozenSnapshots = new WeakHashMap<>();

    /**
     * 为每个新 Turn 重建完整目录；已返回快照仍绑定冻结字节，不受后续文件变化影响。
     *
     * <p>Turn Resolver 必须把返回值直接交给 {@link #select(SkillSnapshot, List)}，从类型上绑定
     * 同一个目录代际，不能跨 Turn 复用而遮蔽用户刚修改的 Skill。
     */
    @Override
    public SkillSnapshot snapshot(SnapshotRequest request) {
        Objects.requireNonNull(request, "request");
        return refresh(request);
    }

    /**
     * 重建一组显式根目录并原子发布。
     *
     * <p>刷新失败时保留上一个完整快照，防止 watcher 用半写入或损坏的 Skill 树覆盖健康目录。
     */
    public SkillSnapshot refresh(SnapshotRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            LinkedHashMap<String, SkillPackageFactory.FrozenSkill> resolved = new LinkedHashMap<>();
            merge(resolved, SkillPackageFactory.loadBuiltins());
            merge(resolved, SkillPackageFactory.loadFilesystemSource(
                    request.agentsSkillRoot(), Source.AGENTS_USER));
            merge(resolved, SkillPackageFactory.loadFilesystemSource(
                    request.jaSkillRoot(), Source.JA_USER));
            if (request.workspaceTrusted()) {
                for (Path root : workspaceSkillRoots(request.workspaceDirectory())) {
                    merge(resolved, SkillPackageFactory.loadFilesystemSource(root, Source.WORKSPACE));
                }
            }
            List<SkillPackageFactory.FrozenSkill> ordered = ordered(resolved.values());
            SkillSnapshot snapshot = new SkillSnapshot(
                    aggregateRevision(ordered),
                    ordered.stream().map(SkillPackageFactory.FrozenSkill::descriptor).toList(), Instant.now());
            synchronized (frozenSnapshots) {
                frozenSnapshots.put(snapshot, new FrozenSnapshot(indexByName(ordered)));
            }
            return snapshot;
        } catch (IOException exception) {
            throw new UncheckedIOException("skill_snapshot_io_failed", exception);
        }
    }

    /**
     * 不扫描目录地创建已注册空快照，避免禁用全部 Skill 时让无关损坏来源阻断 Turn。
     */
    @Override
    public SkillSnapshot emptySnapshot() {
        SkillSnapshot empty = new SkillSnapshot(
                aggregateRevision(List.of()), List.of(), Instant.now());
        synchronized (frozenSnapshots) {
            frozenSnapshots.put(empty, new FrozenSnapshot(Map.of()));
        }
        return empty;
    }

    /**
     * 在调用方持有的精确完整快照上筛选已配置 revisions，避免并发 Turn 刷新造成代际串换。
     *
     * <p>被更高优先级同名包遮蔽的版本不可选择；空选择明确表示不暴露 Skill，禁止回退到其它来源层。
     */
    @Override
    public SkillSnapshot select(SkillSnapshot snapshot, List<String> allowedRevisions) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(allowedRevisions, "allowedRevisions");
        if (allowedRevisions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("skill_revision_selection_contains_null");
        }
        if (allowedRevisions.isEmpty()) {
            return emptySnapshot();
        }
        FrozenSnapshot completeFrozen;
        synchronized (frozenSnapshots) {
            completeFrozen = frozenSnapshots.get(snapshot);
        }
        if (completeFrozen == null) {
            throw new IllegalStateException("skill_complete_snapshot_missing");
        }
        Set<String> selectedRevisions = Set.copyOf(allowedRevisions);
        Set<String> availableRevisions = completeFrozen.skills().values().stream()
                .map(SkillPackageFactory.FrozenSkill::revision)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!availableRevisions.containsAll(selectedRevisions)) {
            throw new IllegalArgumentException("skill_revision_unavailable");
        }
        List<SkillPackageFactory.FrozenSkill> selected = completeFrozen.skills().values().stream()
                .filter(skill -> selectedRevisions.contains(skill.revision()))
                .toList();
        selected = ordered(selected);
        SkillSnapshot filtered = new SkillSnapshot(
                aggregateRevision(selected),
                selected.stream().map(SkillPackageFactory.FrozenSkill::descriptor).toList(), Instant.now());
        synchronized (frozenSnapshots) {
            frozenSnapshots.put(filtered, new FrozenSnapshot(indexByName(selected)));
        }
        return filtered;
    }

    /**
     * 从快照所有的不可变 Map 中读取一个文档。
     *
     * <p>此处禁止文件系统查询，因此编辑、链接替换、过期或重建快照都不能改变 Turn 准入后的模型可见字节。
     */
    @Override
    public SkillDocument read(SkillSnapshot snapshot, SkillReadRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        FrozenSnapshot frozen;
        synchronized (frozenSnapshots) {
            frozen = frozenSnapshots.get(snapshot);
        }
        if (frozen == null) {
            throw new IllegalArgumentException("skill_snapshot_unknown");
        }
        SkillPackageFactory.FrozenSkill skill = frozen.skills().get(request.skillName());
        if (skill == null) {
            throw new IllegalArgumentException("skill_not_in_snapshot");
        }
        String path = normalizeResourcePath(request.resourcePath());
        String content = skill.documents().get(path);
        if (content == null) {
            throw new IllegalArgumentException("skill_resource_not_in_snapshot");
        }
        boolean truncated = content.length() > request.maxCharacters();
        String visible = truncated ? content.substring(0, request.maxCharacters()) : content;
        return new SkillDocument(skill.name(), path, skill.revision(), visible, truncated);
    }

    /**
     * 独立于平台文件系统规范化规则校验冻结 Map 的键。
     *
     * <p>读取请求只允许斜杠分隔的逻辑名称；拒绝别名可防止同一文档形成多个缓存或授权身份。
     */
    private static String normalizeResourcePath(String raw) {
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.endsWith("/") || normalized.contains("//")) {
            throw new IllegalArgumentException("skill_resource_path_invalid");
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("skill_resource_path_invalid");
            }
        }
        return normalized;
    }

    /**
     * 高优先级包整体替换低优先级包，禁止不同版本的资源混合。
     */
    private static void merge(
            Map<String, SkillPackageFactory.FrozenSkill> target,
            List<SkillPackageFactory.FrozenSkill> source) {
        for (SkillPackageFactory.FrozenSkill skill : source) {
            target.put(skill.name(), skill);
        }
    }

    /**
     * Catalog 始终先展示高优先级来源，再按名称稳定排序；排序只影响投影，不改变覆盖决策。
     */
    private static List<SkillPackageFactory.FrozenSkill> ordered(
            Collection<SkillPackageFactory.FrozenSkill> skills) {
        return skills.stream()
                .sorted(Comparator
                        .comparingInt((SkillPackageFactory.FrozenSkill skill) ->
                                skill.descriptor().source().priority())
                        .reversed()
                        .thenComparing(SkillPackageFactory.FrozenSkill::name))
                .toList();
    }

    /**
     * 从最近 Git 根到 cwd 生成逐层工作区来源；找不到 Git 根时只检查 cwd。
     *
     * <p>调用方已经冻结信任，本函数仍只构造语法路径，真正存在的目录会由文件 Adapter
     * 执行 realpath、链接和重解析点校验，避免发现阶段形成第二套安全策略。
     */
    private static List<Path> workspaceSkillRoots(Path workspaceDirectory) throws IOException {
        Path cwd = workspaceDirectory.toAbsolutePath().normalize();
        Path gitRoot = nearestGitRoot(cwd);
        if (gitRoot == null) {
            return List.of(cwd.resolve(WORKSPACE_SKILLS_PATH));
        }
        List<Path> roots = new ArrayList<>();
        Path current = gitRoot;
        while (current != null && cwd.startsWith(current)) {
            roots.add(current.resolve(WORKSPACE_SKILLS_PATH));
            if (current.equals(cwd)) {
                break;
            }
            Path relative = current.relativize(cwd);
            current = current.resolve(relative.getName(0));
        }
        return List.copyOf(roots);
    }

    /**
     * 只把精确 `.git` 文件或目录视作仓库标记，以同时支持普通仓库和 Git worktree。
     */
    private static Path nearestGitRoot(Path cwd) throws IOException {
        Path current = cwd;
        while (current != null) {
            Path marker = current.resolve(".git");
            if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("skill_git_marker_invalid");
                }
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    /**
     * 在聚合快照命名空间中散列已解析的来源、名称与版本元组。
     *
     * <p>聚合值标识一个完成解析的目录代际，因此必须与各包的不透明 descriptor revision 区分。
     */
    private static String aggregateRevision(List<SkillPackageFactory.FrozenSkill> skills) {
        CatalogRevisionHasher digest = new CatalogRevisionHasher("ja-skill-snapshot-v1");
        for (SkillPackageFactory.FrozenSkill skill : skills) {
            digest.append(skill.name())
                    .append(skill.descriptor().source().name().toLowerCase(Locale.ROOT))
                    .append(skill.revision());
        }
        return SNAPSHOT_REVISION_PREFIX + digest.finish();
    }

    /**
     * 来源优先级解析完成后构建不可变索引，避免后续读取重新决策。
     */
    private static Map<String, SkillPackageFactory.FrozenSkill> indexByName(
            List<SkillPackageFactory.FrozenSkill> skills) {
        LinkedHashMap<String, SkillPackageFactory.FrozenSkill> index = new LinkedHashMap<>();
        for (SkillPackageFactory.FrozenSkill skill : skills) {
            index.put(skill.name(), skill);
        }
        return Map.copyOf(index);
    }

    /**
     * 将完成优先级解析的 Skill 索引绑定到快照身份，Turn 读取期间不再访问文件系统。
     */
    private record FrozenSnapshot(Map<String, SkillPackageFactory.FrozenSkill> skills) {
    }

}
