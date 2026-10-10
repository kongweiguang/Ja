<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Ja Agent Core Java 接入

当前为源码接入说明。Ja 0.2 的验收结论以对应源码及产物的完整报告为准。
桌面与 CLI 的验收分别记录。这些坐标尚未发布到外部 Maven 仓库。

## 模块与依赖

Java 25 Maven Reactor 位于 `ja-agent/`。可复用内核坐标为
`io.github.kongweiguang:ja-agent-core`；SQLite、PostgreSQL、Local Skill 与 PostgreSQL Skill
分别由 `ja-agent-store-local`、`ja-agent-store-pg`、`ja-agent-skills-local`、`ja-agent-skills-pg`
提供。调用方按实际部署选择 Core 与扩展依赖，Core 不读取 Ja 配置文件，也不创建本地存储兜底。

版本由 `ja-agent/pom.xml` 的 Maven 项目版本统一管理。各坐标已经声明在 Reactor 中，
尚无外部 Maven 仓库发布证据；消费方需从团队 Maven 仓库获取已部署版本，或在本地构建安装。仓库源码里的
`example/ja-agent-example-springboot` 提供独立可运行示例，使用普通 `@Bean` 装配，没有 Ja Spring starter。
Core、扩展、应用与示例测试放在所属模块的 `src/test`。

首次从源码消费时，先用 JDK 25 安装需要的模块到本地 Maven 仓库（以下命令在仓库根目录执行）：

```shell
mvn -f ja-agent/pom.xml -pl ja-agent-core,extensions/ja-agent-store-pg,extensions/ja-agent-skills-pg -am install
```

SQLite 与本地 Skill 场景改为选择 `extensions/ja-agent-store-local,extensions/ja-agent-skills-local`。
构建 Ja 应用使用 `mvn -f ja-agent/pom.xml -pl ja-app-server -am package`。

例如服务器选择 PG 存储和 PG Skill 时，在消费工程声明以下依赖；`ja.version` 取已安装或已发布的同一版本，
消费工程不需要继承 Ja parent，也不需要引入 app-server：

```xml
<properties>
    <maven.compiler.release>25</maven.compiler.release>
    <ja.version>0.2.0</ja.version>
</properties>
<dependencies>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-core</artifactId>
        <version>${ja.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-store-pg</artifactId>
        <version>${ja.version}</version>
    </dependency>
    <dependency>
        <groupId>io.github.kongweiguang</groupId>
        <artifactId>ja-agent-skills-pg</artifactId>
        <version>${ja.version}</version>
    </dependency>
</dependencies>
```

## 谁提供什么

