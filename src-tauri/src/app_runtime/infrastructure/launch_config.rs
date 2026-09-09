// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// 受信任 sidecar 启动策略、目录边界与生命周期映射。

use super::path_policy::is_reparse_point;
use super::recovery_store::acknowledge_manual_recovery;
use crate::app_runtime::{ManualRecoveryConfirmation, RuntimeCommandError, RuntimeStatusKind};
use base64::Engine;
use ja_runtime::app_server_process::{LifecycleState, SidecarConfig};
use std::ffi::{OsStr, OsString};
use std::fs;
use std::io;
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

const TURN_DEADLINE: Duration = Duration::from_secs(4);
const READY_DEADLINE: Duration = Duration::from_secs(10);
const SHUTDOWN_DEADLINE: Duration = Duration::from_secs(15);

/// 启动策略只能来自受信任 composition root，绝不接受 WebView 输入；sidecar 启动后配置与凭据由 Java 独占。
#[derive(Clone)]
pub struct LaunchConfig {
    pub(crate) sidecar: SidecarConfig,
    pub(crate) request_timeout: Duration,
    pub(crate) shutdown_timeout: Duration,
}

impl std::fmt::Debug for LaunchConfig {
    /// 在保留 timeout/revision 上下文的同时，阻止可执行路径、参数、环境变量和配置密钥进入诊断信息。
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("LaunchConfig")
            .field("request_timeout", &self.request_timeout)
            .field("shutdown_timeout", &self.shutdown_timeout)
            .field("sidecar", &self.sidecar)
            .finish()
    }
}

impl LaunchConfig {
    /// 从受信 SidecarConfig 统一施加 bridge deadline；生产装配和 Harness 共用同一策略。
    pub(crate) fn from_sidecar(mut sidecar: SidecarConfig) -> Self {
        sidecar.ready_timeout = READY_DEADLINE;
        sidecar.shutdown_timeout = SHUTDOWN_DEADLINE;
        Self {
            sidecar,
            request_timeout: TURN_DEADLINE,
            shutdown_timeout: SHUTDOWN_DEADLINE,
        }
    }

    /// 确保每次 bridge 等待均有上界，并在创建进程 owner 前校验 sidecar envelope。
    pub fn validate(&self) -> Result<(), RuntimeCommandError> {
        if self.request_timeout.is_zero()
            || self.request_timeout > Duration::from_secs(600)
            || self.shutdown_timeout.is_zero()
            || self.shutdown_timeout > Duration::from_secs(120)
        {
            return Err(RuntimeCommandError::configuration());
        }
        self.sidecar
            .validate()
            .map_err(|_| RuntimeCommandError::configuration())
    }

    /// 提供清除启动恢复门禁的唯一可信路径；调用方必须给出显式且有限的确认，才能删除 marker 并允许后续 bridge 启动。
    pub fn acknowledge_manual_recovery(
        &self,
        confirmation: &ManualRecoveryConfirmation,
    ) -> Result<(), RuntimeCommandError> {
        acknowledge_manual_recovery(&self.sidecar.run_dir, confirmation)
    }
}

/// 构建唯一生产启动形态：使用 Tauri 受信任资源目录下的固定原生资源和固定 app-data 工作目录；禁止通过 PATH 或 JAVA_HOME 搜索资源。
pub fn bundled_launch_config(
    resource_dir: impl AsRef<Path>,
    run_dir: impl Into<PathBuf>,
    java_logs_dir: impl Into<PathBuf>,
) -> Result<LaunchConfig, RuntimeCommandError> {
    let run_dir = run_dir.into();
    bundled_launch_config_with_dirs(
        resource_dir,
        run_dir.parent().unwrap_or(&run_dir).to_path_buf(),
        run_dir.parent().unwrap_or(&run_dir).join("data"),
        run_dir,
        java_logs_dir,
    )
}

