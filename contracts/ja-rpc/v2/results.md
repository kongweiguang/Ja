<!-- @author kongweiguang -->

# JA RPC v2 result rules

`configuration/read` returns one explicit `cas` object containing exactly `userVersion`,
`projectVersion`, and `credentialVersion`; layer projections do not repeat these versions.
Configuration and credential mutations require `expectedVersion` and return the newly committed
`version`. No result echoes Secret material. List methods return `{items,nextCursor}`. A successful
`turn/start` returns its admission receipt.

`attachment/import` and `attachment/discard` return exactly
`{attachmentId,workspaceId,displayName,sizeBytes,mediaKind,mediaType,state,createdAt,expiresAt,boundTurnId}`.
`boundTurnId` is non-null only for `state="bound"`. The result never includes `sha256`, ingress token,
staging identity, or a physical path. `thread/read.items[]` may contain `kind="attachment"` with the
public display metadata and state needed to render history, but likewise omits content identity and paths.

`thread/read` text items use only `user_input`, `assistant_progress`, `reasoning_summary`, and
`final_answer`. Progress and reasoning items require `modelRound`; user and final items forbid it.
Tool history uses one canonical `tool_call` item per `callId`; its `presentation` is updated to the latest
durable status and the removed raw `value` is forbidden. Pending approval items include only the safe
call/tool association, public reason, expiry and decision needed to restore an actionable card. Every Turn contains
required nullable `changeSet`; a non-null value is the same strict available/unavailable projection returned
by `turn/change-set/commit`.

`thread/read.contextUsage` is a required nullable field. A non-null value is the latest durable Provider Usage
`{turnId,modelRound,inputTokens,outputTokens,totalTokens,measuredAt}` and its `turnId` must identify a Turn in the
same snapshot. It is exact metering, not a character estimate; `null` means the server has no truthful measurement.
The referenced Turn's frozen `runtime` owns the Provider and model identity used by clients to reject stale usage
after a model switch.

`tool/artifact/read` returns character pagination
`{artifactId,offsetCharacters,nextOffsetCharacters,totalCharacters,truncated,content}` and is authorized by
the exact Thread/Turn/Call/artifact tuple. `turn/change-set/read` is a separate UTF-8 byte contract returning
`{artifactId,offsetBytes,nextOffsetBytes,byteLength,truncated,content}`; the encoded content byte count equals
the returned byte span, and a null next offset means the page reaches the artifact end.

`turn/change-set/commit` returns exactly `{accepted:true,changeSet}`. Available results may expose only the
opaque persisted `artifactId`, never the inline Diff or its hash. Unavailable results require a stable reason,
an empty file list, zero statistics, and no artifact identity.

`turn/steer` and `turn/follow-up` return exactly `{accepted:true,inputId,turnId,kind,status:"queued"}`. A denied Tool is represented as a normal Tool result so the model can continue the Turn. No result contains Host Tool CAS fields or a session grant.

`thread/compact` returns exactly
`{outcome,compactionId,checkpointId,threadRevision,inputTokensBefore,inputTokensAfter}` and always emits both nullable identity fields. `outcome="compacted"` requires non-null `compactionId` and `checkpointId`, a committed `threadRevision`, and `inputTokensAfter < inputTokensBefore`. `outcome="unchanged"` requires both identities to be null and equal before/after counts; it means no new durable facts needed another Checkpoint.
