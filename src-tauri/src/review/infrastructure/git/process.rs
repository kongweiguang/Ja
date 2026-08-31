// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

use super::adapter::GitPolicy;
use super::error::GitError;
use std::io::{self, Read};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// 由独立有界 reader 收集输出，避免高输出 Git 子进程在父进程等待退出时因 stderr 管道填满而阻塞。
pub(crate) struct RawGitOutput {
    pub(crate) stdout: Vec<u8>,
    pub(crate) status: ExitStatus,
}

/// 执行已经校验的 Git argv，并统一负责取消、超时与进程树清理。
pub(crate) fn run_git(
    mut command: Command,
    policy: &GitPolicy,
    cancellation: &tokio_util::sync::CancellationToken,
) -> Result<RawGitOutput, GitError> {
    if cancellation.is_cancelled() {
        return Err(GitError::Cancelled);
    }
    let deadline = Instant::now()
        .checked_add(policy.timeout)
        .unwrap_or_else(Instant::now);
    if Instant::now() >= deadline {
        return Err(GitError::TimedOut);
    }
    command.stdin(Stdio::null());
    command.stdout(Stdio::piped());
    command.stderr(Stdio::piped());
    let mut tree = ProcessTree::prepare(&mut command)
        .map_err(|error| GitError::Spawn { kind: error.kind() })?;
    if Instant::now() >= deadline {
        return Err(GitError::TimedOut);
    }
    let mut child = command
        .spawn()
        .map_err(|error| GitError::Spawn { kind: error.kind() })?;
    if let Err(error) = tree.start(&child) {
        let cleanup_deadline = Instant::now()
            .checked_add(policy.cleanup_timeout)
            .unwrap_or_else(Instant::now);
        let _ = terminate_child(&mut child, &tree, cleanup_deadline);
        return Err(GitError::Spawn { kind: error.kind() });
    }
    let Some(stdout) = child.stdout.take() else {
        let cleanup_deadline = Instant::now()
            .checked_add(policy.cleanup_timeout)
            .unwrap_or_else(Instant::now);
        let _ = terminate_child(&mut child, &tree, cleanup_deadline);
        return Err(GitError::Spawn {
            kind: io::ErrorKind::BrokenPipe,
        });
    };
    let Some(stderr) = child.stderr.take() else {
        let cleanup_deadline = Instant::now()
            .checked_add(policy.cleanup_timeout)
            .unwrap_or_else(Instant::now);
        let _ = terminate_child(&mut child, &tree, cleanup_deadline);
        return Err(GitError::Spawn {
            kind: io::ErrorKind::BrokenPipe,
        });
    };
    let stdout_limited = Arc::new(AtomicBool::new(false));
    let stderr_limited = Arc::new(AtomicBool::new(false));
    let stdout_reader = spawn_reader(stdout, policy.max_output_bytes, Arc::clone(&stdout_limited));
    let stderr_reader = spawn_reader(stderr, policy.max_error_bytes, Arc::clone(&stderr_limited));
    let mut terminal_error = None;
    let status = loop {
        if cancellation.is_cancelled() {
            terminal_error = Some(GitError::Cancelled);
            break None;
        }
        if stdout_limited.load(Ordering::Acquire) || stderr_limited.load(Ordering::Acquire) {
            terminal_error = Some(GitError::OutputLimitExceeded);
            break None;
        }
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(error) => {
                terminal_error = Some(GitError::Spawn { kind: error.kind() });
                break None;
            }
        }
        if Instant::now() >= deadline {
            terminal_error = Some(GitError::TimedOut);
            break None;
        }
        thread::park_timeout(policy.poll_interval.min(Duration::from_millis(20)));
    };
    if terminal_error.is_some() {
        let cleanup_deadline = Instant::now()
            .checked_add(policy.cleanup_timeout)
            .unwrap_or_else(Instant::now);
        let cleanup_result = terminate_child(&mut child, &tree, cleanup_deadline);
        let stdout_result = join_reader_until(stdout_reader, &tree, child.id(), cleanup_deadline);
        let stderr_result = join_reader_until(stderr_reader, &tree, child.id(), cleanup_deadline);
        if cleanup_result.is_err() || stdout_result.is_err() || stderr_result.is_err() {
            return Err(GitError::CleanupTimedOut);
        }
        return match terminal_error {
            Some(error) => Err(error),
            None => Err(GitError::CleanupTimedOut),
        };
    }
    let status = status.ok_or(GitError::CleanupTimedOut)?;
    let reader_deadline = Instant::now()
        .checked_add(policy.cleanup_timeout)
        .unwrap_or_else(Instant::now);
    #[cfg(windows)]
    if tree.terminate(child.id(), reader_deadline).is_err() {
        return Err(GitError::CleanupTimedOut);
    }
    let stdout_result = join_reader_until(stdout_reader, &tree, child.id(), reader_deadline);
    let stderr_result = join_reader_until(stderr_reader, &tree, child.id(), reader_deadline);
    let stdout = stdout_result?;
    let _stderr = stderr_result?;
    if stdout_limited.load(Ordering::Acquire) || stderr_limited.load(Ordering::Acquire) {
        return Err(GitError::OutputLimitExceeded);
    }
    Ok(RawGitOutput { stdout, status })
}

