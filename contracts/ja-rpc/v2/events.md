<!-- @author kongweiguang -->

# JA RPC 2.0 notifications

`workspace/metadata-changed` uses the common event envelope plus exact `workspaceId` and
`revision`. It is emitted after a committed project update. Clients invalidate metadata and
call `workspace/read`; the event is not a replacement projection. Name-only updates retain file
bindings. Root changes revoke the old native binding and require resources to reopen against the
registered root. Late callbacks remain fenced by Thread, root/revision and click sequence.

`approval/requested` 必须携带 `presentation`，引用与待执行 Tool 相同的冻结、校验后脱敏展示。
该字段复用 `ToolPresentation`，但 `status` 只能为 `pending`，不得包含 `media`。客户端按
`turnId + callId` 关联工具行，使用安全命令或目标展示待批准内容；不得读取原始参数或把内部
调用标识当作用户文案。缺字段、其他状态或媒体引用均视为无效帧，停止消费并权威重读。
新必填字段要求 Java、Rust、TypeScript 通知消费者同步更新，协议不保留旧通知形态。

`turn/messages_received` publishes a non-empty `items` batch only after mailbox consumption commits.
It uses the common Turn event envelope; each item has the `thread_message` content fields returned by
`thread/read`, without the snapshot-only `origin` field. Live items belong to the current Thread and clients
project them as `origin="native"`; snapshots explicitly distinguish `native` and `inherited` history.
Items are ordered by mailbox sequence and belong to the event's Turn. Receiving a message
does not wake an idle Thread, interrupt an in-flight Provider request, or create a Task activity card.
The next normal Provider request consumes pending messages and receives their explicit source context.

Runtime, Turn, Tool, approval, context, workspace, and configuration notifications are transactional and redacted. `assistant/model-step-committed` is emitted only for an Assistant message with one or more ordered Tool calls; it publishes durable progress text, optional public reasoning summary, model round, usage, and those calls. A zero-call final Assistant message is represented by `turn/terminal.finalMessage`, not an empty model-step event. Each Tool call contains only a strict `ToolPresentation`; raw arguments and byte-count placeholders are invalid. `callId` is an opaque Provider-native identifier preserved exactly across the event, result, approval, and artifact-read path; JA-RPC accepts 1–128 ASCII characters, starting with an alphanumeric and continuing with alphanumeric, `.`, `_`, `:`, or `-`.

`assistant/context-estimated` publishes the current full request envelope estimate immediately before an ordinary Assistant Provider dispatch. Its nested Usage is `certainty="unknown"` with null Provider Token fields, and `contextEstimate` carries the Java-local base estimate, model window, and timestamp. The estimate excludes send-admission safety margin, never enters the measured Usage ledger, and is replaced by Provider Usage when the same request settles. It is persisted beside the UNKNOWN request so `thread/read.contextUsage` can restore it after restart. Summary requests do not publish this event.

`turn/retry-started` uses the normal Turn event envelope and carries a monotonic `attempt` starting at 2 without an attempt budget. It follows settlement of the failed request's independent Usage fact. Clients clear that attempt's temporary Assistant and reasoning draft, retain the monotonic stream sequence, and keep the Turn in the working state. Recoverable transport failures do not produce a terminal error; a deterministic external blocker or explicit cancellation closes the Turn.

Request-level Usage has one shape across `assistant/model-step-committed`, `assistantSettlement`, `turn/terminal`,
and `thread/read.contextUsage`. A current record includes the complete `ProviderRequestProfile`; a migrated record
always carries the complete request profile. `certainty="unknown"` keeps all Token values null when the Provider
did not return trustworthy metering. A local `contextEstimate` is allowed only on UNKNOWN Assistant usage and is never
Provider measurement. Higher request ordinals replace older facts; the same request identity only permits an UNKNOWN-to-KNOWN
upgrade with an identical profile.

