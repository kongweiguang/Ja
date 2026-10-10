// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.skills.local.infrastructure;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 将宿主显式提供的目录或内置资源适配为 Skill 来源；路径解析留给请求属性，优先级与激活仍由 Core 统一管理。
 */
public final class LocalSkillSource implements SkillSource {
    static final int MAX_SKILLS_PER_SOURCE = 128;
    static final int MAX_DIRECTORY_DEPTH = 8;
    static final int MAX_FILE_BYTES = 1024 * 1024;
    static final int MAX_SKILL_DOCUMENT_BYTES = 64 * 1024;
    static final int MAX_SKILL_CHARACTERS = 1_000_000;
    static final int MAX_SKILL_TOKENS = 250_000;

    private static final String WORKSPACE_SKILLS_PATH = ".agents/skills";
    private static final int MAX_PAGE_SIZE = 4_096;

    private final String id;
    private final int priority;
    private final RootResolver roots;
    private final boolean bundled;
    private final List<PageSnapshot> pageSnapshots = new ArrayList<>();
    private final List<DescriptorRegistration> descriptorRegistrations = new ArrayList<>();

    /**
     * 创建固定的内置 Skill 来源；内置清单显式列出资源，避免 classpath 扫描把任意文件变成能力。
     */
    public static LocalSkillSource bundled() {
        return new LocalSkillSource("bundled", 0, ignored -> List.of(), true);
    }

    /**
     * 创建单目录来源；每次发现都从请求属性取绝对路径，避免复用实例时残留上一次租户的目录。
     */
    public static LocalSkillSource directory(String sourceId, int priority, String rootAttributeKey) {
        String key = requireAttributeKey(rootAttributeKey);
        return new LocalSkillSource(sourceId, priority,
                context -> List.of(absolutePath(context.requireAttribute(key))), false);
    }

    /**
     * 创建工作区来源；仅宿主标记为可信时，才按 Git 根到当前目录顺序发现逐级覆盖的 Skill。
     */
    public static LocalSkillSource workspace(String sourceId, int priority,
                                               String workspaceAttributeKey, String trustedAttributeKey) {
        String workspaceKey = requireAttributeKey(workspaceAttributeKey);
        String trustedKey = requireAttributeKey(trustedAttributeKey);
        return new LocalSkillSource(sourceId, priority, context -> {
            String trustedValue = context.requireAttribute(trustedKey);
            if (!trustedValue.equals("true") && !trustedValue.equals("false")) {
                throw new IllegalArgumentException("invalid_skill_workspace_trust_value");
            }
            if (!Boolean.parseBoolean(trustedValue)) {
                return List.of();
            }
            return workspaceSkillRoots(absolutePath(context.requireAttribute(workspaceKey)));
        }, false);
    }

    /**
     * 只保存来源身份和路径解析函数；请求路径不得变成进程级默认值或在租户之间共享。
     */
    private LocalSkillSource(String id, int priority, RootResolver roots, boolean bundled) {
        if (id == null || !id.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || priority < 0) {
            throw new IllegalArgumentException("invalid_skill_source_identity");
        }
        this.id = id;
        this.priority = priority;
        this.roots = Objects.requireNonNull(roots, "roots");
        this.bundled = bundled;
    }

    /**
     * 返回 Core 用于显式注册、来源隔离和引用稳定性的标识。
     */
    @Override
    public String id() {
        return id;
    }

    /**
     * 返回宿主配置的覆盖优先级；不通过目录名称或枚举顺序暗中推导覆盖规则。
     */
    @Override
    public int priority() {
        return priority;
    }

