// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later
// Windows Job Object 故障注入测试；与跨平台进程树测试分文件，避免测试职责再次嵌套。

use super::*;
use crate::unit_support_tests::poison_mutex;
use std::sync::Arc;

/// 持有 Windows descendant fixture 的精确路径，确保 fault assertion 提前失败时仍会清理。
struct TempFileGuard(std::path::PathBuf);

impl TempFileGuard {
    /// 只接收当前测试生成的文件路径，避免 Drop 扩大到目录或用户数据。
    fn new(path: std::path::PathBuf) -> Self {
        Self(path)
    }
}

impl Drop for TempFileGuard {
    /// 以幂等文件删除收口 fixture；不存在表示生产 cleanup 已先完成，不视为失败。
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// 仅在私有算法测试模块替换 Job backend，避免生产 ProcessTreeGuard 暴露测试控制
/// 方法；guard 的所有终止路径仍调用同一生产实现。
fn replace_backend<F>(guard: &mut ProcessTreeGuard, create: F)
where
    F: FnOnce(&ProcessTreeGuard) -> Arc<dyn JobBackend>,
{
    let backend = create(guard);
    guard.backend = backend;
}

#[derive(Clone, Copy, Default)]
struct FaultingJob {
    job: *mut std::ffi::c_void,
    fail_assign: bool,
    fail_resume: bool,
    fail_terminate: bool,
    fail_close: bool,
    fail_terminate_process: bool,
}

impl FaultingJob {
    /// 构造单个 production backend fault，使每个测试都经过 ProcessTreeGuard 的
    /// assign/resume/terminate wrapper 的生产实现。
    fn for_guard(guard: &ProcessTreeGuard, operation: JobOperation) -> Self {
        let mut fault = Self {
            job: guard.job.raw(),
            ..Self::default()
        };
        match operation {
            JobOperation::Assign => fault.fail_assign = true,
            JobOperation::Resume => fault.fail_resume = true,
            JobOperation::Terminate => fault.fail_terminate = true,
            JobOperation::Close => fault.fail_close = true,
            JobOperation::TerminateProcess => fault.fail_terminate_process = true,
        }
        fault
    }

    /// 构造把成功调用委托给真实 Win32 API 的 backend，避免测试复制平台实现。
    fn success_for_guard(guard: &ProcessTreeGuard) -> Self {
        Self {
            job: guard.job.raw(),
            ..Self::default()
        }
    }

    /// 组合 terminate 与 direct-process fault，强制走最终 exact-handle 所有权路径，
    /// 同时仍由 Job close 持有 descendants。
    fn terminate_and_process_fault() -> Self {
        Self {
            fail_terminate: true,
            fail_terminate_process: true,
            ..Self::default()
        }
    }

    /// 把组合终止 fault 绑定到当前被测 exact Job handle，避免注入影响其它进程。
    fn for_guard_terminate_and_process(guard: &ProcessTreeGuard) -> Self {
        Self {
            job: guard.job.raw(),
            ..Self::terminate_and_process_fault()
        }
    }
}

unsafe impl Send for FaultingJob {}
unsafe impl Sync for FaultingJob {}

impl JobBackend for FaultingJob {
    /// 注入 assign failure，同时保留 duplicate exact child handle 供失败恢复。
    fn assign(
        &self,
        _job: *mut std::ffi::c_void,
        _process: *mut std::ffi::c_void,
    ) -> io::Result<()> {
        if self.fail_assign {
            Err(io::Error::other("injected assign failure"))
        } else {
            let ok = unsafe { AssignProcessToJobObject(self.job, _process) };
            if ok == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }
    }

    /// production assign wrapper 成功后注入 resume failure，验证 suspended owner 未丢失。
    fn resume(&self, _process_id: u32) -> io::Result<()> {
        if self.fail_resume {
            Err(io::Error::other("injected resume failure"))
        } else {
            resume_suspended_process(_process_id)
        }
    }

    /// 注入 Job termination failure，同时保留 close 所有权路径。
    fn terminate(&self) -> io::Result<()> {
        if self.fail_terminate {
            Err(io::Error::other("injected job failure"))
        } else {
            let ok = unsafe { TerminateJobObject(self.job, 1) };
            if ok == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }
    }

