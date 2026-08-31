// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.kongweiguang.ja.configuration.domain;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 配置代际在应用与文件适配器之间共享的不可变 Provider/Model 投影。
 *
 * <p>投影只包含 JDK 值类型和 credentialId，不包含 Secret、Jackson、Watcher 或缓存实现。</p>
 */
public interface ConfigurationGenerationSnapshot {
    /** 返回不透明代际标识。 */
    String generationId();

    /** 返回冻结的 Skill 定义。 */
    List<Skill> skillDefinitions();

    /** 返回冻结的 MCP 定义。 */
    List<McpServer> mcpDefinitions();

    /** 返回本代际默认执行模式，单次 Turn 只能继承或收紧。 */
    AccessMode accessMode();

    /** 返回代际创建时冻结的工作区信任结果，后续文件变化不得改写活动 Turn。 */
    boolean trusted();

    /** 返回成对校验后的默认 Provider；空 catalog 明确返回 empty。 */
    Optional<String> defaultProviderId();

    /** 返回成对校验后的默认 Model；空 catalog 明确返回 empty。 */
    Optional<String> defaultModelId();

    /** 返回选中模型支持的默认思考档位；模型不支持时明确返回 empty。 */
    Optional<ReasoningLevel> defaultReasoningLevel();

    /** 按稳定标识解析 MCP；缺失时必须失败关闭。 */
    McpServer requireMcp(String mcpId);

    /** 按稳定标识解析 Provider，不允许从 Model ID 反推路由。 */
    Provider requireProvider(String providerId);

    /** 在指定 Provider 内解析 Model，防止跨 Provider 的同名模型产生歧义。 */
    Model requireModel(String providerId, String modelId);

    /** catalog 可见的 Skill 描述，不携带资源正文。 */
    record Skill(String skillId, String name, String scope, boolean enabled, String description) {
    }

    /** Provider 保存稳定连接、凭据引用、网络预算、Agent 默认值和模型目录。 */
    record Provider(String providerId, String name, ProviderType provider, Api api, URI baseUrl,
                    String credentialId, NetworkTimeouts networkTimeouts,
                    AgentDefaults agentDefaults, List<Model> models) {
        /** 防御性复制模型列表，避免消费者修改冻结代际中的选择顺序。 */
        public Provider {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(api, "api");
            Objects.requireNonNull(baseUrl, "baseUrl");
            Objects.requireNonNull(networkTimeouts, "networkTimeouts");
            Objects.requireNonNull(agentDefaults, "agentDefaults");
            models = List.copyOf(models);
        }
    }

    /** Model 保存上游名称、能力、输入模态和思考档位，不重复 Provider 连接字段。 */
    record Model(String modelId, String name, String model, Capabilities capabilities,
                 Map<ReasoningLevel, String> reasoningLevelMap, ReasoningLevel defaultReasoningLevel) {
        /** 冻结逻辑档位到上游值的映射，并要求默认值属于映射键集合。 */
        public Model {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(capabilities, "capabilities");
            reasoningLevelMap = Map.copyOf(reasoningLevelMap);
            if (defaultReasoningLevel != null && !reasoningLevelMap.containsKey(defaultReasoningLevel)) {
                throw new IllegalArgumentException("default reasoning level is unsupported");
            }
        }
    }

    /** Provider 能力上限与输入模态一起冻结，附件路由不得另行猜测。 */
    record Capabilities(long contextWindowTokens, long maxOutputTokens,
                        List<InputModality> inputModalities) {
        /** 防御性复制输入模态并阻止空能力集合。 */
        public Capabilities {
            inputModalities = List.copyOf(inputModalities);
            if (inputModalities.isEmpty()) throw new IllegalArgumentException("input modalities are missing");
        }
    }

    /** Provider 级 Agent 默认值不参与连接身份，可被项目层单调收紧。 */
    record AgentDefaults(Context context, TurnLimits turnLimits) {
        /** 只冻结真实 Provider 默认值；目录开关从根级代际读取。 */
        public AgentDefaults {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(turnLimits, "turnLimits");
        }
    }

    /** 压缩策略只暴露用户可控开关，算法阈值由应用版本统一管理。 */
    record Context(boolean autoCompact) {
    }

    /** Agent Loop 的轮次、Tool 调用和墙钟截止约束。 */
    record TurnLimits(int maxModelRounds, int maxToolCalls, Duration wallTimeout) {
    }

    /** Provider 网络连接和完整请求的独立超时。 */
    record NetworkTimeouts(Duration connectTimeout, Duration requestTimeout) {
    }

    /** 配置域 Provider 类型；调用方必须显式穷举映射到具体适配器。 */
    enum ProviderType {
        /** 使用 OpenAI Provider。 */
        OPENAI,

        /** 使用 Anthropic Provider。 */
        ANTHROPIC
    }

    /** 配置域模型 API；调用方必须显式穷举映射。 */
    enum Api {
        /** 采用 OpenAI Responses API。 */
        OPENAI_RESPONSES,

        /** 采用 Anthropic Messages API。 */
        ANTHROPIC_MESSAGES
    }

    /** 模型声明可接收的输入模态，附件能力只依赖该闭集。 */
    enum InputModality {
        /** 纯文本输入。 */
        TEXT,

        /** 图片输入。 */
        IMAGE,

        /** PDF 输入。 */
        PDF
    }

    /** 模型级思考档位闭集，不暴露隐藏思维链。 */
    enum ReasoningLevel {
        /** 明确关闭推理。 */
        OFF,

        /** 最小推理预算。 */
        MINIMAL,

        /** 较低思考预算。 */
        LOW,

        /** 中等思考预算。 */
        MEDIUM,

        /** 较高思考预算。 */
        HIGH,

        /** 超高推理预算。 */
        XHIGH,

        /** 最大推理预算。 */
        MAX
    }

    /** 全局执行模式只有直接执行和逐次确认两种，不承载路径或 Tool 策略。 */
    enum AccessMode {
        /** 每次 Tool 调用均请求用户确认。 */
        APPROVAL_REQUIRED,

        /** 完整权限。 */
        FULL_ACCESS
    }

    /** MCP 启动描述；凭据仅以 credentialId 引用。 */
    record McpServer(String mcpId, String name, Transport transport, String endpoint,
                     List<String> args, Map<String, String> env, Map<String, String> headers,
                     Auth auth, boolean enabled) {
        /** 冻结集合字段，防止适配器在连接期间观察到调用方修改。 */
        public McpServer {
            args = List.copyOf(args);
            env = Map.copyOf(env);
            headers = Map.copyOf(headers);
            Objects.requireNonNull(auth, "auth");
        }
    }

    /** MCP 凭据注入目标，不包含真实 secret。 */
    record Auth(AuthKind kind, String name, String credentialId) {
    }

    /** MCP 传输类型。 */
    enum Transport {
        /** 标准输入输出子进程传输。 */
        STDIO,

        /** Streamable HTTP 正式传输。 */
        STREAMABLE_HTTP
    }

    /** MCP 凭据注入策略。 */
    enum AuthKind {
        /** 不注入凭据。 */
        NONE,

        /** 注入进程环境变量。 */
        ENV,

        /** 注入标准 Bearer token。 */
        BEARER,

        /** 注入配置指定的请求头。 */
        HEADER
    }
}
