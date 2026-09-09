// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

//! Sidecar 启动配置与原生进程边界校验。

use crate::app_server_process::error::AppServerProcessError;
use crate::app_server_process::lifecycle::RestartPolicy;
use crate::app_server_process::protocol;
use crate::app_server_process::protocol::{
    Limits, MAX_READY_TIMEOUT, MAX_SHUTDOWN_TIMEOUT, allowed_env_name, contains_secret_marker,
    validate_initialize_params,
};
use std::collections::BTreeMap;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
#[cfg(windows)]
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[cfg(windows)]
use std::ffi::c_void;
#[cfg(windows)]
use std::os::windows::ffi::OsStrExt;

/// 启动参数只允许显式 run directory 与 allowlist 环境，不继承桌面进程环境。
/// 不可变启动策略只保存进程路径、限制与传输策略；配置和凭据仍由 Java owner 持有。
/// 四个目录角色保持独立，避免历史 `run`/`data` 别名重新变成持久数据库路径。
#[derive(Clone)]
pub struct SidecarConfig {
    pub executable: PathBuf,
    pub args: Vec<OsString>,
    pub home_dir: PathBuf,
    pub data_dir: PathBuf,
    pub run_dir: PathBuf,
    pub log_dir: PathBuf,
    /// 可选 workspace 根；配置后 run_dir 必须位于其外部，避免 host 自身目录被 sidecar 复用。
    pub workspace_root: Option<PathBuf>,
    pub env: BTreeMap<OsString, OsString>,
    pub limits: Limits,
    pub ready_timeout: Duration,
    pub shutdown_timeout: Duration,
    pub(crate) restart: RestartPolicy,
    canonical_executable: PathBuf,
    canonical_home_dir: PathBuf,
    canonical_data_dir: PathBuf,
    canonical_run_dir: PathBuf,
    canonical_log_dir: PathBuf,
    canonical_workspace_root: Option<PathBuf>,
    #[cfg(windows)]
    pub(crate) executable_identity: Arc<Mutex<Option<ExecutableIdentity>>>,
}

impl std::fmt::Debug for SidecarConfig {
    /// Debug 投影主动隐藏所有启动路径；配置和凭据内容不会进入 Rust 策略或诊断。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("SidecarConfig")
            .field("executable", &"REDACTED")
            .field("args", &self.args.len())
            .field("run_dir", &"REDACTED")
            .field("env", &self.env.len())
            .field("limits", &self.limits)
            .field("home_dir", &"REDACTED")
            .field("data_dir", &"REDACTED")
            .field("log_dir", &"REDACTED")
            .field("ready_timeout", &self.ready_timeout)
            .field("shutdown_timeout", &self.shutdown_timeout)
            .field("restart", &self.restart)
            .finish()
    }
}

/// 只保留平台矩阵验证过的原生 sidecar 环境；`env_clear` 后继续排除凭据、代理和任意
/// 用户环境。`PATH`、`ComSpec` 与 PowerShell 模块分析缓存是非 secret runtime 输入，
/// 临时目录别名统一指向 sidecar 已拥有的 run directory，不能继承用户 temp。
fn default_runtime_environment(run_dir: &Path) -> BTreeMap<OsString, OsString> {
    default_runtime_environment_from(run_dir, |name| std::env::var_os(name))
}

/// 从只读 lookup 构造固定环境，使测试无需修改 host process 即可注入输入；生产仍只读取
/// 当前进程，且所有输出必须通过同一 allowlist。
pub(crate) fn default_runtime_environment_from<F>(
    run_dir: &Path,
    lookup: F,
) -> BTreeMap<OsString, OsString>
where
    F: for<'a> Fn(&'a str) -> Option<OsString>,
{
    let mut environment = BTreeMap::new();
    #[cfg(windows)]
    {
        for name in ["SystemRoot", "PATH", "ComSpec", "PSModuleAnalysisCachePath"] {
            if let Some(value) = lookup(name) {
                environment.insert(OsString::from(name), value);
            }
        }
        let temporary = run_dir.as_os_str().to_owned();
        environment.insert(OsString::from("TEMP"), temporary.clone());
        environment.insert(OsString::from("TMP"), temporary);
    }
    #[cfg(target_os = "macos")]
    {
        // Java 的 macOS 临时目录属性来自 TMPDIR；在真实 native fixture 证明必要前，
        // 不继承 HOME 与 locale，避免扩大 sidecar 环境能力。
        if let Some(value) = lookup("PATH") {
            environment.insert(OsString::from("PATH"), value);
        }
        environment.insert(OsString::from("TMPDIR"), run_dir.as_os_str().to_owned());
    }
    environment
}

