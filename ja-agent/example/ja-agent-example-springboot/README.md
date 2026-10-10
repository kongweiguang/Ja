<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Spring Boot Agent 对话

Java 25 / Spring Boot 4 MVC 示例，直接依赖 Ja Core 与 PostgreSQL Store。
提供原生图片/PDF 对话、自定义企业 Middleware、同事务笔记归档，以及流式回答、Skills、HTTP MCP、审批/澄清、输入队列和恢复。
聊天工作台由 `frontend/` 下的 React + TypeScript 实现；Maven 打包时构建到 Spring 静态资源目录，部署后页面与 API 同源。
Spring 基础配置保留 YAML；Agent 配置和模型密钥按用户选择明文保存在独立演示库中，启动时直接读取。
模块目录名、Maven `artifactId` 与项目名统一为 `ja-agent-example-springboot`，Java 根包为 `io.github.kongweiguang.ja.agent.example.springboot`。

## 代码入口

```text
SpringBootAgentExample         应用启动
bootstrap/                     Spring 装配、数据源配置读取、Engine 与扩展注册
identity/                      可替换 CurrentActorProvider；准入冻结租户与用户
attachment/                    图片/PDF 字节、上传幂等、元数据、预览与 Core Reader
agent/domain/AgentDefinition   完整 Persona 的校验规则
agent/application/AgentCatalog 角色选择、公开目录与启动快照
agent/port/out/                Agent 定义读取端口
agent/adapter/out/jdbc/       PostgreSQL Agent 定义与缺失行播种
conversation/domain/           会话目录标题、归档与准入规则
conversation/application/      对话、流、Thread 用例及共用命令/读模型
conversation/port/out/         Thread 目录持久化端口
conversation/adapter/in/web/   POST/SSE 控制器、HTTP 查询 DTO 与错误映射
conversation/adapter/out/jdbc/ Thread 目录 SQL；与 Core Store 共事务
experience/domain/            笔记回执和幂等核验
experience/application/       Skill 管理与笔记用例
experience/port/out/          Skill、笔记持久化端口
experience/adapter/out/       PG Skill/笔记、HTTP MCP 与 Core 工具适配
mcp/server.py                 uv 管理的独立 FastMCP HTTP 服务
frontend/src/                 React 工作台与 POST/SSE 客户端
target/classes/static/        Maven 打包时生成的同源页面资源
```

调用顺序：浏览器 → Web Adapter → Application → Domain / Port → PostgreSQL Adapter 或 Ja Core。
Core Store 管理执行事实；会话目录只保存列表元数据，并在 Core 提供的同一事务连接中更新。
Agent 定义在启动时从数据库读取并冻结；应用层按 Thread 绑定选择完整 Prompt，Core 继续组装 Skill 和摘要。
Skill 管理端口与 Engine 注册复用同一个 PG 来源。附件引用、笔记、来源与执行事实使用 Store 同一 JDBC Connection 提交；钩子与工具不另开业务写入事务。
Spring 按依赖顺序关闭 Engine、Store、DataSource。

## 聊天界面

页面沿用 Ja 桌面截图的灰色单行侧栏、白色对话区、紧凑标题栏和底部输入框。左栏选择创建会话时使用的真实 Agent，查看最近/归档会话；搜索不切走当前对话或丢弃草稿，关闭搜索后清除筛选。“设置”只管理浏览器外观偏好，不修改服务端模型配置。

输入框内展示当前 Thread 的 Agent 和真实模型名，`+` 提供上传资料和 Skill 选择；可拖放文件或粘贴图片。附件卡片支持取消、重试、预览、下载和移除，上传未确认时保留草稿并暂缓发送，只有附件也能发送。运行中停止按钮与追加输入共用底部工具栏；Tool 正常结果默认折叠，失败展开诊断。流式输出在底部自动跟随，上翻后保持阅读位置；切换会话隔离草稿、上传和预览，迟到响应归原 Thread。

窄屏导航与能力面板使用抽屉，不挤压聊天正文。会话操作菜单及重命名弹窗支持键盘与焦点管理；重命名冲突留在弹窗内，控制器读取最新元数据后，显式再次保存才使用新版本。归档会话只读，恢复后才允许继续发送。

构建选择器为 `:ja-agent-example-springboot`。本轮输入接口统一使用嵌套 `content`，调用方必须同步替换旧顶层 `text/skillIds`；不提供旧请求别名。

## 配置与启动

[application.yml](src/main/resources/application.yml) 保留 `server`、`spring.datasource`、连接池与 MVC 配置。
数据源默认使用独立演示库 `ja_agent_demo`，`org.postgresql.Driver` 与 `sslmode=disable`、`reWriteBatchedInserts=true`；企业按实际网络环境覆盖 JDBC URL。
数据库连接密码由 YAML 环境变量占位符提供，Agent 配置由 `ja_agent_demo_config` 的 `profile=demo` 行读取。

| 表 | 用途 |
| --- | --- |
| ja_agent_demo_config | revision bigint、config jsonb、updated_at_ms bigint；模型/上下文/MCP 配置 |
| ja_agent_demo_agent | agent_id、name、system_prompt；Spring 宿主维护的完整 Agent Persona |
| agent_skill_module_version / agent_skill_package / agent_skill_resource / agent_skill_catalog_revision | PG Skills 模块基线版本、Skill 版本、正文、资源与目录修订号 |
| ja_agent_demo_thread | operation_key（按租户和用户唯一）、is_title_initialized、is_title_customized、is_archived、version、created_at_ms、updated_at_ms；Thread 目录元数据，Core 仍保存执行事实 |
| ja_agent_demo_attachment | 归属、上传操作键、格式/大小/摘要、不可变 bytea、引用与草稿清理事实 |
| ja_agent_demo_note / ja_agent_demo_note_source | owner/Turn/callKey 笔记回执与来源关联；由 Store participant 原子保存 |
| `agent_schema_version`、`agent_conversation`、`agent_execution`、`agent_record`、`agent_event`、`agent_operation_receipt`、`agent_commit_receipt`、`agent_lease` | Core Store 的会话锚点、执行记录、事件、回执和租约 |

数据库时间以 UTC Unix epoch 毫秒整数存储；HTTP API 时间仍使用 ISO-8601 格式。

