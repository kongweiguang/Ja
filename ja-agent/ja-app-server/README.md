<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja App Server

`ja-agent/ja-app-server/` 是 Ja 唯一可发布的 Java 应用服务工程，使用 Java 25 和 Solon 4.1.0，
并复用 `ja-agent/ja-agent-core/` 的模型调用与执行循环。它既支持 JVM 调试运行，也支持 GraalVM Native Image 构建。
`ja-agent/pom.xml` 是 Maven 聚合入口，所有 Java 模块都位于 `ja-agent/` 下。Ja App Server 的对外产物仍命名为
`ja-app-server` 并保留 `ja-agent/ja-app-server/target/` 输出目录；`sidecar` 只描述它被 Tauri 托管时的部署关系。

## 运行边界

子智能体的全局默认开关和模型选择由 `config.toml` 的 `subagents` 管理，设置只影响之后新建的独立
会话。指定子模型还可选择它支持的思考等级；默认值沿用模型默认，跟随父任务时沿用父 Turn 的档位。
Java 将策略在会话创建事务中持久化，派生任务继承父会话策略；后续设置和进程重启不改写
已有会话。数据库 V2 初始化开启/跟随父任务，V3 为策略添加 nullable 思考档位，完整行为与当前验证边界见
[侧聊、子任务与会话通信](../../.updeng/docs/business/task-threads/README.md)。

Ja App Server 作为本地 sidecar 运行，stdio 与 Solon FeatHTTP WebSocket 是独立传输入口，
边界协议是受版本约束的 JA-RPC。stdio 模式的 stdout 仅用于 JSONL 协议帧；日志、诊断和异常必须走 stderr 或日志
文件。可复用的模型供应商、上下文和 Agent 循环位于 Agent Core；Ja App Server 负责把共享能力接入本地运行时，并持有
MCP、工作区、JA-RPC、Plan 与桌面投影。Java 也是 `config.toml`、`auth.json`、
配置 generation、Workspace identity 与凭据持久化的唯一 owner；Rust 只拥有
App Server 进程、stdio/共享 WebSocket 连接生命周期、Windows 进程树与桌面原生能力，React 只消费脱敏投影。
Provider 是用户自定义连接；名称不参与路由，每条配置按所选 API 规范决定请求、鉴权与 Usage 解析，并通过
自己的凭据引用取得 API Key。每次模型请求前解析最新环境并对最终 envelope 进行本地预算估算，该估算不保证严格 Token 上界；实际 Usage 只取正式
响应并由 Java 持久化。

## Reasoning 展示与续轮

界面中的 reasoning 区块只展示 Provider 明确返回的公开摘要或公开 reasoning 文本，不是 Ja 根据答案自行生成的“思维链”。
公开展示事件和原生 opaque 块是两条独立链路：前者进入 Timeline，后者只进入助手历史，绝不会把签名、加密载荷或
`redacted_thinking.data` 下发到 UI。

- OpenAI Responses 请求固定 `store:false` 并声明 `include:["reasoning.encrypted_content"]`。公开 summary 用于流式展示；只有
  非空 `encrypted_content` 才会持久化为可回放的 `reasoning` output item。
- Anthropic Messages 的 `thinking` 使用公开 `thinking` 文本和 `signature` 原样保存，`redacted_thinking` 保存其原生 `data`；
  两者都只在同一 Provider、模型、API、上游模型和端点身份下回放。
- OpenAI Chat Completions 按上游实际返回的 `reasoning_content`、`reasoning` 或 `reasoning_text` 字段保存，字段名不由 Ja
  擅自改写；公开增量仍单独投影为 reasoning 摘要。
- 切换上述任一身份时，opaque 内容不会发送给新模型；若 Provider 返回了明确公开文本，Context mapper 只保留该安全文本。
  如果响应没有可回放的原生载荷，Ja 只展示摘要，不伪造一个下一轮可能被拒绝的 reasoning 块。

思考档位是配置层的逻辑 `off|minimal|low|medium|high|xhigh|max`，由模型能力表映射成上游值。Anthropic 当前自适应思考
使用 `thinking.type=adaptive` 和 `output_config.effort`；Codec 对旧版 `budget_tokens` 的数字形式仍做防御性解析，但任意数字预算不是当前
桌面设置的公开档位，不能绕过配置合同直接写入请求。

## 日志目录

Rust Host 在启动 Ja App Server sidecar 前创建并校验 `home/data/run/log` 四个独立目录，再通过
Base64URL 参数传给 JVM 或 Native Image；Java 从 `home` 自行读取配置和凭据，并把日志
固定写入 `logs/java`，不依赖 cwd 推断生产路径。
Host 同时通过必填 `--ja-runtime-generation=<安全正整数>` 传入本次启动代际；Java 通知沿用该值。
该参数由进程 owner 注入，不属于用户配置，也不能由 WebView 覆盖，重启后仍严格隔离旧进程事件。
`app-server.log` 保存 INFO 以上运行日志，`app-server-error.log` 单独保存 ERROR，二者按日
及 20 MiB 分片滚动并保留 30 天。stdout 始终只承载 JSONL/JSON-RPC，WARN 以上
诊断才同时写入 stderr。

