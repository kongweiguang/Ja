// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.skill;

import io.github.kongweiguang.ja.agent.domain.skill.SkillDescriptor;
import io.github.kongweiguang.ja.agent.domain.skill.SkillDocument;
import io.github.kongweiguang.ja.agent.domain.skill.SkillPage;
import io.github.kongweiguang.ja.agent.domain.skill.SkillReadRequest;
import io.github.kongweiguang.ja.agent.domain.skill.SkillRequestContext;
import io.github.kongweiguang.ja.agent.domain.skill.SkillResourceContent;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;
import io.github.kongweiguang.ja.agent.shared.Hashing;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 按冻结的来源优先级解析同名 Skill，并把读取能力绑定到租户及发现快照，避免调用方伪造来源或借用其他请求的目录。
 */
public final class SkillCatalog {
    private static final int PAGE_SIZE = 256;

    private final List<RegisteredSource> sources;
    private final List<CatalogRegistration> registrations = new ArrayList<>();

    /**
     * 冻结显式注册顺序、来源身份和优先级；同优先级冲突由后注册来源胜出，避免每次发现结果随扩展对象状态漂移。
     */
    public SkillCatalog(List<SkillSource> sources) {
        List<SkillSource> requested = List.copyOf(Objects.requireNonNull(sources, "sources"));
        List<RegisteredSource> registered = new ArrayList<>(requested.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < requested.size(); index++) {
            SkillSource source = requested.get(index);
            Objects.requireNonNull(source, "source");
            requireSourceIdentity(source);
            if (!ids.add(source.id())) {
                throw new IllegalArgumentException("duplicate_skill_source_id:" + source.id());
            }
            registered.add(new RegisteredSource(source, source.id(), source.priority(), index));
        }
        this.sources = List.copyOf(registered);
    }

    /**
     * 以固定页大小消费来源的不透明游标直至结束；重复名称或不前进的游标立即失败，避免覆盖数据和无限循环。
     */
    private static List<SkillDescriptor> discoverAllPages(RegisteredSource source, SkillRequestContext context) {
        List<SkillDescriptor> result = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> names = new HashSet<>();
        String cursor = null;
        while (true) {
            SkillPage page = Objects.requireNonNull(source.source().discover(context, cursor, PAGE_SIZE),
                    "skill source returned null page");
            for (SkillDescriptor descriptor : page.skills()) {
                if (!names.add(descriptor.name())) {
                    throw new IllegalStateException("duplicate_skill_name_in_source:" + source.id());
                }
                result.add(descriptor);
            }
            String next = page.nextCursor();
            if (next == null) {
                return List.copyOf(result);
            }
            if (!cursors.add(next) || next.equals(cursor)) {
                throw new IllegalStateException("skill_source_cursor_loop:" + source.id());
            }
            cursor = next;
        }
    }

    /**
     * 在注册时校验来源 ID 的稳定格式和非负优先级，避免非法命名进入持久引用及覆盖排序规则。
     */
    private static void requireSourceIdentity(SkillSource source) {
        String id = source.id();
        ContractChecks.skillSourceIdentifier(id);
        if (source.priority() < 0) {
            throw new IllegalArgumentException("invalid_skill_source_identity");
        }
    }

    /**
     * 对实际暴露给模型或激活流程的 UTF-8 原文计算 SHA-256，指纹不包含未返回内容或来源本地路径。
     */
    private static String fingerprint(String content) {
        return "sha256:" + Hashing.sha256Hex(content);
    }

