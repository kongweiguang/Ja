// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! 平台相关的 sidecar 进程树所有权与终止策略。
//!
//! supervisor 只负责生命周期与握手；Job/process-group 的 ABI 细节集中在这里，
//! 这样新增平台不会把平台清理分支散落到协议代码中。

use std::io;
use std::process::{Child, Command, ExitStatus};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

/// 把 OS child 控制操作抽成可注入边界，故障测试可以验证清理路径不会无限等待。
pub(crate) trait ProcessBackend: Send + Sync {
    /// 发送终止请求；失败不能让 caller 放弃后续 bounded reap。
    fn kill(&self, child: &mut Child) -> io::Result<()>;

    /// 以非阻塞方式读取退出事实，避免在生命周期锁内等待未知时长。
    fn try_wait(&self, child: &mut Child) -> io::Result<Option<ExitStatus>>;
}

/// 生产 backend 直接调用标准库的 child handle，不通过 PID 重开进程句柄。
pub(super) struct SystemProcessBackend;

impl ProcessBackend for SystemProcessBackend {
    /// 使用仍由当前 owner 持有的 Child handle 终止 leader，避免 PID 复用误杀。
    fn kill(&self, child: &mut Child) -> io::Result<()> {
        child.kill()
    }

    /// 使用 Child::try_wait 保留原始 handle 所有权并保持调用非阻塞。
    fn try_wait(&self, child: &mut Child) -> io::Result<Option<ExitStatus>> {
        child.try_wait()
    }
}

/// 在绝对 deadline 内终止并回收 child；deadline 到期仍返回而不调用无界 wait。
pub(crate) fn bounded_reap_child(child: &mut Child, deadline: Instant) -> io::Result<()> {
    bounded_reap_child_with(child, deadline, &SystemProcessBackend)
}

/// 用注入 backend 复用同一收口算法，覆盖 kill/wait 故障而不依赖真实 sleep。
pub(crate) fn bounded_reap_child_with<B: ProcessBackend>(
    child: &mut Child,
    deadline: Instant,
    backend: &B,
) -> io::Result<()> {
    // 保留 kill failure 作为诊断；若 child 并发退出且已无存活进程，仍视为成功回收。
    let mut first_error = backend.kill(child).err();
    loop {
        match backend.try_wait(child) {
            Ok(Some(_status)) => return Ok(()),
            Ok(None) => {}
            Err(error) => {
                first_error.get_or_insert(error);
            }
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(first_error.unwrap_or_else(|| {
                io::Error::new(io::ErrorKind::TimedOut, "child reap deadline exceeded")
            }));
        }
        // 有界 park 只在非阻塞 probe 间让出 CPU；终止由绝对 deadline 而非 park 间隔控制。
        thread::park_timeout(remaining.min(Duration::from_millis(10)));
    }
}

#[cfg(unix)]
use std::os::unix::process::CommandExt;
#[cfg(windows)]
use std::os::windows::process::CommandExt;
#[cfg(windows)]
use std::sync::Mutex;
#[cfg(windows)]
use std::sync::atomic::AtomicPtr;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
#[cfg(windows)]
const CREATE_SUSPENDED: u32 = 0x0000_0004;

#[cfg(windows)]
pub(crate) trait JobBackend: Send + Sync {
    /// 将 exact child handle 加入已配置 KILL_ON_JOB_CLOSE 的 Job。
    fn assign(&self, job: *mut std::ffi::c_void, process: *mut std::ffi::c_void) -> io::Result<()>;

    /// 恢复 suspended child 的 primary thread，绑定成功后才允许执行。
    fn resume(&self, process_id: u32) -> io::Result<()>;

    /// 终止 Job；实现可在测试中注入失败而不触碰真实进程树。
    fn terminate(&self) -> io::Result<()>;

    /// 关闭 Job handle；失败时调用方必须保留 raw ownership 以便重试。
    fn close_job(&self, job: *mut std::ffi::c_void) -> io::Result<()>;

    /// 通过 assign 阶段保留的 exact process handle 终止 leader。
    fn terminate_process(&self, process: *mut std::ffi::c_void) -> io::Result<()>;
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum JobOperation {
    Assign,
    Resume,
    Terminate,
    Close,
    TerminateProcess,
}

#[cfg(windows)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct JobErrorRecord {
    pub(crate) operation: JobOperation,
    pub(crate) kind: io::ErrorKind,
}