`turn/terminal` is a state-discriminated closed projection and always carries the `changeSet` field, which is either a committed ChangeSet object or JSON null. Every state requires `summary`, `summaryTotalCharacters`, and `summaryTruncated`; the summary preview is limited to 512 Unicode code points, the total counts the complete summary in code points, and the truncation flag reports whether text was omitted. When present, `finalMessage` requires `messageId`, `text`, `totalCharacters`, and `truncated`; its text preview is also limited to 512 Unicode code points, its total counts the complete text in code points, and its truncation flag reports whether text was omitted. `completed` requires `finalMessage` and a non-null committed ChangeSet. `failed` requires the stable `errorCode` and redacted `errorMessage` pair, and may omit `finalMessage` when execution produced no final assistant message; `cancelled` carries no final message or error fields. For failed and cancelled Turns, ChangeSet is null when no committed ChangeSet fact exists at the terminal watermark; if one exists, the exact fact is preserved. The server never synthesizes an empty ChangeSet or removes already committed changes. Clients show actual error details instead of inventing an assistant reply.

`turn/resync-required` is a non-durable observer notification emitted when the server cannot safely project a committed Turn event. Its parameters are `serverInstanceId`, `eventId`, the standard connection-wide `sequence`, `occurredAt`, `generation`, `workspaceId`, `threadId`, `turnId`, and `reasonCode="projection_failed"`. The sequence only orders JA-RPC notifications on this connection; it is not a durable event cursor or a replay position. The event never means that execution failed or was cancelled. The client must reload `thread/read` as the authoritative snapshot and keep the existing observation active so later durable events can resume delivery. The server does not skip the unprojected commit or repeat the notification while that same projection is blocked.

`tool/started` is published only after the Tool execution boundary has atomically committed both the internal `RUNNING` state and `ToolPresentation.status=running`. It carries only `callId` and the Turn-global `ordinal` in addition to the common Turn event envelope. Consumers update the matching prepared Tool in place; unknown, mismatched, duplicate, terminal, or late started events fail closed and never downgrade a completed Tool. Turn lifecycle also distinguishes `waiting_input` from `waiting_approval`; the latter remains specific to Tool approval.

`tool/batch-committed` carries ordered Java-executed built-in or MCP Tool results. Each result contains its outcome and completed `ToolPresentation`; raw `content`, `workspaceDirty`, `dirtyReason`, and ad-hoc file-change fields are invalid. Turn file changes have exactly one owner: the separately committed `TurnChangeSet`. There is no `host-tool/cancel` notification or `h:` namespace.

`ToolPresentation.durationMs` is an optional non-negative integer measured in milliseconds around the actual host tool's asynchronous execution, after any workspace write claim is acquired. A normally returned failure result may also have a measured duration. An admission rejection, exceptional stage, or recovery verification without the original measurement omits this field; consumers must not interpret omission as zero or infer elapsed execution time from event timestamps. When present, the value is committed with the result and preserved in history and replay.

`tool/recovery-changed` carries the durable `recoveryId`, the unchanged Provider-native `callId`, a monotonic recovery `revision`, and the closed status/disposition pair. `pending` with no disposition and `requiresUserAction=true` exposes an explicit recovery action; settled states always have `requiresUserAction=false`. This event reports only the Core recovery decision. It does not complete or fail a Tool and does not claim an unknown external side effect succeeded.

`approval/closed` closes a pending approval because its wait was cancelled, superseded, or expired. Its `reason` is exactly `cancelled`, `superseded`, or `expired`, and it carries no user decision. A user choosing deny remains `approval/resolved` with `decision="deny"`; clients must not display a system close as a user rejection.

`ToolPresentation` exposes a closed Tool kind/status, bounded title and preview fields, safe relative paths, optional Shell command/cwd/stdout/stderr/exit/duration, truncation state, an opaque artifact identity, and optional recovery affordance revision. Status is exactly `pending`, `running`, `waiting_approval`, `success`, `error`, or `cancelled`; approval waiting is a real state rather than inferred from a disabled control, while any unrecognized status fails closed and forces authoritative resynchronization. Its previews are capped at 32 Ki characters and its relative path list at 64 items. Secret-shaped keys, absolute paths, unknown fields, and unredacted result bodies fail closed.

