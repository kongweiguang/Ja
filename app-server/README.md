<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja App Server

`app-server/` 是 Ja 唯一可发布的 Java 应用服务工程。它使用 Java 25、Solon 4.0.6
并内置 Ja 原生 Agent Kernel，既支持 JVM 调试运行，也支持 GraalVM Native Image 构建。
`app-server/pom.xml` 是独立的可运行 Maven 项目，不是 Maven parent，也不是额外
的聚合模块。对外产物统一命名为 `ja-app-server`；`sidecar` 只描述它被 Tauri 托管
时的部署关系，不作为服务本身的产品名称。

## 运行边界

Ja App Server 作为本地 sidecar 运行，只通过 stdin/stdout 与 Tauri/Rust host 通信，边界协议是受版本约束的
JSONL/JSON-RPC。stdout 仅用于协议帧；日志、诊断和异常必须走 stderr 或日志
文件。Java Kernel 是 Turn、Tool、Skill、MCP、Permission、Approval、Cancellation
和 SQLite 会话事实的唯一运行时核心。Java 也是 `config.toml`、`auth.json`、
配置 generation、Workspace identity 与凭据持久化的唯一 owner；Rust 只拥有
sidecar/stdio 生命周期、Windows 进程树与桌面原生能力，React 只消费脱敏投影。
Provider 是用户自定义连接；名称不参与路由，每条配置按所选 API 规范决定请求、鉴权与 Usage 解析，并通过
自己的凭据引用取得 API Key。每次模型请求前解析最新环境并在本地估算最终 envelope 的 Token 上界，实际 Usage 只取正式
响应并由 Java 持久化。

## 日志目录

Rust Host 在启动 Ja App Server sidecar 前创建并校验 `home/data/run/log` 四个独立目录，再通过
Base64URL 参数传给 JVM 或 Native Image；Java 从 `home` 自行读取配置和凭据，并把日志
固定写入 `logs/java`，不依赖 cwd 推断生产路径。
`app-server.log` 保存 INFO 以上运行日志，`app-server-error.log` 单独保存 ERROR，二者按日
及 20 MiB 分片滚动并保留 30 天。stdout 始终只承载 JSONL/JSON-RPC，WARN 以上
诊断才同时写入 stderr。

## Java 包职责

分包采用 DDD 的限界上下文与六边形依赖方向，但不机械创建空的 `entity/service/repository` 层。
一级业务域保持为 `conversation`、`configuration`、`workspace`、`catalog`；域内只在存在真实
`domain/application/port/adapter` 边界时继续分层。`bootstrap` 负责 Solon 启动和组合，
`foundation` 提供无业务归属的基础类型，`infrastructure`、`platform` 与 `transport` 位于外层。

高密度实现按下列职责继续细分：

| 路径 | 叶子职责 |
| --- | --- |
| `conversation/adapter/out/provider` | `anthropic`、`openai` 保存厂商原生协议；`shared` 保存 Provider 中立 HTTP/SSE、重试与取消基础设施；`summary` 独立收口结构化摘要 |
| `conversation/application/context` | `checkpoint` 定义持久化边界，`compaction` 编排预算压缩，`summary` 定义摘要文档和调用端口 |
| `configuration/adapter/out` | `document` 管配置文档与 Watcher，`generation` 管不可变代际和租约，`security/windows` 管凭据与 Windows 安全文件 |
| `catalog/adapter/out/mcp` | `generation` 投影配置代际，`runtime` 编排目录与调用，`session` 隔离 SDK Client，`transport` 实现 stdio/HTTP，`support` 保存不可变共享值 |
| `infrastructure/persistence` | `database` 持有 SQLite/Flyway 生命周期，`mapper` 定义 SQL 边界，`repository` 实现领域端口，`transaction` 统一提交回滚，`recovery` 编排启动恢复 |
| `transport/rpc` | `protocol` 冻结 JA-RPC v1 Wire 合同，`handler` 适配用例，`runtime` 持有连接、路由、关闭屏障与 stdout 单写者 |

每个职责叶子包都有 `package-info.java` 说明允许内容与禁止边界；ArchUnit 检查领域纯度、
六边形依赖方向、顶层包无环，以及 Provider、MCP、持久化、RPC、配置和 Context 的关键职责边界。

模型固定看到 `read/edit/write`；只有进程级 Shell 预检成功时才额外看到 `shell`，复杂 Tool
在 Provider 请求安全点按需发现，已生成 Tool batch 通过不可变 binding 精确路由。Windows 依次尝试 `PATH` 中的 PowerShell 7 和系统
Windows PowerShell 5.1；两者都不可用时，执行环境明确标记 `shell: unavailable`，App Server
仍保留配置、历史与 RPC 恢复面，不注册一个必然失败的 Shell Tool。Tool 在 Java App Server
内执行，文件访问只受当前操作系统账户权限约束，Rust 不再提供 Host Tool 或 Sandbox RPC。
旧 Tool 别名、旧权限/审批枚举、`change/*`、`runtime/configure` 和
`initialize.configSnapshot` 均已删除且 fail closed。活动 Turn 的权威输入队列由
`turn/input/enqueue`、`turn/input/prioritize`、`turn/input/update` 与 `turn/input/delete`
维护；Steering 在安全点优先消费，普通输入保持 FIFO，队列随 `thread/read` 和全量事件恢复。