首次启动仅在配置行不存在时用 `JA_EXAMPLE_MODEL_*` 环境变量播种；已有配置不会被环境变量覆盖。
默认 Spring `AgentStore` Bean 在构造 `PostgresAgentStore` 前显式调用 `PostgresAgentStore.bootstrap(dataSource)`；bootstrap 负责创建缺失的 Core schema 或校验已有 schema，Store 构造函数只做 schema 校验。示例宿主提供并持有 DataSource/连接池，初始化所需的 `agent_*` DDL 权限；若宿主自行提供 `AgentStore` Bean，则由宿主负责先初始化 Core schema。Ja App Server 使用不同 owner：`JaDatabase/Flyway` 初始化完整 Ja 数据库，之后由组合根构造 Store 校验，不再由 Core Store 单独 bootstrap。
PG Skills 使用独立模块基线。Spring 的 `ExperienceConfiguration` 显式调用 `PostgresSkillSchema.install(DataSource)`：本模块为空时初始化，已有模块的版本、列、约束或索引不匹配时拒绝启动，不补表或迁移；`PostgresSkillSource` 只校验准入，DataSource 仍归宿主持有。

`config` 示例如下，凭据值为占位符：

```json
{
  "model": {
    "providerId": "provider_demo", "modelId": "model_demo",
    "api": "OPENAI_RESPONSES", "name": "gpt-6-luna",
    "baseUri": "<Provider base URI>", "apiKey": "<API key>",
    "reasoningLevel": "medium", "connectTimeoutSeconds": 10, "requestTimeoutSeconds": 120,
    "inputModalities": ["TEXT", "IMAGE", "PDF"]
  },
  "context": {"windowTokens": 256000, "maxOutputTokens": 8192, "autoCompact": true},
  "mcpServers": [{"id": "demo", "url": "http://127.0.0.1:8091/mcp", "headers": {}}]
}
```

模型 api 是 `OPENAI_RESPONSES / OPENAI_CHAT_COMPLETIONS / ANTHROPIC_MESSAGES`。`inputModalities` 必填且包含 TEXT，IMAGE/PDF 只声明实际模型支持的输入；上传与发送仍取 Provider Codec 交集。新库通过 `JA_EXAMPLE_MODEL_INPUT_MODALITIES=TEXT,IMAGE,PDF` 播种；已有配置或表结构不符合新基线时明确失败，不自动迁移、重置或清空。
模型/服务身份、真实上游模型名必填；apiKey 按明文存储。网络超时为 1..3600 秒，只约束单次请求。
windowTokens ≥1024，maxOutputTokens 为 1..windowTokens；必须按实际模型能力填写。
MCP 服务最多16个，id 为小写字母开头的字母/数字/连字符（最长64）；url 为完整 HTTP/HTTPS 地址，
不支持查询参数、URL 用户信息或片段。headers 是可省略的请求头对象，可直接保存明文 Authorization。
Spring 启动时用一条查询读取完整明文 JSON，不提供另一套配置管理接口或凭据转换层。
修改配置表后递增 revision 并重启 Spring Boot；revision 用于标识冻结到 Engine 的配置代际。
Skill 管理是实时版本化更新，不需要重启；仅播种缺失的 brief-reply 和 tool-audit。
Agent 定义同样只播种缺失的 `general`（通用助手）和 `analyst`（数据分析助手）。可在
`ja_agent_demo_agent` 插入新的 `agent_id`、`name`、`system_prompt`，或修改已有角色正文；
重启 Spring 后新的定义才进入当前进程的冻结快照。`agent_id` 使用小写字母开头、最长 64 字符的
小写字母/数字/连字符；名称不超过 80 字符，非空提示词不超过 32768 字符。
Spring 将该完整正文传入 Core 的 `new AgentEngineBuilder().systemPrompt(...)`；Core 继续组装
Skill、摘要和 Prompt revision。模型收到的是所选 Agent 的正文，不再叠加 Core 默认英文 Persona。
角色文本不授予工具或权限，实际能力仍由 Spring 注册的工具、Skill 来源和策略决定。
例如在演示库中新增一个角色，重启后即可在新对话下拉框选择：

```sql
INSERT INTO ja_agent_demo_agent (agent_id, name, system_prompt)
VALUES ('reviewer', '审阅助手', '你是审阅助手。先指出可核实的问题，再给出简短建议；不确定时明确说明。');
```

修改已有角色使用 `UPDATE ja_agent_demo_agent SET system_prompt = '...' WHERE agent_id = 'reviewer';`。
该修改影响重启后的模型请求；已有 Thread 保留 `agent_id`，因此同名角色正文更新也会作用于其后续 Turn。

Python 服务独立运行，Spring 不启动或安装 Python：

```powershell
uv sync --project ja-agent/example/ja-agent-example-springboot/mcp
uv run --project ja-agent/example/ja-agent-example-springboot/mcp ja-agent/example/ja-agent-example-springboot/mcp/server.py
```

`pyproject.toml + uv.lock` 固定 FastMCP 依赖。默认 Streamable HTTP 地址是 http://127.0.0.1:8091/mcp，
`add(a,b)` 返回十进制加法结果，`now()` 返回真实 UTC 时间。
可设置 `JA_DEMO_MCP_PORT` 调整 Python 端口，并同步数据库中的 MCP 地址。

Windows 仓库根目录：

```powershell
$env:JAVA_HOME = 'C:\Users\24052\.jdks\liberica-25.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn.cmd -version
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am package
$env:JA_EXAMPLE_DB_PASSWORD = '<database-password-placeholder>'
$env:JA_EXAMPLE_MODEL_BASE_URI = '<Provider API base URI>'
$env:JA_EXAMPLE_MODEL_API_KEY = '<API key>'
pwsh -NoProfile -File ja-agent/example/ja-agent-example-springboot/run-demo.ps1
```

默认地址 http://127.0.0.1:8087，根路径即聊天页。可用 YAML 或 `JA_EXAMPLE_PORT` 修改端口。
Spring 的端口和数据库连接可用 YAML / Spring 外部配置覆盖；Agent 参数直接修改配置表。
示例不读取 Ja 配置文件或用户凭据；接入方准备演示数据库和 DataSource，示例默认 Bean 只初始化 Core 自有 `agent_*` 表，不创建连接池。