impl SidecarConfig {
    /// 创建 home/data/run/log 相互独立的 canonical 边界；本层不读取文件内容，也不接受
    /// 凭据字节，避免进程策略复制 Java 配置事实。
    pub fn with_directories(
        executable: impl Into<PathBuf>,
        home_dir: impl Into<PathBuf>,
        data_dir: impl Into<PathBuf>,
        run_dir: impl Into<PathBuf>,
        log_dir: impl Into<PathBuf>,
    ) -> Self {
        let limits = Limits::default();
        let executable = executable.into();
        let home_dir = home_dir.into();
        let data_dir = data_dir.into();
        let run_dir = run_dir.into();
        let log_dir = log_dir.into();
        let canonical_run_dir = fs::canonicalize(&run_dir).unwrap_or_else(|_| run_dir.clone());
        Self {
            executable: fs::canonicalize(&executable).unwrap_or_else(|_| executable.clone()),
            args: Vec::new(),
            home_dir: fs::canonicalize(&home_dir).unwrap_or_else(|_| home_dir.clone()),
            data_dir: fs::canonicalize(&data_dir).unwrap_or_else(|_| data_dir.clone()),
            run_dir: canonical_run_dir.clone(),
            log_dir: fs::canonicalize(&log_dir).unwrap_or_else(|_| log_dir.clone()),
            workspace_root: None,
            env: default_runtime_environment(&canonical_run_dir),
            limits,
            ready_timeout: Duration::from_secs(10),
            shutdown_timeout: Duration::from_secs(3),
            restart: RestartPolicy::default(),
            canonical_executable: fs::canonicalize(&executable).unwrap_or(executable),
            canonical_home_dir: fs::canonicalize(&home_dir).unwrap_or(home_dir),
            canonical_data_dir: fs::canonicalize(&data_dir).unwrap_or(data_dir),
            canonical_run_dir,
            canonical_log_dir: fs::canonicalize(&log_dir).unwrap_or(log_dir),
            canonical_workspace_root: None,
            #[cfg(windows)]
            executable_identity: Arc::new(Mutex::new(None)),
        }
    }

    /// 设置 workspace containment 根并同时冻结 canonical identity，避免 spawn 时解析到替换目录。
    pub fn set_workspace_root(&mut self, workspace_root: Option<PathBuf>) {
        self.workspace_root = workspace_root;
        self.canonical_workspace_root = self
            .workspace_root
            .as_ref()
            .and_then(|root| fs::canonicalize(root).ok());
    }

    /// 返回构造时冻结的 executable，spawn 不再信任外部可变 PathBuf。
    pub(super) fn canonical_executable(&self) -> &PathBuf {
        &self.canonical_executable
    }

    /// 返回构造时冻结的 run directory，避免符号链接替换改变 sidecar 工作目录。
    pub(super) fn canonical_run_dir(&self) -> &PathBuf {
        &self.canonical_run_dir
    }