/// 以有界分块读取管道；一旦输出超限便通知 supervisor 终止子进程，禁止内存无界增长。
fn spawn_reader<R: Read + Send + 'static>(
    mut reader: R,
    limit: usize,
    limited: Arc<AtomicBool>,
) -> JoinHandle<Result<Vec<u8>, GitError>> {
    thread::spawn(move || {
        let mut bytes = Vec::with_capacity(limit.min(64 * 1024));
        let mut buffer = [0_u8; 16 * 1024];
        loop {
            let count = reader
                .read(&mut buffer)
                .map_err(|error| GitError::Spawn { kind: error.kind() })?;
            if count == 0 {
                return Ok(bytes);
            }
            if bytes.len().saturating_add(count) > limit {
                let remaining = limit.saturating_sub(bytes.len());
                bytes.extend_from_slice(&buffer[..remaining]);
                limited.store(true, Ordering::Release);
                return Ok(bytes);
            }
            bytes.extend_from_slice(&buffer[..count]);
        }
    })
}

/// 仅在进程完成或终止并关闭管道后 join reader，避免父线程永久等待。
fn join_reader(reader: JoinHandle<Result<Vec<u8>, GitError>>) -> Result<Vec<u8>, GitError> {
    reader.join().map_err(|_| GitError::Spawn {
        kind: io::ErrorKind::Other,
    })?
}

/// 正常完成路径的 pipe join 同样有界，因为异常后代可能在 Git 主进程退出后仍持有继承管道。
fn join_reader_until(
    reader: JoinHandle<Result<Vec<u8>, GitError>>,
    tree: &ProcessTree,
    pid: u32,
    deadline: Instant,
) -> Result<Vec<u8>, GitError> {
    let mut reader = Some(reader);
    loop {
        let finished = reader.as_ref().is_some_and(JoinHandle::is_finished);
        if finished {
            return join_reader(reader.take().ok_or(GitError::CleanupTimedOut)?);
        }
        if Instant::now() >= deadline {
            let _ = tree.terminate(pid, deadline);
            return Err(GitError::CleanupTimedOut);
        }
        thread::park_timeout(Duration::from_millis(10));
    }
}

/// 在平台进程树边界收到终止信号后，于同一个绝对清理 Deadline 内终止完整 group/job，
/// 并回收原始 Child 句柄，避免分阶段各自扩张超时。
fn terminate_child(
    child: &mut Child,
    tree: &ProcessTree,
    deadline: Instant,
) -> Result<(), GitError> {
    let _ = tree.terminate(child.id(), deadline);
    let child_error = match child.kill() {
        Ok(()) => None,
        Err(error)
            if matches!(
                error.kind(),
                io::ErrorKind::InvalidInput | io::ErrorKind::NotFound
            ) =>
        {
            None
        }
        Err(error) => Some(error),
    };
    loop {
        match child.try_wait() {
            Ok(Some(_)) => {
                return if child_error.is_some() {
                    Err(GitError::CleanupTimedOut)
                } else {
                    Ok(())
                };
            }
            Ok(None) => {}
            Err(_) => {}
        }
        if Instant::now() >= deadline {
            return Err(GitError::CleanupTimedOut);
        }
        thread::park_timeout(Duration::from_millis(10));
    }
}