## Java 包职责

Maven 模块按可复用边界组织：`ja-agent/ja-agent-core/` 的根包为
`io.github.kongweiguang.ja.agent`，内部按 `api`、`domain`、`application`、`extension` 与 `infrastructure` 分层；
`ja-agent/extensions/` 中的四个模块分别提供 SQLite/PG Store 和本地/PG Skill 来源。
Ja 应用仍按 DDD 限界上下文组织配置、工作区、目录、任务、Plan 与 RPC；Solon 启动和 Ja 专属组合只在
`ja-agent/ja-app-server/`。Spring Boot 接入示例只放在 `ja-agent/example/ja-agent-example-springboot/`，不提供 Spring starter。

运行时代码按以下边界维护：

| 模块 | 叶子职责 |
| --- | --- |
| `ja-agent/ja-agent-core/` | Agent API、领域状态、执行编排、模型供应商、上下文与共享扩展契约 |
| `ja-agent/extensions/ja-agent-store-local/`、`ja-agent-store-pg/` | 用各自数据库事务实现共享 `AgentStore` 契约；PG 连接由业务提供 |
| `ja-agent/extensions/ja-agent-skills-local/`、`ja-agent-skills-pg/` | 分别从本地目录和 PostgreSQL 读取 Skill；不负责授予工具权限 |
| `ja-agent/ja-app-server/configuration/` | Ja 配置文档、generation 与 Windows 凭据文件 |
| `ja-agent/ja-app-server/catalog/adapter/out/mcp/` | MCP 配置、SDK 会话、stdio/HTTP transport 与 Tool adapter |
| `ja-agent/ja-app-server/infrastructure/persistence/` | Ja 自有配置、工作区、任务、Plan 与应用迁移的持久化适配 |
| `ja-agent/ja-app-server/transport/rpc/` | JA-RPC 2.0 协议、请求处理、连接和 stdout 单写者 |

每个职责叶子包都有 `package-info.java` 说明允许内容与禁止边界；ArchUnit 检查领域纯度、
六边形依赖方向、顶层包无环，以及 Provider、MCP、持久化、RPC、配置和 Context 的关键职责边界。

模型固定看到 `read/edit/write`；只有进程级 Shell 预检成功时才额外看到 `shell`，复杂 Tool
在 Provider 请求安全点按需发现，已生成 Tool batch 通过不可变 binding 精确路由。Windows 依次尝试 `PATH` 中的 PowerShell 7 和系统
Windows PowerShell 5.1；两者都不可用时，执行环境明确标记 `shell: unavailable`，App Server
仍保留配置、历史与 RPC 恢复面，不注册一个必然失败的 Shell Tool。Tool 在 Java App Server
内执行，文件访问只受当前操作系统账户权限约束，Rust 不再提供 Host Tool 或 Sandbox RPC。
App Server 的 Shell 与界面终端完整继承 Ja 的宿主环境，保留用户 PATH、代理、临时目录和
已有 CLI 登录配置；不再设置环境白名单，也不要求在 Ja 重复登录。PowerShell 加载用户
Profile，Agent Shell 仍按非交互命令执行并保留超时、取消和进程树清理。其他终端中临时
设置的变量不会自动同步到已启动的 Ja；继承环境不投影到界面或普通日志。
旧 Tool 别名、旧权限/审批枚举、`change/*`、`runtime/configure` 和
`initialize.configSnapshot` 均已删除且 fail closed。活动 Turn 的权威输入队列由
`turn/input/enqueue`、`turn/input/prioritize`、`turn/input/update` 与 `turn/input/delete`
维护；Steering 在安全点优先消费，普通输入保持 FIFO，队列随 `thread/read` 和全量事件恢复。

## Plan

2026-09-28 确认移除独立 Goal。普通对话直接承接可恢复的实施任务，Plan 是用户按需选择的单次任务规划能力，
不作为普通对话的执行前置条件。`CollaborationMode=plan` 控制规划语义，与
`AccessMode=approval_required|full_access` 正交，切换模式或回答问题不会扩大 Tool 权限。

Plan、PlanRevision、执行授权、Run、StepExecution、Evidence 与 Evaluation 由 Java 25 App Server 和 SQLite
唯一持有。草稿形成不可变 revision 与 canonical JSON SHA-256；`plan/execute` 在同一事务核对精确 revision/hash、
记录用户授权并创建唯一 Run。公共接口没有独立 `plan/approve`。Rust 只代理类型化 JA-RPC，React 不计算状态、hash 或完成结论。

Plan 执行复用共享 Agent Core，创建不伪造 USER 的 `PLAN_EXECUTION` Turn；用户明确开启 Run 后，
同一 Run 可自动跨执行 Turn 修正并再次验收，不隐式建立新 Run。已提交的 Tool 结果不重放；
未知副作用阻断完成，暂停、恢复与停止保留原 Run 的证据和审计事实。
Core 只解释通用对话、工具和持久化流程，Plan 状态与验收规则由应用层持有。

