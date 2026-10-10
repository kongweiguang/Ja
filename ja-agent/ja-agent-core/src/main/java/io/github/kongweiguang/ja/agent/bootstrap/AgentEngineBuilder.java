// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.agent.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.application.cancellation.DefaultCancellationCoordinator;
import io.github.kongweiguang.ja.agent.application.engine.AgentEventWatchRegistry;
import io.github.kongweiguang.ja.agent.application.engine.AgentInitialQueuedInputsCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentRequestBindingCodec;
import io.github.kongweiguang.ja.agent.application.engine.AgentStreamRegistry;
import io.github.kongweiguang.ja.agent.application.engine.ExecutionScheduler;
import io.github.kongweiguang.ja.agent.application.engine.ParticipatingAgentStore;
import io.github.kongweiguang.ja.agent.application.loop.AgentContextMapper;
import io.github.kongweiguang.ja.agent.application.loop.AgentExecutionDriver;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopPersistence;
import io.github.kongweiguang.ja.agent.application.loop.AgentLoopExecutionService;
import io.github.kongweiguang.ja.agent.application.loop.StandardAgentLoopExecutionService;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.domain.ToolProjectionLimits;
import io.github.kongweiguang.ja.agent.application.gateway.AgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.application.gateway.ExtensionRegistry;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ManagedAttachmentReader;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.ToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStoreTransactionParticipant;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.infrastructure.model.ModelAdapterFactory;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.JacksonExecutionEventCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.MiddlewareStateRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.TurnExecutionStateCodec;
import io.github.kongweiguang.ja.agent.infrastructure.runtime.StandardAgentRequestRuntimeResolver;
import io.github.kongweiguang.ja.agent.infrastructure.tool.JsonSchemaToolArgumentValidator;
import io.github.kongweiguang.ja.agent.application.service.AgentEngineService;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 可复用循环的显式装配入口；不在此读取框架或 Ja 服务器配置。
 */
public final class AgentEngineBuilder {
    private AgentStore store;
    private ModelPort.ModelConfiguration model;
    private ContextBudget contextBudget;
    private ManagedAttachmentReader attachments;
    private ToolProjectionLimits outputLimits;
    private Consumer<ExtensionRegistry> extensions = ignored -> {
    };
    private AgentRequestRuntimeResolver runtimeResolver;
    private java.util.function.Function<io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest, String> systemPrompt;
    private AgentStoreTransactionParticipant transactionParticipant = AgentStoreTransactionParticipant.NONE;
    private int parallelism = Math.clamp(Runtime.getRuntime().availableProcessors(), 1, 8);
    private int maxOutstandingExecutions = 128;
    private int maxOutstandingPerThread = 16;
    private Duration leaseDuration = Duration.ofSeconds(30);

    /**
     * 创建尚未绑定资源的 Builder，使宿主显式决定每项外部能力的所有权。
     */
    public AgentEngineBuilder() {
    }

