<!-- @author kongweiguang -->

# JA RPC v1 transport and ownership

Rust Host passes the required `--ja-runtime-generation=<positive safe integer>` process argument to Java. Notifications use this exact generation together with `serverInstanceId`; restart never resets the host generation to one, and launch configuration cannot override the owner-injected value.

用户级 `interaction.clarification_enabled` 默认开启，只控制非 Plan 模式的澄清提问；项目配置不能覆盖。Plan 规划阶段始终允许澄清，并由后端目录过滤与执行前策略限制为可信只读或内部计划状态操作。模型原生结构化 Tool 调用是产生问答和计划动作的唯一入口，普通文本标记不参与执行。

用户配置文档必填 `subagents: {enabled: boolean, provider_id: string|null, model_id: string|null, reasoning_level: string|null}`。
默认 true/null/null；两个引用均为 null 表示跟随父任务，否则必须成对引用现有模型。
`reasoning_level` 为 null 时沿用父任务（跟随模式）或所选模型默认；指定档位只接受该模型支持的
`off|minimal|low|medium|high|xhigh|max`。跟随模式禁止独立覆盖思考等级。
关闭仍保留模型与思考选择，项目层禁止覆盖；配置读写继续使用现有 configuration 方法与 CAS。

`ja-rpc/v1` is the JA-RPC 1.0 JSON-RPC 2.0 JSONL contract between Tauri and the Java app-server. All requests use `c:` ids and flow from Tauri to Java; Java never sends a reverse Tool request. Java owns configuration, credentials, workspace identity, Thread/Turn admission, Tools, MCP sessions, approval state, persisted Tool presentations, frozen Turn change artifacts, and SQLite facts; Rust/Tauri requests one selected artifact file at a time.

Initialize requires `protocolMajor=1` and `protocolMinor=0`. Capabilities contain exactly `methods`, `events`, `accessModes`, `collaborationModes`, and `features`. `accessModes` is exactly `["approval_required","full_access"]`, `collaborationModes` is independently `["default","plan"]`, and `features` is `["task_threads_v1","plan_goal_v1","interaction_v1"]`; Plan never expands Tool authority. Limits additionally publish the fixed `maxTurnQueuedInputs=8` and `maxTurnQueuedInputBytes=524288` queue budgets. The Java-owned configuration accepts only `schema_version=1`; every Provider has one required `credential_id`, and that identity is unique across the Provider catalog. The root field `default_access_mode` has only `full_access` and `approval_required`; `thread/create` stores the selected Provider, Model, reasoning effort, access mode, and collaboration mode as durable Thread preferences, while `turn/start` accepts only the Thread identity, strict `content[]`, and optional deadline. Approval decisions are only `approve` and `deny`. Each Provider request resolves current preferences, Prompt, Skills and Tool catalog immediately before dispatch; an already dispatched request and an already prepared Tool batch remain stable. `turn/resume` accepts exactly `{turnId,expectedThreadRevision}` and resumes only Java-owned persisted execution state; Rust and React do not hold a recovery cursor. `turn/input/enqueue`, `turn/input/prioritize`, `turn/input/update`, and `turn/input/delete` are the only pending-input mutation methods.

Goal and Plan are independent Java/SQLite aggregates. Every Goal mutation carries `expectedGoalRevision` and `idempotencyKey`; every Plan mutation carries `expectedPlanRevision` and `idempotencyKey`. Plan approval and execution bind the exact `planRevisionId` and canonical JSON SHA-256 `planHash`, while `goal/plan/attach` additionally binds that approved revision through a nullable `GoalPlanLink`. A Goal can run and complete without any Plan. Rust/Tauri only validates and proxies typed JA-RPC payloads, while React observes authoritative snapshots plus sequenced events. A Goal belongs to a main Thread or an independent Side Task; a Subagent cannot own or approve it. A Plan belongs directly to a Thread.

Interaction is a shared Conversation capability owned by Java/SQLite and referenced by Plan and Goal rather than duplicated in either aggregate. `interaction/read` and `interaction/observe` return the current request, local draft, event sequence, and the authoritative `resumeState`, whose values are `none`, `waiting_for_answer`, `waiting_to_resume`, `resuming`, `settled`, and `closed`. `interaction/respond` atomically persists the stable option/self-text/skip answer, settles the originating Tool cursor, and schedules the same Turn identity for resume; an idempotent retry returns the original result and cannot wake the model twice. `interaction/cancel` invalidates the pending request without granting approval or execution authority. A Thread has at most one active request batch, and a pending request is not implicitly expired or answered by a recommendation. The client must preserve drafts when confirmation or resume fails and rebuild from the authoritative snapshot after reconnect.

`thread/read.goalActivities` is a required array containing at most the 128 most recent terminal Goals owned by that Thread. It contains only `achieved` and `stopped` projections, ordered by terminal event sequence from newest to oldest; each row binds the Goal snapshot and terminal event from the same Goal revision. Active, paused, draft, and recovery state remains available through the Goal read/observe contract and is never inferred from this timeline projection.

