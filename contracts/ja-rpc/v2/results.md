<!-- @author kongweiguang -->

# JA RPC 2.0 result rules

`workspace/open`, `workspace/read` and `workspace/update` return the complete authoritative
`{workspaceId,root,displayName,trust,kind,revision}` projection. Existing IDs and history ownership
are preserved on edits; new projects use `ws_` plus 32 random lowercase hexadecimal characters.
SESSION identity retains its existing strategy. A name-only edit never requires root availability.
A changed root increments the same row's revision and atomically resets trust to `untrusted`.
Native binding failure after a committed update does not undo the stored root; read metadata to
verify uncertain results before retrying, then reopen the same workspace ID.

`runtime/health` and `runtime/storage/retry` return `status: ready|blocked` and component health.
Optional `storageRecovery` contains exactly `id`, `phase`, `reason`, `historyAvailable`,
`backupAvailable`, and `rebuilt`. Phase is `ready|blocked`; reasons are the closed stable set
documented in the integration contract. `historyAvailable` reports whether the recovery source has
recognized schema history, not whether all its business data is usable. `backupAvailable` reports a
server-managed backup for this recovery only; it does not expose a path or let a caller select a
restore source. `rebuilt` reports whether the current candidate database was installed. The
projection contains no database path, exception text, credentials or SQL. Blocked health keeps the
authenticated transport available but never authorizes storage-dependent requests.

`configuration/read` returns one explicit `cas` object containing exactly `userVersion`,
`projectVersion`, and `credentialVersion`; layer projections do not repeat these versions.
It returns `sources`, mapping each effective field path to `default`, `user`, or `project`, and an
`issues` array of stable, redacted objects. An issue identifies its scope, optional
field or entry, reason, actual impact, and currently permitted recovery actions; it never includes a
file path, TOML source, provider endpoint, header, credential, or parser exception text.
Configuration and credential mutations require `expectedVersion` and return the newly committed
`version`. No result echoes Secret material except the strict `{secret:string|null}` response from
`credential/reveal-provider`, which is limited to the API Key currently bound to an explicitly selected Provider.
`configuration/replace` is a discriminated strict union: `scope:"user"` accepts the complete v2 user
document, while `scope:"project"` requires a trusted `workspaceId` and accepts a sparse v2 project
document. Project Provider routes require all of `api`, `base_url`, and `credential_id`; project `models[]`
overlays are identified by `model_id`, and an explicitly empty array clears that Provider's effective catalog.
For sparse project patches, `providers` is keyed by `provider_id` and each nested `models` object is keyed by
`model_id`; a null model entry removes that project override. A field set to null removes that field and inherits
the global value. For nullable model fields such as `default_reasoning_level`, use the exact
`{"__ja_null":true}` sentinel to store an explicit null; the TOML owner persists it as its reversible null value.
`removed_model_ids` masks matching global models,
and removing an ID from that list restores the inherited model. Effective model field provenance uses paths such as
`providers.provider_demo.models.model_demo.model`.
Actual Secret values stay in the user vault. A failed write leaves the last accepted
projection unchanged; the caller must read back before retrying.

List methods return `{items,nextCursor}`. Successful
`turn/start`, `turn/continue`, `turn/reask`, and `turn/resume` return the same exact admission receipt field set
`{accepted:true,queued:boolean,turnId,threadRevision}`. A new Turn may report `queued:false` when execution starts immediately;
`turn/resume` always reports `queued:true` because it transitions the existing Operation from `suspended` to `queued` and never creates or returns another Operation identity.

When `turn/start.runtimeOverrides.outputSchema` is supplied, Java validates the schema and selected model capability before Provider dispatch, freezes the constraint with the Turn, and validates the terminal answer against it before reporting completion. An invalid schema returns `OUTPUT_SCHEMA_INVALID` and a model without the declared capability returns `OUTPUT_SCHEMA_UNSUPPORTED`, both before Turn admission. A terminal answer that fails the frozen schema ends the Turn as failed with `OUTPUT_SCHEMA_VALIDATION_FAILED`. These are non-retryable validation errors. Model configuration uses optional `capabilities.supports_output_schema`; omission means false.

`thread/fork` returns exactly `{thread,source}`. `source` contains exactly `threadId`, `itemId`, and
`threadRevision`, repeating the requested source identity and revision. The selected durable final answer
is included; later user messages, replies, Tools and checkpoints are excluded. The new Thread is distinct,
active and idle, with a frozen readable history prefix and context seed. Its latestTurnStatus is null until
it owns a real Turn. Historical Turn projections never grant execution, approval, Plan or resource control.
Frozen media references belong to the target Thread and reuse immutable blobs.