    /**
     * 只解析发现所需的 frontmatter，并将分页绑定到同一请求上下文快照，正文仍按需读取。
     */
    @Override
    public SkillPage discover(SkillRequestContext context, String cursor, int pageSize) {
        Objects.requireNonNull(context, "context");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("invalid_skill_page_size");
        }
        try {
            if (cursor == null) {
                return firstPage(context, pageSize, discoverSources(context));
            }
            return followingPage(context, cursor, pageSize);
        } catch (IOException exception) {
            throw new UncheckedIOException("skill_discovery_io_failed", exception);
        }
    }

    /**
     * 只读取本次上下文发现时登记的精确定位，并在打开资源时再次校验路径、大小和编码。
     */
    @Override
    public SkillResourceContent read(SkillRequestContext context, SkillDescriptor descriptor,
                                     SkillReadRequest request) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(request, "request");
        if (!descriptor.sourceId().equals(id) || !request.skillName().equals(descriptor.name())) {
            throw new IllegalArgumentException("skill_source_identity_mismatch:" + id);
        }
        SkillPackageFactory.LocatedSkill located = locator(context, descriptor)
                .orElseThrow(() -> new IllegalArgumentException("skill_descriptor_unknown"));
        String path = normalizeResourcePath(request.resourcePath());
        try {
            SkillPackageFactory.ResourceContent content = SkillPackageFactory.readResource(
                    located, path, request.maxCharacters());
            return new SkillResourceContent(content.content(), content.truncated());
        } catch (IOException exception) {
            throw new UncheckedIOException("skill_read_io_failed", exception);
        }
    }

    /**
     * 按配置顺序合并根目录；同一来源内后出现的同名项覆盖前项，以保留工作区逐级继承规则。
     */
    private List<SkillPackageFactory.LocatedSkill> discoverSources(SkillRequestContext context) throws IOException {
        if (bundled) {
            return SkillPackageFactory.discoverBuiltins(id);
        }
        LinkedHashMap<String, SkillPackageFactory.LocatedSkill> resolved = new LinkedHashMap<>();
        for (Path root : roots.resolve(context)) {
            for (SkillPackageFactory.LocatedSkill skill : SkillPackageFactory.discoverFilesystemSource(root, id)) {
                resolved.put(skill.name(), skill);
            }
        }
        return ordered(resolved.values());
    }

    /**
     * 只有条目超过页长才保存分页快照；游标使用不透明随机令牌，不暴露文件路径。
     */
    private SkillPage firstPage(SkillRequestContext context, int pageSize,
                                List<SkillPackageFactory.LocatedSkill> discovered) {
        List<SkillPackageFactory.LocatedSkill> snapshot = ordered(discovered);
        if (snapshot.size() <= pageSize) {
            registerDescriptors(context, snapshot);
            return new SkillPage(descriptors(snapshot), null);
        }
        String token = UUID.randomUUID().toString();
        synchronized (pageSnapshots) {
            purgeSnapshots();
            pageSnapshots.add(new PageSnapshot(token, new WeakReference<>(context), snapshot));
        }
        int end = pageSize;
        registerDescriptors(context, snapshot.subList(0, end));
        return new SkillPage(descriptors(snapshot.subList(0, end)), token + ":" + end);
    }

    /**
     * 从原始发现快照继续分页，并校验上下文对象身份；末页读完即释放快照以限制缓存寿命。
     */
    private SkillPage followingPage(SkillRequestContext context, String cursor, int pageSize) {
        String[] parts = cursor.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("skill_source_cursor_invalid");
        }
        int offset;
        try {
            offset = Integer.parseInt(parts[1]);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("skill_source_cursor_invalid", invalid);
        }
        synchronized (pageSnapshots) {
            purgeSnapshots();
            PageSnapshot snapshot = pageSnapshots.stream()
                    .filter(item -> item.token().equals(parts[0]))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("skill_source_cursor_unknown"));
            if (snapshot.context().get() != context || offset < 1 || offset >= snapshot.skills().size()) {
                throw new IllegalArgumentException("skill_source_cursor_scope_mismatch");
            }
            int end = Math.min(snapshot.skills().size(), Math.addExact(offset, pageSize));
            List<SkillPackageFactory.LocatedSkill> page = snapshot.skills().subList(offset, end);
            registerDescriptors(context, page);
            String next = end == snapshot.skills().size() ? null : snapshot.token() + ":" + end;
            if (next == null) {
                pageSnapshots.remove(snapshot);
            }
            return new SkillPage(descriptors(page), next);
        }
    }

    /**
     * 将资源定位绑定到弱持有的请求上下文，避免长生命周期来源对象延长单次请求的路径和属性生命周期。
     */
    private void registerDescriptors(SkillRequestContext context,
                                     List<SkillPackageFactory.LocatedSkill> skills) {
        List<DescriptorRegistration> registrations = skills.stream()
                .map(skill -> new DescriptorRegistration(new WeakReference<>(context), skill))
                .toList();
        synchronized (descriptorRegistrations) {
            descriptorRegistrations.removeIf(item -> item.context().get() == null);
            descriptorRegistrations.addAll(registrations);
        }
    }

    /**
     * 同时比对描述符对象和上下文对象，阻止伪造引用或跨租户、跨目录复用已发现的定位。
     */
    private Optional<SkillPackageFactory.LocatedSkill> locator(
            SkillRequestContext context, SkillDescriptor descriptor) {
        synchronized (descriptorRegistrations) {
            descriptorRegistrations.removeIf(item -> item.context().get() == null);
            return descriptorRegistrations.stream()
                    .filter(item -> item.skill().descriptor() == descriptor && item.context().get() == context)
                    .map(DescriptorRegistration::skill)
                    .findFirst();
        }
    }

    /**
     * 按当前稳定页序返回已验证描述符，不在发现阶段读取或复制 Skill 正文。
     */
    private static List<SkillDescriptor> descriptors(List<SkillPackageFactory.LocatedSkill> skills) {
        return skills.stream().map(SkillPackageFactory.LocatedSkill::descriptor).toList();
    }

    /**
     * 清除请求上下文已释放的分页快照；此缓存只保存元数据定位，不缓存正文。
     */
    private void purgeSnapshots() {
        pageSnapshots.removeIf(snapshot -> snapshot.context().get() == null);
    }

    /**
     * 将资源名规范为跨平台逻辑路径，并拒绝绝对路径、空段和点段别名以阻断目录逃逸。
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
     * 按来源内名称排序，保证重复发现和分页顺序稳定，不让文件系统枚举顺序影响结果。
     */
    private static List<SkillPackageFactory.LocatedSkill> ordered(
            Collection<SkillPackageFactory.LocatedSkill> skills) {
        return skills.stream().sorted(Comparator.comparing(SkillPackageFactory.LocatedSkill::name)).toList();
    }

    /**
     * 从最近 Git 根沿工作区路径逐级计算 Skill 根目录，保持上层配置可被更近目录覆盖。
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
     * 只接受真实 `.git` 目录或 worktree 文件标记；遇到异常标记立即失败，不猜测仓库边界。
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
     * 在构造可复用来源前校验请求属性键，避免后续以任意键名读取宿主上下文。
     */
    private static String requireAttributeKey(String key) {
        if (key == null || !key.matches("[a-z][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException("invalid_skill_source_attribute_key");
        }
        return key;
    }

    /**
     * 将宿主路径规范化为绝对路径；缺失或相对路径都是配置错误，不能静默解释成空来源。
     */
    private static Path absolutePath(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Path.of(value).isAbsolute()) {
                throw new IllegalArgumentException("skill_source_path_must_be_absolute");
            }
            return path;
        } catch (InvalidPathException invalid) {
            throw new IllegalArgumentException("skill_source_path_invalid", invalid);
        }
    }

    /** 由宿主属性解析本次请求根目录；接口刻意不持有 Ja 配置对象或进程默认路径。 */
    @FunctionalInterface
    private interface RootResolver {
        /** 解析当前请求允许访问的根目录，并将文件系统错误原样交给来源边界处理。 */
        List<Path> resolve(SkillRequestContext context) throws IOException;
    }

    /**
     * 保存一个上下文限定、只供后续分页使用的有界发现快照。
     *
     * @param token 令牌
     * @param context 当前操作的上下文
     * @param skills Skill 列表
     */
    private record PageSnapshot(String token,
                                WeakReference<SkillRequestContext> context,
                                List<SkillPackageFactory.LocatedSkill> skills) {
    }

    /**
     * 将短生命周期描述符身份绑定到对应请求的文件定位，防止重建或跨上下文使用。
     *
     * @param context 当前操作的弱引用上下文
     * @param skill Skill 信息
     */
    private record DescriptorRegistration(WeakReference<SkillRequestContext> context,
                                          SkillPackageFactory.LocatedSkill skill) {
    }
}
