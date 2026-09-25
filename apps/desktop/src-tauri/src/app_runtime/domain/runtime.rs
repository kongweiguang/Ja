// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime 生命周期的纯领域投影。

/// Runtime 状态只投影 generation 与公开实例 identity；握手 challenge 和密钥永远不进入领域对象。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeStatus {
    pub status: RuntimeStatusKind,
    pub generation: u64,
    pub server_instance_id: Option<String>,
}

/// Host 状态包含 Busy，而 JA-RPC wire status 仍遵循更小的生命周期枚举。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeStatusKind {
    Starting,
    Ready,
    Busy,
    Stopping,
    Stopped,
    RecoveryRequired,
    Crashed,
    Incompatible,
    Faulted,
}

impl RuntimeStatusKind {
    /// 将 Host 领域状态收敛为冻结的 JA-RPC 状态名。
    /// Busy 仍属于 Ready generation，故不能扩展 wire 枚举或让前端误判为新的生命周期阶段。
    pub(crate) fn protocol_name(self) -> &'static str {
        match self {
            Self::Starting => "starting",
            Self::Ready | Self::Busy => "ready",
            Self::Stopping => "shutting_down",
            Self::Stopped => "stopped",
            Self::RecoveryRequired => "recovery_required",
            Self::Crashed | Self::Incompatible | Self::Faulted => "crashed",
        }
    }
}
