<!-- @author kongweiguang -->

# JA RPC v1 notifications

`turn/messages_received` publishes a non-empty `items` batch only after mailbox consumption commits.
It uses the common Turn event envelope; each item is the same `thread_message` shape returned by
`thread/read`. Items are ordered by mailbox sequence and belong to the event's Turn. Receiving a message
does not wake an idle Thread, interrupt an in-flight Provider request, or create a Task activity card.
The next normal Provider request consumes pending messages and receives their explicit source context.

Runtime, Turn, Tool, approval, context, workspace, and configuration notifications are transactional and redacted. `assistant/model-step-committed` publishes durable progress text, optional public reasoning summary, model round, usage, and ordered Tool calls. Each Tool call contains only a strict `ToolPresentation`; raw arguments and byte-count placeholders are invalid.

Request-level Usage has one shape across `assistant/model-step-committed`, `assistantSettlement`, `turn/terminal`,
and `thread/read.contextUsage`. A current record includes the complete `ProviderRequestProfile`; a migrated record
always carries the complete request profile. `certainty="unknown"` keeps all Token values null when the Provider
did not return trustworthy metering. Higher request ordinals replace older
facts; the same request identity only permits an UNKNOWN-to-KNOWN upgrade with an identical profile.

`turn/terminal` is a state-discriminated closed projection and always carries the final `changeSet`. Both `completed` and `failed` require a bounded `finalMessage`; `failed` additionally requires the stable `errorCode` and redacted `errorMessage` pair. `cancelled` carries none of those three fields. Terminal state, final message, Usage, ChangeSet, and its frozen Diff artifact are committed in one SQLite transaction before publication.

`tool/started` is published only after the Tool execution boundary has atomically committed both the internal `RUNNING` state and `ToolPresentation.status=running`. It carries only `callId` and the Turn-global `ordinal` in addition to the common Turn event envelope. Consumers update the matching prepared Tool in place; unknown, mismatched, duplicate, terminal, or late started events fail closed and never downgrade a completed Tool.

`tool/batch-committed` carries ordered Java-executed built-in or MCP Tool results. Each result contains its outcome and completed `ToolPresentation`; raw `content`, `workspaceDirty`, `dirtyReason`, and ad-hoc file-change fields are invalid. Turn file changes have exactly one owner: the separately committed `TurnChangeSet`. There is no `host-tool/cancel` notification or `h:` namespace.

`ToolPresentation` exposes a closed Tool kind/status, bounded title and preview fields, safe relative paths, optional Shell command/cwd/stdout/stderr/exit/duration, truncation state, and an opaque artifact identity. Status is exactly `pending`, `running`, `waiting_approval`, `success`, `error`, or `cancelled`; approval waiting is a real state rather than inferred from a disabled control, while any unrecognized status fails closed and forces authoritative resynchronization. Its previews are capped at 32 Ki characters and its relative path list at 64 items. Secret-shaped keys, absolute paths, unknown fields, and unredacted result bodies fail closed.

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
`QueuedInput`; `userItem` is exactly `{itemId,createdAt,turnId,kind:"user_input",content,attachments}`. Both
attachment arrays must preserve the same strict summaries and order as their attachment content blocks.
Consumers must apply the event atomically so a queue row cannot coexist with its committed Timeline item.

`configuration/changed` remains an invalidation hint. It contains no cwd, path, Provider/Model body, credential, or Secret; clients reissue `configuration/read` for the committed projection.

Task Threads publish exactly three additional notifications. `task/activity` is the durable ordered fact used by the parent Timeline and overview. Its envelope, activity and embedded Task summary name the same root/task identity and revision; the activity sequence and safe summary equal the embedded projection's latest values. `task/mailbox-changed` is a durable low-frequency invalidation carrying a positive latest mailbox sequence and unread count. `task/progress` is emitted only for an active connection-scoped `task/observe` handle in the current sidecar generation, may be coalesced by task identity, and never substitutes for approval, error, mailbox, or terminal activity. Reload, disconnect, stop and shutdown revoke local observation routing and compensate server handles. A renderer that detects a revision gap discards progress and reloads `task/read`.

Goal 发布 `goal/changed` 与 `goal/activity`，仍携带自己的身份、revision 与持久序列。Plan 通过 `plan/changed` 发布独立状态及 `progress`（当前步骤、必要步骤总数与完成数），不携带完整计划正文或证据。公共 `interaction/changed` 仅携带 Thread、请求、revision、事件序列和变化类型；客户端注册观察后读取快照对账，草稿和答案不在事件中广播。缺口、重连与迟到事件通过权威 read 和单调水位修复，事件本身不批准或启动执行。