    /// 注入 Job close failure，使 raw ownership 在后续重试中仍可观察。
    fn close_job(&self, _job: *mut std::ffi::c_void) -> io::Result<()> {
        if self.fail_close {
            Err(io::Error::other("injected close failure"))
        } else {
            let ok = unsafe { CloseHandle(_job) };
            if ok == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }
    }

    /// group cleanup 尝试后注入 exact leader termination failure，验证最终 owner 保留。
    fn terminate_process(&self, _process: *mut std::ffi::c_void) -> io::Result<()> {
        if self.fail_terminate_process {
            Err(io::Error::other("injected process failure"))
        } else {
            let ok = unsafe { TerminateProcess(_process, 1) };
            if ok == 0 {
                Err(io::Error::last_os_error())
            } else {
                Ok(())
            }
        }
    }
}

/// 断言结构化 operation record，不暴露 raw OS message。
fn assert_recorded(guard: &ProcessTreeGuard, operation: JobOperation) {
    let Ok(records) = guard.errors.lock() else {
        panic!("job error fixture ledger must remain healthy");
    };
    assert!(
        records.iter().any(|entry| entry.operation == operation),
        "missing structured record for {operation:?}"
    );
}

/// duplicated child handle owner 中毒后 direct terminate 必须返回结构化 IO failure，
/// 不能从 poisoned Option 取出可能已转移或关闭的 Win32 handle。
#[test]
fn poisoned_child_handle_rejects_direct_termination() {
    let executable = std::env::current_exe().expect("current test executable");
    let mut command = Command::new(executable);
    let guard = ProcessTreeGuard::prepare(&mut command).expect("job guard fixture");
    poison_mutex(&guard.child_handle);
    let backend = FaultingJob::success_for_guard(&guard);

    let error = guard
        .terminate_direct_child_with_backend(&backend)
        .expect_err("poisoned owner must reject handle access");
    assert_eq!(error.kind(), io::ErrorKind::Other);
}

/// assign 与 resume 注入必须经过 production adapter，并为有界 cleanup 路径保留真实
/// suspended child 资源。
#[test]
fn real_child_assign_and_resume_faults_keep_ownership() {
    let mut command = Command::new("cmd");
    command.args(["/C", "ping 127.0.0.1 -n 30 >NUL"]);
    let mut guard = ProcessTreeGuard::prepare(&mut command).expect("job prepared");
    let mut child = command.spawn().expect("real child spawned");
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::for_guard(guard, JobOperation::Assign))
    });
    assert!(guard.assign(&child).is_err());
    assert_recorded(&guard, JobOperation::Assign);
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::for_guard(guard, JobOperation::Resume))
    });
    assert!(guard.resume(&child).is_err());
    assert_recorded(&guard, JobOperation::Resume);
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::success_for_guard(guard))
    });
    assert!(guard.terminate().is_ok());
    bounded_reap_child(
        &mut child,
        Instant::now()
            .checked_add(Duration::from_secs(2))
            .expect("cleanup deadline fits"),
    )
    .expect("retained child must be reaped");
}

/// Job/Close/TerminateProcess fault 后，真实 suspended child 必须在 guard Drop 前回收；
/// Drop 不是正常 cleanup 机制。
#[test]
fn real_child_fault_keeps_exact_handle_for_bounded_cleanup() {
    let mut command = Command::new("cmd");
    command.args(["/C", "ping 127.0.0.1 -n 30 >NUL"]);
    let mut guard = ProcessTreeGuard::prepare(&mut command).expect("job prepared");
    let mut child = command.spawn().expect("real child spawned");
    guard.assign(&child).expect("child assigned");
    guard.resume(&child).expect("child resumed");
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::for_guard_terminate_and_process(guard))
    });
    let error = guard
        .terminate()
        .expect_err("injected Job failure must be observable");
    assert_eq!(error.kind(), io::ErrorKind::Other);
    assert_recorded(&guard, JobOperation::Terminate);
    assert_recorded(&guard, JobOperation::TerminateProcess);
    bounded_reap_child(
        &mut child,
        Instant::now()
            .checked_add(Duration::from_secs(2))
            .expect("cleanup deadline fits"),
    )
    .expect("exact child handle must be reaped");
    drop(guard);
}