    /**
     * 构造失败后继续关闭所有自建资源，Exception 与 Error 均只补充原始失败。
     */
    private static void closeAfterBuildFailure(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Exception | Error closeFailure) {
            if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 附件来源属于宿主授权；未装配读取器时只在模型输入实际含附件时明确失败。
     */
    private static ManagedAttachmentReader disabledAttachmentReader() {
        return request -> {
            throw new UnsupportedOperationException(
                "managed attachment access requires an explicitly configured reader");
        };
    }

    /**
     * 默认只约束单个 Tool 结果的头尾文本投影，不构成任务轮数或总输出预算。
     */
    private static ToolProjectionLimits defaultProjectionLimits() {
        return new ToolProjectionLimits(12_000, 2_000);
    }

    /**
     * 设置必需的持久化 Store；Engine 仅做事务装饰，不接管宿主注入的适配器。
     */
    public AgentEngineBuilder store(AgentStore value) {
        store = Objects.requireNonNull(value, "store");
        return this;
    }

    /**
     * 设置请求未覆盖模型时使用的显式默认配置。
     */
    public AgentEngineBuilder model(ModelPort.ModelConfiguration value) {
        model = Objects.requireNonNull(value, "model");
        return this;
    }

    /**
     * 标准运行时使用宿主完整 Persona；专用 Runtime Resolver 自行拥有 Prompt Session。
     */
    public AgentEngineBuilder systemPrompt(
        java.util.function.Function<io.github.kongweiguang.ja.agent.application.gateway.AgentRuntimeRequest, String> value) {
        systemPrompt = Objects.requireNonNull(value, "systemPrompt");
        return this;
    }

    /**
     * 设置模型真实上下文窗口；Core 不根据模型名推测容量。
     */
    public AgentEngineBuilder contextBudget(ContextBudget value) {
        contextBudget = Objects.requireNonNull(value, "contextBudget");
        return this;
    }

    /**
     * 设置受管附件读取权限；省略时只禁用附件读取，不影响纯文本循环。
     */
    public AgentEngineBuilder attachments(ManagedAttachmentReader value) {
        attachments = Objects.requireNonNull(value, "attachments");
        return this;
    }

    /**
     * 设置 Tool 输出投影上限，确保上下文估算与实际 Provider 请求采用同一预算。
     */
    public AgentEngineBuilder toolProjectionLimits(ToolProjectionLimits value) {
        outputLimits = Objects.requireNonNull(value, "outputLimits");
        return this;
    }

    /**
     * 按调用顺序显式注册扩展，并在 Engine 构造时冻结为不可变能力视图。
     */
    public AgentEngineBuilder extensions(Consumer<ExtensionRegistry> registration) {
        extensions = Objects.requireNonNull(registration, "extensions");
        return this;
    }

    /**
     * 注入宿主运行时解析器，供 Ja 等应用按安全点装配动态 Prompt、Skill 与工具能力。
     */
    public AgentEngineBuilder runtimeResolver(AgentRequestRuntimeResolver value) {
        runtimeResolver = Objects.requireNonNull(value, "runtimeResolver");
        return this;
    }

    /**
     * 让宿主业务事实加入 Store 的同一事务；实际连接参与方式由具体 Store 契约决定。
     */
    public AgentEngineBuilder transactionParticipant(AgentStoreTransactionParticipant value) {
        transactionParticipant = Objects.requireNonNull(value, "transactionParticipant");
        return this;
    }

    /**
     * 限定同时运行的不同会话数；这是调度并发上限，不是 Agent 轮数或时长预算。
     */
    public AgentEngineBuilder parallelism(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("parallelism must be positive");
        }
        parallelism = value;
        return this;
    }