Context compaction is a Thread-level lifecycle with exactly three events:

- `context/compaction-started`
- `context/compacted`
- `context/compaction-failed`

All three carry the normal notification metadata plus `workspaceId`, `threadId`, nullable `turnId`, `threadRevision`, `compactionId`, `trigger`, `sourceRevision`, `inputTokensBefore`, `inputTokensAfter`, and `strategyVersion="ja-context-v1"`. `trigger` is the closed set `automatic`, `manual`, and `overflow_recovery`.

The started event requires a measured `inputTokensBefore` and JSON null `inputTokensAfter`. The compacted event requires both counts, a `checkpointId`, and an after count lower than before. The failed event has no `checkpointId`, requires JSON null `inputTokensAfter`, allows `inputTokensBefore` to be null when counting never completed, and exposes only a stable `errorCode`. Manual compaction uses `turnId:null`; automatic and overflow recovery attach the owning Turn.

`thread/metadata-changed` is the single committed title invalidation event. Its `titleSource` is the closed set `placeholder`, `auto`, and `manual`: first-turn admission publishes `placeholder` before Agent execution, while a later automatic summary or explicit rename publishes the corresponding final ownership. Consumers apply only a newer revision in the current runtime generation and otherwise reload the authoritative Thread projection.

`turn/input-queue-changed` carries the normal server/event/sequence/time/generation and
Workspace/Thread/Turn identities plus the full post-mutation `inputQueue`. It deliberately has no
`threadRevision`: queue mutations advance only `inputQueue.revision`, so consumers ignore duplicate or older
queue revisions without perturbing Timeline ordering.

`turn/input-consumed` participates in the Thread semantic revision stream. It carries `threadRevision`, the
complete consumed `input`, the committed `userItem`, and the full post-consumption `inputQueue`. The optional
`assistantSettlement` is exactly `{messageId,text,modelRound,usage?,reasoningSummary?}` and atomically settles
the previous STOP-boundary Assistant message before the new user item. `input` is the pre-consumption
`QueuedInput`; `userItem` is exactly `{itemId,createdAt,turnId,kind:"user_input",content,attachments,totalCharacters,truncated}`. Both
attachment arrays must preserve the same strict summaries and order as their attachment content blocks.
The live `userItem` likewise omits snapshot-only `origin` and is projected as `native` by clients.
Consumers must apply the event atomically so a queue row cannot coexist with its committed Timeline item.

`configuration/changed` remains an invalidation hint. It contains no cwd, path, Provider/Model body, credential, or Secret; clients reissue `configuration/read` for the committed projection.

Task Threads publish four notifications. `task/activity` is the durable ordered fact used by the parent Timeline and overview. Its envelope, activity and embedded Task summary name the same root/task identity and revision; the activity sequence and safe summary equal the embedded projection's latest values. `task/mailbox-changed` is a durable low-frequency invalidation carrying a positive latest mailbox sequence and unread count. `task/progress` is emitted only for an active connection-scoped `task/observe` handle in the current sidecar generation, may be coalesced by task identity, and never substitutes for approval, error, mailbox, or terminal activity. `task/closed` is published after the close receipt commits. Its `rootThreadId` is the owner identity fixed at the first close and reused on retries; it is a routing identity and may no longer resolve to a current Thread after a later parent purge. The event carries the purged side-chat root plus the complete unique deletion identity set; that set must contain `taskThreadId`, while `rootThreadId` must differ from it. Clients remove listed IDs idempotently. Reload, disconnect, stop and shutdown revoke local observation routing and compensate server handles. A renderer that detects a revision gap discards progress and reloads `task/read`.

Plan 通过 `plan/changed` 发布 Thread-owned Plan 状态及 `progress`（当前步骤、必要步骤总数与完成数），不携带完整计划正文或证据。公共 `interaction/changed` 仅携带 Thread、请求、revision、事件序列和变化类型；客户端注册观察后读取快照对账，草稿和答案不在事件中广播。缺口、重连与迟到事件通过权威 read 和单调水位修复，事件本身不批准或启动执行。
