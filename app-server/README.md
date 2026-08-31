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
| `transport/rpc` | `protocol` 冻结 JA-RPC v2 Wire 合同，`handler` 适配用例，`runtime` 持有连接、路由、关闭屏障与 stdout 单写者 |

每个职责叶子包都有 `package-info.java` 说明允许内容与禁止边界；ArchUnit 检查领域纯度、
六边形依赖方向、顶层包无环，以及 Provider、MCP、持久化、RPC、配置和 Context 的关键职责边界。

模型固定看到 `read/edit/write`；只有进程级 Shell 预检成功时才额外看到 `shell`，复杂 Tool
由当前 Turn 冻结的 MCP 列表提供。Windows 依次尝试 `PATH` 中的 PowerShell 7 和系统
Windows PowerShell 5.1；两者都不可用时，执行环境明确标记 `shell: unavailable`，App Server
仍保留配置、历史与 RPC 恢复面，不注册一个必然失败的 Shell Tool。Tool 在 Java App Server
内执行，文件访问只受当前操作系统账户权限约束，Rust 不再提供 Host Tool 或 Sandbox RPC。
旧 Tool 别名、旧权限/审批枚举、`change/*`、`runtime/configure` 和
`initialize.configSnapshot` 均已删除且 fail closed。活动 Turn 支持持久 FIFO 的
`turn/steer` 与 `turn/follow-up`，React 只提供逐条追加，不提供队列重排或编辑。

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
