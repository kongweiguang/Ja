<!-- @author kongweiguang -->

# JA RPC v2 method closure

| Method | Parameters | Ownership rule |
| --- | --- | --- |
| `runtime/initialize` | protocol/version, capabilities, limits | capabilities contain no Host Tool bridge |
| `thread/create` | optional `cwd`, title, `providerId`, `modelId`, nullable `reasoningLevel`, `accessMode` | freezes the relative-path base and explicit Thread preferences |
| `thread/list` | required `workspaceId`, optional cursor and limit | SQL keyset page is confined to one Java-owned Workspace |
| `thread/compact` | exact `{threadId, expectedThreadRevision}` | only an idle Thread may be compacted; Java resolves its bound Provider/Model and owns all Token counting, Summary, MCP lease, Checkpoint, and CAS work |
| `attachment/import` | exact `{ingressToken,workspaceId,displayName,sizeBytes,sha256}` | Rust private staging is revalidated and copied into Java-owned managed storage; paths are forbidden |
| `attachment/discard` | exact `{attachmentId}` | only an unbound draft can move to the discarded terminal state |
| `turn/start` | `threadId`, non-empty `content[]`, optional deadline | `content` is a strict text/attachment discriminated union; Java atomically binds at most ten unique attachment IDs and freezes the runtime |
| `turn/cancel` | turn id and expected Thread revision | cancels the Turn and all pending inputs |
| `turn/steer` | `turnId`, `text` | durable FIFO; consumed before the next model call after a Tool boundary |
| `turn/follow-up` | `turnId`, `text` | durable FIFO; consumed when the Turn would otherwise finish |
| `tool/artifact/read` | `threadId`, `turnId`, `callId`, `artifactId`, character offset/limit | reads only the redacted Tool artifact owned by that exact identity tuple; the page limit is 65,536 characters |
| `turn/change-set/commit` | Turn/Workspace identity, availability state, strict files/stats, optional inline Diff artifact | ordinary Rust-to-Java request; commits one captured Turn fact and never acts as a reverse request |
| `turn/change-set/read` | `threadId`, `turnId`, `artifactId`, byte offset/limit | reads the persisted Turn Diff by UTF-8 byte range; offsets must be valid character boundaries and limits are at most 65,536 bytes |
| `approval/respond` | approval id, turn id, `approve` or `deny`, revision | resolves one Tool request only |
| `model/test` | exact saved `providerId` and `modelId` | sends one bounded request without history, Tools, or attachments; returns only redacted response model and latency |

Configuration, credential, workspace, history, catalog, health, and shutdown methods remain those listed by the schema. Unknown methods and fields fail closed.

`thread/compact` never sends a normal assistant generation. A stale revision returns `CONFLICT`, a non-terminal Turn returns `THREAD_BUSY`, and a missing Thread returns `THREAD_NOT_FOUND`. Official Provider Token counting or Summary failure is reported through the stable context error subset; no Provider response body crosses JA-RPC.

`turn/start` does not accept the removed top-level `input` or `text` fields. Text items are `{type:"text",text}` and attachment items are `{type:"attachment",attachmentId}`. Attachment-only turns are valid; absolute paths, ingress tokens, duplicate attachment IDs, and unknown item fields fail closed.

`turn/change-set/commit` accepts exactly one of `state="available"` or `state="unavailable"`. Available commits omit `reason` and may include a SHA-256-bound UTF-8 unified Diff up to 2 MiB. Unavailable commits require one of `concurrent_turn`, `not_git`, `capture_failed`, or `diff_too_large`, contain no files or artifact, and expose zero stats only under the unavailable discriminator. File paths are normalized Workspace-relative text; absolute, parent-escaping, control-character, backslash, and drive-prefixed forms are invalid.