前端单独开发时先启动 Spring 后端，再从仓库根目录运行 `pnpm dev:example:spring`；Vite 使用 5173 端口并将 `/api` 代理到 8087。
前端检查命令为 `pnpm typecheck:example:spring`、`pnpm test:example:spring` 与 `pnpm build:example:spring`。
Maven `package` 阶段会执行最后一条构建，并将页面资源写入 `target/classes/static/`；运行打包后的示例不需要 Vite。

## 对话接口

接口为企业宿主接入示例契约，默认地址 http://127.0.0.1:8087。对话动作使用 POST + JSON，
`Content-Type: application/json`；附件上传使用 multipart，内容预览使用 GET + 路径/查询参数。身份始终由服务端提供，SSE 不使用 Last-Event-ID。以下 JSON 均为脱敏协议示例，不能代替本地运行结果。

### 身份与命名

| 字段 | 含义 | 来源与约束 |
| --- | --- | --- |
| userId | 当前业务用户 | 服务端 `CurrentActorProvider` 默认提供 user_demo，客户端不传；企业替换此 Bean 接入既有认证 |
| threadId | 一段持续对话，对应 Ja Thread / Core threadId | 由 `/api/chat/threads/create` 返回，示例使用 thr_ 前缀 |
| turnId | 一次用户提交及其后续执行，对应 Ja Turn / Core turnId | 请求体必填字符串，示例生成 turn_ 前缀；追问换新值 |

Core tenantId 是租户隔离空间；`CurrentActorProvider.currentActor()` 默认返回 `CurrentActor("tenant_demo", "user_demo")`。身份在 HTTP 准入取得后冻结，Thread 查询、附件、异步工具和 Store participant 使用同一 owner；请求正文不接受 tenantId/userId，异步线程不读取登录 ThreadLocal。
threadId、turnId、operationKey、recordId、interactionId 采用 Core 标识约束：1..256 个 ASCII 字母、数字、点、下划线、冒号或连字符；首字符为字母或数字。
Thread/Turn 是本宿主的业务命名，示例未接入 Ja App Server 的 Thread 列表或 JA-RPC。
企业已有认证时，只需声明自有 `CurrentActorProvider` Bean，从经过验证的登录主体返回稳定租户与用户标识。默认演示 Bean 条件注册；不额外实现登录、JWT 或用户管理。默认固定身份入口不能作为公开服务的认证。

### 页面实际调用顺序

1. `POST /api/chat/model`，正文 `{}`，取得真实模型名称。
2. `POST /api/chat/threads/agents` 读取可选 Agent，`POST /api/chat/threads/list` 读取活动会话；创建时调用 `/api/chat/threads/create` 并选定 agentId，用返回的 threadId 发起对话。
3. 新消息分配独立 turnId 和稳定 operationKey，再调用 `POST /api/chat/stream` 并观察同一条 SSE 连接。
4. 接收 accepted、text-delta / reasoning-delta 和 agent-event；收到 agent.model.completed 后，使用事件的 messageRecordId、usageRecordId 调用 `POST /api/chat/message`，将草稿替换为持久消息。
5. 接收 agent.execution.terminal，以 state 和 summary 结束展示；COMPLETED、FAILED、CANCELLED 分别表示完成、失败和取消。
6. 打开历史会话时调用 `/api/chat/threads/history` 分页读取公开消息；继续追问复用 threadId，换新 turnId 和 operationKey，Core 从 PG 读取同 Thread 上下文。

创建请求使用可重试的操作键：

```http
POST /api/chat/threads/create
Content-Type: application/json

{"operationKey":"create_01","agentId":"analyst"}
```

响应示例：

```json
{"threadId":"thr_example01","agentId":"analyst","title":"新对话","archived":false,"version":0,
 "createdAt":"2026-09-28T10:00:00Z","updatedAt":"2026-09-28T10:00:00Z"}
```

`agentId` 省略时默认为 `general`；已有会话也使用 `general`。创建时角色固定，后续提交、工具后续轮次及恢复均按该 Thread 的角色选择 Prompt。网络结果未知时重用原 `operationKey` 和 `agentId`，服务端返回原 Thread；同键改选角色返回 409，未知角色返回 400。Thread 元数据行与 Core 会话锚点同一 Store 事务提交。

列表、重命名、归档与历史读取也使用 POST + JSON：

| 地址 | 请求正文 | 响应与约束 |
| --- | --- | --- |
| `/api/chat/threads/agents` | `{}` | `[{"id":"analyst","name":"数据分析助手"}, ...]`；只返回 ID 与名称，不返回 Prompt 正文 |
| `/api/chat/threads/list` | `archived`、`search`、`limit`、`offset` | `ThreadView[]`，按最近更新时间降序；limit 为 1..100，offset 为 0..100000，search 最多 128 字符且只匹配标题 |
| `/api/chat/threads/update` | `threadId`、`expectedVersion`，以及 `title`、`archived` 至少一项 | 更新后的 `ThreadView`；标题去除首尾空白后为 1..120 字符，陈旧版本返回 409 |
| `/api/chat/threads/history` | `threadId`、`afterSequence`、可选 `throughSequence`、`limit` | `HistoryPage`；limit 为 1..256，throughSequence 首次可省略 |

`update` 通过 expectedVersion 比较并交换元数据版本。显式提交 `title` 会记录为用户自定义，即使标题改回“新对话”也不会被消息覆盖。归档与恢复使用同一接口分别设置 `archived:true` 和 `archived:false`；运行、等待或暂停中的 Thread 不能归档，恢复不会清除历史。首条已准入用户消息只初始化一次默认标题；即使消息文本恰好是“新对话”，后续消息也不会重新命名。

首次读取历史省略 `throughSequence`，服务端返回本次读取固定的记录水位。后续页将上一页的 `nextSequence` 作为 `afterSequence`，并原样带回 `throughSequence`，避免新提交混入旧历史页。`HistoryPage.items` 只投影公开用户消息、Assistant 正文/公开思考摘要、工具调用/结果，以及 `usage`、`wait` 的记录引用；每项包含 `sequence`、稳定记录标识 `recordId`、`turnId`、`kind`，并按类型提供 `text`、`reasoning`、`callId`、`name`、`state`。`usage` 和 `wait` 仅返回引用，分别使用 `/usage` 和 `/wait` 按原 Turn 读取当前公开事实。同一 Core 记录拆出的多个展示项共享 `recordId`，可用于断线重放去重。`latestTurn` 是当前最新执行的 `turnId`、`state`、`updatedAt`，还没有执行时为 null。