## Plan / Goal

Plan 与 Goal 是两个独立聚合。`CollaborationMode=plan` 只控制当前 Thread 的计划协作语义；Goal 可依据冻结的
objective 与验收条件直接持续执行，不要求先创建或批准 Plan。两者只通过显式、可解除的 `GoalPlanLink`
关联：未关联 Goal 使用 Goal-only run；已关联 Goal run 才绑定获批的精确 Plan revision/hash。二者都与
`AccessMode=approval_required|full_access` 正交，切换模式、创建 Goal 或建立关联都不会扩大 Tool 权限。
Goal、Plan、PlanRevision、Approval、Run、StepExecution、Evidence、Evaluation 与可选 Link 由 Java 25
App Server 和 SQLite 唯一持有。Plan 使用结构化 canonical JSON SHA-256；Goal 与 Plan mutation 分别使用各自
revision CAS 和幂等键，批准及显式 Plan 执行必须绑定精确 revision/hash。Rust 只代理 typed JA-RPC，React
不计算状态、hash 或完成结论。

当前源码已经具备独立 Goal/Plan domain、V1 persistence、repository、完成门、recovery/lease 基础，以及
编译期注册的 `PlanGoalAgentCapability`。计划模式会在请求 profile 中加入 `plan_propose`、`plan_step_update`；
活动 Goal 上下文按需加入 `goal_request_input`、`goal_request_evaluation`。这些 Agent Tool 仍经过现有权限和
内核审批，standalone Plan 的内部 Turn 事件只返回发起连接，Goal continuation 事件继续按 Goal observation 路由。

截至 2026-09-05，`RpcServer` 已注册 Goal/Plan Handler 并发布 `goal/changed`、`goal/activity`、
`goal/input-requested`；`GoalContinuationCoordinator`、独立无 Tool evaluator、启动恢复、Rust typed proxy 与
React 权威投影均已进入生产 composition。桌面入口仍以握手的 `plan_goal_v1` capability 为上限；Native Image、
隔离 Windows Tauri/WebView2 真窗矩阵和 120 分钟 mock soak 是发布前 Gate，不得由局部单测替代。

## Agent 能力与扩展边界

能力以 Java 25 的窄端口和显式组合实现，不提供动态插件加载、独立扩展状态库或第二套 Agent Runtime。
`SolonRuntimeComposition` 注册 `AgentCapability`，`AgentCapabilityCatalog` 固定身份和顺序；
Plan/Goal 与 Task 保留各自用例和状态机，仅将模型说明与工具适配到统一请求目录。

能力先 `prepare` 一次冻结领域状态和现有 `ToolSpec` 安全描述，再由 Resolver 合并内置、能力、MCP
目录并校验 Task 权限上限，最后 `bind` 最终目录身份。两阶段只为消除 Task 权限上限与目录摘要的
相互依赖，不重复读取领域状态；绑定后的工具必须与准备时的描述、权限元数据和路由完全一致。
Provider、Skills 和权限仍在请求安全点刷新，已生成工具批次不会按同名工具重新路由。

- `ContextTransform` 同步增删不可变的派生 System 片段，结果在计量前进入 Prompt revision。
  AGENTS、Skills、历史消息、身份和凭据不暴露给该接口；不支持历史消息改写。
- `ToolPolicy` 是只能继续或拒绝的前置责任链；异常失败关闭，内核审批不可被移除或替换。
- `ExecutionObserver` 按类型订阅安全执行元数据，不接收 API Key、参数或结果正文；观察故障不改变结算。
- `AgentTool.sideEffect()` 默认 `EXTERNAL`，只读工具明确声明 `READ_ONLY`，不再按名称推断。
  副作用与 `WorkspaceMutationMode` 独立，并共同进入目录和绑定身份。MCP 路由摘要由 MCP owner 生成。

工具真实结果先经内核结算和持久化，再通知观察器；必要的规则刷新失败也不能丢失已经执行的结果。
普通 Hook 必须短时、同步、无 IO，不拥有资源；IO 与关闭仍由现有适配器、租约、取消和 Deadline 负责。
编译期 Java 扩展是可信进程内代码，不是沙箱，也不能以 Future 超时宣称已经强制终止实现。

扩展验收见 `AgentCapabilityIntegrationTest`（真实 Prompt factory、Loop、临时文件和 SQLite），
职责方向由 `HexagonalArchitectureTest` 固定。JVM 测试、Native 构建和 Windows 真窗验收是不同证据层级。

## 构建和测试

在仓库根目录执行：

```powershell
$env:JAVA_HOME = '<Liberica-NIK-25-home>'
mvn.cmd -B -ntp -f app-server/pom.xml test
```

Native Image 构建使用同一个 `app-server/pom.xml` 的 `native` profile；发布
流程再把 `app-server/target/ja-app-server(.exe)` staging 为 Tauri `sidecars/` 资源。

## 何时拆分其它服务模块

只有在新增单元具备独立进程入口、独立依赖/生命周期、独立协议或权限边界，且
需要单独测试、版本化、SBOM 或发布产物时，才新增并列服务目录。
仅仅为了拆 Java 包、工具类或功能文件，不新增 Maven module；应继续在
`app-server/src/main/java` 内按职责分包并保持唯一 app-server 入口。