/// spawn 一个会创建可跟踪 descendant 的真实 suspended PowerShell leader。
fn spawn_real_descendant_fixture() -> Option<(ProcessTreeGuard, Child, u32, TempFileGuard)> {
    let pid_path = std::env::temp_dir().join(format!(
        "ja-process-tree-fault-{}-{}.pid",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .expect("clock is after epoch")
            .as_nanos()
    ));
    let pid_cleanup = TempFileGuard::new(pid_path.clone());
    let powershell = std::path::PathBuf::from(std::env::var_os("SystemRoot").expect("SystemRoot"))
        .join("System32")
        .join("WindowsPowerShell")
        .join("v1.0")
        .join("powershell.exe");
    if !powershell.is_file() {
        return None;
    }
    let pid_literal = pid_path.to_string_lossy().replace('\'', "''");
    let script = format!(
        r#"$child = Start-Process -FilePath ($env:SystemRoot + '\System32\WindowsPowerShell\v1.0\powershell.exe') -ArgumentList @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -WindowStyle Hidden -PassThru; [IO.File]::WriteAllText('{pid_literal}', [string]$child.Id); Wait-Process -Id ($child.Id)"#
    );
    let mut command = Command::new(&powershell);
    command.args(["-NoProfile", "-NonInteractive", "-Command", &script]);
    let guard = ProcessTreeGuard::prepare(&mut command).expect("job prepared");
    let child = command.spawn().expect("real leader spawned");
    guard.assign(&child).expect("leader assigned");
    guard.resume(&child).expect("leader resumed");
    let deadline = Instant::now() + Duration::from_secs(3);
    let descendant = loop {
        if let Ok(value) = std::fs::read_to_string(&pid_path)
            && let Ok(pid) = value.trim().parse::<u32>()
        {
            break pid;
        }
        assert!(
            Instant::now() < deadline,
            "descendant pid was not published"
        );
        thread::yield_now();
    };
    Some((guard, child, descendant, pid_cleanup))
}

/// 真实 leader 出现 Job/Close/TerminateProcess fault 时，仍须在绝对 deadline 前关闭
/// 保留 Job 并回收 descendant。
#[test]
fn production_terminate_fault_reaps_real_descendant() {
    let Some((mut guard, mut child, descendant, _pid_cleanup)) = spawn_real_descendant_fixture()
    else {
        return;
    };
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::for_guard_terminate_and_process(guard))
    });
    assert!(guard.terminate().is_err());
    assert_recorded(&guard, JobOperation::Terminate);
    assert_recorded(&guard, JobOperation::TerminateProcess);
    bounded_reap_child(
        &mut child,
        Instant::now()
            .checked_add(Duration::from_secs(2))
            .expect("leader cleanup deadline fits"),
    )
    .expect("leader must be reaped");
    let deadline = Instant::now() + Duration::from_secs(2);
    while windows_process_exists(descendant) && Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(!windows_process_exists(descendant));
    drop(guard);
}

/// 真实 Job close fault 必须经 terminate 记录，且 descendants 必须在 guard Drop 前退出。
#[test]
fn production_close_fault_reaps_real_descendant() {
    let Some((mut guard, mut child, descendant, _pid_cleanup)) = spawn_real_descendant_fixture()
    else {
        return;
    };
    replace_backend(&mut guard, |guard| {
        Arc::new(FaultingJob::for_guard(guard, JobOperation::Close))
    });
    assert!(guard.terminate().is_err());
    assert_recorded(&guard, JobOperation::Close);
    bounded_reap_child(
        &mut child,
        Instant::now()
            .checked_add(Duration::from_secs(2))
            .expect("leader cleanup deadline fits"),
    )
    .expect("leader must be reaped before guard drop");
    let deadline = Instant::now() + Duration::from_secs(2);
    while windows_process_exists(descendant) && Instant::now() < deadline {
        thread::yield_now();
    }
    assert!(!windows_process_exists(descendant));
    drop(guard);
}

/// 仅为测试观察 descendant 最终消失而查询 tasklist，不重新打开 process handle。
fn windows_process_exists(pid: u32) -> bool {
    let filter = format!("PID eq {pid}");
    Command::new("tasklist")
        .args(["/FI", &filter, "/NH"])
        .output()
        .ok()
        .map(|output| {
            String::from_utf8_lossy(&output.stdout).lines().any(|line| {
                line.split_whitespace()
                    .nth(1)
                    .is_some_and(|value| value == pid.to_string())
            })
        })
        .unwrap_or(false)
}