历史记录的 `afterSequence` / `nextSequence` / `throughSequence` 属于 Store 记录水位；`/events` 与 `/reconnect` 的 `afterSequence` 属于事件水位，两者不能互换。切换或刷新只重建观察和展示；断线后使用原 threadId、turnId 与已保存事件水位调用 `/reconnect`，不得再次提交正文。等待已回答不代表执行已恢复，只有显式调用 `/resume` 后才继续原 Turn。

使用创建接口返回的 threadId 发送第一条消息：

```http
POST /api/chat/stream
Content-Type: application/json

{"threadId":"thr_example01","turnId":"turn_01","operationKey":"submit_01",
 "content":{"text":"请记住我的名字是小明。","skillIds":[],"attachmentIds":[]}}
```

第二次消息复用 Thread：

```json
{"threadId":"thr_example01","turnId":"turn_02","operationKey":"submit_02",
 "content":{"text":"我叫什么名字？","skillIds":[],"attachmentIds":[]}}
```

### 请求与响应

下表所有动作都是 POST，地址前缀为 `/api/chat`。省略的公共身份均为正文中的 threadId、turnId。

| 地址 | 请求正文（除公共身份） | 响应与下一步 |
| --- | --- | --- |
| /model | {}，无需公共身份 | 200，model、experience、attachmentCapabilities；不返回密钥或连接地址 |
| /stream | operationKey、content | 200 SSE，提交后观察到持久终态 |
| /submit | 与 stream 相同 | 202，identity；之后 reconnect 或查询 status |
| /reconnect | afterSequence，可省略默认 0 | 200 SSE，只补读原 Turn，不重新提交 |
| /status | 无额外字段 | 200，identity、state、threadRevision、turnMutationVersion、updatedAt、resumeRequired |
| /events | afterSequence 默认 0、limit 默认 256（1..10000） | 200，events、nextSequence、hasMore |
| /events/stream | afterSequence 默认 0 | 200，只含持久事件的 SSE |
| /message | recordId 必填、usageRecordId 可空/可省略 | 200，modelRequestId（可空）、text、reasoning、calls |
| /inputs | operationKey、content | 204，输入已排队，下一安全点消费 |
| /cancel | operationKey、reason | 204，取消意图已保存，继续观察 CANCELLED |
| /resume | 无额外字段 | 202，identity；继续原 Turn |
| /wait | recordId 来自 wait.requested 的 requestRecordId | 200，interactionId、interactionRevision、status、kind、questions |
| /answers | operationKey、interactionId、expectedInteractionRevision、answers | 204，随后显式 resume |
| /recoveries | 无额外字段 | 200，threadId、turnId、recoveries；仅返回待裁决项 |
| /recoveries/resolve | recoveryId、callId、expectedRecoveryRevision、operationKey、decision | 204，裁决已保存；随后 resume 原 Turn |

受理响应示例（submit / resume，以及 SSE accepted 的 data）：

```json
{"identity":{"userId":"user_demo","threadId":"thr_demo","turnId":"turn_01"}}
```

operationKey 必填，同一键重试不得改变正文；提交、追加输入、回答、取消使用独立稳定键。
content 必填；text 可空或省略，最大 4,000,000 字符；skillIds/attachmentIds 省略默认空数组。Skill 最多32项，附件最多10项；只有 Skill 不构成可执行输入，文本和附件至少一项存在。转换顺序固定 Skill→附件→正文。reason 为必填非空字符串、最大 512 字符，不允许控制字符。
afterSequence 是非负整数，排他水位按 Thread 递增，过滤当前 Turn 后允许不连续；limit 为整数。
updatedAt、occurredAt 为 ISO-8601 UTC。版本和水位为整数。
resumeRequired 为布尔值：持久状态为 RUNNING/QUEUED，但当前宿主没有活动执行句柄。它提示用户显式核实，
不把未知结果改成 SUSPENDED，也不授权直接重试工具；其它实例仍可能持有租约，恢复时由 Core 决定接管资格。

message 的 recordId 使用模型完成事件的 messageRecordId 或工具完成事件的 resultRecordId。
usageRecordId 使用同一 agent.model.completed 的引用；服务核对同 Turn、同模型轮次后返回 modelRequestId。
calls 每项为 callId、name；只返回公开正文与推理摘要，不返回供应商隐藏推理、配置或凭据。

### SSE 与恢复

发送顺序：stream → accepted → text-delta / reasoning-delta → agent-event → agent.execution.terminal。
草稿 data 为 modelRequestId、text，没有持久 SSE id；reset 撤销对应模型调用草稿，resync 表示订阅溢出，应清空草稿并 reconnect。
收到 `recovery-required`（data 为 `{ "reason": "execution-unobserved" }`）时停止自动重连，查询 status/recoveries并提示用户继续原Turn核实。
该宿主观察事件没有持久 SSE id，不是业务终态。页面刷新后也按相同规则核对；不得重新提交正文或自动重放工具。
agent-event 信封为 threadId、turnId、eventId、eventType、payloadSchemaVersion、sequence、threadRevision、turnMutationVersion、lastRecordSequence、payloadJson、occurredAt。
payloadJson 是 Core 版本化事件 JSON 字符串，保留原生 eventType（如 agent.execution.terminal）以明确事件契约来源。
读取公开消息及消费事件成功后再保存水位，不用草稿参与水位计算。

断线或刷新：

```http
POST /api/chat/reconnect
Content-Type: application/json

{"threadId":"thr_demo","turnId":"turn_01","afterSequence":12}
```

只关闭观察连接，原 Turn 继续执行。空闲 SSE 每 15 秒发送 keep-alive 注释。
只有 terminal 的 summary 是最终答复；HTTP 200/202/204 均不代表业务完成。
RUNNING、WAITING_INPUT、WAITING_APPROVAL、SUSPENDED 等状态由 Core 管理。

