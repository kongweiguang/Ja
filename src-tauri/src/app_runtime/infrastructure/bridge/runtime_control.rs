// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Runtime bridge 的内部原生控制端口。
//
// 生产端使用无操作实现；Harness 在 `app_runtime::test_support` 中实现同步栅栏和
// 故障注入。actor/lifecycle 只依赖这个端口，因此测试 feature 不会散落到生产状态机。

use super::{EXIT_DEADLINE, LaunchConfig, RuntimeCommandError, SidecarSupervisor};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// 标识 Harness 可观察的关键线性化点；数值稳定仅用于测试失败诊断，不进入 IPC。
#[derive(Clone, Copy)]
pub(crate) enum RuntimeControlPhase {
    ActorEnter = 1,
    StartReceived = 2,
    StartFailed = 3,
    StopReceived = 4,
    StopReplied = 5,
    ShutdownReceived = 6,
    ShutdownConfirmed = 7,
    ActorCompleted = 8,
}

/// 将所有测试同步和 supervisor 构造变化收敛为一个私有端口。
///
/// 端口不能替换 RPC 方法、响应或领域状态，只能控制 deadline、进程 cleanup 故障与
/// actor 观察点；这样生产状态机和测试执行的是同一条逻辑路径。
pub(crate) trait RuntimeControlPort: Send + Sync {
    /// 返回 bridge 退出预算；生产固定 20 秒，Harness 只能缩短测试等待。
    fn exit_timeout(&self) -> Duration;

    /// 构造真实 supervisor；Harness 可在实现内部注入实例级 cleanup 故障。
    fn create_supervisor(
        &self,
        config: &LaunchConfig,
        host_generation: u64,
    ) -> Result<SidecarSupervisor, RuntimeCommandError> {
        SidecarSupervisor::new_with_host_generation(config.sidecar.clone(), host_generation)
            .map_err(|error| RuntimeCommandError::from_process(&error))
    }

    /// 进程树清理必须经过同一个内部端口，测试才能注入一次性失败并验证 quarantine；
    /// 生产实现仍直接调用 `ja-runtime` 的唯一 supervisor 清理路径，不暴露测试构造器。
    fn shutdown_supervisor_until(
        &self,
        supervisor: &mut SidecarSupervisor,
        deadline: Instant,
    ) -> Result<(), RuntimeCommandError> {
        supervisor
            .shutdown_until(deadline)
            .map_err(|error| RuntimeCommandError::from_process(&error))
    }

    /// actor 进入消费循环前提供确定同步点；生产实现不阻塞。
    fn before_actor_loop(&self) {}

    /// command 成功进入有界数据队列后记录准入；生产实现不保存计数。
    fn command_admitted(&self) {}

    /// State command 完成快照后记录处理事实；生产实现不保存计数。
    fn state_command_processed(&self) {}

    /// start 已失败但 reply 尚未发布时提供确定栅栏；生产实现不阻塞。
    fn before_start_failure_reply(&self) {}

    /// 记录关键 phase；生产实现不保存测试状态。
    fn set_phase(&self, _phase: RuntimeControlPhase) {}

    /// 记录脱敏的内部标记；生产实现不分配 trace 缓冲区。
    fn record(&self, _event: &str) {}
}

/// 生产控制端口没有可变状态，确保普通 bridge 不携带测试 gate 或 trace。
struct ProductionRuntimeControl;

impl RuntimeControlPort for ProductionRuntimeControl {
    /// 生产退出预算只从 bridge 权威常量派生，避免 composition 层复制数值。
    fn exit_timeout(&self) -> Duration {
        default_exit_timeout()
    }
}

/// 为每个生产 Host 返回独立的无操作端口对象；Arc 仅用于跨 actor 线程共享所有权。
pub(crate) fn production_runtime_control() -> Arc<dyn RuntimeControlPort> {
    Arc::new(ProductionRuntimeControl)
}

/// 返回生产退出预算，供 Harness 复用同一权威默认值，避免测试夹具复制常量后发生漂移。
pub(crate) const fn default_exit_timeout() -> Duration {
    EXIT_DEADLINE
}