    /// 在 spawn 前拒绝相对路径、继承环境和疑似 secret 参数，防止 sidecar 越界获得隐式权限。
    pub fn validate(&self) -> Result<(), AppServerProcessError> {
        if !self.executable.is_absolute()
            || !self.run_dir.is_absolute()
            || !self.run_dir.is_dir()
            || self.ready_timeout.is_zero()
            || self.shutdown_timeout.is_zero()
            || self.ready_timeout > MAX_READY_TIMEOUT
            || self.shutdown_timeout > MAX_SHUTDOWN_TIMEOUT
        {
            return Err(AppServerProcessError::InvalidConfig);
        }
        if self.executable != self.canonical_executable
            || self.home_dir != self.canonical_home_dir
            || self.data_dir != self.canonical_data_dir
            || self.run_dir != self.canonical_run_dir
            || self.log_dir != self.canonical_log_dir
            || self
                .workspace_root
                .as_ref()
                .zip(self.canonical_workspace_root.as_ref())
                .is_some_and(|(configured, canonical)| {
                    fs::canonicalize(configured).ok().as_ref() != Some(canonical)
                })
            || self.workspace_root.is_some() != self.canonical_workspace_root.is_some()
        {
            return Err(AppServerProcessError::InvalidConfig);
        }
        let canonical_executable =
            fs::canonicalize(&self.executable).map_err(|_| AppServerProcessError::InvalidConfig)?;
        let canonical_run_dir =
            fs::canonicalize(&self.run_dir).map_err(|_| AppServerProcessError::InvalidConfig)?;
        let canonical_home_dir =
            fs::canonicalize(&self.home_dir).map_err(|_| AppServerProcessError::InvalidConfig)?;
        let canonical_data_dir =
            fs::canonicalize(&self.data_dir).map_err(|_| AppServerProcessError::InvalidConfig)?;
        let canonical_log_dir =
            fs::canonicalize(&self.log_dir).map_err(|_| AppServerProcessError::InvalidConfig)?;
        if canonical_executable != self.canonical_executable
            || canonical_home_dir != self.canonical_home_dir
            || canonical_data_dir != self.canonical_data_dir
            || canonical_run_dir != self.canonical_run_dir
            || canonical_log_dir != self.canonical_log_dir
            || !canonical_executable.is_file()
            || !canonical_run_dir.is_dir()
        {
            return Err(AppServerProcessError::InvalidConfig);
        }
        self.verify_executable_identity()?;
        if let Some(workspace_root) = &self.workspace_root {
            let canonical_workspace = fs::canonicalize(workspace_root)
                .map_err(|_| AppServerProcessError::InvalidConfig)?;
            if Some(&canonical_workspace) != self.canonical_workspace_root.as_ref()
                || !canonical_workspace.is_dir()
                || canonical_run_dir == canonical_workspace
                || canonical_run_dir.starts_with(&canonical_workspace)
            {
                return Err(AppServerProcessError::InvalidConfig);
            }
        }
        self.limits.validate()?;
        self.restart.validate()?;
        for directory in [&self.home_dir, &self.data_dir, &self.run_dir, &self.log_dir] {
            if !directory.is_absolute() || !directory.is_dir() {
                return Err(AppServerProcessError::InvalidConfig);
            }
        }
        let initialize = protocol::default_initialize_params(&self.limits);
        validate_initialize_params(&initialize, &self.limits)
            .map_err(|_| AppServerProcessError::InvalidConfig)?;
        if self
            .args
            .iter()
            .any(|arg| contains_secret_marker(&arg.to_string_lossy()))
        {
            return Err(AppServerProcessError::InvalidConfig);
        }
        for (name, value) in &self.env {
            let name = name.to_string_lossy();
            if !allowed_env_name(&name)
                || contains_secret_marker(&name)
                || (!matches!(
                    name.as_ref(),
                    "PATH" | "ComSpec" | "PSModuleAnalysisCachePath"
                ) && contains_secret_marker(&value.to_string_lossy()))
            {
                return Err(AppServerProcessError::InvalidConfig);
            }
        }
        Ok(())
    }

    /// 校验并持有 executable identity 直到 spawn，阻止并发替换在配置校验与进程创建
    /// 之间把 sidecar 重定向到另一目标。
    pub(crate) fn verify_executable_identity(&self) -> Result<(), AppServerProcessError> {
        #[cfg(windows)]
        {
            let mut identity = self
                .executable_identity
                .lock()
                .map_err(|_| AppServerProcessError::InvalidConfig)?;
            if let Some(identity) = identity.as_ref() {
                identity
                    .verify(&self.canonical_executable)
                    .map_err(|_| AppServerProcessError::InvalidConfig)?;
            } else {
                *identity = Some(
                    ExecutableIdentity::open(&self.canonical_executable)
                        .map_err(|_| AppServerProcessError::InvalidConfig)?,
                );
            }
        }
        #[cfg(not(windows))]
        {
            // POSIX identity 固定归平台进程 adapter；这里仍重新校验 canonical path，
            // 拒绝常见 symlink 替换窗口。
            let current = fs::canonicalize(&self.executable)
                .map_err(|_| AppServerProcessError::InvalidConfig)?;
            if current != self.canonical_executable || !current.is_file() {
                return Err(AppServerProcessError::InvalidConfig);
            }
        }
        Ok(())
    }
}

