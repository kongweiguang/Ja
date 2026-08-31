// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime 人工恢复的纯领域值。

/// 恢复原因采用封闭枚举，防止恢复命令退化为可自由传入路径或进程信息的确认通道。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ManualRecoveryReason {
    SystemRestarted,
    ExternallyCleaned,
}

/// 这是唯一能清除恢复标记的用户意图；identity 与 revision 必须回显当前投影以拒绝陈旧操作。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManualRecoveryConfirmation {
    pub recovery_id: String,
    pub revision: u64,
    pub reason: ManualRecoveryReason,
}

/// 描述人工恢复门禁的最小领域事实，不携带 marker 路径、进程信息或原始存储内容。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeRecoveryState {
    pub required: bool,
    pub acknowledgeable: bool,
    pub recovery_id: Option<String>,
    pub revision: Option<u64>,
}
