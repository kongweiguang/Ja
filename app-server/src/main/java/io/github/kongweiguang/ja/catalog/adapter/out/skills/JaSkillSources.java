// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.catalog.adapter.out.skills;

import io.github.kongweiguang.ja.conversation.port.out.SkillCatalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 具备确定性来源优先级与实时资源读取能力的 Ja Skill 目录。
 *
 * <p>Catalog 只发布发现时的 name、description、source，并以弱身份注册表绑定原始 locator；
 * SKILL.md 正文和辅助资源均在 read 时重新从 classpath 或文件系统读取。
 */
public final class JaSkillSources implements SkillCatalog {
    static final int MAX_SKILLS_PER_SOURCE = 128;
    static final int MAX_DIRECTORY_DEPTH = 8;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_SKILL_DOCUMENT_BYTES = 64 * 1024;
    static final int MAX_SKILL_CHARACTERS = 1_000_000;
    static final int MAX_SKILL_TOKENS = 250_000;

    private static final String WORKSPACE_SKILLS_PATH = ".agents/skills";

    /*
     * Catalog 是 value record，普通 WeakHashMap 会让相同元数据的不同根目录错误共享 locator；
     * 因此这里显式采用弱引用 + identity 比较，只保留定位元数据且随 Catalog 回收。
     */
    private final List<CatalogRegistration> catalogRegistrations = new ArrayList<>();

