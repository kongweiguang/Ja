<!-- @author kongweiguang -->

# JA RPC 2.0 transport and ownership

Rust Host passes the required `--ja-runtime-generation=<positive safe integer>` process argument to Java. Notifications use this exact generation together with `serverInstanceId`; generation is not a cross-process monotonic identity. A new server instance may have the same generation, and clients must invalidate the old binding when either identity component changes. Launch configuration cannot override the owner-injected value.

用户级 `interaction.clarification_enabled` 默认开启，只控制非 Plan 模式的澄清提问；项目配置不能覆盖。Plan 规划阶段始终允许澄清，并由后端目录过滤与执行前策略限制为可信只读或内部计划状态操作。模型原生结构化 Tool 调用是产生问答和计划动作的唯一入口，普通文本标记不参与执行。

用户配置文档必填 `subagents: {enabled: boolean, provider_id: string|null, model_id: string|null, reasoning_level: string|null}`。
默认 true/null/null；两个引用均为 null 表示跟随父任务，否则必须成对引用现有模型。
`reasoning_level` 为 null 时沿用父任务（跟随模式）或所选模型默认；指定档位只接受该模型支持的
`off|minimal|low|medium|high|xhigh|max`。跟随模式禁止独立覆盖思考等级。
关闭仍保留模型与思考选择，项目层禁止覆盖；配置读写继续使用现有 configuration 方法与 CAS。

`ja-rpc/v2` is the JA-RPC 2.0 JSON-RPC 2.0 contract between native clients and the Java App Server. The shared backend uses one WebSocket text message per JSON-RPC frame; the internal RpcServer still uses JSONL. All requests use `c:` ids and flow from clients to Java; Java never sends a reverse Tool request. Java owns configuration, credentials, workspace identity, Thread/Turn admission, Tools, MCP sessions, approval state, persisted Tool presentations, frozen Turn change artifacts, and SQLite facts; native clients request one selected artifact file at a time. JA-RPC 2.0 is a breaking contract: clients must use the v2 schema and exact field set; v1 aliases and dual parsing are not supported.

权威消息结构见 [JA-RPC 2.0 JSON Schema](schema/ja-rpc-v2.schema.json)。本地 Ja App Server 使用固定主体 `user_local`；`thread/create` 将 owner 保存为不可变 Thread 字段，客户端不提交 `userId`，后续 Core Store 执行身份从该 owner 构造。可复用 Core 仍要求独立宿主显式提供完整身份。

共享后台通过 `--ja-transport=websocket` 启动 Solon FeatHTTP，只监听 `127.0.0.1` 随机端口，并在 `/ja-rpc` 接受 WebSocket 连接。Java 在 `<run-dir>/app-server.endpoint.json` 原子发布 `{protocolMajor:2,protocolMinor:0,port,serverInstanceId,runtimeGeneration,token}`；token 是 64 个小写十六进制字符，仅保存在当前用户专属 ACL 或 POSIX 0600 文件中。首条 WebSocket 文本消息是独立认证帧 `{"token":"..."}`，最多 256 bytes、两秒超时；认证成功后每条文本消息承载一帧标准 JA-RPC JSON，失败直接断开。每个连接完成标准 initialize/initialized；实例身份和 generation 均属于唯一 Java owner。断开连接只释放请求路由和观察，不取消在途 Turn。五分钟无连接时服务端尝试正常关闭；有活动 Turn、待审批/澄清、活动 Plan，或活 Native owner 尚有临时根及侧聊创建请求时保持运行并重新计时。

认证客户端完成 initialize/initialized 后调用 `runtime/context/register`，严格提交完整宿主环境对象、nullable Shell 路径、正 safe integer `clientProcessId` 及 `native_` 加 32 位小写十六进制 `clientLifetimeId`。Java 在内存中固定进程 PID/startInstant 与不透明生命周期身份，RPC 请求绑定精确 owner 对象；PID 只用于活性及复用核对，不替代端点 token 鉴权。执行环境只在 Turn 准入时冻结给派生任务，不进入前端、普通日志或配置。旧的两字段注册不被接受，未注册上下文的业务请求返回 `NOT_INITIALIZED`。

普通断线不关闭 owner。正常 Native Stop/Exit 先封闭普通 RPC 准入，再调用 `runtime/client/close {timeoutMs}`，整数期限为 `1..20000` 毫秒；只有其临时根及派生树清理完成后才返回精确 `{accepted:true}`。该方法不接受目标 PID、owner 或 Thread 列表，也不停止共享后台、其他客户端或普通持久任务。响应未知时沿用同一生命周期身份重连现有后台，仅重试 scoped close，不启动新后台、生成新身份或恢复普通准入；旧 owner 进入关闭后不得再次创建侧聊。进程真正退出由服务端活性监听走同一关闭链，失败保留关闭状态。无资源 owner 在存储 blocked 时仍可注册并完成轻量关闭。

