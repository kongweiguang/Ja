<!-- @author kongweiguang -->

# JA RPC v1 result rules

`configuration/read` returns one explicit `cas` object containing exactly `userVersion`,
`projectVersion`, and `credentialVersion`; layer projections do not repeat these versions.
Configuration and credential mutations require `expectedVersion` and return the newly committed
`version`. No result echoes Secret material. List methods return `{items,nextCursor}`. Successful
`turn/start` and `turn/resume` return the same exact admission receipt field set
`{accepted:true,queued:boolean,turnId,threadRevision}`. `turn/start` may report `queued:false` when execution starts immediately;
`turn/resume` always reports `queued:true` because it transitions the existing Operation from `suspended` to `queued` and never creates or returns another Operation identity.

Every Thread result contains required `pinned:boolean`, required nullable `latestTurnStatus`, and required
`latestTurnSeen:boolean` in addition to identity, preferences, lifecycle status, revision, and timestamps.
`latestTurnStatus` is selected by the largest persisted `turn_sequence`; null means the Thread has never owned
a Turn and therefore requires `latestTurnSeen=true`. `latestTurnSeen` compares that same sequence with the
durable seen boundary and never suppresses a non-terminal execution status. `thread/pin`, `thread/seen`,
`thread/archive`, and `thread/restore` return this complete Thread shape. `thread/seen` returns
`latestTurnSeen=true` for the latest Turn visible at that CAS commit. Archive results have
`status="archived"` and `pinned=false`; restore results have `status="active"` and `pinned=false`.

`skill/list` projects only the effective precedence winner for each Skill name. `scope` is exactly one of
`builtin`, `user`, `ja`, or `project`; unconfigured discoveries are returned disabled until the user
explicitly enables them. Physical roots, `SKILL.md` bodies, and package revisions do not cross JA-RPC.

`attachment/import` and `attachment/discard` return exactly
`{attachmentId,workspaceId,displayName,sizeBytes,mediaKind,mediaType,state,createdAt,expiresAt,boundMessageId}`.
`boundMessageId` is non-null only for `state="bound"`. The result never includes `sha256`, ingress token,
staging identity, or a physical path. History does not create a standalone attachment item;
`thread/read.items[].kind="user_input"` retains the attachment block in `content` and carries a same-order
`attachments` summary array for display and preview authorization.

`thread/read.inputQueue` is required and nullable. It is null when the Thread has no active Turn queue;
otherwise `{turnId,revision,accepting,items}` is the complete durable projection, and `items` order is the
only authoritative consumption order. Each `QueuedInput` is exactly
`{inputId,turnId,content,attachments,kind,status,issue,inputRevision,createdAt}`. `attachments` uses the same
strict summary shape and order as USER Message items. `status` is `pending` with a null
`issue`, or `needs_attention` with `{errorCode,message,retryable}`; `kind` remains `follow_up` or `steering`.

`thread/read` user input items carry the complete structured `content[]`; assistant progress,
reasoning summaries, and final answers retain bounded `text`. Progress and reasoning require `modelRound`.
Tool history uses one canonical `tool_call` item per `callId`; its `presentation` is updated to the latest
durable status and the removed raw `value` is forbidden. Pending approval items include only the safe
call/tool association, public reason, expiry and decision needed to restore an actionable card. Every Turn contains
required nullable `changeSet`; a non-null value is the persisted Turn summary. Frozen Diff content is read only through `turn/change-set/read` after the Turn has settled.

`thread/read.contextUsage` is a required nullable field containing the latest durable request-level Usage and its
required `turnId`; event-scoped Usage obtains that identity from the enclosing event instead of duplicating it.
Every Usage carries `requestId`, monotonic `requestOrdinal`, `modelRound`, `purpose`, `certainty`, the complete
`profile`, all three Token fields, and `measuredAt`. `certainty="unknown"` requires null Token fields. KNOWN requires integer
Tokens with `totalTokens >= inputTokens + outputTokens`; UNKNOWN never means zero. Snapshot Turns have no `runtime`.
Clients calculate context capacity only from `usage.profile.contextWindowTokens` and never current preferences.

`thread/read.turns[].status` is one of `queued`, `running`, `waiting_approval`, `suspended`,
`completed`, `failed`, or `cancelled`. `suspended` is non-terminal and has null `completedAt`; it is
authoritative reload state, not an event-stream guess. Cancelling a suspended Turn commits and returns
`status:"cancelled"`; it never leaves a successfully cancelled Operation suspended.

`tool/artifact/read` returns character pagination
`{artifactId,offsetCharacters,nextOffsetCharacters,totalCharacters,truncated,content}` and is authorized by
the exact Thread/Turn/Call/artifact tuple. `turn/change-set/read` is a separate complete-file contract returning
`{artifactId,filePath,byteLength,sha256,contentBase64}`; `filePath` exactly echoes the required request path.
`contentBase64` is canonical standard Base64 for at most 2,097,152 valid UTF-8 bytes, `byteLength` is the decoded
length, and lowercase `sha256` is calculated over those exact decoded bytes. The maximum encoded content is
2,796,204 characters, which keeps the complete result envelope below the negotiated 4 MiB frame limit.

Every terminal ChangeSet uses `state="complete"` with an empty `incompleteReasons`, or `state="partial"`
with at least one reason from `unknown_mutator`, `mutation_chain_broken`, `outside_workspace`, `limit_exceeded`,
`capture_failed`, `commit_unconfirmed`, and `recovery_boundary`. Files are limited to 256 text changes and always
carry additions, deletions, `binary:false`, and truncation state. The deleted `turn/change-set/commit` result has no
compatibility shape; only Java creates and persists the frozen artifact.