取消：

```json
{"threadId":"thr_demo","turnId":"turn_01","operationKey":"cancel_01","reason":"User cancelled."}
```

将此正文 POST 到 /api/chat/cancel，继续观察直到 CANCELLED。新对话只切换浏览器 Thread，停止旧 Turn 需先取消。
Thread 列表和对话历史由服务端提供。浏览器只保存主题、草稿、Skill 选择、滚动位置及尚未确认的操作身份，不把本地缓存当作对话记录。
刷新后先读取服务端 Thread 列表与固定水位历史，再核对未确认提交的原 status；结果未知时保留原 turnId、正文与 operationKey，用户明确重试后才重新提交。
切换 Thread 只中止当前浏览器的观察连接，不取消服务端执行；回到未完成 Turn 后以原事件水位 reconnect。SUSPENDED 需要用户显式恢复。

### 通用等待

默认 ExperienceExtension 注册7个真实工具。宿主声明 AgentTool / AgentMiddleware / AgentExtension Bean 时，Engine 注册并使用 Core 原生等待。
WAITING_APPROVAL / WAITING_INPUT → POST wait → POST answers → SUSPENDED → POST resume → 继续观察 terminal。
wait 的 questions 包含 questionId、prompt、type、required、allowFreeText、options；type 为 SINGLE / MULTIPLE / TEXT。
options 包含 optionId、label、description、recommended；status 为 Core 大写枚举。
kind 为 `approval` 或 `input`，由持久等待来源决定；页面按该字段显示审批或补充信息，不能凭问题文字猜测授权。
持久等待缺少、类型非法或携带未知 `kind` 时返回完整性错误 500，不再默认解释为 `input`；此时需宿主诊断，不能按 409 状态冲突反复重试。
调用方应保留原执行身份并排查记录，不能替用户回答或重新提交 Turn。
等待是事件引用的固定快照，回答需匹配当前 interactionRevision；审批必须明确选项，自由文本不等于授权。

answers 请求：

```json
{
  "threadId":"thr_demo", "turnId":"turn_01",
  "operationKey":"answer_01", "interactionId":"<POST wait 返回的 interactionId>", "expectedInteractionRevision":0,
  "answers":[{"questionId":"<实际 questionId>","optionIds":["<实际 optionId>"],"freeText":null,"skipped":false}]
}
```

answers 为 1..3 项；freeText 可空、最大 16000 字符。expectedInteractionRevision 使用本次 wait 响应的非负 interactionRevision。
回答提交成功后 POST /api/chat/resume，正文为 {"threadId":"thr_demo","turnId":"turn_01"}。
回答结算事件为 agent.execution.control 的 WAIT_RESOLVED。恢复失败可显式继续；取消后关闭等待表单。

### 错误与变更

错误为 ProblemDetail：参数错误 400，不存在或越界记录 404，状态/幂等冲突 409，已失效上传键 410，超限 413，不支持的媒体 415，存储不可用 503，完整性错误 500。
超时先用原身份查询 status，再使用原操作键重试；409 先刷新状态与版本，不能换键绕过冲突。
GET 对话动作不支持（405）；旧路径已移除。身份缺失或负水位为 400。

调用方统一使用 POST `/api/chat` 动作接口，身份与查询参数放 JSON；对话和执行始终使用 `threadId`、
`turnId`，不暴露 Core 内部的 `tenantId`、`userId` 或身份请求头。
受理和状态响应 identity 使用 userId、threadId、turnId，状态与事件信封的版本名称同步改为 Thread/Turn。
事件 eventType 和 payloadJson 仍是 Core 合同，没有修改 Ja 桌面/CLI 的 JA-RPC 协议。

## 资料上传与原生多模态

先创建 Thread，再读取 `/model.attachmentCapabilities`。该对象包含 `declaredFormats`（配置声明）、`effectiveFormats`（与实际 Codec 的交集）、`maxSingleBytes`、`maxTotalBytes`、`maxAttachments`；格式值为 MIME，大小单位为字节。

| Provider API | 有效格式（模型同时声明 IMAGE/PDF 时） | 单文件 / 同一次请求总量 |
| --- | --- | --- |
| OPENAI_RESPONSES | image/png、image/jpeg、image/gif、image/webp、application/pdf | 50,000,000 / 50,000,000 字节 |
| ANTHROPIC_MESSAGES | 同上 | 32,000,000 / 32,000,000 字节 |
| OPENAI_CHAT_COMPLETIONS | 空列表，当前只支持文本 | 0 / 0 |

每条输入最多10个附件，来自 Core 约束。上传校验真实文件签名与基本结构，multipart Content-Type 不作为内容格式依据；已知后缀与实测格式冲突、损坏或伪装文件返回415，空文件和非法操作键返回400。合法但模型不支持的格式同样返回415。前端能力提示与后端使用同一份快照。

发送前还会检查当前 Checkpoint 保留的有效历史、未消费队列与本次输入的资料总量；队列 UPDATE 替换旧项，重复引用每次计费。
超过实际请求上限返回 413 `REQUEST_TOO_LARGE`，草稿保留。可移除本次待发送资料，或在已完成 Thread 上通过能力面板手动压缩历史后重试。
已提交操作的同键重试仍交给 Core 回读回执、核对指纹。准入预检不替代执行权校验；并发变化或恢复时，Core 发送请求前再次守住真实总量，
本示例不把超限引用降级为“已经读取资料”的文本提示。

| 方法与地址 | 请求 | 响应与业务含义 |
| --- | --- | --- |
| POST `/api/chat/attachments/upload` | multipart 必填 `threadId`、`operationKey`、`file`；一个文件 | 200 `AttachmentMetadata`；必须拿到附件 ID 才可发送 |
| POST `/api/chat/attachments/metadata` | JSON `threadId`、`attachmentIds` 字符串数组，最多2560项 | 200 `{items:[AttachmentMetadata]}`；仅元数据，不读取文件正文 |
| GET `/api/chat/attachments/{id}/content` | 路径附件 ID；必填查询 `threadId`；`download` 布尔值默认false | 200原始字节；inline预览或attachment下载，正确MIME、长度和文件名，no-store/nosniff |
| POST `/api/chat/attachments/discard` | JSON `threadId`、`attachmentIds` 数组，最多2560项 | 200 `{discardedIds:[...]}`；只移除未引用草稿，重复移除幂等 |

