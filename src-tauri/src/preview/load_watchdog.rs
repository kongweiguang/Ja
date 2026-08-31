// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 为只公开 completion 而不公开 error 的 engine 提供有界页面加载 watchdog。

use super::error::{PreviewError, PreviewErrorCode};
use super::model::{PreviewGeneration, PreviewId};
use std::collections::HashMap;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

/// 表示可取消的延迟任务；具体 runtime handle 留在 composition adapter 内，
/// 避免 Preview 状态层依赖 Tauri 或某个异步执行器。
pub(crate) trait LoadTimeoutTask: Send {
    /// 取消尚未领取的 callback；实现必须允许重复调用且不能同步执行 callback。
    fn abort(&self);
}

/// 注入 watchdog 所需的最小调度能力；callback 必须延迟派发，不能在 `schedule`
/// 调用栈内同步执行，否则会破坏 registry 的原子发布约束。
pub(crate) trait LoadTimeoutRuntime: Send + Sync {
    /// 在 delay 后至多执行一次 callback，并返回可由 session 生命周期拥有的取消句柄。
    fn schedule(
        &self,
        delay: Duration,
        callback: Box<dyn FnOnce() + Send + 'static>,
    ) -> Box<dyn LoadTimeoutTask>;
}

/// 标识一个已启动 timeout，但不暴露 URL 或原生 WebView handle。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LoadTimeoutTicket {
    session_id: PreviewId,
    generation: PreviewGeneration,
    token: u64,
}

/// 拥有单个 session 唯一活动 watchdog task 的 abort handle。
struct PendingLoadTimeout {
    ticket: LoadTimeoutTicket,
    task: Box<dyn LoadTimeoutTask>,
}

#[derive(Default)]
struct LoadTimeoutState {
    shutdown_started: bool,
    next_token: u64,
    pending: HashMap<PreviewId, PendingLoadTimeout>,
}

/// 每个 Preview session 最多一个 timeout task，并按 generation 识别陈旧 callback。
#[derive(Clone)]
pub(crate) struct PreviewLoadWatchdog {
    state: Arc<Mutex<LoadTimeoutState>>,
    runtime: Arc<dyn LoadTimeoutRuntime>,
}

impl PreviewLoadWatchdog {
    /// 显式注入宿主 runtime，使同一状态机既能由 Tauri 全局执行器驱动，
    /// 也能在单元测试中使用确定性调度器验证竞争与取消语义。
    pub(crate) fn new(runtime: Arc<dyn LoadTimeoutRuntime>) -> Self {
        Self {
            state: Arc::new(Mutex::new(LoadTimeoutState::default())),
            runtime,
        }
    }

    /// spawn 新有界 task 前替换已有 session timeout；在 spawn/insert 全程持有 registry lock，
    /// 防止旧 task 在替换决定与发布之间领取 timeout。
    pub(crate) fn arm<F>(
        &self,
        session_id: PreviewId,
        generation: PreviewGeneration,
        timeout: Duration,
        on_timeout: F,
    ) -> Result<(), PreviewError>
    where
        F: FnOnce() + Send + 'static,
    {
        let mut state = self.lock_state()?;
        if state.shutdown_started {
            return Err(PreviewError::new(PreviewErrorCode::ShutdownStarted));
        }
        let token = state.next_token.checked_add(1).ok_or(PreviewError::new(
            PreviewErrorCode::InternalStateUnavailable,
        ))?;
        state.next_token = token;
        let ticket = LoadTimeoutTicket {
            session_id,
            generation,
            token,
        };
        let watchdog = self.clone();
        // 调度实现由 composition 注入，因此原生 callback 线程不需要当前 Tokio context；
        // registry lock 会覆盖 schedule/insert，确保零延迟任务也只能在发布后领取 ticket。
        let task = self.runtime.schedule(
            timeout,
            Box::new(move || match watchdog.expire(ticket) {
                Ok(true) => on_timeout(),
                Ok(false) => {}
                Err(error) => {
                    tracing::debug!(
                        code = ?error.code(),
                        "preview load watchdog timeout could not reconcile state"
                    );
                }
            }),
        );
        let previous = state
            .pending
            .insert(session_id, PendingLoadTimeout { ticket, task });
        drop(state);
        if let Some(previous) = previous {
            previous.task.abort();
        }
        Ok(())
    }

    /// 只取消与 engine completion 当前 generation 对应的 timeout；旧 navigation 的晚到
    /// completion 不能解除较新的 load。
    pub(crate) fn complete(
        &self,
        session_id: PreviewId,
        generation: PreviewGeneration,
    ) -> Result<bool, PreviewError> {
        let mut state = self.lock_state()?;
        let matches = state
            .pending
            .get(&session_id)
            .is_some_and(|pending| pending.ticket.generation == generation);
        let pending = matches.then(|| state.pending.remove(&session_id)).flatten();
        drop(state);
        if let Some(pending) = pending {
            pending.task.abort();
            return Ok(true);
        }
        Ok(false)
    }

    /// close、navigation 拒绝或原生创建失败时取消 session timeout，
    /// 不依赖最后观察到哪个 generation。
    pub(crate) fn cancel(&self, session_id: PreviewId) -> Result<bool, PreviewError> {
        let pending = self.lock_state()?.pending.remove(&session_id);
        if let Some(pending) = pending {
            pending.task.abort();
            return Ok(true);
        }
        Ok(false)
    }

    /// 永久隔离未来 timer，并原子 abort 所有 detached task；晚到的原生 Started callback
    /// 因此无法在 shutdown 后复活工作。
    pub(crate) fn cancel_all(&self) -> Result<(), PreviewError> {
        let pending = {
            let mut state = self.lock_state()?;
            state.shutdown_started = true;
            std::mem::take(&mut state.pending)
        };
        for (_, pending) in pending {
            pending.task.abort();
        }
        Ok(())
    }

    /// 只有 generation 与不透明 attempt token 都仍为当前值时才领取 timeout；
    /// completion、replacement 与 close 都会使其 stale。
    fn expire(&self, ticket: LoadTimeoutTicket) -> Result<bool, PreviewError> {
        let mut state = self.lock_state()?;
        let matches = state
            .pending
            .get(&ticket.session_id)
            .is_some_and(|pending| pending.ticket == ticket);
        if matches {
            state.pending.remove(&ticket.session_id);
            return Ok(true);
        }
        Ok(false)
    }

    /// mutex poison 映射为现有脱敏 host-state error；task ownership 已不可信时
    /// 不尝试恢复 registry。
    fn lock_state(&self) -> Result<MutexGuard<'_, LoadTimeoutState>, PreviewError> {
        self.state
            .lock()
            .map_err(|_| PreviewError::new(PreviewErrorCode::InternalStateUnavailable))
    }
}

// 测试通过既有行为观察任务所有权，不在生产类型上增加仅测试可见的方法。
