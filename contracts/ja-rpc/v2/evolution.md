<!-- @author kongweiguang -->

# JA RPC v2 evolution

v2 is a private breaking major and does not dispatch v1. Old access modes, approval decisions, Tool names, Host Tool/Sandbox methods, session grants, Turn-only context event shapes, context-compaction aliases, raw Tool arguments/results, `assistant_message`, `workspaceDirty`, and `dirtyReason` remain invalid.

The current history migration is one-way: assistant progress, public reasoning summary, and final answer are separate durable kinds; Tool history keeps only `ToolPresentation`; Turn file facts keep only the nullable strict `TurnChangeSet`. No alias, dual read, or legacy fallback may be added. Any addition requires synchronized schema, valid/invalid golden, and Java/Rust/TypeScript production consumer fixtures.