所有接口核对服务端 actor、Thread 和附件归属，越界按404处理；归档 Thread 不能上传或发送。文件内容不可更新，只能用新操作键上传新资料。附件元数据格式：

metadata/discard 的 2560 项只约束单次批量查询或移除请求；超出时分批调用。服务端读取长历史和笔记来源时内部按批查询，不以此限制会话累计资料数量。单条输入最多 10 个附件来自 Core `UserContent`，字节上限仍由当前模型与 Codec 决定。

```json
{"id":"att_example01","threadId":"thr_example01","fileName":"预算.pdf",
 "mediaType":"application/pdf","sizeBytes":1536,"sha256":"<64位SHA-256>",
 "createdAt":"2026-10-06T03:00:00Z","referenced":false}
```

上传响应丢失时，重试必须复用同一个 Thread、操作键、文件名和字节；同文件返回原 ID，改变内容返回409。取消/移除可能与服务端完成竞争，前端仍核实并丢弃迟到附件。已移除或过期键保留幂等墓碑；旧键同文件返回410，改变内容返回409。410 后由用户重新选择/上传生成新键。

只含附件的有效消息：

```json
{"threadId":"thr_example01","turnId":"turn_document01","operationKey":"submit_document01",
 "content":{"text":null,"skillIds":[],"attachmentIds":["att_example01"]}}
```

上传成功仅是草稿保存；提交准入时，附件引用与用户输入在 Store 同一事务提交。历史用户项与队列项的 `content` 返回 `text/skillIds/attachmentIds/attachments`，`attachments` 使用同一元数据结构。纯附件首条消息用文件名初始化标题。已引用资料永久保留供历史和恢复，不允许discard；未引用草稿默认24小时后清理，通过 `JA_EXAMPLE_ATTACHMENT_DRAFT_RETENTION_MS` 调整保留时间，`JA_EXAMPLE_ATTACHMENT_CLEANUP_INTERVAL_MS` 调整清理间隔，单位毫秒且必须为正。

## 企业策略、审批与未知结果

`EnterpriseDocumentMiddleware` 是独立可替换的 Core middleware，namespace 为 `spring-document`，先注册它，再注册审批策略。宿主不把同一实例额外注册成第二个 middleware Bean。

| 真实扩展点 | 行为与持久证据 |
| --- | --- |
| beforeRequest | 从实际原生媒体块读取附件ID并向宿主核验，要求区分事实、推断与不确定信息；不解析引用占位文字 |
| beforeTool | trim/规范化save_note标题正文，空白返回可纠正工具错误；规范化后再产生审批 |
| projectToolResult | 保留实际ToolOutcome，记录已保存回执、处理结局及策略事实，模型获得真实保存信息 |
| beforeCompletion | 本轮保存成功时要求终答含所有真实笔记标题；缺失则携带已提交回执继续，满足后保存交付事实 |

审批内容、持久ToolPrepared和实际写入都使用同一份最终参数。用户拒绝后允许结束；策略不强迫再次保存。所有角色都可解读资料，保存由用户要求与模型真实结构化工具调用触发。

`save_note` 生成 `note.write` 扩展事实，transaction participant 使用 Store 的同一 JDBC Connection 写笔记、资料关联和业务审计。笔记、原始工具结果、中间件状态与事实一起提交；participant失败或陈旧fencing全部回滚。策略回调不自行写业务表。投影异常时，Core保存权威原始结果并挂起，笔记仍能查询；恢复从持久回执补齐交付要求，不再次写入。

save_note 审批页直接展示最终笔记标题、完整正文与资料名称，已加载的资料可点击预览；原审批参数折叠在技术详情中。界面摘要只处理宿主生成的原生审批，未知参数或其它问题完整展示；确认仍提交 Core 原问题与选项标识，不重新构造工具参数。

记录页显式展开本轮后调用 `/experience/policy/facts`，以 `nextSequence` 分页直到 `hasMore=false`。页面以中文业务结局展示已核实事实，执行标识、原始类型、时间与参数放在技术详情中。每条fact包含 `turnId/namespace/type/schemaVersion/payload/occurredAt`；payload是普通JSON对象。`afterSequence`是记录水位，不是事件水位；没有策略事实的页也可能需要继续。笔记的 `sourceAttachmentIds` 可用metadata/content回看原始来源。
`/experience/notes` 只接收 `{ "threadId": "thr_example01" }`，返回数组项字段为
`callKey/turnId/title/body/sourceAttachmentIds/createdAt`，时间为 ISO-8601。服务端核对当前主体，响应不包含租户、用户或内部参数指纹。
该查询拒绝额外字段并返回400，包括 `turnId`、`userId` 和 `tenantId`；调用方只提交 Thread，不以请求正文指定身份。

未知结果时，先用原Thread/Turn查询状态与 `/recoveries`。若 `resumeRequired=true` 且尚无恢复条目，用户先明确调用普通resume，
让Core只读核验原Started；此动作不是重试未核验副作用。随后待裁决条目包含 `recoveryId/callId/revision/status/disposition/attempt/toolResultRecordId/updatedAt/allowedActions`。
只展示当前 `allowedActions`，不得预先猜测可以重试。核验已提交则复用回执，核验未应用才进入用户裁决。

```json
{"threadId":"thr_example01","turnId":"turn_document01",
 "recoveryId":"<recoveries返回的ID>","callId":"<同条目的callId>",
 "expectedRecoveryRevision":0,"operationKey":"resolve_document01","decision":"RETRY"}
```

将实际条目的revision替换示例0，POST `/recoveries/resolve`；decision为RETRY或SKIP，204仅表示裁决保存，随后POST `/resume`继续原Turn。响应丢失复用原操作键及原正文；陈旧修订号先查询，不换键绕过。save_note的reconcile只核验持久事实：已提交则复用回执，确认未应用后才执行用户明确选择的重试；SKIP保留跳过结果并让模型继续。

连续联调步骤（所有ID取前一步真实响应）：