    /**
     * 限定排队和运行中的总任务数，使过载在持久准入前被明确拒绝。
     */
    public AgentEngineBuilder maxOutstandingExecutions(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxOutstandingExecutions must be positive");
        }
        maxOutstandingExecutions = value;
        return this;
    }

    /**
     * 限定单个租户 Thread 的排队和运行任务数，防止一个 Thread 耗尽全局队列。
     */
    public AgentEngineBuilder maxOutstandingPerThread(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("maxOutstandingPerThread must be positive");
        }
        maxOutstandingPerThread = value;
        return this;
    }

    /**
     * 设置 Thread 执行租约的续期区间；它只用于 fencing 与故障接管，不限制任务运行时长。
     */
    public AgentEngineBuilder leaseDuration(Duration value) {
        Duration configured = Objects.requireNonNull(value, "leaseDuration");
        if (configured.compareTo(Duration.ofSeconds(3)) < 0) {
            throw new IllegalArgumentException("leaseDuration must be at least 3 seconds");
        }
        try {
            if (configured.toNanos() <= 0) {
                throw new IllegalArgumentException("leaseDuration must be representable as positive nanoseconds");
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("leaseDuration exceeds the supported range", overflow);
        }
        leaseDuration = configured;
        return this;
    }

    /**
     * 唯一标准 Loop 由 bootstrap 私有组装，固定 codec 与执行服务配对，避免为单一路径暴露公共工厂 SPI。
     */
    private static AgentLoopExecutionService createStandardLoop(AgentStore store,
                                                                 ExtensionRegistry.Snapshot extensions,
                                                                 AgentRequestRuntimeResolver runtimes,
                                                                 JsonValueCodec jsonCodec,
                                                                 Duration leaseDuration) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(extensions, "extensions");
        Objects.requireNonNull(runtimes, "runtimes");
        Objects.requireNonNull(jsonCodec, "jsonCodec");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        StorePayloadCodec records = new StorePayloadCodec(mapper);
        TurnExecutionStateCodec cursors = new TurnExecutionStateCodec(mapper);
        JacksonExecutionEventCodec events = new JacksonExecutionEventCodec(mapper);
        ControlPayloadCodec controls = new ControlPayloadCodec(mapper);
        AgentLoopPersistence persistence = new AgentLoopPersistence(store, Clock.systemUTC());
        AgentContextMapper contexts = new AgentContextMapper(jsonCodec, records);
        ToolArgumentValidator validator = new JsonSchemaToolArgumentValidator(jsonCodec);
        AgentExecutionDriver driver = new AgentExecutionDriver(store, persistence, records, cursors, events,
                contexts, validator, controls, jsonCodec, new ExtensionFactRecordCodec(),
                new MiddlewareStateRecordCodec(jsonCodec), Clock.systemUTC(), leaseDuration);
        return new StandardAgentLoopExecutionService(store, driver, persistence, records,
                events, controls, leaseDuration, Clock.systemUTC());
    }

    /**
     * Builder 在 bootstrap 边界组装唯一 Loop；Store 仍由宿主管理，本地模型工厂则转交 Engine 或在失败时关闭。
     */
    @SuppressWarnings("PMD.CloseResource")
    public AgentEngine build() {
        AgentStore configuredStore = Objects.requireNonNull(store, "store is required");
        boolean standardResolver = runtimeResolver == null;
        if (!standardResolver && (model != null || contextBudget != null || attachments != null
                || outputLimits != null || systemPrompt != null)) {
            throw new IllegalStateException("standard runtime settings cannot be combined with runtimeResolver");
        }
        ModelPort.ModelConfiguration configuredModel = standardResolver
            ? Objects.requireNonNull(model, "model is required when using the standard runtime resolver")
            : model;
        ContextBudget configuredContextBudget = standardResolver
            ? Objects.requireNonNull(contextBudget, "contextBudget is required when using the standard runtime resolver")
            : null;
        if (maxOutstandingPerThread > maxOutstandingExecutions) {
            throw new IllegalStateException("per-thread outstanding limit exceeds global limit");
        }
        AgentStore safeStore = new ParticipatingAgentStore(configuredStore, transactionParticipant);
        JsonValueCodec jsonCodec = JsonValueCodecFactory.standard();
        AgentRequestBindingCodec bindingCodec = new AgentRequestBindingCodec();
        AgentInitialQueuedInputsCodec initialQueuedInputsCodec = new AgentInitialQueuedInputsCodec();
        AgentEventWatchRegistry eventWatches = new AgentEventWatchRegistry(safeStore, maxOutstandingExecutions);
        AgentStreamRegistry streamRegistry = new AgentStreamRegistry(maxOutstandingExecutions);
        ExtensionRegistry registry = new ExtensionRegistry();
        AgentLoopExecutionService service = null;
        ModelAdapterFactory modelAdapters = null;
        try {
            extensions.accept(registry);
            ExtensionRegistry.Snapshot snapshot = registry.freeze();
            AgentRequestRuntimeResolver runtimes = runtimeResolver;
            if (standardResolver) {
                ManagedAttachmentReader attachmentReader = attachments == null
                    ? disabledAttachmentReader() : attachments;
                ToolProjectionLimits projectionLimits = outputLimits == null
                    ? defaultProjectionLimits() : outputLimits;
                modelAdapters = new ModelAdapterFactory();
                ModelAdapterFactory ownedAdapters = modelAdapters;
                runtimes = new StandardAgentRequestRuntimeResolver(configuredModel,
                    configuration -> ownedAdapters, configuredContextBudget, ownedAdapters, attachmentReader, projectionLimits,
                    systemPrompt);
            }
            service = createStandardLoop(safeStore, snapshot, runtimes, jsonCodec, leaseDuration);
            ExecutionScheduler scheduler = new ExecutionScheduler(
                parallelism, maxOutstandingExecutions, maxOutstandingPerThread);
            return new AgentEngineService(safeStore,
                service,
                runtimes,
                snapshot,
                bindingCodec,
                initialQueuedInputsCodec,
                eventWatches,
                streamRegistry,
                new DefaultCancellationCoordinator(),
                scheduler,
                modelAdapters);
        } catch (RuntimeException | Error failure) {
            closeAfterBuildFailure(eventWatches, failure);
            closeAfterBuildFailure(streamRegistry, failure);
            if (service != null) {
                closeAfterBuildFailure(service, failure);
            }
            if (modelAdapters != null) {
                closeAfterBuildFailure(modelAdapters, failure);
            }
            throw failure;
        }
    }
}
