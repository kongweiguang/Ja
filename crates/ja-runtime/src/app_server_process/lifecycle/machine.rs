// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar 生命周期状态机及 generation 不变量。
//!
//! 进程 reader、writer 和 monitor 都只能报告事实；只有这个模块负责状态转换，
//! 从而让旧 generation 的迟到事件不能把新实例误切回 Exited。

use crate::app_server_process::error::AppServerProcessError;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LifecycleState {
    Starting,
    Ready,
    Busy,
    Stopping,
    Exited,
    Backoff,
    Incompatible,
    Faulted,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct RestartPolicy {
    pub(crate) max_attempts: u32,
    pub(crate) base_delay: Duration,
    pub(crate) max_delay: Duration,
}

impl Default for RestartPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 3,
            base_delay: Duration::from_millis(100),
            max_delay: Duration::from_secs(5),
        }
    }
}

impl RestartPolicy {
    /// 在构造状态机前拒绝零重试或不可表示的退避窗口，避免恢复逻辑进入死循环。
    pub(crate) fn validate(self) -> Result<(), AppServerProcessError> {
        if self.max_attempts == 0
            || self.base_delay.is_zero()
            || self.max_delay < self.base_delay
            || self.max_delay > Duration::from_secs(3_600)
        {
            return Err(AppServerProcessError::InvalidConfig);
        }
        Ok(())
    }
}

/// 可注入的单调时钟把时间来源留在 lifecycle 内部，避免 wall clock 回拨影响恢复，
/// 同时不要求调用方通过 sleep 隐式推进状态。
pub(crate) trait Clock: Send + Sync {
    /// 返回相对进程启动点的单调毫秒，供 backoff 和稳定窗口使用。
    fn now_millis(&self) -> u64;
}

#[derive(Debug)]
pub(crate) struct MonotonicClock {
    origin: Instant,
}

impl Default for MonotonicClock {
    /// 从独立起点开始计时，避免系统墙上时钟调整破坏 deadline 判断。
    fn default() -> Self {
        Self {
            origin: Instant::now(),
        }
    }
}

impl Clock for MonotonicClock {
    /// 将高精度 elapsed 转成有界毫秒，防止极端运行时长溢出协议字段。
    fn now_millis(&self) -> u64 {
        self.origin.elapsed().as_millis().min(u128::from(u64::MAX)) as u64
    }
}

/// Supervisor 只通过这些方法推进状态，避免把 bool 标志组合成非法状态。
pub(crate) struct LifecycleMachine {
    state: LifecycleState,
    generation: u64,
    restart_attempt: u32,
    next_retry_millis: Option<u64>,
    ready_since_millis: Option<u64>,
    policy: RestartPolicy,
    clock: Arc<dyn Clock>,
}

impl LifecycleMachine {
    /// 使用生产单调时钟创建生命周期机，初态 Exited 允许第一次显式 start。
    pub(crate) fn new(policy: RestartPolicy) -> Result<Self, AppServerProcessError> {
        Self::with_clock(policy, Arc::new(MonotonicClock::default()))
    }

    /// 在 crate 内注入单调时钟端口，使生命周期算法不依赖墙上时间，同时不把测试
    /// 控制面扩大为公共 façade。
    pub(crate) fn with_clock(
        policy: RestartPolicy,
        clock: Arc<dyn Clock>,
    ) -> Result<Self, AppServerProcessError> {
        policy.validate()?;
        Ok(Self {
            state: LifecycleState::Exited,
            generation: 0,
            restart_attempt: 0,
            next_retry_millis: None,
            ready_since_millis: None,
            policy,
            clock,
        })
    }

    /// 读取当前状态而不改变 transition owner，供 supervisor 做能力门控。
    pub(crate) fn state(&self) -> LifecycleState {
        self.state
    }

    /// 返回 generation，事件消费者用它丢弃重启前实例的迟到事实。
    pub(crate) fn generation(&self) -> u64 {
        self.generation
    }

    /// 只有 Exited 或已到期 Backoff 能创建新 generation，防止双启动。
    pub(crate) fn begin_start(&mut self) -> Result<u64, AppServerProcessError> {
        match self.state {
            LifecycleState::Exited => {}
            LifecycleState::Backoff => {
                if !self.backoff_due() {
                    return Err(AppServerProcessError::Backoff {
                        retry_after: self.retry_after(),
                    });
                }
            }
            LifecycleState::Incompatible => return Err(AppServerProcessError::Incompatible),
            LifecycleState::Faulted => return Err(AppServerProcessError::Faulted),
            _ => return Err(AppServerProcessError::InvalidState),
        }
        self.generation = self.generation.wrapping_add(1).max(1);
        self.state = LifecycleState::Starting;
        self.next_retry_millis = None;
        self.ready_since_millis = None;
        Ok(self.generation)
    }

