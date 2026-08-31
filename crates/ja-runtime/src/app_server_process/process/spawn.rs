// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Child process 所有权与 sidecar stdio 启动。

use super::SidecarConfig;
use super::tree::{ProcessTreeGuard, bounded_reap_child};
use crate::app_server_process::client::session::wire::DEFAULT_WRITE_WATCHDOG_TIMEOUT;
use crate::app_server_process::client::{Session, TerminalCallback, TerminalReason};
use crate::app_server_process::error::AppServerProcessError;
use std::collections::VecDeque;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const MAX_TERMINAL_SIGNALS: usize = 64;

const MAX_REAP_TIMEOUT: Duration = Duration::from_secs(2);

pub(crate) struct RunningProcess {
    child: Mutex<Option<Child>>,
    tree: ProcessTreeGuard,
    exited: AtomicBool,
    exit_wake: Condvar,
    exit_lock: Mutex<()>,
}

#[derive(Debug, Clone, Copy)]
pub(crate) struct TerminalSignal {
    pub(crate) generation: u64,
    pub(crate) reason: TerminalReason,
}

impl RunningProcess {
    /// 终止完整 process group/job，而不是只杀 Java 直接子进程。
    pub(crate) fn terminate_tree(&self) -> Result<(), AppServerProcessError> {
        self.terminate_tree_until(reap_deadline())
    }

    /// 在 caller 给定的绝对 deadline 内完成最终 reap，避免 shutdown 超时被 cleanup 延长。
    pub(crate) fn terminate_tree_until(
        &self,
        deadline: Instant,
    ) -> Result<(), AppServerProcessError> {
        let result = self.tree.terminate();
        // Child 在整个生命周期都由 RunningProcess 持有；OS tree adapter 只是补充路径，
        // 即使 Job/process-group 终止失败，也必须通过原始 handle 有界回收。
        let reap_error = match self.child.lock() {
            Ok(mut child) => child
                .as_mut()
                .and_then(|child| bounded_reap_child(child, deadline).err()),
            Err(_) => {
                // tree adapter 已先执行 terminate；Child owner 中毒后不能读取可能处于
                // try_wait 中间态的 handle，只能报告 cleanup 未被完整确认。
                return Err(AppServerProcessError::ProcessTree);
            }
        };
        if result.is_err() || reap_error.is_some() {
            Err(AppServerProcessError::ProcessTree)
        } else {
            Ok(())
        }
    }

    /// 在 deadline 内等待 monitor 发布退出事实，不能持有 child 锁阻塞 shutdown。
    pub(crate) fn wait_until(&self, deadline: Instant) -> bool {
        let Ok(mut lock) = self.exit_lock.lock() else {
            // exited AtomicBool 是唯一退出事实；等待锁中毒时不能恢复 Condvar 协议，
            // 让 caller 走有界 terminate/reap 路径。
            return self.exited.load(Ordering::Acquire);
        };
        loop {
            if self.exited.load(Ordering::Acquire) {
                return true;
            }
            let remaining = deadline.saturating_duration_since(Instant::now());
            if remaining.is_zero() {
                return false;
            }
            let Ok((next, wait)) = self.exit_wake.wait_timeout(lock, remaining) else {
                return self.exited.load(Ordering::Acquire);
            };
            lock = next;
            if wait.timed_out() {
                return self.exited.load(Ordering::Acquire);
            }
        }
    }

    /// 记录 monitor 的单次退出事实并唤醒所有 deadline waiter。
    pub(crate) fn mark_exited(&self) -> Result<(), AppServerProcessError> {
        // leader 退出不能证明 descendants 已退出；发布事件前先终止自有 group/job，
        // 防止 consumer 看见 session 已结束时 grandchild 仍在执行。
        let result = self.tree.mark_exited();
        self.exited.store(true, Ordering::Release);
        self.exit_wake.notify_all();
        result.map_err(|_| AppServerProcessError::ProcessTree)
    }
}