/// 以明确的 home/data/run/log 角色构建启动策略；这是唯一组装 Java argv 的生产构造入口，独立保留可直接测试 `data` 与 `run` 不变量。
pub fn bundled_launch_config_with_dirs(
    resource_dir: impl AsRef<Path>,
    home_dir: impl Into<PathBuf>,
    data_dir: impl Into<PathBuf>,
    run_dir: impl Into<PathBuf>,
    java_logs_dir: impl Into<PathBuf>,
) -> Result<LaunchConfig, RuntimeCommandError> {
    let resource_root = fs::canonicalize(resource_dir.as_ref())
        .map_err(|_| RuntimeCommandError::configuration())?;
    if !resource_root.is_dir() {
        return Err(RuntimeCommandError::configuration());
    }
    let staged = resource_root.join(sidecar_resource_name());
    validate_resource_components(&resource_root, &staged)?;
    let executable = fs::canonicalize(&staged).map_err(|_| RuntimeCommandError::configuration())?;
    let expected = resource_root.join(sidecar_resource_name());
    if executable != expected || !executable.starts_with(&resource_root) || !executable.is_file() {
        return Err(RuntimeCommandError::configuration());
    }
    let home_dir = prepare_runtime_directory(home_dir.into())?;
    let data_dir = prepare_runtime_directory(data_dir.into())?;
    let run_dir = selected_runtime_dir(run_dir.into())?;
    fs::create_dir_all(&run_dir).map_err(|_| RuntimeCommandError::configuration())?;
    let run_dir = fs::canonicalize(run_dir).map_err(|_| RuntimeCommandError::configuration())?;
    let java_logs_dir = validated_log_directory(java_logs_dir.into())?;
    let mut sidecar = bounded_sidecar_with_dirs(
        executable,
        home_dir,
        data_dir,
        run_dir,
        java_logs_dir.clone(),
    );
    sidecar.args = vec![
        OsString::from(format!(
            "--home-dir-base64={}",
            encode_directory_argument(&sidecar.home_dir)?
        )),
        OsString::from(format!(
            "--data-dir-base64={}",
            encode_directory_argument(&sidecar.data_dir)?
        )),
        OsString::from(format!(
            "--run-dir-base64={}",
            encode_directory_argument(&sidecar.run_dir)?
        )),
        OsString::from(format!(
            "--log-dir-base64={}",
            encode_directory_argument(&java_logs_dir)?
        )),
    ];
    Ok(LaunchConfig::from_sidecar(sidecar))
}

/// 拒绝每个分段路径组件上的 symlink/reparse 间接跳转，避免只校验最终文件后受信任资源根在父目录与可执行文件之间被重定向。
fn validate_resource_components(
    resource_root: &Path,
    staged: &Path,
) -> Result<(), RuntimeCommandError> {
    let relative = staged
        .strip_prefix(resource_root)
        .map_err(|_| RuntimeCommandError::configuration())?;
    let mut component_path = resource_root.to_path_buf();
    for component in relative.components() {
        if !matches!(component, Component::Normal(_)) {
            return Err(RuntimeCommandError::configuration());
        }
        component_path.push(component);
        let metadata = fs::symlink_metadata(&component_path)
            .map_err(|_| RuntimeCommandError::configuration())?;
        if metadata.file_type().is_symlink() || is_reparse_point(&metadata) {
            return Err(RuntimeCommandError::configuration());
        }
        if component_path != staged && !metadata.is_dir() {
            return Err(RuntimeCommandError::configuration());
        }
        if component_path == staged && !metadata.is_file() {
            return Err(RuntimeCommandError::configuration());
        }
    }
    Ok(())
}

/// 对每个 Java-owned 目录角色应用相同的规范目录校验，同时允许受信任 host 创建首次运行所需目录。
fn bounded_sidecar_with_dirs(
    executable: PathBuf,
    home_dir: PathBuf,
    data_dir: PathBuf,
    run_dir: PathBuf,
    log_dir: PathBuf,
) -> SidecarConfig {
    SidecarConfig::with_directories(executable, home_dir, data_dir, run_dir, log_dir)
}

fn prepare_runtime_directory(path: PathBuf) -> Result<PathBuf, RuntimeCommandError> {
    if !path.is_absolute() || is_filesystem_root(&path) {
        return Err(RuntimeCommandError::configuration());
    }
    fs::create_dir_all(&path).map_err(|_| RuntimeCommandError::configuration())?;
    fs::canonicalize(path).map_err(|_| RuntimeCommandError::configuration())
}

/// 将规范化 host-owned 目录编码为 URL-safe ASCII，避免 Windows 原生 argv 通过有损进程边界传递 data 或 log 路径。
pub(crate) fn encode_directory_argument(path: &Path) -> Result<String, RuntimeCommandError> {
    let utf8 = path
        .to_str()
        .ok_or_else(RuntimeCommandError::configuration)?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(utf8.as_bytes()))
}

/// 构建启动参数时重新校验固定 Java log 目录，防止既有 alias 将 sidecar 诊断重定向到 host 创建的 `~/.ja/logs/java` 边界之外。
fn validated_log_directory(path: PathBuf) -> Result<PathBuf, RuntimeCommandError> {
    if !path.is_absolute() || is_filesystem_root(&path) {
        return Err(RuntimeCommandError::configuration());
    }
    validate_runtime_path_components(&path)?;
    let canonical = fs::canonicalize(path).map_err(|_| RuntimeCommandError::configuration())?;
    let metadata =
        fs::symlink_metadata(&canonical).map_err(|_| RuntimeCommandError::configuration())?;
    if !canonical.is_absolute()
        || is_filesystem_root(&canonical)
        || metadata.file_type().is_symlink()
        || is_reparse_point(&metadata)
        || !metadata.is_dir()
    {
        return Err(RuntimeCommandError::configuration());
    }
    Ok(canonical)
}

