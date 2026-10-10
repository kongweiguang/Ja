// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.application.gateway;

import io.github.kongweiguang.ja.agent.domain.tool.ToolSpec;
import io.github.kongweiguang.ja.agent.domain.validation.ContractChecks;
import io.github.kongweiguang.ja.agent.application.gateway.model.SummaryModel;
import io.github.kongweiguang.ja.agent.application.gateway.skill.SkillSource;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 显式收集模型、工具、Skill 来源与执行扩展；冻结后的注册顺序构成每个 Engine 的稳定能力视图。
 */
public final class ExtensionRegistry {
    private final Object lock = new Object();
    private final Set<String> extensionIds = new HashSet<>();
    private final Map<String, ModelProvider> modelProviders = new LinkedHashMap<>();
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final Map<String, SkillSource> skillSources = new LinkedHashMap<>();
    private final Map<String, MiddlewareRegistration> middleware = new LinkedHashMap<>();
    private final Map<String, AgentEventListener> listeners = new LinkedHashMap<>();
    private boolean frozen;

    /**
     * 从已冻结的基础能力派生独立请求注册表，不重复调用扩展回调，也不改变原快照或其他请求。
     * 中间件沿用登记时的 namespace/schema，避免可变实例的当前返回值改写恢复身份。
     */
    public static ExtensionRegistry copyOf(Snapshot snapshot) {
        Snapshot source = Objects.requireNonNull(snapshot, "snapshot");
        ExtensionRegistry copy = new ExtensionRegistry();
        for (String id : source.extensionIds()) {
            copy.extensionIds.add(ContractChecks.identifier(id, "extension id"));
        }
        for (ModelProvider provider : source.modelProviders()) {
            putUnique(copy.modelProviders, provider.id(), provider, "model provider");
        }
        for (AgentTool tool : source.tools()) {
            copy.registerTool(tool);
        }
        for (SkillSource skill : source.skillSources()) {
            copy.registerSkillSource(skill);
        }
        for (MiddlewareRegistration registration : source.middleware()) {
            putUnique(copy.middleware, registration.namespace(), registration, "middleware");
        }
        for (AgentEventListener listener : source.listeners()) {
            copy.registerEventListener(listener);
        }
        return copy;
    }

    /**
     * 在注册阶段校验稳定标识并拒绝重复键，避免歧义推迟到模型调用或分发时才暴露。
     */
    private static <T> void putUnique(Map<String, T> target, String rawId, T value, String kind) {
        String id = ContractChecks.identifier(rawId, kind + " id");
        Objects.requireNonNull(value, kind);
        if (target.putIfAbsent(id, value) != null)
            {
                throw new IllegalArgumentException("duplicate " + kind + " id: " + id);
            }
    }

    /**
     * 统一校验策略持久身份，并保护 `agent.*` 记录类型留给 Core 自身使用。
     */
    private static String middlewareNamespace(String value) {
        String namespace = ContractChecks.identifier(value, "middleware namespace");
        if (namespace.equals("agent") || namespace.startsWith("agent.")) {
            throw new IllegalArgumentException("middleware namespace is reserved");
        }
        return namespace;
    }

    /**
     * 扩展先写候选副本，回调成功后锁定候选再采用；私有锁避免宿主持有 Registry 监视器阻塞注册。
     */
    public ExtensionRegistry register(AgentExtension extension) {
        synchronized (lock) {
            ensureMutable();
            AgentExtension required = Objects.requireNonNull(extension, "extension");
            String id = ContractChecks.identifier(required.id(), "extension id");
            if (extensionIds.contains(id)) {
                throw new IllegalArgumentException("duplicate extension id: " + id);
            }
            ExtensionRegistry candidate = copyMutable();
            candidate.extensionIds.add(id);
            required.register(candidate);
            synchronized (candidate.lock) {
                candidate.ensureMutable();
                candidate.frozen = true;
                adopt(candidate);
            }
            return this;
        }
    }

