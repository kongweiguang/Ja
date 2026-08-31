# @author kongweiguang
# SPDX-License-Identifier: GPL-3.0-or-later

# JA RPC v2 三端合同 Gate

`run.py` 是 schema-first 的唯一入口。它固定让 Java、Rust、TypeScript production parser
读取同一绝对路径 corpus，并在末尾输出相同 digest 与实际 frame count。不存在跳过 production
consumer 的局部门禁。

## 完整 gate

```powershell
pwsh -NoProfile -File tests/contract/run.ps1
python tests/contract/run.py
```

默认 gate 的 consumer 阶段要求 JDK 25、Rust、Node/pnpm 和 Maven/Cargo workspace 均可用。
任一 consumer 失败都表示 parser/DTO 与 schema 或 error catalog 漂移，不能以 Python validator
单独通过替代完整门禁，也不能反向放宽闭集。

Python validator 覆盖 Draft 2020-12、严格 duplicate-key、method-specific result、握手
challenge、public Turn 状态迁移、唯一 terminal、thread revision 单调性、delta contiguous
stream、redaction 与 corpus digest。上下文语料额外冻结 `thread/compact` 的 exact params/result、
Token 实际下降或 unchanged 不变式、三类 Thread lifecycle、trigger/strategy 闭集和稳定错误 tuple。
Golden 文件不含真实凭据；配置入站的 apiKey 只使用 `DUMMY_ONLY_NOT_A_SECRET`。

Agent 过程语料同时冻结 `ToolPresentation` 的严格字段和 Secret/路径边界、四种历史文本阶段、
Tool artifact 字符分页、Turn change-set 的 available/unavailable 判别联合、2 MiB Diff 上限以及
独立的 UTF-8 byte 分页读取。`workspaceDirty/dirtyReason`、raw `arguments/content/value` 和
`assistant_message` 只存在于 invalid corpus。Java/Rust probe 使用各自 production parser，
TypeScript probe 使用 renderer 的 method/event/result parser；任何一端暂未完成 breaking 迁移时，
完整 Gate 必须保持失败。

`python_consumer.py` 另外按命名 `$defs` 消费 Agent 过程专用 transcript，直接验证新方法结果，
并交叉检查 ChangeSet 统计、Diff SHA-256/UTF-8 byteLength、Tool 字符页和 Diff byte 页；这样
invalid response 不会仅因为 orphan correlation 被误算成具体 DTO 已拒绝。
