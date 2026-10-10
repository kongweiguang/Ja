// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.kongweiguang.ja.agent.example.springboot.bootstrap;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;

import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.example.springboot.agent.application.AgentCatalog;
import io.github.kongweiguang.ja.agent.example.springboot.conversation.application.ThreadService;
import io.github.kongweiguang.ja.agent.example.springboot.attachment.application.AttachmentService;
import io.github.kongweiguang.ja.agent.example.springboot.experience.application.ExperienceService;
import io.github.kongweiguang.ja.agent.application.gateway.AgentExtension;
import io.github.kongweiguang.ja.agent.application.gateway.AgentMiddleware;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.JsonValueCodec;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.application.gateway.store.AgentStore;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec;
import io.github.kongweiguang.ja.agent.application.gateway.store.ExtensionFactRecordCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.ControlPayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.persistence.StorePayloadCodec;
import io.github.kongweiguang.ja.agent.infrastructure.json.JsonValueCodecFactory;
import io.github.kongweiguang.ja.agent.store.pg.infrastructure.PostgresAgentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.List;
import javax.sql.DataSource;

/**
 * Spring 按依赖顺序关闭 Engine、Store 和连接池，不接管其它应用运行时。
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {
    /**
     * Codec 共享同一 Jackson 配置；示例测试可能只装载最小 Spring 上下文，因此不能依赖 Web 自动配置隐式提供它。
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /**
     * 只在 bootstrap 装配具体 JSON 实现，应用用例依赖稳定 Gateway，避免 Spring 示例复制编码逻辑。
     */
    @Bean
    public ExecutionRecordCodec executionRecordCodec(ObjectMapper mapper) {
        return new StorePayloadCodec(mapper);
    }

    /**
     * 控制事实与普通执行事实使用独立协议实现，调用方只能看到各自的公开契约。
     */
    @Bean
    public ExecutionControlCodec executionControlCodec(ObjectMapper mapper) {
        return new ControlPayloadCodec(mapper);
    }

    /** 扩展信封共用 Core 严格编解码契约，业务用例只注入 Gateway，不构造基础设施实现。 */
    @Bean
    public ExtensionFactRecordCodec extensionFactRecordCodec() {
        return new io.github.kongweiguang.ja.agent.infrastructure.persistence.ExtensionFactRecordCodec();
    }

    /**
     * 由组合根选定 Core 的标准严格 JSON 实现，应用层只接收稳定 Gateway 而不各自构造序列化器。
     */
    @Bean
    public JsonValueCodec jsonValueCodec() {
        return JsonValueCodecFactory.standard();
    }

    /**
     * 错误不回显值，尤其是模型密钥和带认证信息的地址。
     */
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing property " + field);
        }
        return value;
    }

    /**
     * 配置与对话共用宿主 DataSource，读取一次冻结本次运行时能力。
     */
    @Bean
    public DatabaseAgentConfiguration databaseAgentConfiguration(DataSource dataSource, org.springframework.core.env.Environment environment) {
        return new DatabaseAgentConfiguration(dataSource, environment);
    }

    /**
     * 不使用文件绑定，启动时从 PG 配置快照生成不可变模型与扩展声明。
     */
    @Bean
    public AgentProperties agentProperties(DatabaseAgentConfiguration configuration) {
        return configuration.load();
    }

    /**
     * 独立示例宿主先显式引导 Core schema，再构造只负责校验的 Store；连接池仍由 Spring 管理。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentStore.class)
    public AgentStore agentStore(DataSource dataSource) {
        PostgresAgentStore.bootstrap(dataSource);
        return new PostgresAgentStore(dataSource);
    }

    /**
     * 附件引用、目录与笔记共享 Store 事务；异步回调只消费已冻结身份，且由 Store 统一提交或回滚。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentEngine.class)
    public AgentEngine agentEngine(AgentStore store, ThreadService threads, AgentCatalog prompts,
                                   AttachmentService attachments, ObjectProvider<ExperienceService> experience,
                                   ModelPort.ModelConfiguration model,
                                   ContextBudget budget,
                                   List<AgentTool> tools,
                                   List<AgentMiddleware> middleware,
                                   List<AgentExtension> extensions) {
        return new AgentEngineBuilder()
                .store(store)
                .attachments(attachments)
                .transactionParticipant(context -> {
                    attachments.onExecutionCommit(context);
                    threads.onExecutionCommit(context);
                    ExperienceService documents = experience.getIfAvailable();
                    if (documents != null) {
                        documents.onExecutionCommit(context);
                    }
                })
                .model(model)
                .contextBudget(budget)
                .systemPrompt(request -> prompts.prompt(threads.agentId(request.identity())))
                .extensions(registry -> {
                    extensions.forEach(registry::register);
                    tools.forEach(registry::registerTool);
                    middleware.forEach(registry::registerMiddleware);
                }).build();
    }

    /**
     * 输入模态只来自启动时冻结的显式声明，HTTP 能力与 Codec 交集继续在附件模块校验。
     */
    @Bean
    @ConditionalOnMissingBean(ModelPort.ModelConfiguration.class)
    public ModelPort.ModelConfiguration modelConfiguration(AgentProperties properties) {
        AgentProperties.Model model = properties.model();
        return new ModelPort.ModelConfiguration(model.providerId(), model.modelId(), model.configGeneration(),
                model.api(), model.name(), URI.create(required(model.baseUri(), "agent.model.base-uri")),
                required(model.apiKey(), "agent.model.api-key"), model.connectTimeout(), model.requestTimeout(),
                model.inputModalities(), new ModelPort.GenerationOptions(null, null, null,
                model.reasoningLevel() == null || model.reasoningLevel().isBlank() ? null : model.reasoningLevel()));
    }

    /**
     * 窗口独立于模型名称，具体额度继续由 Core 校验。
     */
    @Bean
    @ConditionalOnMissingBean(ContextBudget.class)
    public ContextBudget contextBudget(AgentProperties properties) {
        AgentProperties.Context context = properties.context();
        return ContextBudget.capabilities(context.windowTokens(), context.maxOutputTokens(), context.autoCompact());
    }
}