/// 在 Unix 使用私有进程组，在 Windows 使用挂起启动后接纳的 Job Object，以关闭子进程逃逸窗口。
struct ProcessTree {
    #[cfg(unix)]
    prepared: bool,
    #[cfg(windows)]
    job: *mut std::ffi::c_void,
}

impl ProcessTree {
    /// 在 spawn 前安装平台进程组边界，关闭 Git helper 越过直接子进程存活的超时竞态。
    fn prepare(command: &mut Command) -> io::Result<Self> {
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            unsafe {
                command.pre_exec(|| {
                    if setpgid(0, 0) == 0 {
                        Ok(())
                    } else {
                        Err(io::Error::last_os_error())
                    }
                });
            }
            return Ok(Self { prepared: true });
        }
        #[cfg(windows)]
        {
            command.creation_flags(CREATE_NO_WINDOW | CREATE_SUSPENDED);
            let job = unsafe { CreateJobObjectW(std::ptr::null_mut(), std::ptr::null()) };
            if job.is_null() {
                return Err(io::Error::last_os_error());
            }
            let mut limits = ExtendedLimitInformation::default();
            limits.basic.limit_flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let configured = unsafe {
                SetInformationJobObject(
                    job,
                    JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                    (&mut limits as *mut ExtendedLimitInformation).cast(),
                    std::mem::size_of::<ExtendedLimitInformation>() as u32,
                )
            } != 0;
            if !configured {
                unsafe { CloseHandle(job) };
                return Err(io::Error::last_os_error());
            }
            Ok(Self { job })
        }
        #[cfg(not(any(unix, windows)))]
        {
            let _ = command;
            Ok(Self {})
        }
    }

    /// 在恢复挂起的 Windows 子进程前将其接纳进 Job，关闭 helper 在 Job 外继承管道的 spawn-to-tree 竞态。
    fn start(&mut self, child: &Child) -> io::Result<()> {
        #[cfg(windows)]
        {
            use std::os::windows::io::AsRawHandle;
            let assigned = unsafe { AssignProcessToJobObject(self.job, child.as_raw_handle()) };
            if assigned == 0 {
                return Err(io::Error::last_os_error());
            }
            resume_suspended_process(child.id())
        }
        #[cfg(not(windows))]
        {
            let _ = child;
            Ok(())
        }
    }

    /// 只终止本次启动的进程组或进程树；Windows 在主进程退出后再调用一次，防止继承输出管道越过 Git 生命周期。
    fn terminate(&self, pid: u32, deadline: Instant) -> io::Result<()> {
        #[cfg(unix)]
        {
            if self.prepared {
                let mut first = None;
                for signal in [SIGTERM, SIGKILL] {
                    if unsafe { kill(-(pid as i32), signal) } != 0 {
                        let error = io::Error::last_os_error();
                        if error.kind() != io::ErrorKind::NotFound {
                            first.get_or_insert(error);
                        }
                    }
                }
                if let Some(error) = first {
                    return Err(error);
                }
            }
            return Ok(());
        }
        #[cfg(windows)]
        {
            let _ = (pid, deadline);
            if self.job.is_null() {
                return Ok(());
            }
            let terminated = unsafe { TerminateJobObject(self.job, 1) } != 0;
            if terminated {
                Ok(())
            } else {
                Err(io::Error::last_os_error())
            }
        }
        #[cfg(not(any(unix, windows)))]
        {
            let _ = pid;
            Ok(())
        }
    }
}

#[cfg(windows)]
impl Drop for ProcessTree {
    /// 关闭 kill-on-close Job，确保即使清理报错，adapter 返回后也不会遗留持有继承管道的 helper。
    fn drop(&mut self) {
        if !self.job.is_null() {
            unsafe { CloseHandle(self.job) };
            self.job = std::ptr::null_mut();
        }
    }
}

#[cfg(unix)]
const SIGTERM: i32 = 15;
#[cfg(unix)]
const SIGKILL: i32 = 9;

#[cfg(unix)]
unsafe extern "C" {
    fn kill(pid: i32, signal: i32) -> i32;
    fn setpgid(pid: i32, pgid: i32) -> i32;
}

