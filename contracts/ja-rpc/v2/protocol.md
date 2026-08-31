<!-- @author kongweiguang -->

# JA RPC v2 transport and ownership

`ja-rpc/v2` is the JSON-RPC 2.0 JSONL contract between Tauri and the Java app-server. All requests use `c:` ids and flow from Tauri to Java; Java never sends a reverse Tool request. Rust submits `turn/change-set/commit` through this same ordinary request direction after native capture. Java owns configuration, credentials, workspace identity, Thread/Turn admission, Tools, MCP sessions, approval state, persisted Tool presentations, change sets, artifacts, and SQLite facts.

Initialize capabilities contain exactly `methods`, `events`, and `accessModes`. The Java-owned root configuration field `default_access_mode` has only `full_access` and `approval_required`; `thread/create` freezes the selected Provider, Model, reasoning effort, and access mode into durable Thread preferences, while `turn/start` accepts only the Thread identity, strict `content[]`, and optional deadline. Approval decisions are only `approve` and `deny`. `turn/start` freezes the Java-owned runtime generation and atomically binds referenced managed attachments. `turn/steer` and `turn/follow-up` accept `{turnId,text}` and return a durable queued-input receipt.

`thread/compact` is the only client-triggered context compaction method. It is guarded by Thread revision CAS and the idle-state invariant, and it does not authorize a normal assistant generation. Automatic, manual, and overflow-recovery compaction share the same Thread-level lifecycle schema and `ja-context-v3` strategy identity.

`cwd` is fixed when `thread/create` succeeds. It remains the relative-path base and default Shell cwd, not a filesystem containment boundary. The four built-in Tools execute inside Java, while additional Tools come only from the Turn-frozen MCP catalog.

Rust owns native file selection and private staging; Java owns attachment blobs, metadata, lifecycle, quota, and Turn association. The WebView and ordinary JA-RPC history never receive a source path, staging path, ingress token, or content hash.

Raw Tool arguments/results and raw reasoning never cross the public wire. Java projects bounded, redacted `ToolPresentation` before persistence or notification. Tool artifacts use character pagination and retain Call ownership; persisted Turn Diffs use a separate UTF-8 byte reader and retain Turn ownership. Neither reader accepts a physical path or a caller-selected identity alias.

There is no `runtime/configure`, Host Tool RPC, Sandbox RPC, old Tool alias, session grant, old `turn/start.input`, raw Tool `arguments/content/value`, `workspaceDirty/dirtyReason`, or compatibility enum. Secret material is legal only in `credential/set.secret` and is never echoed.