    /**
     * 为每次请求重新发现来源元数据；发现结果稳定，但后续 read 不绑定内容代际。
     */
    @Override
    public Catalog discover(DiscoveryRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            LinkedHashMap<String, SkillPackageFactory.LocatedSkill> resolved = new LinkedHashMap<>();
            merge(resolved, SkillPackageFactory.discoverBuiltins());
            merge(resolved, SkillPackageFactory.discoverFilesystemSource(
                    request.agentsSkillRoot(), Source.AGENTS_USER));
            merge(resolved, SkillPackageFactory.discoverFilesystemSource(
                    request.jaSkillRoot(), Source.JA_USER));
            if (request.workspaceTrusted()) {
                for (Path root : workspaceSkillRoots(request.workspaceDirectory())) {
                    merge(resolved, SkillPackageFactory.discoverFilesystemSource(root, Source.WORKSPACE));
                }
            }
            List<SkillPackageFactory.LocatedSkill> ordered = ordered(resolved.values());
            Catalog catalog = new Catalog(ordered.stream()
                    .map(SkillPackageFactory.LocatedSkill::descriptor).toList());
            register(catalog, indexByName(ordered));
            return catalog;
        } catch (IOException exception) {
            throw new UncheckedIOException("skill_discovery_io_failed", exception);
        }
    }

    /**
     * 不扫描目录地创建已注册空目录，避免禁用全部 Skill 时让无关损坏来源阻断 Turn。
     */
    @Override
    public Catalog emptyCatalog() {
        Catalog empty = new Catalog(List.of());
        register(empty, Map.of());
        return empty;
    }

    /**
     * 在调用方持有的精确 Catalog 身份上按名称筛选，不重新发现来源或隐式回退到被遮蔽版本。
     */
    @Override
    public Catalog select(Catalog catalog, List<String> allowedNames) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(allowedNames, "allowedNames");
        if (allowedNames.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("skill_name_selection_contains_null");
        }
        Map<String, SkillPackageFactory.LocatedSkill> complete = locations(catalog)
                .orElseThrow(() -> new IllegalArgumentException("skill_catalog_unknown"));
        if (allowedNames.isEmpty()) {
            return emptyCatalog();
        }
        Set<String> selectedNames = Set.copyOf(allowedNames);
        if (!complete.keySet().containsAll(selectedNames)) {
            throw new IllegalArgumentException("skill_name_unavailable");
        }
        List<SkillPackageFactory.LocatedSkill> selected = complete.values().stream()
                .filter(skill -> selectedNames.contains(skill.name()))
                .toList();
        selected = ordered(selected);
        Catalog filtered = new Catalog(selected.stream()
                .map(SkillPackageFactory.LocatedSkill::descriptor).toList());
        register(filtered, indexByName(selected));
        return filtered;
    }

    /**
     * 从 Catalog 绑定的 locator 实时读取资源；每次调用都重新执行 Factory 的文件安全与编码检查。
     */
    @Override
    public SkillDocument read(Catalog catalog, SkillReadRequest request) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(request, "request");
        Map<String, SkillPackageFactory.LocatedSkill> available = locations(catalog)
                .orElseThrow(() -> new IllegalArgumentException("skill_catalog_unknown"));
        SkillPackageFactory.LocatedSkill skill = available.get(request.skillName());
        if (skill == null) {
            throw new IllegalArgumentException("skill_not_in_catalog");
        }
        String resourcePath = normalizeResourcePath(request.resourcePath());
        try {
            SkillPackageFactory.ResourceContent result = SkillPackageFactory.readResource(
                    skill, resourcePath, request.maxCharacters());
            return new SkillDocument(skill.name(), resourcePath, result.content(), result.truncated());
        } catch (IOException exception) {
            throw new UncheckedIOException("skill_read_io_failed", exception);
        }
    }

    /**
     * 独立于平台文件系统规则规范化逻辑资源名，拒绝别名、空段、父级与绝对路径。
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
        try {
            if (Path.of(normalized.replace('/', java.io.File.separatorChar)).isAbsolute()) {
                throw new IllegalArgumentException("skill_resource_path_invalid");
            }
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("skill_resource_path_invalid", invalid);
        }
        return normalized;
    }

    /**
     * 高优先级包整体替换低优先级 locator，禁止同名来源间混用元数据与资源目录。
     */
    private static void merge(
            Map<String, SkillPackageFactory.LocatedSkill> target,
            List<SkillPackageFactory.LocatedSkill> source) {
        for (SkillPackageFactory.LocatedSkill skill : source) {
            target.put(skill.name(), skill);
        }
    }

    /**
     * Catalog 先展示高优先级来源，再按名称稳定排序；排序只影响投影，不改变覆盖决策。
     */
    private static List<SkillPackageFactory.LocatedSkill> ordered(
            Collection<SkillPackageFactory.LocatedSkill> skills) {
        return skills.stream()
                .sorted(Comparator
                        .comparingInt((SkillPackageFactory.LocatedSkill skill) ->
                                skill.descriptor().source().priority())
                        .reversed()
                        .thenComparing(SkillPackageFactory.LocatedSkill::name))
                .toList();
    }

    /**
     * 从最近 Git 根到 cwd 生成逐层工作区来源；找不到 Git 根时只检查 cwd。
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
     * 只把精确 .git 文件或目录视作仓库标记，同时支持普通仓库和 Git worktree。
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
     * 来源优先级解析完成后构建不可变 locator 索引，正文不会进入注册表。
     */
    private static Map<String, SkillPackageFactory.LocatedSkill> indexByName(
            List<SkillPackageFactory.LocatedSkill> skills) {
        LinkedHashMap<String, SkillPackageFactory.LocatedSkill> index = new LinkedHashMap<>();
        for (SkillPackageFactory.LocatedSkill skill : skills) {
            index.put(skill.name(), skill);
        }
        return Map.copyOf(index);
    }

    /**
     * 以 Catalog 对象身份注册 locator，避免 record 值相同但来源根不同的目录发生串换。
     */
    private void register(Catalog catalog, Map<String, SkillPackageFactory.LocatedSkill> locations) {
        synchronized (catalogRegistrations) {
            purgeCollectedCatalogs();
            catalogRegistrations.add(new CatalogRegistration(
                    new WeakReference<>(catalog), Map.copyOf(locations)));
        }
    }

    /**
     * 只按对象身份查找注册结果；伪造的同值 Catalog 不具备任何文件读取能力。
     */
    private Optional<Map<String, SkillPackageFactory.LocatedSkill>> locations(Catalog catalog) {
        synchronized (catalogRegistrations) {
            purgeCollectedCatalogs();
            for (CatalogRegistration registration : catalogRegistrations) {
                if (registration.catalog().get() == catalog) {
                    return Optional.of(registration.locations());
                }
            }
            return Optional.empty();
        }
    }

    /**
     * 在每次目录操作时清除已回收弱键，避免长进程按历史 Turn 数量累积 locator。
     */
    private void purgeCollectedCatalogs() {
        catalogRegistrations.removeIf(registration -> registration.catalog().get() == null);
    }

    /**
     * 弱注册项只保存 Catalog identity 与资源 locator，不保存任何正文、摘要或内容 revision。
     */
    private record CatalogRegistration(
            WeakReference<Catalog> catalog,
            Map<String, SkillPackageFactory.LocatedSkill> locations) {
    }
}
