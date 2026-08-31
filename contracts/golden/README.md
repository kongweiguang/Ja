<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# JA RPC v2 golden corpus

`v2/` 是 Java、Rust、TypeScript 共用的唯一合同语料目录，不是产品数据库快照。校验器只
读取 `v2/valid/` 与 `v2/invalid/`，因此目录外的旧 fixture 不会重新成为协议输入。

正向样本覆盖 v2 握手、ready token、workspace/open-general、配置读写/repair、credential
写入/清理、steering/follow-up FIFO 请求结果以及 Turn/Tool 事件边界；
`configuration/read` 的三个 CAS 版本仅位于
`result.cas.{userVersion,projectVersion,credentialVersion}`。
负向样本以中性文件名覆盖未知方法、非法参数、配置 CAS、Secret 出站边界、非法 reverse
request、非法 Tool/权限/审批枚举以及 missing/null/unknown input。它们只证明当前闭集会
fail closed，不构成任何兼容合同。

`tool/batch-committed` 的 `workspaceDirty=true` 只接受 `dirtyReason="shell"`，表示命令可能产生
无法精确归因的旁路写入；`workspace/dirty` 是独立的刷新提示，
不与 tool batch 的 dirtyReason 共用闭集。

运行 `validate.py` 会从磁盘实际 JSON/JSONL 派生正负 frame 数量，并输出包含相对路径和
原始 bytes 的 corpus SHA-256 digest；不使用手工计数。

```powershell
uv run --with 'jsonschema[format]' python contracts/golden/validate.py
```
