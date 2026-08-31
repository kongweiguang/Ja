<!-- @author kongweiguang -->

# JA RPC v2 notifications

Runtime, Turn, Tool, approval, context, workspace, and configuration notifications are transactional and redacted. `assistant/model-step-committed` publishes durable progress text, optional public reasoning summary, model round, usage, and ordered Tool calls. Each Tool call contains only a strict `ToolPresentation`; raw arguments and byte-count placeholders are invalid.

`tool/batch-committed` carries ordered Java-executed built-in or MCP Tool results. Each result contains its outcome and completed `ToolPresentation`; raw `content`, `workspaceDirty`, `dirtyReason`, and ad-hoc file-change fields are invalid. Turn file changes have exactly one owner: the separately committed `TurnChangeSet`. There is no `host-tool/cancel` notification or `h:` namespace.

`ToolPresentation` exposes a closed Tool kind/status, bounded title and preview fields, safe relative paths, optional Shell command/cwd/stdout/stderr/exit/duration, truncation state, and an opaque artifact identity. Status is exactly `pending`, `running`, `waiting_approval`, `success`, `error`, or `cancelled`; approval waiting is a real state rather than inferred from a disabled control, while any unrecognized status fails closed and forces authoritative resynchronization. Its previews are capped at 32 Ki characters and its relative path list at 64 items. Secret-shaped keys, absolute paths, unknown fields, and unredacted result bodies fail closed.

Context compaction is a Thread-level lifecycle with exactly three events:

- `context/compaction-started`
- `context/compacted`
- `context/compaction-failed`

All three carry the normal notification metadata plus `workspaceId`, `threadId`, nullable `turnId`, `threadRevision`, `compactionId`, `trigger`, `sourceRevision`, `inputTokensBefore`, `inputTokensAfter`, and `strategyVersion="ja-context-v3"`. `trigger` is the closed set `automatic`, `manual`, and `overflow_recovery`.

The started event requires a measured `inputTokensBefore` and JSON null `inputTokensAfter`. The compacted event requires both counts, a `checkpointId`, and an after count lower than before. The failed event has no `checkpointId`, requires JSON null `inputTokensAfter`, allows `inputTokensBefore` to be null when counting never completed, and exposes only a stable `errorCode`. Manual compaction uses `turnId:null`; automatic and overflow recovery attach the owning Turn.

`thread/metadata-changed` is the single committed title invalidation event. Its `titleSource` is the closed set `placeholder`, `auto`, and `manual`: first-turn admission publishes `placeholder` before Agent execution, while a later automatic summary or explicit rename publishes the corresponding final ownership. Consumers apply only a newer revision in the current runtime generation and otherwise reload the authoritative Thread projection.

`configuration/changed` remains an invalidation hint. It contains no cwd, path, Provider/Model body, credential, or Secret; clients reissue `configuration/read` for the committed projection.
