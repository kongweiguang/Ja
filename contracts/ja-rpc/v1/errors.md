<!-- @author kongweiguang -->

# JA RPC v1 stable errors

The Java error catalog is the single numeric/`errorCode`/`category`/retryable source. `category` is
the closed set `protocol`, `validation`, `conflict`, `not_found`, `permission`, `capacity`,
`unavailable`, `timeout`, `cancelled`, and `internal`; every catalog entry declares one explicitly.
Missing Provider, missing Model, and missing credential are distinct non-retryable Turn admission
errors. Corrupt configuration is reported as
`CONFIG_CORRUPTED`; a data/run database ambiguity is `STORAGE_CONFLICT`. Error messages never carry
paths, SQL, stack traces, configuration values, or Secret material.

Every public error contains a fresh opaque `data.errorId` matching `err_[0-9a-f]{32}`. Optional
`retryAfterMs` is present only when a retryable failure has an explicit positive delay; it is never
derived from `retryable`, emitted as null, or emitted as zero.

Tool failures use bounded, redacted Tool results. Protocol errors never expose absolute paths,
command bodies, patch bodies, configuration values, or Secret material.

Context compaction adds two stable catalog entries: `THREAD_BUSY` (`-32030`, conflict, retryable) and `SUMMARY_FAILURE` (`-32049`, unavailable, retryable). The `thread/compact` public failure closure also reuses `THREAD_NOT_FOUND`, `CONFLICT`, `CONTEXT_LIMIT`, `CANCELLED`, and `INVALID_STATE`; lifecycle failure notifications expose only one of these stable `errorCode` values and never include Provider diagnostics. Local token budgeting is a pure preflight calculation and therefore has no remote token-count availability error. Session shutdown cancels an active manual compaction before runtime resources are released.

Attachment operations add `ATTACHMENT_NOT_FOUND` (`-32061`, not found, non-retryable),
`ATTACHMENT_LIMIT_EXCEEDED` (`-32062`, capacity, non-retryable), `ATTACHMENT_CONFLICT`
(`-32063`, conflict, non-retryable), and `ATTACHMENT_UNAVAILABLE` (`-32064`, unavailable,
retryable). These errors never distinguish source paths, staging paths, hashes, SQLite rows, or the
specific integrity check that failed.

Turn resume adds two distinct failures: `TURN_NOT_RESUMABLE` (`-32065`, conflict,
non-retryable) when the target has no valid suspended execution state;
`TURN_RESUME_ORDER_CONFLICT` (`-32066`, conflict, retryable) when an earlier non-terminal Turn must
be resolved first. Code `-32067` remains intentionally unassigned and later codes are not
renumbered; resume re-enters the normal next-request safe point instead of restoring a Turn-wide runtime.

Turn input mutations add `TURN_INPUT_QUEUE_FULL` (`-32068`, capacity, retryable) when the active
Turn already has eight pending inputs or their combined UTF-8 text would exceed 524,288 bytes, and
`QUEUED_INPUT_NOT_FOUND` (`-32069`, not found, non-retryable) when the named pending input is no
longer available. A stale `expectedInputRevision` reuses `CONFLICT`; clients must reload
`thread/read.inputQueue` instead of retrying against a guessed revision.

Structured references add `WORKSPACE_REFERENCE_INVALID` (`-32070`, validation, non-retryable),
`SKILL_LOAD_FAILED` (`-32071`, unavailable, retryable), and `CONTENT_TOO_LARGE` (`-32072`, capacity,
non-retryable). A disabled or missing referenced Skill reuses `SKILL_UNAVAILABLE`; queued failures are also
projected through the smaller `QueuedInput.issue` shape without exposing `errorId`. That issue code is a closed
subset and additionally includes `ATTACHMENT_UNAVAILABLE` when a reserved attachment cannot be consumed.

Task Threads add `TASK_NOT_FOUND` (`-32073`), `TASK_RELATION_INVALID` (`-32074`),
`TASK_CONTEXT_REVISION_CONFLICT` (`-32075`), `TASK_PERMISSION_DENIED` (`-32076`),
`TASK_DEPTH_LIMIT` (`-32077`), `TASK_TREE_LIMIT` (`-32078`), `TASK_MAILBOX_FULL` (`-32079`),
`TASK_TREE_DELETE_REQUIRED` (`-32083`), `TASK_OBSERVATION_INVALID` (`-32084`), and
`WORKSPACE_WRITE_LEASE_TIMEOUT` (`-32085`). These codes are stable state/authority outcomes; no SQL,
prompt, absolute path, secret, raw reasoning, or approval token is returned in their messages.

Goal/Plan adds `GOAL_NOT_FOUND` (`-32086`), `GOAL_REVISION_CONFLICT` (`-32087`),
`GOAL_INVALID_STATE` (`-32088`), `PLAN_INVALID` (`-32089`), `PLAN_APPROVAL_STALE` (`-32090`),
`GOAL_EVIDENCE_INCOMPLETE` (`-32091`), `GOAL_RECOVERY_REQUIRED` (`-32092`), and
`GOAL_INPUT_EXPIRED` (`-32093`). Only revision conflict is retryable, and only after reloading the authoritative
Goal projection; stale approval, incomplete evidence, unknown side effects and expired input require a new user or
server fact rather than blind replay.