#[cfg(windows)]
const CREATE_SUSPENDED: u32 = 0x0000_0004;
#[cfg(windows)]
const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: u32 = 9;
#[cfg(windows)]
const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x2000;
#[cfg(windows)]
const TH32CS_SNAPTHREAD: u32 = 0x0000_0004;
#[cfg(windows)]
const THREAD_SUSPEND_RESUME: u32 = 0x0002;
#[cfg(windows)]
const INVALID_HANDLE_VALUE: *mut std::ffi::c_void = (-1_isize) as *mut std::ffi::c_void;

#[cfg(windows)]
#[repr(C)]
#[derive(Default)]
struct BasicLimitInformation {
    per_process_user_time: i64,
    per_job_user_time: i64,
    limit_flags: u32,
    minimum_working_set_size: usize,
    maximum_working_set_size: usize,
    active_process_limit: u32,
    affinity: usize,
    priority_class: u32,
    scheduling_class: u32,
}

#[cfg(windows)]
#[repr(C)]
#[derive(Default)]
struct IoCounters {
    read_operations: u64,
    write_operations: u64,
    other_operations: u64,
    read_bytes: u64,
    write_bytes: u64,
    other_bytes: u64,
}

#[cfg(windows)]
#[repr(C)]
#[derive(Default)]
struct ExtendedLimitInformation {
    basic: BasicLimitInformation,
    io: IoCounters,
    process_memory_limit: usize,
    job_memory_limit: usize,
    peak_process_memory_used: usize,
    peak_job_memory_used: usize,
}

#[cfg(windows)]
#[repr(C)]
#[derive(Default)]
struct ThreadEntry32 {
    size: u32,
    usage: u32,
    thread_id: u32,
    owner_process_id: u32,
    base_priority: i32,
    delta_priority: i32,
    flags: u32,
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn AssignProcessToJobObject(job: *mut std::ffi::c_void, process: *mut std::ffi::c_void) -> i32;
    fn CloseHandle(handle: *mut std::ffi::c_void) -> i32;
    fn CreateJobObjectW(
        attributes: *mut std::ffi::c_void,
        name: *const u16,
    ) -> *mut std::ffi::c_void;
    fn SetInformationJobObject(
        job: *mut std::ffi::c_void,
        info_class: u32,
        info: *mut std::ffi::c_void,
        length: u32,
    ) -> i32;
    fn TerminateJobObject(job: *mut std::ffi::c_void, exit_code: u32) -> i32;
    fn CreateToolhelp32Snapshot(flags: u32, process_id: u32) -> *mut std::ffi::c_void;
    fn Thread32First(snapshot: *mut std::ffi::c_void, entry: *mut ThreadEntry32) -> i32;
    fn Thread32Next(snapshot: *mut std::ffi::c_void, entry: *mut ThreadEntry32) -> i32;
    fn OpenThread(access: u32, inherit_handle: i32, thread_id: u32) -> *mut std::ffi::c_void;
    fn ResumeThread(thread: *mut std::ffi::c_void) -> u32;
}

#[cfg(windows)]
/// 在 Job 接纳后恢复主线程，同时不向只读 adapter 的其余部分暴露原始 Windows 进程句柄。
fn resume_suspended_process(process_id: u32) -> io::Result<()> {
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
    if snapshot.is_null() || snapshot == INVALID_HANDLE_VALUE {
        return Err(io::Error::last_os_error());
    }
    let mut entry = ThreadEntry32 {
        size: std::mem::size_of::<ThreadEntry32>() as u32,
        ..ThreadEntry32::default()
    };
    let mut found = unsafe { Thread32First(snapshot, &mut entry) } != 0;
    let mut result = Err(io::Error::new(
        io::ErrorKind::NotFound,
        "child thread not found",
    ));
    while found {
        if entry.owner_process_id == process_id {
            let thread = unsafe { OpenThread(THREAD_SUSPEND_RESUME, 0, entry.thread_id) };
            if !thread.is_null() {
                let previous = unsafe { ResumeThread(thread) };
                unsafe { CloseHandle(thread) };
                result = if previous == u32::MAX {
                    Err(io::Error::last_os_error())
                } else {
                    Ok(())
                };
            }
            break;
        }
        found = unsafe { Thread32Next(snapshot, &mut entry) } != 0;
    }
    unsafe { CloseHandle(snapshot) };
    result
}

// 进程树测试保留为私有子模块，既能验证内部清理路径，也不会增加生产可见 API。