`thread/read.items[]` and `thread/read.turns[]` require `origin:"native"|"inherited"`. Inherited rows are
display facts, not real target executions. The same cursor stream covers the prefix and subsequent native
history. Public text still uses lazy `thread/item-content/read`. A fork can select an inherited final answer,
including before the target has sent its first message, without depending on the original Thread.

`operation/read {operationKey,sourceThreadId}` returns unknown or a committed `thread/fork` result. For a
committed fork, outer `threadId` is the source owner, while `result.thread.threadId` is the distinct child;
the child's revision is `result.thread.revision`. Consumers must validate the complete source tuple before
recovering a pending fork and retain the child identity until opening succeeds.

`process/list` returns `{workspaceId,threadId,generation,sessions,nextCursor}`. Session rows retain their
originating workspace, Thread, runtime generation, Turn, Tool call and opaque session ID, plus lifecycle and
output sequence state; a live session from an older generation is invalid. `process/read` returns the same
session projection with `{output,nextSequence}` for output after `afterSequence`, bounded by `maxBytes` and
65,536 bytes. `process/write` returns that incremental snapshot after its idempotent input write. `process/stop`
returns an output-free snapshot (`output:""`, `outputTruncated:false`) whose state is `stopping` or `exited`.
Writes and stops must target the current runtime generation; a session from an earlier generation is read-only
and must already be exited or recovered as interrupted.

`thread/mcp/read` returns exactly
`{threadId,source,notices,catalogRevision?,observedAt?,servers:[{serverId,name,scope,state,toolCount?,reasonCode?}]}`.
Unknown optional values are omitted rather than serialized as null; in particular an unknown tool count is
not reported as zero. The result contains no endpoint, environment, header, credential, or full tool schema.
Server names follow the configured 1..512 text bound (NUL is rejected; CR/LF are preserved as JSON text),
known tool counts are integers from 0 through 10,000, and reason codes use the bounded uppercase identifier form.
`thread/mcp/read` projects the Thread's current observation without connecting to a service. Its `scope`
identifies global or project ownership. A running Turn retains its frozen catalog; a changed configuration
adds `configuration_changed` until the next request. Project trust and configuration errors use short notice codes.
Connection testing remains under `mcp/test` in Settings and accepts an optional `workspaceId`.

`thread/read.turns[].sourceMessageId` is required and nullable. It identifies the original USER item only for a hidden `turn/continue` Turn; ordinary and replacement USER Turns carry null. `thread/read` selects the current path while preserving displaced Turn, Tool, attachment, and Usage records for audit. Context Usage comes from the current path; `thread/usage/read` still accounts for every actual request.

`turn/recovery/respond` returns exactly `{accepted:true,turnId,threadRevision,decision,resumed}`. `resumed:false` means the selected resolution committed but an earlier or later unknown Tool still prevents ordinary continuation; it is not a failed submission. This ACK never represents a Tool success, a file verification, or a terminal Turn state.

`model/discover` returns exactly `{items:string[],truncated:boolean}`. It reads one upstream `/v1/models`
page using the selected saved Provider configuration, caps `items` at 200, and returns no endpoint, Header,
credential, model capability, or vendor metadata. The response is only a draft input: it never persists a model.

Every Thread result contains required `pinned:boolean`, required nullable `latestTurnStatus`, and required
`latestTurnSeen:boolean` in addition to identity, preferences, lifecycle status, revision, and timestamps.
`latestTurnStatus` is selected by the largest persisted `turn_sequence`; null means the Thread has never owned
a Turn and therefore requires `latestTurnSeen=true`. `latestTurnSeen` compares that same sequence with the
durable seen boundary and never suppresses a non-terminal execution status. `thread/pin`, `thread/seen`,
`thread/archive`, and `thread/restore` return this complete Thread shape. `thread/seen` returns
`latestTurnSeen=true` for the latest Turn visible at that CAS commit. Archive results have
`status="archived"` and `pinned=false`; restore results have `status="active"` and `pinned=false`.

`skill/list` projects only the effective precedence winner for each Skill name. `skillId` is always
`user:名称`, `ja:名称`, or `project:名称`, and `scope` is exactly `user`, `ja`, or `project`.
Unconfigured discoveries are returned disabled until explicitly enabled. Physical roots, `SKILL.md` bodies,
and package revisions do not cross JA-RPC.