宿主负责创建 `AgentIdentity`、模型配置、上下文预算、`DataSource` 和业务工具；按部署选择并显式装配
`AgentStore` 与 `SkillSource`。模型配置包括选定 Provider API、端点、凭据和上下文能力，Core 不按模型名猜测预算。
工具通过 `AgentExtension` / `ExtensionRegistry` 显式注册；空工具或 Skill 列表是合法配置。
Engine 不接管宿主注入的 `DataSource`、连接池或业务 Service。
宿主可在标准运行时调用 `new AgentEngineBuilder().systemPrompt(request -> ...)` 提供完整 System Prompt。
回调按当前请求身份选择正文；同一 Thread 的角色归属应由宿主持久化并在追问、恢复时保持一致。
未设置时 Core 不注入任何 Persona 或行为规则，系统提示词为空；设置后该正文作为基础 Prompt，Core 仅负责追加
Skill、摘要及 Prompt revision 校验；提示词内容本身不授予工具、审批或数据访问权限。
Spring 示例的 [Agent 角色配置与接口](example/ja-agent-example-springboot/README.md#配置与启动) 展示了 PG 定义与 Thread 绑定。

用户图片/PDF 由宿主 `ManagedAttachmentReader` 按受控切片读取，实际能力取模型输入模态与 Provider Codec 的交集。
读取器默认允许生成 `read_attachment` 引用提示；宿主没有注册该读取工具时，应覆盖
`allowsReferenceFallback()` 返回 `false`，使不支持的模态或请求总量超限在发送前明确失败。
同一附件在有效上下文中出现多次时，每次都计入实际请求字节预算，不能只按唯一文件去重计量。
Spring 企业示例采用严格策略，并在输入准入前校验有效历史与待提交资料。

`AgentIdentity` 执行身份为 `tenantId + threadId + turnId + userId`。Store 分别以
`ConversationKey(tenantId, threadId, userId)` 标识会话，以增加 `turnId` 的 `ExecutionKey` 标识执行；
`OperationKey(tenantId, userId, operationKey)` 隔离幂等回执，不含 Thread 或 Turn。相同作用域复用键时，
请求指纹不匹配必须冲突。Ja App Server 当前是单本地主体：Thread 创建时持久化不可变 `owner_user_id=user_local`，
客户端不提交用户身份；其它 Core 宿主须显式提供自己的执行身份。RPC JSON-RPC `id` 只配对一次传输请求，
实现内部称 `rpcRequestId`，不能替代上述 Store key 或模型调用身份。

内置模型适配器同时提供普通流式请求和上下文摘要。自定义供应商通过
`registerModelProvider(id, modelPort, summaryFactory)` 显式绑定两种能力；只注册 `ModelPort`
时，普通请求可用，但需要摘要时会明确报不支持，不会改走内置 HTTP 或另一个模型。

自定义 `ModelPort` 在原生 reasoning 条目已结束、可回放载荷尚未齐全时，可发送
`ReasoningBlockDeferred(slotId, null)` 保留历史位置并继续发送 `TextDelta`；终态拿到有效载荷后，
以相同 `slotId` 和非空 `ReasoningContent` 填回一次。每个槽在同一模型响应内只能声明、填充各一次；
未填充槽不会进入模型历史，迟到载荷不会改变正文与 reasoning 的原始顺序。

中间件按显式注册顺序执行，使用请求准备、工具执行前、结果投影和完成前四个处理点。
结果投影的 `ToolResultContext.executionStarted()` 区分已持久化派发意图与准入拒绝；审批拒绝或过期为 `false`，
不能仅凭失败结果推断工具已执行。`true` 也不等于外部操作一定成功，真实结果和恢复证据仍是判断依据。
`beforeCompletion` 返回的 `facts` 是终态候选事实：只在全部决定最终接受完成时，与 Terminal 同事务保存；
如果本轮决定继续或等待，只保存状态补丁，不提前提交这些候选事实。回调可能在恢复后再次执行，不能在其中直接产生业务副作用。
`CompletionContext.hasUnverifiedToolEffects()` 表示持久恢复记录中仍有未经核验的工具副作用；
例如用户明确跳过结果未知的工具恢复，宿主应保留“不完整”状态，不能据此宣称没有发生修改。

工具可以在真实执行的异步阶段完成后，用 `ExecutionResult.withExecutionDurationMillis(...)` 附带单调时钟实测耗时。
`resultFacts` 接收完整的不可变 `ExecutionResult`，可将实测值与结果一起持久化；Core 不用事件提交时间推算耗时。
未测量或仅通过恢复接口核验旧结果时，`executionDurationMillis` 保持为空，不能把停机或等待核验时间计作执行耗时。

工具图片通过 `AgentTool.ToolResult.media` 与 `ToolMediaReference` 返回。每项保存
`attachmentId/mediaType/displayName/sizeBytes`，最多十项，不允许重复 ID；单图最多 50 MiB。
引用必须由宿主受管媒体存储导入和授权，不能把文件路径、远程 URL 或 Base64 文本作为附件身份。
Core 将这些引用随 Tool 结果、有效上下文和历史保存；真正发送时由宿主读取器校验引用并提供媒体，
Codec 同时检查模型输入模态和 Tool 图片能力。只支持用户图片输入的实现不发送 Tool 图片并明确说明限制，
不能把结果文本化后声称模型已经看图。宿主自行实现存储时也必须保证删除来源会话不会破坏仍被独立
会话引用的内容，读取失败和未知副作用分别保留真实失败事实。

需要结构化终答时，在用户请求中显式传入可选 JSON 对象 Schema。模型配置的
`supportsOutputSchema` 与 `ModelPort.supportsOutputSchema(configuration)` 必须同时支持；
未声明能力的配置默认关闭。Schema 在 HTTP 前编译校验，最终助手文本必须解析为 JSON 并通过
同一 Schema 校验才提交成功；约束不满足以 `OUTPUT_SCHEMA_VALIDATION_FAILED` 结束，
不能只因 Provider 返回成功 HTTP 或声称使用 strict 格式就判成功。请求与 Schema 一同冻结用于
重启恢复。内部标题与摘要请求不继承用户终答约束。

Engine 负责持久执行状态、单 Thread 调度、模型与工具循环、等待/恢复和有序事件查询。执行流程由
`new AgentEngineBuilder()` 装配；`submit(AgentRequest)` 返回 `AgentExecutionHandle`，调用方继续观察
`completion()`。结果是 `AgentExecutionOutcome`：

- `Completed` 表示本次运行已提交终态，终态详情在 `TurnResult` 中。
- `Suspended` 表示等待或挂起事实已持久化，当前执行 owner 已释放；宿主完成必要交互后再显式调用 `resume(identity)`。
- `Compacted` 是手动上下文压缩的专用回执，不代表普通 Turn 的完成结果。

用户等待的答案通过 `answer(identity, AgentAnswer)` 持久结算，之后显式 `resume(identity)`；不要把等待误报成
成功终态。`query(identity)` 查询当前权威状态，`readEvents(identity, afterSequence, limit)` 按持久游标补读事件，
`watchEvents(identity, cursor)` 仅用于等待新水位通知，通知本身不代替事件分页读取。客户端断开不应被当成取消。

宿主显式暂停使用 `suspend(identity, operationKey, reasonCode)`，等待返回阶段完成后再读取权威状态；
写入暂停请求不等于执行已安全停下，Engine 还须等待当前 owner 结算。控制键和原因在重试中保持不变。
暂停与取消的内部 Store CAS 若被同一执行的合法并发提交推进，先核对原回执，再按同一执行身份重读当前版本；
只有版本确实前进且执行尚未终态才重试，已提交回执不重复写入。身份或指纹不一致、版本未推进、
终态冲突及未知提交结果仍明确失败，不靠换键或猜测状态确认成功。

首次准入前可用零游标注册观察，即使 conversation 尚未提交创建；真实提交会唤醒观察，宿主不需要提前建行或轮询。
不存在的 conversation 不接受正游标，已有 conversation 的游标不得超过持久水位。宿主应关闭不再使用的观察句柄，
释放有限观察容量；水位读取失败保留原异常并释放本次注册，不能将其当作空事件或准入成功。

Store 的负载、执行 cursor 和 receipt 都使用对象形 `JsonObject`；单项 UTF-8 编码不超过 16 MiB。
事件和记录分页中 `after` 为排他起点，`through` 为包含且固定的水位，默认响应预算为 UTF-8 4 MiB；
沿 `limit/nextSequence/hasMore` 继续读取。调用方应保存最后成功处理的 sequence，并在重连后从该位置补读。

关闭时 Engine 释放自己创建的运行资源；宿主仍负责关闭自己创建并注入的数据库池和其他业务资源。

Core Store SPI 以 `io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionRecordCodec`
和 `io.github.kongweiguang.ja.agent.application.gateway.store.ExecutionControlCodec` 声明记录编码契约；
控制事实的记录类型与 Schema 版本由后者统一声明。Core 编排、JSON 编码实现及宿主同事务投影共用这些契约。
需要原子更新业务表时，
Store 的事务参与接口提供同一个 JDBC Connection，业务事实与执行事实一起提交或回滚。
审批计时器只负责有界登记、同一等待版本的存储故障重试和线程关闭，等待结算仍经过执行服务的持久 CAS；
正延迟向上取整到毫秒，避免在截止前触发后失去后续结算，极端时间按过去或未来饱和处理。
Engine 正常关闭返回前会取消未触发计时并等待已进入的结算退出；清理失败会明确向宿主报告。

下面是宿主已经提供 `DataSource dataSource`、`ModelPort.ModelConfiguration model`、
`ContextBudget budget`、`AgentTool businessTool` 后的最小装配片段。
`budget` 必须依据所选模型真实上下文能力设置；`businessTool`
由宿主注入 Service，声明参数 Schema、副作用和审批要求，执行时使用 Core 给出的稳定 `callKey`。

```java
import io.github.kongweiguang.ja.agent.application.api.AgentEngine;
import io.github.kongweiguang.ja.agent.domain.model.AgentIdentity;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionHandle;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentExecutionOutcome;
import io.github.kongweiguang.ja.agent.application.api.engine.AgentRequest;
import io.github.kongweiguang.ja.agent.bootstrap.AgentEngineBuilder;
import io.github.kongweiguang.ja.agent.domain.ContextBudget;
import io.github.kongweiguang.ja.agent.application.gateway.AgentTool;
import io.github.kongweiguang.ja.agent.application.gateway.ModelPort;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSource;
import io.github.kongweiguang.ja.agent.skills.pg.infrastructure.PostgresSkillSchema;
import io.github.kongweiguang.ja.agent.store.pg.infrastructure.PostgresAgentStore;
import javax.sql.DataSource;

// 下列语句放在宿主方法中；四个输入由业务配置和依赖注入提供。
PostgresAgentStore.bootstrap(dataSource);
PostgresSkillSchema.install(dataSource);
PostgresSkillSource skills = new PostgresSkillSource(dataSource, "business-skills", 100);
try (PostgresAgentStore store = new PostgresAgentStore(dataSource);
     AgentEngine engine = new AgentEngineBuilder()
        .store(store)
        .model(model)
        .contextBudget(budget)
        .extensions(registry -> {
            registry.registerSkillSource(skills);
            registry.registerTool(businessTool);
        })
        .build()) {
    AgentIdentity identity = new AgentIdentity("tenant_1", "thread_1", "turn_1", "user_1");
    AgentExecutionHandle handle = engine.submit(
            AgentRequest.text(identity, "查询订单处理进度", "op_turn_1"));
    AgentExecutionOutcome outcome = handle.completion().toCompletableFuture().join();
    // Completed 仍需检查 result().state()；Suspended 需展示真实等待或阻塞，不能当作成功。
}
```

该片段是一次调用的资源生命周期示意；长期服务应复用一个 Engine，由应用停止时统一关闭。
同一次准入重试复用相同身份、幂等键和内容；新任务使用新的 turnId 和幂等键。
独立宿主先用 `PostgresAgentStore.bootstrap(dataSource)` 或 `SQLiteAgentStore.bootstrap(dataSource)` 显式初始化/核验 Core schema，
再构造 Store；构造函数只验证 schema，不隐式写库。PG Store 借用宿主 DataSource，不创建或关闭连接池。
PG Store 的固定表校验允许独立 `agent_skill_*` 扩展共用数据库；仍拒绝其它未知 `agent_*` 或旧 Core 表，
并严格核验 Core 自有表、列和约束。业务宿主应使用自己的表命名空间，不能把扩展表冒充 Core 表。
PG Skill 也须先显式调用 `PostgresSkillSchema.install(dataSource)`：空的 Skill 命名空间初始化当前模块版本，
已有命名空间只有版本、完整列、约束和索引全部匹配才通过；缺表、结构漂移和旧版本直接拒绝，
不以 `CREATE TABLE IF NOT EXISTS` 补表或在线迁移。`PostgresSkillSource` 构造只核验结构，
借用宿主 DataSource，不关闭连接池；Skill CAS 与同事务资源替换/回滚保持原有语义。
`SQLiteAgentStore.open(path)` 是本地便利工厂，会显式创建该路径的 Core schema 后构造 Store；宿主负责关闭返回的 Store。
Ja App Server 由唯一 owner `JaDatabase` 通过 Flyway 初始化和恢复完整应用数据库，组合根仅将其 DataSource 交给 `new SQLiteAgentStore(dataSource)` 做 schema 校验，不能再调用 Store bootstrap 或 `open(path)`。

存储异常按原因处理，不能统一当作可重试冲突：

- `StoreConflictException`：版本过期或幂等身份不一致，先读取当前状态并核对原请求。
- `StoreIntegrityException`：持久内容损坏或约束被破坏，停止推进并排查，不能猜测修复游标。
- `StoreUnavailableException`：数据库访问失败；提交结果不确定时，先按原提交身份查询回执，再决定是否重试。
- `StoreParticipantException`：宿主同事务参与者失败，整笔事务回滚；先修复业务原因，不能跳过参与者继续提交。

工具恢复由业务方明确实现：Core 先保存调用意图，再执行工具，再提交真实结果。外部操作应以
`ExecutionContext.callKey()` 作为幂等身份保存业务回执，并通过工具恢复接口查询该回执。
进程在操作后、结果提交前退出时，恢复端核实回执后结算，不能盲目重新执行；无法核实时执行挂起，
宿主展示阻塞并处理，不自动切换模型或存储。取消只停止后续推进并保存已有事实，不撤销已完成的业务操作。

审批等待的身份来自 `InteractionWait.interactionId()` 和 `toolCallId()`；扩展属性保存 `kind`、`expiresAt` 等策略内容，
不再生成 `approvalId`、`callId` 的属性副本。回答和恢复必须引用原等待及冻结调用，不能另造授权身份；
同事务参与者应读取类型字段。拒绝或过期只结算原调用，不执行工具，也不伪造用户批准。

事件重连先保存最后成功处理的持久 sequence，再用 `readEvents` 分页补读；`watchEvents` 的通知只表示
可能有新水位，不能当作业务事实。临时 token 流用于显示正在生成的文字，持久消息和终态才是恢复依据。
Core 的事件 ID 是不透明身份，调用方不能假定它具有 Ja RPC 的 `evt_` 前缀；若外部协议需要自己的格式，
应做稳定且不混淆不同源身份的映射，仍以持久 sequence 作为补读游标。
慢消费者应重读持久游标，不让 UI 或监听器反向阻塞工具事务；客户端断开后要主动重连，不能用断线推断失败。

`subscribeStream(identity, capacity)` 返回单消费者的有界临时订阅，宿主应在执行准入前建立，并在退出时关闭。
容量为 1–256 个队列帧。连续同一 `modelRequestId` 的同类型正文或公开摘要可合并为最多 8192 个 UTF-16
字符的帧，已有较大单片仍独立排队；正文、摘要、Reset 与不同请求不会互相合并。合并帧的 `context.sequence`
取最后一个源片段，严格递增但不保证逐一连续，不能以 SSE 分片数与订阅帧数不同判断正文丢失。
`poll(timeout)` 的空事件表示当前没有可读帧，不能当作执行完成；`RESYNC_REQUIRED` 表示真实容量溢出或乱序，
此后不能继续拼接该订阅的草稿。宿主须显式提醒界面重新读取持久状态，关闭订阅后按需重新建立；
未提交正文只会在后续消息提交后进入权威历史。发布不等待宿主线程，慢订阅不会拖住模型或工具。

## 追加输入与宿主消息

首轮需要携带已经接收的消息时，使用 `AgentRequest.withInitialQueuedInputs(...)`；用户正文与这些
`AgentInputCommand.Enqueue` 在同一次准入事务持久化。每项提供独立的 `inputId` 和稳定的 `operationKey`，
超时重试必须复用原操作键与原内容，不得重新生成身份。

宿主主动发起的内部续跑使用 `AgentInternalRequest.withInitialQueuedInputs(...)`，仅允许 `MESSAGE`；
手动压缩不接纳首批消息。消息投递本身不会启动内部续跑，启动权仍属于宿主。
如果宿主邮箱会在准入后改变状态，跨调用重试应先用 `readAdmittedInitialInputs(request)` 读取原始批次：
`Optional.empty()` 表示尚未准入，才可以读取当前待投递消息；`Optional.of(emptyList())` 表示原请求已准入且首批为空。
原批次与请求绑定同事务保存，包含稳定身份、内容和宿主元数据；重试不得把后来到达的消息混入旧请求。
该只读接口不重新授权，也不替代 `submit` 的幂等内容校验。

- `STEERING` 在下一模型请求前消费，用于用户追加指导。
- `FOLLOW_UP` 在模型停止边界按队首顺序消费；该边界也不能越过队首的 `STEERING` 或 `MESSAGE`。
- `MESSAGE` 在下一模型请求前进入 USER 历史，保留当前已选择的 Skill；消息正文不能携带 Skill 选择块。

队列最多保留 8 项；容量满会明确返回 `AgentInputQueue.QueueFullException`，宿主应保留未受理消息并显示
容量状态，不能丢弃或静默覆盖。更新、删除与优先级操作需要当前条目版本；MESSAGE 不允许提升优先级。
已经结束的执行拒绝新的入队，宿主需将尚未投递的消息保留到下一次明确发起的请求；单纯投递不代表启动任务。
`hostMetadata` 是宿主自定义的持久元数据，最多 32 项，单值最多 16,384 字符，键和值合计最多 65,536 字符。
Core 不据此授权；需与业务消息表一起更新时，使用 Store 的同事务参与接口。命令准入与持久队列采用相同边界，
超出限制会在准入前明确拒绝。

0.2 的队列只通过当前 `agent.input.queue.command` 记录恢复顺序、版本和待消费项；
不再提供旧队列 snapshot 的编码、解码或基线导入 API。自定义 `ExecutionControlCodec` 实现应删除相应旧方法，
遇到 `agent.input.queue.snapshot` 时明确拒绝恢复，不迁移原记录，也不将其解释为空队列。
这不取消 `AgentStore` 的自定义扩展记录能力；宿主自有记录仍按自己的契约保存与处理。

## 冻结父上下文

新建子会话或其他空 conversation 时，可在首个用户请求上调用 `AgentRequest.withInitialContextSeed(seed)`。
`InitialContextSeed` 是显式冻结的背景事实，不会伪装成本次执行的用户消息、工具结果或 Checkpoint；Seed 与首条
`agent.user.message`、请求绑定、游标和准入事件在同一 Store admission 中写入，宿主不能先接纳请求、之后再补背景。
只有尚无执行和记录的 conversation 可接收 Seed；标题、偏好等元数据仅提高 conversation revision 不会单独封住首次导入，
准入仍必须对该 revision 做 CAS。成功准入后该 conversation 不能再写第二份 Seed；重试应复用原执行身份和幂等请求。

Seed 的 `sourceIdentity` 标识冻结来源，必须与 child 属于同一 tenant；`sourceOrdinal` 是父历史的消息水位，
不是摘要覆盖边界。`messages` 应是同一固定父快照经有效 checkpoint 与完整工具配对裁剪后的有效尾部，按父 ordinal
递增且保留原 ordinal，允许中间有已被 checkpoint 覆盖的序号间隔；不能留下未配对的 Tool call。可选 `summary` 保留父摘要
事实各自的来源 ordinal，不因 child 消息 ordinal 延续而改写。Child 后续消息从 `sourceOrdinal + 1` 起编号。

父 summary 仅在 child 尚无自有 Checkpoint 时作为背景摘要提供。Child 创建后的首个 Checkpoint 由 child 自己提交；
Seed 中的父消息继续保留为固定背景，之后的压缩按 child 当前历史水位处理，不重复注入父 summary，也不把父摘要来源 ordinal
伪造成 child 的压缩水位。该边界让父上下文可重启恢复，同时避免不同 ordinal 坐标造成摘要重复或历史缺口。

Skill 来源以宿主注册的 `SkillSource.id()` 标识，不局限于本地目录或 PG；例如 `business.docs:guide`
表示来源 `business.docs` 中的 `guide`。来源 ID 不能包含冒号或路径分隔符，Skill 名称使用小写字母、数字与
连字符。`UserContent` 的顺序为上下文引用（包含 Skill）、附件、正文；单独选择 Skill 而没有问题或资源不构成可提交输入。

## 仓库示例与运行

`example` 聚合 Spring Boot 接入示例：

- [SpringBootAgentExample](example/ja-agent-example-springboot/src/main/java/io/github/kongweiguang/ja/agent/example/springboot/SpringBootAgentExample.java) 提供独立 MVC 企业资料工作台，按 agent、identity、attachment、conversation、experience 业务边界组织，通过 bootstrap 装配。示例贯通原生图片/PDF、四阶段 Middleware、审批、同事务笔记及恢复；Spring 基础配置使用 application.yml，Agent 配置使用 PostgreSQL 表，HTTP MCP 由独立 Python FastMCP 服务提供。接入见 [Spring 对话说明](example/ja-agent-example-springboot/README.md)。

示例自身的测试位于 `example/ja-agent-example-springboot/src/test`。`AgentExampleStreamingTest` 使用
Embedded PostgreSQL 和 MockWebServer 验证真实 MVC HTTP/SSE、审批、澄清、输入队列与取消；
`FastMcpHttpTest` 启动 uv 管理的 Python 服务验证实际 HTTP MCP 接入，不访问真实模型账户。

在仓库根目录、JDK 25 下运行完整示例与其 Reactor 依赖：

```powershell
mvn.cmd -f ja-agent/pom.xml -pl :ja-agent-example-springboot -am `
  test
```

这个示例使用 PostgreSQL，不证明 SQLite 示例接线，也不代表长时间压力或外部 Provider 服务验收。
FastMCP 测试需要 uv 与已同步的 Python 依赖。

## 当前验证边界

Java 全量门禁使用本仓库显式 JDK 25；单项回归、生产代码编译或历史产物结果均不能替代完整验收。
桌面、CLI、合同、JVM 与 Native Image 的结论分别记录，并绑定本轮确切源码与产物。
下方是本仓库的验证命令；结束状态以本轮完整报告中的实际结果为准：

在仓库根目录显式使用 JDK 25 运行：

```powershell
$env:JAVA_HOME = 'C:\Users\24052\.jdks\liberica-25.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -version
mvn.cmd -version
mvn.cmd -B -ntp -f ja-agent/pom.xml verify '-Dja.example.browser=true'
pwsh -NoProfile -File tests/contract/run.ps1
```

完整门禁覆盖 Checkstyle、PMD、CPD、SpotBugs、依赖与架构，以及真实 SQLite 和隔离 PostgreSQL 测试。
Spring 浏览器流程由 `AgentExampleStreamingTest` 的 `-Dja.example.browser=true` 开启，需要 Node 24、pnpm 10.33.0、
仓库依赖与已安装 Chromium。真实外部 Provider、长时间压力与生产部署另行验收；不由上述测试覆盖。
条件跳过、宿主依赖和未覆盖范围应以绑定对应源码及产物的完整报告为准，不能沿用历史通过记录。