1. 在全新测试库配置支持图片/PDF的Provider与模态，启动后确认 `/model` 的有效格式和实际模型名。
2. 创建Thread，分别上传图片/PDF，保存两次独立上传键和返回ID；断网重试须取得相同ID。
3. `/stream` 发送嵌套content，正文要求解读实际资料并保存笔记；观察真实模型调用和原生审批。此时notes中没有本次新笔记。
4. `/wait` 回读当前interaction/revision，检查规范化参数，选择实际批准option；`/answers`成功后显式`/resume`。
5. 观察COMPLETED与包含实际标题的终答，读取notes、来源metadata/content和policy facts，核对事实与原始资料。
6. 新Turn测试拒绝，确认无新增笔记；用只含空白的工具参数场景验证模型得到可纠正错误。
7. 故障测试用隔离数据库/受控Provider制造未知Started或投影异常；按recoveries裁决与resume恢复，核对原callKey笔记只保存一次。

## 能力体验接口

所有地址仍为 POST + JSON，前缀 `/api/chat`。`/experience/**` 默认启用，
测试可用 Spring 参数 `--agent.experience.enabled=false` 关闭整组扩展和管理接口。

| 地址 | 额外请求字段 | 响应 |
| --- | --- | --- |
| /pause | 公共身份、operationKey | 204；继续观察 SUSPENDED，再 resume |
| /compact | 公共身份、operationKey | 202 identity；返回 compaction_ 开头的独立 turnId |
| /queue | 公共身份 | turnId、revision、entries |
| /queue/mutate | 公共身份、operationKey、action、inputId 等 | Core 操作回执；之后重新 queue |
| /usage | 公共身份、recordId | 已提交 Usage 事实；引用 model.completed 的 usageRecordId |
| /experience/skills | {} | name、description、sourceId、version 数组 |
| /experience/skills/read | name | name、description、reference、version、body |
| /experience/skills/write | name、description、body、expectedVersion | 新 SkillDescriptor；创建版本0，更新使用读到的版本 |
| /experience/skills/delete | name、expectedVersion | deleted=true；版本冲突409 |
| /experience/tools | {} | name、description、inputSchema 数组 |
| /experience/mcp | {} | id、transport、status、tools 数组 |
| /experience/notes | threadId | 笔记回执数组，含标题、正文、Turn 与 sourceAttachmentIds，最多最近100条 |
| /experience/policy/facts | threadId、turnId、afterSequence、limit | facts、nextSequence、hasMore；limit 为1..256，afterSequence 为非负记录水位 |

Skill name 必须为 ASCII 小写 kebab-case，长度为 1..256 个字符；正文最多32768字符。管理编辑通过同一 PG 事务按
expectedVersion 对正文执行 CAS 更新，只修改 SKILL.md 并保留原包资源；删除会删除该包和资源。目录最多读取256项，正文延迟读取。version 是目录分配的版本，
不能假定某个 Skill 总是从1开始。404需刷新目录；409需重新读取版本后由用户决定是否继续编辑。

选择 Skill 的完整消息示例：

```json
{"threadId":"thr_demo","turnId":"turn_skill","operationKey":"submit_skill",
 "content":{"text":"用三条要点介绍 Java 智能体。","skillIds":["demo-pg:brief-reply"],"attachmentIds":[]}}
```

skillIds 可省略默认空数组，最多32项；使用 `sourceId:name` 引用，不把正文拼到用户文本。
接口使用 Core SkillReferenceContent，Core 按当前版本建立上下文。

队列提交示例（执行中或暂停时使用）：

```json
{"threadId":"thr_demo","turnId":"turn_skill","operationKey":"enqueue_01","action":"ENQUEUE",
 "inputId":"input_01","kind":"STEERING",
 "content":{"text":"重点讲工具审批。","skillIds":["demo-pg:brief-reply"],"attachmentIds":[]}}
```

action 为 ENQUEUE / UPDATE / DELETE / PRIORITIZE。
ENQUEUE 需要 kind（STEERING即时指导、FOLLOW_UP当前回复结束后处理、MESSAGE保留技能的宿主消息），
缺失、空白或未知分类返回参数错误 400，队列不变；content 必填，MESSAGE 的 skillIds 必须为空。
UPDATE 需要 content 和已观察到的 expectedInputRevision；DELETE / PRIORITIZE 不需要 content，但必须带该条目版本。
操作键固定用于重试，换键不能绕过版本冲突。队列最大8项；已消费条目不可再编辑。
entries 包含 inputId、kind、status、inputRevision、content；content 中保留 text、skillIds、attachmentIds 和 attachments 元数据。revision 是队列版本，
expectedInputRevision 是条目版本，两者不要混用。暂未消费不等于已进入模型历史。

工具行为：

| 工具 | 参数 | 原生流程 |
| --- | --- | --- |
| read_skill | name | PG 读取真实正文 |
| mcp_tools | {} | HTTP initialize → tools/list；按需探测 |
| mcp_call | server、tool、argumentsJson | 原生审批 → HTTP tools/call → 真实工具结果 |
| save_note | title、body；来源由策略核验 | 参数规范化 → 原生审批 → note.write → Store 同事务保存 |
| list_notes | {} | 查询当前 Thread 已保存笔记 |
| request_user_input | prompt、options | 原生 WAITING_INPUT → 回答 → 恢复 → 结果进入下一模型请求 |
| wait_seconds | seconds 1..30 | 可取消的实际工具等待 |

mcp_call 的 argumentsJson 是 JSON 对象字符串，例如 `"{\\"a\\":12.5,\\"b\\":7.25}"`，
用标准 JSON 解析器转换，避免动态远程参数与 OpenAI strict Schema 冲突。
所有 MCP 调用保守要求审批；拒绝后不会发 tools/call。
save_note 的 title 必填、最长200字符，body 必填、最长16000字符；只有审批通过才写入。
request_user_input 的 options 为必填数组、最多6项；空数组使用文本问题，否则使用单选问题。
远程连接失败显示 UNAVAILABLE；真实工具失败交回模型处理，外部结果无法核实则保留 Core 的挂起语义，
不会凭空宣称完成或自动重复副作用。

最小体验顺序：