`runtime/shutdown {force?:boolean}` 是独立的全后台管理操作，不作为普通客户端退出方法；默认 false 在仍有待处理工作时返回 `INVALID_STATE`，成功结果为 `{accepted:true,status:"shutting_down"}`。显式 force 才进入现有有界取消关闭。

持久变更统一使用 `operationKey`；Core/Turn 与分叉键采用 `op_` 加 32 位小写十六进制字符。服务端用同事务的请求指纹和业务回执去重，同键但方法或内容不同返回 `CONFLICT`。`operation/read {operationKey,sourceThreadId?}` 返回 `{status:"unknown"}` 或 `{status:"committed",method,threadId,result}`；省略来源时查询 Core/Turn，提供来源时只查询该 owner 的 `thread/fork` 回执，不查询 Plan、Interaction 或 Task 自有账本。分叉回执外层 `threadId` 是来源，`result.thread.threadId` 才是新会话。unknown 不能证明副作用未发生；客户端保留原请求、版本与操作键，核查后沿用原身份重试。

`thread/fork` 以 `sourceItemId` 定位真实、已结算的最终答复，截断位置包含该项。Java 原子提交独立 Thread、冻结公开历史、中立模型上下文、有效 seed、重新授权的附件与操作回执。`thread/read` 统一分页原生与继承历史，Item 与展示用 Turn 都显式标记 `origin:native|inherited`；继承 Turn 不创建真实执行，也不控制来源资源。再次分叉及长正文回读只依赖目标自有数据。两端先持久保存请求身份，再发送；创建完成但打开失败时重试打开已有 Thread，不再次创建。

`thread/observe {threadId}` 在确认前为该连接建立事件观察，`thread/unobserve` 只撤销该连接的观察；两者成功均返回 `{accepted:true,threadId}`。Thread/Turn/Approval 详情事件只发送给观察该 Thread 的连接，runtime/configuration/workspace dirty/thread metadata 保留全局低频广播。Plan 进度和 Task 高频进度要求当前连接持有对应 `plan/observe`、`task/observe` 句柄；断开和 unobserve 只释放本连接的句柄，同 Thread 的低频 Task 发现仍可到达观察者。客户端在 observe ACK 后调用 `thread/read` 对账，期间事件继续排队；连接间通知 `sequence` 沿用进程全局物理写序，过滤其它 Thread 后的缺口正常。`thread/read {threadId,tail:true,limit?,cursor?}` 从最新一页开始，页内 items 仍按旧到新排列，`nextCursor` 指向更旧一页；每页保留权威最新及排队 Turn 身份，`liveStream` 基线由当前 Thread 活动状态决定，与历史分页方向无关。

Initialize requires `protocolMajor=2` and `protocolMinor=0`. Capabilities contain exactly `methods`, `events`, `accessModes`, `collaborationModes`, and `features`. `accessModes` is exactly `["approval_required","full_access"]`, `collaborationModes` is independently `["default","plan"]`, and `features` is `["task_threads","plan","interaction"]`; Plan never expands Tool authority. Limits additionally publish the fixed `maxTurnQueuedInputs=8` and `maxTurnQueuedInputBytes=524288` queue budgets. The Java-owned configuration accepts only `schema_version=2`; every Provider has one required `credential_id`, and that identity is unique across the Provider catalog. The root field `default_access_mode` has only `full_access` and `approval_required`; `thread/create` stores the selected Provider, Model, reasoning effort, access mode, and collaboration mode as durable Thread preferences, while `turn/start` accepts only the Thread identity, strict `content[]`. Approval decisions are only `approve` and `deny`. Each Provider request resolves current preferences, Prompt, Skills and Tool catalog immediately before dispatch; an already dispatched request and an already prepared Tool batch remain stable. `turn/resume` accepts exactly `{turnId,expectedThreadRevision}` and resumes only Java-owned persisted execution state; `turn/cancel` accepts exactly `{turnId}` and is an idempotent highest-priority stop intent whose authoritative Thread revision and Turn mutation version are read inside SQLite; repeated claims reuse the first receipt, and a natural terminal or cancellation race returns the current terminal ACK. Rust and React do not hold a recovery cursor. `turn/input/enqueue`, `turn/input/prioritize`, `turn/input/update`, and `turn/input/delete` are the only pending-input mutation methods.

Plan is a Java/SQLite aggregate owned by one Thread. Plan mutations carry `expectedPlanRevision` and `operationKey`; approval and execution bind the exact `planRevisionId` and canonical JSON SHA-256 `planHash`. Rust/Tauri validates and proxies typed JA-RPC payloads, while React observes authoritative snapshots plus sequenced events.