Plan 与 Goal 保持独立。`plan/current/read` 按 Thread 恢复最近计划；`plan/read`、`plan/revisions/list`、`plan/events/read`、`plan/evidence/list` 提供精确版本和 Run 的读取。`plan/observe` / `plan/unobserve` 管理连接观察，`plan/changed` 只发送 Plan 行与步骤摘要。`plan/execute` 在同一事务记录当前 revision/hash 授权、唯一 Run 与冻结预算；`plan/pause`、`plan/resume`、`plan/stop` 始终绑定原 Run。Goal 问答不再内嵌在 Goal 投影中，统一使用 Conversation Interaction；`goal/changed` 与 `goal/activity` 只表示 Goal 状态与活动。

Goal/Plan failures use the frozen catalog tuples `GOAL_NOT_FOUND/-32086/not_found/false`, `GOAL_REVISION_CONFLICT/-32087/conflict/true`, `GOAL_INVALID_STATE/-32088/conflict/false`, `PLAN_INVALID/-32089/validation/false`, `PLAN_APPROVAL_STALE/-32090/conflict/false`, `GOAL_EVIDENCE_INCOMPLETE/-32091/conflict/false`, `GOAL_RECOVERY_REQUIRED/-32092/conflict/false`.

Implementation status as of 2026-09-05: schema and golden frames, Java domain/persistence and handlers, Goal notifications, the static Plan Goal Agent Extension, continuation scheduling, the independent no-tool evaluator, startup recovery, Rust typed proxying, and React authoritative projection are wired into production composition. Runtime exposure remains bounded by the negotiated `plan_goal_v1` feature. Native Image, isolated Windows Tauri/WebView2 acceptance, and the 120-minute deterministic mock soak remain release gates and cannot be inferred from contract or unit-test success.

`suspended` is the public non-terminal restart state. App Server startup may reconcile persisted active work into this state without invoking a Provider or Tool. An explicit `turn/resume`, or the scheduler after a durably committed Interaction answer, may advance `suspended` to `queued`; subsequent execution uses the existing `turnId`. `turn/cancel` accepts a suspended Turn directly.

`thread/compact` is the only client-triggered context compaction method. It is guarded by Thread revision CAS and the idle-state invariant, and it does not authorize a normal assistant generation. Automatic, manual, and overflow-recovery compaction share the same Thread-level lifecycle schema and `ja-context-v1` strategy identity.

`cwd` is fixed when `thread/create` succeeds. It remains the relative-path base and default Shell cwd, not a filesystem containment boundary. Built-in Tools execute inside Java, while additional Tools come from the request-level MCP catalog and keep their prepared batch binding until settlement.

Start, enqueue, update, queue events, and history share one ordered `content[]` union; there is no queue-only `text` compatibility field. `workspace/path/search` is the only Composer path lookup and returns generation-fenced relative metadata without reading file bodies.

Rust owns native file selection and private staging; Java owns attachment blobs, metadata, lifecycle, quota, and Turn association. The WebView and ordinary JA-RPC history never receive a source path, staging path, ingress token, or content hash.

Java also owns the durable Thread read boundary. `latestTurnSeen` is projected from the persisted latest Turn sequence and the persisted seen sequence; it is not renderer-local state and does not replace `latestTurnStatus`. `thread/seen` advances that boundary by Thread revision CAS and returns the complete authoritative Thread projection. Active states remain visible independently of the seen boundary, while terminal success/failure can use it as the unread signal.

Raw Tool arguments/results and raw reasoning never cross the public wire. Java projects bounded, redacted `ToolPresentation` before persistence or notification. Tool artifacts use character pagination and retain Call ownership; persisted Turn Diffs return one selected UTF-8 file as bounded Base64 and retain Turn ownership. Neither reader accepts a physical path or a caller-selected identity alias.

There is no `runtime/configure`, Host Tool RPC, Sandbox RPC, old Tool alias, session grant, old `turn/start.input`, `turn/resume.force`, `turn/steer`, `turn/follow-up`, separate `operationId`, raw Tool `arguments/content/value`, `workspaceDirty/dirtyReason`, or compatibility enum. Secret material is legal only in `credential/set.secret` and is never echoed.

MCP 设置查询保持配置与健康事实分离：`mcp/list` 的 `configured` 仅表示已保存定义；
`mcp/test` 的 `available` 表示实际握手与目录探测成功。二者的服务摘要均含
`mcpId`、`name`、`transport`、`status` 和 `toolCount`，不返回地址、参数或凭据。
完整响应字段与状态分别由 JSON schema、TypeScript parser 和 Rust 设置边界校验。
停用不删除定义；当前已准备的工具批次继续遵守 Java 的资源引用和安全点释放约束。