1. 启动 FastMCP 和 Spring Boot，页面打开 Agent 能力 → MCP，目录显示 AVAILABLE 和 add / now。
2. Skills 勾选 brief-reply，关闭面板发送消息，观察模型调用与完成。
3. MCP 中准备 add 参数并发送；出现原生审批，批准后展开 mcp_call 结果并看到最终答复。
4. 工具页体验 save_note，审批前记录页无笔记；批准后记录页出现笔记，拒绝则无写入。
5. 体验 request_user_input，选择答案并确认；页面先 answers 再 resume，同一 Turn 继续。
6. 体验 wait_seconds，执行中追加指导；队列页刷新查看版本，暂停后继续或取消并观察真实终态。
7. 完成对话后点压缩，页面观察独立 compaction Turn；使用新 turnId 查 status/events，不将受理当作压缩完成。

页面记录页默认展示最近20条 Turn，可继续分页加载更早记录；展开 Turn 后才读取供应商 Usage，每批最多8条，未知计量不补零。笔记按 Thread 隔离。Skills/MCP/记录等仅打开页签时读取。
Ja App Server 的 Plan、侧聊、Workspace 和文件/Shell 工具是宿主能力，
此示例未引入 App Server，也不把这些入口伪装成 Core 已提供的完整产品功能。

## 验证

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am `
  test
```

`AgentExampleStreamingTest` 使用 Embedded PostgreSQL 和 MockWebServer 验证 MVC/SSE 与持久状态，不调用真实 Provider。线程用例覆盖幂等创建、角色目录和固定绑定、模型实际收到的两种 System Prompt、同 Thread 追问、列表搜索、标题 CAS、活动态归档拒绝、归档恢复、缺失 Thread 404 和固定记录水位分页；其余用例覆盖审批、澄清、输入队列、取消和事件回放。
`FastMcpHttpTest` 使用 Java SDK 验证 Python FastMCP 的 HTTP 握手、Schema、`add` 与 `now`；需要 `uv` 和已同步的 Python 依赖。

```powershell
pnpm typecheck:example:spring
pnpm test:example:spring
pnpm build:example:spring
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am package
```

React tests 位于 `apps/desktop/tests/api/spring-boot/` 与 `apps/desktop/tests/app/spring-boot/`；`package` 才运行 Maven 的 `prepare-package` 前端构建并生成 JAR 静态页面。2026-09-27 的浏览器验证记录针对旧静态聊天页，不构成 React 工作台的浏览器验收证据。
`workbenchPresentation.test.tsx` 覆盖搜索/草稿、侧栏开关、主题弹层、重命名冲突重试、Skill 移除、IME 输入、单一停止入口与滚动跟随；`browser-workbench.mjs` 随 Spring 集成测试在隔离 Chromium 中运行，核对真实 MVC/SSE 与测试 PG/Provider 流程，不占用宿主桌面。
`workbenchEnterpriseReliability.test.tsx` 覆盖上传/取消/刷新、纯附件草稿、未知恢复裁决，以及恢复受理后旧暂停快照与等待状态先于表单记录到达的时序；明确等待、恢复错误和人工暂停仍由用户处理。
`attachmentPreview.test.tsx` 验证关闭预览、取消上传、重试和移除附件后返回当前 Thread 的输入焦点、只读来源控件的回焦、模态阻止后台夺焦，以及 Blob URL 释放。慢下载保持单一请求，关闭后取消，迟到成功不能触发下载；下载失败保留预览并可原地重试。`browser-enterprise-document.mjs` 使用真实 React 组件与全局样式覆盖选择器、拖放、粘贴、PNG/PDF 预览下载、键盘焦点、审批保存与双资料来源回看，并核对同次 Provider 请求中的原始附件字节。
`approvalPresentation.test.tsx` 验证审批业务摘要、完整原始参数的折叠回看与原问题/选项身份，普通问题和未识别授权参数不会被摘要隐藏。浏览器同时模拟真实上传已提交但响应丢失，核对页面用原 Thread、操作键和文件字节确认后取得同一个附件 ID。
Browser插件不在本次环境中，浏览器验证使用已有Playwright与隔离Chromium，不新增浏览器依赖。企业资料脚本采用完整 Chromium 的新无头模式（`channel: "chromium"`）验收原生 PDF 预览，需要已安装与 Playwright 版本匹配的完整浏览器；headless shell 的接口/字节检查不能替代 PDF 呈现验证。执行入口：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am `
  '-Dtest=AgentExampleStreamingTest,EnterpriseDocumentIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dja.example.browser=true' test
```

打包后对真实exec JAR做独立库/受控Provider冒烟；测试仅启动自己的进程并在结束时关闭：

```powershell
$jar = (Resolve-Path 'ja-agent/example/ja-agent-example-springboot/target/ja-agent-example-springboot-0.2.0-exec.jar').Path
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am `
  '-Dtest=PackagedJarSmokeTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dja.example.jar=$jar" test
```

`RealProviderIntegrationTest`默认跳过，只有显式 `-Dja.example.realProvider=true` 才会进行真实调用。接入方先在本地环境设置`JA_EXAMPLE_PROVIDER_ID/MODEL_ID/MODEL_API/MODEL/MODEL_BASE_URI/MODEL_API_KEY/CONTEXT_WINDOW_TOKENS/MAX_OUTPUT_TOKENS`，可选`JA_EXAMPLE_REASONING_LEVEL`，然后运行：

```powershell
mvn.cmd -B -ntp -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am `
  '-Dtest=RealProviderIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dja.example.realProvider=true' test
```

真实测试使用全新Embedded PostgreSQL、带独立事实的图片/PDF、实际审批与笔记保存；不会访问接入方已有业务数据库。桌面配置只有在用户明确授权联调后才由本地验证进程读取，示例生产应用自身仍不读取Ja配置。真实调用可能受Provider连接、额度和模态支持影响；受控测试、构建或旧运行记录均不能证明真实Provider已通过。

跨Java/Rust/TypeScript的完整合同验证始终从仓库根、显式JDK25运行`pwsh -NoProfile -File tests/contract/run.ps1`。它包含App Server与其它宿主；与示例Maven/前端门禁分别报告，任何编译阻塞都不能写成合同Gate通过。
该演示及这些测试不代表生产鉴权、外部网络部署、长期压力或 Ja 桌面验收。