`attachment/import` and `attachment/discard` return exactly
`{attachmentId,workspaceId,displayName,sizeBytes,mediaKind,mediaType,state,createdAt,expiresAt,boundMessageId,boundThreadId}`.
For `state="bound"`, exactly one owner is present: message-bound attachments set `boundMessageId`, while
independently fork-bound attachments set `boundThreadId`. Other states set both fields to null. The result never includes `sha256`, ingress token,
staging identity, or a physical path. History does not create a standalone attachment item;
`thread/read.items[].kind="user_input"` retains the attachment block in `content` and carries a same-order
`attachments` summary array for display and preview authorization.

`thread/read.inputQueue` is required and nullable. It is null when the Thread has no active Turn queue;
otherwise `{turnId,revision,accepting,items}` is the complete durable projection, and `items` order is the
only authoritative consumption order. Each `QueuedInput` is exactly
`{inputId,turnId,content,attachments,kind,status,issue,inputRevision,createdAt}`. `attachments` uses the same
strict summary shape and order as USER Message items. `status` is `pending` with a null
`issue`, or `needs_attention` with `{errorCode,message,retryable}`; `kind` remains `follow_up` or `steering`.

Hydratable public text item kinds are `user_input`, `thread_message`, `assistant_progress`,
`reasoning_summary`, and `final_answer`. Each keeps its existing `content` or `text` preview shape,
limited to 512 Unicode code points, and adds required `totalCharacters` (Unicode code points) and derived `truncated`; `truncated=false`
means the preview is complete, while `true` requires `thread/item-content/read` before full display or
copy. Tool and approval items carry no hydration metadata and remain outside that read method.
`thread/read` USER items retain their structured `content[]`, including the same-order attachment
summary. Assistant progress, reasoning summaries, and final answers retain bounded `text`; progress
and reasoning require `modelRound`.
Tool history uses one canonical `tool_call` item per Provider-native opaque `callId`; the identifier is preserved byte-for-byte through history, approval, Tool recovery, and artifact reads. Its `presentation` is updated to the latest
durable status and the removed raw `value` is forbidden. Pending approval items include only the safe
call/tool association, public reason, expiry and decision needed to restore an actionable card. Every Turn contains
required nullable `changeSet`; a non-null value is the persisted Turn summary. Frozen Diff content is read only through `turn/change-set/read` after the Turn has settled.

Tool presentation `media` contains at most 10 unique managed attachment identities, each describing one PNG,
JPEG, GIF or WebP image with a size from 1 byte through 50 MiB. The wire carries only attachment identity and
safe display metadata; image bytes remain in the managed attachment store and are resolved through the
separately authorized attachment preview flow.

`thread/read.contextUsage` is a required nullable field containing the latest durable ordinary Assistant request-level Usage and its
required `turnId`; summary requests remain in the cumulative ledger but do not replace the context window baseline. Event-scoped Usage obtains that identity from the enclosing event instead of duplicating it.
Every Usage carries `modelRequestId`, monotonic `modelRequestOrdinal`, `modelRound`, `purpose`, `certainty`, the complete
`profile`, all three Token fields, and `measuredAt`. `certainty="unknown"` requires null Token fields. KNOWN requires integer
Tokens with `totalTokens >= inputTokens + outputTokens`; UNKNOWN never means zero. A snapshot may include nullable `contextEstimate`; when present it is a Java-local full-envelope estimate from a pending dispatch or a newer committed checkpoint, with its own measurement time and the same context window as `profile`. It excludes the send-admission safety margin and is never cumulative Provider Usage. Snapshot Turns have no `runtime`.
Clients calculate context capacity only from `usage.profile.contextWindowTokens` and never current preferences.

`thread/usage/read` returns the exact persisted aggregate
`{threadId,snapshotRevision,requestCount,measuredRequestCount,newInputRequestCount,newInputTokens,outputRequestCount,outputTokens,totalRequestCount,totalTokens,cacheReadRequestCount,cacheReadTokens,cacheWriteRequestCount,cacheWriteTokens,cacheCompleteRequestCount,cacheCompleteInputTokens,cacheCompleteReadTokens}`.
Every token total has a companion request-coverage field. Missing Provider accounting lowers coverage rather than
creating a zero-valued measurement; this diagnostic result never contains prompts, responses, credentials, prices,
or Provider configuration.