    /**
     * 仅注册普通模型能力；未提供摘要能力时压缩会明确失败，不借用另一条内置 HTTP 路由。
     */
    public ExtensionRegistry registerModelProvider(String id, ModelPort provider) {
        synchronized (lock) {
            ensureMutable();
            putUnique(modelProviders, id, new ModelProvider(id, provider, Optional.empty()), "model provider");
            return this;
        }
    }

    /**
     * 同时冻结普通请求与摘要路由，确保压缩使用宿主显式选择的供应商能力。
     */
    public ExtensionRegistry registerModelProvider(String id, ModelPort provider,
                                                   SummaryModel.Factory summaries) {
        synchronized (lock) {
            ensureMutable();
            putUnique(modelProviders, id, new ModelProvider(id, provider,
                    Optional.of(Objects.requireNonNull(summaries, "summaries"))), "model provider");
            return this;
        }
    }

    /**
     * 工具名来自不可变规范并作为唯一路由键，重名在 Engine 创建前失败。
     */
    public ExtensionRegistry registerTool(AgentTool tool) {
        synchronized (lock) {
            ensureMutable();
            AgentTool required = Objects.requireNonNull(tool, "tool");
            ToolSpec spec = Objects.requireNonNull(required.spec(), "tool spec");
            putUnique(tools, spec.name(), required, "tool");
            return this;
        }
    }

    /**
     * 按来源身份注册 Skill 提供者；同名来源不得互相覆盖，来源优先级由目录规则显式决定。
     */
    public ExtensionRegistry registerSkillSource(SkillSource source) {
        synchronized (lock) {
            ensureMutable();
            SkillSource required = Objects.requireNonNull(source, "source");
            putUnique(skillSources, required.id(), required, "skill source");
            return this;
        }
    }