#[cfg(windows)]
struct SystemJobBackend(*mut std::ffi::c_void);

#[cfg(windows)]
unsafe impl Send for SystemJobBackend {}

#[cfg(windows)]
unsafe impl Sync for SystemJobBackend {}

#[cfg(windows)]
impl JobBackend for SystemJobBackend {
    /// 调用 AssignProcessToJobObject，失败交由 retained handle 收口。
    fn assign(&self, job: *mut std::ffi::c_void, process: *mut std::ffi::c_void) -> io::Result<()> {
        let ok = unsafe { AssignProcessToJobObject(job, process) };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    /// 通过线程快照恢复原始 child，而不是按 PID 重开进程句柄。
    fn resume(&self, process_id: u32) -> io::Result<()> {
        resume_suspended_process(process_id)
    }

    /// 调用 Job API，错误交给 retained child handle 和 bounded reap 收口。
    fn terminate(&self) -> io::Result<()> {
        let ok = unsafe { TerminateJobObject(self.0, 1) };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    /// 关闭 KILL_ON_JOB_CLOSE handle，并将失败传播给树收口诊断。
    fn close_job(&self, job: *mut std::ffi::c_void) -> io::Result<()> {
        let ok = unsafe { CloseHandle(job) };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    /// 终止仍由当前 guard 持有的 exact leader handle，避免 PID 重开。
    fn terminate_process(&self, process: *mut std::ffi::c_void) -> io::Result<()> {
        let ok = unsafe { TerminateProcess(process, 1) };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }
}

#[cfg(unix)]
use std::sync::atomic::AtomicI32;
#[cfg(target_os = "linux")]
use std::sync::atomic::AtomicU64;

#[cfg(unix)]
#[derive(Clone)]
pub(super) struct ProcessTreeGuard {
    pid: Arc<AtomicI32>,
    alive: Arc<AtomicBool>,
    failed: Arc<AtomicBool>,
    #[cfg(target_os = "linux")]
    leader_start_time: Arc<AtomicU64>,
}

#[cfg(unix)]
impl ProcessTreeGuard {
    /// exec 前建立独立 process group，使 killpg 覆盖 sidecar 的全部后代。
    pub(super) fn prepare(command: &mut Command) -> io::Result<Self> {
        let guard = Self {
            pid: Arc::new(AtomicI32::new(0)),
            alive: Arc::new(AtomicBool::new(true)),
            failed: Arc::new(AtomicBool::new(false)),
            #[cfg(target_os = "linux")]
            leader_start_time: Arc::new(AtomicU64::new(0)),
        };
        unsafe {
            command.pre_exec(|| {
                if setpgid(0, 0) == 0 {
                    Ok(())
                } else {
                    Err(io::Error::last_os_error())
                }
            });
        }
        Ok(guard)
    }

    /// 记录 child pid，后续负 pid signal 指向整个 process group。
    pub(super) fn assign(&self, child: &Child) -> io::Result<()> {
        self.pid.store(child.id() as i32, Ordering::Release);
        #[cfg(target_os = "linux")]
        self.leader_start_time.store(
            linux_process_start_time(child.id())
                .ok_or_else(|| io::Error::other("sidecar leader identity unavailable"))?,
            Ordering::Release,
        );
        Ok(())
    }

    /// Unix 不需要 suspended resume，但保留阶段接口与 Windows tree adapter 对齐。
    pub(super) fn resume(&self, _child: &Child) -> io::Result<()> {
        Ok(())
    }

    /// 先 TERM 再 KILL，保证超时收口不会留下 grandchild。
    pub(super) fn terminate(&self) -> io::Result<()> {
        if self.failed.load(Ordering::Acquire) {
            return Err(io::Error::other("process group termination failed"));
        }
        let pid = self.pid.load(Ordering::Acquire);
        if self.alive.swap(false, Ordering::AcqRel) && pid > 0 && self.owns_group(pid) {
            let mut first_error = None;
            unsafe {
                if kill(-pid, SIGTERM) != 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() != io::ErrorKind::NotFound {
                        first_error = Some(error);
                    }
                }
                if kill(-pid, SIGKILL) != 0 {
                    let error = io::Error::last_os_error();
                    if error.kind() != io::ErrorKind::NotFound {
                        first_error.get_or_insert(error);
                    }
                }
            }
            if let Some(error) = first_error {
                self.failed.store(true, Ordering::Release);
                return Err(error);
            }
        }
        Ok(())
    }

    /// leader 退出不代表 group 退出，因此仍立即终止整个 process group。
    pub(super) fn mark_exited(&self) -> io::Result<()> {
        // leader 退出不能证明 descendants 已退出；guard 仍持有 PID 时立即关闭 Unix
        // process group 的资源所有权。
        self.terminate()
    }

    /// Linux 在 killpg 前用 PID start time 证明 pgid 仍属于本次 spawn 的 leader，避免 PID 复用误杀。
    #[cfg(target_os = "linux")]
    fn owns_group(&self, pid: i32) -> bool {
        let expected = self.leader_start_time.load(Ordering::Acquire);
        if expected == 0 {
            return false;
        }
        match linux_process_start_time(pid as u32) {
            Some(actual) => actual == expected,
            // 已消失的 leader 仍可能拥有存活 descendant group；只要成员存在，kernel
            // 就不会复用该 pgid。
            None => (unsafe { kill(-pid, 0) }) == 0,
        }
    }

    /// macOS/其它 Unix 的 process group 继续由 guard 持有，直到 `mark_exited` 明确释放。
    #[cfg(all(unix, not(target_os = "linux")))]
    fn owns_group(&self, _pid: i32) -> bool {
        true
    }
}

#[cfg(target_os = "linux")]
/// 读取内核分配的 process start time，避免 leader PID 复用后误杀新 process group。
fn linux_process_start_time(pid: u32) -> Option<u64> {
    let text = std::fs::read_to_string(format!("/proc/{pid}/stat")).ok()?;
    let after_name = text.rsplit_once(") ")?.1;
    after_name
        .split_whitespace()
        .nth(19)
        .and_then(|value| value.parse().ok())
}

#[cfg(unix)]
impl Drop for ProcessTreeGuard {
    /// 任何 early return 都必须清理 process group，避免测试/窗口关闭泄露进程。
    fn drop(&mut self) {
        let _ = self.terminate();
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
#[derive(Clone)]
pub(crate) struct ProcessTreeGuard {
    pub(crate) job: Arc<WinJobHandle>,
    pub(crate) backend: Arc<dyn JobBackend>,
    pub(crate) child_handle: Arc<Mutex<Option<WinProcessHandle>>>,
    alive: Arc<AtomicBool>,
    failed: Arc<AtomicBool>,
    pub(crate) errors: Arc<Mutex<Vec<JobErrorRecord>>>,
}

#[cfg(windows)]
pub(crate) struct WinJobHandle {
    raw: AtomicPtr<std::ffi::c_void>,
}

#[cfg(windows)]
unsafe impl Send for WinJobHandle {}
#[cfg(windows)]
unsafe impl Sync for WinJobHandle {}

#[cfg(windows)]
impl WinJobHandle {
    /// 保存可立即关闭的 Job handle，允许失败路径触发 KILL_ON_JOB_CLOSE。
    fn new(raw: *mut std::ffi::c_void) -> Self {
        Self {
            raw: AtomicPtr::new(raw),
        }
    }

    /// 读取当前 Job handle；空句柄表示此前已执行 failure close。
    pub(crate) fn raw(&self) -> *mut std::ffi::c_void {
        self.raw.load(Ordering::Acquire)
    }

    /// 关闭 Job 并在 OS 报错时恢复 raw ownership，避免错误路径泄漏或静默丢句柄。
    fn close_now(&self) -> io::Result<()> {
        let raw = self.raw.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if !raw.is_null() {
            let result = unsafe { CloseHandle(raw) };
            if result == 0 {
                let error = io::Error::last_os_error();
                let _ = self.raw.compare_exchange(
                    std::ptr::null_mut(),
                    raw,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                );
                return Err(error);
            }
        }
        Ok(())
    }
}

#[cfg(windows)]
pub(crate) struct WinProcessHandle(*mut std::ffi::c_void);

#[cfg(windows)]
unsafe impl Send for WinProcessHandle {}
#[cfg(windows)]
unsafe impl Sync for WinProcessHandle {}

#[cfg(windows)]
impl Drop for WinProcessHandle {
    /// 关闭 assign 时复制的原始 process handle，避免依据可复用 PID 重开句柄。
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe { CloseHandle(self.0) };
        }
    }
}

#[cfg(windows)]
impl ProcessTreeGuard {
    /// 以 suspended child 创建 Job，确保 primary thread resume 前已绑定完整树策略。
    pub(crate) fn prepare(command: &mut Command) -> io::Result<Self> {
        command.creation_flags(CREATE_NO_WINDOW | CREATE_SUSPENDED);
        let handle = unsafe { CreateJobObjectW(std::ptr::null_mut(), std::ptr::null()) };
        if handle.is_null() {
            return Err(io::Error::last_os_error());
        }
        let mut limits = ExtendedLimitInformation::default();
        limits.basic.limit_flags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok = unsafe {
            SetInformationJobObject(
                handle,
                JOB_OBJECT_EXTENDED_LIMIT_INFORMATION,
                (&mut limits as *mut ExtendedLimitInformation).cast(),
                std::mem::size_of::<ExtendedLimitInformation>() as u32,
            )
        };
        if ok == 0 {
            unsafe { CloseHandle(handle) };
            return Err(io::Error::last_os_error());
        }
        Ok(Self {
            job: Arc::new(WinJobHandle::new(handle)),
            backend: Arc::new(SystemJobBackend(handle)),
            child_handle: Arc::new(Mutex::new(None)),
            alive: Arc::new(AtomicBool::new(true)),
            failed: Arc::new(AtomicBool::new(false)),
            errors: Arc::new(Mutex::new(Vec::new())),
        })
    }

    /// 暂存真实 API 错误，避免 cleanup 只返回泛化的 tree failure。
    fn record_error(&self, operation: JobOperation, error: &io::Error) {
        let Ok(mut errors) = self.errors.lock() else {
            // 诊断账本已不可信时保持 failed=true；cleanup 仍由 OS handle 执行，不能读取
            // 可能只写入一半的历史记录继续推断资源状态。
            self.failed.store(true, Ordering::Release);
            return;
        };
        errors.push(JobErrorRecord {
            operation,
            kind: error.kind(),
        });
    }

    /// 将 child 加入 Job，保证任何 descendant 都受同一 kill policy 约束。
    pub(crate) fn assign(&self, child: &Child) -> io::Result<()> {
        let job = self.job.raw();
        if job.is_null() {
            return Err(io::Error::other("job handle closed"));
        }
        let result = self.assign_with_backend(child, job, self.backend.as_ref());
        if let Err(error) = &result {
            self.record_error(JobOperation::Assign, error);
        }
        result
    }

    /// 只在 Job 绑定成功后恢复 primary thread，消除 spawn→assign 竞态。
    pub(crate) fn resume(&self, child: &Child) -> io::Result<()> {
        let job = self.job.raw();
        if job.is_null() {
            return Err(io::Error::other("job handle closed"));
        }
        let result = self.resume_with_backend(child, self.backend.as_ref());
        if let Err(error) = &result {
            self.record_error(JobOperation::Resume, error);
        }
        result
    }

    /// 终止 Job 中全部进程；alive 标志使 monitor/Drop 重复调用安全。
    pub(crate) fn terminate(&self) -> io::Result<()> {
        if self.failed.load(Ordering::Acquire) {
            if let Err(error) = self.close_job_with_backend(self.backend.as_ref()) {
                self.record_error(JobOperation::Close, &error);
            }
            if let Err(error) = self.terminate_direct_child_with_backend(self.backend.as_ref()) {
                self.record_error(JobOperation::TerminateProcess, &error);
            }
            return Err(io::Error::other("job termination failed"));
        }
        if self.alive.swap(false, Ordering::AcqRel) {
            let job = self.job.raw();
            if job.is_null() {
                self.failed.store(true, Ordering::Release);
                if let Err(error) = self.terminate_direct_child_with_backend(self.backend.as_ref())
                {
                    self.record_error(JobOperation::TerminateProcess, &error);
                }
                return Err(io::Error::other("job handle closed"));
            }
            if let Err(error) = self.terminate_with_backend(self.backend.as_ref()) {
                self.failed.store(true, Ordering::Release);
                return Err(error);
            }
        }
        Ok(())
    }

    /// 让 fault-injection 与生产 Job API 共用同一失败收口语义。
    fn terminate_with_backend<B: JobBackend + ?Sized>(&self, backend: &B) -> io::Result<()> {
        let job = self.job.raw();
        let error = match backend.terminate() {
            Ok(()) => {
                if let Err(error) = self.close_job_with_backend(backend) {
                    self.record_error(JobOperation::Close, &error);
                    self.failed.store(true, Ordering::Release);
                    if let Err(process_error) = self.terminate_direct_child_with_backend(backend) {
                        self.record_error(JobOperation::TerminateProcess, &process_error);
                    }
                    return Err(error);
                }
                return Ok(());
            }
            Err(error) => error,
        };
        self.record_error(JobOperation::Terminate, &error);
        self.failed.store(true, Ordering::Release);
        // 即使 Job API 失败也尝试关闭 KILL_ON_JOB_CLOSE handle；close 失败时保留 raw
        // ownership，供稍后重试。
        if let Err(close_error) = self.close_job_with_backend(backend) {
            self.record_error(JobOperation::Close, &close_error);
        }
        // 保留的精确 leader handle 是最终所有权路径，必须独立于 Job/CloseHandle 结果执行。
        if let Err(process_error) = self.terminate_direct_child_with_backend(backend) {
            self.record_error(JobOperation::TerminateProcess, &process_error);
        }
        if job.is_null() {
            self.record_error(
                JobOperation::Terminate,
                &io::Error::other("job handle closed"),
            );
        }
        Err(error)
    }

    /// 关闭 Job；若 OS 报错则恢复 raw handle 所有权，以便后续有界重试。
    fn close_job_with_backend<B: JobBackend + ?Sized>(&self, backend: &B) -> io::Result<()> {
        let raw = self.job.raw.swap(std::ptr::null_mut(), Ordering::AcqRel);
        if raw.is_null() {
            return Ok(());
        }
        match backend.close_job(raw) {
            Ok(()) => Ok(()),
            Err(error) => {
                let _ = self.job.raw.compare_exchange(
                    std::ptr::null_mut(),
                    raw,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                );
                Err(error)
            }
        }
    }

    /// 共享 AssignProcessToJobObject 流程，便于注入 assign failure 回归。
    fn assign_with_backend<B: JobBackend + ?Sized>(
        &self,
        child: &Child,
        job: *mut std::ffi::c_void,
        backend: &B,
    ) -> io::Result<()> {
        use std::os::windows::io::AsRawHandle;
        let mut duplicate = std::ptr::null_mut();
        let current = unsafe { GetCurrentProcess() };
        let copied = unsafe {
            DuplicateHandle(
                current,
                child.as_raw_handle(),
                current,
                &mut duplicate,
                0,
                0,
                DUPLICATE_SAME_ACCESS,
            )
        };
        if copied == 0 || duplicate.is_null() {
            return Err(io::Error::last_os_error());
        }
        *self.child_handle.lock().map_err(|_| {
            // duplicated handle 尚未发布给 guard；锁中毒时让局部 RAII handle 立即关闭，
            // 并拒绝 assign，避免覆盖可能不完整的旧 owner。
            io::Error::other("process handle owner is unavailable")
        })? = Some(WinProcessHandle(duplicate));
        backend.assign(job, child.as_raw_handle())
    }

    /// 共享 resume 流程，注入 backend 可覆盖 suspended 失败边界。
    fn resume_with_backend<B: JobBackend + ?Sized>(
        &self,
        child: &Child,
        backend: &B,
    ) -> io::Result<()> {
        backend.resume(child.id())
    }

    /// Job API 失败时终止 assign 阶段保留的原始 leader handle，保证不误杀 PID 重用者。
    pub(crate) fn terminate_direct_child_with_backend<B: JobBackend + ?Sized>(
        &self,
        backend: &B,
    ) -> io::Result<()> {
        let child_handle = self
            .child_handle
            .lock()
            .map_err(|_| io::Error::other("process handle owner is unavailable"))?;
        if let Some(process) = child_handle.as_ref() {
            return backend.terminate_process(process.0);
        }
        Ok(())
    }

    /// leader 退出不代表 descendants 退出，因此 monitor 发布事件前先收口 Job。
    pub(crate) fn mark_exited(&self) -> io::Result<()> {
        // leader 退出不能证明 descendants 已退出；立即终止 Job，不等待 supervisor poll
        // 或 Drop 执行。
        self.terminate()
    }
}

#[cfg(windows)]
impl Drop for ProcessTreeGuard {
    /// 最后一个 guard 所有者负责报告并收口 raw Job handle，避免 Drop 静默吞掉 CloseHandle fault。
    fn drop(&mut self) {
        if Arc::strong_count(&self.job) != 1 || self.job.raw().is_null() {
            return;
        }
        if let Err(error) = self.close_job_with_backend(self.backend.as_ref()) {
            self.record_error(JobOperation::Close, &error);
        }
        if let Err(error) = self.job.close_now() {
            self.record_error(JobOperation::Close, &error);
        }
    }
}

#[cfg(windows)]
const JOB_OBJECT_EXTENDED_LIMIT_INFORMATION: u32 = 9;
#[cfg(windows)]
const JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: u32 = 0x2000;

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
#[link(name = "kernel32")]
unsafe extern "system" {
    pub(crate) fn AssignProcessToJobObject(
        job: *mut std::ffi::c_void,
        process: *mut std::ffi::c_void,
    ) -> i32;
    pub(crate) fn CloseHandle(handle: *mut std::ffi::c_void) -> i32;
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
    pub(crate) fn TerminateJobObject(job: *mut std::ffi::c_void, exit_code: u32) -> i32;
    /// 复制 exact leader handle 作为 Job API 故障后的最终回收 owner，禁止按 PID 重开。
    fn DuplicateHandle(
        source_process: *mut std::ffi::c_void,
        source_handle: *mut std::ffi::c_void,
        target_process: *mut std::ffi::c_void,
        target_handle: *mut *mut std::ffi::c_void,
        desired_access: u32,
        inherit_handle: i32,
        options: u32,
    ) -> i32;
    fn GetCurrentProcess() -> *mut std::ffi::c_void;
    pub(crate) fn TerminateProcess(process: *mut std::ffi::c_void, exit_code: u32) -> i32;
    fn CreateToolhelp32Snapshot(flags: u32, process_id: u32) -> *mut std::ffi::c_void;
    fn Thread32First(snapshot: *mut std::ffi::c_void, entry: *mut ThreadEntry32) -> i32;
    fn Thread32Next(snapshot: *mut std::ffi::c_void, entry: *mut ThreadEntry32) -> i32;
    fn OpenThread(access: u32, inherit_handle: i32, thread_id: u32) -> *mut std::ffi::c_void;
    fn ResumeThread(thread: *mut std::ffi::c_void) -> u32;
}

#[cfg(windows)]
const TH32CS_SNAPTHREAD: u32 = 0x0000_0004;
#[cfg(windows)]
const THREAD_SUSPEND_RESUME: u32 = 0x0002;
#[cfg(windows)]
const DUPLICATE_SAME_ACCESS: u32 = 0x0000_0002;
#[cfg(windows)]
const INVALID_HANDLE_VALUE: *mut std::ffi::c_void = (-1_isize) as *mut std::ffi::c_void;

#[cfg(windows)]
#[repr(C)]
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
impl Default for ThreadEntry32 {
    /// 初始化 Toolhelp 结构体 size 字段，Windows API 以此判断 ABI 版本。
    fn default() -> Self {
        Self {
            size: std::mem::size_of::<Self>() as u32,
            usage: 0,
            thread_id: 0,
            owner_process_id: 0,
            base_priority: 0,
            delta_priority: 0,
            flags: 0,
        }
    }
}

#[cfg(windows)]
/// 找到 suspended child 的 primary thread 并恢复，避免 Job 绑定竞态。
pub(crate) fn resume_suspended_process(process_id: u32) -> io::Result<()> {
    let snapshot = unsafe { CreateToolhelp32Snapshot(TH32CS_SNAPTHREAD, 0) };
    if snapshot.is_null() || snapshot == INVALID_HANDLE_VALUE {
        return Err(io::Error::last_os_error());
    }
    let mut entry = ThreadEntry32::default();
    let mut found = unsafe { Thread32First(snapshot, &mut entry) } != 0;
    let mut result = Err(io::Error::last_os_error());
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

#[cfg(not(any(unix, windows)))]
compile_error!("ja-runtime requires Windows or Unix process-tree ownership");