握手使用 `plan` feature，变化通知只有 `plan/changed`。执行 ACK 表示已准入，完成结论以权威 Plan 快照和证据为准；
重连应补读事件并重新读取快照。接口顺序、字段、幂等和失败处理见
[JA-RPC 2.0 方法契约](../../contracts/ja-rpc/v2/methods.md)及[结果契约](../../contracts/ja-rpc/v2/results.md)。
历史 Goal 合同和历史综合验收不能证明当前 Plan-only 产物已通过验证。

## Agent Core 集成边界

Ja App Server 通过 `AgentEngine` 组合共享内核，并显式注册 Ja 工具、Skill 来源、中间件和事件监听器。
扩展身份、工具名称冲突会在装配时拒绝；Core 不扫描 classpath，不读取 Ja 配置文件，也不因异常切换到另一种
模型或存储。配置文件到模型请求的适配、Solon 生命周期和 Ja 业务状态仍属于 App Server。

Plan、任务通信、MCP 和工作区保留各自的应用用例与状态机。需要模型执行的外部动作通过注册的工具完成；
中间件不绕过工具调用、权限判断或执行事实提交。事件监听器只观察执行和持久化事件，不能改写终态。

Spring Boot 独立示例放在 `ja-agent/example/ja-agent-example-springboot/`，使用普通 Bean 装配，
不会引入额外 starter。Ja 应用行为、Core 回归与存储/Skill 契约分别在自己的模块中验证。

接入源码位于 `example/ja-agent-example-springboot/src/main`，测试位于该模块的 `src/test`。
`SpringBootAgentExample` 展示普通 `@Bean` 与真实任务接口，由宿主注入 `DataSource`、模型配置和真实
`ContextBudget`，组合 PostgreSQL Store、PostgreSQL Skill 来源及自定义 Tool；Core 不会自行创建连接池，
也不会从 `user.home` 读取或写入配置。Windows JDK 25 下可单独运行 HTTP/SSE 与 HTTP MCP 验证：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml '-Dja.build.directory=target-agent-demo' `
  -pl :ja-agent-example-springboot -am '-Dtest=AgentExampleStreamingTest,FastMcpHttpTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' test
```

测试使用隔离的 Embedded PostgreSQL 和受控 Chat Completions SSE 服务，不连接业务数据库或外部模型；
FastMCP 测试需要 uv 与已同步的 Python 依赖。完整接入流程见[Spring Boot 接入说明](../example/ja-agent-example-springboot/README.md)。

## 在其他 Maven 工程接入

Core 与扩展是独立 Maven 产物，不依赖 Ja App Server。按数据库选择 Store 和 Skill 模块：SQLite 使用
`ja-agent-store-local` 与 `ja-agent-skills-local`；PostgreSQL 使用 `ja-agent-store-pg` 与
`ja-agent-skills-pg`。Core 不创建连接池，PostgreSQL 扩展接收业务方的 `DataSource`。Spring 项目沿用相同依赖，
通过普通 `@Bean` 注册 Engine；Ja 不提供 Spring Starter。

本次变更不发布 Maven 制品。当前源码版本可先安装到本机 Maven 仓库，再由独立工程声明依赖：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml -DskipTests `
  -pl .,ja-agent-core,extensions/ja-agent-store-pg,extensions/ja-agent-skills-pg -am install
```

```xml
<properties>
    <ja-agent.version>0.2.0</ja-agent.version>
</properties>
<dependencies>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-core</artifactId>
        <version>${ja-agent.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-store-pg</artifactId>
        <version>${ja-agent.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-skills-pg</artifactId>
        <version>${ja-agent.version}</version>
    </dependency>
</dependencies>
```

宿主需显式提供模型配置、真实 `ContextBudget`、Store 和自己授权的 Tool；需要数据库 Skill 时再注册
`PostgresSkillSource`。当前独立可运行接入组合参考上文 Spring Boot 示例；不再提供纯 Java 示例模块。远程 Maven 仓库发布不属于当前实施范围。

## 构建和测试

从仓库根目录通过 Maven 聚合工程构建：

```powershell
$env:JAVA_HOME = '<Liberica-NIK-25-home>'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn.cmd -version
mvn.cmd -B -ntp -f ja-agent/pom.xml test
```

只构建 Ja App Server 及其模块依赖时可使用 `-pl ja-app-server -am`。Native Image 仍由
`ja-agent/ja-app-server/pom.xml` 的 `native` profile 提供；产物写入 `ja-agent/ja-app-server/target/ja-app-server(.exe)`，
发布流程再将它 staging 为 Tauri `sidecars/` 资源。

## 何时拆分其它服务模块

只有在新增单元具备独立进程入口、独立依赖/生命周期、独立协议或权限边界，且
需要单独测试、版本化、SBOM 或发布产物时，才新增并列服务目录。
仅仅为了拆 Java 包、工具类或 Ja 专属功能，不新增 Maven module；Agent 通用逻辑归 Core，Ja 专属逻辑归
`ja-agent/ja-app-server/src/main/java`，并保持唯一 App Server 入口。