Interaction is a shared Conversation capability owned by Java/SQLite. `interaction/read` and `interaction/observe` return the current request, local draft, event sequence, and the authoritative `resumeState`, whose values are `none`, `waiting_for_answer`, `waiting_to_resume`, `resuming`, `settled`, and `closed`. `interaction/respond` atomically persists the stable option/self-text/skip answer, settles the originating Tool cursor, and schedules the same Turn identity for resume; an `operationKey` retry returns the original result and cannot wake the model twice. `interaction/cancel` invalidates the pending request without granting approval or execution authority. A Thread has at most one active request batch, and a pending request is not implicitly expired or answered by a recommendation. The client must preserve drafts when confirmation or resume fails and rebuild from the authoritative snapshot after reconnect.

`plan/current/read` 按 Thread 恢复最近计划；`plan/read`、`plan/revisions/list`、`plan/events/read`、`plan/evidence/list` 提供精确版本和 Run 的读取。`plan/observe` / `plan/unobserve` 管理连接观察，`plan/changed` 只发送 Plan 行与步骤摘要。`plan/execute` 在同一事务记录当前 revision/hash 授权与唯一 Run；`plan/pause`、`plan/resume`、`plan/stop` 始终绑定原 Run。对话中的审批与澄清通过 Conversation Interaction 表达，不并入 Plan 投影。

Plan failures use the frozen catalog tuples `PLAN_NOT_FOUND/-32086/not_found/false`, `PLAN_REVISION_CONFLICT/-32087/conflict/true`, `PLAN_INVALID_STATE/-32088/conflict/false`, `PLAN_INVALID/-32089/validation/false`, `PLAN_APPROVAL_STALE/-32090/conflict/false`, `PLAN_EVIDENCE_INCOMPLETE/-32091/conflict/false`, and `PLAN_RECOVERY_REQUIRED/-32092/conflict/false`.

`suspended` is the public non-terminal restart state. App Server startup may reconcile persisted active work into this state without invoking a Provider or Tool. An explicit `turn/resume`, or the scheduler after a durably committed Interaction answer, may advance `suspended` to `queued`; subsequent execution uses the existing `turnId`. `turn/cancel` accepts a suspended Turn directly.

`thread/compact` is the only client-triggered context compaction method. It is guarded by Thread revision CAS and the idle-state invariant, and it does not authorize a normal assistant generation. `thread/compact/cancel` stops only the same connection's in-flight manual request; its ACK is not a completion claim. Automatic, manual, and overflow-recovery compaction share the same Thread-level lifecycle schema and `ja-context-v1` strategy identity.

`cwd` is fixed when `thread/create` succeeds. It remains the relative-path base and default Shell cwd, not a filesystem containment boundary. Built-in Tools execute inside Java, while additional Tools come from the request-level MCP catalog and keep their prepared batch binding until settlement.

Start, enqueue, update, queue events, and history share one ordered `content[]` union; there is no queue-only `text` compatibility field. `workspace/path/search` is the only Composer path lookup and returns generation-fenced relative metadata without reading file bodies.

Rust owns native file selection and private staging; Java owns attachment blobs, metadata, lifecycle, quota, and Turn association. The WebView and ordinary JA-RPC history never receive a source path, staging path, ingress token, or content hash.

Java also owns the durable Thread read boundary. `latestTurnSeen` is projected from the persisted latest Turn sequence and the persisted seen sequence; it is not renderer-local state and does not replace `latestTurnStatus`. `thread/seen` advances that boundary by Thread revision CAS and returns the complete authoritative Thread projection. Active states remain visible independently of the seen boundary, while terminal success/failure can use it as the unread signal.

Raw Tool arguments/results and raw reasoning never cross the public wire. Java projects bounded, redacted `ToolPresentation` before persistence or notification. Tool artifacts use character pagination and retain Call ownership; persisted Turn Diffs return one selected UTF-8 file as bounded Base64 and retain Turn ownership. Neither reader accepts a physical path or a caller-selected identity alias.

There is no `runtime/configure`, Host Tool RPC, Sandbox RPC, old Tool alias, session grant, old `turn/start.input`, `turn/resume.force`, `turn/steer`, `turn/follow-up`, raw Tool `arguments/content/value`, `workspaceDirty/dirtyReason`, or compatibility enum. Secret material is legal only in `credential/set.secret` and is never echoed.

MCP 设置查询保持配置与健康事实分离：`mcp/list` 的 `configured` 仅表示已保存定义；
`mcp/test` 的 `available` 表示实际握手与目录探测成功。二者的服务摘要均含
`mcpId`、`name`、`transport`、`status` 和 `toolCount`，不返回地址、参数或凭据。
完整响应字段与状态分别由 JSON schema、TypeScript parser 和 Rust 设置边界校验。
停用不删除定义；当前已准备的工具批次继续遵守 Java 的资源引用和安全点释放约束。
