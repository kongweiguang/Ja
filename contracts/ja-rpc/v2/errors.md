<!-- @author kongweiguang -->

# JA RPC v2 stable errors

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

Context compaction adds three stable catalog entries: `THREAD_BUSY` (`-32030`, conflict, retryable), `TOKEN_COUNT_UNAVAILABLE` (`-32048`, unavailable, retryable), and `SUMMARY_FAILURE` (`-32049`, unavailable, retryable). The `thread/compact` public failure closure also reuses `THREAD_NOT_FOUND`, `CONFLICT`, `CONTEXT_LIMIT`, `CANCELLED`, and `INVALID_STATE`; lifecycle failure notifications expose only one of these stable `errorCode` values and never include Provider diagnostics. Session shutdown cancels an active manual compaction before runtime resources are released.

Attachment operations add `ATTACHMENT_NOT_FOUND` (`-32061`, not found, non-retryable),
`ATTACHMENT_LIMIT_EXCEEDED` (`-32062`, capacity, non-retryable), `ATTACHMENT_CONFLICT`
(`-32063`, conflict, non-retryable), and `ATTACHMENT_UNAVAILABLE` (`-32064`, unavailable,
retryable). These errors never distinguish source paths, staging paths, hashes, SQLite rows, or the
specific integrity check that failed.