`thread/read.turns[].status` is one of `queued`, `running`, `waiting_approval`, `suspended`,
`completed`, `failed`, or `cancelled`. `suspended` is non-terminal and has null `completedAt`; it is
authoritative reload state, not an event-stream guess. Cancelling a suspended Turn commits and returns
`status:"cancelled"`; it never leaves a successfully cancelled Operation suspended.

`thread/item-content/read` returns
`{itemId,offsetCharacters,nextOffsetCharacters,totalCharacters,truncated,content}`.
Offsets and total count Unicode code points; `nextOffsetCharacters` is null exactly on the final page.
The result contains only public text from a USER or Assistant item on the current Thread path, a
public reasoning summary, or a mailbox `thread_message` currently visible in the Thread projection.
Mailbox content remains owned by its mailbox; the Thread projection exposes only a bounded preview of
messages visible in that Thread. Hydration returns requested pages without creating another persisted
full-message copy. Tool content, private reasoning, and stale-path items cannot be read. Missing,
wrong-owner, and unauthorized
items share `THREAD_NOT_FOUND` so callers cannot probe old branches or other users' Threads. Thread
item and terminal answer text are safe previews; callers must page this method before treating a long
item as complete.

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
`{accepted:true,deletedTaskCount}`. `task/close` returns exactly
`{closedThreadIds:["thr_side_root","thr_side_child"]}` after the temporary task lifecycle has been
stopped and purged. The non-empty array is the complete set of valid, unique Thread IDs physically
deleted by this purge transaction: the requested root and every still-live descendant in its
`parentThreadId` lineage, regardless of Thread kind or lifecycle. Descendants already purged by an
earlier close are absent. Element order has no tree meaning. The server stores the successful receipt
with the purge, so retrying the same close after a lost response or restart returns the same set.
After commit, `task/closed` publishes that same set with `rootThreadId` set to the surviving top-level
Task tree root and `taskThreadId` set to the purged side-chat root. The notification omits the deleted
Task revision, is routed by the surviving root, and may repeat when a close is retried; clients remove
the listed IDs idempotently. Failed purge attempts do not publish `task/closed`.

`thread/list` in explicit `scope:"all"` returns `{items,nextCursor}` with lightweight
`{threadId,title,kind,workspaceId,status}` items. `kind` is `main | side_chat | subagent`;
status is the latest run state or `idle`. A page contains at most 200 items and no transcript or preferences.
Workspace navigation retains its complete Thread metadata shape; mixed discovery/navigation items are invalid.

`thread/read.items` may contain `thread_message` with
`{kind,itemId,turnId,createdAt,sourceThreadId,sourceTitle,content}`. Source identity and title are immutable
snapshots, so messages survive deletion of their temporary sender. Content is the original plain text,
not the separate external-data envelope used in Provider context.

Every Task summary carries lineage, kind/lifecycle, current Turn state, revision, unread and descendant
statistics, safe summary, and timestamps.
`latestSafeSummary` and terminal Activity summaries are previews limited to 4,096 Unicode code points,
without splitting surrogate pairs. Complete Assistant messages and Subagent `FINAL_ANSWER` deliveries
retain the original text; use message-content pagination when reading a complete long reply.
The only valid pairs are `side_task + independent` and `subagent + attached`; every Subagent has a non-null `originTurnId`. A `task/list` result is a complete
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

Plan reads and mutations return the strict `{plan,draft,currentRevision,approval,stepExecutions,eventSequence}` projection. A Plan belongs to its Thread. `plan/propose` only freezes a revision and cannot introduce an active Run; `plan/execute` atomically records the user approval audit fact and creates a Plan-owned Run for that exact revision.

Interaction reads and mutations return `{threadId,eventSequence,request,draft,resumeState}`. `resumeState` is authoritative and distinguishes `waiting_for_answer` from `waiting_to_resume` after an answer has been committed but before the original Turn is safely resumed; clients must not infer execution from the request status alone.

`plan/revisions/list`, `plan/events/read`, and `plan/evidence/list` return method-specific pages with a required
nullable `nextCursor`. Plan revisions freeze the complete structured definition and canonical SHA-256 hash.
Evidence binds its Plan, Run and Plan revision, with nullable criterion/step identities, a closed source type,
source identity, summary, digest and timestamp; model-authored pasted text is not a valid evidence source.