    /// ready 只接受当前 generation 的严格握手，旧实例的通知不能越过状态机。
    pub(crate) fn mark_ready(&mut self, generation: u64) -> Result<(), AppServerProcessError> {
        if generation != self.generation || self.state != LifecycleState::Starting {
            return Err(AppServerProcessError::InvalidState);
        }
        self.state = LifecycleState::Ready;
        self.ready_since_millis = Some(self.clock.now_millis());
        Ok(())
    }

    /// 长请求开始时显式进入 Busy，UI 可区分 ready 与正在占用的 sidecar。
    pub(crate) fn mark_busy(&mut self, generation: u64) -> Result<(), AppServerProcessError> {
        if generation != self.generation || self.state != LifecycleState::Ready {
            return Err(AppServerProcessError::InvalidState);
        }
        self.state = LifecycleState::Busy;
        Ok(())
    }

    /// 把 Busy request 完成后的同一 generation 恢复为 Ready，保持状态闭环。
    pub(crate) fn mark_ready_again(
        &mut self,
        generation: u64,
    ) -> Result<(), AppServerProcessError> {
        if generation != self.generation || self.state != LifecycleState::Busy {
            return Err(AppServerProcessError::InvalidState);
        }
        self.state = LifecycleState::Ready;
        Ok(())
    }

    /// Stopping 是显式阶段，shutdown deadline 期间禁止新的业务 request。
    pub(crate) fn begin_stop(&mut self, generation: u64) -> Result<(), AppServerProcessError> {
        if generation != self.generation
            || !matches!(
                self.state,
                LifecycleState::Starting | LifecycleState::Ready | LifecycleState::Busy
            )
        {
            return Err(AppServerProcessError::InvalidState);
        }
        self.state = LifecycleState::Stopping;
        Ok(())
    }

    /// 旧 generation 的退出只作为诊断，不改变当前运行实例的状态。
    pub(crate) fn mark_exited(&mut self, generation: u64) -> bool {
        if generation != self.generation
            || matches!(
                self.state,
                LifecycleState::Faulted | LifecycleState::Incompatible
            )
        {
            return false;
        }
        self.state = LifecycleState::Exited;
        true
    }

    /// 协议 major/minor 不兼容是配置事实，不能被 crash restart 反复触发。
    pub(crate) fn mark_incompatible(&mut self, generation: u64) -> bool {
        if generation != self.generation {
            return false;
        }
        self.state = LifecycleState::Incompatible;
        self.next_retry_millis = None;
        self.ready_since_millis = None;
        true
    }

    /// 标记当前 generation 为终态 fault，禁止旧 monitor 事件重新开启重试。
    pub(crate) fn mark_faulted(&mut self, generation: u64) -> bool {
        if generation != self.generation {
            return false;
        }
        self.state = LifecycleState::Faulted;
        self.next_retry_millis = None;
        self.ready_since_millis = None;
        true
    }

    /// 计算有限指数退避；达到次数后 Faulted，避免桌面 crash loop。
    pub(crate) fn record_crash(&mut self, generation: u64) -> LifecycleState {
        if generation != self.generation
            || matches!(
                self.state,
                LifecycleState::Exited | LifecycleState::Faulted | LifecycleState::Incompatible
            )
        {
            return self.state;
        }
        if matches!(
            self.state,
            LifecycleState::Stopping | LifecycleState::Backoff
        ) {
            return self.state;
        }
        if let Some(ready_since) = self.ready_since_millis
            && self.clock.now_millis().saturating_sub(ready_since) >= STABLE_WINDOW_MILLIS
        {
            self.restart_attempt = 0;
        }
        self.restart_attempt = self.restart_attempt.saturating_add(1);
        if self.restart_attempt > self.policy.max_attempts {
            self.state = LifecycleState::Faulted;
            self.next_retry_millis = None;
            return self.state;
        }
        let shift = self.restart_attempt.saturating_sub(1).min(31);
        let multiplier = 1_u32.checked_shl(shift).unwrap_or(u32::MAX);
        let delay = self
            .policy
            .base_delay
            .saturating_mul(multiplier)
            .min(self.policy.max_delay);
        self.next_retry_millis = Some(
            self.clock
                .now_millis()
                .saturating_add(delay.as_millis().min(u128::from(u64::MAX)) as u64),
        );
        self.state = LifecycleState::Backoff;
        self.state
    }

    /// 判断下一次 start 是否到达退避时间，不通过 sleep 隐式推进状态。
    pub(crate) fn backoff_due(&self) -> bool {
        self.next_retry_millis
            .is_none_or(|deadline| self.clock.now_millis() >= deadline)
    }

    /// 返回距离退避结束的剩余时间，供调用方构造稳定的 Backoff 错误。
    pub(crate) fn retry_after(&self) -> Duration {
        self.next_retry_millis
            .map(|deadline| Duration::from_millis(deadline.saturating_sub(self.clock.now_millis())))
            .unwrap_or_default()
    }

    /// 判断事件 generation 是否仍属于当前实例，防止跨重启资源清理。
    pub(crate) fn is_current(&self, generation: u64) -> bool {
        generation == self.generation
    }
}

const STABLE_WINDOW_MILLIS: u64 = 30_000;