    /**
     * 中间件按注册次序参与关键执行点；身份冲突直接失败，避免隐式替换改变策略顺序。
     */
    public ExtensionRegistry registerMiddleware(AgentMiddleware value) {
        synchronized (lock) {
            ensureMutable();
            AgentMiddleware required = Objects.requireNonNull(value, "middleware");
            String namespace = middlewareNamespace(required.id());
            int schemaVersion = required.stateSchemaVersion();
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("invalid middleware state schema version");
            }
            putUnique(middleware, namespace,
                    new MiddlewareRegistration(namespace, schemaVersion, required), "middleware");
            return this;
        }
    }

    /**
     * 观察器保留注册次序，并与失败可阻断执行的中间件分开管理其错误语义。
     */
    public ExtensionRegistry registerEventListener(AgentEventListener listener) {
        synchronized (lock) {
            ensureMutable();
            AgentEventListener required = Objects.requireNonNull(listener, "listener");
            putUnique(listeners, required.id(), required, "event listener");
            return this;
        }
    }

    /**
     * 一次冻结全部能力并复制为快照，确保 Engine 不会观察到构建后迟到的部分注册。
     */
    public Snapshot freeze() {
        synchronized (lock) {
            ensureMutable();
            frozen = true;
            return new Snapshot(List.copyOf(modelProviders.values()),
                    List.copyOf(tools.values()), List.copyOf(skillSources.values()), orderedMiddlewareRegistrations(),
                    orderedListeners(), Set.copyOf(extensionIds));
        }
    }

    /**
     * 为扩展创建可变候选状态，使其贡献在注册回调成功前不暴露给正式注册表。
     */
    private ExtensionRegistry copyMutable() {
        ExtensionRegistry copy = new ExtensionRegistry();
        copy.extensionIds.addAll(extensionIds);
        copy.modelProviders.putAll(modelProviders);
        copy.tools.putAll(tools);
        copy.skillSources.putAll(skillSources);
        copy.middleware.putAll(middleware);
        copy.listeners.putAll(listeners);
        return copy;
    }

    /**
     * 将完整候选状态替换为正式状态；调用方持有注册锁，其他线程不会读到逐项复制过程。
     */
    private void adopt(ExtensionRegistry candidate) {
        extensionIds.clear();
        extensionIds.addAll(candidate.extensionIds);
        modelProviders.clear();
        modelProviders.putAll(candidate.modelProviders);
        tools.clear();
        tools.putAll(candidate.tools);
        skillSources.clear();
        skillSources.putAll(candidate.skillSources);
        middleware.clear();
        middleware.putAll(candidate.middleware);
        listeners.clear();
        listeners.putAll(candidate.listeners);
    }

    /**
     * 以登记时捕获的 id/schema 顺序生成持久状态绑定，避免再次调用可变扩展实例推断 namespace。
     */
    private List<MiddlewareRegistration> orderedMiddlewareRegistrations() {
        return List.copyOf(middleware.values());
    }

    /**
     * 用声明顺序冻结观察器，使宿主收到的诊断通知顺序稳定。
     */
    private List<AgentEventListener> orderedListeners() {
        return List.copyOf(listeners.values());
    }

    /**
     * Engine 已取得不可变能力视图后拒绝所有写操作，避免热变更穿过执行边界。
     */
    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("extension registry is frozen");
        }
    }

    /**
     * Engine 构建时取得的完整能力快照；列表次序可观察，因此构造时固定为不可变副本。
     *
     * @param modelProviders 模型供应商列表
     * @param tools 工具列表
     * @param skillSources Skill 来源集合
     * @param middleware 中间件
     * @param listeners 监听器列表
     * @param extensionIds 扩展标识集合
     */
    public record Snapshot(List<ModelProvider> modelProviders,
                           List<AgentTool> tools,
                           List<SkillSource> skillSources,
                           List<MiddlewareRegistration> middleware,
                           List<AgentEventListener> listeners,
                           Set<String> extensionIds) {
        /**
         * 逐层冻结集合，防止 Builder 交付快照后调用方改变正在使用的能力视图。
         */
        public Snapshot {
            modelProviders = List.copyOf(Objects.requireNonNull(modelProviders, "modelProviders"));
            tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
            skillSources = List.copyOf(Objects.requireNonNull(skillSources, "skillSources"));
            middleware = List.copyOf(Objects.requireNonNull(middleware, "middleware"));
            listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
            extensionIds = Set.copyOf(Objects.requireNonNull(extensionIds, "extensionIds"));
            if (middleware.stream().map(MiddlewareRegistration::namespace).distinct().count() != middleware.size()) {
                throw new IllegalArgumentException("duplicate middleware namespace in snapshot");
            }
        }
    }

    /**
     * 登记时冻结中间件的持久状态身份与 schema 版本，并保留原实现供四个拦截点调用。
     *
     * @param namespace 命名空间
     * @param stateSchemaVersion 状态数据结构版本
     * @param middleware 中间件
     */
    public record MiddlewareRegistration(String namespace,
                                         int stateSchemaVersion,
                                         AgentMiddleware middleware) {
        /**
         * Registry 已校验身份，但记录本身仍拒绝非法状态以保护直接构造的测试/调用快照。
         */
        public MiddlewareRegistration {
            namespace = middlewareNamespace(namespace);
            if (stateSchemaVersion < 1) {
                throw new IllegalArgumentException("invalid middleware state schema version");
            }
            Objects.requireNonNull(middleware, "middleware");
        }
    }

    /**
     * 将供应商实现绑定到宿主配置使用的稳定名称，显式选择时不依赖服务发现顺序。
     *
     * @param id 当前实体的稳定标识
     * @param port 模型调用端口
     * @param summaries 摘要集合（可选）
     */
    public record ModelProvider(String id,
                                ModelPort port,
                                Optional<SummaryModel.Factory> summaries) {
        /**
         * 校验配置键并固定端口引用，避免 Engine 快照中出现空实现或别名冲突。
         */
        public ModelProvider {
            id = ContractChecks.identifier(id, "model provider id");
            Objects.requireNonNull(port, "port");
            summaries = Objects.requireNonNull(summaries, "summaries");
        }
    }
}