    /**
     * 分页发现全部来源，先剔除禁用引用再按优先级选出同名赢家，并为结果建立不可伪造的读取快照。
     * 禁用高优先级副本后，下层来源可以接管同名 Skill；已选中的赢家不会在后续读取时重新解析。
     */
    public Catalog discover(SkillRequestContext context, Set<String> disabledReferences) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(disabledReferences, "disabledReferences");
        List<Binding> candidates = new ArrayList<>();
        for (RegisteredSource source : sources) {
            for (SkillDescriptor descriptor : discoverAllPages(source, context)) {
                if (!descriptor.sourceId().equals(source.id())) {
                    throw new IllegalStateException("skill_source_identity_mismatch:" + source.id());
                }
                if (!disabledReferences.contains(descriptor.reference())) {
                    candidates.add(new Binding(source, descriptor));
                }
            }
        }
        Map<String, Binding> winners = new LinkedHashMap<>();
        for (Binding candidate : ordered(candidates)) {
            winners.putIfAbsent(candidate.descriptor().name(), candidate);
        }
        List<Binding> ordered = ordered(winners.values());
        return register(ordered, context);
    }

    /**
     * 返回各来源发现的全部副本供设置页或管理操作展示，不建立运行时读取能力，也不改变同名覆盖规则。
     */
    public List<SkillDescriptor> discoverAll(SkillRequestContext context) {
        Objects.requireNonNull(context, "context");
        List<Binding> discovered = new ArrayList<>();
        for (RegisteredSource source : sources) {
            for (SkillDescriptor descriptor : discoverAllPages(source, context)) {
                if (!descriptor.sourceId().equals(source.id())) {
                    throw new IllegalStateException("skill_source_identity_mismatch:" + source.id());
                }
                discovered.add(new Binding(source, descriptor));
            }
        }
        return ordered(discovered).stream().map(Binding::descriptor).toList();
    }

    /**
     * 为宿主关闭 Skill 能力的请求生成真实空快照，使“没有 Skill”与未执行发现操作保持可区分。
     */
    public Catalog emptyCatalog(SkillRequestContext context) {
        return register(List.of(), Objects.requireNonNull(context, "context"));
    }

    /**
     * 仅从原发现快照裁剪名称，不重新扫描来源，避免过滤过程中读到新版本或暴露已被覆盖的低优先级副本。
     */
    public Catalog select(Catalog catalog, List<String> allowedNames) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(allowedNames, "allowedNames");
        CatalogRegistration registration = registration(catalog)
                .orElseThrow(() -> new IllegalArgumentException("skill_catalog_unknown"));
        Map<String, Binding> bindings = registration.bindings();
        if (allowedNames.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("skill_name_selection_contains_null");
        }
        Set<String> selectedNames = Set.copyOf(allowedNames);
        if (!bindings.keySet().containsAll(selectedNames)) {
            throw new IllegalArgumentException("skill_name_unavailable");
        }
        return register(bindings.values().stream()
                .filter(binding -> selectedNames.contains(binding.descriptor().name())).toList(), registration.context());
    }

    /**
     * 只读取快照中已选中的 Skill，沿用发现时租户和来源绑定，并对精确返回文本计算内容指纹供执行事实记录。
     * 来源仍须在自己的存储查询中强制租户隔离；目录引用本身不是授权凭据。
     */
    public SkillDocument read(Catalog catalog, SkillReadRequest request) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(request, "request");
        CatalogRegistration registration = registration(catalog)
                .orElseThrow(() -> new IllegalArgumentException("skill_catalog_unknown"));
        Binding binding = registration.bindings().get(request.skillName());
        if (binding == null) {
            throw new IllegalArgumentException("skill_not_in_catalog");
        }
        SkillResourceContent resource = Objects.requireNonNull(
                binding.source().source().read(registration.context(), binding.descriptor(), request),
                "skill source returned null");
        if (resource.content().length() > request.maxCharacters()) {
            throw new IllegalStateException("skill_source_exceeded_character_limit");
        }
        return new SkillDocument(request.skillName(), request.resourcePath(), binding.source().id(),
                binding.descriptor().version(), resource.content(), resource.truncated(), fingerprint(resource.content()));
    }

    /**
     * 按冻结的数值优先级降序排列；优先级相同时后注册者先出现，名称排序只用于稳定无冲突结果的顺序。
     */
    private List<Binding> ordered(Collection<Binding> bindings) {
        return bindings.stream().sorted(Comparator
                .comparingInt((Binding value) -> value.source().priority()).reversed()
                .thenComparing(Comparator.comparingInt((Binding value) -> value.source().order()).reversed())
                .thenComparing(value -> value.descriptor().name())).toList();
    }

    /**
     * 创建只公开元数据的新目录，并把真实来源绑定保存在私有注册表中；调用方无法通过构造同值目录取得读取能力。
     */
    private Catalog register(Collection<Binding> bindings, SkillRequestContext context) {
        List<Binding> ordered = ordered(bindings);
        Catalog catalog = new Catalog(ordered.stream().map(Binding::descriptor).toList());
        LinkedHashMap<String, Binding> byName = new LinkedHashMap<>();
        ordered.forEach(binding -> byName.put(binding.descriptor().name(), binding));
        synchronized (registrations) {
            purgeCollectedCatalogs();
            registrations.add(new CatalogRegistration(new WeakReference<>(catalog), Map.copyOf(byName), context));
        }
        return catalog;
    }

    /**
     * 仅按目录对象身份查找绑定，拒绝另一引擎或调用方自行构造的等值对象借用本引擎的来源读取能力。
     */
    private Optional<CatalogRegistration> registration(Catalog catalog) {
        synchronized (registrations) {
            purgeCollectedCatalogs();
            for (CatalogRegistration registration : registrations) {
                if (registration.catalog().get() == catalog) {
                    return Optional.of(registration);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 清理已被回收的请求目录；来源定位状态不应因长生命周期 Engine 而无限累积。
     */
    private void purgeCollectedCatalogs() {
        registrations.removeIf(registration -> registration.catalog().get() == null);
    }

    /**
     * 只读的 Skill 元数据快照；仅持有此对象身份且通过本目录发现的条目才可继续读取资源。
     */
    public static final class Catalog {
        private final List<SkillDescriptor> skills;

        /**
         * 仅允许解析器构造目录，防止外部伪造没有来源和租户绑定的条目集合。
         */
        private Catalog(List<SkillDescriptor> skills) {
            this.skills = List.copyOf(skills);
        }

        /**
         * 按覆盖优先级返回不可变元数据，不暴露物理定位信息、资源正文或来源对象。
         */
        public List<SkillDescriptor> skills() {
            return skills;
        }
    }

    /**
     * 注册时冻结的来源身份、优先级和顺序；运行期间不再从扩展对象读取可变排序信息。
     *
     * @param source 来源
     * @param id 当前实体的稳定标识
     * @param priority 优先级
     * @param order 排序顺序
     */
    private record RegisteredSource(SkillSource source,
                                    String id,
                                    int priority,
                                    int order) {
    }

    /**
     * 将一个来源与其发现的公开描述符绑定，供目录快照内部解析名称。
     *
     * @param source 来源
     * @param descriptor 描述信息
     */
    private record Binding(RegisteredSource source,
                           SkillDescriptor descriptor) {
    }

    /**
     * 将目录对象、私有来源绑定和请求上下文关联，构成不可复制的读取能力。
     *
     * @param catalog 目录
     * @param bindings 绑定集合
     * @param context 当前操作的上下文
     */
    private record CatalogRegistration(WeakReference<Catalog> catalog,
                                       Map<String, Binding> bindings,
                                       SkillRequestContext context) {
    }
}