#[cfg(windows)]
const FILE_READ_ATTRIBUTES: u32 = 0x0000_0080;
#[cfg(windows)]
const SYNCHRONIZE: u32 = 0x0010_0000;
#[cfg(windows)]
const GENERIC_READ: u32 = 0x8000_0000;
#[cfg(windows)]
const FILE_SHARE_READ: u32 = 0x0000_0001;
#[cfg(windows)]
const OPEN_EXISTING: u32 = 3;
#[cfg(windows)]
const FILE_ATTRIBUTE_NORMAL: u32 = 0x0000_0080;

#[cfg(windows)]
#[repr(C)]
struct ByHandleFileInformation {
    file_attributes: u32,
    creation_time_low: u32,
    creation_time_high: u32,
    last_access_time_low: u32,
    last_access_time_high: u32,
    last_write_time_low: u32,
    last_write_time_high: u32,
    volume_serial_number: u32,
    file_size_high: u32,
    file_size_low: u32,
    number_of_links: u32,
    file_index_high: u32,
    file_index_low: u32,
}

#[cfg(windows)]
#[link(name = "kernel32")]
unsafe extern "system" {
    fn CloseHandle(handle: *mut c_void) -> i32;
    fn CreateFileW(
        name: *const u16,
        desired_access: u32,
        share_mode: u32,
        security_attributes: *const c_void,
        creation_disposition: u32,
        flags_and_attributes: u32,
        template_file: *mut c_void,
    ) -> *mut c_void;
    /// 从已持有句柄读取稳定文件 identity，避免重新按可替换路径打开目标。
    fn GetFileInformationByHandle(
        handle: *mut c_void,
        information: *mut ByHandleFileInformation,
    ) -> i32;
}

#[cfg(windows)]
const INVALID_HANDLE_VALUE: *mut c_void = -1_isize as *mut c_void;

#[cfg(windows)]
#[derive(Debug)]
pub(crate) struct ExecutableIdentity {
    handle: *mut c_void,
    volume_serial_number: u32,
    file_index: u64,
}

#[cfg(windows)]
unsafe impl Send for ExecutableIdentity {}

#[cfg(windows)]
unsafe impl Sync for ExecutableIdentity {}

#[cfg(windows)]
impl ExecutableIdentity {
    /// 以禁止 write/delete sharing 的方式打开句柄，让 Windows 在不可变配置存活期间
    /// 直接拒绝替换目标，而不是依赖易竞态的二次路径检查。
    fn open(path: &std::path::Path) -> std::io::Result<Self> {
        let wide: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        let handle = unsafe {
            CreateFileW(
                wide.as_ptr(),
                GENERIC_READ | FILE_READ_ATTRIBUTES | SYNCHRONIZE,
                FILE_SHARE_READ,
                std::ptr::null(),
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                std::ptr::null_mut(),
            )
        };
        if handle.is_null() || handle == INVALID_HANDLE_VALUE {
            return Err(std::io::Error::last_os_error());
        }
        let mut information = std::mem::MaybeUninit::<ByHandleFileInformation>::uninit();
        let ok = unsafe { GetFileInformationByHandle(handle, information.as_mut_ptr()) } != 0;
        if !ok {
            let error = std::io::Error::last_os_error();
            unsafe {
                CloseHandle(handle);
            }
            return Err(error);
        }
        let information = unsafe { information.assume_init() };
        Ok(Self {
            handle,
            volume_serial_number: information.volume_serial_number,
            file_index: (u64::from(information.file_index_high) << 32)
                | u64::from(information.file_index_low),
        })
    }

    /// 在调用进程创建 API 前重新读取不可变句柄 identity，检测句柄失效或路径替换。
    fn verify(&self, path: &std::path::Path) -> std::io::Result<()> {
        if fs::canonicalize(path)
            .map(|canonical| canonical != path)
            .unwrap_or(true)
        {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "executable identity path changed",
            ));
        }
        let mut information = std::mem::MaybeUninit::<ByHandleFileInformation>::uninit();
        if unsafe { GetFileInformationByHandle(self.handle, information.as_mut_ptr()) } == 0 {
            return Err(std::io::Error::last_os_error());
        }
        let information = unsafe { information.assume_init() };
        let file_index =
            (u64::from(information.file_index_high) << 32) | u64::from(information.file_index_low);
        if information.volume_serial_number != self.volume_serial_number
            || file_index != self.file_index
        {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "executable identity changed",
            ));
        }
        Ok(())
    }
}

#[cfg(windows)]
impl Drop for ExecutableIdentity {
    /// 仅在全部 config clone 释放后关闭 identity guard，保证并行启动共享同一冻结目标。
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.handle);
        }
    }
}
