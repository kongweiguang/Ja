<!-- @author kongweiguang -->
<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# JA RPC v1 golden corpus

`v1/` 是 Java、Rust、TypeScript 共用的唯一合同语料目录，不是产品数据库快照。校验器只
读取 `v1/valid/` 与 `v1/invalid/`，因此目录外的旧 fixture 不会重新成为协议输入。

正向样本覆盖 v1 握手、ready token、workspace/open-general、配置读写/repair、credential
写入/清理、四个 `turn/input/*` mutation、队列全量投影、消费到 Timeline 的原子事件以及
Turn/Tool 事件边界；`turn/terminal` 语料固定成功和失败必须有最终消息、失败必须有错误对、取消不得携带
最终消息或错误，并且三种终态都必须携带冻结 ChangeSet；活动预览语料覆盖 open/read/close、summary-only
更新事件、UTF-8 字节分页与四个稳定错误；
`configuration/read` 的三个 CAS 版本仅位于
`result.cas.{userVersion,projectVersion,credentialVersion}`。
Goal/Plan 正向语料完整覆盖 17 个方法、3 个事件、结构化 Plan revision、批准 hash、步骤执行、
验收证据与独立 evaluation；负例固定缺少 CAS/幂等键、缺少批准 hash、Markdown draft 和不完整事件
都必须失败关闭。
负向样本以中性文件名覆盖未知方法、已删除的 steer/follow-up、队列 CAS/字段/顺序、配置 CAS、
Secret 出站边界、非法 reverse request、非法 Tool/权限/审批枚举以及 missing/null/unknown input。它们只证明当前闭集会
fail closed，不构成任何兼容合同。

`workspaceDirty/dirtyReason` 只存在于负例；旁路修改由 Java tracker 将 ChangeSet 降级为
`partial`，不会通过 Tool batch 暴露另一套修改归属协议。

运行 `validate.py` 会从磁盘实际 JSON/JSONL 派生正负 frame 数量，并输出包含相对路径和
原始 bytes 的 corpus SHA-256 digest；不使用手工计数。

```powershell
uv run --with 'jsonschema[format]' python contracts/golden/validate.py
```