/// 启动并绑定完整进程树，任何管道/线程失败都在返回前 kill+wait。
pub(crate) fn spawn_process(
    config: &SidecarConfig,
    generation: u64,
    terminal_signals: Arc<Mutex<VecDeque<TerminalSignal>>>,
) -> Result<(Arc<RunningProcess>, Session), AppServerProcessError> {
    config.verify_executable_identity()?;
    let mut command = Command::new(config.canonical_executable());
    command
        .args(&config.args)
        .current_dir(config.canonical_run_dir())
        .env_clear()
        .envs(config.env.iter())
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let tree = ProcessTreeGuard::prepare(&mut command).map_err(|_| AppServerProcessError::Spawn)?;
    let mut child = command.spawn().map_err(|_| AppServerProcessError::Spawn)?;
    if tree
        .assign(&child)
        .and_then(|_| tree.resume(&child))
        .is_err()
    {
        return Err(
            cleanup_spawned_child(&tree, &mut child).unwrap_or(AppServerProcessError::Spawn)
        );
    }
    let stdin = match child.stdin.take() {
        Some(stdin) => stdin,
        None => return Err(abort_spawned_child(&tree, &mut child)),
    };
    let stdout = match child.stdout.take() {
        Some(stdout) => stdout,
        None => return Err(abort_spawned_child(&tree, &mut child)),
    };
    let stderr = match child.stderr.take() {
        Some(stderr) => stderr,
        None => return Err(abort_spawned_child(&tree, &mut child)),
    };
    let process = Arc::new(RunningProcess {
        child: Mutex::new(Some(child)),
        tree,
        exited: AtomicBool::new(false),
        exit_wake: Condvar::new(),
        exit_lock: Mutex::new(()),
    });
    // stale client 可能比 supervisor 存活更久；weak capture 防止 lifecycle detach 后
    // client 继续持有 OS process/job。
    let terminal_process = Arc::downgrade(&process);
    let terminal_callback: TerminalCallback = Arc::new(move |reason| {
        let _ = terminal_process
            .upgrade()
            .map(|process| process.terminate_tree());
        let Ok(mut signals) = terminal_signals.lock() else {
            // 进程树已经在上方终止；signal queue 中毒时不恢复其顺序，supervisor
            // 会在下一次同步时把当前 generation 视为 Faulted。
            return;
        };
        // generation 单调递增，超过窗口的最旧 signal 只可能是 stale owner；
        // 保留最新窗口让当前 generation 永远可达，同时限制异常重启的内存占用。
        while signals.len() >= MAX_TERMINAL_SIGNALS {
            signals.pop_front();
        }
        signals.push_back(TerminalSignal { generation, reason });
    });
    let session = match Session::from_io_with_terminal(
        stdout,
        stdin,
        stderr,
        generation,
        config.limits.clone(),
        Some(terminal_callback),
        DEFAULT_WRITE_WATCHDOG_TIMEOUT,
    ) {
        Ok(session) => session,
        Err(error) => {
            let cleanup_error = process.terminate_tree_until(reap_deadline()).err();
            if cleanup_error.is_some() {
                return Err(AppServerProcessError::ProcessTree);
            }
            return Err(error);
        }
    };
    let monitor_process = Arc::clone(&process);
    let monitor_session = session.clone();
    thread::Builder::new()
        .name("ja-sidecar-monitor".to_owned())
        .spawn(move || {
            // 在短 mutex scope 内探测保留的 Child；blocking wait 不持有该锁，因此
            // shutdown 始终可以执行自己的 deadline。
            loop {
                let child = monitor_process.child.lock();
                let status = match child {
                    Ok(mut child) => child.as_mut().map(Child::try_wait),
                    Err(poisoned) => {
                        // Child owner 已不可信，停止 monitor 正常路径并强制 tree cleanup；
                        // 先释放 PoisonError guard，避免 cleanup 重新锁 Child 时自锁。
                        drop(poisoned);
                        let _ = monitor_process.terminate_tree_until(reap_deadline());
                        monitor_session.report_process_fault();
                        break;
                    }
                };
                match status {
                    Some(Ok(Some(status))) => {
                        if monitor_process.mark_exited().is_err() {
                            monitor_session.report_process_fault();
                        } else {
                            monitor_session.report_process_exit(status.code());
                        }
                        break;
                    }
                    Some(Ok(None)) => thread::park_timeout(Duration::from_millis(10)),
                    Some(Err(_)) => {
                        // error 不能证明 leader 已消失；有界 cleanup 继续持有原始 handle，
                        // 保证后续重试仍可观察和回收。
                        let _ = monitor_process.terminate_tree_until(reap_deadline());
                        let _ = monitor_process.mark_exited();
                        monitor_session.report_process_fault();
                        break;
                    }
                    None => {
                        let _ = monitor_process.mark_exited();
                        monitor_session.report_process_fault();
                        break;
                    }
                }
            }
        })
        .map_err(|_| {
            let _ = process.terminate_tree_until(reap_deadline());
            AppServerProcessError::Spawn
        })?;
    Ok((process, session))
}

/// spawn 后任一 stdio pipe 缺失都必须收口 suspended/job child，不能依赖 Drop 偶然清理。
/// 收口已经 spawn 但无法接入 session 的 child，避免 suspended/zombie 泄露。
fn abort_spawned_child(tree: &ProcessTreeGuard, child: &mut Child) -> AppServerProcessError {
    cleanup_spawned_child(tree, child).unwrap_or(AppServerProcessError::Spawn)
}

/// kill/wait 直接 child，同时尝试 tree adapter；失败只改变可观察错误，不放弃收口。
fn cleanup_spawned_child(
    tree: &ProcessTreeGuard,
    child: &mut Child,
) -> Option<AppServerProcessError> {
    // 先执行 direct kill/reap，确保 Job 调用失败不会阻塞 leader cleanup；随后由 tree
    // adapter 尽力清理 descendants。
    let deadline = reap_deadline();
    let reap_error = bounded_reap_child(child, deadline).is_err();
    let tree_error = tree.terminate().is_err();
    if tree_error || reap_error {
        Some(AppServerProcessError::ProcessTree)
    } else {
        None
    }
}

/// 为每次收口创建独立绝对 deadline，避免等待时间叠加成无界 cleanup。
fn reap_deadline() -> Instant {
    Instant::now()
        .checked_add(MAX_REAP_TIMEOUT)
        .unwrap_or_else(Instant::now)
}