All four `turn/input/*` mutations return exactly `{accepted:true,inputId,inputQueue}`. The queue is the
post-mutation authoritative projection; clients merge it by its queue `revision` and never synthesize order
from ACK arrival order. A denied Tool is represented as a normal Tool result so the model can continue the Turn. No result contains Host Tool CAS fields or a session grant.

`workspace/path/search` returns exactly `{threadId,workspaceId,generation,query,items,truncated}`.
Each item is `{relativePath,kind}`; no absolute root, file body, match excerpt, or hidden scan state crosses the wire.

`thread/compact` returns exactly
`{outcome,compactionId,checkpointId,threadRevision,inputTokensBefore,inputTokensAfter}` and always emits both nullable identity fields. `outcome="compacted"` requires non-null `compactionId` and `checkpointId`, a committed `threadRevision`, and `inputTokensAfter < inputTokensBefore`. `outcome="unchanged"` requires both identities to be null and equal before/after counts; it means no new durable facts needed another Checkpoint.

Task Thread results are method-specific and strict. `task/create` returns
`{accepted:true,task}` for an idle Child Thread; `task/list` returns `{items}` with at most 64 complete Task summaries;
`task/read` returns `{task,thread,contextSeed,activities,mailbox,nextCursor}` without materializing the Child
transcript. `task/observe` returns `{observationId,taskThreadId,revision}` and `task/unobserve`
returns only `{accepted:true}`. `thread/message/send` returns
`{accepted:true,messageId,mailboxSequence}` without starting an idle Agent, while `task/followup`
returns `{accepted:true,messageId,turnId,task}` after durable Turn admission. `task/seen` and
`task/cancel` return `{accepted:true,task}`; `task/tree/delete` returns
`{accepted:true,deletedTaskCount}`. `task/close` returns exactly `{closed:true}` after the
temporary task lifecycle has been stopped and its resources released.

`thread/list` in explicit `scope:"all"` returns `{items,nextCursor}` with lightweight
`{threadId,title,kind,workspaceId,status}` items. `kind` is `main | side_chat | subagent`;
status is the latest run state or `idle`. A page contains at most 200 items and no transcript or preferences.
Workspace navigation retains its complete Thread metadata shape; mixed discovery/navigation items are invalid.

`thread/read.items` may contain `thread_message` with
`{kind,itemId,turnId,createdAt,sourceThreadId,sourceTitle,content}`. Source identity and title are immutable
snapshots, so messages survive deletion of their temporary sender. Content is the original plain text,
not the separate external-data envelope used in Provider context.

Every Task summary carries lineage, kind/lifecycle, current Turn state, revision, unread and descendant
statistics, safe summary, and timestamps. The only valid pairs are `side_task + independent` and
`subagent + attached`; every Subagent has a non-null `originTurnId`. A `task/list` result is a complete
rooted projection: task identities are unique, every non-root parent is present, and depths are contiguous.
Context seeds expose only the frozen inheritance mode, task brief, safe inherited summary, opaque
fingerprint and creation revision. An `effective_context` seed may additionally expose
`inheritedContextPreview`, containing at most 24 `user | assistant` items. Each item carries nullable text
of at most 512 Unicode code points and at most 10 attachment identities; the complete preview contains at
most 4,096 text code points. A `brief_only` seed always returns a null inherited summary and an empty
preview. System/Tool content, raw hidden reasoning, permission data, Provider secrets and unbounded parent
archives never cross JA-RPC. Mailbox rows expose structured content and the closed
`pending | bound | consumed | cancelled` state set. Activity and mailbox sequences in each page are
strictly increasing, and `nextCursor` is either null or exactly
`task:<activitySequence>:<mailboxSequence>`.

Goal mutation results return the same strict `{goal,eventSequence}` projection as `goal/read`.
`goal` always includes the frozen objective and acceptance criteria plus required nullable link/run/step/input/attention/evaluation/terminal fields. A null `planLink` is the normal standalone Goal state, not missing data.
`goal/observe` adds only its connection-scoped `observationId`; `goal/unobserve` returns `{accepted:true}`.

Plan reads and mutations return the independent strict `{plan,draft,currentRevision,approval,stepExecutions,eventSequence}` projection. `plan/propose` only freezes a revision and cannot introduce an active Run; `plan/execute` atomically records the user approval audit fact and creates a standalone Plan-owned Run for that exact revision.

Interaction reads and mutations return `{threadId,eventSequence,request,draft,resumeState}`. `resumeState` is authoritative and distinguishes `waiting_for_answer` from `waiting_to_resume` after an answer has been committed but before the original Turn is safely resumed; clients must not infer execution from the request status alone.

`plan/revisions/list`, `goal/events/read`, and `goal/evidence/list` return method-specific pages with a required
nullable `nextCursor`. Plan revisions freeze the complete structured definition and canonical SHA-256 hash.
Evidence carries nullable Goal/Plan ownership, nullable Goal definition and Plan revision bindings, its Run, nullable criterion/step, closed source type, source identity,
summary, digest and timestamp; model-authored pasted text is not a valid evidence source. The latest evaluation is
a structured `met | not_met | inconclusive` result with one verdict per acceptance criterion.