/// 创建 app-data runtime 目录并设置平台可用的最小权限；Windows 上仍以 Tauri app-data 根目录作为安全边界。
pub fn prepare_run_dir(run_dir: impl AsRef<Path>) -> Result<PathBuf, RuntimeCommandError> {
    // Tauri 的 Windows Known Folder 查找可能忽略桌面 smoke test 提供的 APPDATA/LOCALAPPDATA。
    // 因此必须在创建任何内容前解析仅用于 debug 的最终 runtime 目录，避免测试误写开发者真实 app-data 目录。
    let run_dir = selected_runtime_dir(run_dir.as_ref().to_path_buf())?;
    fs::create_dir_all(&run_dir).map_err(|_| RuntimeCommandError::configuration())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&run_dir, fs::Permissions::from_mode(0o700))
            .map_err(|_| RuntimeCommandError::configuration())?;
    }
    fs::canonicalize(run_dir).map_err(|_| RuntimeCommandError::configuration())
}

/// 在受信任原生 composition 边界一次性选择 runtime 目录；`JA_E2E_RUNTIME_ROOT` 是传给 Java 的最终目录而非父目录，release 构建会完全移除该 hook。
fn selected_runtime_dir(default_root: PathBuf) -> Result<PathBuf, RuntimeCommandError> {
    resolve_runtime_root(default_root, debug_runtime_root_override().as_deref())
}

/// 解析 host 控制的 debug 目录并维持明确且收敛的文件系统边界：路径必须绝对、不得包含遍历组件、不得穿越 symlink/reparse，且最终必须是规范目录而非文件系统根或文件。
pub(crate) fn resolve_runtime_root(
    default_root: PathBuf,
    override_root: Option<&OsStr>,
) -> Result<PathBuf, RuntimeCommandError> {
    let Some(raw_override) = override_root else {
        return Ok(default_root);
    };
    let requested = PathBuf::from(raw_override);
    if !requested.is_absolute() || is_filesystem_root(&requested) {
        return Err(RuntimeCommandError::configuration());
    }
    validate_runtime_path_components(&requested)?;
    match fs::symlink_metadata(&requested) {
        Ok(metadata) if metadata.file_type().is_symlink() || is_reparse_point(&metadata) => {
            return Err(RuntimeCommandError::configuration());
        }
        Ok(metadata) if !metadata.is_dir() => {
            return Err(RuntimeCommandError::configuration());
        }
        Ok(_) => {}
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(_) => return Err(RuntimeCommandError::configuration()),
    }
    fs::create_dir_all(&requested).map_err(|_| RuntimeCommandError::configuration())?;
    let canonical =
        fs::canonicalize(&requested).map_err(|_| RuntimeCommandError::configuration())?;
    if !canonical.is_absolute() || is_filesystem_root(&canonical) {
        return Err(RuntimeCommandError::configuration());
    }
    let metadata =
        fs::symlink_metadata(&canonical).map_err(|_| RuntimeCommandError::configuration())?;
    if metadata.file_type().is_symlink() || is_reparse_point(&metadata) || !metadata.is_dir() {
        return Err(RuntimeCommandError::configuration());
    }
    Ok(canonical)
}

/// 创建目录前检查每个既有组件，防止 E2E root 通过既有 symlink/junction 或 `..` 片段逃逸。
fn validate_runtime_path_components(path: &Path) -> Result<(), RuntimeCommandError> {
    let mut current = PathBuf::new();
    for component in path.components() {
        if matches!(component, Component::CurDir | Component::ParentDir) {
            return Err(RuntimeCommandError::configuration());
        }
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.file_type().is_symlink() || is_reparse_point(&metadata) => {
                return Err(RuntimeCommandError::configuration());
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => {}
            Err(_) => return Err(RuntimeCommandError::configuration()),
        }
    }
    Ok(())
}

/// 拒绝将驱动器或根目录作为可变 runtime 状态目标，避免测试或进程获得过宽写入边界。
fn is_filesystem_root(path: &Path) -> bool {
    path.parent().is_none() || path.parent() == Some(path)
}

/// 仅在 debug 构建读取 E2E runtime hook；泛型查找让 release cfg 保证可直接单元测试，无需在并行测试中修改进程环境变量。
#[cfg(debug_assertions)]
fn debug_runtime_root_override() -> Option<OsString> {
    runtime_root_override_from(|name| std::env::var_os(name))
}

/// release 二进制有意不提供环境变量控制的 runtime 路径，避免生产边界受外部输入影响。
#[cfg(not(debug_assertions))]
fn debug_runtime_root_override() -> Option<OsString> {
    None
}

#[cfg(debug_assertions)]
/// 让 debug seam 的环境查找可注入，使 cfg 行为测试不会与其他 Rust 测试竞争进程环境。
pub(crate) fn runtime_root_override_from<F>(lookup: F) -> Option<OsString>
where
    F: FnOnce(&str) -> Option<OsString>,
{
    lookup("JA_E2E_RUNTIME_ROOT")
}

#[cfg(debug_assertions)]
impl LaunchConfig {
    /// 仅允许 host 控制的 debug 环境选择 Java 25 与本地 jar；home/data 必须由同一个
    /// HomeLayout 显式传入，避免 debug 启动从 run 目录另行派生出第二套配置根目录。
    pub fn debug_java(
        java: PathBuf,
        jar: PathBuf,
        home_dir: PathBuf,
        data_dir: PathBuf,
        run_dir: PathBuf,
        java_logs_dir: PathBuf,
    ) -> Result<Self, RuntimeCommandError> {
        if !java.is_absolute() || !java.is_file() || !jar.is_absolute() || !jar.is_file() {
            return Err(RuntimeCommandError::configuration());
        }
        let home_dir = prepare_runtime_directory(home_dir)?;
        let data_dir = prepare_runtime_directory(data_dir)?;
        let run_dir = selected_runtime_dir(run_dir)?;
        fs::create_dir_all(&run_dir).map_err(|_| RuntimeCommandError::configuration())?;
        let run_dir =
            fs::canonicalize(run_dir).map_err(|_| RuntimeCommandError::configuration())?;
        let java_logs_dir = validated_log_directory(java_logs_dir)?;
        let mut sidecar =
            bounded_sidecar_with_dirs(java, home_dir, data_dir, run_dir, java_logs_dir.clone());
        sidecar.args = vec![
            OsString::from("-jar"),
            jar.into_os_string(),
            OsString::from(format!(
                "--home-dir-base64={}",
                encode_directory_argument(&sidecar.home_dir)?
            )),
            OsString::from(format!(
                "--data-dir-base64={}",
                encode_directory_argument(&sidecar.data_dir)?
            )),
            OsString::from(format!(
                "--run-dir-base64={}",
                encode_directory_argument(&sidecar.run_dir)?
            )),
            OsString::from(format!(
                "--log-dir-base64={}",
                encode_directory_argument(&java_logs_dir)?
            )),
        ];
        let config = Self::from_sidecar(sidecar);
        config.validate()?;
        Ok(config)
    }
}

/// 按编译目标返回唯一 sidecar 资源名，避免运行时猜测架构或搜索旧文件名。
#[cfg(all(windows, target_arch = "x86_64"))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-x86_64-pc-windows-msvc.exe"
}

/// Windows ARM64 只接受对应原生资源，不能回退到 x86_64 或 PATH 中的可执行文件。
#[cfg(all(windows, target_arch = "aarch64"))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-aarch64-pc-windows-msvc.exe"
}

/// 未支持的 Windows 架构返回确定的缺失资源名，使组合阶段稳定失败而非误启动。
#[cfg(all(windows, not(any(target_arch = "x86_64", target_arch = "aarch64"))))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-unsupported-target.exe"
}

/// macOS x86_64 固定绑定同架构资源，签名和打包清单因此只有一个事实来源。
#[cfg(all(target_os = "macos", target_arch = "x86_64"))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-x86_64-apple-darwin"
}

/// macOS ARM64 固定绑定 Apple Silicon 资源，禁止 Rosetta 路径成为隐式兼容层。
#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-aarch64-apple-darwin"
}

/// Linux x86_64 使用唯一 GNU 资源名，运行时不根据宿主目录内容选择变体。
#[cfg(all(not(windows), not(target_os = "macos"), target_arch = "x86_64"))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-x86_64-unknown-linux-gnu"
}

/// 未支持的 Unix 架构返回确定占位名，使资源校验 fail closed 并给出稳定配置错误。
#[cfg(all(not(windows), not(target_os = "macos"), not(target_arch = "x86_64")))]
pub(crate) fn sidecar_resource_name() -> &'static str {
    "sidecars/ja-app-server-unsupported-target"
}
impl RuntimeStatusKind {
    /// 将冻结的生命周期事实映射为 UI-safe 有限状态，不扩展协议状态空间。
    pub(crate) fn from_lifecycle(state: LifecycleState) -> Self {
        match state {
            LifecycleState::Starting => Self::Starting,
            LifecycleState::Ready => Self::Ready,
            LifecycleState::Busy => Self::Busy,
            LifecycleState::Stopping => Self::Stopping,
            LifecycleState::Exited | LifecycleState::Backoff => Self::Stopped,
            LifecycleState::Incompatible => Self::Incompatible,
            LifecycleState::Faulted => Self::Faulted,
        }
    }
}
